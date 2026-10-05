package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.util.TtffTrace
import com.nuvio.tv.core.logging.diagnosticStreamUrl
import com.nuvio.tv.core.logging.redactedUrlForLog
import android.content.Context
import android.content.res.Resources
import android.graphics.RectF
import android.media.MediaFormat
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.CaptioningManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import com.nuvio.tv.ui.screens.player.iec.IecPassthroughAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nuvio.tv.R
import com.nuvio.tv.core.player.DolbyVisionCodecFallback
import com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicy
import com.nuvio.tv.core.player.BitrateAwareLoadControl
import com.nuvio.tv.core.player.DolbyVisionConversionConfig
import com.nuvio.tv.core.player.DolbyVisionConversionStats
import com.nuvio.tv.core.player.AviTrackFormatExtractorsFactory
import com.nuvio.tv.core.player.DolbyVisionExtractorsFactory
import com.nuvio.tv.core.player.DoviBridge
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.repository.PlaybackIssueErrorInput
import com.nuvio.tv.domain.model.Subtitle
import io.github.peerless2012.ass.media.kt.buildWithAssSupport
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException
import kotlin.math.min
import androidx.media3.common.Tracks
import com.nuvio.tv.core.player.PlaceholderStreamPolicy


private const val MPV_AFR_SETTLE_DELAY_MS = 2_000L
// Absolute cap on how long playback start may wait for the AFR
// preflight. In-probe timeouts (NextLib 6s + extractor 4s budgets) are advisory
// against blocked native calls; the extractor also has a force-release
// watchdog. This is the final backstop.
private const val AFR_PREFLIGHT_ABSOLUTE_DEADLINE_MS = 15_000L
private const val AUDIO_DELAY_REFRESH_DEBOUNCE_MS = 120L

/**
 * With Nuvio's own AFR off, leave media3's built-in
 * Surface.setFrameRate path on ONLY_IF_SEAMLESS so displays/firmware that
 * support seamless switching still get a free, blackout-less refresh match.
 * With Nuvio AFR on, keep it OFF so the two mechanisms don't fight.
 */
private fun nuvioFrameRateStrategy(mode: FrameRateMatchingMode): Int =
    if (mode == FrameRateMatchingMode.OFF) {
        C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
    } else {
        C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
    }
// ExoPlayer.release() blocks the calling thread until the
// internal playback thread joins, bounded by this timeout, and the app-layer
// caller is always Main (viewModelScope). Moving release() off Main is
// engine-blocked: the fork AAR's ExoPlayerImpl.release() calls
// verifyApplicationThread(), which throws off the application looper.
// The supported lever is this timeout: 1000ms caps the worst-case Main block
// while comfortably covering healthy releases (typically <100ms, slow codec
// flush ~500ms). A genuinely wedged decoder ends the same way with any
// timeout (release() gives up and the playback thread is abandoned); a short
// one just stops it holding the UI hostage. PLAYER_RELEASE: log lines record
// real-world durations.
private const val PLAYER_RELEASE_TIMEOUT_MS = 1000L
private const val PLAYER_REBUILD_SETTLE_DELAY_MS = 120L
private const val ADAPTIVE_QUALITY_INCREASE_MIN_DURATION_MS = 2_000
private const val ADAPTIVE_INITIAL_BITRATE_ESTIMATE_BPS = 25_000_000L

internal data class StartupSubtitlePreparation(
    val fetchedSubtitles: List<Subtitle>,
    val attachedSubtitles: List<Subtitle>,
    val fetchCompleted: Boolean
)

private suspend fun PlayerRuntimeController.resolveCurrentStreamMimeType(
    url: String,
    headers: Map<String, String>
) {
    currentStreamMimeType?.let { resolvedMimeType ->
        Log.d(
            PlayerRuntimeController.TAG,
            "Resolved stream mimeType=$resolvedMimeType for url=${url.redactedUrlForLog()}"
        )
        return
    }
    currentStreamMimeType = PlayerMediaSourceFactory.probeMimeType(
        url = url,
        headers = headers,
        filename = currentFilename,
        responseHeaders = currentStreamResponseHeaders
    )
    Log.d(
        PlayerRuntimeController.TAG,
        "Resolved stream mimeType=${currentStreamMimeType ?: "unknown"} for url=${url.redactedUrlForLog()}"
    )
}

/**
 * The constructor-baked configuration of an ExoPlayer build. Everything here
 * is fixed at construction (renderers-factory arguments, load-control geometry,
 * bandwidth-meter mode, the libass build fork); per-media-source and live-settable
 * state (track-selector parameters, frame-rate strategy, extractors config) is
 * deliberately excluded and re-applied on the reuse branch instead.
 */
internal data class ExoConstructionFingerprint(
    val useLibass: Boolean,
    val isHls: Boolean,
    val performanceModeEnabled: Boolean,
    val bufferEngineEnabled: Boolean,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val backBufferDurationMs: Int,
    val targetBufferSizeMb: Int,
    val bufferBudgetManaged: Boolean,
    val allowLargeTargetBuffer: Boolean,
    val downmixEnabled: Boolean,
    val audioOutputChannels: com.nuvio.tv.data.local.AudioOutputChannels,
    val maintainOriginalAudioOnDownmix: Boolean,
    val forceOpticalPassthroughActive: Boolean,
    val useSystemPassthrough: Boolean,
    val initialForcePcm: Boolean,
    val audioPassthroughPolicy: com.nuvio.tv.core.player.AudioPassthroughPolicy,
    val deniedTranscodeMimes: Set<String>,
    val preferFfmpegAudio: Boolean,
    val extensionRendererMode: Int,
    val convertToDv81Active: Boolean,
    val mapDv7ToHevc: Boolean,
    val tunnelingEnabled: Boolean,
    val nativeFelActive: Boolean = false
)

/**
 * Pushes the current player settings onto the shared media-source factory.
 *
 * Shared with the chunk-0 pre-start, which runs before initializePlayer,
 * in switchToEpisodeStream, and the factory's geometry fields
 * (parallel on/off, connection count, chunk size) are what the companion
 * session store keys on. Applied only inside initializePlayer, the pre-start
 * would key a session on whatever the PREVIOUS stream left behind, and the
 * player would then decline to adopt it. Idempotent, so calling it from both
 * sites is safe.
 */
internal fun PlayerRuntimeController.applyMediaSourceFactorySettings(playerSettings: PlayerSettings) {
    if (playerSettings.bufferEngineEnabled) {
        mediaSourceFactory.vodCacheEnabled = playerSettings.vodCacheEnabled
        mediaSourceFactory.vodCacheSizeMode = playerSettings.vodCacheSizeMode
        mediaSourceFactory.vodCacheSizeMb = playerSettings.vodCacheSizeMb
    } else {
        mediaSourceFactory.vodCacheEnabled = false
    }

    if (playerSettings.parallelNetworkEnabled) {
        mediaSourceFactory.useParallelConnections = playerSettings.useParallelConnections
        mediaSourceFactory.parallelConnectionCount = playerSettings.parallelConnectionCount
        mediaSourceFactory.parallelChunkSizeKb = playerSettings.parallelChunkSizeKb
    } else {
        // Reset each playback so the factory doesn't keep last stream's state.
        mediaSourceFactory.useParallelConnections = false
    }
    mediaSourceFactory.nuvioPerformanceModeEnabled = playerSettings.nuvioPerformanceModeEnabled
    mediaSourceFactory.nativeEngineEnabled = playerSettings.nuvioPerformanceModeEnabled
    mediaSourceFactory.bufferEngineEnabled = playerSettings.bufferEngineEnabled
    mediaSourceFactory.bufferBudgetManaged = playerSettings.bufferBudgetManaged
    lastAppliedPlayerSettings = playerSettings
}

/**
 * Returns true when an ExoPlayer was actually released, so the caller
 * can skip the settle that only a release needs.
 */
private fun PlayerRuntimeController.disposeExoPlayerBeforeRebuild(): Boolean {
    notifyAudioSessionUpdate(false)
    try {
        currentMediaSession?.release()
        currentMediaSession = null
    } catch (_: Exception) {
    }
    _exoPlayer?.let { player ->
        // No pause()/clearMediaItems(): each is a
        // redundant player-thread round trip immediately before release()
        // (playWhenReady=false already pauses; release() discards the item
        // queue). stop() is kept: it halts renderers early, which shortens the
        // release join on a live pipeline.
        runCatching { player.playWhenReady = false }
        runCatching { player.stop() }
        runCatching { player.clearVideoSurface() }
        val releaseStartMs = android.os.SystemClock.elapsedRealtime()
        runCatching { player.release() }
        val releaseMs = android.os.SystemClock.elapsedRealtime() - releaseStartMs
        Log.i(
            PlayerRuntimeController.TAG,
            "PLAYER_RELEASE: site=rebuild exoReleaseMs=$releaseMs timeoutMs=$PLAYER_RELEASE_TIMEOUT_MS"
        )
    }
    val releasedExistingPlayer = _exoPlayer != null
    _exoPlayer = null
    nativeVideoSelection.clear()
    playbackSpeedAwareAudioSink = null
    currentExoPlayerListener = null
    currentExoAnalyticsListener = null
    return releasedExistingPlayer
}

// AFR settle hold duration (Exo parity with mpvDelayStartAfterAfrSwitch).
private const val AFR_EXO_SETTLE_HOLD_MS = 2_000L

/**
 * Applies a [PlayerStartupPlaybackPolicy] start action through the fork's AFR gates:
 *
 *  - Track-AFR gate: while a track-format-driven AFR switch is in flight the
 *    start is suppressed; resumePlaybackAfterTrackAfrIfHeld() (deadline-bounded, with
 *    its own settle delay) resumes playback when the gate releases.
 *  - Display-AFR settle hold:
 *    when the preflight actually changed the display mode, hold the first start
 *    ~2 s so the A/V pipeline does not begin inside the HDMI mode transition. On
 *    QMS-capable chains the seamless switch reports complete almost instantly, so
 *    an immediate start can otherwise start the hardware clock mid-transition and
 *    latch bad frame pacing for the whole title. Timer-released, never event-gated,
 *    so it cannot deadlock startup; no-switch starts are unaffected.
 */
private fun PlayerRuntimeController.startPlaybackThroughAfrGates(
    player: ExoPlayer,
    setPlayWhenReady: Boolean,
    callPlay: Boolean,
    holdForAfrSettle: Boolean
) {
    if (!setPlayWhenReady && !callPlay) return
    if (afrTrackSwitchInFlight) return
    if (holdForAfrSettle) {
        scope.launch {
            kotlinx.coroutines.delay(AFR_EXO_SETTLE_HOLD_MS)
            if (_exoPlayer === player && !userPausedManually && !isReleasingPlayer) {
                player.playWhenReady = true
                player.play()
            }
        }
        return
    }
    if (setPlayWhenReady) {
        player.playWhenReady = true
    }
    if (callPlay) {
        player.play()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.initializePlayer(
    url: String,
    headers: Map<String, String>,
    overrideInternalPlayerEngine: InternalPlayerEngine? = null,
    allowEngineFailover: Boolean = true,
    startPaused: Boolean = false
) {
    if (url.isEmpty()) {
        _uiState.update { it.copy(error = context.getString(R.string.player_error_no_stream_url), showLoadingOverlay = false) }
        return
    }
    playbackRecoveryJob?.cancel()
    playbackRecoveryJob = null
    if (playbackRecoveryGate.begin(url, headers)) nativeFelDisabledStreamUrls.clear()
    val initializationGeneration = playbackRecoveryGate.generation
    mpvMediaLoadPrepared = false

    scope.launch {
        if (initializationGeneration != playbackRecoveryGate.generation) return@launch
        try {
            // Player reuse: snapshot the live player's constructor-baked companions
            // before the per-stream resets and construction overwrite the fields.
            // Consumed only on the reuse branch at the build fork.
            val previousTrackSelectorForReuse = trackSelector
            val previousLoadControlForReuse = _loadControl
            val previousBitrateAwareLoadControlForReuse = currentBitrateAwareLoadControl
            // The back buffer is baked into ExoPlayerImplInternal at construction and never
            // re-read, so a reused player keeps the value it was first built with. Capture it
            // here, before the per-stream reset below zeroes the field, so the reuse branch can
            // report what the engine is actually running.
            val previousEffectiveBackBufferMsForReuse = effectiveBackBufferDurationMs
            if (allowEngineFailover) {
                startupEngineFailoverTriggered = false
            }
            autoSubtitleSelected = false
            hasScannedTextTracksOnce = false
            lastPlaybackDiagnosticsForReport = LastPlaybackDiagnostics.EMPTY
            lastPlaybackIssueError = null
            playbackIssueReportRequestVersion.incrementAndGet()
            playbackAnalyticsDiagnostics.reset()
            PlayerAudioUnderrunCounter.reset()
            PlayerAudioBitrateMeter.reset()
            _uiState.update {
                it.copy(
                    playbackIssueReportStatus = PlaybackIssueReportStatus.Idle,
                    playbackIssueReportId = null,
                    playbackIssueReportError = null
                )
            }
            resetLoadingOverlayForNewStream()
            if (startPaused) {
                userPausedManually = true
                shouldEnforceAutoplayOnFirstReady = false
            }
            val applyPcmFallbackOnStartup = pendingAudioPcmFallbackRebuild
            val applyDv7FallbackOnStartup = forceDv7ToHevc
            if (!applyPcmFallbackOnStartup) {
                hasTriedAudioPcmFallback = false
            }
            hasTriedDv7HevcFallback = false
            // Fresh stream, fresh Audio Path row, so a stream whose track-init
            // event never fires does not inherit the previous stream's row.
            currentAudioPathDescription = null
            forceDv7ToHevc = false
            mpvDelayStartAfterAfrSwitch = false
            // Fresh stream, fresh track-AFR state.
            // Bump the generation so an in-flight track-AFR coroutine
            // from the previous stream stands down in its finally.
            afrTrackGeneration++
            trackAfrAttemptedForCurrentStream = false
            afrTrackSwitchInFlight = false
            afrModeAppliedPreStart = false
            afrSeededRateRaw = 0f
            // Never carry a preview position into a new player /
            // stream - a leaked value would commit the *new* episode to the old
            // preview position on the next gesture.
            pendingPreviewSeekExpiryJob?.cancel()
            pendingPreviewSeekPosition = null
            _uiState.update { it.copy(pendingPreviewSeekPosition = null, previewThumbPositionMs = null) }
            // Fresh stream, fresh output-mode state; the audio-path
            // listener re-derives it at AudioTrack init.
            isAudioOutputBypassing = false
            playerInitializationStartedAtMs = System.currentTimeMillis()
            // Reset per playback; only the ExoPlayer custom-buffer path sets a real value.
            effectiveBackBufferDurationMs = 0
            currentBitrateAwareLoadControl = null
            configuredBackBufferMs = 0

            AudioRejectionReverifier.ledger.restoreEvidence(playerSettingsDataStore.audioRefusalEvidence())
            val playerSettings = playerSettingsDataStore.playerSettings.first()
            if (initializationGeneration != playbackRecoveryGate.generation) return@launch
            currentPlayerSettingsForReport = playerSettings
            rememberAudioDelayPerDeviceEnabled = playerSettings.rememberAudioDelayPerDevice
            // Always watch output-device changes so Bluetooth connect/disconnect can switch
            // PCM/passthrough policy in place (Media3 1.8.0 BT semantics; do not rebuild).
            registerAudioDelayRouteCallback()
            currentAudioOutputRoute = AudioOutputRouteDetector.detect(context)
            if (rememberAudioDelayPerDeviceEnabled) {
                applyStoredAudioDelayForCurrentRouteIfEnabled()
            }
            currentAudioOutputRoute?.let { route ->
                if (!route.isBluetooth && _exoPlayer == null) {
                    AudioRejectionReverifier.start(route.key, playerSettings.audioRejectionsConfirmed) { entry ->
                        scope.launch { playerSettingsDataStore.clearAudioRejection(entry) }
                    }
                }
            }
            cachedDecoderPriority = playerSettings.decoderPriority
            val preferredAudioLanguages = resolvePreferredAudioLanguages(
                preferredAudioLanguage = playerSettings.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = playerSettings.secondaryPreferredAudioLanguage,
                deviceLanguages = resolveDeviceAudioLanguages(),
                contentOriginalLanguage = contentLanguage
            )
            mpvPreferredAudioLanguages = preferredAudioLanguages
            mpvHi10pGnextSoftwareFallbackEnabledSetting =
                playerSettings.mpvHi10pGnextSoftwareFallbackEnabled
            mpvHardwareDecodeModeSetting = playerSettings.mpvHardwareDecodeMode
            var effectiveInternalPlayerEngine = overrideInternalPlayerEngine ?: playerSettings.internalPlayerEngine
            if (effectiveInternalPlayerEngine == InternalPlayerEngine.AUTO) {
                effectiveInternalPlayerEngine = resolveAutoInternalPlayerEngine()
            }
            runtimeInternalPlayerEngineOverride = overrideInternalPlayerEngine
            if (overrideInternalPlayerEngine == null && playerSettings.internalPlayerEngine == InternalPlayerEngine.AUTO) {
                resolvedAutoPlayerEngine = effectiveInternalPlayerEngine
            } else if (overrideInternalPlayerEngine != null) {
                resolvedAutoPlayerEngine = null
            }
            currentInternalPlayerEngine = effectiveInternalPlayerEngine
            playbackAnalyticsDiagnostics.setTraceContext(
                host = url.safeHost(),
                engine = effectiveInternalPlayerEngine.name
            )
            playbackAnalyticsDiagnostics.setStartupContext(
                launchStartedAtElapsedMs = launchStartedAtElapsedMs,
                initializationStartedAtWallTimeMs = playerInitializationStartedAtMs,
                startPositionMs = null
            )
            flushPendingPlaybackRawEventLines()
            val deviceAspectMode = deviceLocalPlayerPreferences.aspectMode.first()
            val tunneledSurfaceFill = deviceLocalPlayerPreferences.tunneledSurfaceFill.first()
            if (initializationGeneration != playbackRecoveryGate.generation) return@launch
            _uiState.update {
                it.copy(
                    internalPlayerEngine = effectiveInternalPlayerEngine,
                    frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                    resizeMode = playerSettings.resizeMode,
                    aspectMode = deviceAspectMode,
                    tunneledSurfaceFill = tunneledSurfaceFill,
                    playbackIssueReportsEnabled = playerSettings.playbackIssueReportsEnabled,
                    tunnelingEnabled = playerSettings.effectiveTunnelingEnabled &&
                            effectiveInternalPlayerEngine != InternalPlayerEngine.MVP_PLAYER
                )
            }


            setLoadingStatus(
                phase = "detecting_format",
                message = context.getString(R.string.player_loading_detecting_format)
            )

            resolveCurrentStreamMimeType(
                url = url,
                headers = headers
            )

            val afrJob = async {
                if (effectiveInternalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) {
                    // MPV has no track-format path; keep the full probing preflight.
                    // Thread the resolved mimeType through so the
                    // probe can route extensionless MP4/MKV URLs correctly.
                    runAfrPreflightIfEnabled(
                        url = url,
                        headers = headers,
                        frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                        resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled,
                        mimeType = currentStreamMimeType
                    )
                } else {
                    // ExoPlayer: cache-only, instant. On a
                    // cache miss the frame rate comes from ExoPlayer's reported
                    // track format after prepare, with no MediaExtractor/NextLib
                    // network probe on this path at all.
                    runAfrCachePreflightIfEnabled(
                        url = url,
                        headers = headers,
                        frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                        resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled
                    )
                }
            }
            if (effectiveInternalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) {
                mpvInitializationInProgress = true
                try {
                    // Never let the probe hold playback hostage -
                    // the in-probe timeouts are advisory against blocked native
                    // calls, so back them with an absolute deadline here.
                    // The probe chain is 10 s OkHttp + 10 s NextLib + 5 s
                    // extractor, so a 12 s cap can truncate its own fallback;
                    // 15 s matches the chain's stated worst-case budget.
                    withTimeoutOrNull(AFR_PREFLIGHT_ABSOLUTE_DEADLINE_MS) { afrJob.await() }
                        ?: run {
                            Log.w(PlayerRuntimeController.TAG, "AFR preflight exceeded absolute deadline; starting playback without it")
                            afrJob.cancel()
                        }
                    if (mpvDelayStartAfterAfrSwitch) {
                        Log.d(PlayerRuntimeController.TAG, "AFR display mode switched; delaying MPV start by ${MPV_AFR_SETTLE_DELAY_MS}ms")
                        delay(MPV_AFR_SETTLE_DELAY_MS)
                    }
                    setLoadingStatus(
                        phase = "mpv_buffering",
                        message = context.getString(R.string.player_loading_buffering)
                    )
                    // The MPV path reads the resume position
                    // inside initializeMpvPlayer (a plain fun), so the join
                    // happens here, in this coroutine, before entry.
                    awaitSavedProgressLoad()
                    initializeMpvPlayer(url = url, headers = headers, allowEngineFailover = allowEngineFailover)
                    fetchAddonSubtitles()
                } finally {
                    mpvInitializationInProgress = false
                }
                return@launch
            }
            mpvInitializationInProgress = false

            // ── ExoPlayer Dolby Vision Logic (mode-driven via Dv7HandlingMode) ──
            DoviBridge.resetRuntimeCounters()
            DolbyVisionConversionStats.reset()
            rebufferCount = 0
            rebufferTotalMs = 0L
            rebufferStartedAtMs = 0L
            lastSeekWallMs = 0L
            seekReadyLogPending = false
            currentRebufferSeekInduced = false

            // Resolve effective DV7 mode — AUTO consults the display-capability policy.
            // The persisted enum stays as-is; only the runtime behavior is derived per playback.
            var effectiveDv7Mode: Dv7HandlingMode
            val dv7AutoResult: DolbyVisionBaseLayerPolicy.Result?
            // AM9 native FEL: only when selected, not disabled for this stream, the display takes Dolby Vision,
            // the box exposes the Amlogic dual-layer port AND runs a FEL-capable kernel (the stock kernel
            // drops the EL with the same port present). Otherwise NATIVE_FEL resolves exactly like AUTO.
            val nativeFelDeviceBits = if (playerSettings.dv7HandlingMode == Dv7HandlingMode.NATIVE_FEL) {
                com.nuvio.tv.core.player.amlfel.AmlFelSupport.deviceBits()
            } else 0
            // FEL is only useful on a display that takes Dolby Vision; on any other display the mode must behave
            // exactly like AUTO (e.g. HDR10 base layer on an HDR10-only TV), so the AUTO policy decides that.
            val nativeFelDisplayPolicy = if (playerSettings.dv7HandlingMode == Dv7HandlingMode.NATIVE_FEL) {
                DolbyVisionBaseLayerPolicy.resolve(context = context, bridgeReady = DoviBridge.isLibraryLoaded)
            } else null
            val nativeFelActive = playerSettings.dv7HandlingMode == Dv7HandlingMode.NATIVE_FEL &&
                !nativeFelDisabledStreamUrls.contains(url) &&
                nativeFelDisplayPolicy?.displayDv == true &&
                com.nuvio.tv.core.player.amlfel.AmlFelSupport.isNativeFelUsable(nativeFelDeviceBits)
            isNativeFelActiveForCurrentPlayback = nativeFelActive
            if (playerSettings.dv7HandlingMode == Dv7HandlingMode.NATIVE_FEL) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "DV7_NATIVE_FEL: active=$nativeFelActive " +
                        "felKernel=${com.nuvio.tv.core.player.amlfel.AmlFelSupport.isFelKernel()} " +
                        "displayDv=${nativeFelDisplayPolicy?.displayDv} " +
                        "disabledForStream=${nativeFelDisabledStreamUrls.contains(url)}"
                )
            }
            val autoLikeDv7Mode = playerSettings.dv7HandlingMode == Dv7HandlingMode.AUTO ||
                (playerSettings.dv7HandlingMode == Dv7HandlingMode.NATIVE_FEL && !nativeFelActive)
            when {
                nativeFelActive -> {
                    dv7AutoResult = null
                    effectiveDv7Mode = Dv7HandlingMode.NATIVE_FEL
                }
                autoLikeDv7Mode -> {
                    val result = nativeFelDisplayPolicy ?: DolbyVisionBaseLayerPolicy.resolve(
                        context = context,
                        bridgeReady = DoviBridge.isLibraryLoaded
                    )
                    dv7AutoResult = result
                    effectiveDv7Mode = when (result.decision) {
                        DolbyVisionBaseLayerPolicy.Decision.NATIVE_DV7 -> Dv7HandlingMode.OFF
                        DolbyVisionBaseLayerPolicy.Decision.CONVERT_TO_DV81 -> Dv7HandlingMode.DV81_LIBDOVI
                        else -> Dv7HandlingMode.HDR10_BASE_LAYER
                    }
                    Log.i(
                        PlayerRuntimeController.TAG,
                        "DV7_AUTO: decision=${result.decision} " +
                                "effectiveMode=$effectiveDv7Mode " +
                                "hdrCapsKnown=${result.hdrCapsKnown} " +
                                "displayDv=${result.displayDv} " +
                                "displayHdr10=${result.displayHdr10} " +
                                "displayHdr10Plus=${result.displayHdr10Plus} " +
                                "codecDvheDtb=${result.codecSupportsDvheDtb} " +
                                "bridgeReady=${result.bridgeReady} " +
                                "api=${result.apiLevel} " +
                                "host=${url.safeHost()}"
                    )
                }
                else -> {
                    dv7AutoResult = null
                    effectiveDv7Mode = playerSettings.dv7HandlingMode
                }
            }

            // Experimental: explicit libdovi conversion-mode override. Only applies
            // when DV7 handling is Convert to DV8.1 (the modes are libdovi conversion
            // modes, so they're only meaningful while conversion is active). Picks
            // which conversion mode runs; -1 (None) uses the auto-selected mode.
            val libdoviModeOverride = playerSettings.dv7LibdoviModeOverride
            val libdoviModeOverrideActive = libdoviModeOverride in 0..4 &&
                    playerSettings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI &&
                    effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI
            if (libdoviModeOverrideActive) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "DV7_LIBDOVI_OVERRIDE: forcing conversion mode=$libdoviModeOverride"
                )
            }

            // DV7 to DV8.1 libdovi probe — only runs when the effective mode requests it.
            val dv7ToDv81SettingActive = effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI
            val dv7ToDv81Probe = if (dv7ToDv81SettingActive) {
                DoviBridge.probeRealtimeConversionSupport(url)
            } else {
                val reason = when (effectiveDv7Mode) {
                    Dv7HandlingMode.HDR10_BASE_LAYER -> "hdr10-base-layer-mode"
                    Dv7HandlingMode.STRIP_DV -> "strip-dv-mode"
                    Dv7HandlingMode.OFF -> "dv7-mode-off"
                    Dv7HandlingMode.NATIVE_FEL -> "native-fel-mode"
                    Dv7HandlingMode.AUTO -> "auto-mode-no-dv81"  // unreachable; AUTO is collapsed above
                    Dv7HandlingMode.DV81_LIBDOVI -> "setting-disabled"  // unreachable
                }
                DoviBridge.RealtimeConversionProbe(
                    supported = false,
                    reason = reason,
                    bridgeVersion = DoviBridge.getBridgeVersionOrNull(),
                    extractorHookReady = DoviBridge.isExtractorHookReadyInBuild,
                    selfTest = DoviBridge.SelfTestResult(false, "not-run", 0, 0)
                )
            }
            // A stream that previously failed with conversion armed is forced to the HEVC
            // base layer via dv7ToHevcForcedStreamUrls; that override must also disarm the
            // conversion/extractor path, otherwise the retry rebuilds the exact same broken
            // pipeline (stock extractor + no vendored MKV path) and fails identically.
            val dv7ConversionDisarmedForUrl = dv7ToHevcForcedStreamUrls.contains(url)
            isExperimentalDv7ToDv81ActiveForCurrentPlayback =
                dv7ToDv81SettingActive && dv7ToDv81Probe.supported && !dv7ConversionDisarmedForUrl
            // AUTO fallback: if AUTO chose DV81 but the probe failed for this stream,
            // downgrade to HDR10_BASE_LAYER so the user still gets a picture.
            if (autoLikeDv7Mode &&
                effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI &&
                !dv7ToDv81Probe.supported
            ) {
                effectiveDv7Mode = Dv7HandlingMode.HDR10_BASE_LAYER
                Log.i(
                    PlayerRuntimeController.TAG,
                    "DV7_AUTO_FALLBACK: dv81-probe-failed reason=${dv7ToDv81Probe.reason} " +
                            "fallback=HDR10_BASE_LAYER host=${url.safeHost()}"
                )
            }
            hasAttemptedDv7ToDv81ForCurrentPlayback = false
            dv7ToDv81BridgeVersionForCurrentPlayback = dv7ToDv81Probe.bridgeVersion
            dv7ToDv81LastProbeReasonForCurrentPlayback = dv7ToDv81Probe.reason
            Log.i(
                PlayerRuntimeController.TAG,
                "DV7_DOVI: mode=${playerSettings.dv7HandlingMode} " +
                        "effectiveMode=$effectiveDv7Mode " +
                        "dv81Active=$dv7ToDv81SettingActive " +
                        "dv5Compat=${playerSettings.dv5ToDv81Enabled} " +
                        "buildNative=${DoviBridge.isNativeEnabledInBuild} " +
                        "libraryLoaded=${DoviBridge.isLibraryLoaded} " +
                        "extractorHookReady=${dv7ToDv81Probe.extractorHookReady} " +
                        "active=${isExperimentalDv7ToDv81ActiveForCurrentPlayback} " +
                        "reason=${dv7ToDv81Probe.reason} " +
                        "selfTest=${dv7ToDv81Probe.selfTest.reason} " +
                        "bridge=${dv7ToDv81Probe.bridgeVersion ?: "n/a"} " +
                        "host=${url.safeHost()}"
            )

            // ── Diagnostics builder ──
            // Built incrementally during init; written to DataStore on terminal events
            // (first frame rendered = "Played", or final error display = "Error: ...").
            var currentDiagnostics = LastPlaybackDiagnostics(
                timestampMs = System.currentTimeMillis(),
                host = url.safeHost(),
                streamUrl = url.diagnosticStreamUrl(serverPlayback.isServerSource(url)),
                filename = currentFilename ?: streamName ?: title,
                headersJson = org.json.JSONObject(headers).toString(),
                hdrCapsKnown = dv7AutoResult?.hdrCapsKnown ?: false,
                displayDv = dv7AutoResult?.displayDv ?: false,
                displayHdr10 = dv7AutoResult?.displayHdr10 ?: false,
                displayHdr10Plus = dv7AutoResult?.displayHdr10Plus ?: false,
                // In manual modes the AUTO probe never runs; query the
                // decoder capability directly so the Diagnostics row is honest.
                codecDv7Supported = dv7AutoResult?.codecSupportsDvheDtb
                    ?: DolbyVisionBaseLayerPolicy.queryCodecDv7Support(),
                dv81DecoderName = null,
                bridgeReady = DoviBridge.isLibraryLoaded,
                bridgeVersion = dv7ToDv81Probe.bridgeVersion,
                bridgeReason = dv7ToDv81Probe.reason,
                dv7ModeRequested = playerSettings.dv7HandlingMode.name,
                dv7ModeEffective = effectiveDv7Mode.name,
                dv7AutoDecision = dv7AutoResult?.decision?.name,
                dvSourceProfile = null,
                dv7DoviCalls = 0,
                dv7DoviSuccess = 0,
                dv7DoviSignalRewrites = 0,
                bufferEngineEnabled = false,
                parallelNetworkEnabled = false,
                firstFrameMs = -1L,
                result = "Pending"
            )

            // ── Buffer & Network ──
            // Master toggles off => stock Media3 (DefaultLoadControl, single connection,
            // no cache). DV7 to DV8.1 conversion runs libdovi off-heap, outside the heap
            // budget; a large heap buffer on top of that is what pushed the Fire TV into the
            // lowmemorykiller spiral, so for confirmed DV7 on low-RAM we drop the back buffer
            // and shrink the budget at first frame (below).
            val libdoviConversionActive = effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI
            NuvioExoPlayerPerformanceHelper.updateSettings(playerSettings, context)
            NuvioExoPlayerPerformanceHelper.enabled = playerSettings.nuvioPerformanceModeEnabled
            val streamMime = currentStreamMimeType
            val isHls = streamMime != null && (
                streamMime.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true) ||
                streamMime.lowercase().contains("mpegurl") ||
                streamMime.lowercase().contains("m3u8")
            )
            val rawBandwidthMeter = if (NuvioExoPlayerPerformanceHelper.enabled) {
                NuvioExoPlayerPerformanceHelper.buildBandwidthMeter(context)
            } else {
                DefaultBandwidthMeter.Builder(context)
                    .setInitialBitrateEstimate(ADAPTIVE_INITIAL_BITRATE_ESTIMATE_BPS)
                    .build()
            }
            val bandwidthMeter = SafeBandwidthMeter(rawBandwidthMeter, isHls)
            // Player reuse: the custom-buffer budget must be visible at the build fork so a
            // reused BitrateAwareLoadControl can be reset to build-equivalent state.
            var customBufferBudgetBytes = 0L
            val loadControl = if (playerSettings.nuvioPerformanceModeEnabled) {
                effectiveBackBufferDurationMs = NuvioExoPlayerPerformanceHelper.backBufferMs
                currentBitrateAwareLoadControl = null
                Log.i(
                    PlayerRuntimeController.TAG,
                    "BUFFER_GATE: engine=exo-native-perf master=on; NuvioExoPlayerPerformanceHelper.buildLoadControl host=${url.safeHost()}"
                )
                NuvioExoPlayerPerformanceHelper.buildLoadControl(context)
            } else if (playerSettings.bufferEngineEnabled) {
                val bufferSettings = playerSettings.bufferSettings
                // Managed (default) caps the buffer at the device budget; off uses Target Buffer Size.
                // Stay full here even on a DV display; first frame tightens only for confirmed DV7.
                val budgetManaged = playerSettings.bufferBudgetManaged
                val budgetMbEffective = if (budgetManaged) {
                    MemoryBudget.budgetMb
                } else {
                    MemoryBudget.effectiveBufferMb(bufferSettings.targetBufferSizeMb)
                        .coerceAtLeast(MemoryBudget.MIN_BUFFER_MB)
                }
                customBufferBudgetBytes = budgetMbEffective.toLong() * 1024L * 1024L
                // Build with the user's back buffer so seek-back works immediately (it can't
                // depend on the player re-polling the LoadControl). First frame only lowers it
                // to 0 for confirmed DV7 on low-RAM; everything else keeps it.
                configuredBackBufferMs = bufferSettings.backBufferDurationMs
                val backBufferMsAtBuild = configuredBackBufferMs
                Log.i(
                    PlayerRuntimeController.TAG,
                    "BUFFER_GATE: engine=exo-custom master=on " +
                            "lowRam=${MemoryBudget.isLowRamTier} " +
                            "allowLarge=${playerSettings.allowLargeTargetBuffer} " +
                            "dv7conv=$libdoviConversionActive " +
                            "managed=$budgetManaged " +
                            "backBufferMsAtBuild=$backBufferMsAtBuild (set=$configuredBackBufferMs, lowered to 0 only for real DV7) " +
                            "budgetMb=$budgetMbEffective host=${url.safeHost()}"
                )
                effectiveBackBufferDurationMs = backBufferMsAtBuild
                val allocator = androidx.media3.exoplayer.upstream.DefaultAllocator(
                    true,
                    C.DEFAULT_BUFFER_SEGMENT_SIZE,
                    64,
                    playerSettings.nuvioPerformanceModeEnabled
                )
                // Both engines report through the same sampler, so whichever built last owns it.
                NuvioExoPlayerPerformanceHelper.liveAllocator = allocator
                BitrateAwareLoadControl(
                    minBufferMs = bufferSettings.minBufferMs,
                    maxBufferMs = bufferSettings.maxBufferMs,
                    bufferForPlaybackMs = bufferSettings.bufferForPlaybackMs,
                    bufferForPlaybackAfterRebufferMs = bufferSettings.bufferForPlaybackAfterRebufferMs,
                    // Allow buffering past the byte budget until the minimum time threshold is
                    // met. Without this, high-bitrate remux files (e.g. 100+ Mbps UHD MKV with
                    // multiple audio tracks) exhaust the 500MB byte cap in <5s of content before
                    // minBufferMs is satisfied, leaving ExoPlayer stuck in STATE_BUFFERING.
                    prioritizeTimeOverSizeThresholds = true,
                    backBufferDurationMs = backBufferMsAtBuild,
                    retainBackBufferFromKeyframe = true,
                    budgetBytes = customBufferBudgetBytes,
                    allocator = allocator
                ).also { currentBitrateAwareLoadControl = it }
            } else {
                // Stock LoadControl: DefaultLoadControl configured with 1.5s back buffer so 1s rewind doesn't clear buffer.
                effectiveBackBufferDurationMs = 1_500
                currentBitrateAwareLoadControl = null
                Log.i(
                    PlayerRuntimeController.TAG,
                    "BUFFER_GATE: engine=exo-stock master=off; DefaultLoadControl " +
                            "(1.5s back buffer, no VOD cache) host=${url.safeHost()}"
                )
                // Stock load control with a 1.5 s back buffer; the read-ahead reserve is idle unless thumbnails ask.
                com.nuvio.tv.core.player.ReservableLoadControl.stock(backBufferMs = 1_500)
            }
            _loadControl = loadControl

            // VOD cache sits under the buffer master in the UI, so gate it the same way at
            // runtime. The low-RAM + confirmed DV7 case is handled dynamically at first frame
            // (back buffer shrink + budget reduction) rather than blanket-disabling user
            // settings at init, since the stream content isn't known yet at this point.
            val bufferEngineEffective = playerSettings.bufferEngineEnabled
            applyMediaSourceFactorySettings(playerSettings)
            if (isTorrentStream) {
                mediaSourceFactory.useParallelConnections = false
            }

            // Log the effective state (post-gating), not the raw settings.
            val engineNative = androidx.media3.common.NuvioEngineConfig.get().isNativeAllocationEnabled()
            val effectiveNative = mediaSourceFactory.nuvioPerformanceModeEnabled || engineNative
            Log.i(
                PlayerRuntimeController.TAG,
                "BUFFER_NETWORK: bufferEngine=${playerSettings.bufferEngineEnabled} " +
                        "parallelNetwork=${playerSettings.parallelNetworkEnabled} " +
                        "useParallel=${mediaSourceFactory.useParallelConnections} " +
                        "nuvioPerf=${mediaSourceFactory.nuvioPerformanceModeEnabled} " +
                        "engineNative=$engineNative useNativeEffective=$effectiveNative " +
                        "vodCache=${mediaSourceFactory.vodCacheEnabled} " +
                        "host=${url.safeHost()}"
            )

            currentDiagnostics = currentDiagnostics.copy(
                bufferEngineEnabled = playerSettings.bufferEngineEnabled,
                parallelNetworkEnabled = playerSettings.parallelNetworkEnabled
            )

            val safeAudioModeEnabled = safeAudioForcedStreamUrls.contains(url)
            val audioDisabledForStream = audioDisabledForcedStreamUrls.contains(url)
            val vc1TrackSelectionBypassActive = vc1TrackSelectionBypassStreamUrls.contains(url)
            isSafeAudioModeActiveForCurrentPlayback = safeAudioModeEnabled
            isAudioDisabledForCurrentPlayback = audioDisabledForStream
            isVc1TrackSelectionBypassActiveForCurrentPlayback = vc1TrackSelectionBypassActive

            val startupSubtitlePreparation = prepareStreamStartSubtitles(playerSettings)
            // Absolute deadline (see MPV path above).
            withTimeoutOrNull(AFR_PREFLIGHT_ABSOLUTE_DEADLINE_MS) { afrJob.await() }
                ?: run {
                    Log.w(PlayerRuntimeController.TAG, "AFR preflight exceeded absolute deadline; starting playback without it")
                    afrJob.cancel()
                }

            // ── Libass Setup (From 0.5.7-beta/Left) ──
            requestedUseLibassByUser = playerSettings.useLibass
            val useLibass = when {
                !requestedUseLibassByUser -> false
                libassPipelineOverrideForCurrentStream != null -> libassPipelineOverrideForCurrentStream == true
                else -> true
            }
            val requestedLibassRenderType = playerSettings.libassRenderType.toAssRenderType()
            val libassRenderType = requestedLibassRenderType
            _uiState.update {
                it.copy(
                    useLibass = useLibass,
                    libassRenderType = playerSettings.libassRenderType
                )
            }
            // ── Track Selector Setup ──
            val adaptiveTrackSelectionFactory = AdaptiveTrackSelection.Factory(
                ADAPTIVE_QUALITY_INCREASE_MIN_DURATION_MS,
                AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
                AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
                AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION
            )
            trackSelector = object : DefaultTrackSelector(context, adaptiveTrackSelectionFactory) {
                override fun selectAllTracks(
                    mappedTrackInfo: MappedTrackInfo,
                    rendererFormatSupports: Array<out Array<out IntArray>>,
                    rendererMixedMimeTypeAdaptationSupports: IntArray,
                    params: Parameters
                ): Array<ExoTrackSelection.Definition?> {
                    val streamMime = currentStreamMimeType
                    val isHls = streamMime != null && (
                        streamMime.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true) ||
                        streamMime.lowercase().contains("mpegurl") ||
                        streamMime.lowercase().contains("m3u8")
                    )
                    Log.d("NuvioTrackSelector", "selectAllTracks run: streamMime=$streamMime, isHls=$isHls")
                    promotePassthroughAudioWhenRendererAlive(
                        mappedTrackInfo,
                        rendererFormatSupports,
                        passthroughPolicy = currentAudioPassthroughPolicy,
                        audioSink = playbackSpeedAwareAudioSink
                    )
                    demoteAudioTunnelingWhereItCannotBeClocked(
                        mappedTrackInfo,
                        rendererFormatSupports,
                        ffmpegRendererName = ffmpegAudioRenderer?.name,
                        audioSink = playbackSpeedAwareAudioSink,
                        deadClockAudioClasses = PlayerTunnelAvSyncPolicy.deadAudioClasses
                    )
                    if (isHls) {
                        for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                            if (mappedTrackInfo.getRendererType(rendererIndex) == C.TRACK_TYPE_VIDEO) {
                                val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                                for (groupIndex in 0 until trackGroups.length) {
                                    val group = trackGroups[groupIndex]
                                    for (trackIndex in 0 until group.length) {
                                        val format = group.getFormat(trackIndex)
                                        val support = rendererFormatSupports[rendererIndex][groupIndex][trackIndex]
                                        val formatSupport = RendererCapabilities.getFormatSupport(support)
                                        Log.d("NuvioTrackSelector", "Evaluating track: id=${format.id}, res=${format.width}x${format.height}, mime=${format.sampleMimeType}, codecs=${format.codecs}, support=${formatSupport}")
                                        if (formatSupport == C.FORMAT_EXCEEDS_CAPABILITIES) {
                                            val mime = format.sampleMimeType
                                            val isAvcOrHevc = mime == MimeTypes.VIDEO_H264 || mime == MimeTypes.VIDEO_H265
                                            val isAtMost1080p = format.width <= 1920 && format.height <= 1080
                                            val codecs = format.codecs?.lowercase() ?: ""
                                            val is10Bit = codecs.contains("main10") || codecs.contains("hevc.2") || codecs.contains("hev2")
                                            val isHdr = format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084
                                            val isStandard8Bit = !is10Bit && !isHdr

                                            Log.d("NuvioTrackSelector", "Conditions for id=${format.id}: isAvcOrHevc=$isAvcOrHevc, isAtMost1080p=$isAtMost1080p, isStandard8Bit=$isStandard8Bit")
                                            if (isAvcOrHevc && isAtMost1080p && isStandard8Bit) {
                                                Log.i("NuvioTrackSelector", "Upgraded track support to FORMAT_HANDLED for id=${format.id}")
                                                rendererFormatSupports[rendererIndex][groupIndex][trackIndex] =
                                                    RendererCapabilities.create(
                                                        C.FORMAT_HANDLED,
                                                        RendererCapabilities.ADAPTIVE_SEAMLESS,
                                                        RendererCapabilities.getTunnelingSupport(support),
                                                        RendererCapabilities.getHardwareAccelerationSupport(support),
                                                        RendererCapabilities.getDecoderSupport(support)
                                                    )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    var forceVc1VideoSelection = false
                    for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                        if (mappedTrackInfo.getRendererType(rendererIndex) == C.TRACK_TYPE_VIDEO) {
                            val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                            for (groupIndex in 0 until trackGroups.length) {
                                val group = trackGroups[groupIndex]
                                for (trackIndex in 0 until group.length) {
                                    val format = group.getFormat(trackIndex)
                                    val support = rendererFormatSupports[rendererIndex][groupIndex][trackIndex]
                                    val formatSupport = RendererCapabilities.getFormatSupport(support)
                                    if (Vc1VideoFormatHeuristics.isLikelyVc1(format.sampleMimeType, format.codecs, format.label) &&
                                        formatSupport != C.FORMAT_HANDLED &&
                                        formatSupport != C.FORMAT_UNSUPPORTED_DRM
                                    ) {
                                        forceVc1VideoSelection = true
                                        Log.i("NuvioTrackSelector", "Upgraded VC-1 track support to FORMAT_HANDLED so ExoPlayer attempts decoding: id=${format.id}")
                                        rendererFormatSupports[rendererIndex][groupIndex][trackIndex] =
                                            RendererCapabilities.create(
                                                C.FORMAT_HANDLED,
                                                RendererCapabilities.ADAPTIVE_SEAMLESS,
                                                RendererCapabilities.getTunnelingSupport(support),
                                                RendererCapabilities.getHardwareAccelerationSupport(support),
                                                RendererCapabilities.getDecoderSupport(support)
                                            )
                                    }
                                }
                            }
                        }
                    }
                    val selectionParams = if (forceVc1VideoSelection) {
                        params.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                            .setExceedVideoConstraintsIfNecessary(true)
                            .setExceedRendererCapabilitiesIfNecessary(true)
                            .setTunnelingEnabled(false)
                            .build()
                    } else {
                        params
                    }
                    return super.selectAllTracks(
                        mappedTrackInfo,
                        rendererFormatSupports,
                        rendererMixedMimeTypeAdaptationSupports,
                        selectionParams
                    )
                }
            }.apply {
                setParameters(buildUponParameters().setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true))
                isTunnelingActiveForCurrentPlayback = playerSettings.effectiveTunnelingEnabled &&
                    !safeAudioModeEnabled && !tunnelingDisabledStreamUrls.contains(url)
                if (isTunnelingActiveForCurrentPlayback) {
                    setParameters(buildUponParameters().setTunnelingEnabled(true))
                } else if (safeAudioModeEnabled) {
                    setParameters(buildUponParameters().setTunnelingEnabled(false).setConstrainAudioChannelCountToDeviceCapabilities(true))
                }
                if (audioDisabledForStream) {
                    setParameters(buildUponParameters().setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO)))
                }
                if (vc1TrackSelectionBypassActive ||
                    Vc1VideoFormatHeuristics.isLikelyVc1Stream(
                        _uiState.value.currentStreamName,
                        streamName,
                        currentFilename,
                        currentStreamDescription,
                        url
                    )
                ) {
                    setParameters(
                        buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                            .setExceedVideoConstraintsIfNecessary(true)
                            .setExceedRendererCapabilitiesIfNecessary(true)
                            .setForceHighestSupportedBitrate(true)
                    )
                }

                if (preferredAudioLanguages.isNotEmpty()) {
                    setParameters(buildUponParameters().setPreferredAudioLanguages(*preferredAudioLanguages.toTypedArray()))
                }

                val captioningManager = context?.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
                if (captioningManager != null) {
                    if (!captioningManager.isEnabled) {
                        setParameters(buildUponParameters().setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT))
                    }
                    captioningManager.locale?.let { locale ->
                        setParameters(buildUponParameters().setPreferredTextLanguage(locale.isO3Language))
                    }
                }
                // Forced mode: disable ExoPlayer auto text selection; our logic handles it.
                if (playerSettings.subtitleStyle.useForcedSubtitles) {
                    setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true))
                }
                // When forced subtitles are disabled, tell ExoPlayer to ignore
                // SELECTION_FLAG_FORCED so it won't auto-select forced tracks.
                if (!playerSettings.subtitleStyle.useForcedSubtitles) {
                    val currentFlags = parameters.ignoredTextSelectionFlags
                    setParameters(
                        buildUponParameters().setIgnoredTextSelectionFlags(
                            currentFlags or C.SELECTION_FLAG_FORCED
                        )
                    )
                }
            }

            // ── Extractors & DV Hook ──
            val extractorsFactory: ExtractorsFactory = AviTrackFormatExtractorsFactory(
                DefaultExtractorsFactory()
                    .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                    .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
            )

            // Manual Convert-to-DV8.1 uses mode 2; if a prior attempt at this stream
            // failed to play, force mode 1 this time (before the HDR10 fallback).
            val dv7Mode1Forced = dv7Mode1ForcedStreamUrls.contains(url)
            val manualDv81Selected = playerSettings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI
            isManualDv81Mode2ActiveForCurrentPlayback =
                manualDv81Selected &&
                effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI &&
                !libdoviModeOverrideActive &&
                !dv7Mode1Forced
            // DV7 conversion is handled app-side by DolbyVisionExtractorsFactory and the
            // vendored Matroska extractor (wired into effectiveExtractorsFactory below).
            if (isExperimentalDv7ToDv81ActiveForCurrentPlayback &&
                dv7ToDv81LastProbeReasonForCurrentPlayback != "ready") {
                dv7ToDv81LastProbeReasonForCurrentPlayback = "app-extractor-factory"
            }

            audioDelayUs.set(_uiState.value.audioDelayMs.toLong() * 1000L)
            subtitleDelayUs.set(_uiState.value.subtitleDelayMs.toLong() * 1000L)

            // ── Fallback Codec Setup ──
            // mapDv7ToHevc is now driven by effective mode (HDR10_BASE_LAYER strips DV7),
            // OR the error handler's per-stream override (preserved for retry-after-failure).
            val mapDv7ToHevcEnabled = effectiveDv7Mode == Dv7HandlingMode.HDR10_BASE_LAYER ||
                    dv7ToHevcForcedStreamUrls.contains(url)
            val isHdr10BaseLayerModeActive = when {
                nativeFelActive -> false
                autoLikeDv7Mode -> dv7AutoResult?.displayDv != true
                else -> effectiveDv7Mode == Dv7HandlingMode.HDR10_BASE_LAYER ||
                        effectiveDv7Mode == Dv7HandlingMode.STRIP_DV
            }
            com.nuvio.tv.core.player.dvmkv.DolbyVisionCompatibility.setHdr10BaseLayerModeActive(isHdr10BaseLayerModeActive)
            isMapDv7ToHevcActiveForCurrentPlayback = mapDv7ToHevcEnabled
            // Key this off the effective mode, not the AUTO policy
            // result. dv7AutoResult is null in every manual mode, so gating on it
            // would withhold findDvDecodersIgnoringProfile() from users who
            // manually select Convert-to-DV8.1 - exactly the hidden-DV-decoder
            // hardware that fallback selector is for. effectiveDv7Mode
            // already collapses AUTO to DV81_LIBDOVI when the policy chose convert.
            val convertToDv81Active = !mapDv7ToHevcEnabled &&
                    effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI
            val codecSelector = createDolbyVisionFallbackCodecSelector(
                convertToDv81Active = convertToDv81Active
            )
            val preferFfmpegAudioActive = preferFfmpegAudioStreamUrls.contains(url)
            // Bluetooth media sink (A2DP / LE Audio): Media3 only advertises PCM. Do not attempt
            // optical/HDMI passthrough — decode to PCM and let the BT stack encode SBC/AAC/aptX/LDAC.
            val isBluetoothAudioOutput = currentAudioOutputRoute?.isBluetooth == true ||
                AudioOutputRouteDetector.isBluetoothMediaOutput(context)
            // Force-optical must never win over Bluetooth: AC3/DTS AudioTrack to A2DP fails hard.
            val isForcePassthroughActive = !isBluetoothAudioOutput &&
                playerSettings.forceOpticalPassthrough &&
                playerSettings.decoderPriority != 0
            // Force-AC3 does not escalate the *global* extension mode to
            // PREFER (that would put software AV1 video decode ahead of
            // MediaCodec). The FFmpeg audio renderer is instead reordered
            // audio-locally in buildAudioRenderers. Bluetooth keeps the
            // global PREFER, paired with preferSoftwareAudioOnly
            // below, which demotes the video renderers back to MediaCodec-first.
            val effectiveDecoderPriority = if (hasTriedAudioPcmFallback || isBluetoothAudioOutput) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            } else if (isForcePassthroughActive) {
                maxOf(playerSettings.decoderPriority, DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            } else {
                playerSettings.decoderPriority
            }
            // A2DP is stereo; force a clean 2.0 downmix so surround content is audible and balanced.
            val bluetoothStereoDownmix = isBluetoothAudioOutput
            val effectiveDownmixEnabled = playerSettings.effectiveDownmixEnabled || bluetoothStereoDownmix
            val effectiveAudioOutputChannels = if (bluetoothStereoDownmix) {
                com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_2_0
            } else {
                playerSettings.audioOutputChannels
            }
            if (isBluetoothAudioOutput) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "Bluetooth media output active (route=${currentAudioOutputRoute?.key}): " +
                        "PCM-only sink, stereo downmix, no optical passthrough"
                )
            }

            // ── Renderers Factory (Combining Libass offsets + Audio Gain + Video Fallback) ──
            val currentRouteKey = currentAudioOutputRoute?.key
            // A distinct token is consumed once per profile, including across process restarts.
            val clearLearnedAudioRejections = playerSettingsDataStore.consumeAudioRejectionReset(
                am9SystemProperty("debug.nuvio.clear_audio_rejections").orEmpty()
            )
            if (initializationGeneration != playbackRecoveryGate.generation) return@launch
            val surroundSettings = if (clearLearnedAudioRejections) {
                Log.i(PlayerRuntimeController.TAG, "Learned audio rejections reset")
                playerSettings.copy(audioRejectionsSeen = emptySet(), audioRejectionsConfirmed = emptySet())
                    .also { currentPlayerSettingsForReport = it }
            } else {
                playerSettings
            }
            val softwareDecodersAvailable =
                effectiveDecoderPriority != DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            val surroundInputs = SurroundResolveInputs(
                routeKey = currentRouteKey,
                isBluetooth = isBluetoothAudioOutput,
                softwareDecodersAvailable = softwareDecodersAvailable,
                forceOpticalActive = isForcePassthroughActive,
                effectiveDownmixEnabled = effectiveDownmixEnabled,
                effectiveAudioOutputChannels = effectiveAudioOutputChannels
            )
            surroundResolveInputs = surroundInputs
            val surround = resolveSurroundForRoute(context, surroundSettings, surroundInputs)
            val surroundResolution = surround.resolution
            tunnelDeadClockSignature = if (isBluetoothAudioOutput || currentRouteKey == null) {
                null
            } else {
                val chain = AudioChainProbe.snapshot(context, currentRouteKey)
                PlayerTunnelAvSyncPolicy.chainSignature(
                    fingerprint = Build.FINGERPRINT,
                    routeKey = currentRouteKey,
                    direct = chain.direct,
                    maxPcmChannels = chain.maxPcmChannels,
                )
            }
            tunnelDeadClockSignature?.let { signature ->
                val seeded = PlayerTunnelAvSyncPolicy.seedFromStore(
                    classes = playerSettings.tunnelDeadAudioClasses,
                    storedSignature = playerSettings.tunnelDeadAudioSignature,
                    currentSignature = signature,
                )
                if (seeded.isNotEmpty()) {
                    Log.i(
                        PlayerRuntimeController.TAG,
                        "TUNNEL_AV_SYNC: dead-clock memo restored for $seeded"
                    )
                }
            }
            val surroundTargetChannels = surround.targetChannels
            val surroundDownmixEnabled = surround.downmixEnabled
            val surroundAudioOutputChannels = surround.audioOutputChannels
            if (!surroundResolution.policy.allowsEverything() || surroundTargetChannels != null) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "SURROUND_RESOLVE: route=$currentRouteKey policy=[ac3=${surroundResolution.policy.allowAc3} " +
                        "eac3=${surroundResolution.policy.allowEac3} truehd=${surroundResolution.policy.allowTrueHd} " +
                        "dts=${surroundResolution.policy.allowDts} dtshd=${surroundResolution.policy.allowDtsHd} " +
                        "learned=${surroundResolution.policy.learnedDeniedGroups}] " +
                        "transcodePreferred=${surroundResolution.transcodePreferred} " +
                        "channelTarget=$surroundTargetChannels tvArcSoundbar=${surroundSettings.surroundTvArcSoundbar}"
                )
                queuePlaybackRawEventLine(
                    "surround_resolve route=$currentRouteKey " +
                        "ac3=${surroundResolution.policy.allowAc3} eac3=${surroundResolution.policy.allowEac3} " +
                        "truehd=${surroundResolution.policy.allowTrueHd} dts=${surroundResolution.policy.allowDts} " +
                        "dtshd=${surroundResolution.policy.allowDtsHd} transcodePreferred=${surroundResolution.transcodePreferred} " +
                        "channelTarget=$surroundTargetChannels tvArcSoundbar=${surroundSettings.surroundTvArcSoundbar}"
                )
            }

            currentAudioPassthroughPolicy = surroundResolution.policy
            lastAppliedSurroundResolve = surround

            val deniedTranscodeMimes = surround.deniedTranscodeMimes
            if (deniedTranscodeMimes.isNotEmpty()) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "SURROUND_TRANSCODE: route=$currentRouteKey mimes=$deniedTranscodeMimes"
                )
                queuePlaybackRawEventLine(
                    "surround_transcode route=$currentRouteKey " +
                        "mimes=${deniedTranscodeMimes.joinToString(",")}"
                )
            }
            val nativeSelectionOwner = Any()
            val renderersFactory = SubtitleOffsetRenderersFactory(
                audioPassthroughPolicy = surroundResolution.policy,
                context = context,
                subtitleDelayUsProvider = subtitleDelayUs::get,
                audioDelayUsProvider = audioDelayUs::get,
                shouldNormalizeCuePositionProvider = { true },
                presentCue = { presentSubtitleCue(it) },
                shouldStripSdhProvider = {
                    currentPlayerSettingsForReport.subtitleStyle.stripSdh
                },
                isBuiltInSubtitleProvider = {
                    _uiState.value.selectedAddonSubtitle == null
                },
                isSidecarAddonSubtitleActiveProvider = {
                    isSidecarAddonSubtitleActive()
                },
                videoBoundsFractionProvider = {
                    val pv = exoPlayerView
                    if (pv != null) pv.videoBoundsFraction(videoAspectRatio) else null
                },
                gainAudioProcessor = gainAudioProcessor,
                downmixEnabled = surroundDownmixEnabled,
                audioOutputChannels = surroundAudioOutputChannels,
                downmixNormalizationEnabled = !playerSettings.maintainOriginalAudioOnDownmix,
                forceOpticalPassthrough = isForcePassthroughActive,
                useSystemPassthrough = playerSettings.useSystemPassthrough,
                deniedTranscodeMimes = deniedTranscodeMimes,
                preferFfmpegAudio = preferFfmpegAudioActive,
                bluetoothForcePcm = isBluetoothAudioOutput,
                nativeFelActive = nativeFelActive,
                onNativeSelectionChanged = { selected -> nativeVideoSelection.update(nativeSelectionOwner, selected) },
                nativeMelEnabled = am9SystemProperty("debug.nuvio.native_dv7_mel") != "0",
                playbackSpeedProvider = { _uiState.value.playbackSpeed },
                initialForcePcm = hasTriedAudioPcmFallback || isBluetoothAudioOutput,
                // An audio fallback must not move video to the software decoders (AV1 would leave the
                // hardware decoder) unless the user chose app decoders first.
                preferSoftwareAudioOnly = isBluetoothAudioOutput ||
                    (hasTriedAudioPcmFallback &&
                        playerSettings.decoderPriority != DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER),
                vendorUnstablePts = am9SystemProperty("debug.nuvio.aml_unstable_pts") == "1",
                mapDv7ToHevc = mapDv7ToHevcEnabled,
                onPlaybackSpeedAwareAudioSinkCreated = { playbackSpeedAwareAudioSink = it },
                onAudioDiagnosticEvent = { line -> queuePlaybackRawEventLine(line) },
                onFfmpegAudioRendererChanged = { renderer ->
                    ffmpegAudioRenderer = renderer
                    renderer?.applyDownmixSettings(
                        downmixEnabled = surroundDownmixEnabled,
                        audioOutputChannels = surroundAudioOutputChannels,
                        downmixNormalizationEnabled = !playerSettings.maintainOriginalAudioOnDownmix,
                        forceOpticalPassthrough = isForcePassthroughActive,
                        deniedTranscodeMimes = deniedTranscodeMimes
                    )
                    applyCenterMixLevel(_uiState.value.centerMixLevelDb)
                    updateAudioControlAvailability()
                }
            ).setExtensionRendererMode(effectiveDecoderPriority)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(codecSelector)
                .applyMapDv7ToHevcIfSupported(mapDv7ToHevcEnabled)

            // The app-level factory performs DV7 conversion for the in-band-RPU containers
            // (MP4/fMP4/TS); MKV goes through the vendored extractor. Pass-through for non-DV.
            val stripDvRpuEnabled = playerSettings.dv7HandlingMode == Dv7HandlingMode.STRIP_DV ||
                    effectiveDv7Mode == Dv7HandlingMode.HDR10_BASE_LAYER
            if (stripDvRpuEnabled) {
                Log.i(PlayerRuntimeController.TAG, "DV_RPU_STRIP: enabled — will remove DV RPU NALs")
            }
            val stripHdr10PlusSei = playerSettings.stripHdr10PlusSei
            if (stripHdr10PlusSei) {
                Log.i(PlayerRuntimeController.TAG, "HDR10PLUS_STRIP: enabled — will remove HDR10+ SEI NALs")
            }

            // The factory is always used. When no DV
            // feature is active it wraps with an inactive config - only the
            // Matroska swap fires (for the DTS-HD sniff); MP4/TS extractors are
            // returned untouched via the early return in wrap().
            val dolbyVisionExtractorsFactory: ExtractorsFactory =
                    DolbyVisionExtractorsFactory(
                        delegate = extractorsFactory,
                        config = DolbyVisionConversionConfig(
                            active = isExperimentalDv7ToDv81ActiveForCurrentPlayback || nativeFelActive,
                            nativeProfile7 = nativeFelActive,
                            forcedMode = when {
                                libdoviModeOverrideActive -> libdoviModeOverride
                                dv7Mode1Forced -> 1
                                else -> -1
                            },
                            dv5Enabled = playerSettings.dv5ToDv81Enabled,
                            manualDv81 = manualDv81Selected && !dv7Mode1Forced
                        ),
                        stripDvRpu = stripDvRpuEnabled,
                        stripHdr10PlusSei = stripHdr10PlusSei,
                        injectHdr10Sei = playerSettings.injectHdr10MetadataOnStrip
                    )
            // The thumbnail tap wraps the outermost factory. The libass path takes the unwrapped one and wraps
            // after its own Matroska swap.
            val effectiveExtractorsFactory: ExtractorsFactory =
                com.nuvio.tv.core.player.thumbnail.PlaybackTap.wrap(
                    autoSyncExtractorsFactory(dolbyVisionExtractorsFactory, url, headers) // AutoSync hook
                )

            setLoadingStatus(
                phase = "building_player",
                message = context.getString(R.string.player_loading_building)
            )
            // ── Build ExoPlayer ──
            val buildDefaultPlayer = {
                // The actual MediaSource is built by mediaSourceFactory.createMediaSource()
                // (setMediaSource below), NOT the DefaultMediaSourceFactory on the builder.
                // So the DV7 app-level factory must be wired in here, otherwise
                // createMediaSource falls back to a plain DefaultExtractorsFactory and the
                // conversion never runs. (The libass path wires it via buildWithAssSupportCompat.)
                mediaSourceFactory.configureSubtitleParsing(
                    // Always pass the wrapped factory so the
                    // MKV DTS-HD sniff applies on this path too.
                    extractorsFactory = effectiveExtractorsFactory,
                    subtitleParserFactory = null
                )
                val playerDataSourceFactory = LoggingDataSourceFactory(PlayerPlaybackNetworking.createDataSourceFactory(context, headers), "BUILDER_DEFAULT")
                ExoPlayer.Builder(context)
                    .setBandwidthMeter(bandwidthMeter)
                    .setTrackSelector(trackSelector!!)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(playerDataSourceFactory, effectiveExtractorsFactory))
                    .setRenderersFactory(renderersFactory)
                    .setLoadControl(loadControl)
                    .setReleaseTimeoutMs(PLAYER_RELEASE_TIMEOUT_MS)
                    .setVideoChangeFrameRateStrategy(nuvioFrameRateStrategy(playerSettings.frameRateMatchingMode))
                    .build()
            }

            val constructionFingerprint = ExoConstructionFingerprint(
                useLibass = useLibass,
                isHls = isHls,
                performanceModeEnabled = playerSettings.nuvioPerformanceModeEnabled,
                bufferEngineEnabled = playerSettings.bufferEngineEnabled,
                minBufferMs = playerSettings.bufferSettings.minBufferMs,
                maxBufferMs = playerSettings.bufferSettings.maxBufferMs,
                bufferForPlaybackMs = playerSettings.bufferSettings.bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs = playerSettings.bufferSettings.bufferForPlaybackAfterRebufferMs,
                backBufferDurationMs = playerSettings.bufferSettings.backBufferDurationMs,
                targetBufferSizeMb = playerSettings.bufferSettings.targetBufferSizeMb,
                bufferBudgetManaged = playerSettings.bufferBudgetManaged,
                allowLargeTargetBuffer = playerSettings.allowLargeTargetBuffer,
                downmixEnabled = surroundDownmixEnabled,
                audioOutputChannels = surroundAudioOutputChannels,
                maintainOriginalAudioOnDownmix = playerSettings.maintainOriginalAudioOnDownmix,
                forceOpticalPassthroughActive = isForcePassthroughActive,
                useSystemPassthrough = playerSettings.useSystemPassthrough,
                initialForcePcm = hasTriedAudioPcmFallback,
                audioPassthroughPolicy = surroundResolution.policy,
                deniedTranscodeMimes = deniedTranscodeMimes,
                preferFfmpegAudio = preferFfmpegAudioActive,
                extensionRendererMode = effectiveDecoderPriority,
                convertToDv81Active = convertToDv81Active,
                mapDv7ToHevc = mapDv7ToHevcEnabled,
                // Record the effective flag (raw toggle gated by
                // prefer-app decoder): construction uses it, so reuse must too.
                tunnelingEnabled = playerSettings.effectiveTunnelingEnabled,
                nativeFelActive = nativeFelActive
            )
            val reuseCandidatePlayer = _exoPlayer
            // Native renderers bind stats and decoder ownership to one playback session.
            val reuseLivePlayer = !nativeFelActive && reuseCandidatePlayer != null &&
                !useLibass && !activePlayerUsesLibass &&
                previousTrackSelectorForReuse != null &&
                constructionFingerprint == lastExoConstructionFingerprint
            if (reuseLivePlayer) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "PLAYER_REUSE: reusing live ExoPlayer across transition; " +
                        "fingerprint unchanged host=${url.safeHost()}"
                )
                // Drop the previous stream's listeners before anything below can
                // fire a callback into them: a playWhenReady flip observed by the
                // old listener would set userPausedManually and start the new
                // stream paused.
                currentExoPlayerListener?.let { staleListener ->
                    runCatching { reuseCandidatePlayer!!.removeListener(staleListener) }
                }
                currentExoPlayerListener = null
                currentExoAnalyticsListener?.let { staleListener ->
                    runCatching { reuseCandidatePlayer!!.removeAnalyticsListener(staleListener) }
                }
                currentExoAnalyticsListener = null
                runCatching { reuseCandidatePlayer!!.playWhenReady = false }
                // The freshly derived companions cannot be installed on a live
                // player; restore the live instances and re-apply the fresh
                // per-stream state onto them.
                previousTrackSelectorForReuse!!.setParameters(trackSelector!!.parameters)
                trackSelector = previousTrackSelectorForReuse
                _loadControl = previousLoadControlForReuse
                currentBitrateAwareLoadControl = previousBitrateAwareLoadControlForReuse
                previousBitrateAwareLoadControlForReuse?.let { liveLoadControl ->
                    // The back buffer is NOT re-applied here: media3 captured it when this
                    // player was first constructed and will not re-read it. Report the
                    // captured value rather than the newly configured one (which the engine
                    // never adopted) or 0 (the per-stream reset at the top of initializePlayer).
                    effectiveBackBufferDurationMs = previousEffectiveBackBufferMsForReuse
                    liveLoadControl.setBudgetBytesOverride(customBufferBudgetBytes)
                }
                reuseCandidatePlayer!!.setVideoChangeFrameRateStrategy(
                    nuvioFrameRateStrategy(playerSettings.frameRateMatchingMode)
                )
                // The rebuild path wires the per-stream extractors factory inside
                // buildDefaultPlayer(); the reuse path must wire it itself so DV
                // conversion and subtitle parsing follow the new stream.
                mediaSourceFactory.configureSubtitleParsing(
                    extractorsFactory = effectiveExtractorsFactory,
                    subtitleParserFactory = null
                )
            } else {
            // The settle lets a just-released decoder and its surface stand
            // down before the next one is built. On a FRESH start nothing was
            // released, so it is skipped: beyond its own cost, the delay holds
            // back prepare(), which opens the datasource and starts chunk 0.
            if (disposeExoPlayerBeforeRebuild()) {
                delay(PLAYER_REBUILD_SETTLE_DELAY_MS)
            }

            if (initializationGeneration != playbackRecoveryGate.generation) return@launch
            nativeVideoSelection.activate(nativeSelectionOwner)
            _exoPlayer = if (useLibass) {
                val playerDataSourceFactory = LoggingDataSourceFactory(PlayerPlaybackNetworking.createDataSourceFactory(context, headers), "BUILDER_LIBASS")
                ExoPlayer.Builder(context)
                    .setBandwidthMeter(bandwidthMeter)
                    .setLoadControl(loadControl)
                    .setTrackSelector(trackSelector!!)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(playerDataSourceFactory, effectiveExtractorsFactory))
                    .setReleaseTimeoutMs(PLAYER_RELEASE_TIMEOUT_MS)
                    .setVideoChangeFrameRateStrategy(nuvioFrameRateStrategy(playerSettings.frameRateMatchingMode))
                    .buildWithAssSupportCompat(
                        context = context,
                        renderType = libassRenderType,
                        playerMediaSourceFactory = mediaSourceFactory,
                        dataSourceFactory = playerDataSourceFactory,
                        extractorsFactory = dolbyVisionExtractorsFactory,
                        renderersFactory = renderersFactory,
                        autoSyncSourceKey = url
                    )
            } else {
                buildDefaultPlayer()
            }
            } // end rebuild branch of the reuse fork
            lastExoConstructionFingerprint = constructionFingerprint
            activePlayerUsesLibass = useLibass
            libassPipelineSwitchInFlight = false

            _exoPlayer?.apply {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                // On Android TV, disable audio focus handling: focus-driven ducking applies
                // a software volume multiplier, which can't be applied to a compressed
                // bitstream, so ExoPlayer drops passthrough (Atmos/TrueHD/DTS-HD) to PCM.
                // Keep focus handling on phones/tablets, where ducking/pausing for calls
                // and other apps' audio matters.
                val handleAudioFocus = !context.packageManager
                    .hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
                setAudioAttributes(audioAttributes, handleAudioFocus)
                setPlaybackSpeed(_uiState.value.playbackSpeed)
                if (applyPcmFallbackOnStartup) {
                    pendingAudioPcmFallbackRebuild = false
                    hasTriedAudioPcmFallback = true
                }

                if (playerSettings.skipSilence) skipSilenceEnabled = true
                setHandleAudioBecomingNoisy(true)

                try {
                    currentMediaSession?.release()
                    if (canAdvertiseSession()) {
                        currentMediaSession = MediaSession.Builder(context, SafeMediaSessionPlayer(this, onSessionPlayPause)).build()
                    }
                    updateMediaSessionMetadata()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                applyAudioAmplification(_uiState.value.audioAmplificationDb)
                applyCenterMixLevel(_uiState.value.centerMixLevelDb)

                notifyAudioSessionUpdate(true)

                val preferred = playerSettings.subtitleStyle.preferredLanguage
                val secondary = playerSettings.subtitleStyle.secondaryPreferredLanguage
                val tertiary = playerSettings.subtitleStyle.tertiaryPreferredLanguage
                applySubtitlePreferences(preferred, secondary, tertiary)
                applyStartupSubtitlePreparation(startupSubtitlePreparation)
                val startupSubtitleConfigurations = buildStartupSubtitleConfigurations(startupSubtitlePreparation)
                // Join the saved-progress read before the
                // resume position is resolved. Runway to here is the whole
                // player build; SAVED_PROGRESS_AWAIT prices the residual.
                awaitSavedProgressLoad()
                val initialResumePosition = resolvePendingInitialResumePosition()
                playbackAnalyticsDiagnostics.setStartupStartPosition(initialResumePosition)
                val playerInitLine =
                    "PLAYER_INIT: engine=EXOPLAYER host=${url.safeHost()} " +
                        "playbackSpeed=${_uiState.value.playbackSpeed} " +
                        "resumePositionMs=$initialResumePosition mime=${currentStreamMimeType ?: "unknown"} " +
                        "bufferEngine=${playerSettings.bufferEngineEnabled} parallel=${mediaSourceFactory.useParallelConnections} " +
                        "vodCache=${mediaSourceFactory.vodCacheEnabled} tunneling=${playerSettings.effectiveTunnelingEnabled}"
                playbackAnalyticsDiagnostics.recordRawEventLine(playerInitLine)
                TtffTrace.mirror(playerInitLine)
                val initialMediaSource = mediaSourceFactory.createMediaSource(
                    context = context,
                    url = url,
                    headers = headers,
                    subtitleConfigurations = startupSubtitleConfigurations,
                    subtitleRoutes = subtitleRoutes(startupSubtitlePreparation.attachedSubtitles),
                    filename = currentFilename,
                    responseHeaders = currentStreamResponseHeaders,
                    mimeTypeOverride = currentStreamMimeType,
                    audioDelayUsProvider = audioDelayUs::get,
                    mediaMetadata = buildMediaSessionMetadata(),
                    cacheKey = currentStreamCacheKey
                )

                if (initialResumePosition > 0L) {
                    setMediaSource(initialMediaSource, initialResumePosition)
                    clearPendingInitialResumePosition()
                    updatePlaybackTimeline(currentPosition = initialResumePosition)
                } else {
                    setMediaSource(initialMediaSource)
                }

                setLoadingStatus(
                    phase = "starting_stream",
                    message = context.getString(R.string.player_loading_starting)
                )
                scheduleStartupWatchdog()
                val isTunneledPlayback = isTunnelingActiveForCurrentPlayback
                // Hold playWhenReady=false through prepare() so audio does not race ahead
                // while the video decoder is still opening. The first STATE_READY primes the
                // pipeline (ColdStartPrime); synchronized play() begins in onRenderedFirstFrame().
                //
                // Exception: tunneled playback bypasses the normal video rendering pipeline
                // so onRenderedFirstFrame() never fires — TunneledFirstReady starts on READY.
                // The fork's AFR settle hold and track-AFR start gate wrap the
                // policy's start actions (see startPlaybackThroughAfrGates).
                // The settle hold is owed by *this* player build only, and only
                // for an automatic start. Consumed here, so a start that happens later (a rebuffer, or a manual
                // play after a paused start) is never delayed by a mode transition that
                // finished long ago.
                var afrSettleHoldPending =
                    exoDelayStartAfterAfrSwitch && !startPaused && !userPausedManually
                exoDelayStartAfterAfrSwitch = false
                playWhenReady = false
                prepare()

                currentExoPlayerListener?.let { staleListener -> removeListener(staleListener) }
                currentExoPlayerListener = null
                val exoPlayerListenerForStream = object : Player.Listener {
                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        if (isReleasingPlayer || reason != Player.DISCONTINUITY_REASON_SEEK) return
                        // Holding the seek button emits a discontinuity per step, and reading the
                        // span set on each one is real work on a cache holding thousands of spans.
                        val seekedToMs = newPosition.positionMs
                        seekSourceLogJob?.cancel()
                        seekSourceLogJob = scope.launch {
                            delay(SEEK_SOURCE_SETTLE_MS)
                            val heldForTitle = mediaSourceFactory.vodCacheBytesForKey(
                                currentStreamCacheKey,
                                currentStreamUrl
                            )
                            Log.i(
                                PlayerRuntimeController.TAG,
                                "SEEK_SOURCE: toMs=$seekedToMs " +
                                    "durationMs=${_exoPlayer?.duration ?: -1L} " +
                                    "titleCachedMb=${if (heldForTitle < 0L) -1L else heldForTitle / (1024L * 1024L)}"
                            )
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (isReleasingPlayer) return
                        logScrobbleDiagnostic(
                            "exo_playback_state",
                            "playbackState=$playbackState playWhenReady=$playWhenReady isPlaying=$isPlaying " +
                                "userPaused=$userPausedManually"
                        )
                        if (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY) {
                            mediaSourceFactory.unlockStartupPrefetch()
                        }
                        val playerDuration = duration
                        if (playerDuration > lastKnownDuration) { lastKnownDuration = playerDuration }
                        val isBuffering = playbackState == Player.STATE_BUFFERING
                        updatePlaybackTimeline(duration = playerDuration.coerceAtLeast(0L))
                        // Only mark playbackEnded for real finishes so PlayerScreen does not
                        // dispatch next-episode navigation for short debrid/error placeholders.
                        val naturalEnded = playbackState == Player.STATE_ENDED &&
                            shouldTreatAsNaturalPlaybackCompletion(
                                hasRenderedFirstFrame = hasRenderedFirstFrame,
                                hasFatalError = !_uiState.value.error.isNullOrBlank(),
                                durationMs = playerDuration.coerceAtLeast(0L).let { d ->
                                    maxOf(d, lastKnownDuration)
                                }
                            )
                        _uiState.update {
                            it.copy(
                                isBuffering = if (NuvioExoPlayerPerformanceHelper.shouldSuppressBufferingUi(
                                    suppressBufferingUiForSeek, seekBufferingUiDeferred, isBuffering
                                )) false else isBuffering,
                                playbackEnded = naturalEnded
                            )
                        }
                        updateAudioControlAvailability()

                        // Rebuffer telemetry: a rebuffer is STATE_BUFFERING entered
                        // AFTER the first frame (initial startup buffering is excluded).
                        // Accumulate time spent rebuffering; closed out on any non-buffering state.
                        // Buffering within seekRebufferGraceMs of a user seek is the seek's
                        // own refill, not a starvation rebuffer. Excluded from count/totalMs/
                        // analytics; duration tracking and the passthrough-resync arm still run
                        // for every episode. Known limit: a seek INTO a starved region is
                        // excluded too (STALL_WATCHDOG / Rate-limit row still surface it).
                        if (playbackState == Player.STATE_BUFFERING) {
                            if (hasRenderedFirstFrame && rebufferStartedAtMs == 0L) {
                                rebufferStartedAtMs = SystemClock.elapsedRealtime()
                                currentRebufferSeekInduced =
                                    rebufferStartedAtMs - lastSeekWallMs <= seekRebufferGraceMs
                                if (!currentRebufferSeekInduced) {
                                    rebufferCount += 1
                                    playbackAnalyticsDiagnostics.onRebufferStarted(this@apply, rebufferCount)
                                    Log.i(
                                        PlayerRuntimeController.TAG,
                                        "REBUFFER: count=$rebufferCount totalRebufferMs=$rebufferTotalMs " +
                                            "bufferEngine=${currentDiagnostics.bufferEngineEnabled} " +
                                            "dv7dovi=${isExperimentalDv7ToDv81ActiveForCurrentPlayback} " +
                                            "host=${currentStreamUrl.safeHost()}"
                                    )
                                } else {
                                    Log.i(
                                        PlayerRuntimeController.TAG,
                                        "REBUFFER_SEEK_EXCLUDED: sinceSeekMs=${rebufferStartedAtMs - lastSeekWallMs}"
                                    )
                                }
                            }
                        } else if (rebufferStartedAtMs != 0L) {
                            val lastRebufferMs = (SystemClock.elapsedRealtime() - rebufferStartedAtMs).coerceAtLeast(0L)
                            if (!currentRebufferSeekInduced) {
                                rebufferTotalMs += lastRebufferMs
                                playbackAnalyticsDiagnostics.onRebufferEnded(this@apply, rebufferTotalMs, lastRebufferMs)
                            }
                            rebufferStartedAtMs = 0L
                            currentRebufferSeekInduced = false
                        }

                        if (playbackState == Player.STATE_BUFFERING && !hasRenderedFirstFrame) {
                            _uiState.update { state ->
                                if (state.loadingOverlayEnabled && !state.showLoadingOverlay) {
                                    recordLoadingDiagnosticEvent(
                                        phase = "buffering_before_first_frame",
                                        message = context.getString(R.string.player_loading_buffering),
                                        detail = "overlay_reopened"
                                    )
                                    state.copy(showLoadingOverlay = true, showControls = false, loadingMessage = context.getString(R.string.player_loading_buffering))
                                } else {
                                    recordLoadingDiagnosticEvent(
                                        phase = "buffering_before_first_frame",
                                        message = context.getString(R.string.player_loading_buffering)
                                    )
                                    state.copy(loadingMessage = context.getString(R.string.player_loading_buffering))
                                }
                            }
                        }

                        // Arm stall watchdog while buffering.
                        if (playbackState == Player.STATE_BUFFERING) {
                            maybeScheduleStallWatchdog()
                        } else {
                            cancelStallWatchdog()
                        }

                        if (playbackState == Player.STATE_BUFFERING && pendingSeekTelemetryAwaitingFirstFrame && pendingSeekTelemetryReadyAssumed) {
                            pendingSeekTelemetryReadyAtMs = 0L
                            pendingSeekTelemetryReadyLatencyMs = -1L
                            pendingSeekTelemetryReadyAssumed = false
                        }

                        if (playbackState == Player.STATE_READY) {
                            // The placeholder byte floor acts here -- content-length is stable at
                            // READY. The duration backstop is deferred to the progress tick
                            // (see rejectPlaceholderStream). Returning skips autoplay for a
                            // rejected placeholder; the policy's runtime guard is the fail-safe.
                            val placeholderVerdict = probePlaceholderStream(this@apply)
                            if (placeholderVerdict is PlaceholderStreamPolicy.Verdict.Reject &&
                                placeholderVerdict.reason == PlaceholderStreamPolicy.Reason.ImplausibleSize
                            ) {
                                rejectPlaceholderStream(placeholderVerdict)
                                return
                            }
                            if (pendingSeekTelemetryRequestedAtMs > 0L && pendingSeekTelemetryReadyAtMs <= 0L) {
                                val latencyMs = (System.currentTimeMillis() - pendingSeekTelemetryRequestedAtMs).coerceAtLeast(0L)
                                pendingSeekTelemetryReadyAtMs = System.currentTimeMillis()
                                pendingSeekTelemetryReadyLatencyMs = latencyMs
                            }
                            logSeekReady("ready")
                            // Don't auto-play on the initial STATE_READY — wait
                            // for onRenderedFirstFrame() to ensure A/V sync.
                            // Exception: tunneled playback never fires
                            // onRenderedFirstFrame(), so we must start here.
                            // The preference only requests tunneling: track selection enables it only when the
                            // selected renderers support it (not the AM9 native FEL renderer, safe-audio mode
                            // turns it off). The synthetic-first-frame start below is only for real tunneling.
                            val tunneledNow = isTunneledPlayback && this@apply.isTunnelingEnabled
                            val readyTransition = PlayerStartupPlaybackPolicy.onStateReady(
                                PlayerStartupPlaybackPolicy.ReadyState(
                                    shouldEnforceAutoplayOnFirstReady = shouldEnforceAutoplayOnFirstReady,
                                    hasRenderedFirstFrame = hasRenderedFirstFrame,
                                    userPausedManually = userPausedManually,
                                    startPaused = startPaused,
                                    isTunneledPlayback = tunneledNow,
                                )
                            )
                            shouldEnforceAutoplayOnFirstReady =
                                readyTransition.nextState.shouldEnforceAutoplayOnFirstReady
                            if (readyTransition.nextState.hasRenderedFirstFrame && tunneledNow) {
                                hasRenderedFirstFrame = true
                                cancelStartupWatchdog()
                                retractStartupTimeoutErrorAfterFirstFrame()
                            }
                            when (val action = readyTransition.action) {
                                is PlayerStartupPlaybackPolicy.ReadyAction.TunneledFirstReady -> {
                                    mediaSourceFactory.unlockStartupPrefetch()
                                    playbackAnalyticsDiagnostics.onSyntheticFirstFrame(this@apply)
                                    if (_uiState.value.postPlayDismissedForCurrentEpisode) {
                                        _uiState.update { it.copy(postPlayDismissedForCurrentEpisode = false) }
                                    }
                                    // The policy already folds startPaused and
                                    // userPausedManually into the action; the fork's
                                    // track-AFR gate and display-AFR settle hold wrap it.
                                    startPlaybackThroughAfrGates(
                                        player = this@apply,
                                        setPlayWhenReady = action.setPlayWhenReady,
                                        callPlay = action.callPlay,
                                        holdForAfrSettle = afrSettleHoldPending
                                    )
                                    afrSettleHoldPending = false
                                    // Force MediaCodec video decoder & AudioSink flush/re-alignment on initial
                                    // tunneled startup behind the loading overlay so playback starts immediately.
                                    // Note: ExoPlayer ignores seeks if target position == current position,
                                    // so we add a 100ms delta to guarantee an actual MediaCodec flush.
                                    if (_uiState.value.pendingSeekPosition == null) {
                                        val initialPos = currentPosition
                                        seekTo((initialPos + 100L).coerceAtLeast(100L))
                                    }
                                    finishLoadingDiagnostics("first_frame_ready")
                                    currentDiagnostics = recordFirstFrameDiagnostics(this@apply, currentDiagnostics, playerSettings)
                                    _uiState.update {
                                        it.copy(
                                            showLoadingOverlay = false,
                                            loadingMessage = null,
                                            loadingProgress = if (it.loadingProgress != null) 1f else null,
                                            showPlayerEngineSwitchInfo = false
                                        )
                                    }
                                }
                                is PlayerStartupPlaybackPolicy.ReadyAction.ColdStartPrime -> {
                                    startPlaybackThroughAfrGates(
                                        player = this@apply,
                                        setPlayWhenReady = action.setPlayWhenReady,
                                        callPlay = action.callPlay,
                                        holdForAfrSettle = afrSettleHoldPending
                                    )
                                    afrSettleHoldPending = false
                                }
                                is PlayerStartupPlaybackPolicy.ReadyAction.PreFirstFrameResume -> {
                                    startPlaybackThroughAfrGates(
                                        player = this@apply,
                                        setPlayWhenReady = action.setPlayWhenReady,
                                        callPlay = action.callPlay,
                                        holdForAfrSettle = false
                                    )
                                }
                                is PlayerStartupPlaybackPolicy.ReadyAction.PostFirstFrameResume -> {
                                    // A rebuffer inside the track-AFR settle window
                                    // must not restart playback mid-switch; the gate
                                    // release resumes it (hasRenderedFirstFrame is
                                    // already true on this branch).
                                    startPlaybackThroughAfrGates(
                                        player = this@apply,
                                        setPlayWhenReady = false,
                                        callPlay = action.callPlay,
                                        holdForAfrSettle = false
                                    )
                                }
                                PlayerStartupPlaybackPolicy.ReadyAction.None -> Unit
                            }
                            tryApplyPendingResumeProgress(this@apply)
                            _uiState.value.pendingSeekPosition?.let { position ->
                                seekTo(position)
                                if (NuvioExoPlayerPerformanceHelper.enabled) {
                                    seekBufferingUiDeferred = true
                                    seekBufferingUiJob?.cancel()
                                    seekBufferingUiJob = scope.launch {
                                        delay(seekBufferingUiDelayMs)
                                        seekBufferingUiDeferred = false
                                        if (pendingSeekFlush) {
                                            _uiState.update { it.copy(isBuffering = true) }
                                        }
                                    }
                                }
                                _uiState.update { it.copy(pendingSeekPosition = null) }
                            }
                            tryAutoSelectPreferredSubtitleFromAvailableTracks()
                            if (!NuvioExoPlayerPerformanceHelper.shouldGuardTrackRebuild() || !hasRenderedFirstFrame) {
                                trackSelectionParameters = trackSelectionParameters.buildUpon().build()
                            }
                            maybeScheduleFirstFrameWatchdog()
                            maybeScheduleTunnelAvSyncWatchdog()
                        } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                            cancelFirstFrameWatchdog()
                            cancelTunnelAvSyncWatchdog()
                        }

                        if (playbackState == Player.STATE_ENDED) {
                            // Re-persist diagnostics with the final rebuffer totals (the
                            // first-frame snapshot captured 0, since rebuffers accrue after).
                            Log.i(
                                PlayerRuntimeController.TAG,
                                "BUFFER_SUMMARY: rebuffers=$rebufferCount rebufferTotalMs=$rebufferTotalMs " +
                                    "bufferEngine=${currentDiagnostics.bufferEngineEnabled} host=${currentStreamUrl.safeHost()}"
                            )
                            if (currentDiagnostics.result == "Played") {
                                currentDiagnostics = currentDiagnostics.copy(
                                    rebufferCount = rebufferCount,
                                    rebufferTotalMs = rebufferTotalMs,
                                    vodCacheStats = mediaSourceFactory.vodCacheStatsLabel(context)
                                )
                                val endDiagnostics = currentDiagnostics
                                lastPlaybackDiagnosticsForReport = endDiagnostics
                                scope.launch {
                                    runCatching { playerSettingsDataStore.setLastPlaybackDiagnostics(endDiagnostics) }
                                }
                            }
                            // Marks watched + auto-play next only for real episode finishes;
                            // short debrid/error placeholders are ignored (see #2819).
                            handleNaturalPlaybackEnded()
                        }

                        refreshStableProgressResetGate()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // Gate the audio-clock jitter sensor on actual playback -
                        // paused/rebuffering time must not count as clock jitter.
                        playbackSpeedAwareAudioSink?.setPlaybackActive(isPlaying)
                        logScrobbleDiagnostic(
                            "exo_is_playing_changed",
                            "isPlaying=$isPlaying playbackState=$playbackState playWhenReady=$playWhenReady " +
                                "userPaused=$userPausedManually"
                        )
                        _uiState.update { it.copy(isPlaying = isPlaying) }
                        if (isPlaying) {
                            userPausedManually = false
                            cancelPauseOverlay()
                            startProgressUpdates()
                            startWatchProgressSaving()
                            scheduleHideControls()
                            tryShowParentalGuide()
                            emitScrobbleStart()
                        } else {
                            if (userPausedManually) schedulePauseOverlay() else cancelPauseOverlay()
                            // A held scrub is not a user pause: no scrobble, no progress save.
                            if (scrubHoldPaused && playbackState == Player.STATE_READY) {
                                refreshStableProgressResetGate()
                                return
                            }
                            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                                stopProgressUpdates()
                            }
                            stopWatchProgressSaving()
                            if (playbackState == Player.STATE_BUFFERING) {
                                saveWatchProgressIfNeeded()
                            } else {
                                when (trackingActionForNonPlayingState(playbackState)) {
                                    TrackingScrobbleAction.PAUSE -> emitPauseScrobbleForCurrentProgress()
                                    TrackingScrobbleAction.STOP -> emitStopScrobbleForCurrentProgress()
                                    TrackingScrobbleAction.START, null -> Unit
                                }
                                saveWatchProgress()
                            }
                        }
                        refreshStableProgressResetGate()
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        updateAvailableTracks(tracks)
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            currentVideoWidth = videoSize.width
                            currentVideoHeight = videoSize.height
                            Log.d(PlayerRuntimeController.TAG, "onVideoSizeChanged: updated resolution to ${videoSize.width}x${videoSize.height}")
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        val isFirstFrame = !hasRenderedFirstFrame  // capture BEFORE flipping
                        hasRenderedFirstFrame = true
                        logSeekReady("first_frame")
                        cancelStartupWatchdog()
                        retractStartupTimeoutErrorAfterFirstFrame()
                        mediaSourceFactory.unlockStartupPrefetch()
                        // NuvioTV fork: pre-populate the source list in the
                        // background once playback is healthy, so a mid-play failover
                        // (an unrecoverable malformed hole, or an HTTP dead source) has
                        // alternatives to switch to without the user having opened the
                        // sources panel first. Cheap - the details screen already
                        // fetched these, so it is a cache hit. Guarded on empty so it
                        // runs once and never re-fires on a failover re-prepare.
                        if (_uiState.value.sourceAllStreams.isEmpty() ||
                            sourceStreamsCacheRequestKey != currentSourceRequestKey()
                        ) {
                            loadSourceStreams(forceRefresh = false)
                        }
                        if (isFirstFrame && _uiState.value.postPlayDismissedForCurrentEpisode) {
                            _uiState.update { it.copy(postPlayDismissedForCurrentEpisode = false) }
                        }
                        updateAudioControlAvailability()
                        // Start playback now that the first video frame is
                        // visible: audio and video begin in sync.
                        // While a track-format AFR switch is settling, hold
                        // the start; resumePlaybackAfterTrackAfrIfHeld() starts
                        // playback when the gate releases (deadline-bounded).
                        if (!startPaused && !userPausedManually && !afrTrackSwitchInFlight) {
                            playWhenReady = true
                            play()
                        }
                        refreshStableProgressResetGate()
                        cancelFirstFrameWatchdog()
                        _uiState.update {
                            it.copy(
                                showLoadingOverlay = false,
                                loadingMessage = null,
                                loadingProgress = if (it.loadingProgress != null) 1f else null,
                                loadingIssueReportVisible = false,
                                loadingIssueElapsedMs = 0L,
                                showPlayerEngineSwitchInfo = false
                            )
                        }
                        finishLoadingDiagnostics("first_frame_rendered")

                        if (isFirstFrame) {
                            currentDiagnostics = recordFirstFrameDiagnostics(this@apply, currentDiagnostics, playerSettings)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (isReleasingPlayer && error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) return
                        cancelFirstFrameWatchdog()
                        cancelTunnelAvSyncWatchdog()
                        val detailedError = error.toDisplayMessage(context)
                        cancelStableProgressReset()

                        if (Vc1VideoFormatHeuristics.isVc1PlaybackFailure(
                                error = error,
                                currentVideoTrackIsLikelyVc1 = currentVideoTrackIsLikelyVc1,
                                currentStreamName = _uiState.value.currentStreamName ?: streamName ?: currentFilename
                            )
                        ) {
                            // In the background nothing is restarted, as for every other codec.
                            if (!isInBackground) {
                                if (tryVc1DecoderStartRetry(error)) return
                                if (isExoPlayerActiveForFormatFallback &&
                                    switchToMpvForUnsupportedFormat("vc1-decoder-failure")
                                ) {
                                    return
                                }
                            }
                            handleVc1PlaybackFailure(errorMessage = detailedError)
                            return
                        }

                        // If the codec crashed while the app is in the background (e.g. another
                        // app reclaimed the hardware decoder), don't run the retry chain. Each
                        // retry just re-acquires a decoder the foreground app immediately reclaims
                        // again, burning the retry budget and landing on an unrecoverable
                        // ERROR_CODE_DECODING_FAILED by the time the user returns. Save the
                        // position, free the decoder, and rebuild paused on resume instead.
                        if (isInBackground && isRetryablePlaybackError(error)) {
                            backgroundCrashSavedPositionMs = currentPosition.takeIf { it > 0L } ?: 0L
                            pendingBackgroundCrashRecovery = true
                            errorRetryJob?.cancel()
                            errorRetryJob = scope.launch {
                                releasePlayer(flushPlaybackState = false)
                            }
                            return
                        }

                        fun switchedToMpvForUnsupportedFormat(): Boolean =
                            !isInBackground &&
                                UnsupportedFormatMpvFallbackPolicy.shouldSwitchForFatalError(
                                    exoPlayerActive = isExoPlayerActiveForFormatFallback,
                                    alreadySwitched = currentStreamUrl in mpvFormatFallbackStreamUrls,
                                    hasRenderedFirstFrame = hasRenderedFirstFrame,
                                    errorCode = error.errorCode,
                                    videoMimeType = currentVideoTrackMimeType,
                                    fileName = currentFilename ?: currentStreamUrl,
                                    streamMimeType = currentStreamMimeType
                                ) && switchToMpvForUnsupportedFormat(error.errorCodeName)

                        // Every branch below re-initialises playback and
                        // each is individually self-gated, but chained they can loop
                        // a persistently failing pipeline for minutes (for example
                        // decoder-start timeouts re-preparing a large moov-at-tail
                        // MP4 on every cycle). One shared budget bounds the total;
                        // when it's spent, fall through to the terminal error surface.
                        // The same-configuration retry runs first: a refusal it cures is never
                        // learned as a denial.
                        val audioRetryScheduled = tryBitstreamAudioTrackSameConfigRetry(error)
                        noteAudioTrackRejection(error, audioRetryScheduled)
                        if (audioRetryScheduled) return
                        // AM9 native FEL: MEL / non-P7 RPU / device error / stalled decoder -> re-prepare
                        // this stream on the AUTO path at the current position (one-shot per stream).
                        if (isNativeFelActiveForCurrentPlayback &&
                            com.nuvio.tv.core.player.amlfel.AmlDvFelVideoRenderer.isFallbackError(error) &&
                            nativeFelDisabledStreamUrls.add(currentStreamUrl)
                        ) {
                            Log.i(
                                PlayerRuntimeController.TAG,
                                "DV7_NATIVE_FEL_FALLBACK: ${error.cause?.message} -> AUTO path " +
                                    "at ${currentPosition} ms host=${currentStreamUrl.safeHost()}"
                            )
                            retryCurrentStreamWithoutNativeFel(currentPosition)
                            return
                        }
                        val autoRecoveryBudgetAvailable = consumeAutoRecoveryBudget(detailedError)
                        if (autoRecoveryBudgetAvailable) {

                        // Error handlers: DV codec failures, audio decoder issues, codec state errors.
                        if (error.isDolbyVisionDecoderFailure() && !isMapDv7ToHevcActiveForCurrentPlayback) {
                            // Manual Convert-to-DV8.1 mode 2 failed to decode: try
                            // libdovi mode 1 once before falling back to HDR10.
                            if (isManualDv81Mode2ActiveForCurrentPlayback &&
                                !dv7Mode1ForcedStreamUrls.contains(currentStreamUrl)
                            ) {
                                dv7Mode1ForcedStreamUrls.add(currentStreamUrl)
                                Log.i(
                                    PlayerRuntimeController.TAG,
                                    "DV7_MODE2_PLAYBACK_FALLBACK: mode 2 decode failed; " +
                                            "retrying stream at mode 1 host=${currentStreamUrl.safeHost()}"
                                )
                                retryCurrentStreamWithDv7Mode1Fallback(currentPosition)
                                return
                            }
                            if (isExperimentalDv7ToDv81ActiveForCurrentPlayback && !hasAttemptedDv7ToDv81ForCurrentPlayback) {
                                hasAttemptedDv7ToDv81ForCurrentPlayback = true
                                val probe = DoviBridge.probeRealtimeConversionSupport(currentStreamUrl)
                                dv7ToDv81LastProbeReasonForCurrentPlayback = probe.reason
                                dv7ToDv81BridgeVersionForCurrentPlayback = probe.bridgeVersion
                            }
                            dv7ToHevcForcedStreamUrls.add(currentStreamUrl)
                            retryCurrentStreamWithDolbyVisionFallback(currentPosition)
                            return
                        }

                        // DV conversion armed for this stream but the player hit a
                        // FAILED_RUNTIME_CHECK (8000): the converted bitstream trips a
                        // renderer/extractor assertion before the codec ever reports a
                        // decoding failure. That is a video-path failure, so take the same
                        // fallback ladder as a DV decoder failure instead of burning the
                        // audio fallbacks (safe-audio/audio-disabled) on it — they rebuild
                        // the player with the same broken conversion and fail identically.
                        if (error.errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK &&
                            !error.isStuckBufferingWatchdog() &&
                            (isExperimentalDv7ToDv81ActiveForCurrentPlayback ||
                                isManualDv81Mode2ActiveForCurrentPlayback) &&
                            !isMapDv7ToHevcActiveForCurrentPlayback
                        ) {
                            if (isManualDv81Mode2ActiveForCurrentPlayback &&
                                !dv7Mode1ForcedStreamUrls.contains(currentStreamUrl)
                            ) {
                                dv7Mode1ForcedStreamUrls.add(currentStreamUrl)
                                Log.i(
                                    PlayerRuntimeController.TAG,
                                    "DV7_MODE2_RUNTIME_CHECK_FALLBACK: mode 2 hit FAILED_RUNTIME_CHECK; " +
                                            "retrying stream at mode 1 host=${currentStreamUrl.safeHost()}"
                                )
                                retryCurrentStreamWithDv7Mode1Fallback(currentPosition)
                                return
                            }
                            if (isExperimentalDv7ToDv81ActiveForCurrentPlayback &&
                                !hasAttemptedDv7ToDv81ForCurrentPlayback
                            ) {
                                hasAttemptedDv7ToDv81ForCurrentPlayback = true
                                val probe = DoviBridge.probeRealtimeConversionSupport(currentStreamUrl)
                                dv7ToDv81LastProbeReasonForCurrentPlayback = probe.reason
                                dv7ToDv81BridgeVersionForCurrentPlayback = probe.bridgeVersion
                            }
                            Log.i(
                                PlayerRuntimeController.TAG,
                                "DV_RUNTIME_CHECK_FALLBACK: FAILED_RUNTIME_CHECK with DV conversion active; " +
                                        "forcing HDR10 base layer for host=${currentStreamUrl.safeHost()}"
                            )
                            dv7ToHevcForcedStreamUrls.add(currentStreamUrl)
                            retryCurrentStreamWithDolbyVisionFallback(currentPosition)
                            return
                        }

                        if ((error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                             error.errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK) &&
                            !autoSwitchInternalPlayerOnErrorEnabled) {
                            // Only take the safe-audio ->
                            // audio-disabled ladder when the *audio* renderer
                            // failed. Otherwise a video decoder crash would burn the
                            // audio recovery budget (tunneling off, channels
                            // constrained, then audio disabled entirely) for a
                            // problem audio had nothing to do with, ending in
                            // silent video. ExoPlaybackException carries
                            // the failing renderer's format; when it isn't audio,
                            // fall through to the video fallbacks / error surface.
                            val failingMime = (error as? androidx.media3.exoplayer.ExoPlaybackException)
                                ?.rendererFormat?.sampleMimeType
                            val audioRendererFailed = failingMime == null ||
                                androidx.media3.common.MimeTypes.isAudio(failingMime)
                            if (audioRendererFailed) {
                                if (!isSafeAudioModeActiveForCurrentPlayback) {
                                    safeAudioForcedStreamUrls.add(currentStreamUrl)
                                    retryCurrentStreamWithSafeAudioFallback(currentPosition)
                                    return
                                }
                                // Second rung: one more
                                // safe-audio rebuild with the FFmpeg software (PCM)
                                // decoder before giving up on audio entirely.
                                if (!hasTriedAudioPcmFallback) {
                                    markAudioPcmFallbackTried()
                                    retryCurrentStreamWithSafeAudioFallback(currentPosition)
                                    return
                                }
                                if (!isAudioDisabledForCurrentPlayback) {
                                    audioDisabledForcedStreamUrls.add(currentStreamUrl)
                                    retryCurrentStreamWithAudioDisabled(currentPosition)
                                    return
                                }
                            }
                        }

                        // AudioTrack init (5001) or write (5002, e.g. ERROR_DEAD_OBJECT on an
                        // E-AC-3/AC-3 passthrough or offload track) failure: re-select audio with
                        // passthrough/tunneling off and the channel count constrained to the
                        // device's capabilities, then fall back to disabling audio so video keeps
                        // playing, instead of surfacing the fatal error screen.
                        // AudioTrack init failures (5001) try the gentler recovery
                        // first: rebuild with PCM forcing, preserving tunneling and
                        // channel layout, before the heavier safe-audio ladder.
                        // Self-gating: no-op unless code=5001, first attempt,
                        // extension mode ON, tunneling off.
                        // 4001 on a policy-denied audio format
                        // (hybrid MIME upgraded mid-stream past the selection-time
                        // abdication). Rebuild with FFmpeg audio preferred so the
                        // whole family maps to the renderer that can decode it.
                        if (tryDeniedAudioFfmpegFallback(error)) {
                            return
                        }

                        if (tryAudioTrackPcmFallback(error)) {
                            return
                        }

                        if (error.isAudioTrackFailure()) {
                            if (!isSafeAudioModeActiveForCurrentPlayback) {
                                safeAudioForcedStreamUrls.add(currentStreamUrl)
                                retryCurrentStreamWithSafeAudioFallback(currentPosition)
                                return
                            }
                            if (!hasTriedAudioPcmFallback) {
                                markAudioPcmFallbackTried()
                                retryCurrentStreamWithSafeAudioFallback(currentPosition)
                                return
                            }
                            if (!isAudioDisabledForCurrentPlayback) {
                                audioDisabledForcedStreamUrls.add(currentStreamUrl)
                                retryCurrentStreamWithAudioDisabled(currentPosition)
                                return
                            }
                        }

                        if (error.isStuckPlayingNoProgress()) {
                            if (isTunnelingActiveForCurrentPlayback &&
                                !tunnelingDisabledStreamUrls.contains(currentStreamUrl)
                            ) {
                                tunnelingDisabledStreamUrls.add(currentStreamUrl)
                                retryCurrentStreamWithoutTunneling(currentPosition)
                                return
                            }
                            if (!isSafeAudioModeActiveForCurrentPlayback) {
                                safeAudioForcedStreamUrls.add(currentStreamUrl)
                                retryCurrentStreamWithSafeAudioFallback(currentPosition)
                                return
                            }
                            if (!hasTriedAudioPcmFallback) {
                                markAudioPcmFallbackTried()
                                retryCurrentStreamWithSafeAudioFallback(currentPosition)
                                return
                            }
                            if (!isAudioDisabledForCurrentPlayback) {
                                audioDisabledForcedStreamUrls.add(currentStreamUrl)
                                retryCurrentStreamWithAudioDisabled(currentPosition)
                                return
                            }
                        }

                        val timeoutError = error.findCause<SocketTimeoutException>()
                        if (timeoutError != null && timeoutRecoveryAttempts < PlayerRuntimeController.MAX_TIMEOUT_RECOVERY_ATTEMPTS) {
                            retryCurrentStreamAfterTimeout(currentPosition)
                            return
                        }

                        if (error.isUnexpectedLoaderNullPointer() && !hasRetriedCurrentStreamAfterUnexpectedNpe) {
                            hasRetriedCurrentStreamAfterUnexpectedNpe = true
                            retryCurrentStreamAfterUnexpectedNpe(currentPosition)
                            return
                        }

                        if (error.isMediaPeriodHolderStateCrash() && !hasRetriedCurrentStreamAfterMediaPeriodHolderCrash) {
                            hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = true
                            retryCurrentStreamAfterMediaPeriodHolderCrash(currentPosition)
                            return
                        }

                        val responseCode = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                        if (responseCode == 416 && !hasRetriedCurrentStreamAfter416) {
                            retryCurrentStreamFromStartAfter416()
                            return
                        }

                        // A format only MPV can read is not a dead link: same source, other engine first.
                        if (switchedToMpvForUnsupportedFormat()) return

                        // Dead-source failover, HTTP arm:
                        // 404/410 is permanent for this URL with no probe value, so
                        // advance to the next source immediately instead of burning
                        // both same-URL retries on a doomed link. Checked before the
                        // engine failover: a dead URL is dead on either engine.
                        if (isDeadSourceHttpError(error) && attemptDeadSourceFailover(error, detailedError)) {
                            return
                        }

                        // Parsing-error probe: on a sniff/parsing failure,
                        // actively probe the stream's real mime type and retry with
                        // it (a mislabeled container is rescuable). The fork's
                        // dead-source sniff arm (non-media body behind HTTP 200,
                        // .rar/.zip payload) runs inside its probe-null branch, so
                        // genuinely dead bodies still advance to the next source.
                        if (tryParsingErrorProbeFallback(
                            error = error,
                            detailedError = detailedError,
                            allowEngineFailover = allowEngineFailover,
                            savedPosition = currentPosition,
                            paused = userPausedManually
                        )) {
                            return
                        }

                        // ── Main Engine Failover ──
                        if (maybeAutoSwitchInternalPlayerOnStartupError(detailedError = detailedError, allowEngineFailover = allowEngineFailover)) {
                            return
                        }

                        // A mid-play malformed-container error
                        // (3001 / 2000) that the extractor resync could not clear
                        // will not recover on a same-URL re-prepare either - auto-retry
                        // cue-seeks to the same position and re-hits the same corruption.
                        // Fail over to the next source immediately, exactly like the HTTP
                        // dead-source arm above (media3's Loader has already retried
                        // before this point). Bounded by MAX_DEAD_SOURCE_FAILOVERS;
                        // returns false (=> auto-retry, then the error screen) only when
                        // no live source remains.
                        // A 2000 caused by the network is a lost connection, not a broken file: it
                        // takes the same-link retry below instead of moving to another source.
                        val midPlayCorruption = isMidPlayCorruption(error.errorCode, hasRenderedFirstFrame) &&
                            !(error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED &&
                                PlaybackNetworkFailure.hasNetworkCause(error))
                        val serverDirectPlay = isServerDirectPlay
                        if (midPlayCorruption && !serverDirectPlay && advanceToNextLiveSource(detailedError)) {
                            return
                        }

                        if (attemptAutoRetry(error, detailedError)) {
                            return
                        }
                        if (tryServerFallback(error)) {
                            return
                        }
                        if (midPlayCorruption && serverDirectPlay && advanceToNextLiveSource(detailedError)) {
                            return
                        }

                        // A startup failure that exhausted the
                        // ladders and both auto-retries tries the next source
                        // before surfacing the error screen.
                        if (attemptStartupExhaustedSourceFailover(detailedError)) {
                            return
                        }

                        } // end auto-recovery budget gate

                        if (rebufferStartedAtMs != 0L) {
                            val lastRebufferMs = (SystemClock.elapsedRealtime() - rebufferStartedAtMs).coerceAtLeast(0L)
                            rebufferTotalMs += lastRebufferMs
                            rebufferStartedAtMs = 0L
                            playbackAnalyticsDiagnostics.onRebufferEnded(this@apply, rebufferTotalMs, lastRebufferMs)
                        }

                        val errorDiagnostics = currentDiagnostics.copy(
                            rebufferCount = rebufferCount,
                            rebufferTotalMs = rebufferTotalMs,
                            result = "Error: $detailedError"
                        )
                        lastPlaybackDiagnosticsForReport = errorDiagnostics
                        lastPlaybackIssueError = PlaybackIssueErrorInput(
                            displayMessage = detailedError,
                            errorCode = error.errorCode,
                            errorCodeName = error.errorCodeName,
                            exceptionClass = error.javaClass.name,
                            causeClass = error.cause?.javaClass?.name,
                            causeMessage = error.cause?.message,
                            httpStatus = error.findInvalidResponseCodeException()?.responseCode
                        )
                        scope.launch {
                            runCatching {
                                playerSettingsDataStore.setLastPlaybackDiagnostics(errorDiagnostics)
                            }
                        }

                        if (switchedToMpvForUnsupportedFormat()) return

                        // Fatal error: stop any next-episode auto-play that may have been
                        // armed by a short placeholder ENDED or residual post-play state.
                        cancelNextEpisodeAutoPlayOnFatalError()
                        val canSwitchToMpvOnFatal = currentInternalPlayerEngine != InternalPlayerEngine.MVP_PLAYER &&
                            (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                             error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                             error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                             error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                             error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                             error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)
                        _uiState.update {
                            it.copy(
                                // Wiring-gap fix: surface the mapped, human-readable
                                // message (toDisplayMessage) instead of the raw
                                // extractor dump; detailedError still flows to
                                // diagnostics and the issue report above.
                                error = error.toDisplayMessage(context),
                                showSwitchToMpvErrorAction = canSwitchToMpvOnFatal,
                                showLoadingOverlay = false,
                                showPauseOverlay = false,
                                loadingIssueReportVisible = false,
                                loadingIssueElapsedMs = 0L,
                                playbackEnded = false,
                                postPlayMode = null
                            )
                        }
                    }
                }
                currentExoPlayerListener = exoPlayerListenerForStream
                addListener(exoPlayerListenerForStream)

                currentExoAnalyticsListener?.let { staleListener -> removeAnalyticsListener(staleListener) }
                currentExoAnalyticsListener = null
                val exoAnalyticsListenerForStream = object : AnalyticsListener {
                    override fun onAudioTrackInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
                    ) {
                        recordStashedAudioRejection(audioTrackConfig.encoding)
                        // Records whether audio is passed through, decoded to PCM,
                        // or transcoded, for the Audio Path row.
                        val sourceMime = this@apply.audioFormat?.sampleMimeType
                        val sourceCodec = describeAudioMime(sourceMime)
                        // The only transcode this app performs is
                        // x -> AC-3 (denied-codec handling / Force AC-3). When the
                        // sink opened AC-3 for a non-AC-3 source, "Passthrough (AC-3)"
                        // is true at the sink layer but hides the conversion - name it.
                        val sinkPath = if (
                            audioTrackConfig.encoding == C.ENCODING_AC3 &&
                            sourceMime != null &&
                            sourceMime != MimeTypes.AUDIO_AC3
                        ) {
                            "AC-3 transcode"
                        } else {
                            describeAudioEncoding(audioTrackConfig.encoding)
                        }
                        val detail = buildString {
                            append(sinkPath)
                            append(" (")
                            append(audioTrackConfig.sampleRate / 1000)
                            append(" kHz")
                            val ch = Integer.bitCount(audioTrackConfig.channelConfig)
                            if (ch > 0) append(", ${ch}ch")
                            if (audioTrackConfig.tunneling) append(", tunneled")
                            if (audioTrackConfig.offload) append(", offload")
                            append(')')
                        }
                        currentAudioPathDescription = "$sourceCodec \u2192 $detail"
                        if (_exoPlayer === this@apply) {
                            val openedGroup = passthroughGroupOfEncoding(audioTrackConfig.encoding)
                            playbackRecoveryGate.noteAudioOutputOpened(openedGroup)
                            if (openedGroup != null) {
                                currentAudioOutputRoute?.key?.let {
                                    if (AudioRejectionReverifier.ledger.noteBitstreamOpened(it)) saveAudioRefusalEvidence()
                                }
                            }
                        }
                        // Gate gain/skip-silence on the actual
                        // negotiated output mode rather than "a player exists".
                        val bypassing = when (audioTrackConfig.encoding) {
                            C.ENCODING_PCM_16BIT,
                            C.ENCODING_PCM_16BIT_BIG_ENDIAN,
                            C.ENCODING_PCM_24BIT,
                            C.ENCODING_PCM_24BIT_BIG_ENDIAN,
                            C.ENCODING_PCM_32BIT,
                            C.ENCODING_PCM_32BIT_BIG_ENDIAN,
                            C.ENCODING_PCM_8BIT,
                            C.ENCODING_PCM_FLOAT -> false
                            else -> true
                        }
                        if (bypassing != isAudioOutputBypassing) {
                            isAudioOutputBypassing = bypassing
                            updateAudioControlAvailability()
                        }
                    }

                    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                        playbackAnalyticsDiagnostics.onPlaybackStateChanged(eventTime, state)
                    }

                    override fun onPlayWhenReadyChanged(
                        eventTime: AnalyticsListener.EventTime,
                        playWhenReady: Boolean,
                        reason: Int
                    ) {
                        playbackAnalyticsDiagnostics.onPlayWhenReadyChanged(eventTime, playWhenReady, reason)
                    }

                    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
                        playbackAnalyticsDiagnostics.onIsPlayingChanged(eventTime, isPlaying)
                        if (isPlaying && _exoPlayer === this@apply && playbackSpeedAwareAudioSink?.isIecHbrActive() == true) {
                            playbackRecoveryGate.noteAudioOutputOpened(
                                com.nuvio.tv.core.player.AudioPassthroughPolicy.groupOf(
                                    playbackSpeedAwareAudioSink?.activeInputFormat?.sampleMimeType
                                )
                            )
                            currentAudioOutputRoute?.key?.let {
                                if (AudioRejectionReverifier.ledger.noteBitstreamOpened(it)) saveAudioRefusalEvidence()
                            }
                        }
                        // Fallback: the extension-renderer (FFmpeg) path never
                        // delivers onAudioTrackInitialized, so once audio is actually playing and
                        // the row is still empty, derive it from what the app owns:
                        // renderer input format (source) vs the format the sink was
                        // configured with. PCM at the sink = decode; same compressed
                        // MIME = passthrough; different compressed MIME = transcode.
                        // The null guard keeps the richer track-init event authoritative
                        // whenever it does fire, and the per-stream reset keeps this
                        // one-shot per stream.
                        if (isPlaying && currentAudioPathDescription == null) {
                            runCatching {
                                val source = this@apply.audioFormat
                                val sinkFormat = playbackSpeedAwareAudioSink?.activeInputFormat
                                if (source?.sampleMimeType != null && sinkFormat?.sampleMimeType != null) {
                                    val src = describeAudioMime(source.sampleMimeType)
                                    val detail = buildString {
                                        when {
                                            sinkFormat.sampleMimeType == MimeTypes.AUDIO_RAW -> append("PCM decode")
                                            playbackSpeedAwareAudioSink?.isIecHbrActive() == true ->
                                                append("Passthrough, IEC (").append(src).append(')')
                                            sinkFormat.sampleMimeType == source.sampleMimeType ->
                                                append("Passthrough (").append(src).append(')')
                                            else -> append(describeAudioMime(sinkFormat.sampleMimeType)).append(" transcode")
                                        }
                                        append(" (")
                                        if (sinkFormat.sampleRate > 0) append(sinkFormat.sampleRate / 1000).append(" kHz")
                                        if (sinkFormat.channelCount > 0) append(", ").append(sinkFormat.channelCount).append("ch")
                                        append(')')
                                    }
                                    currentAudioPathDescription = "$src \u2192 $detail"
                                }
                            }
                        }
                    }

                    override fun onIsLoadingChanged(eventTime: AnalyticsListener.EventTime, isLoading: Boolean) {
                        playbackAnalyticsDiagnostics.onIsLoadingChanged(eventTime, isLoading)
                    }

                    override fun onPlaybackParametersChanged(
                        eventTime: AnalyticsListener.EventTime,
                        playbackParameters: PlaybackParameters
                    ) {
                        playbackAnalyticsDiagnostics.onPlaybackParametersChanged(eventTime, playbackParameters)
                    }

                    override fun onRenderedFirstFrame(
                        eventTime: AnalyticsListener.EventTime,
                        output: Any,
                        renderTimeMs: Long
                    ) {
                        playbackAnalyticsDiagnostics.onRenderedFirstFrame(eventTime)
                    }

                    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                        playbackAnalyticsDiagnostics.onPlayerError(eventTime, error)
                    }

                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        if (_exoPlayer !== this@apply) return
                        currentDiagnostics = currentDiagnostics.copy(dv81DecoderName = decoderName)
                        playbackAnalyticsDiagnostics.onVideoDecoderInitialized(
                            eventTime = eventTime,
                            decoderName = decoderName,
                            initializationDurationMs = initializationDurationMs
                        )
                        Log.i(
                            PlayerRuntimeController.TAG,
                            "VIDEO_DECODER: name=$decoderName initMs=$initializationDurationMs host=${currentStreamUrl.safeHost()}"
                        )
                    }

                    override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                        if (_exoPlayer !== this@apply) return
                        playbackAnalyticsDiagnostics.onVideoDecoderReleased(eventTime, decoderName)
                    }

                    override fun onVideoInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: Format,
                        decoderReuseEvaluation: DecoderReuseEvaluation?
                    ) {
                        // Decoder input can resolve colour after the initial track snapshot.
                        // Ignore callbacks from an already replaced player.
                        if (_exoPlayer === this@apply) {
                            _uiState.update { it.copy(isHdrVideo = isHdrVideoFormat(format)) }
                        }
                        playbackAnalyticsDiagnostics.onVideoInputFormatChanged(
                            eventTime = eventTime,
                            format = format,
                            reuseEvaluation = decoderReuseEvaluation
                        )
                    }

                    override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: androidx.media3.common.VideoSize) {
                        playbackAnalyticsDiagnostics.onVideoSizeChanged(eventTime, videoSize)
                    }

                    override fun onDroppedVideoFrames(
                        eventTime: AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long
                    ) {
                        playbackAnalyticsDiagnostics.onDroppedVideoFrames(eventTime, droppedFrames, elapsedMs)
                    }

                    override fun onVideoFrameProcessingOffset(
                        eventTime: AnalyticsListener.EventTime,
                        totalProcessingOffsetUs: Long,
                        frameCount: Int
                    ) {
                        playbackAnalyticsDiagnostics.onVideoFrameProcessingOffset(
                            eventTime = eventTime,
                            totalProcessingOffsetUs = totalProcessingOffsetUs,
                            frameCount = frameCount
                        )
                    }

                    override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
                        playbackAnalyticsDiagnostics.onVideoDisabled(eventTime, decoderCounters)
                    }

                    override fun onAudioDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        playbackAnalyticsDiagnostics.onAudioDecoderInitialized(
                            eventTime = eventTime,
                            decoderName = decoderName,
                            initializationDurationMs = initializationDurationMs
                        )
                    }

                    override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                        playbackAnalyticsDiagnostics.onAudioDecoderReleased(eventTime, decoderName)
                    }

                    override fun onAudioInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: Format,
                        decoderReuseEvaluation: DecoderReuseEvaluation?
                    ) {
                        playbackAnalyticsDiagnostics.onAudioInputFormatChanged(
                            eventTime = eventTime,
                            format = format,
                            reuseEvaluation = decoderReuseEvaluation
                        )
                    }

                    override fun onAudioUnderrun(
                        eventTime: AnalyticsListener.EventTime,
                        bufferSize: Int,
                        bufferSizeMs: Long,
                        elapsedSinceLastFeedMs: Long
                    ) {
                        playbackAnalyticsDiagnostics.onAudioUnderrun(
                            eventTime = eventTime,
                            bufferSize = bufferSize,
                            bufferSizeMs = bufferSizeMs,
                            elapsedSinceLastFeedMs = elapsedSinceLastFeedMs
                        )
                        PlayerAudioUnderrunCounter.record()
                    }

                    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
                        if (_exoPlayer !== this@apply) return
                        playbackRecoveryGate.noteAudioSinkError(SystemClock.elapsedRealtime())
                    }

                    override fun onBandwidthEstimate(
                        eventTime: AnalyticsListener.EventTime,
                        totalLoadTimeMs: Int,
                        totalBytesLoaded: Long,
                        bitrateEstimate: Long
                    ) {
                        playbackAnalyticsDiagnostics.onBandwidthEstimate(
                            eventTime = eventTime,
                            totalLoadTimeMs = totalLoadTimeMs,
                            totalBytesLoaded = totalBytesLoaded,
                            bitrateEstimate = bitrateEstimate
                        )
                    }

                    override fun onLoadStarted(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData
                    ) {
                        playbackAnalyticsDiagnostics.onLoadStarted(eventTime, loadEventInfo, mediaLoadData)
                    }

                    override fun onLoadCompleted(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData
                    ) {
                        playbackAnalyticsDiagnostics.onLoadCompleted(eventTime, loadEventInfo, mediaLoadData)
                    }

                    override fun onLoadCanceled(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData
                    ) {
                        playbackAnalyticsDiagnostics.onLoadCanceled(eventTime, loadEventInfo, mediaLoadData)
                    }

                    override fun onLoadError(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData,
                        error: java.io.IOException,
                        wasCanceled: Boolean
                    ) {
                        playbackAnalyticsDiagnostics.onLoadError(
                            eventTime = eventTime,
                            loadEventInfo = loadEventInfo,
                            mediaLoadData = mediaLoadData,
                            error = error,
                            wasCanceled = wasCanceled
                        )
                    }

                    override fun onPositionDiscontinuity(
                        eventTime: AnalyticsListener.EventTime,
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        Log.w(
                            PlayerRuntimeController.TAG,
                            "SEEK_TRACE DISCONTINUITY er=${SystemClock.elapsedRealtime()} reason=$reason " +
                                "oldMs=${oldPosition.positionMs} newMs=${newPosition.positionMs} " +
                                "eventRealtimeMs=${eventTime.realtimeMs}"
                        )
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            lastSeekWallMs = SystemClock.elapsedRealtime()
                            seekReadyLogPending = true
                            seekReadyLogTargetMs = newPosition.positionMs
                        }
                    }
                }
                currentExoAnalyticsListener = exoAnalyticsListenerForStream
                addAnalyticsListener(exoAnalyticsListenerForStream)
            }
            if (!startupSubtitlePreparation.fetchCompleted) {
                fetchAddonSubtitles()
            }
        } catch (e: Exception) {
            if (
                attemptStartupExhaustedSourceFailover(
                    detailedError = e.message ?: context.getString(com.nuvio.tv.R.string.player_error_initialize_failed)
                )
            ) {
                return@launch
            }
            val displayError = e.toDisplayMessage(context, context.getString(com.nuvio.tv.R.string.player_error_initialize_failed))
            val diagnostics = LastPlaybackDiagnostics(
                timestampMs = System.currentTimeMillis(),
                host = currentStreamUrl.safeHost(),
                filename = currentFilename ?: streamName ?: title,
                result = "Error: $displayError"
            )
            lastPlaybackDiagnosticsForReport = diagnostics
            lastPlaybackIssueError = PlaybackIssueErrorInput(
                displayMessage = displayError,
                errorCode = null,
                errorCodeName = null,
                exceptionClass = e.javaClass.name,
                causeClass = e.cause?.javaClass?.name,
                causeMessage = e.cause?.message ?: e.message,
                httpStatus = null
            )
            scope.launch {
                runCatching { playerSettingsDataStore.setLastPlaybackDiagnostics(diagnostics) }
            }
            _uiState.update {
                it.copy(
                    error = displayError,
                    showLoadingOverlay = false,
                    loadingIssueReportVisible = false,
                    loadingIssueElapsedMs = 0L
                )
            }
        }
    }
}

internal suspend fun PlayerRuntimeController.resolveAutoInternalPlayerEngine(): InternalPlayerEngine {
    val streamMetadataText = buildString {
        currentFilename?.let { appendLine(it) }
        streamName?.let { appendLine(it) }
        currentStreamDescription?.let { appendLine(it) }
        append(title)
    }
    val isHdrOrDv = Regex("""(?i)\b(hdr|hdr10\+?|dv|dolby\s*vision)\b""").containsMatchIn(streamMetadataText)

    return if (isHdrOrDv) {
        InternalPlayerEngine.EXOPLAYER
    } else {
        val hasAnimeId = currentVideoId?.startsWith("kitsu:") == true ||
                currentVideoId?.startsWith("mal:") == true ||
                currentVideoId?.startsWith("anilist:") == true

        if (hasAnimeId) return InternalPlayerEngine.MVP_PLAYER

        metaFetchJob?.let { job ->
            withTimeoutOrNull(3000L) { job.join() }
        }

        val hasAnimeGenre = metaGenres.any { it.equals("anime", ignoreCase = true) }
        val isAnimationFromJapan = (metaGenres.any { it.equals("animation", ignoreCase = true) } &&
                metaCountry?.contains("Japan", ignoreCase = true) == true)

        val isAnime = hasAnimeGenre || isAnimationFromJapan

        if (isAnime) InternalPlayerEngine.MVP_PLAYER else InternalPlayerEngine.EXOPLAYER
    }
}

internal fun resolvePreferredAudioLanguages(
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    deviceLanguages: List<String>,
    contentOriginalLanguage: String? = null
): List<String> {
    fun normalize(language: String?): String? {
        val normalized = language?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return when (normalized) {
            AudioLanguageOption.DEFAULT,
            AudioLanguageOption.DEVICE,
            SUBTITLE_LANGUAGE_FORCED -> null
            AudioLanguageOption.ORIGINAL -> contentOriginalLanguage?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            else -> normalized
        }
    }

    return when (preferredAudioLanguage.trim().lowercase()) {
        AudioLanguageOption.DEFAULT -> listOfNotNull(
            normalize(secondaryPreferredAudioLanguage)
        ).distinct()
        AudioLanguageOption.DEVICE -> (
            deviceLanguages
            .mapNotNull(::normalize)
            + listOfNotNull(normalize(secondaryPreferredAudioLanguage))
            ).distinct()
        AudioLanguageOption.ORIGINAL -> {
            val originalLang = normalize(contentOriginalLanguage)
            if (originalLang != null) {
                listOfNotNull(
                    originalLang,
                    normalize(secondaryPreferredAudioLanguage)
                ).distinct()
            } else {
                // Fallback to device languages when original language is unknown
                (deviceLanguages
                    .mapNotNull(::normalize)
                    + listOfNotNull(normalize(secondaryPreferredAudioLanguage))
                ).distinct()
            }
        }
        else -> listOfNotNull(
            normalize(preferredAudioLanguage),
            normalize(secondaryPreferredAudioLanguage)
        ).distinct()
    }
}

internal fun resolveDeviceAudioLanguages(): List<String> {
    return if (Build.VERSION.SDK_INT >= 24) {
        val localeList = Resources.getSystem().configuration.locales
        List(localeList.size()) { localeList[it].isO3Language }
    } else {
        listOf(Resources.getSystem().configuration.locale.isO3Language)
    }
}

internal suspend fun PlayerRuntimeController.prepareStartupSubtitles(): StartupSubtitlePreparation {
    return StartupSubtitlePreparation(
        fetchedSubtitles = emptyList(),
        attachedSubtitles = emptyList(),
        fetchCompleted = false
    )
}

internal fun PlayerRuntimeController.resetAddonSubtitleStateForNewStream() {
    autoSubtitleSelected = subtitleDisabledByPersistedPreference || subtitleAddonRestoredByPersistedPreference
    isUserExplicitSubtitleSelection = false
    hasScannedTextTracksOnce = false
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    explicitSubtitleSelectionForEngineSwitch = null
    effectiveSubtitleSelectionForEngineSwitch = null
    attachedAddonSubtitleKeys = emptySet()
    stopSidecarAddonSubtitle(clearView = true)
    _uiState.update {
        it.copy(
            addonSubtitles = emptyList(),
            selectedAddonSubtitle = null,
            selectedSubtitleTrackIndex = -1,
            isLoadingAddonSubtitles = false,
            addonSubtitlesError = null
        )
    }
}

internal suspend fun PlayerRuntimeController.prepareStreamStartSubtitles(
    playerSettings: PlayerSettings
): StartupSubtitlePreparation {
    requestedUseLibassByUser = playerSettings.useLibass
    if (libassPipelineDecisionStreamUrl != currentStreamUrl) {
        libassPipelineDecisionStreamUrl = currentStreamUrl
        libassPipelineOverrideForCurrentStream = null
        libassPipelineSwitchInFlight = false
        hasDetectedAssSsaTrackForCurrentStream = false
    }
    resetAddonSubtitleStateForNewStream()
    return prepareStartupSubtitles()
}

internal fun PlayerRuntimeController.applyStartupSubtitlePreparation(startupSubtitlePreparation: StartupSubtitlePreparation) {
    attachedAddonSubtitleKeys = startupSubtitlePreparation.attachedSubtitles.distinctBy { addonSubtitleKey(it) }.map(::addonSubtitleKey).toSet()
    if (!startupSubtitlePreparation.fetchCompleted) return
    _uiState.update { it.copy(addonSubtitles = startupSubtitlePreparation.fetchedSubtitles, isLoadingAddonSubtitles = false, addonSubtitlesError = null) }
}

internal fun PlayerRuntimeController.buildStartupSubtitleConfigurations(startupSubtitlePreparation: StartupSubtitlePreparation): List<androidx.media3.common.MediaItem.SubtitleConfiguration> {
    return startupSubtitlePreparation.attachedSubtitles.distinctBy { "${it.id}|${it.url}" }.map(::toSubtitleConfiguration)
}

internal fun PlayerRuntimeController.resetLoadingOverlayForNewStream() {
    // Drop the previous session's resolved serving host so a new
    // stream (initial play or any switch) never shows the prior title's
    // CDN host during TTFF.
    PlaybackConnectionEvents.clearResolvedHost()
    cancelFirstFrameWatchdog()
    cancelTunnelAvSyncWatchdog()
    cancelStallWatchdog()
    cancelStartupWatchdog()
    val preparingMessage = context.getString(R.string.player_loading_preparing)
    resetLoadingDiagnostics(
        phase = "preparing",
        message = preparingMessage,
        progress = null
    )
    hasRenderedFirstFrame = false
    endDetectionArmed = false
    mpvEofSeenClear = false
    hasMarkedCurrentEpisodeCompleted = false
    // The placeholder probe is a per-stream measurement, not a
    // per-screen one: the reuse path re-enters initializePlayer on the
    // same controller, so without this reset it would evaluate only the first
    // stream of a binge and stay inert on every transition after it.
    placeholderProbeDone = false
    shouldEnforceAutoplayOnFirstReady = true
    userPausedManually = false
    scrubHoldPaused = false
    timeoutRecoveryAttempts = 0
    hasRetriedCurrentStreamAfterUnexpectedNpe = false
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = false
    hasRetriedCurrentStreamAfter416 = false
    hasRetriedAfterMimeOverrideClear = false
    hasAttemptedDv7ToDv81ForCurrentPlayback = false
    isExperimentalDv7ToDv81ActiveForCurrentPlayback = false
    isVc1TrackSelectionBypassActiveForCurrentPlayback = false
    isSafeAudioModeActiveForCurrentPlayback = false
    isAudioDisabledForCurrentPlayback = false
    dv7ToDv81BridgeVersionForCurrentPlayback = null
    dv7ToDv81LastProbeReasonForCurrentPlayback = null
    playerInitializationStartedAtMs = 0L
    pendingSeekTelemetryRequestedAtMs = 0L
    pendingSeekTelemetryTargetMs = -1L
    pendingSeekTelemetryReadyAtMs = 0L
    pendingSeekTelemetryReadyLatencyMs = -1L
    pendingSeekTelemetryAwaitingFirstFrame = false
    pendingSeekTelemetryReadyAssumed = false
    lastKnownDuration = 0L
    currentStreamHasVideoTrack = false
    currentVideoTrackIsLikelyVc1 = false
    currentVideoTrackMimeType = null
    currentVideoTrackCodecs = null
    currentVideoTrackWidth = 0
    currentVideoTrackHeight = 0
    currentVideoTrackBitrate = -1
    currentVideoTrackColorTransfer = null
    _uiState.update { it.copy(isHdrVideo = false) }
    currentVideoTrackSelected = false
    currentVideoTrackBestSupport = C.FORMAT_UNSUPPORTED_TYPE
    lastLoggedVideoTrackSignature = null
    _uiState.update { state ->
        state.copy(
            showLoadingOverlay = state.loadingOverlayEnabled,
            showControls = false,
            loadingMessage = preparingMessage,
            loadingIssueReportVisible = false,
            loadingIssueElapsedMs = 0L,
            loadingProgress = null
        )
    }
}

internal fun surroundTargetToOutputChannels(
    channels: Int,
    fallback: com.nuvio.tv.data.local.AudioOutputChannels
): com.nuvio.tv.data.local.AudioOutputChannels = when (channels) {
    2 -> com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_2_0
    3 -> com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_2_1
    4 -> com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_3_1
    6 -> com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_5_1
    8 -> com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_7_1
    else -> fallback
}

// ── CUSTOM RENDERERS FOR AUDIO/SUBTITLES ──

private const val SEEK_SOURCE_SETTLE_MS = 800L

private class SubtitleOffsetRenderersFactory(
    context: Context,
    private val subtitleDelayUsProvider: () -> Long,
    private val audioDelayUsProvider: () -> Long,
    private val shouldNormalizeCuePositionProvider: () -> Boolean,
    private val presentCue: (Cue) -> Cue,
    private val shouldStripSdhProvider: () -> Boolean,
    private val isBuiltInSubtitleProvider: () -> Boolean,
    private val isSidecarAddonSubtitleActiveProvider: () -> Boolean = { false },
    private val videoBoundsFractionProvider: () -> RectF?,
    private val gainAudioProcessor: GainAudioProcessor,
    private val downmixEnabled: Boolean,
    private val audioOutputChannels: com.nuvio.tv.data.local.AudioOutputChannels,
    private val downmixNormalizationEnabled: Boolean,
    private val forceOpticalPassthrough: Boolean,
    private val useSystemPassthrough: Boolean,
    private val deniedTranscodeMimes: Set<String>,
    private val preferFfmpegAudio: Boolean,
    private val audioPassthroughPolicy: AudioPassthroughPolicy,
    private val bluetoothForcePcm: Boolean = false,
    /** Native FEL: put [com.nuvio.tv.core.player.amlfel.AmlDvFelVideoRenderer] first (claims DV P7 only). */
    private val nativeFelActive: Boolean = false,
    private val onNativeSelectionChanged: (Boolean) -> Unit = {},
    private val nativeMelEnabled: Boolean = true,
    private val vendorUnstablePts: Boolean = false,
    private val mapDv7ToHevc: Boolean = false,
    private val playbackSpeedProvider: () -> Float,
    private val initialForcePcm: Boolean = false,
    /**
     * When true, [EXTENSION_RENDERER_MODE_PREFER] applies to audio only — video stays on the
     * platform MediaCodec path so Bluetooth PCM policy does not force software video decode.
     */
    private val preferSoftwareAudioOnly: Boolean = false,
    private val onPlaybackSpeedAwareAudioSinkCreated: (PlaybackSpeedAwareAudioSink) -> Unit,
    private val onFfmpegAudioRendererChanged: (FfmpegAudioRenderer?) -> Unit,
    private val onAudioDiagnosticEvent: ((String) -> Unit)? = null
) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        val videoExtensionMode = when {
            !preferSoftwareAudioOnly -> extensionRendererMode
            extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER -> EXTENSION_RENDERER_MODE_ON
            else -> extensionRendererMode
        }
        if (nativeFelActive) {
            // Ahead of MediaCodec so it wins the DV profile 7 track (equal support -> first renderer).
            out.add(com.nuvio.tv.core.player.amlfel.AmlDvFelVideoRenderer(eventHandler, eventListener, playbackSpeedProvider, nativeMelEnabled, onNativeSelectionChanged))
        }
        val vc1RestampTmp = ArrayList<Renderer>()
        super.buildVideoRenderers(
            context,
            videoExtensionMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            vc1RestampTmp
        )
        // Decode-order time repair (AVI, VC-1, XviD/DivX): replace ONLY the platform MediaCodec
        // video renderer with the repairing subclass; extension renderers (VP9/AV1) and their
        // PREFER/ON ordering pass through untouched.
        for (vc1RestampRenderer in vc1RestampTmp) {
            if (vc1RestampRenderer::class.java == MediaCodecVideoRenderer::class.java) {
                val vc1RestampBuilder = MediaCodecVideoRenderer.Builder(context)
                    .setCodecAdapterFactory(getCodecAdapterFactory())
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(
                        DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                    )
                    // The factory's own setting only reaches the stock renderer replaced here.
                    .setMapDV7ToHevc(mapDv7ToHevc)
                out.add(DecodeOrderPtsVideoRenderer(vc1RestampBuilder, vendorUnstablePts))
            } else {
                out.add(vc1RestampRenderer)
            }
        }
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        // Bluetooth: pin Media3-equivalent DEFAULT (PCM-only) so TV HDMI profiles / force-optical
        // cannot advertise AC3/DTS passthrough while audio is routed to A2DP.
        // Otherwise, on Android TV, pin audio capabilities: build the sink WITHOUT a Context so
        // media3 does NOT install a live AudioCapabilitiesReceiver. On the context
        // path, any audio-device change (e.g. a Bluetooth remote idling for battery,
        // then reinitialising) makes media3 re-query capabilities mid-playback; if
        // the HAL transiently omits the passthrough encoding, DefaultAudioSink
        // renegotiates and drops Atmos/TrueHD/DTS-HD to PCM. VLC avoids this by not
        // subscribing to those events. We probe once (with the MEDIA/MOVIE
        // attributes playback uses) and pin.
        // On phones/tablets, keep the live receiver: headphone/Bluetooth/routing
        // changes are common there and must be handled dynamically.
        // TRADE-OFF (TV): capabilities fixed at build time, no adaptation if the
        // output genuinely changes mid-playback; a cold AVR/soundbar wake-up may be
        // probed before it reports passthrough (recovers on next play). A Bluetooth
        // route flip IS handled: the route callback rebuilds the player, landing in
        // the bluetoothForcePcm branch here.
        val isTelevision =
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        var pinnedOutputEncodings: Set<Int>? = null
        val builder = (when {
            bluetoothForcePcm -> {
                DefaultAudioSink.Builder()
                    .setAudioCapabilities(AudioOutputRouteDetector.bluetoothPcmOnlyCapabilities())
            }
            isTelevision -> {
                val pinned = PassthroughOutputReturn.capabilities(context)
                pinnedOutputEncodings = PassthroughOutputReturn.encodingsOf(pinned)
                DefaultAudioSink.Builder()
                    .setAudioCapabilities(pinned)
            }
            else -> DefaultAudioSink.Builder(context)
        })
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(gainAudioProcessor))
            .setAudioTrackBufferSizeProvider(cappedPassthroughBufferSizeProvider)
        val baseAudioSink = builder.build()
        // Read-only capability snapshot for the diagnostics page. Never opens an
        // AudioTrack - startup IEC61937 activity is a wedge risk on some HALs.
        AudioCapabilityReport.capture(context)
        // Diagnostic harness: arm with
        //   adb shell settings put global nuvio_fault_reject_mime audio/vnd.dts.hd
        // disarm with `settings delete global nuvio_fault_reject_mime`. Read at build
        // time, so arm it before starting playback. Inert when the setting is absent.
        val faultInjectRejectMime = runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, "nuvio_fault_reject_mime")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (faultInjectRejectMime != null) {
            Log.w(PlayerRuntimeController.TAG, "FAULT_INJECT armed: passthrough for $faultInjectRejectMime will be refused")
        }
        // Lower start threshold for platform DIRECT tracks, so playback does not wait for
        // the whole buffer to fill. Override with
        //   adb shell settings put global nuvio_reduced_start_threshold <frames>
        // and disable with a value of 0.
        val defaultStartThresholdFrames = 262144  // ~5.5 s at 48 kHz
        val reducedStartThresholdFrames = runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, "nuvio_reduced_start_threshold")
        }.getOrNull()?.trim()?.toIntOrNull() ?: defaultStartThresholdFrames
        if (reducedStartThresholdFrames > 0) {
            Log.w(PlayerRuntimeController.TAG, "STHRESH: DIRECT start threshold -> $reducedStartThresholdFrames frames")
        }
        var speedAwareSink: PlaybackSpeedAwareAudioSink? = null
        val iecAudioSink = IecPassthroughAudioSink(
            sink = baseAudioSink,
            hbrIecEnabled = !useSystemPassthrough && !forceOpticalPassthrough && !bluetoothForcePcm,
            onIecBecameReady = {
                Handler(Looper.getMainLooper()).post {
                    speedAwareSink?.notifyAudioProcessingRequirementChanged()
                }
            },
            onDiagnosticEvent = onAudioDiagnosticEvent
        )
        val playbackSpeedAwareAudioSink = PlaybackSpeedAwareAudioSink(
            sink = iecAudioSink,
            initialForcePcm = initialForcePcm,
            forcePcmForBluetooth = bluetoothForcePcm,
            passthroughPolicy = audioPassthroughPolicy,
            onDiagnosticEvent = onAudioDiagnosticEvent,
            forceAc3Support = forceOpticalPassthrough || deniedTranscodeMimes.isNotEmpty(),
            faultInjectRejectMime = faultInjectRejectMime,
            reducedStartThresholdFrames = reducedStartThresholdFrames,
            platformSink = baseAudioSink,
            pinnedOutputEncodings = pinnedOutputEncodings
        )
        speedAwareSink = playbackSpeedAwareAudioSink
        playbackSpeedAwareAudioSink.setInitialPlaybackSpeed(playbackSpeedProvider())
        onPlaybackSpeedAwareAudioSinkCreated(playbackSpeedAwareAudioSink)
        return playbackSpeedAwareAudioSink
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val playbackAwareSink = audioSink as? PlaybackSpeedAwareAudioSink
        val startIndex = out.size
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
        if (playbackAwareSink != null && out.size > startIndex) {
            val mediaCodecAudioRendererIndex = (startIndex until out.size)
                .firstOrNull { index -> out[index] is MediaCodecAudioRenderer }
                ?: startIndex
            out[mediaCodecAudioRendererIndex] =
                PlaybackSpeedAwareAudioRenderer(
                    rendererContext = context,
                    codecAdapterFactory = getCodecAdapterFactory(),
                    mediaCodecSelector = mediaCodecSelector,
                    enableDecoderFallback = enableDecoderFallback,
                    eventHandler = eventHandler,
                    eventListener = eventListener,
                    playbackSpeedAwareAudioSink = playbackAwareSink
                )
        }
        applyFfmpegRendererSettings(out)
        // When Force AC-3 Transcoding is on, the FFmpeg audio
        // renderer must outrank MediaCodec audio for the transcode to engage -
        // but forcing EXTENSION_RENDERER_MODE_PREFER globally would also reorder
        // *video* extension renderers, putting software AV1 (Libgav1) ahead of
        // the hardware decoder. Reorder audio-locally:
        // move FFmpeg audio renderers ahead of other audio renderers in the
        // block we just built, leaving video renderer order untouched.
        if ((forceOpticalPassthrough || preferFfmpegAudio) && out.size > startIndex) {
            val audioBlock = out.subList(startIndex, out.size)
            val reordered = audioBlock.sortedByDescending { it is FfmpegAudioRenderer }
            for (i in reordered.indices) audioBlock[i] = reordered[i]
        }
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val normalizingOutput = CueNormalizingTextOutput(
            delegate = SdhFilteringTextOutput(output, shouldStripSdhProvider),
            shouldNormalizeCuePositionProvider = shouldNormalizeCuePositionProvider,
            presentCue = presentCue,
            isBuiltInSubtitleProvider = isBuiltInSubtitleProvider,
            isSidecarAddonSubtitleActiveProvider = isSidecarAddonSubtitleActiveProvider,
            videoBoundsFractionProvider = videoBoundsFractionProvider
        )
        val startIndex = out.size
        super.buildTextRenderers(context, normalizingOutput, outputLooper, extensionRendererMode, out)
        for (index in startIndex until out.size) {
            out[index] = SubtitleOffsetRenderer(
                baseRenderer = out[index],
                subtitleDelayUsProvider = subtitleDelayUsProvider,
                audioDelayUsProvider = audioDelayUsProvider
            )
        }
    }

    private fun applyFfmpegRendererSettings(out: ArrayList<Renderer>) {
        val ffmpegRenderers = out.filterIsInstance<FfmpegAudioRenderer>()
        ffmpegRenderers.forEach { renderer ->
            renderer.applyDownmixSettings(
                downmixEnabled = downmixEnabled,
                audioOutputChannels = audioOutputChannels,
                downmixNormalizationEnabled = downmixNormalizationEnabled,
                forceOpticalPassthrough = forceOpticalPassthrough,
                deniedTranscodeMimes = deniedTranscodeMimes
            )
        }
        onFfmpegAudioRendererChanged(ffmpegRenderers.firstOrNull())
    }
}
internal fun FfmpegAudioRenderer.applyDownmixSettings(
    downmixEnabled: Boolean,
    audioOutputChannels: com.nuvio.tv.data.local.AudioOutputChannels,
    downmixNormalizationEnabled: Boolean,
    forceOpticalPassthrough: Boolean,
    deniedTranscodeMimes: Set<String>
) {
    setForceOpticalPassthrough(forceOpticalPassthrough)
    setDeniedTranscodeMimes(deniedTranscodeMimes)
    if (downmixEnabled) {
        setAudioOutputChannels(
            audioOutputChannels.ffmpegLayoutName,
            audioOutputChannels.channelCount
        )
        setDownmixNormalizationEnabled(downmixNormalizationEnabled)
    } else {
        setAudioOutputChannels(null, 0)
        setDownmixNormalizationEnabled(false)
    }
}

private class CueNormalizingTextOutput(
    private val delegate: TextOutput,
    private val shouldNormalizeCuePositionProvider: () -> Boolean,
    private val presentCue: (Cue) -> Cue,
    private val isBuiltInSubtitleProvider: () -> Boolean,
    private val isSidecarAddonSubtitleActiveProvider: () -> Boolean,
    private val videoBoundsFractionProvider: () -> RectF?
) : TextOutput {

    override fun onCues(cueGroup: CueGroup) {
        if (isSidecarAddonSubtitleActiveProvider()) {
            return
        }
        val cues = cueGroup.cues
        if (cues.isEmpty()) {
            delegate.onCues(cueGroup)
            return
        }
        var modifiedList: ArrayList<Cue>? = null
        val count = cues.size
        for (i in 0 until count) {
            val original = cues[i]
            val processed = processCue(original)
            if (processed !== original) {
                if (modifiedList == null) {
                    modifiedList = ArrayList(count)
                    for (j in 0 until i) {
                        modifiedList.add(cues[j])
                    }
                }
                modifiedList.add(processed)
            } else {
                modifiedList?.add(original)
            }
        }
        val processedCues = modifiedList ?: cues
        val mergedCues = PlayerSubtitleUtils.mergeOverlappingCues(processedCues)
        delegate.onCues(CueGroup(mergedCues, cueGroup.presentationTimeUs))
    }

    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        if (isSidecarAddonSubtitleActiveProvider()) {
            return
        }
        if (cues.isEmpty()) {
            delegate.onCues(cues)
            return
        }
        var modifiedList: ArrayList<Cue>? = null
        val count = cues.size
        for (i in 0 until count) {
            val original = cues[i]
            val processed = processCue(original)
            if (processed !== original) {
                if (modifiedList == null) {
                    modifiedList = ArrayList(count)
                    for (j in 0 until i) {
                        modifiedList.add(cues[j])
                    }
                }
                modifiedList.add(processed)
            } else {
                modifiedList?.add(original)
            }
        }
        val processedCues = modifiedList ?: cues
        val mergedCues = PlayerSubtitleUtils.mergeOverlappingCues(processedCues)
        delegate.onCues(mergedCues)
    }

    private fun processCue(cue: Cue): Cue {
        var processed = SubtitleMojibakeSanitizer.sanitizeCue(cue)
        processed = PlayerSubtitleRtlFix.fixCueText(processed, isBuiltInSubtitleProvider())
        if (shouldNormalizeCuePositionProvider()) {
            processed = normalizeCuePosition(processed)
        }
        if (processed.bitmap != null) {
            val bounds = videoBoundsFractionProvider()
            if (bounds != null && bounds.width() > 0f && bounds.height() > 0f) {
                val isIdentity = bounds.left == 0f && bounds.top == 0f
                    && bounds.width() == 1f && bounds.height() == 1f
                if (!isIdentity) {
                    processed = remapBitmapCueToVideoBounds(processed, bounds)
                }
            }
        }
        return presentCue(processed)
    }

    private fun remapBitmapCueToVideoBounds(cue: Cue, bounds: RectF): Cue {
        val builder = cue.buildUpon()
        if (cue.position != Cue.DIMEN_UNSET) {
            builder.setPosition(bounds.left + cue.position * bounds.width())
        }
        if (cue.size != Cue.DIMEN_UNSET) {
            builder.setSize(cue.size * bounds.width())
        }
        if (cue.lineType == Cue.LINE_TYPE_FRACTION && cue.line != Cue.DIMEN_UNSET) {
            builder.setLine(bounds.top + cue.line * bounds.height(), Cue.LINE_TYPE_FRACTION)
        }
        if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
            builder.setBitmapHeight(cue.bitmapHeight * bounds.height())
        }
        return builder.build()
    }

    private fun normalizeCuePosition(cue: Cue): Cue {
        if (cue.bitmap != null || cue.verticalType != Cue.TYPE_UNSET || cue.line == Cue.DIMEN_UNSET) {
            return cue
        }
        return cue.buildUpon()
            .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
            .setLineAnchor(Cue.TYPE_UNSET)
            .build()
    }
}

private class SubtitleOffsetRenderer(
    private val baseRenderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long,
    private val audioDelayUsProvider: () -> Long
) : ForwardingRenderer(baseRenderer) {

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val subtitleOffsetUs = subtitleDelayUsProvider()
        val audioOffsetUs = audioDelayUsProvider()
        val adjustedPositionUs = (positionUs + audioOffsetUs - subtitleOffsetUs).coerceAtLeast(0L)
        
        super.render(adjustedPositionUs, elapsedRealtimeUs)
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

private fun PlaybackException.isDolbyVisionDecoderFailure(): Boolean {
    // media3 surfaces failure-to-instantiate as
    // ERROR_CODE_DECODER_INIT_FAILED ("Decoder init failed..."), which the
    // message-string match below never catches - so the mode2 -> mode1 -> HDR10
    // retry ladder would never fire on devices whose DV decoder exists but refuses to
    // start (the hidden/broken-licence Amlogic case DolbyVisionCodecFallback
    // documents). Match init failures by code + the failing format's DV MIME.
    if (errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
        val mime = (this as? androidx.media3.exoplayer.ExoPlaybackException)
            ?.rendererFormat?.sampleMimeType
        if (mime?.contains("dolby-vision", ignoreCase = true) == true) return true
        // Init-failure messages name the codec; catch DV decoders that way too.
        val initDetails = (message ?: "") + ' ' + (cause?.message ?: "")
        return initDetails.contains("dvhe", ignoreCase = true) ||
            initDetails.contains("dvh1", ignoreCase = true) ||
            initDetails.contains("dolby", ignoreCase = true)
    }
    if (errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("dolby-vision", ignoreCase = true) && details.contains("decoder failed", ignoreCase = true)
}

private fun PlaybackException.isUnexpectedLoaderNullPointer(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_IO_UNSPECIFIED) return false
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("unexpected nullpointerexception", ignoreCase = true) ||
            (details.contains("nullpointerexception", ignoreCase = true) && details.contains("matroskaextractor", ignoreCase = true))
}

/**
 * Notes a refused bitstream open. The session log feeds the diagnostics card; the stash is
 * persisted as a learned denial only once a fallback has opened audio.
 */
internal fun PlayerRuntimeController.noteAudioTrackRejection(error: PlaybackException, retryScheduled: Boolean) {
    if (!error.isAudioTrackFailure()) return
    val mime = failedAudioTrackInputFormat(error)?.sampleMimeType
    val routeKey = currentAudioOutputRoute?.key
    AudioTrackRejectionLog.labelForMime(mime)?.let { label ->
        AudioTrackRejectionLog.record(label, routeKey, System.currentTimeMillis())
        Log.w(PlayerRuntimeController.TAG, "AUDIO_TRACK_REJECTION encoding=$label route=$routeKey retry=$retryScheduled")
    }
    val group = com.nuvio.tv.core.player.AudioPassthroughPolicy.groupOf(mime)
    if (group != null && routeKey != null && error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) {
        AudioRejectionReverifier.ledger.noteOpenRefused(routeKey, group)
        saveAudioRefusalEvidence()
    }
    if (group != null && !playbackRecoveryGate.shouldLearnAudioRejection(group)) {
        Log.w(PlayerRuntimeController.TAG, "AUDIO_TRACK_REJECTION_NOT_LEARNED group=${group.name} opened earlier in this playback")
        return
    }
    audioRejectionToStash(error.errorCode, retryScheduled, mime, routeKey)?.let { entry ->
        AudioRejectionReverifier.ledger.stashPending(currentStreamUrl, entry)
    }
}

internal fun PlayerRuntimeController.saveAudioRefusalEvidence() {
    scope.launch {
        playerSettingsDataStore.saveAudioRefusalEvidence(AudioRejectionReverifier.ledger.evidenceSnapshot())
    }
}

internal fun PlayerRuntimeController.recordStashedAudioRejection(openedEncoding: Int) {
    val entry = AudioRejectionReverifier.ledger.takePendingFor(currentStreamUrl) ?: return
    val routeKey = AudioRejectionLedger.routeOf(entry) ?: return
    val group = AudioRejectionLedger.groupOf(entry) ?: return
    val commit = AudioRejectionReverifier.ledger.commit(entry)
    saveAudioRefusalEvidence()
    if (!commit.learn) {
        Log.w(
            PlayerRuntimeController.TAG,
            "AUDIO_REJECTION_NOT_LEARNED group=${group.name} route=$routeKey: " +
                "${commit.refusedOnRoute.joinToString(",") { it.name }} refused with no bitstream open in between, " +
                "AC-3 or E-AC-3 among them: the output is down, not the format" +
                if (commit.forget.isEmpty()) "" else "; forgetting ${commit.forget.joinToString(",")}"
        )
        for (stale in commit.forget) scope.launch { playerSettingsDataStore.clearAudioRejection(stale) }
        return
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "AUDIO_REJECTION: ${group.name} refused at open on $routeKey, fallback opened " +
            "encoding=$openedEncoding; recording denial"
    )
    scope.launch { playerSettingsDataStore.recordAudioRejection(routeKey, group.name) }
}

private fun PlaybackException.isStuckBufferingWatchdog(): Boolean {
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return isStuckBufferingWatchdog(errorCode, details)
}

private fun PlaybackException.isAudioTrackFailure(): Boolean {
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return isAudioTrackFailure(errorCode, details)
}

private fun PlaybackException.isStuckPlayingNoProgress(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_TIMEOUT) return false
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("stuck playing with no progress", ignoreCase = true)
}

private fun PlaybackException.isMediaPeriodHolderStateCrash(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_UNSPECIFIED) return false
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("mediaperiodholder", ignoreCase = true) && details.contains(".info", ignoreCase = true) && details.contains("null", ignoreCase = true)
}

internal fun String.safeHost(): String {
    return runCatching { Uri.parse(this).host ?: "unknown" }.getOrDefault("unknown")
}

/**
 * Parses the DV profile number from a codec string, e.g. "dvhe.07.06" gives 7.
 * Used as a fallback when libdovi bridge hasn't loaded (e.g. HDR10_BASE_LAYER
 * mode strips DV before the bridge runs, so its source-profile detector
 * never sees the stream).
 */
private fun parseDvProfileFromCodecString(codecs: String?): Int? {
    if (codecs.isNullOrBlank()) return null
    val match = Regex("^(?:dvhe|dvav|dvh1|dva1)\\.(\\d+)\\.").find(codecs.trim().lowercase()) ?: return null
    return match.groupValues[1].toIntOrNull()
}

/** Human-friendly codec name for the diagnostics card. */
private fun friendlyVideoCodecName(mimeType: String?, codecs: String?): String? {
    val mime = mimeType?.lowercase()
    return when {
        mime == null -> null
        mime == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        mime == MimeTypes.VIDEO_H265 -> "HEVC"
        mime == MimeTypes.VIDEO_H264 -> "H.264"
        mime == MimeTypes.VIDEO_AV1 -> "AV1"
        mime == MimeTypes.VIDEO_VP9 -> "VP9"
        mime.startsWith("video/") -> mime.removePrefix("video/").uppercase()
        else -> codecs ?: mime
    }
}

/**
 * Human-friendly HDR/output type for the diagnostics card — reflects what is
 * actually output, not just the source track mime. When DV7 is stripped to the
 * HDR10 base layer the output is HDR10/SDR even though the track mime is DV.
 */
private fun friendlyVideoHdrType(
    mimeType: String?,
    colorTransfer: Int?,
    effectiveModeName: String?,
    dvConversionOccurred: Boolean
): String? {
    val isDolbyVisionMime = mimeType?.lowercase() == MimeTypes.VIDEO_DOLBY_VISION
    fun fromTransfer(): String? = when (colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10"
        C.COLOR_TRANSFER_HLG -> "HLG"
        C.COLOR_TRANSFER_SDR -> "SDR"
        else -> null
    }
    return when {
        // Ignore DV data: output is HDR10/SDR, never Dolby Vision.
        effectiveModeName == "HDR10_BASE_LAYER" -> fromTransfer() ?: "HDR10"
        // DV RPU stripped: output is HDR10 base layer, never Dolby Vision.
        effectiveModeName == "STRIP_DV" -> fromTransfer() ?: "HDR10"
        // DV8.1 conversion, but only label it DV if a conversion actually ran. AUTO arms
        // this mode for every file on a DV display, so plain SDR/HDR10 lands here too.
        effectiveModeName == "DV81_LIBDOVI" && dvConversionOccurred -> "Dolby Vision"
        effectiveModeName == "DV81_LIBDOVI" -> fromTransfer()
        // Native DV passthrough.
        isDolbyVisionMime -> "Dolby Vision"
        else -> fromTransfer()
    }
}

private fun createDolbyVisionFallbackCodecSelector(
    convertToDv81Active: Boolean = false
): MediaCodecSelector {
    // Stripping DV7 to its HEVC base layer is handled by the renderer (setMapDV7ToHevc),
    // which only touches profile 7. We must NOT force video/dolby-vision to the HEVC
    // decoder here: that also catches DV5, which has no HDR10 base layer and ends up
    // decoded without its reshaping (wrong colors). DV5 keeps the DV decoder.
    return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        val defaults = MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType, requiresSecureDecoder, requiresTunnelingDecoder
        )
        if (defaults.isNotEmpty()) {
            return@MediaCodecSelector defaults
        }
        // VC-1 MIME bridge. media3 tags Blu-ray VC-1 as video/wvc1, but Amlogic
        // (S905X5) registers its VC-1 decoders as video/vc1, so a video/wvc1 query
        // returns nothing on hardware that can decode it (MediaCodecList lists
        // supportedTypes=[video/vc1] for c2.amlogic.vc1.decoder and .sw, and nothing
        // under video/wvc1). Re-query under video/vc1; the returned
        // MediaCodecInfo carries codecMimeType=video/vc1, which the renderer sets as
        // the MediaFormat KEY_MIME at configure, so the codec gets a string it accepts.
        if (mimeType.equals(MimeTypes.VIDEO_VC1, ignoreCase = true)) {
            val vc1Decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                "video/vc1", requiresSecureDecoder, requiresTunnelingDecoder
            )
            if (vc1Decoders.isNotEmpty()) {
                return@MediaCodecSelector vc1Decoders
            }
        }
        // DivX 4/5/6 is MPEG-4 ASP; few devices register a decoder under video/divx.
        if (mimeType.equals(MimeTypes.VIDEO_DIVX, ignoreCase = true)) {
            val mpeg4Decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                MimeTypes.VIDEO_MP4V, requiresSecureDecoder, requiresTunnelingDecoder
            )
            if (mpeg4Decoders.isNotEmpty()) {
                return@MediaCodecSelector mpeg4Decoders
            }
        }
        if (convertToDv81Active && mimeType == MimeTypes.VIDEO_DOLBY_VISION) {
            return@MediaCodecSelector DolbyVisionCodecFallback.findDvDecodersIgnoringProfile()
        }
        defaults
    }
}
private fun describeExtensionRendererMode(mode: Int): String {
    return when (mode) {
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF -> "off"
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> "on"
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> "prefer"
        else -> mode.toString()
    }
}

private fun promotePassthroughAudioWhenRendererAlive(
    mappedTrackInfo: androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo,
    rendererFormatSupports: Array<out Array<out IntArray>>,
    passthroughPolicy: AudioPassthroughPolicy?,
    audioSink: AudioSink?
) {
    for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
        if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_AUDIO) continue
        val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
        val supports = rendererFormatSupports[rendererIndex]
        var rendererAlive = false
        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups[groupIndex]
            for (trackIndex in 0 until group.length) {
                if (RendererCapabilities.getFormatSupport(supports[groupIndex][trackIndex]) ==
                    C.FORMAT_HANDLED
                ) {
                    rendererAlive = true
                }
            }
        }
        if (!rendererAlive) continue
        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups[groupIndex]
            for (trackIndex in 0 until group.length) {
                val mime = group.getFormat(trackIndex).sampleMimeType ?: continue
                if (!isPassthroughAudioMime(mime)) continue
                val current = supports[groupIndex][trackIndex]
                val sinkSupport = audioSink?.getFormatSupport(group.getFormat(trackIndex))
                    ?: AudioSink.SINK_FORMAT_UNSUPPORTED
                if (!PassthroughTrackPromotion.shouldPromote(
                        current,
                        sinkSupport,
                        policyDenies = passthroughPolicy?.deniesPassthrough(mime) == true
                    )
                ) continue
                supports[groupIndex][trackIndex] = RendererCapabilities.create(
                    C.FORMAT_HANDLED,
                    RendererCapabilities.ADAPTIVE_SEAMLESS,
                    RendererCapabilities.getTunnelingSupport(current),
                    RendererCapabilities.getHardwareAccelerationSupport(current),
                    RendererCapabilities.getDecoderSupport(current)
                )
            }
        }
    }
}

private fun demoteAudioTunnelingWhereItCannotBeClocked(
    mappedTrackInfo: androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo,
    rendererFormatSupports: Array<out Array<out IntArray>>,
    ffmpegRendererName: String?,
    audioSink: PlaybackSpeedAwareAudioSink?,
    deadClockAudioClasses: Set<String>
) {
    for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
        if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_AUDIO) continue
        val rendererName = mappedTrackInfo.getRendererName(rendererIndex)
        val isFfmpegRenderer = ffmpegRendererName != null && rendererName == ffmpegRendererName
        val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
        val supports = rendererFormatSupports[rendererIndex]
        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups[groupIndex]
            for (trackIndex in 0 until group.length) {
                val format = group.getFormat(trackIndex)
                val reason = when {
                    isFfmpegRenderer ->
                        if (deadClockAudioClasses.contains(PlaybackSpeedAwareAudioSink.TUNNEL_AUDIO_CLASS_PCM)) {
                            "dead-tunnel-clock"
                        } else {
                            null
                        }
                    audioSink == null -> null
                    audioSink.demandsNonTunnelledVideo(format) -> "iec-hbr"
                    audioSink.hbrDemandsNonTunnelledVideo(format) -> "hbr-format"
                    deadClockAudioClasses.isNotEmpty() &&
                        deadClockAudioClasses.contains(audioSink.tunnelAudioClass(format)) -> "dead-tunnel-clock"
                    else -> null
                } ?: continue
                val current = supports[groupIndex][trackIndex]
                if (RendererCapabilities.getTunnelingSupport(current) ==
                    RendererCapabilities.TUNNELING_NOT_SUPPORTED
                ) {
                    continue
                }
                Log.d(
                    "NuvioTrackSelector",
                    "Tunnelling cleared for renderer=$rendererName mime=${format.sampleMimeType} reason=$reason"
                )
                supports[groupIndex][trackIndex] = RendererCapabilities.create(
                    RendererCapabilities.getFormatSupport(current),
                    RendererCapabilities.getAdaptiveSupport(current),
                    RendererCapabilities.TUNNELING_NOT_SUPPORTED,
                    RendererCapabilities.getHardwareAccelerationSupport(current),
                    RendererCapabilities.getDecoderSupport(current)
                )
            }
        }
    }
}

private fun isPassthroughAudioMime(mime: String): Boolean {
    return PassthroughWaterLevelPacer.isPassthroughMime(mime)
}

private val defaultPassthroughBuffers = DefaultAudioTrackBufferSizeProvider.Builder().build()

private val cappedPassthroughBufferSizeProvider =
    DefaultAudioSink.AudioTrackBufferSizeProvider { minBuffer, encoding, outputMode, pcmFrameSize, sampleRate, bitrate, maxSpeed ->
        val size = defaultPassthroughBuffers.getBufferSizeInBytes(
            minBuffer,
            encoding,
            outputMode,
            pcmFrameSize,
            sampleRate,
            bitrate,
            maxSpeed
        )
        val capBytes = if (outputMode == DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH) {
            passthroughBufferCapBytes(encoding)
        } else {
            Int.MAX_VALUE
        }
        maxOf(minBuffer, minOf(size, capBytes))
    }

private fun passthroughBufferCapBytes(encoding: Int): Int {
    return when (encoding) {
        C.ENCODING_DOLBY_TRUEHD -> 2 * 61_440
        C.ENCODING_DTS_HD, C.ENCODING_DTS_UHD_P2 -> 4 * 30_720
        C.ENCODING_DTS -> 16 * 2_012
        C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC -> 2 * 10_752
        C.ENCODING_AC3 -> 1_536 * 8
        C.ENCODING_AC4 -> 16_384
        else -> 16_384
    }
}

private fun DefaultRenderersFactory.applyMapDv7ToHevcIfSupported(enabled: Boolean): DefaultRenderersFactory {
    return runCatching {
        val method = javaClass.getMethod("setMapDV7ToHevc", Boolean::class.javaPrimitiveType)
        method.invoke(this, enabled)
        this
    }.getOrElse { this }
}

@Suppress("unused")
// Retained for reference only: Builder.setAudioCapabilities is discarded when the
// builder has a Context, so this never reaches the sink. The force-AC3 path lives
// in PlaybackSpeedAwareAudioSink.
private fun buildStableAudioCapabilities(context: Context, forceOpticalPassthrough: Boolean = false): AudioCapabilities {
    val detected = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
    val supportedEncodings = mutableListOf<Int>()
    val knownEncodings = intArrayOf(
        C.ENCODING_PCM_16BIT, C.ENCODING_AC3, C.ENCODING_AC4, C.ENCODING_DTS,
        C.ENCODING_E_AC3_JOC, C.ENCODING_E_AC3, C.ENCODING_DOLBY_TRUEHD
    )
    for (encoding in knownEncodings) {
        if (detected.supportsEncoding(encoding)) {
            supportedEncodings += encoding
        }
    }
    if ((detected.supportsEncoding(C.ENCODING_DTS_HD) || detected.supportsEncoding(C.ENCODING_DTS_UHD_P2)) && C.ENCODING_DTS !in supportedEncodings) {
        supportedEncodings += C.ENCODING_DTS
    }
    if (forceOpticalPassthrough) {
        val forced = intArrayOf(
            C.ENCODING_AC3,
            C.ENCODING_E_AC3,
            C.ENCODING_E_AC3_JOC,
            C.ENCODING_DTS,
            C.ENCODING_DTS_HD
        )
        for (encoding in forced) {
            if (encoding !in supportedEncodings) {
                supportedEncodings += encoding
            }
        }
    }
    val maxChannelCount = if (forceOpticalPassthrough) {
        maxOf(detected.maxChannelCount, 8)
    } else {
        detected.maxChannelCount
    }
    return AudioCapabilities(supportedEncodings.toIntArray(), maxChannelCount)
}

private class SafeBandwidthMeter(
    private val delegate: BandwidthMeter,
    private val isHls: Boolean
) : BandwidthMeter {
    override fun getBitrateEstimate(): Long {
        val raw = delegate.bitrateEstimate
        return if (isHls) maxOf(raw, 25_000_000L) else raw
    }

    override fun getTimeToFirstByteEstimateUs(): Long = delegate.timeToFirstByteEstimateUs

    override fun getTransferListener(): androidx.media3.datasource.TransferListener? = delegate.transferListener

    override fun addEventListener(
        eventHandler: android.os.Handler,
        eventListener: BandwidthMeter.EventListener
    ) {
        delegate.addEventListener(eventHandler, eventListener)
    }

    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) {
        delegate.removeEventListener(eventListener)
    }
}

/** Human-readable source codec from the selected track's mime. */
private fun describeAudioMime(mime: String?): String = when (mime) {
    androidx.media3.common.MimeTypes.AUDIO_TRUEHD -> "TrueHD"
    androidx.media3.common.MimeTypes.AUDIO_DTS -> "DTS"
    androidx.media3.common.MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    androidx.media3.common.MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
    androidx.media3.common.MimeTypes.AUDIO_AC3 -> "AC-3"
    androidx.media3.common.MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
    androidx.media3.common.MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3 JOC"
    androidx.media3.common.MimeTypes.AUDIO_AC4 -> "AC-4"
    androidx.media3.common.MimeTypes.AUDIO_AAC -> "AAC"
    androidx.media3.common.MimeTypes.AUDIO_FLAC -> "FLAC"
    androidx.media3.common.MimeTypes.AUDIO_OPUS -> "Opus"
    null -> "Audio"
    else -> mime.substringAfterLast('/').uppercase()
}

/** Sink output mode from the negotiated AudioTrack encoding. */
private fun describeAudioEncoding(encoding: Int): String = when (encoding) {
    C.ENCODING_AC3 -> "Passthrough (AC-3)"
    C.ENCODING_E_AC3 -> "Passthrough (E-AC-3)"
    C.ENCODING_E_AC3_JOC -> "Passthrough (E-AC-3 JOC)"
    C.ENCODING_DOLBY_TRUEHD -> "Passthrough (TrueHD)"
    C.ENCODING_DTS -> "Passthrough (DTS)"
    C.ENCODING_DTS_HD -> "Passthrough (DTS-HD)"
    C.ENCODING_AC4 -> "Passthrough (AC-4)"
    C.ENCODING_PCM_16BIT,
    C.ENCODING_PCM_16BIT_BIG_ENDIAN,
    C.ENCODING_PCM_24BIT,
    C.ENCODING_PCM_24BIT_BIG_ENDIAN,
    C.ENCODING_PCM_32BIT,
    C.ENCODING_PCM_32BIT_BIG_ENDIAN,
    C.ENCODING_PCM_8BIT,
    C.ENCODING_PCM_FLOAT -> "PCM decode"
    else -> "Encoding $encoding"
}

private fun PlayerRuntimeController.logSeekReady(via: String) {
    if (!seekReadyLogPending) return
    seekReadyLogPending = false
    Log.i(
        PlayerRuntimeController.TAG,
        "SEEK_READY: ms=${(SystemClock.elapsedRealtime() - lastSeekWallMs).coerceAtLeast(0L)} " +
            "targetMs=$seekReadyLogTargetMs via=$via"
    )
}

private fun PlayerRuntimeController.recordFirstFrameDiagnostics(
    player: ExoPlayer,
    currentDiagnostics: LastPlaybackDiagnostics,
    playerSettings: com.nuvio.tv.data.local.PlayerSettings
): LastPlaybackDiagnostics {
    val startupMs = (System.currentTimeMillis() - playerInitializationStartedAtMs).coerceAtLeast(0L)
    val conversionCalls = DoviBridge.getConversionCallCount()
    val conversionSucceeded = DoviBridge.getConversionSuccessCount()
    val signalingRewrites = DolbyVisionConversionStats.getCodecStringRewriteCount()
    val sourceProfile = DolbyVisionConversionStats.getLastSourceProfile()
        ?: parseDvProfileFromCodecString(currentVideoTrackCodecs)
    val conversionMode = DolbyVisionConversionStats.getLastSelectedConversionMode()
    val conversionAttempted = hasAttemptedDv7ToDv81ForCurrentPlayback || conversionCalls > 0 || signalingRewrites > 0
    if (pendingSeekTelemetryAwaitingFirstFrame && pendingSeekTelemetryRequestedAtMs > 0L) {
        pendingSeekTelemetryRequestedAtMs = 0L
        pendingSeekTelemetryTargetMs = -1L
        pendingSeekTelemetryReadyAtMs = 0L
        pendingSeekTelemetryReadyLatencyMs = -1L
        pendingSeekTelemetryAwaitingFirstFrame = false
    }

    val clickToFirstFrameMs = launchStartedAtElapsedMs
        ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
        ?: -1L
    val playbackSnapshot = playbackAnalyticsDiagnostics.snapshot(
        player = player,
        hasRenderedFirstFrame = true,
        rebufferCount = rebufferCount,
        rebufferTotalMs = rebufferTotalMs,
        rebufferStartedAtMs = rebufferStartedAtMs
    )
    val playbackStartupLine =
        "PLAYBACK_STARTUP: clickToFirstFrameMs=$clickToFirstFrameMs " +
            "initToFirstFrameMs=$startupMs playbackSpeed=${player.playbackParameters.speed} " +
            "pitch=${player.playbackParameters.pitch} startPositionMs=${player.currentPosition.coerceAtLeast(0L)} " +
            "currentPositionMs=${player.currentPosition.coerceAtLeast(0L)} bufferedMs=${player.bufferedPosition.coerceAtLeast(0L)} " +
            "durationMs=${player.duration.takeIf { it > 0L } ?: -1L} " +
            "video=${playbackSnapshot.videoFormat?.sampleMimeType ?: currentVideoTrackMimeType ?: "n/a"} " +
            "codecs=${playbackSnapshot.videoFormat?.codecs ?: currentVideoTrackCodecs ?: "n/a"} " +
            "size=${playbackSnapshot.videoFormat?.width ?: currentVideoTrackWidth}x${playbackSnapshot.videoFormat?.height ?: currentVideoTrackHeight} " +
            "frameRate=${playbackSnapshot.videoFormat?.frameRate ?: -1f} " +
            "bitrate=${playbackSnapshot.videoFormat?.bitrate ?: -1} " +
            "bandwidthBps=${playbackSnapshot.bandwidthEstimateBps ?: -1L} " +
            "loads=${playbackSnapshot.loadCompletedCount}/${playbackSnapshot.loadStartedCount} " +
            "bytesLoaded=${playbackSnapshot.totalBytesLoaded} droppedFrames=${playbackSnapshot.droppedFrames} " +
            "audioUnderruns=${playbackSnapshot.audioUnderrunCount} rebufferCount=$rebufferCount " +
            "host=${currentStreamUrl.safeHost()} engine=$currentInternalPlayerEngine"
    playbackAnalyticsDiagnostics.recordRawEventLine(playbackStartupLine)
    TtffTrace.mirror(playbackStartupLine)
    TtffTrace.mirror(
        "PLAYBACK_TUNNELING: requested=${playerSettings.effectiveTunnelingEnabled} applied=${player.isTunnelingEnabled}"
    )

    val dvConversionOccurred = conversionSucceeded > 0 ||
        signalingRewrites > 0 ||
        sourceProfile != null

    currentBitrateAwareLoadControl?.let { lc ->
        val budgetManaged = playerSettings.bufferBudgetManaged
        val keepZeroForDv7 = budgetManaged && conversionSucceeded > 0L &&
                MemoryBudget.isLowRamTier
        // Only the BUDGET can be tightened at runtime. The back buffer was fixed when
        // this player was constructed and media3 will not re-read it, so it is reported
        // as built rather than as intended.
        if (keepZeroForDv7) {
            lc.setBudgetBytesOverride(
                MemoryBudget.conversionBudgetMb.toLong() * 1024L * 1024L
            )
        }
        Log.i(
            PlayerRuntimeController.TAG,
            "BACK_BUFFER_RESOLVED: dvConversion=$dvConversionOccurred " +
                    "lowRam=${MemoryBudget.isLowRamTier} " +
                    "effectiveBackBufferMs=$effectiveBackBufferDurationMs " +
                    "managed=$budgetManaged " +
                    "budgetMb=${when {
                        keepZeroForDv7 -> MemoryBudget.conversionBudgetMb
                        budgetManaged -> MemoryBudget.budgetMb
                        else -> MemoryBudget.effectiveBufferMb(playerSettings.bufferSettings.targetBufferSizeMb)
                    }} " +
                    "host=${currentStreamUrl.safeHost()}"
        )
    }
    val finalDiagnostics = currentDiagnostics.copy(
        firstFrameMs = startupMs,
        // Persist the post-redirect serving host the NET_CONN
        // listener captured for this session, so Diagnostics can show it after
        // playback (the live holder is process-scoped and cleared at each new
        // stream). At first frame, bytes have demonstrably flowed through the
        // listener-carrying client, so the value is populated whenever the
        // source actually redirected.
        resolvedServingHost = PlaybackConnectionEvents.resolvedHost(),
        vodCacheState = mediaSourceFactory.vodCacheStateLabel(context),
        dv7DoviCalls = conversionCalls.toInt(),
        dv7DoviSuccess = conversionSucceeded.toInt(),
        dv7DoviSignalRewrites = signalingRewrites.toInt(),
        dvSourceProfile = sourceProfile?.toString(),
        dvElType = when (DolbyVisionConversionStats.getLastElType()) {
            DoviBridge.EL_TYPE_FEL -> "FEL"
            DoviBridge.EL_TYPE_MEL -> "MEL"
            DoviBridge.EL_TYPE_NONE, -1 -> "unknown"
            else -> null
        },
        dv7RpuDrops = DolbyVisionConversionStats.getRpuDropCount().toInt(),
        dvHdrMastering = DolbyVisionConversionStats.getLastRpuMetadata()?.toDiagnosticLine(),
        videoResolution = if (currentVideoTrackWidth > 0 && currentVideoTrackHeight > 0)
            "${currentVideoTrackWidth}x${currentVideoTrackHeight}" else null,
        videoCodec = friendlyVideoCodecName(currentVideoTrackMimeType, currentVideoTrackCodecs),
        videoHdrType = friendlyVideoHdrType(
            currentVideoTrackMimeType,
            currentVideoTrackColorTransfer,
            currentDiagnostics.dv7ModeEffective,
            dvConversionOccurred
        ),
        audioPath = currentAudioPathDescription
            ?: if (playbackSpeedAwareAudioSink?.isIecHbrActive() == true) "passthrough (IEC)" else null,
        audioCapabilities = AudioCapabilityReport.latest,
        videoBitrate = run {
            val durationMsVal = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            val sizeBytes = currentVideoSize
            if (sizeBytes != null && sizeBytes > 0L && durationMsVal > 0L) {
                val durationSecs = durationMsVal / 1000.0
                ((sizeBytes * 8.0) / durationSecs).toInt()
            } else {
                currentVideoTrackBitrate
            }
        },
        durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
        rebufferCount = rebufferCount,
        rebufferTotalMs = rebufferTotalMs,
        result = "Played"
    )
    lastPlaybackDiagnosticsForReport = finalDiagnostics
    scope.launch {
        runCatching {
            playerSettingsDataStore.setLastPlaybackDiagnostics(finalDiagnostics)
        }
    }
    return finalDiagnostics
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.refreshVideoBottomFraction() {
    val pv = exoPlayerView
    videoBottomFractionState.value = if (pv != null) pv.videoBoundsFraction(videoAspectRatio)?.bottom else null
}

private fun PlayerView.videoBoundsFraction(aspectRatio: Float): RectF? {
    val subtitleView = this.subtitleView ?: return null
    val viewWidth = subtitleView.width.toFloat()
    val viewHeight = subtitleView.height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f) return null

    if (aspectRatio > 0f) {
        val parentRatio = viewWidth / viewHeight
        return if (parentRatio > aspectRatio) {
            val fitW = viewHeight * aspectRatio
            val leftPx = (viewWidth - fitW) / 2f
            RectF(leftPx / viewWidth, 0f, (leftPx + fitW) / viewWidth, 1f)
        } else {
            val fitH = viewWidth / aspectRatio
            val topPx = (viewHeight - fitH) / 2f
            RectF(0f, topPx / viewHeight, 1f, (topPx + fitH) / viewHeight)
        }
    }

    val contentFrame = getTag(androidx.media3.ui.R.id.exo_content_frame) as? AspectRatioFrameLayout
        ?: findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            ?.also { setTag(androidx.media3.ui.R.id.exo_content_frame, it) }
        ?: return null
    val frameWidth = contentFrame.width.toFloat()
    val frameHeight = contentFrame.height.toFloat()
    if (frameWidth <= 0f || frameHeight <= 0f) return null
    if (frameWidth > viewWidth || frameHeight > viewHeight) return null
    val left = contentFrame.x / viewWidth
    val top = contentFrame.y / viewHeight
    return RectF(
        left,
        top,
        left + frameWidth / viewWidth,
        top + frameHeight / viewHeight,
    )
}

/** Reads an Android system property (debug.* switches settable with adb, no root); null if unavailable. */
private fun am9SystemProperty(name: String): String? = runCatching {
    Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java)
        .invoke(null, name) as? String
}.getOrNull()
