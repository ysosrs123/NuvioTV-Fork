package com.nuvio.tv.ui.screens.player

import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.data.local.FrameRateMatchingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * AFR preflight probe budgets.
 *
 * Keep user-facing wait short. Cold debrid/CDN TTFB is handled by a tiny Range warmup
 * inside the OkHttp probe (not by inflating these ceilings). NextLib/extractor only use
 * whatever remains of the total deadline after OkHttp.
 */
internal const val AFR_PREFLIGHT_OKHTTP_TIMEOUT_MS = 15_000L
internal const val AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS = 10_000L
internal const val AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS = 5_000L
internal const val AFR_PREFLIGHT_TOTAL_TIMEOUT_MS = 18_000L
/** Minimum remaining time to attempt NextLib / extractor after OkHttp. */
internal const val AFR_PREFLIGHT_MIN_STAGE_MS = 2_000L

/**
 * ExoPlayer engine path: cache-only preflight.
 *
 * Applies a display-mode switch before prepare() ONLY when a cached detection
 * exists (instant). No NextLib probe, no MediaExtractor probe: on a cache miss
 * the frame rate comes from ExoPlayer's own track format after prepare (see
 * PlayerRuntimeControllerAfrTrack.kt), so the blocking-native-probe hang on
 * non-faststart MP4s is structurally impossible on this path. The full probing
 * preflight below remains in use for the MPV engine only.
 */
/**
 * Derives a video frame rate from the prewarm's already-held head bytes
 * (MKV only). Returns a provisional FrameRateDetection and records the seeded
 * raw rate on the controller so the track-format path can validate it against
 * ExoPlayer's reported rate after prepare (see maybeRunTrackFormatAfr). Gated
 * on ladder proximity: a rate that is not close to a standard rate is not
 * trusted to switch the panel, so it is left to the post-prepare path. The
 * entry is provisional only -- it is NOT written to the persistent cache here;
 * the track-format path promotes it after validation.
 */
private fun PlayerRuntimeController.seedFrameRateFromPrewarmedHead(url: String, headers: Map<String, String>): FrameRateUtils.FrameRateDetection? {
    val head = com.nuvio.tv.ui.screens.player.PrefetchWindowStore.peekHead(
        android.net.Uri.parse(url), PlaybackPrewarmIdentity.from(url, headers, mapOf("Icy-MetaData" to "1"))
    )
        ?: return null
    val hint = com.nuvio.tv.core.player.MatroskaAfrProbe.parseVideoFrameRateFromHead(head) ?: return null
    if (!FrameRateUtils.isNearStandardRate(hint.rawFps)) {
        Log.d(PlayerRuntimeController.TAG, "AFR seed rejected: fps=${hint.rawFps} not near a standard rate")
        return null
    }
    val snapped = FrameRateUtils.snapToStandardRate(hint.rawFps)
    afrSeededRateRaw = hint.rawFps
    Log.i(PlayerRuntimeController.TAG, "AFR seed: fps=${hint.rawFps} snapped=$snapped ${hint.width}x${hint.height} (provisional, from prewarm head)")
    return FrameRateUtils.FrameRateDetection(
        raw = hint.rawFps,
        snapped = snapped,
        videoWidth = hint.width,
        videoHeight = hint.height
    )
}

internal suspend fun PlayerRuntimeController.runAfrCachePreflightIfEnabled(
    url: String,
    headers: Map<String, String>,
    frameRateMatchingMode: FrameRateMatchingMode,
    resolutionMatchingEnabled: Boolean
) {
    mpvDelayStartAfterAfrSwitch = false
    exoDelayStartAfterAfrSwitch = false

    if (frameRateMatchingMode == FrameRateMatchingMode.OFF) {
        _uiState.update {
            it.copy(
                detectedFrameRateRaw = 0f,
                detectedFrameRate = 0f,
                detectedFrameRateSource = null,
                afrProbeRunning = false
            )
        }
        return
    }

    val activity = currentHostActivity()
    if (activity == null) {
        Log.w(PlayerRuntimeController.TAG, "AFR cache preflight skipped: host activity unavailable")
        return
    }

    if (_uiState.value.afrProbeRunning || _uiState.value.detectedFrameRateSource != null) {
        Log.d(PlayerRuntimeController.TAG, "AFR cache preflight: already running or completed, skipping")
        return
    }

    // Keyed with the filename, as the cache writers key it, so their entries
    // are visible here. Falls back to the URL-based key when no filename is
    // known, the same rule the writers use, so the two sides never disagree.
    val cached = FrameRateUtils.getCachedFrameRate(url, headers, currentFilename)
        ?: seedFrameRateFromPrewarmedHead(url, headers)
        ?: run {
            Log.d(PlayerRuntimeController.TAG, "AFR cache preflight: miss; deferring to track-format AFR after prepare")
            return
        }

    Log.d(PlayerRuntimeController.TAG, "AFR cache preflight: cache hit! Using cached FPS=${cached.snapped}")
    _uiState.update {
        it.copy(
            detectedFrameRateRaw = cached.raw,
            detectedFrameRate = cached.snapped,
            detectedFrameRateSource = FrameRateSource.PROBE
        )
    }
    val prefer23976ProbeBias = cached.raw in 23.95f..23.999f
    val targetFrameRate = FrameRateUtils.refineFrameRateForDisplay(
        activity = activity,
        detectedFps = cached.snapped,
        prefer23976Near24 = prefer23976ProbeBias
    )
    val initialDisplayModeId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        withContext(Dispatchers.Main) {
            activity.window?.decorView?.display?.mode?.modeId
        }
    } else {
        null
    }

    val result = FrameRateUtils.matchFrameRateAndWait(
        activity = activity,
        frameRate = targetFrameRate,
        videoWidth = cached.videoWidth,
        videoHeight = cached.videoHeight,
        resolutionMatchingEnabled = resolutionMatchingEnabled
    )

    if (result != null) {
        val switchedDisplayMode = initialDisplayModeId != null &&
            initialDisplayModeId != result.appliedMode.modeId
        mpvDelayStartAfterAfrSwitch = switchedDisplayMode
        exoDelayStartAfterAfrSwitch = switchedDisplayMode
        // Track-format AFR stands down when the cache path already ran a
        // mode selection for this stream (whether or not the mode changed).
        afrModeAppliedPreStart = true

        _uiState.update {
            it.copy(
                displayModeInfo = DisplayModeInfo(
                    width = result.appliedMode.physicalWidth,
                    height = result.appliedMode.physicalHeight,
                    refreshRate = result.appliedMode.refreshRate
                ),
                showDisplayModeInfo = true
            )
        }
    }
}

internal suspend fun PlayerRuntimeController.runAfrPreflightIfEnabled(
    url: String,
    headers: Map<String, String>,
    frameRateMatchingMode: FrameRateMatchingMode,
    resolutionMatchingEnabled: Boolean,
    mimeType: String? = null
) {
    mpvDelayStartAfterAfrSwitch = false
    exoDelayStartAfterAfrSwitch = false

    if (frameRateMatchingMode == FrameRateMatchingMode.OFF) {
        _uiState.update {
            it.copy(
                detectedFrameRateRaw = 0f,
                detectedFrameRate = 0f,
                detectedFrameRateSource = null,
                afrProbeRunning = false
            )
        }
        return
    }

    val activity = currentHostActivity()
    if (activity == null) {
        Log.w(PlayerRuntimeController.TAG, "AFR preflight skipped: host activity unavailable")
        return
    }

    if (_uiState.value.afrProbeRunning || _uiState.value.detectedFrameRateSource != null) {
        Log.d(PlayerRuntimeController.TAG, "AFR preflight: already running or completed, skipping duplicate execution")
        return
    }

    _uiState.update {
        it.copy(
            detectedFrameRateRaw = 0f,
            detectedFrameRate = 0f,
            detectedFrameRateSource = null,
            afrProbeRunning = true
        )
    }

    // Original stream headers (without Range) – used for NextLib bypass decision.
    // If these contain auth/custom headers, NextLib is skipped (MediaInfoBuilder cannot forward them).
    val streamHeaders = FrameRateUtils.streamHeadersForAfrProbe(headers)
    // Extractor fallback headers – add Connection: close for proper connection teardown.
    val probeHeaders = FrameRateUtils.extractorProbeHeaders(headers)
    val effectiveMimeType = mimeType ?: currentStreamMimeType
    val filename = currentFilename
    val deadlineElapsedRealtime = SystemClock.elapsedRealtime() + AFR_PREFLIGHT_TOTAL_TIMEOUT_MS

    fun remainingMs(): Long =
        (deadlineElapsedRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    try {
        val cached = FrameRateUtils.getCachedFrameRate(url, headers, filename)
        if (cached != null) {
            Log.d(PlayerRuntimeController.TAG, "AFR preflight: cache hit! Using cached FPS=${cached.snapped}")
            _uiState.update {
                it.copy(
                    detectedFrameRateRaw = cached.raw,
                    detectedFrameRate = cached.snapped,
                    detectedFrameRateSource = FrameRateSource.PROBE
                )
            }
            val prefer23976ProbeBias = cached.raw in 23.95f..23.999f
            val targetFrameRate = FrameRateUtils.refineFrameRateForDisplay(
                activity = activity,
                detectedFps = cached.snapped,
                prefer23976Near24 = prefer23976ProbeBias
            )
            val initialDisplayModeId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                withContext(Dispatchers.Main) {
                    activity.window?.decorView?.display?.mode?.modeId
                }
            } else {
                null
            }

            val result = FrameRateUtils.matchFrameRateAndWait(
                activity = activity,
                frameRate = targetFrameRate,
                videoWidth = cached.videoWidth,
                videoHeight = cached.videoHeight,
                resolutionMatchingEnabled = resolutionMatchingEnabled
            )

            if (result != null) {
                val switchedDisplayMode = initialDisplayModeId != null &&
                    initialDisplayModeId != result.appliedMode.modeId
                mpvDelayStartAfterAfrSwitch = switchedDisplayMode
                exoDelayStartAfterAfrSwitch = switchedDisplayMode

                _uiState.update {
                    it.copy(
                        displayModeInfo = DisplayModeInfo(
                            width = result.appliedMode.physicalWidth,
                            height = result.appliedMode.physicalHeight,
                            refreshRate = result.appliedMode.refreshRate
                        ),
                        showDisplayModeInfo = true
                    )
                }
            }
            return
        }

        val okHttpBudget = minOf(AFR_PREFLIGHT_OKHTTP_TIMEOUT_MS, remainingMs())
        val okHttpDetection = if (okHttpBudget >= AFR_PREFLIGHT_MIN_STAGE_MS) {
            withTimeoutOrNull(okHttpBudget) {
                withContext(Dispatchers.IO) {
                    // Blocking OkHttp calls ignore coroutine cancellation; hand the probe a
                    // signal tied to this job so timeout/cancel actually stops the downloads.
                    val probeJob = coroutineContext[Job]
                    FrameRateUtils.detectFrameRateWithOkHttpProbe(
                        context = context,
                        sourceUrl = url,
                        headers = streamHeaders,
                        mimeType = effectiveMimeType,
                        filename = filename,
                        isCancelled = { probeJob?.isActive != true }
                    )
                }
            }
        } else {
            null
        }

        val detection = if (okHttpDetection != null) {
            Log.d(PlayerRuntimeController.TAG, "AFR preflight: OkHttp probe succeeded! FPS=${okHttpDetection.snapped}")
            okHttpDetection
        } else {
            val nextLibBudget = minOf(AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS, remainingMs())
            val nextLibDetection = if (nextLibBudget >= AFR_PREFLIGHT_MIN_STAGE_MS) {
                withTimeoutOrNull(nextLibBudget) {
                    withContext(Dispatchers.IO) {
                        FrameRateUtils.detectFrameRateFromNextLib(
                            context = context,
                            sourceUrl = url,
                            headers = streamHeaders,
                            mimeType = effectiveMimeType,
                            filename = filename
                        )
                    }
                }
            } else {
                null
            }
            if (nextLibDetection != null) {
                nextLibDetection
            } else {
                val fallbackBudget = minOf(AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS, remainingMs())
                if (fallbackBudget < AFR_PREFLIGHT_MIN_STAGE_MS) {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "AFR preflight: no time left for extractor fallback (remaining=${remainingMs()}ms)"
                    )
                    null
                } else {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "AFR preflight NextLib probe failed/timed out; trying extractor fallback (${fallbackBudget}ms)"
                    )
                    withTimeoutOrNull(fallbackBudget) {
                        withContext(Dispatchers.IO) {
                            FrameRateUtils.detectFrameRateFromExtractor(
                                context = context,
                                sourceUrl = url,
                                headers = probeHeaders
                            )
                        }
                    }
                }
            }
        }

        if (detection == null) {
            Log.w(
                PlayerRuntimeController.TAG,
                "AFR preflight probe timed out/failed (OkHttp + NextLib + extractor fallback)"
            )
            return
        }

        FrameRateUtils.cacheFrameRate(url, headers, detection, currentFilename)

        _uiState.update {
            it.copy(
                detectedFrameRateRaw = detection.raw,
                detectedFrameRate = detection.snapped,
                detectedFrameRateSource = FrameRateSource.PROBE
            )
        }

        val prefer23976ProbeBias = detection.raw in 23.95f..23.999f
        val targetFrameRate = FrameRateUtils.refineFrameRateForDisplay(
            activity = activity,
            detectedFps = detection.snapped,
            prefer23976Near24 = prefer23976ProbeBias
        )
        val initialDisplayModeId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            withContext(Dispatchers.Main) {
                activity.window?.decorView?.display?.mode?.modeId
            }
        } else {
            null
        }

        val result = FrameRateUtils.matchFrameRateAndWait(
            activity = activity,
            frameRate = targetFrameRate,
            videoWidth = detection.videoWidth,
            videoHeight = detection.videoHeight,
            resolutionMatchingEnabled = resolutionMatchingEnabled
        )

        if (result != null) {
            val switchedDisplayMode = initialDisplayModeId != null &&
                initialDisplayModeId != result.appliedMode.modeId
            mpvDelayStartAfterAfrSwitch = switchedDisplayMode
            exoDelayStartAfterAfrSwitch = switchedDisplayMode

            _uiState.update {
                it.copy(
                    displayModeInfo = DisplayModeInfo(
                        width = result.appliedMode.physicalWidth,
                        height = result.appliedMode.physicalHeight,
                        refreshRate = result.appliedMode.refreshRate
                    ),
                    showDisplayModeInfo = true
                )
            }
        }
    } finally {
        withContext(NonCancellable) {
            _uiState.update { it.copy(afrProbeRunning = false) }
        }
    }
}
