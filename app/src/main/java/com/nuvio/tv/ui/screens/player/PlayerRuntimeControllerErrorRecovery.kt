package com.nuvio.tv.ui.screens.player
import com.nuvio.tv.data.local.InternalPlayerEngine

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_STARTUP_AUTO_RETRIES = 2
private const val MAX_AUTO_RETRIES = 2
private const val MAX_DEAD_SOURCE_FAILOVERS = 3
private const val FAILOVER_SOURCE_LIST_WAIT_MS = 20_000L
private const val FAILOVER_SOURCE_LIST_POLL_MS = 250L

// Ceiling on TOTAL automatic recoveries for one stream URL, across
// every fallback ladder combined (DV modes, safe audio, PCM, timeout, NPE, 416,
// engine failover, dead-source, auto-retry). Each ladder is individually
// bounded, but chained they can loop a dead decoder pipeline for minutes
// (about 40 s per cycle on a large moov-at-tail MP4). Five covers the
// deepest legitimate chain while bounding the pathological case.
private const val MAX_TOTAL_AUTO_RECOVERIES_PER_STREAM = 5
private const val RETRY_DELAY_MS = 1_500L
private const val STABLE_PROGRESS_RESET_DELAY_MS = 5_000L
private const val VC1_DECODER_RETRY_DELAY_MS = 1_500L

internal fun PlayerRuntimeController.showRecoveryOverlay() {
    _uiState.update { state ->
        state.copy(
            error = null,
            isBuffering = true,
            showLoadingOverlay = true,
            loadingMessage = context.getString(R.string.player_loading_buffering),
            showPauseOverlay = false
        )
    }
}

internal fun PlayerRuntimeController.attemptStartupRecovery(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (currentVideoTrackIsLikelyVc1 ||
        Vc1VideoFormatHeuristics.isLikelyVc1(streamName = _uiState.value.currentStreamName ?: streamName)
    ) {
        return false
    }
    if (hasRenderedFirstFrame) return false
    if (!isRetryablePlaybackError(error)) return false
    if (startupRetryCount >= MAX_STARTUP_AUTO_RETRIES) return false

    val paused = userPausedManually
    val attempt = startupRetryCount
    startupRetryCount++

    Log.w(
        PlayerRuntimeController.TAG,
        "Startup recovery ${attempt + 1}/$MAX_STARTUP_AUTO_RETRIES after ${RETRY_DELAY_MS}ms for: $detailedError"
    )

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                isBuffering = true,
                showLoadingOverlay = it.loadingOverlayEnabled,
                loadingMessage = context.getString(R.string.player_loading_buffering),
                showPauseOverlay = false
            )
        }

        delay(RETRY_DELAY_MS)

        releasePlayer(flushPlaybackState = false)
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }
    return true
}

/**
 * Determines whether the given [PlaybackException] is transient and worth retrying.
 *
 * Retryable errors include source/IO errors, parsing glitches, and unexpected runtime
 * exceptions that commonly occur after pause/resume or seek on flaky streams.
 * Decoder-init and DRM errors are considered fatal.
 */
internal fun isRetryablePlaybackError(error: PlaybackException): Boolean {
    return when (error.errorCode) {
        // --- Source / IO errors (the 2xxx range) ---
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, -> true

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val httpCause = error.findCauseOfType<HttpDataSource.InvalidResponseCodeException>()
            if (httpCause != null) {
                val code = httpCause.responseCode
                !(code == 400 || code == 401 || code == 403 || code == 404 || code == 410)
            } else {
                true
            }
        }
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,

        // --- Decoder errors (often transient after pause/resume on some hardware) ---
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true

        // --- Behind-the-scenes / unexpected errors (often IllegalStateException / NPE) ---
        PlaybackException.ERROR_CODE_UNSPECIFIED -> {
            val cause = error.cause
            cause is IllegalStateException || cause is NullPointerException
        }

        else -> false
    }
}

/**
 * Audio-track failures that the safe-audio → audio-disabled fallback ladder can recover from.
 *
 * - [PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED] (5001): the AudioTrack could not be
 *   created (e.g. the requested passthrough/offload encoding is not actually accepted by the sink).
 * - [PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED] (5002): a write to the AudioTrack
 *   failed, most commonly with `AudioTrack.ERROR_DEAD_OBJECT` (-6) when an HDMI/audio-route
 *   renegotiation invalidates an E-AC-3/AC-3 passthrough or offload track mid-playback.
 *
 * Both are remedied by re-selecting audio with tunneling/passthrough off and the channel count
 * constrained to the device's capabilities (safe-audio mode), or by dropping audio entirely — so
 * a write failure must take the same recovery path as an init failure rather than landing on the
 * fatal error screen.
 *
 * [combinedMessage] is the concatenated exception/cause messages; the string checks are a safety
 * net for devices that surface the same failure under a generic error code.
 */
internal fun isAudioTrackFailure(errorCode: Int, combinedMessage: String): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return true
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) return true
    return combinedMessage.contains("audiotrack init failed", ignoreCase = true) ||
        combinedMessage.contains("audiotrack write failed", ignoreCase = true)
}

internal fun isStuckBufferingWatchdog(errorCode: Int, combinedMessage: String): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK) return false
    return combinedMessage.contains("stuck buffering and not loading", ignoreCase = true)
}

internal fun httpStatusExplanation(context: android.content.Context, code: Int): String {
    return when (code) {
        401, 410 -> context.getString(com.nuvio.tv.R.string.player_error_stream_expired)
        404 -> context.getString(com.nuvio.tv.R.string.player_error_stream_removed)
        429 -> context.getString(com.nuvio.tv.R.string.player_error_stream_rate_limited)
        in 500..599 -> context.getString(com.nuvio.tv.R.string.player_error_stream_unavailable)
        in 400..499 -> context.getString(com.nuvio.tv.R.string.player_error_stream_blocked)
        else -> ""
    }
}

internal fun PlaybackException.findInvalidResponseCodeException(): HttpDataSource.InvalidResponseCodeException? {
    var current: Throwable? = cause
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current
        current = current.cause
    }
    return null
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlaybackException.toDisplayMessage(context: android.content.Context): String {
    val responseException = findInvalidResponseCodeException()
    if (responseException != null) {
        val code = responseException.responseCode
        val statusText = responseException.responseMessage?.takeIf { it.isNotBlank() }
        val providerHint = httpStatusExplanation(context, code)
        return buildString {
            append("HTTP $code")
            statusText?.let { append(" $it") }
            append(" [$errorCodeName]")
            append(providerHint)
        }
    }

    // Check for unrecognized format (provider returned non-video content)
    val isUnrecognizedFormat = findCauseOfType<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null
    if (isUnrecognizedFormat) {
        return context.getString(com.nuvio.tv.R.string.player_error_source_invalid_content, errorCodeName)
    }

    val decoderInit = findCauseOfType<MediaCodecRenderer.DecoderInitializationException>()
    if (decoderInit != null) {
        val decoderMessage = decoderInit.message?.trim()?.takeIf { it.isNotBlank() }
            ?: decoderInit.diagnosticInfo?.trim()?.takeIf { it.isNotBlank() }
        return if (decoderMessage != null) {
            "$decoderMessage [$errorCodeName]"
        } else {
            errorCodeName
        }
    }

    val meaningfulMessage = findMostRelevantCauseMessage() ?: cause?.message ?: message
    return if (meaningfulMessage != null) {
        "$meaningfulMessage [$errorCodeName]"
    } else {
        errorCodeName
    }
}

private inline fun <reified T : Throwable> Throwable.findCauseOfType(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

internal fun Throwable.toDisplayMessage(context: android.content.Context, fallback: String? = null): String {
    val meaningfulMessage = findMostRelevantCauseMessage()
    return meaningfulMessage
        ?: message?.takeIf { it.isNotBlank() }
        ?: fallback
        ?: context.getString(com.nuvio.tv.R.string.player_error_playback_fallback)
}

private fun Throwable.findMostRelevantCauseMessage(): String? {
    val candidates = buildList {
        var current: Throwable? = this@findMostRelevantCauseMessage
        while (current != null) {
            current.message
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.equals("Playback error", ignoreCase = true) &&
                        !it.equals("Source error", ignoreCase = true) &&
                        !it.equals("Unexpected runtime error", ignoreCase = true)
                }
                ?.let(::add)
            current = current.cause
        }
    }
    return candidates.firstOrNull()
}

/**
 * Attempts an automatic retry of the current stream, preserving the playback position.
 *
 * The first retry re-prepares the current player, and the second retry fully rebuilds it,
 * so recovery stays on the loading overlay until playback succeeds or finally fails.
 *
 * Returns `true` if a retry was scheduled, `false` if the error should be shown to the user.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.attemptAutoRetry(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (currentVideoTrackIsLikelyVc1 ||
        Vc1VideoFormatHeuristics.isLikelyVc1(streamName = _uiState.value.currentStreamName ?: streamName)
    ) {
        return false
    }
    if (!isRetryablePlaybackError(error)) return false
    // Dead URLs (non-media body, 404/410) never benefit from same-URL retries;
    // they are handled by attemptDeadSourceFailover before this is reached.
    if (isDeadSourcePlaybackError(error)) return false
    if (errorRetryCount >= MAX_AUTO_RETRIES) return false

    val paused = userPausedManually
    val attempt = errorRetryCount
    errorRetryCount++

    Log.w(
        PlayerRuntimeController.TAG,
        "Auto-retry ${attempt + 1}/$MAX_AUTO_RETRIES after ${RETRY_DELAY_MS}ms for: $detailedError"
    )

    // Capture the current position so we can resume after re-init.
    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L
    val isFirstAttempt = attempt == 0

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                showLoadingOverlay = if (isFirstAttempt) false else it.loadingOverlayEnabled,
                showPauseOverlay = false
            )
        }

        delay(RETRY_DELAY_MS)

        if (isFirstAttempt) {
            // Lightweight recovery: re-prepare the same source without destroying the player.
            val player = _exoPlayer
            if (player != null) {
                if (savedPosition > 0L) {
                    player.seekTo((savedPosition - 1).coerceAtLeast(0L))
                }
                player.prepare()
                // Only resume playback if the user hadn't paused.
                player.playWhenReady = !paused
            } else {
                releasePlayer(flushPlaybackState = false)
                if (savedPosition > 0L) {
                    _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
                }
                initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
            }
        } else {
            // Full teardown — clears any corrupt decoder/internal state.
            releasePlayer(flushPlaybackState = false)
            if (savedPosition > 0L) {
                _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
            }
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
        }
    }
    return true
}

internal fun PlayerRuntimeController.markAudioPcmFallbackTried() {
    hasTriedAudioPcmFallback = true
    pendingAudioPcmFallbackRebuild = true
}

/**
 * Resets the retry counter. Call this whenever playback enters a healthy state
 * (first frame rendered, or user-initiated retry). The automatic source-switch
 * count is not part of it: that only restarts on a manual source pick, an
 * episode change or a manual retry.
 */
internal fun PlayerRuntimeController.resetErrorRetryState() {
    startupRetryCount = 0
    errorRetryCount = 0
    hasRetriedAfterMimeOverrideClear = false
    parsingErrorProbeAttempted = false
    pendingAudioPcmFallbackRebuild = false
    errorRetryJob?.cancel()
    errorRetryJob = null
}

internal fun PlayerRuntimeController.scheduleStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = scope.launch {
        val playingSinceMs = android.os.SystemClock.elapsedRealtime()
        delay(STABLE_PROGRESS_RESET_DELAY_MS)
        val player = _exoPlayer ?: return@launch
        if (player.playbackState == Player.STATE_READY && player.isPlaying) {
            resetErrorRetryState()
            delay(PlaybackRecoveryGate.AUDIO_RETRY_STABLE_RESET_MS - STABLE_PROGRESS_RESET_DELAY_MS)
            if (_exoPlayer === player && player.playbackState == Player.STATE_READY && player.isPlaying &&
                playbackRecoveryGate.noteStablePlayback(playingSinceMs, android.os.SystemClock.elapsedRealtime())
            ) {
                Log.i(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY_RESET after stable playback")
            }
        }
    }
}

internal fun PlayerRuntimeController.cancelStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = null
}

internal fun PlayerRuntimeController.refreshStableProgressResetGate() {
    if (!hasRenderedFirstFrame) return
    val player = _exoPlayer ?: return
    val healthy = player.playbackState == Player.STATE_READY && player.isPlaying
    if (healthy) {
        if (stableProgressResetJob?.isActive != true) {
            scheduleStableProgressReset()
        }
    } else {
        cancelStableProgressReset()
    }
}

/**
 * Silent PCM audio fallback for ERROR_CODE_AUDIO_TRACK_INIT_FAILED (5001).
 *
 * When the decoder is set to EXTENSION_RENDERER_MODE_ON (decoderPriority == 1,
 * the default) and tunneling is NOT active, audio passthrough may fail on certain devices/formats.
 * Instead of tearing down and re-building the entire player, we apply an
 * imperceptible speed change (1.00001×) which forces ExoPlayer to decode audio
 * through the software PCM pipeline — identical to what happens when the user
 * manually changes playback speed.
 *
 * This is a one-shot attempt per stream; if it fails again the normal retry
 * logic takes over.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryAudioTrackPcmFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return false
    if (hasTriedAudioPcmFallback) return false
    if (cachedDecoderPriority != 1) return false // Only for EXTENSION_RENDERER_MODE_ON
    if (_uiState.value.tunnelingEnabled) return false

    markAudioPcmFallbackTried()

    val player = _exoPlayer ?: return false
    val savedPosition = player.currentPosition.takeIf { it > 0L } ?: 0L
    val paused = userPausedManually

    Log.d(PlayerRuntimeController.TAG, "Audio track init failed (5001) — rebuilding player with PCM forcing, position=${savedPosition}ms")
    showRecoveryOverlay()

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        releasePlayer(flushPlaybackState = false)
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }

    return true
}

/** Bounded retry of a verified bitstream sink configuration; delays are policy, not a root-cause claim. */
internal fun PlayerRuntimeController.tryBitstreamAudioTrackSameConfigRetry(error: PlaybackException): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return false
    if (hasTriedAudioPcmFallback) return false
    val mime = failedAudioTrackInputFormat(error)?.sampleMimeType
    if (AudioTrackRejectionLog.labelForMime(mime) == null || currentAudioPassthroughPolicy?.deniesPassthrough(mime) != false) return false
    val player = _exoPlayer ?: return false
    if (!playbackRecoveryGate.canRetryAudio()) return false
    if (!consumeAutoRecoveryBudget("bitstream initialization retry")) return false
    val delayMs = playbackRecoveryGate.nextAudioDelayMs() ?: return false
    Log.w(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY delayMs=$delayMs")
    scheduleOwnedPlaybackRecovery(
        player.currentPosition,
        delayMs,
        awaitOutputFor = PassthroughOutputReturn.encodingOf(failedAudioTrackInputFormat(error))
    )
    return true
}

/**
 * Some VC-1 hardware decoders (Amlogic) refuse to start while any other video decoder is still alive, for
 * example a trailer or the previous title being released. One delayed retry per stream.
 */
internal fun PlayerRuntimeController.tryVc1DecoderStartRetry(error: PlaybackException): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return false
    val initFailure = generateSequence<Throwable>(error) { it.cause }
        .filterIsInstance<androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException>()
        .firstOrNull()
    // No VC-1 decoder on this device at all: nothing to wait for.
    if (initFailure != null && initFailure.codecInfo == null) return false
    val player = _exoPlayer ?: return false
    if (!vc1DecoderRetryStreamUrls.add(currentStreamUrl)) return false
    if (!consumeAutoRecoveryBudget("VC-1 decoder start retry")) return false
    Log.w(PlayerRuntimeController.TAG, "VC1_DECODER_RETRY delayMs=$VC1_DECODER_RETRY_DELAY_MS")
    scheduleOwnedPlaybackRecovery(player.currentPosition, VC1_DECODER_RETRY_DELAY_MS)
    return true
}

/**
 * The learned-rejection entry for a refused open, or null when it must not be learned:
 * a same-configuration retry is already scheduled, or the failure is not a bitstream open.
 */
internal fun audioRejectionToStash(
    errorCode: Int,
    retryScheduled: Boolean,
    failingMime: String?,
    routeKey: String?
): String? {
    if (retryScheduled) return null
    if (errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return null
    val group = com.nuvio.tv.core.player.AudioPassthroughPolicy.groupOf(failingMime) ?: return null
    if (routeKey == null) return null
    return AudioRejectionLedger.entry(routeKey, group)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithoutNativeFel(fromPositionMs: Long) {
    if (!consumeAutoRecoveryBudget("native FEL fallback")) {
        _uiState.update { it.copy(error = "Native FEL recovery budget exhausted", showLoadingOverlay = false) }
        return
    }
    scheduleOwnedPlaybackRecovery(fromPositionMs, 0L)
}

/** Snapshot identity before releasing. A LAZY job is assigned before it can run on Main.immediate. */
internal fun PlayerRuntimeController.scheduleOwnedPlaybackRecovery(
    positionMs: Long,
    delayMs: Long,
    awaitOutputFor: Int? = null
) {
    val url = currentStreamUrl
    val headers = currentHeaders.toMap()
    val generation = playbackRecoveryGate.generation
    var route = runCatching { AudioOutputRouteDetector.detect(context)?.key }.getOrNull()
    val paused = userPausedManually
    val position = _uiState.value.pendingSeekPosition ?: positionMs.coerceAtLeast(0L)
    val preference = rememberedTrackPreference ?: persistedTrackPreference
    cancelFirstFrameWatchdog()
    cancelTunnelAvSyncWatchdog()
    cancelStallWatchdog()
    cancelStartupWatchdog()
    pendingResumeProgress = null
    showRecoveryOverlay()
    playbackRecoveryJob?.cancel()
    val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
        kotlinx.coroutines.yield()
        if (!playbackRecoveryGate.isCurrent(generation, url, headers)) return@launch
        releasePlayer(flushPlaybackState = false, preserveRecovery = true)
        delay(delayMs)
        if (!playbackRecoveryGate.isCurrent(generation, url, headers) || currentStreamUrl != url || currentHeaders != headers) return@launch
        if (awaitOutputFor != null) {
            val waited = awaitAudioOutputForRetry(awaitOutputFor) {
                playbackRecoveryGate.isCurrent(generation, url, headers)
            } ?: return@launch
            if (waited) route = runCatching { AudioOutputRouteDetector.detect(context)?.key }.getOrNull()
        }
        if (runCatching { AudioOutputRouteDetector.detect(context)?.key }.getOrNull() != route) {
            _uiState.update { it.copy(error = "Audio output changed during recovery. Start playback again.", showLoadingOverlay = false) }
            return@launch
        }
        if (preference != null) pendingEngineSwitchTrackPreference = PlayerRuntimeController.PendingEngineSwitchTrackPreference(
            streamUrl = url, preference = preference, sourceEngine = InternalPlayerEngine.EXOPLAYER
        )
        _uiState.update { it.copy(pendingSeekPosition = position, error = null) }
        playbackRecoveryJob = null
        initializePlayer(url, headers, startPaused = paused || userPausedManually)
    }
    playbackRecoveryJob = job
    job.start()
}

/**
 * Waits until the audio output is back before a refused bitstream open of [encoding] is retried.
 * Without this, a refusal while HDMI is unplugged (TV on another input) rebuilds the player
 * with PCM-only capabilities. Returns whether it had to wait, or null when the recovery became
 * stale while waiting.
 */
internal suspend fun PlayerRuntimeController.awaitAudioOutputForRetry(
    encoding: Int,
    stillCurrent: () -> Boolean
): Boolean? {
    val startedMs = SystemClock.elapsedRealtime()
    var logged = false
    while (true) {
        if (!stillCurrent()) return null
        val routeKey = runCatching { AudioOutputRouteDetector.detect(context)?.key }.getOrNull()
        val ready = PassthroughOutputReturn.outputReadyForRetry(
            live = PassthroughOutputReturn.liveEncodings(context),
            routeIsHdmi = PassthroughOutputReturn.isHdmiRoute(routeKey)
        )
        val waitedMs = SystemClock.elapsedRealtime() - startedMs
        if (PassthroughOutputReturn.retryMayProceed(ready, waitedMs)) {
            if (logged) {
                Log.i(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY output back after ${waitedMs}ms ready=$ready route=$routeKey")
            }
            return logged
        }
        if (!logged) {
            Log.w(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY waiting for audio output encoding=$encoding route=$routeKey")
            logged = true
        }
        delay(PassthroughOutputReturn.POLL_MS)
    }
}

/**
 * FFmpeg-preferred rebuild for ERROR_CODE_DECODER_INIT_FAILED (4001) on an audio
 * renderer whose failing format belongs to a policy-denied group.
 *
 * A hybrid track (e.g. DTS-HD MA in Matroska) is selected under its base MIME
 * (audio/vnd.dts), then upgrades mid-stream to the denied MIME
 * (audio/vnd.dts.hd), leaving the selected renderer without a decoder. media3
 * never remaps a track mid-stream, so a plain retry fails the same way.
 * Preferring FFmpeg audio makes it win the tie for the whole family.
 *
 * While active (this stream only) FFmpeg decodes every audio format it
 * supports, so a track that could have passed through plays as PCM.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryDeniedAudioFfmpegFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return false
    if (currentStreamUrl in preferFfmpegAudioStreamUrls) return false
    if (cachedDecoderPriority == 0) return false // No FFmpeg renderer without extensions.
    val failingMime = (error as? androidx.media3.exoplayer.ExoPlaybackException)
        ?.rendererFormat?.sampleMimeType
    if (failingMime == null || !androidx.media3.common.MimeTypes.isAudio(failingMime)) return false
    val policy = currentAudioPassthroughPolicy ?: return false
    if (!policy.deniesPassthrough(failingMime)) return false

    preferFfmpegAudioStreamUrls.add(currentStreamUrl)

    val paused = userPausedManually
    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L

    Log.d(
        PlayerRuntimeController.TAG,
        "Decoder init failed (4001) on policy-denied audio $failingMime - retrying with FFmpeg audio preferred, position=${savedPosition}ms"
    )

    resetErrorRetryState()

    errorRetryJob = scope.launch {
        showRecoveryOverlay()

        releasePlayer(flushPlaybackState = false)
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }
    return true
}

/**
 * DV7-to-HEVC decoder fallback for ERROR_CODE_DECODER_INIT_FAILED (4003).
 *
 * When decoderPriority == 1 (EXTENSION_RENDERER_MODE_ON) and the decoder
 * fails to initialise, this is often caused by Dolby Vision profile 7
 * content on devices without a DV decoder.  Enabling the DV7-to-HEVC
 * mapping allows the HEVC decoder to handle the stream instead.
 *
 * Unlike the PCM fallback this requires a full player rebuild because
 * the mapping is baked into the renderers factory at build time.
 * Tunneling state does not matter for this fallback.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryDv7HevcFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return false
    if (hasTriedDv7HevcFallback) return false
    if (cachedDecoderPriority != 1) return false
    // Skip if DV7-to-HEVC is already active — nothing more we can do.
    if (forceDv7ToHevc) return false

    hasTriedDv7HevcFallback = true
    forceDv7ToHevc = true

    val paused = userPausedManually
    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L

    Log.d(
        PlayerRuntimeController.TAG,
        "Decoder init failed (4003) — retrying with DV7-to-HEVC mapping, position=${savedPosition}ms"
    )

    resetErrorRetryState()

    // Show loading overlay with fallback info instead of error screen.
    errorRetryJob = scope.launch {
        showRecoveryOverlay()

        releasePlayer(flushPlaybackState = false)
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }
    return true
}

internal fun PlayerRuntimeController.tryParsingErrorProbeFallback(
    error: PlaybackException,
    detailedError: String,
    allowEngineFailover: Boolean,
    savedPosition: Long = 0L,
    paused: Boolean = userPausedManually
): Boolean {
    if (currentVideoTrackIsLikelyVc1 ||
        Vc1VideoFormatHeuristics.isLikelyVc1(streamName = _uiState.value.currentStreamName ?: streamName)
    ) {
        return false
    }
    val isSourceOrParsingError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
        error.findCauseOfType<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null ||
        error.cause?.toString()?.contains("UnrecognizedInputFormatException") == true

    if (!isSourceOrParsingError) return false
    // Mid-play, the container already played for a
    // while under a known non-HLS mimeType - re-probing the format cannot help
    // (the data is corrupt, not the container label) and costs ~6s against the
    // NNTP engine. Skip the probe; the dispatcher's auto-retry and the mid-play
    // failover after the budget gate handle it. HLS (M3U8) still probes for
    // live-window recovery.
    if (hasRenderedFirstFrame &&
        currentStreamMimeType != null &&
        currentStreamMimeType != androidx.media3.common.MimeTypes.APPLICATION_M3U8
    ) {
        return false
    }
    if (parsingErrorProbeAttempted) return false
    parsingErrorProbeAttempted = true

    val previousMimeType = currentStreamMimeType
    Log.w(
        PlayerRuntimeController.TAG,
        "Source/parsing error [${error.errorCode}] detected (previous mimeType=$previousMimeType). " +
            "Probing stream format..."
    )

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        showRecoveryOverlay()
        val probedMime = PlayerMediaSourceFactory.probeNetworkMimeType(
            url = currentStreamUrl,
            headers = currentHeaders
        )

        if (probedMime != null && probedMime != previousMimeType) {
            Log.i(
                PlayerRuntimeController.TAG,
                "Stream probe resolved mimeType=$probedMime (was $previousMimeType). Retrying playback..."
            )
            currentStreamMimeType = probedMime
            currentStreamResponseHeaders = emptyMap()
            releasePlayer(flushPlaybackState = false)
            if (savedPosition > 0L) {
                _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
            }
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
        } else if (previousMimeType == androidx.media3.common.MimeTypes.APPLICATION_M3U8) {
            currentStreamMimeType = null
            currentStreamResponseHeaders = emptyMap()
            releasePlayer(flushPlaybackState = false)
            if (savedPosition > 0L) {
                _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
            }
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
        } else {
            // Dead-source rung: the probe failed to
            // name a better container, so a sniff-failure body (HTML page behind
            // HTTP 200, .rar/.zip payload) or HTTP 404/410 is permanent for this
            // URL. Advance to the next source instead of burning both same-URL
            // retries on a doomed link. Checked before the engine failover: a
            // dead URL is dead on either engine.
            if (isDeadSourcePlaybackError(error) && attemptDeadSourceFailover(error, detailedError)) {
                return@launch
            }
            if (maybeAutoSwitchInternalPlayerOnStartupError(detailedError = detailedError, allowEngineFailover = allowEngineFailover)) {
                return@launch
            }
            if (attemptAutoRetry(error, detailedError)) {
                return@launch
            }
            if (tryServerFallback(error)) {
                return@launch
            }
            if (isServerDirectPlay && advanceToNextLiveSource(detailedError)) {
                return@launch
            }
            val userFacingError = error.toDisplayMessage(context)
            _uiState.update {
                it.copy(
                    error = userFacingError,
                    isBuffering = false,
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
        }
    }
    return true
}

/** @return true if a mimeType override was present and has been cleared. */
private fun PlayerRuntimeController.clearMimeOverrideForParsingError(error: PlaybackException): Boolean {
    if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
    ) {
        if (currentStreamMimeType != null) {
            Log.w(
                PlayerRuntimeController.TAG,
                "Parsing error [${error.errorCode}] detected with mimeType=$currentStreamMimeType. " +
                        "Clearing mimeType override for fallback."
            )
            currentStreamMimeType = null
            currentStreamResponseHeaders = emptyMap()
            return true
        }
    }
    return false
}

/**
 * Dead-source classification.
 *
 * A container-sniff failure where the content is NOT malformed media - media3's
 * [androidx.media3.exoplayer.source.UnrecognizedInputFormatException], surfaced as
 * ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003) - means the body is not media at
 * all: an HTML error page behind HTTP 200, or a .rar/.zip payload from a torrent.
 * HTTP 404/410 are equally permanent for the URL. Same-URL retries only make the
 * user sit through doomed rebuild cycles.
 *
 * Deliberately NOT classified dead: HTTP 429 and timeouts - auto-advancing on a
 * debrid rate limit (TorBox parallel-connection 429s are transient) would wrongly
 * burn perfectly good sources.
 */
internal fun PlayerRuntimeController.isDeadSourcePlaybackError(error: PlaybackException): Boolean {
    if (isDeadSourceHttpError(error)) return true
    return error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
        error.findCauseOfType<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null
}

/**
 * The HTTP arm of the dead-source classification. 404/410 is certain-dead with
 * no probe value, so callers can advance without spending the
 * parsing-error probe on it.
 */
internal fun PlayerRuntimeController.isDeadSourceHttpError(error: PlaybackException): Boolean {
    val http = error.findInvalidResponseCodeException()
    return http != null && (http.responseCode == 404 || http.responseCode == 410)
}

/**
 * Handles a dead-source error: one narrowly-scoped same-URL retry if a mimeType
 * override was steering the extractor (the only genuinely recoverable sub-case),
 * otherwise mark the URL dead for the session and auto-advance to the next source
 * in the user's existing sort order. Returns false when out of options (failover
 * cap reached or no live sources left) so the caller surfaces the error screen.
 */
internal fun PlayerRuntimeController.attemptDeadSourceFailover(
    error: PlaybackException,
    detailedError: String
): Boolean {
    // The override is baked into the MediaItem, so only a FULL re-init applies the
    // clear - a bare prepare() retry cannot (which is why the old auto-retry's first
    // attempt never fixed this case). One full retry, then the URL is treated as dead.
    if (clearMimeOverrideForParsingError(error) && !hasRetriedAfterMimeOverrideClear) {
        hasRetriedAfterMimeOverrideClear = true
        val paused = userPausedManually
        val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L
        Log.w(
            PlayerRuntimeController.TAG,
            "Dead-source check: mimeType override cleared; one full re-init for: $detailedError"
        )
        errorRetryJob?.cancel()
        errorRetryJob = scope.launch {
            _uiState.update {
                it.copy(error = null, showLoadingOverlay = it.loadingOverlayEnabled, showPauseOverlay = false)
            }
            delay(RETRY_DELAY_MS)
            releasePlayer(flushPlaybackState = false)
            if (savedPosition > 0L) {
                _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
            }
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
        }
        return true
    }

    return advanceToNextLiveSource(detailedError)
}

/**
 * Marks the current stream URL dead for this session (greying it in the source
 * panel), then auto-advances to the next live source in the user's existing
 * sort order. Shared by the dead-source path and the startup-exhausted path;
 * both draw on the same MAX_DEAD_SOURCE_FAILOVERS cap. Returns false when out
 * of options (cap reached or no live sources left) so the caller surfaces the
 * error screen.
 *
 * The source list must belong to the title or episode that is playing. When it
 * is missing, belongs to another episode or is still loading, it is loaded first
 * and the switch happens once it is there, or the error screen shows if nothing
 * usable turned up.
 */
internal fun PlayerRuntimeController.advanceToNextLiveSource(detailedError: String): Boolean {
    deadSourceStreamUrls.add(currentStreamUrl)
    serverPlayback.session(currentStreamUrl)?.target?.let { deadSourceStreamUrls.add(it.key()) }
    _uiState.update { it.copy(deadSourceStreamUrls = deadSourceStreamUrls.toSet()) }

    if (deadSourceFailoverCount >= MAX_DEAD_SOURCE_FAILOVERS) {
        Log.w(
            PlayerRuntimeController.TAG,
            "Dead-source failover cap ($MAX_DEAD_SOURCE_FAILOVERS) reached; surfacing error"
        )
        return false
    }
    val requestKey = currentSourceRequestKey() ?: return false
    val savedPosition = currentPlaybackPositionMs()?.takeIf { it > 0L } ?: 0L

    if (sourceStreamsCacheRequestKey == requestKey) {
        val next = nextFailoverSource()
        if (next != null) {
            errorRetryJob?.cancel()
            scope.launch { failOverToSource(next, detailedError, savedPosition) }
            return true
        }
        if (sourceStreamsFetchCompleted) {
            Log.w(PlayerRuntimeController.TAG, "Dead source and no live sources left; surfacing error")
            return false
        }
    }

    Log.w(PlayerRuntimeController.TAG, "Dead source ($detailedError) - loading the source list before failing over")
    val attemptNo = deadSourceFailoverCount + 1
    loadSourceStreams(forceRefresh = false)
    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        showSourceFailoverOverlay(attemptNo, savedPosition)
        val deadline = SystemClock.elapsedRealtime() + FAILOVER_SOURCE_LIST_WAIT_MS
        var next: Stream? = null
        while (true) {
            if (currentSourceRequestKey() != requestKey) return@launch
            if (sourceStreamsCacheRequestKey == requestKey) {
                next = nextFailoverSource()
                if (next != null || sourceStreamsFetchCompleted) break
            }
            if (SystemClock.elapsedRealtime() >= deadline) break
            delay(FAILOVER_SOURCE_LIST_POLL_MS)
        }
        errorRetryJob = null
        if (next != null) {
            failOverToSource(next, detailedError, savedPosition)
        } else {
            Log.w(PlayerRuntimeController.TAG, "Dead source and no live sources in the loaded list; surfacing error")
            surfaceSourceFailoverError(detailedError)
        }
    }
    return true
}

/** Marks the list entry that is playing as dead and returns the next playable one after it. */
private fun PlayerRuntimeController.nextFailoverSource(): Stream? {
    val state = _uiState.value
    val streams = state.sourceAllStreams
    if (streams.isEmpty()) return null
    val serverTarget = serverPlayback.session(currentStreamUrl)?.target
    val currentIdx = findCurrentStreamIndex(
        streams = streams,
        currentStreamInfoHash = state.currentStreamInfoHash,
        currentStreamFileIdx = state.currentStreamFileIdx,
        currentStreamAddonName = state.currentStreamAddonName,
        currentStreamUrl = state.currentStreamUrl,
        currentStreamName = state.currentStreamName
    ).takeIf { it >= 0 } ?: indexOfServerTarget(streams, serverTarget)
    if (currentIdx >= 0) {
        streams.getOrNull(currentIdx)?.let(SourceFailoverSelection::failoverKey)?.let { url ->
            if (deadSourceStreamUrls.add(url)) {
                _uiState.update { it.copy(deadSourceStreamUrls = deadSourceStreamUrls.toSet()) }
            }
        }
    }
    val request = currentSourceStreamsRequest()
    return SourceFailoverSelection.selectNext(
        streams = streams,
        currentIndex = currentIdx,
        listRequestKey = sourceStreamsCacheRequestKey,
        currentRequestKey = currentSourceRequestKey(),
        deadUrls = deadSourceStreamUrls,
        season = request?.season,
        episode = request?.episode
    )
}

private fun PlayerRuntimeController.showSourceFailoverOverlay(attemptNo: Int, savedPosition: Long, sourceName: String? = null) {
    _uiState.update {
        it.copy(
            error = null,
            showPauseOverlay = false,
            showLoadingOverlay = it.loadingOverlayEnabled,
            loadingMessage = if (sourceName != null) {
                context.getString(com.nuvio.tv.R.string.player_dead_source_failover_named, attemptNo, MAX_DEAD_SOURCE_FAILOVERS, sourceName)
            } else {
                context.getString(com.nuvio.tv.R.string.player_dead_source_failover, attemptNo, MAX_DEAD_SOURCE_FAILOVERS)
            },
            pendingSeekPosition = if (savedPosition > 0L) savedPosition else it.pendingSeekPosition
        )
    }
}

private fun PlayerRuntimeController.failOverToSource(next: Stream, detailedError: String, savedPosition: Long) {
    deadSourceFailoverCount++
    if (serverPlayback.session(currentStreamUrl) != null) stopServerPlayback()
    val attemptNo = deadSourceFailoverCount
    Log.w(
        PlayerRuntimeController.TAG,
        "Dead source ($detailedError) - failing over to next source ($attemptNo/$MAX_DEAD_SOURCE_FAILOVERS): " +
                "host=${next.getStreamUrl()?.safeHost()}"
    )
    val sourceName = next.name?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: next.addonName
    showSourceFailoverOverlay(attemptNo, savedPosition, sourceName.takeIf { it.isNotBlank() })
    switchToSourceStream(next, automatic = true)
}

internal fun PlayerRuntimeController.surfaceSourceFailoverError(detailedError: String) {
    cancelNextEpisodeAutoPlayOnFatalError()
    _uiState.update {
        it.copy(
            error = detailedError,
            isBuffering = false,
            showLoadingOverlay = false,
            showPauseOverlay = false
        )
    }
}

/**
 * A startup-phase failure that has exhausted the fallback
 * ladders and both same-URL auto-retries advances to the next source instead
 * of surfacing the error screen. A URL that could not
 * produce a first frame through every recovery path is treated like a dead
 * source for this session.
 */
internal fun PlayerRuntimeController.attemptStartupExhaustedSourceFailover(detailedError: String): Boolean {
    if (hasRenderedFirstFrame) return false
    Log.w(
        PlayerRuntimeController.TAG,
        "Startup recovery exhausted; attempting next-source failover for: $detailedError"
    )
    return advanceToNextLiveSource(detailedError)
}

/**
 * Consumes one unit of the per-stream auto-recovery budget.
 *
 * Self-keys on the current stream URL: a genuine source switch resets the
 * counter without any external reset call. Returns false once the budget is
 * spent, at which point onPlayerError skips every fallback ladder and surfaces
 * the error to the user instead of silently re-preparing again.
 */
internal fun PlayerRuntimeController.consumeAutoRecoveryBudget(detailedError: String): Boolean {
    val url = currentStreamUrl
    if (url != autoRecoveryBudgetUrl) {
        autoRecoveryBudgetUrl = url
        autoRecoveryCountForCurrentStream = 0
    }
    autoRecoveryCountForCurrentStream += 1
    if (autoRecoveryCountForCurrentStream > MAX_TOTAL_AUTO_RECOVERIES_PER_STREAM) {
        Log.w(
            PlayerRuntimeController.TAG,
            "Auto-recovery budget exhausted ($MAX_TOTAL_AUTO_RECOVERIES_PER_STREAM recoveries) " +
                "for host=${url.safeHost()}; surfacing error instead of retrying: $detailedError"
        )
        return false
    }
    return true
}
