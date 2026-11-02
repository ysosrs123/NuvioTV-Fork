package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.util.TtffTrace
import android.net.Uri
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.SeekParameters
import com.nuvio.tv.R
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.core.tracking.scrobbleDiagnosticIdentity
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.NightModeLevel
import com.nuvio.tv.data.local.rememberedNightModeLevel
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.repository.PlaybackIssueErrorInput
import com.nuvio.tv.data.repository.PlaybackIssuePlaybackSettingsInput
import com.nuvio.tv.data.repository.PlaybackIssueReportInput
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.WatchProgress
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import com.nuvio.tv.core.player.PlaceholderStreamPolicy
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import kotlinx.coroutines.launch

internal const val AUDIO_AMPLIFICATION_MIN_DB = 0
internal const val AUDIO_AMPLIFICATION_MAX_DB = 10
internal const val DIALOGUE_BOOST_MIN_DB = 0
internal const val DIALOGUE_BOOST_MAX_DB = 12
internal const val AUDIO_DELAY_MIN_MS = com.nuvio.tv.domain.model.AUDIO_DELAY_MIN_MS
internal const val AUDIO_DELAY_MAX_MS = com.nuvio.tv.domain.model.AUDIO_DELAY_MAX_MS
internal const val AUDIO_DELAY_STEP_MS = 25
internal const val AUDIO_DELAY_HOLD_STEP_MS = 50
internal const val AUDIO_DELAY_HOLD_FAST_STEP_MS = 100
internal const val AUDIO_DELAY_HOLD_THRESHOLD_MS = 1000L
internal const val AUDIO_DELAY_HOLD_FAST_THRESHOLD_MS = 2000L
internal const val AUDIO_DELAY_HOLD_REPEAT_INTERVAL_MS = 100L
internal const val WATCH_PROGRESS_SAVE_INTERVAL_MS = 90_000L

/**
 * Audio delay split into its sources, so the stream hint and the user's tweak on that stream
 * never end up in the per-device delay. The player always gets [totalMs].
 */
internal data class AudioDelayParts(
    /** Per-device delay (stored per route when "remember per device" is on). */
    val deviceMs: Int = 0,
    /** `behaviorHints.audioDelayMs` of the current stream. */
    val streamMs: Int = 0,
    /** User adjustment made while a stream delay is active; never persisted, dropped on stream change. */
    val sessionAdjustMs: Int = 0,
    /**
     * `behaviorHints.audioDelayLanguage` of the current stream: when set, the stream delay only
     * applies to the tracks in this language (the ones coming from the addon's audio source).
     */
    val streamLanguage: String? = null,
    /** False while the selected audio track is not in [streamLanguage] (e.g. the video's original audio). */
    val streamAudioActive: Boolean = true
) {
    private val hasActiveStreamDelay: Boolean
        get() = streamMs != 0 && streamAudioActive

    val totalMs: Int
        get() {
            val streamPartMs = if (streamAudioActive) streamMs.toLong() + sessionAdjustMs else 0L
            return (deviceMs.toLong() + streamPartMs)
                .coerceIn(AUDIO_DELAY_MIN_MS.toLong(), AUDIO_DELAY_MAX_MS.toLong())
                .toInt()
        }

    /** Content offset of the stream's audio source (hint + user's tweak), without the device delay. */
    val streamSourceOffsetMs: Int
        get() = (streamMs.toLong() + sessionAdjustMs)
            .coerceIn(AUDIO_DELAY_MIN_MS.toLong(), AUDIO_DELAY_MAX_MS.toLong())
            .toInt()

    /** Value to save for the current route, or null while a stream delay is active. */
    val persistableDeviceMs: Int?
        get() = deviceMs.takeIf { !hasActiveStreamDelay }

    /** The user picked [totalMs] in the Audio panel. */
    fun withManualTotal(totalMs: Int): AudioDelayParts {
        val clampedTotalMs = totalMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
        return when {
            streamMs == 0 -> copy(deviceMs = clampedTotalMs, sessionAdjustMs = 0)
            // Original audio selected: the stream delay is not in play, so this is a device tweak.
            !streamAudioActive -> copy(deviceMs = clampedTotalMs)
            else -> copy(sessionAdjustMs = clampedTotalMs - deviceMs - streamMs)
        }
    }

    fun withStream(streamMs: Int, streamLanguage: String? = null): AudioDelayParts =
        copy(
            streamMs = streamMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS),
            sessionAdjustMs = 0,
            streamLanguage = streamLanguage?.takeIf { it.isNotBlank() },
            streamAudioActive = true
        )

    fun withStreamAudioActive(active: Boolean): AudioDelayParts =
        copy(streamAudioActive = active || streamLanguage == null)

    fun withDevice(deviceMs: Int): AudioDelayParts =
        copy(deviceMs = deviceMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS))
}

/** Decides which selected tracks belong to the stream's audio source (`behaviorHints.audioDelayLanguage`). */
internal object StreamDelayScope {
    /** Without a language every audio track gets the stream delay; unknown selection keeps it until tracks load. */
    fun appliesToAudio(streamLanguage: String?, selectedAudioLanguage: String?, hasSelectedAudio: Boolean): Boolean =
        streamLanguage == null ||
            !hasSelectedAudio ||
            PlayerSubtitleUtils.matchesLanguageCode(selectedAudioLanguage, streamLanguage)

    /** Only embedded tracks in the stream language are shifted; addon subtitles follow the video. */
    fun appliesToSubtitle(
        streamLanguage: String?,
        selectedInternalSubtitleLanguage: String?,
        hasSelectedInternalSubtitle: Boolean,
        addonSubtitleActive: Boolean
    ): Boolean =
        streamLanguage != null &&
            hasSelectedInternalSubtitle &&
            !addonSubtitleActive &&
            PlayerSubtitleUtils.matchesLanguageCode(selectedInternalSubtitleLanguage, streamLanguage)
}

/** Audio panel change: [delayMs] is the new total delay shown to the user. */
internal fun PlayerRuntimeController.applyAudioDelay(delayMs: Int) {
    audioDelayParts = audioDelayParts.withManualTotal(delayMs)
    applyEffectiveAudioDelay()
    audioDelayParts.persistableDeviceMs?.let { persistAudioDelayForCurrentRoute(it) }
}

/** Pushes the current total delay to the UI state and the active engine. */
internal fun PlayerRuntimeController.applyEffectiveAudioDelay() {
    val totalMs = audioDelayParts.totalMs
    audioDelayUs.set(totalMs.toLong() * 1000L)
    _exoPlayer?.getAssHandlerCompat()?.let { it.audioDelayUs = totalMs.toLong() * 1000L }
    _uiState.update { it.copy(audioDelayMs = totalMs) }
    if (isUsingMpvEngine()) {
        mpvView?.setAudioDelayMs(totalMs)
    }
    applyStreamSubtitleOffset()
}

/** A new stream was selected: swap in its delay hint and drop the tweak made on the previous one. */
internal fun PlayerRuntimeController.applyStreamAudioDelay(streamDelayMs: Int?, streamLanguage: String? = null) {
    audioDelayParts = audioDelayParts.withStream(streamDelayMs ?: 0, streamLanguage)
    syncStreamDelayWithSelectedTracks(forceApply = true)
}

/** Re-evaluates which selected tracks come from the stream's audio source and applies the delays. */
internal fun PlayerRuntimeController.syncStreamDelayWithSelectedTracks(forceApply: Boolean = false) {
    val state = _uiState.value
    val selectedAudio = state.audioTracks.getOrNull(state.selectedAudioTrackIndex)
    val audioActive = StreamDelayScope.appliesToAudio(
        streamLanguage = audioDelayParts.streamLanguage,
        selectedAudioLanguage = selectedAudio?.language,
        hasSelectedAudio = selectedAudio != null
    )
    val updatedParts = audioDelayParts.withStreamAudioActive(audioActive)
    if (forceApply || updatedParts != audioDelayParts) {
        audioDelayParts = updatedParts
        applyEffectiveAudioDelay()
    } else {
        applyStreamSubtitleOffset()
    }
}

/**
 * Embedded subtitles from the stream's audio source share its timeline, so they get the same
 * content offset. Kept apart from the user's subtitle delay, which is persisted per video.
 */
internal fun PlayerRuntimeController.applyStreamSubtitleOffset() {
    val state = _uiState.value
    val selectedSubtitle = state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)
    val applies = StreamDelayScope.appliesToSubtitle(
        streamLanguage = audioDelayParts.streamLanguage,
        selectedInternalSubtitleLanguage = selectedSubtitle?.language,
        hasSelectedInternalSubtitle = selectedSubtitle != null,
        addonSubtitleActive = state.selectedAddonSubtitle != null
    )
    val offsetUs = if (applies) audioDelayParts.streamSourceOffsetMs.toLong() * 1000L else 0L
    if (streamSubtitleDelayUs.getAndSet(offsetUs) == offsetUs) return
    // Update libass with combined user delay + stream offset.
    _exoPlayer?.getAssHandlerCompat()?.let {
        it.subtitleDelayUs = subtitleDelayUs.get() + offsetUs
    }
    if (isUsingMpvEngine()) {
        mpvView?.setSubtitleDelayMs(mpvSubtitleDelayMs(state.subtitleDelayMs))
    }
}

/** mpv has a single `sub-delay`: the user's subtitle delay plus the stream source offset. */
internal fun PlayerRuntimeController.mpvSubtitleDelayMs(userDelayMs: Int): Int =
    userDelayMs + (streamSubtitleDelayUs.get() / 1000L).toInt()

internal fun PlayerRuntimeController.skipActiveInterval(): Boolean {
    return skipInterval(_uiState.value.activeSkipInterval ?: return false)
}

internal fun PlayerRuntimeController.skipInterval(interval: SkipInterval): Boolean {
    if (interval.type == "post-credits") return false
    val duration = currentPlaybackDurationMs().takeIf { it > 0 } ?: Long.MAX_VALUE
    val postCredits = interval.followingPostCreditsScene(skipIntervals, currentPlaybackDurationMs())
    val targetTime = postCredits?.startTime ?: interval.endTime
    val seekMs = if (targetTime == Double.MAX_VALUE) {
        duration
    } else {
        (targetTime * 1000).toLong()
    }
    val seekParameters = if (postCredits != null || interval.type == "movie-credits") {
        SeekParameters.EXACT
    } else SeekParameters.NEXT_SYNC
    val requested = seekMs.coerceAtMost(duration)
    val target = requested
    seekPlaybackTo(target, seekParameters)
    scheduleProgressSyncAfterSeek()
    _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = true) }
    return true
}

internal fun PlayerRuntimeController.applyAudioAmplification(db: Int) {
    val clampedDb = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    // Gain is a PCM processor: during bitstream bypass it is
    // a silent no-op, so the control reports unavailable instead of offering a
    // dead slider. MPV always decodes, so bypass only applies on ExoPlayer.
    val isAudioAmplificationAvailable =
        isUsingMpvEngine() || (_exoPlayer != null && !isAudioOutputBypassing)
    val wasActive = gainAudioProcessor.isProcessingEnabled()
    gainAudioProcessor.setGainDb(if (isAudioAmplificationAvailable) clampedDb else AUDIO_AMPLIFICATION_MIN_DB)
    onSoundProcessingChanged(wasActive)

    if (isUsingMpvEngine()) {
        mpvView?.applyAudioAmplificationDb(clampedDb, _uiState.value.nightMode)
    }
    _uiState.update {
        it.copy(
            audioAmplificationDb = clampedDb,
            isAudioAmplificationAvailable = isAudioAmplificationAvailable
        )
    }
}

internal fun PlayerRuntimeController.applyNightMode(level: NightModeLevel) {
    val wasActive = gainAudioProcessor.isProcessingEnabled()
    gainAudioProcessor.setNightMode(level)
    onSoundProcessingChanged(wasActive)
    _uiState.update {
        it.copy(nightMode = level, nightModeLastOn = rememberedNightModeLevel(it.nightMode, level, it.nightModeLastOn))
    }
    if (isUsingMpvEngine()) {
        mpvView?.applyAudioAmplificationDb(_uiState.value.audioAmplificationDb, level)
    }
}

/**
 * Adding the processor needs the sink to rebuild its chain. Turning everything off does
 * not: the processor eases back to unity and drops out at the next flush, so the level
 * does not jump mid-scene. During passthrough nothing is rebuilt; the next PCM
 * configuration picks the setting up.
 */
private fun PlayerRuntimeController.onSoundProcessingChanged(wasActive: Boolean) {
    if (wasActive || !gainAudioProcessor.isProcessingEnabled() || isUsingMpvEngine() || isAudioOutputBypassing) return
    playbackSpeedAwareAudioSink?.notifyAudioProcessingRequirementChanged()
    _exoPlayer?.let { player ->
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().build()
    }
}

internal fun PlayerRuntimeController.applyCenterMixLevel(db: Int) {
    val clampedDb = db.coerceIn(DIALOGUE_BOOST_MIN_DB, DIALOGUE_BOOST_MAX_DB)
    ffmpegAudioRenderer?.setCenterMixLevelDb(clampedDb)
    val wasActive = gainAudioProcessor.isProcessingEnabled()
    gainAudioProcessor.setCenterGainDb(clampedDb)
    onSoundProcessingChanged(wasActive)
    if (isUsingMpvEngine()) {
        mpvView?.applyDialogueBoostDb(clampedDb)
    }
    _uiState.update { state ->
        state.copy(centerMixLevelDb = clampedDb)
    }
}

internal fun PlayerRuntimeController.updateAudioControlAvailability(
    audioTracks: List<TrackInfo> = _uiState.value.audioTracks,
    selectedAudioIndex: Int = _uiState.value.selectedAudioTrackIndex
) {
    val selectedTrack = audioTracks.getOrNull(selectedAudioIndex)
    // See applyAudioAmplification.
    val isAudioAmplificationAvailable =
        isUsingMpvEngine() || (_exoPlayer != null && !isAudioOutputBypassing)
    val dialogueBoostState = dialogueBoostState(
        isMpv = isUsingMpvEngine(),
        isBypassing = _exoPlayer != null && isAudioOutputBypassing,
        trackChannels = selectedTrack?.channelCount ?: 0,
        ffmpegDownmixActive = ffmpegAudioRenderer?.isCenterMixActive() == true,
        pcmChannels = gainAudioProcessor.pcmChannelCount,
        mpvOutputChannels = if (isUsingMpvEngine()) mpvView?.audioOutputChannelCount() ?: 0 else 0
    )
    val isCenterMixAvailable = dialogueBoostState == DialogueBoostState.AVAILABLE
    val clampedDb = _uiState.value.audioAmplificationDb
        .coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    gainAudioProcessor.setGainDb(
        if (isAudioAmplificationAvailable) clampedDb else AUDIO_AMPLIFICATION_MIN_DB
    )
    _uiState.update { state ->
        state.copy(
            isAudioAmplificationAvailable = isAudioAmplificationAvailable,
            isCenterMixAvailable = isCenterMixAvailable,
            dialogueBoostState = dialogueBoostState
        )
    }
}

internal fun PlayerRuntimeController.resetPostPlayStateAfterPlaybackEnded() {
    if (!shouldResetPostPlayStateAfterPlaybackEnded(
            state = _uiState.value,
            hasInFlightNextEpisodeAutoPlay = nextEpisodeAutoPlayJob?.isActive == true
        )
    ) {
        return
    }

    // If auto-play is enabled and the user dismissed the card earlier,
    // still auto-play the next episode when playback ends naturally.
    val state = _uiState.value
    val autoPlayEligible = streamAutoPlayNextEpisodeEnabledSetting &&
        state.nextEpisode?.hasAired == true &&
        nextEpisodeVideo != null
    if (autoPlayEligible && (state.postPlayDismissedForCurrentEpisode || state.postPlayMode == null)) {
        playNextEpisode()
        return
    }

    resetPostPlayOverlayState(clearEpisode = false)
}

internal fun shouldResetPostPlayStateAfterPlaybackEnded(
    state: PlayerUiState,
    hasInFlightNextEpisodeAutoPlay: Boolean
): Boolean {
    if (state.postPlayMode?.blocksNaturalCompletion() == true) return false
    if (hasInFlightNextEpisodeAutoPlay) return false
    return true
}

/**
 * Whether an ENDED / near-end event should count as a real episode finish.
 *
 * Debrid cache-sync placeholders and unplayable source responses (e.g. RAR-only
 * torrents, "service unavailable" error clips) often report a short duration and
 * reach STATE_ENDED. Treating those as natural completion marks the episode watched
 * and chains auto-play next through an entire season. Mirror the external-player
 * guard in [com.nuvio.tv.core.player.ExternalPlaybackTracker].
 */
internal fun shouldTreatAsNaturalPlaybackCompletion(
    hasRenderedFirstFrame: Boolean,
    hasFatalError: Boolean,
    durationMs: Long
): Boolean {
    if (hasFatalError) return false
    if (!hasRenderedFirstFrame) return false
    if (isShortPlaceholderDuration(durationMs)) return false
    return true
}

/**
 * This 2:01 threshold is intentionally NOT aligned with
 * [com.nuvio.tv.core.player.PlaceholderStreamPolicy.MIN_PLAUSIBLE_DURATION_MS] (3:00).
 * This guard is duration-only and suppresses watch-state side-effects (progress,
 * mark-watched, next-episode) for junk clips. Raising it to 3:00 would wrongly
 * suppress those for legitimately short real content; the policy avoids that only
 * because its 3:00 threshold is ANDed with a <33%-of-runtime ratio this guard has
 * no runtime to apply. The two serve different jobs and must stay separate.
 */
/** Streams shorter than ~2:01 are treated as error/placeholder clips, not real episodes. */
internal fun isShortPlaceholderDuration(duration: Long): Boolean = duration in 1..120_999L

internal fun PlayerRuntimeController.startProgressUpdates() {
    progressJob?.cancel()
    progressJob = scope.launch {
        while (isActive) {
            if (isUsingMpvEngine()) {
                val view = mpvView
                if (view != null) {
                    val pos = view.currentPositionMs().coerceAtLeast(0L)
                    val playerDuration = view.durationMs().coerceAtLeast(0L)
                    applyPendingMpvSeekIfNeeded(
                        view = view,
                        currentPositionMs = pos,
                        durationMs = playerDuration
                    )
                    val playingNow = view.isPlayingNow()
                    subtitleCueTracker.updateText(view.currentSubtitleText())
                    val cacheBuffering = view.isPausedForCacheNow() || view.isCoreIdleNow()
                    var firstFrameReady = hasRenderedFirstFrame
                        if (!firstFrameReady) {
                            firstFrameReady = view.isPositionFromRequestedMedia() &&
                                (pos > 0L || (playingNow && !cacheBuffering && playerDuration > 0L))
                            if (firstFrameReady) {
                                hasRenderedFirstFrame = true
                                playerSession.firstFrame()
                                resetMpvStartupWatchdog()
                                scheduleMpvStableProgressReset()
                                val clickToFirstFrameMs = launchStartedAtElapsedMs
                                    ?.let { (android.os.SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
                                    ?: -1L
                                val initToFirstFrameMs = (System.currentTimeMillis() - playerInitializationStartedAtMs)
                                    .coerceAtLeast(0L)
                                val mpvStartupLine =
                                    "PLAYBACK_STARTUP: clickToFirstFrameMs=$clickToFirstFrameMs " +
                                        "initToFirstFrameMs=$initToFirstFrameMs playbackSpeed=${_uiState.value.playbackSpeed} " +
                                        "currentPositionMs=$pos durationMs=$playerDuration engine=MPV " +
                                        "host=${currentStreamUrl.safePlaybackEventsHost()}"
                                playbackAnalyticsDiagnostics.recordRawEventLine(mpvStartupLine)
                                TtffTrace.mirror(mpvStartupLine)
                                finishLoadingDiagnostics("mpv_first_frame_ready")
                                if (_uiState.value.postPlayDismissedForCurrentEpisode) {
                                    _uiState.update { it.copy(postPlayDismissedForCurrentEpisode = false) }
                                }
                            }
                        }
                    maybeRunMpvStartupWatchdog(view)
                    if (playerDuration > lastKnownDuration) {
                        lastKnownDuration = playerDuration
                    }
                    val displayPosition = pendingPreviewSeekPosition ?: pos
                    val playingForWatchClock = playingNow && !cacheBuffering
                    mpvDurationGrowth = LivePlaybackUiPolicy.nextDurationGrowth(
                        previous = mpvDurationGrowth,
                        mediaKey = currentStreamUrl,
                        durationMs = playerDuration,
                        fromRequestedMedia = view.isPositionFromRequestedMedia()
                    )
                    publishPlaybackTimeline(
                        currentPosition = displayPosition,
                        playbackPosition = pos,
                        duration = playerDuration,
                        bufferedPosition = (pos + (view.demuxerCacheDurationSec() * 1000.0).toLong())
                            .coerceAtLeast(pos),
                        playerReportsLive = view.isLiveStreamNow() || mpvDurationGrowth.grew,
                        isPlaying = playingForWatchClock
                    )
                    // Prefer the largest known duration; MPV can report a shorter one transiently.
                    // The playerDuration check stays: lastKnownDuration can still hold the previous
                    // stream's value until it resets.
                    val effectiveDuration = maxOf(playerDuration, lastKnownDuration)
                    val nearEnd = endDetectionArmed && playerDuration > 0L &&
                        pos >= (effectiveDuration - PlayerNextEpisodeRules.NEAR_END_MS)
                    val eofNow = view.isEofReached()
                    if (!eofNow) mpvEofSeenClear = true
                    val mpvEofReached = mpvEofSeenClear && eofNow
                    val naturalEnded = !view.isLiveStreamNow() && (nearEnd || mpvEofReached) && shouldTreatAsNaturalPlaybackCompletion(
                        hasRenderedFirstFrame = firstFrameReady,
                        hasFatalError = !_uiState.value.error.isNullOrBlank(),
                        durationMs = effectiveDuration
                    ) && !isPlaceholderPlayback()
                    val wasEnded = _uiState.value.playbackEnded
                    _uiState.update { state ->
                        state.copy(
                            isPlaying = playingNow,
                            isBuffering = if (naturalEnded) false else (!firstFrameReady || cacheBuffering),
                            showLoadingOverlay = if (state.loadingOverlayEnabled) !firstFrameReady else false,
                            // Snap the loading-logo fill to 100% once playback is
                            // ready so the logo finishes filling on dismissal.
                            loadingProgress = if (firstFrameReady && state.loadingProgress != null) 1f else state.loadingProgress,
                            playbackEnded = naturalEnded
                        )
                    }
                    // libmpv has no Media3 listener callbacks. Keep Android's active media
                    // session in sync with the state sampled by this existing progress tick.
                    refreshMpvMediaSession()
                    updateMpvTracksOnTick()
                    recomputeChapterSkipIntervalsIfNeeded()
                    updateActiveSkipInterval(pos)
                    if (!_playbackTimeline.value.isLive) {
                        evaluatePostPlayOverlayVisibility(
                            positionMs = pos,
                            durationMs = playerDuration
                        )
                    }
                    if (!naturalEnded && firstFrameReady && mpvEofReached && !view.isLiveStreamNow() &&
                        isPlaceholderPlayback()
                    ) {
                        offerSourcesAfterPlaceholderEnded()
                    }
                    if (naturalEnded && !wasEnded) {
                        // Short placeholders never set naturalEnded, so they cannot mark
                        // watched or auto-advance (see #2819).
                        handleNaturalPlaybackEnded()
                    }
                }
                reportServerPlayback()
                delay(500)
                continue
            }

            _exoPlayer?.let { player ->
                val pos = player.currentPosition.coerceAtLeast(0L)
                val playerDuration = player.duration
                if (playerDuration > lastKnownDuration) {
                    lastKnownDuration = playerDuration
                }
                // Placeholder duration backstop. Content-length was judged at READY;
                // here the decoded duration is trustworthy. Guarded on a blank error so
                // it fires once -- the reject sets error, and every later tick short-circuits.
                if (hasRenderedFirstFrame && _uiState.value.error.isNullOrBlank()) {
                    val placeholderDurationVerdict = PlaceholderStreamPolicy.evaluate(
                        contentLengthBytes = null,
                        durationMs = getEffectiveDuration(pos),
                        expectedRuntimeMs = expectedRuntimeMinutes?.let { it * 60_000L }
                    )
                    if (placeholderDurationVerdict is PlaceholderStreamPolicy.Verdict.Reject &&
                        placeholderDurationVerdict.reason == PlaceholderStreamPolicy.Reason.ImplausibleDuration
                    ) {
                        rejectPlaceholderStream(placeholderDurationVerdict)
                    }
                }

                val displayPosition = previewDisplayPosition() ?: pos
                publishPlaybackTimeline(
                    currentPosition = displayPosition,
                    playbackPosition = pos,
                    duration = playerDuration.coerceAtLeast(0L),
                    bufferedPosition = player.bufferedPosition.coerceAtLeast(pos),
                    playerReportsLive = player.isCurrentMediaItemLive,
                    isPlaying = player.isPlaying
                )
                playbackAnalyticsDiagnostics.recordProgressSnapshot(
                    player = player,
                    hasRenderedFirstFrame = hasRenderedFirstFrame,
                    rebufferCount = rebufferCount,
                    rebufferTotalMs = rebufferTotalMs
                )
                // Update torrent rebuffer progress from ExoPlayer's buffer state
                if (isTorrentStream && _uiState.value.isBuffering && hasRenderedFirstFrame) {
                    val bufferedAheadMs = (player.bufferedPosition - pos).coerceAtLeast(0)
                    val bufferedSec = bufferedAheadMs / 1000f
                    val statsHidden = _uiState.value.hideTorrentStats
                    val message = if (statsHidden) {
                        null
                    } else {
                        val speed = formatTorrentSpeed(context, _uiState.value.torrentDownloadSpeed)
                        val peerInfo = context.getString(
                            R.string.player_torrent_peer_info,
                            _uiState.value.torrentSeeds,
                            _uiState.value.torrentPeers
                        )
                        val bufLabel = String.format("%.0fs", bufferedSec)
                        context.getString(
                            R.string.player_torrent_buffered_status,
                            bufLabel,
                            peerInfo,
                            speed
                        )
                    }
                    val progress = (bufferedSec / 10f).coerceIn(0f, 1f)
                    _uiState.update {
                        it.copy(
                            torrentBufferingMessage = message,
                            torrentBufferingProgress = progress
                        )
                    }
                }
                sampleAutoSpeedup(player, pos)
                recomputeChapterSkipIntervalsIfNeeded()
                updateActiveSkipInterval(pos)
                if (!_playbackTimeline.value.isLive) {
                    evaluatePostPlayOverlayVisibility(
                        positionMs = pos,
                        durationMs = playerDuration.coerceAtLeast(0L)
                    )
                }

                if (player.isPlaying) {
                    val now = System.currentTimeMillis()
                    if (now - lastBufferLogTimeMs >= 10_000) {
                        lastBufferLogTimeMs = now
                        val bufAhead = (player.bufferedPosition - player.currentPosition) / 1000
                        val loading = player.isLoading
                        val runtime = Runtime.getRuntime()
                        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                        val maxMb = runtime.maxMemory() / (1024 * 1024)
                        val budgetMb = (_loadControl as? com.nuvio.tv.core.player.BufferBudgetControl)
                            ?.allocatorTargetBytes()?.div(1024 * 1024)
                        val bufferLine = "BUFFER: ahead=${bufAhead}s, loading=$loading, heap=$usedMb/${maxMb}MB, pos=${pos / 1000}s" +
                            (budgetMb?.let { ", budgetMb=$it" } ?: "")
                        scope.launch(Dispatchers.IO) {
                            Log.i(PlayerRuntimeController.TAG, bufferLine + PlaybackRuntimeCounters.logFields())
                            com.nuvio.tv.core.player.AllocationRecorder.sample(context.applicationContext, pos / 1000)
                        }
                        bufferMemoryKeeper?.onTick()
                        
                        if (NuvioExoPlayerPerformanceHelper.shouldLogMemoryFootprint()) {
                            val defaultAllocator = _loadControl?.allocator as? androidx.media3.exoplayer.upstream.DefaultAllocator
                            val totalFootprintBytes = defaultAllocator?.let { allocator ->
                                runCatching { allocator.memoryFootprint.toLong() }.getOrDefault(0L)
                            } ?: 0L
                            val totalActiveBytes = defaultAllocator?.totalBytesAllocated?.toLong() ?: 0L
                            val footprintMb = totalFootprintBytes / (1024 * 1024)
                            val activeMb = totalActiveBytes / (1024 * 1024)
                            Log.d("ExoMemory", "Off-heap OS ahead: $footprintMb MB, active: $activeMb MB")
                        }
                    }
                }
            }
            reportServerPlayback()
            delay(500)
        }
    }
}

internal fun PlayerRuntimeController.stopProgressUpdates() {
    progressJob?.cancel()
    progressJob = null
}

internal fun PlayerRuntimeController.startWatchProgressSaving() {
    watchProgressSaveJob?.cancel()
    watchProgressSaveJob = scope.launch {
        while (isActive) {
            delay(WATCH_PROGRESS_SAVE_INTERVAL_MS)
            saveWatchProgressIfNeeded()
        }
    }
}

internal fun PlayerRuntimeController.stopWatchProgressSaving() {
    watchProgressSaveJob?.cancel()
    watchProgressSaveJob = null
}

internal fun PlayerRuntimeController.submitPlaybackIssueReport() {
    val state = _uiState.value
    if (!state.playbackIssueReportsEnabled) return
    if (state.playbackIssueReportStatus == PlaybackIssueReportStatus.Sending ||
        state.playbackIssueReportStatus == PlaybackIssueReportStatus.Sent
    ) return
    val timeline = _playbackTimeline.value
    val diagnostics = lastPlaybackDiagnosticsForReport.takeIf { it.timestampMs > 0L }
        ?: LastPlaybackDiagnostics(
            timestampMs = System.currentTimeMillis(),
            host = currentStreamUrl.reportSafeHost(),
            result = state.error?.let { "Error: $it" } ?: "Pending"
        )
    val reportError = lastPlaybackIssueError
        ?: PlaybackIssueErrorInput(
            displayMessage = state.error,
            errorCode = null,
            errorCodeName = null,
            exceptionClass = null,
            causeClass = null,
            causeMessage = null,
            httpStatus = null
        )
    val audioTrack = state.audioTracks.reportTrackLabel(state.selectedAudioTrackIndex)
    val subtitleTrack = state.subtitleTracks.reportTrackLabel(state.selectedSubtitleTrackIndex)
    val reportReason = PlayerStartupLoadingPolicy.loadingStallReportReason(
        showLoadingOverlay = state.showLoadingOverlay,
        hasRenderedFirstFrame = hasRenderedFirstFrame,
        error = state.error,
    )
    val loadingInput = buildPlaybackIssueLoadingInput(reportReason)
    val playbackAnalyticsInput = playbackAnalyticsDiagnostics.snapshot(
        player = _exoPlayer,
        hasRenderedFirstFrame = hasRenderedFirstFrame,
        rebufferCount = rebufferCount,
        rebufferTotalMs = rebufferTotalMs,
        rebufferStartedAtMs = rebufferStartedAtMs
    ).let { snapshot ->
        snapshot.copy(
            startupStages = loadingInput.events,
            rawEventLines = snapshot.rawEventLines + listOf(
                "audio_passthrough_state surroundMode=${currentPlayerSettingsForReport.surroundFormatMode.name} " +
                    "tvArcSoundbar=${currentPlayerSettingsForReport.surroundTvArcSoundbar} " +
                    "iecActive=${playbackSpeedAwareAudioSink?.isIecHbrActive()} " +
                    "forceOptical=${currentPlayerSettingsForReport.forceOpticalPassthrough} " +
                    "tunnelingEffective=${state.tunnelingEnabled}"
            )
        )
    }
    val input = PlaybackIssueReportInput(
        diagnostics = diagnostics,
        error = reportError,
        title = title,
        contentName = contentName,
        contentId = contentId,
        contentType = contentType,
        videoId = currentVideoId,
        season = currentSeason,
        episode = currentEpisode,
        episodeTitle = currentEpisodeTitle,
        releaseYear = year,
        streamUrl = currentStreamUrl,
        streamMimeType = currentStreamMimeType,
        streamName = state.currentStreamName,
        addonName = currentAddonName,
        videoHash = currentVideoHash,
        videoSize = currentVideoSize,
        requestHeaders = currentHeaders,
        responseHeaders = currentStreamResponseHeaders,
        playerEngine = currentInternalPlayerEngine.name,
        loading = loadingInput,
        positionMs = timeline.currentPosition.takeIf { it > 0L },
        durationMs = timeline.duration.takeIf { it > 0L },
        bufferedPositionMs = timeline.bufferedPosition.takeIf { it > 0L },
        selectedAudioTrack = audioTrack,
        selectedSubtitleTrack = subtitleTrack,
        isTorrentStream = isTorrentStream,
        playbackSettings = buildPlaybackIssuePlaybackSettingsInput(),
        playbackAnalytics = playbackAnalyticsInput
    )

    val requestVersion = playbackIssueReportRequestVersion.incrementAndGet()
    _uiState.update {
        it.copy(
            playbackIssueReportStatus = PlaybackIssueReportStatus.Sending,
            playbackIssueReportId = null,
            playbackIssueReportError = null
        )
    }
    scope.launch {
        val result = playbackIssueReportRepository.submit(input)
        _uiState.update { current ->
            if (playbackIssueReportRequestVersion.get() != requestVersion ||
                current.playbackIssueReportStatus != PlaybackIssueReportStatus.Sending
            ) {
                current
            } else {
                result.fold(
                    onSuccess = { reportId ->
                        current.copy(
                            playbackIssueReportStatus = PlaybackIssueReportStatus.Sent,
                            playbackIssueReportId = reportId,
                            playbackIssueReportError = null
                        )
                    },
                    onFailure = { error ->
                        current.copy(
                            playbackIssueReportStatus = PlaybackIssueReportStatus.Failed,
                            playbackIssueReportId = null,
                            playbackIssueReportError = error.message ?: "Unable to send report"
                        )
                    }
                )
            }
        }
    }
}

private fun PlayerRuntimeController.buildPlaybackIssuePlaybackSettingsInput(): PlaybackIssuePlaybackSettingsInput {
    val settings = currentPlayerSettingsForReport
    val state = _uiState.value
    val effectiveDecoderPriority = cachedDecoderPriority
    return PlaybackIssuePlaybackSettingsInput(
        playerPreference = settings.playerPreference.name,
        internalPlayerEngine = settings.internalPlayerEngine.name,
        resolvedInternalPlayerEngine = currentInternalPlayerEngine.name,
        autoSwitchInternalPlayerOnError = settings.autoSwitchInternalPlayerOnError,
        decoderPriority = settings.decoderPriority,
        decoderPriorityName = decoderPriorityReportName(settings.decoderPriority),
        effectiveDecoderPriority = effectiveDecoderPriority,
        effectiveDecoderPriorityName = decoderPriorityReportName(effectiveDecoderPriority),
        downmixEnabled = settings.downmixEnabled,
        audioOutputChannels = settings.audioOutputChannels.settingValue,
        maintainOriginalAudioOnDownmix = settings.maintainOriginalAudioOnDownmix,
        tunnelingEnabled = settings.tunnelingEnabled,
        tunnelingEffective = state.tunnelingEnabled,
        forceOpticalPassthrough = settings.forceOpticalPassthrough,
        skipSilence = settings.skipSilence,
        audioAmplificationDb = settings.audioAmplificationDb,
        centerMixLevelDb = settings.centerMixLevelDb,
        persistAudioAmplification = settings.persistAudioAmplification,
        rememberAudioDelayPerDevice = settings.rememberAudioDelayPerDevice,
        preferredAudioLanguage = settings.preferredAudioLanguage,
        secondaryPreferredAudioLanguage = settings.secondaryPreferredAudioLanguage,
        preferredSubtitleLanguage = settings.subtitleStyle.preferredLanguage,
        secondaryPreferredSubtitleLanguage = settings.subtitleStyle.secondaryPreferredLanguage,
        tertiaryPreferredSubtitleLanguage = settings.subtitleStyle.tertiaryPreferredLanguage,
        useForcedSubtitles = settings.subtitleStyle.useForcedSubtitles,
        showOnlyPreferredSubtitleLanguages = settings.subtitleStyle.showOnlyPreferredLanguages,
        useLibass = settings.useLibass,
        activePlayerUsesLibass = requestedUseLibassByUser && !isUsingMpvEngine(),
        libassRenderType = settings.libassRenderType.name,
        addonSubtitleStartupMode = "SIDECAR",
        externalPlayerForwardSubtitles = settings.externalPlayerForwardSubtitles,
        subtitleOrganizationMode = settings.subtitleOrganizationMode.name,
        loadingOverlayEnabled = settings.loadingOverlayEnabled,
        showPlayerLoadingStatus = settings.showPlayerLoadingStatus,
        playbackIssueReportsEnabled = settings.playbackIssueReportsEnabled,
        dv5ToDv81Enabled = settings.dv5ToDv81Enabled,
        dv7HandlingMode = settings.dv7HandlingMode.name,
        dv7LibdoviModeOverride = settings.dv7LibdoviModeOverride,
        stripHdr10PlusSei = settings.stripHdr10PlusSei,
        mpvHardwareDecodeMode = settings.mpvHardwareDecodeMode.name,
        frameRateMatchingMode = settings.frameRateMatchingMode.name,
        resolutionMatchingEnabled = settings.resolutionMatchingEnabled,
        resizeMode = settings.resizeMode,
        aspectMode = state.aspectMode.name,
        bufferEngineEnabled = settings.bufferEngineEnabled,
        minBufferMs = settings.bufferSettings.minBufferMs,
        maxBufferMs = settings.bufferSettings.maxBufferMs,
        bufferForPlaybackMs = settings.bufferSettings.bufferForPlaybackMs,
        bufferForPlaybackAfterRebufferMs = settings.bufferSettings.bufferForPlaybackAfterRebufferMs,
        targetBufferSizeMb = settings.bufferSettings.targetBufferSizeMb,
        backBufferDurationMs = settings.bufferSettings.backBufferDurationMs,
        effectiveBackBufferDurationMs = effectiveBackBufferDurationMs,
        // Report what the engine actually runs, not the stored setting. Every
        // LoadControl branch constructs with retainBackBufferFromKeyframe = true; the
        // stored flag is not wired to the engine.
        retainBackBufferFromKeyframe = PlayerRuntimeController.ENGINE_RETAIN_BACK_BUFFER_FROM_KEYFRAME,
        parallelNetworkEnabled = settings.parallelNetworkEnabled,
        bufferBudgetManaged = settings.bufferBudgetManaged,
        allowLargeTargetBuffer = settings.allowLargeTargetBuffer,
        vodCacheEnabled = settings.vodCacheEnabled,
        vodCacheSizeMode = settings.vodCacheSizeMode.name,
        vodCacheSizeMb = settings.vodCacheSizeMb,
        useParallelConnections = settings.useParallelConnections,
        parallelConnectionCount = settings.parallelConnectionCount,
        parallelChunkSizeKb = com.nuvio.tv.ui.screens.settings.MemoryBudget.customChunkKb(context, settings),
        enableHttp2 = false,
        nuvioPerformanceModeEnabled = settings.nuvioPerformanceModeEnabled,
        streamAutoPlayMode = settings.streamAutoPlayMode.name,
        streamAutoPlaySource = settings.streamAutoPlaySource.name,
        streamAutoPlayNextEpisodeEnabled = settings.streamAutoPlayNextEpisodeEnabled,
        streamAutoPlayPreferBingeGroupForNextEpisode = settings.streamAutoPlayPreferBingeGroupForNextEpisode,
        streamAutoPlayReuseBingeGroup = settings.streamAutoPlayReuseBingeGroup,
        streamAutoPlayTimeoutSeconds = settings.streamAutoPlayTimeoutSeconds,
        stillWatchingEnabled = settings.stillWatchingEnabled,
        stillWatchingEpisodeThreshold = settings.stillWatchingEpisodeThreshold,
        nextEpisodeThresholdMode = settings.nextEpisodeThresholdMode.name,
        nextEpisodeThresholdPercent = settings.nextEpisodeThresholdPercent,
        nextEpisodeThresholdMinutesBeforeEnd = settings.nextEpisodeThresholdMinutesBeforeEnd,
        streamReuseLastLinkEnabled = settings.streamReuseLastLinkEnabled,
        streamReuseLastLinkCacheHours = settings.streamReuseLastLinkCacheHours
    )
}

private fun decoderPriorityReportName(priority: Int): String =
    when (priority) {
        0 -> "DEVICE_ONLY"
        2 -> "PREFER_APP"
        else -> "PREFER_DEVICE"
    }

private fun List<TrackInfo>.reportTrackLabel(selectedIndex: Int): String? {
    val track = firstOrNull { it.index == selectedIndex } ?: getOrNull(selectedIndex) ?: return null
    return buildString {
        append(track.name)
        track.language?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
        track.codec?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
        track.channelCount?.let { append(" | ").append(it).append("ch") }
    }
}

private fun String.reportSafeHost(): String {
    return runCatching { Uri.parse(this).host ?: "unknown" }.getOrDefault("unknown")
}

internal fun PlayerRuntimeController.saveWatchProgressIfNeeded() {
    if (!hasRenderedFirstFrame) return
    // A live stream's position is no point to come back to: resuming there seeks past what the
    // stream holds and nothing plays
    if (_playbackTimeline.value.isLive) return
    val currentPosition = currentPlaybackPositionMs() ?: return
    val duration = getEffectiveDuration(currentPosition)
    // Don't save progress for very short streams (< 2:01) — these are
    // typically error/warning messages or "stream not ready" placeholders that
    // would incorrectly mark content as watched when the user exits.
    if (isShortPlaceholderDuration(duration) || isPlaceholderPlayback()) return

    if (kotlin.math.abs(currentPosition - lastSavedPosition) >= saveThresholdMs) {
        lastSavedPosition = currentPosition
        saveWatchProgressInternal(currentPosition, duration, syncRemote = false)
    }
}

internal fun PlayerRuntimeController.saveWatchProgress() {
    if (!hasRenderedFirstFrame) return
    if (_playbackTimeline.value.isLive) return
    val currentPosition = currentPlaybackPositionMs() ?: return
    val duration = getEffectiveDuration(currentPosition)
    if (isShortPlaceholderDuration(duration) || isPlaceholderPlayback()) return
    saveWatchProgressInternal(currentPosition, duration)
}

internal fun PlayerRuntimeController.getEffectiveDuration(position: Long): Long {
    val playerDuration = currentPlaybackDurationMs()
    val effectiveDuration = maxOf(playerDuration, lastKnownDuration)
    if (effectiveDuration <= 0L) return 0L

    val isEnded = if (isUsingMpvEngine()) {
        position >= (effectiveDuration - 500L)
    } else {
        _exoPlayer?.playbackState == Player.STATE_ENDED
    }
    if (!isEnded && effectiveDuration < position) return 0L

    return effectiveDuration
}

internal fun PlayerRuntimeController.isPlaceholderPlayback(): Boolean {
    if (isPlaceholderPlayback(
            placeholderRejected = placeholderRejectedForCurrentStream,
            filmOrEpisode = isFilmOrEpisode(contentType, currentSeason, currentEpisode),
            playerDurationMs = currentPlaybackDurationMs(),
            longestDurationMs = lastKnownDuration,
            expectedRuntimeMs = expectedRuntimeMinutes?.let { it * 60_000L }
        )
    ) return true
    val position = currentPlaybackPositionMs() ?: return false
    return isShortPlaceholderDuration(getEffectiveDuration(position))
}

internal fun PlayerRuntimeController.isErrorCardPlayback(): Boolean = isErrorCardPlayback(
    placeholderRejected = placeholderRejectedForCurrentStream,
    filmOrEpisode = isFilmOrEpisode(contentType, currentSeason, currentEpisode),
    playerDurationMs = currentPlaybackDurationMs()
)

/**
 * Handles a natural end-of-playback event for ExoPlayer / MPV.
 *
 * Short debrid placeholders and fatal-error states must not mark the episode
 * watched or trigger auto-play next.
 */
internal fun PlayerRuntimeController.handleNaturalPlaybackEnded() {
    val position = currentPlaybackPositionMs() ?: 0L
    val duration = getEffectiveDuration(position)
    val hasFatalError = !_uiState.value.error.isNullOrBlank()
    val placeholder = isPlaceholderPlayback()
    if (placeholder || !shouldTreatAsNaturalPlaybackCompletion(
            hasRenderedFirstFrame = hasRenderedFirstFrame,
            hasFatalError = hasFatalError,
            durationMs = duration
        )
    ) {
        Log.i(
            PlayerRuntimeController.TAG,
            "Ignoring non-natural ENDED: firstFrame=$hasRenderedFirstFrame " +
                "error=$hasFatalError placeholder=$placeholder durationMs=$duration positionMs=$position"
        )
        // Prevent PlayerScreen from dispatching onPlaybackEnded / next-episode navigation.
        _uiState.update { it.copy(playbackEnded = false) }
        nextEpisodeAutoPlayJob?.cancel()
        nextEpisodeAutoPlayJob = null
        if (placeholder && hasRenderedFirstFrame) offerSourcesAfterPlaceholderEnded()
        return
    }

    emitCompletionScrobbleStop(progressPercent = 99.5f)
    if (contentType.equals("cloud", ignoreCase = true)) {
        saveCloudLibraryProgress(position, duration, completed = true)
    } else {
        saveWatchProgress()
    }
    resetPostPlayStateAfterPlaybackEnded()
}

/**
 * Cancels any in-flight next-episode auto-play / still-watching prompt when a
 * fatal player error is shown. Callers should also clear [PlayerUiState.playbackEnded]
 * and [PlayerUiState.postPlayMode] in the same state update as the error message.
 */
internal fun PlayerRuntimeController.cancelNextEpisodeAutoPlayOnFatalError() {
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
}

internal fun PlayerRuntimeController.currentWatchProgress(
    parentContentId: String,
    parentContentType: String,
    position: Long,
    duration: Long,
    lastWatched: Long
) = WatchProgress(
    contentId = parentContentId,
    contentType = parentContentType,
    name = contentName ?: title,
    poster = poster,
    backdrop = backdrop,
    logo = logo,
    videoId = currentVideoId ?: parentContentId,
    season = currentSeason,
    episode = currentEpisode,
    episodeTitle = currentEpisodeTitle,
    position = position,
    duration = duration,
    lastWatched = lastWatched
)

internal fun PlayerRuntimeController.saveWatchProgressInternal(position: Long, duration: Long, syncRemote: Boolean = true) {
    if (contentType.equals("cloud", ignoreCase = true)) {
        saveCloudLibraryProgress(position, duration, completed = false)
        return
    }
    val parentContentId = contentId?.takeIf { it.isNotEmpty() } ?: return
    val parentContentType = contentType?.takeIf { it.isNotEmpty() } ?: return

    if (position < 1000) return

    val fallbackPercent = if (duration <= 0L) 5f else null

    val progress = currentWatchProgress(parentContentId, parentContentType, position, duration, System.currentTimeMillis())
        .copy(progressPercent = fallbackPercent)

    scope.launch(kotlinx.coroutines.NonCancellable) {
        try {
            val effectiveContentId = watchProgressRepository.normalizeParentContentId(
                parentContentId = progress.contentId,
                videoId = progress.videoId,
                profileId = profileId
            )
            val normalizedProgress = progress.copy(contentId = effectiveContentId)
            if (normalizedProgress.isCompleted()) {
                if (!hasMarkedCurrentEpisodeCompleted) {
                    hasMarkedCurrentEpisodeCompleted = true
                    watchProgressRepository.markAsCompleted(
                        normalizedProgress,
                        profileId = profileId,
                        broadcastTrackingHistory = false
                    )
                }
                runCatching { tvRecommendationManager.onProgressRemoved(normalizedProgress.contentId) }
            } else if (!hasMarkedCurrentEpisodeCompleted) {
                // Only save in-progress when the episode has not already been
                // marked as completed during this playback session.  After
                // natural playback completion the player can report stale
                // position/duration values (e.g. duration=0 → fallbackPercent=5)
                // which would overwrite the completed entry in the mutation
                // store and push an incorrect low-progress value to remote.
                watchProgressRepository.saveProgress(
                    normalizedProgress,
                    profileId = profileId,
                    syncRemote = syncRemote
                )
                runCatching { tvRecommendationManager.updateSingleWatchNextProgram(normalizedProgress) }
            }
        } catch (error: IOException) {
            Log.w(PlayerRuntimeController.TAG, "Watch progress could not be saved", error)
        }
    }
}

private fun PlayerRuntimeController.saveCloudLibraryProgress(
    position: Long,
    duration: Long,
    completed: Boolean
) {
    if (!completed && position < 1_000L) return
    val playbackContext = cloudPlaybackContext ?: return
    val file = playbackContext.fileForVideoId(currentVideoId) ?: return
    cloudPlaybackProgressStore.save(
        item = playbackContext.item,
        file = file,
        positionMs = position,
        durationMs = duration,
        completed = completed
    )
}

internal fun PlayerRuntimeController.currentPlaybackProgressPercent(): Float {
    if (!hasRenderedFirstFrame) return 0f
    val position = currentPlaybackPositionMs() ?: return 0f
    val duration = currentPlaybackDurationMs().takeIf { it > 0 } ?: lastKnownDuration
    if (duration <= 0L) return 0f
    return ((position.toFloat() / duration.toFloat()) * 100f).coerceIn(0f, 100f)
}

internal fun PlayerRuntimeController.refreshScrobbleItem() {
    currentScrobbleItem = buildScrobbleItem()
    hasSentScrobbleStartForCurrentItem = false
    hasRequestedScrobbleStartForCurrentItem = false
    scrobbleStartRequestGeneration++
    hasSentCompletionScrobbleForCurrentItem = false
    serverScrobbledProviders = emptySet()
    logScrobbleDiagnostic("item_refreshed")
}

internal fun PlayerRuntimeController.buildScrobbleItem(): TrackingMediaReference? {
    val rawContentId = contentId ?: return null
    val isServerItem = ServerItemRef.isServerId(rawContentId)
    val parentMetaId = if (isServerItem) serverImdbId(rawContentId) ?: return null else rawContentId
    val reference = buildTrackingMediaReference(
        contentType = contentType ?: "movie",
        parentMetaId = parentMetaId,
        videoId = currentVideoId.takeUnless { isServerItem },
        title = contentName ?: title,
        releaseInfo = year,
        seasonNumber = currentSeason,
        episodeNumber = currentEpisode,
        episodeTitle = currentEpisodeTitle
    )
    return reference.takeIf { media ->
        media.hasResolvableIdentity &&
            (media.kind == TrackingMediaKind.MOVIE ||
                media.kind == TrackingMediaKind.ANIME ||
                media.episode != null)
    }
}

internal fun PlayerRuntimeController.emitScrobbleStart() {
    logScrobbleDiagnostic("start_evaluated")
    if (isPlaceholderPlayback()) {
        logScrobbleDiagnostic("start_skipped", "reason=short_placeholder")
        return
    }
    if (hasRequestedScrobbleStartForCurrentItem) {
        logScrobbleDiagnostic("start_skipped", "reason=already_requested")
        return
    }

    // Don't start a new Trakt scrobble session if playback resumes at ≥80%.
    // This avoids creating a duplicate history entry when the user continues
    // watching something already marked as watched. If the user seeks back
    // below 80%, the next progress update will re-trigger scrobble start.
    val currentProgress = currentPlaybackProgressPercent()
    if (currentProgress >= 80f) {
        logScrobbleDiagnostic("start_skipped", "reason=completion_threshold progress=$currentProgress")
        return
    }

    hasRequestedScrobbleStartForCurrentItem = true
    val requestGeneration = ++scrobbleStartRequestGeneration
    logScrobbleDiagnostic("start_queued", "requestGeneration=$requestGeneration")
    scope.launch {
        // Wait for the episode mapping to finish (with its own timeout) so that
        // the scrobble start is sent with the correct season/episode number.
        traktMappingJob?.join()
        currentScrobbleItem = buildScrobbleItem()
        val item = currentScrobbleItem
        if (item == null) {
            logScrobbleDiagnostic("start_cancelled", "reason=no_scrobble_item requestGeneration=$requestGeneration")
            return@launch
        }
        if (requestGeneration != scrobbleStartRequestGeneration || !hasRequestedScrobbleStartForCurrentItem) {
            logScrobbleDiagnostic("start_cancelled", "reason=stale_before_dispatch requestGeneration=$requestGeneration")
            return@launch
        }
        serverScrobbledProviders = serverPlayback.scrobbledServices(currentStreamUrl)
            .mapNotNull(TrackingProviderId::fromStorage)
            .toSet()
        val progressPercent = currentPlaybackProgressPercent()
        logScrobbleDiagnostic("start_dispatching", "requestGeneration=$requestGeneration progress=$progressPercent")
        val failures = trackingScrobbleCoordinator.scrobble(
            action = TrackingScrobbleAction.START,
            event = TrackingScrobbleEvent(item, progressPercent.toDouble(), serverScrobbledProviders)
        )
        logScrobbleDiagnostic(
            "start_dispatched",
            "requestGeneration=$requestGeneration failures=${failures.map { it.providerId.storageId }}"
        )
        if (requestGeneration != scrobbleStartRequestGeneration || !hasRequestedScrobbleStartForCurrentItem) {
            logScrobbleDiagnostic("start_not_recorded", "reason=stale_after_dispatch requestGeneration=$requestGeneration")
            return@launch
        }
        hasSentScrobbleStartForCurrentItem = true
        logScrobbleDiagnostic("start_recorded", "requestGeneration=$requestGeneration")
    }
}

internal fun PlayerRuntimeController.emitScrobbleStop(progressPercent: Float? = null) {
    logScrobbleDiagnostic("stop_evaluated", "providedProgress=${progressPercent ?: "none"}")
    if (isPlaceholderPlayback()) {
        logScrobbleDiagnostic("stop_skipped", "reason=short_placeholder")
        return
    }
    val item = currentScrobbleItem
    if (item == null) {
        logScrobbleDiagnostic("stop_skipped", "reason=no_scrobble_item")
        return
    }

    val provided = progressPercent
    if (!hasRequestedScrobbleStartForCurrentItem && (provided ?: 0f) < 80f) {
        logScrobbleDiagnostic("stop_skipped", "reason=no_active_scrobble providedProgress=${provided ?: "none"}")
        return
    }

    val percent = provided ?: currentPlaybackProgressPercent()
    logScrobbleDiagnostic("stop_queued", "progress=$percent")
    val known = serverScrobbledProviders
    val streamUrl = currentStreamUrl
    scope.launch(kotlinx.coroutines.NonCancellable) {
        logScrobbleDiagnostic("stop_dispatching", "progress=$percent")
        val skipped = known.ifEmpty {
            serverPlayback.scrobbledServices(streamUrl).mapNotNull(TrackingProviderId::fromStorage).toSet()
        }
        val failures = trackingScrobbleCoordinator.scrobble(
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(item, percent.toDouble(), skipped)
        )
        logScrobbleDiagnostic("stop_dispatched", "progress=$percent failures=${failures.map { it.providerId.storageId }}")
    }
    scrobbleStartRequestGeneration++
    hasRequestedScrobbleStartForCurrentItem = false
    hasSentScrobbleStartForCurrentItem = false
    logScrobbleDiagnostic("stop_state_reset", "progress=$percent")
}

internal fun PlayerRuntimeController.emitScrobblePause(progressPercent: Float? = null) {
    logScrobbleDiagnostic("pause_evaluated", "providedProgress=${progressPercent ?: "none"}")
    if (isPlaceholderPlayback()) {
        logScrobbleDiagnostic("pause_skipped", "reason=short_placeholder")
        return
    }
    val item = currentScrobbleItem
    if (item == null) {
        logScrobbleDiagnostic("pause_skipped", "reason=no_scrobble_item")
        return
    }

    val percent = progressPercent ?: currentPlaybackProgressPercent()
    if (!shouldSendPauseScrobble(hasRequestedScrobbleStartForCurrentItem, percent)) {
        logScrobbleDiagnostic(
            "pause_skipped",
            "reason=policy active=$hasRequestedScrobbleStartForCurrentItem progress=$percent"
        )
        return
    }
    logScrobbleDiagnostic("pause_queued", "progress=$percent")
    val skipped = serverScrobbledProviders
    scope.launch(kotlinx.coroutines.NonCancellable) {
        logScrobbleDiagnostic("pause_dispatching", "progress=$percent")
        val failures = trackingScrobbleCoordinator.scrobble(
            action = TrackingScrobbleAction.PAUSE,
            event = TrackingScrobbleEvent(item, percent.toDouble(), skipped)
        )
        logScrobbleDiagnostic("pause_dispatched", "progress=$percent failures=${failures.map { it.providerId.storageId }}")
    }
    scrobbleStartRequestGeneration++
    hasRequestedScrobbleStartForCurrentItem = false
    hasSentScrobbleStartForCurrentItem = false
    logScrobbleDiagnostic("pause_state_reset", "progress=$percent")
}

internal fun PlayerRuntimeController.emitCompletionScrobbleStop(progressPercent: Float) {
    if (progressPercent < 80f || hasSentCompletionScrobbleForCurrentItem) return
    hasSentCompletionScrobbleForCurrentItem = true
    emitScrobbleStop(progressPercent = progressPercent)
}

internal fun PlayerRuntimeController.emitStopScrobbleForCurrentProgress() {
    val progressPercent = currentPlaybackProgressPercent()
    if (!shouldSendStopScrobble(hasRequestedScrobbleStartForCurrentItem, progressPercent)) {
        logScrobbleDiagnostic(
            "stop_current_skipped",
            "reason=policy active=$hasRequestedScrobbleStartForCurrentItem progress=$progressPercent"
        )
        return
    }
    if (progressPercent < 80f) {
        emitScrobbleStop(progressPercent = progressPercent)
        return
    }
    emitCompletionScrobbleStop(progressPercent = progressPercent)
}

internal fun PlayerRuntimeController.emitPauseScrobbleForCurrentProgress() {
    emitScrobblePause(progressPercent = currentPlaybackProgressPercent())
}

internal fun PlayerRuntimeController.emitSeekScrobbleRestart(progressPercent: Float) {
    if (progressPercent < 1f || progressPercent >= 80f) return
    if (isPlaceholderPlayback()) return
    val item = currentScrobbleItem ?: return
    if (!hasRequestedScrobbleStartForCurrentItem) return
    val skipped = serverScrobbledProviders
    scope.launch {
        trackingScrobbleCoordinator.scrobbleSeek(
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(item, progressPercent.toDouble(), skipped)
        )
        if (isPlaybackCurrentlyPlaying()) {
            trackingScrobbleCoordinator.scrobbleSeek(
                action = TrackingScrobbleAction.START,
                event = TrackingScrobbleEvent(item, currentPlaybackProgressPercent().toDouble(), skipped)
            )
        }
    }
}

internal fun PlayerRuntimeController.flushPlaybackSnapshotForSwitchOrExit() {
    logScrobbleDiagnostic("flush_switch_or_exit")
    emitStopScrobbleForCurrentProgress()
    saveWatchProgress()
}

internal fun PlayerRuntimeController.logScrobbleDiagnostic(
    stage: String,
    detail: String = ""
) {
    val item = currentScrobbleItem?.scrobbleDiagnosticIdentity() ?: "media=none"
    Log.d(
        TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
        "player stage=$stage engine=$currentInternalPlayerEngine uiPlaying=${_uiState.value.isPlaying} " +
            "requested=$hasRequestedScrobbleStartForCurrentItem sent=$hasSentScrobbleStartForCurrentItem " +
            "generation=$scrobbleStartRequestGeneration $item $detail".trim()
    )
}

internal fun PlayerRuntimeController.scheduleProgressSyncAfterSeek() {
    seekProgressSyncJob?.cancel()
    seekProgressSyncJob = scope.launch {
        delay(seekProgressSyncDebounceMs)
        saveWatchProgress()

        val progressPercent = currentPlaybackProgressPercent()
        emitSeekScrobbleRestart(progressPercent = progressPercent)
    }
}

fun PlayerRuntimeController.scheduleHideControls() {
    hideControlsJob?.cancel()
    hideControlsJob = scope.launch {
        delay(8000)
        if (_uiState.value.isPlaying && !_uiState.value.showAudioOverlay &&
            !_uiState.value.showSubtitleOverlay && !_uiState.value.showSubtitleStylePanel &&
            !_uiState.value.showSpeedDialog && !_uiState.value.showMoreDialog &&
            !_uiState.value.showSubtitleDelayOverlay &&
            !_uiState.value.showSubtitleTimingDialog &&
            !_uiState.value.showEpisodesPanel && !_uiState.value.showChaptersPanel && !_uiState.value.showSourcesPanel &&
            !_uiState.value.showStreamInfoOverlay && !_uiState.value.showPartyPanel) {
            _uiState.update { it.copy(showControls = false) }
        }
    }
}

internal fun PlayerRuntimeController.showSubtitleDelayOverlay() {
    hideControlsJob?.cancel()
    _uiState.update {
        it.copy(
            showControls = false,
            showSubtitleDelayOverlay = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false
        )
    }
    scheduleHideSubtitleDelayOverlay()
}

internal fun PlayerRuntimeController.hideSubtitleDelayOverlay() {
    hideSubtitleDelayOverlayJob?.cancel()
    hideSubtitleDelayOverlayJob = null
    _uiState.update { it.copy(showSubtitleDelayOverlay = false) }
}

internal fun PlayerRuntimeController.adjustSubtitleDelay(deltaMs: Int) {
    adjustSubtitleDelay(deltaMs = deltaMs, showOverlay = true)
}

internal fun PlayerRuntimeController.adjustSubtitleDelay(deltaMs: Int, showOverlay: Boolean) {
    setSubtitleDelayMs(targetMs = _uiState.value.subtitleDelayMs + deltaMs, showOverlay = showOverlay)
}

internal fun PlayerRuntimeController.resetSubtitleDelay(showOverlay: Boolean = true) {
    setSubtitleDelayMs(targetMs = 0, showOverlay = showOverlay)
}

internal fun PlayerRuntimeController.setSubtitleDelayMs(targetMs: Int, showOverlay: Boolean = true) {
    val newDelayMs = targetMs.coerceIn(
        minimumValue = SUBTITLE_DELAY_MIN_MS,
        maximumValue = SUBTITLE_DELAY_MAX_MS
    )
    val currentState = _uiState.value
    val keepInlineInSubtitleOverlay = showOverlay && currentState.showSubtitleOverlay

    subtitleDelayUs.set(newDelayMs.toLong() * 1000L)
    // Propagate to libass so overlay and video effect paths also shift.
    _exoPlayer?.getAssHandlerCompat()?.let {
        it.subtitleDelayUs = newDelayMs.toLong() * 1000L + streamSubtitleDelayUs.get()
    }
    if (isUsingMpvEngine()) {
        mpvView?.setSubtitleDelayMs(mpvSubtitleDelayMs(newDelayMs))
    }
    if (showOverlay) {
        _uiState.update {
            it.copy(
                subtitleDelayMs = newDelayMs,
                showControls = if (keepInlineInSubtitleOverlay) it.showControls else false,
                showSubtitleDelayOverlay = if (keepInlineInSubtitleOverlay) false else true
            )
        }
    } else {
        hideSubtitleDelayOverlayJob?.cancel()
        _uiState.update {
            it.copy(
                subtitleDelayMs = newDelayMs,
                showSubtitleDelayOverlay = false
            )
        }
    }

    refreshActiveSubtitleTrackAfterTimingChange()
    // Remember the delay so it survives to the next session (issue #1063).
    persistTrackPreference()

    if (!showOverlay || keepInlineInSubtitleOverlay) {
        hideSubtitleDelayOverlayJob?.cancel()
        hideSubtitleDelayOverlayJob = null
    } else {
        scheduleHideSubtitleDelayOverlay()
    }
}

internal fun PlayerRuntimeController.scheduleHideSubtitleDelayOverlay() {
    hideSubtitleDelayOverlayJob?.cancel()
    hideSubtitleDelayOverlayJob = scope.launch {
        delay(SUBTITLE_DELAY_OVERLAY_TIMEOUT_MS)
        _uiState.update { it.copy(showSubtitleDelayOverlay = false) }
    }
}

internal fun PlayerRuntimeController.schedulePauseOverlay() {
    if (pauseDetails.holdsOverlay(_uiState.value.showPauseOverlay)) return
    pauseOverlayJob?.cancel()

    if (!_uiState.value.pauseOverlayEnabled || !hasRenderedFirstFrame || !userPausedManually ||
        partyBridge?.partyPaused == true
    ) {
        _uiState.update { it.copy(showPauseOverlay = false) }
        return
    }

    _uiState.update { it.copy(showPauseOverlay = false) }
    pauseOverlayJob = scope.launch {
        delay(pauseOverlayDelayMs)
        val s = _uiState.value
        val anyPanelOpen = s.showSubtitleOverlay || s.showSubtitleStylePanel ||
            s.showSpeedDialog || s.showMoreDialog || s.showEpisodesPanel || s.showChaptersPanel ||
            s.showSourcesPanel || s.showAudioOverlay || s.showStreamInfoOverlay ||
            s.showSubtitleTimingDialog || s.showSubtitleDelayOverlay || s.showPartyPanel
        if (!s.isPlaying && s.pauseOverlayEnabled && s.error == null && !anyPanelOpen) {
            _uiState.update { it.copy(showPauseOverlay = true, showControls = false) }
        }
    }
}

/**
 * "Generate thumbnails before play": holds the player like a user pause, so start-up autoplay leaves it paused, but a
 * rebuilt player plays again (see [PauseIntent]).
 * False when the user already paused, null when this start cannot be held.
 */
internal fun PlayerRuntimeController.pauseForSeekThumbnails(): Boolean? {
    if (isUsingMpvEngine()) return false
    if (openDeferredForThumbnails) {
        pauseIntent.holdForThumbnails()
        shouldEnforceAutoplayOnFirstReady = false
        return true
    }
    // In a party the room decides when this box plays; a hold here would pause everyone.
    if (partyBridge?.inParty == true) return null
    val player = _exoPlayer ?: return false
    // Already a user pause (or "start paused"): not ours to hold, and never ours to resume.
    if (pausedByUser) return false
    // A tunnelled start only leaves the loading screen on its first READY with autoplay still armed.
    if ((_uiState.value.tunnelingEnabled || player.isTunnelingEnabled) && !hasRenderedFirstFrame) return null
    // The native Dolby Vision renderer must show its first frame before anything pauses it.
    // The route is armed before the video type is known; only a Dolby Vision (or not yet known) track uses it.
    val mayUseNativeRoute = currentVideoTrackMimeType.let { it == null || it == androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION } ||
        currentVideoTrackCodecs?.startsWith("dv") == true
    if (isNativeFelActiveForCurrentPlayback && mayUseNativeRoute && !hasRenderedFirstFrame) return null
    pauseIntent.holdForThumbnails()
    shouldEnforceAutoplayOnFirstReady = false
    player.pause()
    cancelPauseOverlay()
    return true
}

/**
 * Small boxes, 4K: the player held for the thumbnails lets go of its decoders and buffer. The title, the position and
 * the track choices stay; [loadAgainAfterSeekThumbnails] loads it again.
 */
internal fun PlayerRuntimeController.letGoForSeekThumbnails(): Boolean {
    if (isUsingMpvEngine() || partyBridge?.inParty == true) return false
    if (openDeferredForThumbnails) return true
    val player = _exoPlayer ?: return false
    if (player.playWhenReady || player.playbackState == Player.STATE_IDLE || player.playerError != null) return false
    // Amlogic's Dolby Vision decoder built again after its first picture plays sound over a black picture: kept held.
    if (hasRenderedFirstFrame && playbackAnalyticsDiagnostics.hudSample().videoDecoderName
            ?.startsWith(AMLOGIC_DV_DECODER) == true) {
        if (!dvDecoderKeptNoted) {
            dvDecoderKeptNoted = true
            Log.i(PlayerRuntimeController.TAG, "prepare: Dolby Vision decoder kept held for the thumbnails, not let go")
        }
        return false
    }
    player.stop()
    return true
}

private const val AMLOGIC_DV_DECODER = "c2.amlogic.dolby-vision"

/** The film stays unopened at [positionMs] for the thumbnails screen; it opens after [DEFERRED_OPEN_FAILSAFE_MS] if no screen holds it. */
internal fun PlayerRuntimeController.deferFilmOpen(positionMs: Long) {
    openDeferredForThumbnails = true
    deferredOpenAtMs = positionMs
    Log.i(PlayerRuntimeController.TAG, "prepare: the film opens when the thumbnails screen ends (at ${positionMs / 1000} s)")
    scope.launch {
        delay(DEFERRED_OPEN_FAILSAFE_MS)
        if (openDeferredForThumbnails && !pauseIntent.heldForThumbnails) {
            openDeferredFilm(play = true, why = "no thumbnails screen in ${DEFERRED_OPEN_FAILSAFE_MS / 1000} s")
        }
    }
}

private const val DEFERRED_OPEN_FAILSAFE_MS = 8_000L

internal fun PlayerRuntimeController.openDeferredFilm(play: Boolean, why: String) {
    if (!openDeferredForThumbnails) return
    openDeferredForThumbnails = false
    com.nuvio.tv.core.player.thumbnail.SeekThumbnails.deferredOpenEnded()
    if (!play && pauseIntent.heldForThumbnails) userPausedManually = true
    if (play) pauseIntent.onPlayWhenReady(true)
    Log.i(PlayerRuntimeController.TAG, "prepare: the film opens now at ${deferredOpenAtMs / 1000} s ($why)")
    scheduleOwnedPlaybackRecovery(deferredOpenAtMs, 0L)
}

internal fun PlayerRuntimeController.loadAgainAfterSeekThumbnails(play: Boolean) {
    if (openDeferredForThumbnails) {
        openDeferredFilm(play, "the thumbnails screen ended")
        return
    }
    val player = _exoPlayer ?: return
    // Left paused for the user (the app was left meanwhile): from here on it is a pause like the user's own.
    if (!play && pauseIntent.heldForThumbnails) userPausedManually = true
    if (play) pauseIntent.onPlayWhenReady(true)
    when (HandBackReload.of(player.playbackState == Player.STATE_IDLE, player.playerError != null)) {
        HandBackReload.REBUILD -> {
            Log.i(PlayerRuntimeController.TAG, "prepare: the player let go for the thumbnails is built again at " +
                "${player.currentPosition / 1000} s" + if (pausedByUser) ", paused" else "")
            scheduleOwnedPlaybackRecovery(player.currentPosition, 0L)
        }
        HandBackReload.PLAY -> if (play && !player.playWhenReady) player.play()
        HandBackReload.NONE -> Unit
    }
}

internal fun PlayerRuntimeController.cancelPauseOverlay() {
    pauseOverlayJob?.cancel()
    pauseOverlayJob = null
    pauseDetails.clear()
    _uiState.update { it.copy(showPauseOverlay = false) }
}

/** Opens the pause overlay at once, pausing first, whatever the Pause overlay setting says. */
internal fun PlayerRuntimeController.toggleDetails() {
    val state = _uiState.value
    if (state.showPauseOverlay) {
        closeDetails()
        return
    }
    if (!hasRenderedFirstFrame || state.error != null || state.showLoadingOverlay || state.playbackEnded) return
    finishSeekSettle(seek = true, reason = "details")
    val playing = hasActivePlayIntent()
    if (playing) {
        if (isPlaybackCurrentlyPlaying()) {
            onEvent(PlayerEvent.OnPlayPause)
        } else {
            userPausedManually = true
            dropPausedThumbnail()
            _exoPlayer?.pause()
            schedulePausedOutputCheck()
            emitPauseScrobbleForCurrentProgress()
            saveWatchProgress()
        }
    }
    pauseOverlayJob?.cancel()
    pauseOverlayJob = null
    pauseDetails.open(resumeOnClose = playing)
    _uiState.update { it.copy(showPauseOverlay = true, showControls = false) }
}

/** Closes the pause overlay; the video plays again when the overlay was opened over a playing video. */
internal fun PlayerRuntimeController.closeDetails() {
    val resume = pauseDetails.close()
    cancelPauseOverlay()
    if (resume && !hasActivePlayIntent()) onEvent(PlayerEvent.OnPlayPause) else showControlsTemporarily()
}

fun PlayerRuntimeController.onUserInteraction() {
    if (_uiState.value.showPauseOverlay) {
        cancelPauseOverlay()
        showControlsTemporarily()
    } else if (pauseOverlayJob != null && !_uiState.value.isPlaying && userPausedManually) {
        schedulePauseOverlay()
    }
}

fun PlayerRuntimeController.hideControls() {
    hideControlsJob?.cancel()
    _uiState.update { it.copy(showControls = false, showSeekOverlay = false, showMoreDialog = false) }
}

fun PlayerRuntimeController.onEvent(
    event: PlayerEvent,
    showPlaybackControls: Boolean = true,
) {
    if (partyBridge?.onPlayerEvent(event) == true) return
    if (showPlaybackControls && event != PlayerEvent.OnParentalGuideHide && event != PlayerEvent.OnToggleDetails &&
        event != PlayerEvent.OnCloseDetails
    ) {
        onUserInteraction()
    }
    when (event) {
        PlayerEvent.OnPlayPause -> {
            if (finishSeekSettle(seek = true, reason = "ok")) return
            // A play/pause press during a held scrub is the user's own choice from here on.
            scrubHoldPaused = false
            dropPausedThumbnail()
            var pausedNow = false
            if (isUsingMpvEngine()) {
                val playing = isPlaybackCurrentlyPlaying()
                if (playing) {
                    userPausedManually = true
                    pausedNow = true
                    setPlaybackPaused(true)
                    stopProgressUpdates()
                    stopWatchProgressSaving()
                    emitPauseScrobbleForCurrentProgress()
                    schedulePauseOverlay()
                } else {
                    userPausedManually = false
                    cancelPauseOverlay()
                    setPlaybackPaused(false)
                    startProgressUpdates()
                    startWatchProgressSaving()
                    scheduleHideControls()
                    emitScrobbleStart()
                }
            } else {
                _exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        userPausedManually = true
                        pausedNow = true
                        player.pause()
                        schedulePauseOverlay()
                        schedulePausedOutputCheck()
                        // A parked auto-restore subtitle attaches here,
                        // while paused, so the reload lands invisibly.
                        maybeAttachDeferredAddonSubtitle()
                    } else {
                        userPausedManually = false
                        cancelPauseOverlay()
                        cancelPausedOutputCheck()
                        player.play()
                    }
                }
            }
            if (showPlaybackControls) {
                if (pausedNow) _uiState.update { it.copy(pauseFocusRequest = it.pauseFocusRequest + 1) }
                showControlsTemporarily()
            }
        }
        PlayerEvent.OnSeekForward -> {
            if (_playbackTimeline.value.isLive) return
            onEvent(PlayerEvent.OnSeekBy(deltaMs = PlayerScrubRates.STEP_SHORT_MS))
        }
        PlayerEvent.OnSeekBackward -> {
            if (_playbackTimeline.value.isLive) return
            onEvent(PlayerEvent.OnSeekBy(deltaMs = -PlayerScrubRates.STEP_SHORT_MS))
        }
        is PlayerEvent.OnSeekBy -> {
            if (_playbackTimeline.value.isLive) return
            dropSeekSettle()
            stopScrubGlide()
            releaseScrubHold()
            pendingPreviewSeekPosition = null
            _uiState.update { it.copy(pendingPreviewSeekPosition = null, previewThumbPositionMs = null) }
            val current = currentPlaybackPositionMs() ?: 0L
            val maxDuration = currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
            val requested = (current + event.deltaMs)
                .coerceAtLeast(0L)
                .coerceAtMost(maxDuration)
            val target = requested
            val seekParameters = if (event.deltaMs < 0L) {
                SeekParameters.PREVIOUS_SYNC
            } else {
                SeekParameters.NEXT_SYNC
            }
            seekPlaybackTo(target, seekParameters)
            updatePlaybackTimeline(currentPosition = target)
            scheduleProgressSyncAfterSeek()
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        is PlayerEvent.OnScrubHold -> {
            if (_playbackTimeline.value.isLive) return
            val holdStart = scrubHoldClock.repeat(event.eventTime, event.downTime)
            if (holdStart == null) {
                if (scrubHoldClock.takeUndoTap()) {
                    undoScrubTap()
                    seekSettle.undoPress()
                }
                return
            }
            if (scrubGlideJob?.isActive == true && scrubGlideForward == event.forward) {
                scrubStepChase?.repeat(event.eventTime, event.forward)
                return
            }
            startScrubGlide(event.forward, holdStart, repeatAt = event.eventTime)
        }
        is PlayerEvent.OnPreviewSeekBy -> {
            if (_playbackTimeline.value.isLive) return
            stopScrubGlide()
            seekSettleJob?.cancel()
            scrubHoldClock.press(android.os.SystemClock.uptimeMillis())
            scrubTapFrom = pendingPreviewSeekPosition
            val maxDuration = currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
            val playbackPosition = currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L
            if (seekSettleApplies()) seekSettle.press(playbackPosition)
            if (scrubTapBase == null) scrubTapBase = pendingPreviewSeekPosition ?: playbackPosition
            // With thumbnails on, every step lands on the 10 s thumbnail grid.
            val target = SeekSettle.target(
                pendingMs = pendingPreviewSeekPosition,
                playbackMs = playbackPosition,
                deltaMs = event.deltaMs,
                durationMs = maxDuration,
                gridStep = SeekThumbnails::gridStep
            )
            pendingPreviewSeekPosition = target
            _uiState.update { it.copy(pendingPreviewSeekPosition = target, previewThumbPositionMs = target) }
            // Paused or held, the tapped spot is decoded first.
            if (userPausedManually || seekSettle.open) SeekThumbnails.notePriority(target)
            schedulePendingPreviewSeekExpiry()
            updatePlaybackTimeline(
                currentPosition = previewDisplayPosition() ?: target,
                playbackPosition = currentPlaybackPositionMs() ?: _playbackTimeline.value.playbackPosition
            )
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        PlayerEvent.OnCommitPreviewSeek -> {
            if (_playbackTimeline.value.isLive) return
            stopScrubGlide()
            scrubHoldClock.release(android.os.SystemClock.uptimeMillis())
            if (seekSettle.open && (!userPausedManually || SeekSettle.waitsWhilePaused(seekingMode))) {
                if (pendingPreviewSeekPosition != null) startSeekSettle() else finishSeekSettle(seek = false, reason = "empty")
                return
            }
            dropSeekSettle()
            commitPendingSeek()
        }
        is PlayerEvent.OnSeekTo -> {
            if (_playbackTimeline.value.isLive) return
            dropSeekSettle()
            stopScrubGlide()
            releaseScrubHold()
            pendingPreviewSeekPosition = null
            _uiState.update { it.copy(pendingPreviewSeekPosition = null, previewThumbPositionMs = null) }
            seekPlaybackTo(
                event.position,
                if (event.exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC,
                exact = event.exact
            )
            updatePlaybackTimeline(currentPosition = event.position)
            scheduleProgressSyncAfterSeek()
            if (showPlaybackControls) {
                if (_uiState.value.showControls) {
                    showControlsTemporarily()
                } else {
                    showSeekOverlayTemporarily()
                }
            }
        }
        is PlayerEvent.OnSelectAudioTrack -> {
            logSwitchTrace(
                stage = "event-select-audio",
                message = "index=${event.index}"
            )
            if (_uiState.value.serverAudioTracks.isNotEmpty()) {
                selectServerAudio(event.index)
            } else {
                rememberAudioSelection(event.index)
                // Tunnelled playback: in-place AudioTrack recreation inside a live
                // tunnel latches bad frame pacing on some vendor HALs (the Prism+
                // class). Rebuild at position instead; no-op when tunneling off.
                if (!maybeRebuildForTunneledAudioSwitch(event.index)) {
                    selectAudioTrack(event.index)
                }
            }
            _uiState.update {
                it.copy(
                    showAudioOverlay = false,
                    showSubtitleDelayOverlay = false,
                    showSubtitleTimingDialog = false
                )
            }
        }
        is PlayerEvent.OnSetAudioDelayMs -> {
            applyAudioDelay(event.delayMs)
        }
        is PlayerEvent.OnSetAudioAmplificationDb -> {
            val clampedDb = event.db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
            applyAudioAmplification(clampedDb)
            if (_uiState.value.persistAudioAmplification) {
                scope.launch {
                    playerSettingsDataStore.setAudioAmplificationDb(clampedDb)
                }
            }
        }
        is PlayerEvent.OnSetPersistAudioAmplification -> {
            val currentDb = _uiState.value.audioAmplificationDb
            val currentCenterMixDb = _uiState.value.centerMixLevelDb
            _uiState.update { it.copy(persistAudioAmplification = event.enabled) }
            scope.launch {
                playerSettingsDataStore.setPersistAudioAmplification(
                    enabled = event.enabled,
                    dbToPersist = if (event.enabled) currentDb else null,
                    centerMixDbToPersist = if (event.enabled) currentCenterMixDb else null
                )
            }
        }
        is PlayerEvent.OnSetNightMode -> {
            applyNightMode(event.level)
            scope.launch {
                playerSettingsDataStore.setNightMode(event.level)
            }
        }
        is PlayerEvent.OnSetCenterMixLevelDb -> {
            val clampedDb = event.db.coerceIn(DIALOGUE_BOOST_MIN_DB, DIALOGUE_BOOST_MAX_DB)
            applyCenterMixLevel(clampedDb)
            if (_uiState.value.persistAudioAmplification) {
                scope.launch {
                    playerSettingsDataStore.setCenterMixLevelDb(clampedDb)
                }
            }
        }
        is PlayerEvent.OnSelectSubtitleTrack -> {
            logSwitchTrace(
                stage = "event-select-subtitle-internal",
                message = "index=${event.index}"
            )
            autoSubtitleSelected = true
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            resetSubtitleAutoSyncState()
            cancelAutomaticSubtitleSync() // AutoSync hook
            if (_uiState.value.serverSubtitleTracks.isNotEmpty()) {
                selectServerSubtitle(event.index)
            } else {
                rememberInternalSubtitleSelection(event.index)
                selectSubtitleTrack(event.index)
            }
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = event.openPanel || it.showSubtitleOverlay,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = event.openPanel || it.showControls,
                    selectedAddonSubtitle = null
                )
            }
        }
        PlayerEvent.OnDisableSubtitles, PlayerEvent.OnDisableSubtitlesQuietly -> {
            val openPanel = event == PlayerEvent.OnDisableSubtitles
            logSwitchTrace(
                stage = "event-disable-subtitles",
                message = "selectedSubtitleIndex=${_uiState.value.selectedSubtitleTrackIndex}"
            )
            autoSubtitleSelected = true
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            resetSubtitleAutoSyncState()
            cancelAutomaticSubtitleSync() // AutoSync hook
            rememberSubtitleDisabled()
            disableSubtitles()
            if (hasBurnedInServerSubtitle) clearServerSubtitle()
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = openPanel || it.showSubtitleOverlay,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = openPanel || it.showControls,
                    selectedAddonSubtitle = null,
                    selectedSubtitleTrackIndex = -1
                )
            }
        }
        is PlayerEvent.OnSelectAddonSubtitle -> {
            logSwitchTrace(
                stage = "event-select-subtitle-addon",
                message = "addonId=${event.subtitle.id} addonLang=${event.subtitle.lang} addonName=${event.subtitle.addonName}"
            )
            autoSubtitleSelected = true
            rememberAddonSubtitleSelection(event.subtitle)
            selectAddonSubtitle(event.subtitle)
            if (hasBurnedInServerSubtitle) clearServerSubtitle()
            runSelectedAutomaticSubtitleSync(event.subtitle) // AutoSync hook
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = event.openPanel || it.showSubtitleOverlay,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = event.openPanel || it.showControls
                )
            }
        }
        is PlayerEvent.OnSetPlaybackSpeed -> {
            if (event.speed != 1f && !canChangePlaybackSpeed()) {
                showPlaybackSpeedUnavailable()
                return
            }
            if (isUsingMpvEngine()) {
                setPlaybackSpeedInternal(event.speed)
            } else {
                _exoPlayer?.let { player ->
                    player.setPlaybackSpeed(event.speed)
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .build()
                }
            }
            _uiState.update {
                it.copy(
                    playbackSpeed = event.speed,
                    showSpeedDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false
                )
            }
            contentId?.takeIf { it.isNotBlank() }?.let { id ->
                scope.launch { trackPreferenceDataStore.savePlaybackSpeed(id, event.speed) }
            }
        }
        PlayerEvent.OnToggleControls -> {
            if (_uiState.value.showSubtitleTimingDialog) {
                dismissSubtitleTimingDialog()
            }
            if (_uiState.value.showSubtitleDelayOverlay) {
                hideSubtitleDelayOverlay()
            }
            val shouldShowControls = !_uiState.value.showControls
            _uiState.update {
                it.copy(
                    showControls = shouldShowControls,
                    showSeekOverlay = false,
                    showMoreDialog = if (shouldShowControls) it.showMoreDialog else false
                )
            }
            if (shouldShowControls) {
                scheduleHideControls()
            }
        }
        PlayerEvent.OnShowAudioOverlay -> {
            _uiState.update {
                it.copy(
                    showAudioOverlay = true,
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = false,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnShowSubtitleOverlay -> {
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = true,
                    showAudioOverlay = false,
                    showSubtitleStylePanel = false,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnOpenSubtitleStylePanel -> {
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = true,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnDismissSubtitleStylePanel -> {
            _uiState.update { it.copy(showSubtitleStylePanel = false) }
            scheduleHideControls()
        }
        PlayerEvent.OnShowSubtitleTimingDialog -> {
            showSubtitleTimingDialog()
        }
        PlayerEvent.OnDismissSubtitleTimingDialog -> {
            dismissSubtitleTimingDialog()
        }
        PlayerEvent.OnCaptureSubtitleAutoSyncTime -> {
            captureSubtitleAutoSyncTime()
        }
        is PlayerEvent.OnApplySubtitleAutoSyncCue -> {
            applySubtitleAutoSyncCue(event.cueStartTimeMs)
        }
        PlayerEvent.OnReloadSubtitleAutoSyncCues -> {
            reloadSubtitleAutoSyncCues()
        }
        PlayerEvent.OnShowSubtitleDelayOverlay -> {
            showSubtitleDelayOverlay()
        }
        PlayerEvent.OnHideSubtitleDelayOverlay -> {
            hideSubtitleDelayOverlay()
        }
        is PlayerEvent.OnAdjustSubtitleDelay -> {
            adjustSubtitleDelay(event.deltaMs, event.showOverlay)
        }
        is PlayerEvent.OnResetSubtitleDelay -> {
            resetSubtitleDelay(event.showOverlay)
        }
        PlayerEvent.OnShowSpeedDialog -> {
            if (!canChangePlaybackSpeed()) {
                // A speed saved for this title may have been applied at start: the press brings it back to normal.
                if (_uiState.value.playbackSpeed != 1f) onEvent(PlayerEvent.OnSetPlaybackSpeed(1f))
                showPlaybackSpeedUnavailable()
                return
            }
            _uiState.update {
                it.copy(
                    showSpeedDialog = true,
                    showAudioOverlay = false,
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = false,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnShowMoreDialog -> {
            _uiState.update {
                it.copy(
                    showMoreDialog = true,
                    showAudioOverlay = false,
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showSpeedDialog = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnDismissMoreDialog -> {
            _uiState.update { it.copy(showMoreDialog = false) }
            scheduleHideControls()
        }
        PlayerEvent.OnShowEpisodesPanel -> {
            showEpisodesPanel()
        }
        PlayerEvent.OnDismissEpisodesPanel -> {
            dismissEpisodesPanel()
        }
        PlayerEvent.OnShowChaptersPanel -> showChaptersPanel()
        PlayerEvent.OnDismissChaptersPanel -> dismissChaptersPanel()
        is PlayerEvent.OnSelectChapter -> selectChapter(event.startMs)
        PlayerEvent.OnNextChapter -> jumpChapter(forward = true)
        PlayerEvent.OnPreviousChapter -> jumpChapter(forward = false)
        PlayerEvent.OnBackFromEpisodeStreams -> {
            _uiState.update {
                it.copy(
                    showEpisodeStreams = false,
                    isLoadingEpisodeStreams = false
                )
            }
        }
        is PlayerEvent.OnEpisodeSeasonSelected -> {
            selectEpisodesSeason(event.season)
        }
        is PlayerEvent.OnEpisodeSelected -> {
            loadStreamsForEpisode(
                video = event.video,
                forceRefresh = false,
                manualSelection = event.manualSelection
            )
        }
        PlayerEvent.OnReloadEpisodeStreams -> {
            reloadEpisodeStreams()
        }
        is PlayerEvent.OnEpisodeAddonFilterSelected -> {
            filterEpisodeStreamsByAddon(event.addonName)
        }
        is PlayerEvent.OnEpisodeStreamSelected -> {
            switchToEpisodeStream(event.stream)
        }
        PlayerEvent.OnShowSourcesPanel -> {
            showSourcesPanel()
        }
        PlayerEvent.OnDismissSourcesPanel -> {
            dismissSourcesPanel()
        }
        PlayerEvent.OnReloadSourceStreams -> {
            loadSourceStreams(forceRefresh = true)
        }
        is PlayerEvent.OnSourceAddonFilterSelected -> {
            filterSourceStreamsByAddon(event.addonName)
        }
        is PlayerEvent.OnSourceStreamSelected -> {
            switchToSourceStream(event.stream)
        }
        PlayerEvent.OnDismissTransientOverlay -> {
            _uiState.update {
                it.copy(
                    showAudioOverlay = false,
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSpeedDialog = false,
                    showSubtitleDelayOverlay = false,
                    showMoreDialog = false
                )
            }
            scheduleHideControls()
        }
        PlayerEvent.OnRetry -> {
            hasRenderedFirstFrame = false
            endDetectionArmed = false
            mpvEofSeenClear = false
            hasRetriedCurrentStreamAfter416 = false
            playbackIssueReportRequestVersion.incrementAndGet()
            resetErrorRetryState()
            deadSourceFailoverCount = 0
            serverDeadAnswerRetried.clear()
            lastPlaybackIssueError = null
            clearPendingEngineSwitchTrackPreference()
            resetPostPlayOverlayState(clearEpisode = false)
            _uiState.update { state ->
                state.copy(
                    error = null,
                    playbackIssueReportStatus = PlaybackIssueReportStatus.Idle,
                    playbackIssueReportId = null,
                    playbackIssueReportError = null,
                    loadingIssueReportVisible = false,
                    loadingIssueElapsedMs = 0L,
                    showLoadingOverlay = state.loadingOverlayEnabled,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false
                )
            }
            if (isTorrentStream && currentInfoHash != null) {
                releasePlayer()
                stopTorrentStream()
                launchTorrentSourceStream(
                    stream = com.nuvio.tv.domain.model.Stream(
                        name = _uiState.value.currentStreamName,
                        title = null,
                        description = null,
                        url = null,
                        ytId = null,
                        infoHash = currentInfoHash,
                        fileIdx = currentFileIdx,
                        externalUrl = null,
                        behaviorHints = null,
                        addonName = currentAddonName ?: "",
                        addonLogo = currentAddonLogo
                    ),
                    infoHash = currentInfoHash!!,
                    loadSavedProgress = true
                )
            } else {
                releasePlayer()
                initializePlayer(currentStreamUrl, currentHeaders)
            }
        }
        PlayerEvent.OnReportPlaybackIssue -> {
            submitPlaybackIssueReport()
        }
        PlayerEvent.OnParentalGuideHide -> {
            _uiState.update { it.copy(showParentalGuide = false) }
        }
        PlayerEvent.OnToggleTorrentStats -> {
            _uiState.update { it.copy(showTorrentStats = !it.showTorrentStats) }
        }
        is PlayerEvent.OnShowDisplayModeInfo -> {
            _uiState.update {
                it.copy(
                    displayModeInfo = event.info,
                    showDisplayModeInfo = true
                )
            }
        }
        PlayerEvent.OnHideDisplayModeInfo -> {
            _uiState.update { it.copy(showDisplayModeInfo = false) }
        }
        PlayerEvent.OnDismissPauseOverlay -> {
            cancelPauseOverlay()
        }
        PlayerEvent.OnToggleDetails -> toggleDetails()
        PlayerEvent.OnCloseDetails -> closeDetails()
        PlayerEvent.OnSkipIntro -> {
            skipActiveInterval()
        }
        PlayerEvent.OnDismissSkipIntro -> {
            _uiState.update { it.copy(skipIntervalDismissed = true) }
        }
        PlayerEvent.OnPlayNextEpisode -> {
            playNextEpisode(userInitiated = true)
        }
        PlayerEvent.OnDismissNextEpisodeCard -> {
            nextEpisodeAutoPlayJob?.cancel()
            nextEpisodeAutoPlayJob = null
            _uiState.update {
                it.copy(
                    postPlayMode = null,
                    postPlayDismissedForCurrentEpisode = true,
                )
            }
        }
        PlayerEvent.OnStillWatchingContinue -> onStillWatchingContinue()
        PlayerEvent.OnDismissStillWatchingPrompt -> onDismissStillWatchingPrompt()
        is PlayerEvent.OnSetSubtitleBitmapSize -> {
            scope.launch { playerSettingsDataStore.setSubtitleBitmapSize(event.size) }
        }
        is PlayerEvent.OnSetSubtitleSize -> {
            scope.launch { playerSettingsDataStore.setSubtitleSize(event.size) }
        }
        is PlayerEvent.OnSetSubtitleHdrBrightness -> {
            scope.launch { playerSettingsDataStore.setSubtitleHdrBrightness(event.brightness) }
        }
        is PlayerEvent.OnSetSubtitleTextColor -> {
            val editingHdr = _uiState.value.isEditingHdrSubtitleStyle
            scope.launch {
                if (editingHdr) {
                    playerSettingsDataStore.setSubtitleHdrTextColor(event.color)
                } else {
                    playerSettingsDataStore.setSubtitleTextColor(event.color)
                }
            }
        }
        is PlayerEvent.OnSetSubtitleBold -> {
            scope.launch { playerSettingsDataStore.setSubtitleBold(event.bold) }
        }
        is PlayerEvent.OnSetSubtitleFont -> {
            scope.launch { playerSettingsDataStore.setSubtitleFont(event.font) }
        }
        is PlayerEvent.OnSetSubtitleEdgeStyle -> {
            scope.launch { playerSettingsDataStore.setSubtitleEdgeStyle(event.style) }
        }
        is PlayerEvent.OnSetSubtitleOutlineEnabled -> {
            scope.launch { playerSettingsDataStore.setSubtitleOutlineEnabled(event.enabled) }
        }
        is PlayerEvent.OnSetSubtitleOutlineColor -> {
            scope.launch { playerSettingsDataStore.setSubtitleOutlineColor(event.color) }
        }
        is PlayerEvent.OnSetSubtitleVerticalOffset -> {
            scope.launch { playerSettingsDataStore.setSubtitleVerticalOffset(event.offset) }
        }
        PlayerEvent.OnResetSubtitleDefaults -> {
            val editingHdr = _uiState.value.isEditingHdrSubtitleStyle
            val keptTextColor = _uiState.value.subtitleStyle.textColor
            scope.launch {
                playerSettingsDataStore.resetSubtitleAppearance()
                if (editingHdr) {
                    val defaults = SubtitleStyleSettings()
                    playerSettingsDataStore.setSubtitleTextColor(keptTextColor)
                    playerSettingsDataStore.setSubtitleHdrTextColor(defaults.hdrTextColor)
                    playerSettingsDataStore.setSubtitleHdrBrightness(defaults.hdrBrightness)
                }
            }
        }
        PlayerEvent.OnToggleAspectRatio -> {
            val state = _uiState.value
            if (state.tunnelingEnabled) {
                val fill = !state.tunneledSurfaceFill
                val label = PlayerDisplayModeUtils.resizeModeLabel(
                    PlayerDisplayModeUtils.exoSurfaceResizeMode(
                        tunnelingEnabled = true,
                        tunneledSurfaceFill = fill
                    ),
                    context
                )
                Log.d(
                    PlayerRuntimeController.TAG,
                    "Tunneled surface resize toggled: fill=$fill ($label)"
                )
                _uiState.update {
                    it.copy(
                        tunneledSurfaceFill = fill,
                        showAspectRatioIndicator = true,
                        aspectRatioIndicatorText = label
                    )
                }
                scope.launch {
                    deviceLocalPlayerPreferences.setTunneledSurfaceFill(fill)
                }
                hideAspectRatioIndicatorJob?.cancel()
                hideAspectRatioIndicatorJob = scope.launch {
                    delay(1500)
                    _uiState.update { it.copy(showAspectRatioIndicator = false) }
                }
                return
            }
            val newMode = nextAspectMode(state.aspectMode)
            val label = aspectModeLabel(newMode, context::getString)
            Log.d(PlayerRuntimeController.TAG, "Aspect mode toggled by user: ${state.aspectMode} -> $newMode ($label)")
            _uiState.update {
                it.copy(
                    aspectMode = newMode,
                    showAspectRatioIndicator = true,
                    aspectRatioIndicatorText = label
                )
            }
            scope.launch {
                Log.d(PlayerRuntimeController.TAG, "Persisting aspect mode: $newMode")
                deviceLocalPlayerPreferences.setAspectMode(newMode)
            }
            hideAspectRatioIndicatorJob?.cancel()
            hideAspectRatioIndicatorJob = scope.launch {
                delay(1500)
                _uiState.update { it.copy(showAspectRatioIndicator = false) }
            }
        }
        PlayerEvent.OnSwitchInternalPlayerEngine -> {
            logSwitchTrace(
                stage = "event-switch-engine",
                message = "requestedByUser=true"
            )
            switchInternalPlayerEngineManually()
        }
        PlayerEvent.OnSwitchToMpvPlayer -> {
            logSwitchTrace(
                stage = "event-switch-to-mpv",
                message = "requestedByUser=true"
            )
            switchToInternalPlayerEngine(InternalPlayerEngine.MVP_PLAYER, reason = "user-error-dialog-switch-to-mpv")
        }
        PlayerEvent.OnShowStreamInfo -> {
            val info = buildStreamInfoData()
            _uiState.update {
                it.copy(
                    showStreamInfoOverlay = true,
                    streamInfoData = info,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnDismissStreamInfo -> {
            _uiState.update { it.copy(showStreamInfoOverlay = false) }
        }
        PlayerEvent.OnShowPartyPanel -> {
            _uiState.update {
                it.copy(
                    showPartyPanel = true,
                    showAudioOverlay = false,
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = false,
                    showSpeedDialog = false,
                    showMoreDialog = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showStreamInfoOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnDismissPartyPanel -> {
            _uiState.update { it.copy(showPartyPanel = false) }
            scheduleHideControls()
        }
        PlayerEvent.OnTogglePlaybackStats -> {
            _uiState.update { it.copy(showPlaybackStatsOverlay = !it.showPlaybackStatsOverlay) }
        }
        PlayerEvent.OnTogglePlayerStatsHud -> {
            val currentState = _uiState.value
            if (currentState.playerStatsHudButtonAvailable) {
                val newActive = !currentState.playerStatsHudEnabled
                scope.launch {
                    deviceLocalPlayerPreferences.setPlayerStatsHudActive(newActive)
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.buildStreamInfoData(): StreamInfoData {
    val state = _uiState.value
    val selectedAudio = state.audioTracks.firstOrNull { it.isSelected }
    val selectedSubtitle = state.subtitleTracks.firstOrNull { it.isSelected }
    val addonSub = state.selectedAddonSubtitle

    val activeVideoFormat = _exoPlayer?.videoFormat
    val matchedFormat = _exoPlayer?.currentTracks?.groups
        ?.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && it.isSelected }
        ?.let { group ->
            (0 until group.length)
                .map { group.getTrackFormat(it) }
                .firstOrNull { it.id == activeVideoFormat?.id || (it.bitrate > 0 && it.bitrate == activeVideoFormat?.bitrate) }
        }

    val videoWidth = matchedFormat?.width?.takeIf { it > 0 } ?: activeVideoFormat?.width?.takeIf { it > 0 } ?: currentVideoWidth
    val videoHeight = matchedFormat?.height?.takeIf { it > 0 } ?: activeVideoFormat?.height?.takeIf { it > 0 } ?: currentVideoHeight
    val videoBitrate = activeVideoFormat?.bitrate?.takeIf { it > 0 } ?: currentVideoBitrate
    val videoCodec = activeVideoFormat?.let { format ->
        CustomDefaultTrackNameProvider.resolveCodecName(format.sampleMimeType, format.codecs)
    } ?: currentVideoCodec

    // Prefer the renderer's live format for the audio codec label: the dvmkv extractor
    // publishes a provisional core-DTS mime and may refine it to DTS-HD only after the
    // TrackGroup snapshot freezes, so the track-list value can understate the stream.
    // The live format carries the refinement; the track-list value stays as fallback
    // (and is the only value on the mpv engine, where the Exo player handle is null).
    val liveAudioCodec = _exoPlayer?.audioFormat?.let { format ->
        CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
            ?: CustomDefaultTrackNameProvider.formatNameFromMime(format.codecs)
    }

    return StreamInfoData(
        addonName = currentAddonName,
        addonLogo = currentAddonLogo,
        streamName = state.currentStreamName,
        streamDescription = currentStreamDescription,
        filename = currentFilename,
        fileSize = currentVideoSize,
        videoCodec = videoCodec,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        videoFrameRate = state.detectedFrameRate.takeIf { it > 0f },
        videoBitrate = videoBitrate,
        fileBitrate = PlayerBitrateEstimator.fileBitrateBps(
            currentVideoSize,
            playbackTimeline.value.duration
        ),
        audioCodec = liveAudioCodec ?: selectedAudio?.codec,
        audioChannels = selectedAudio?.channelCount?.let {
            CustomDefaultTrackNameProvider.getChannelLayoutName(it)
        },
        audioSampleRate = selectedAudio?.sampleRate,
        audioLanguage = selectedAudio?.language,
        subtitleName = selectedSubtitle?.name ?: addonSub?.lang,
        subtitleCodec = selectedSubtitle?.codec,
        subtitleLanguage = selectedSubtitle?.language ?: addonSub?.lang,
        subtitleSource = when {
            addonSub != null -> context.getString(R.string.stream_info_subtitle_source_addon)
            selectedSubtitle != null -> context.getString(R.string.stream_info_subtitle_source_embedded)
            else -> null
        },
        playerEngine = when (currentInternalPlayerEngine) {
            com.nuvio.tv.data.local.InternalPlayerEngine.EXOPLAYER -> context.getString(R.string.playback_engine_exoplayer)
            com.nuvio.tv.data.local.InternalPlayerEngine.MVP_PLAYER -> context.getString(R.string.playback_engine_mvplayer)
            com.nuvio.tv.data.local.InternalPlayerEngine.AUTO -> null
        },
        serverPlayback = serverPlaybackSummary()
    )
}

private fun String.safePlaybackEventsHost(): String {
    return runCatching {
        Uri.parse(this).host ?: substringBefore("://").takeIf { it.isNotBlank() } ?: "unknown"
    }.getOrDefault("unknown")
}

private fun formatTorrentSpeed(context: android.content.Context, bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> context.getString(R.string.unit_speed_mb_s, String.format("%.1f", bytesPerSec / 1_048_576.0))
        bytesPerSec >= 1_024 -> context.getString(R.string.unit_speed_kb_s, String.format("%.0f", bytesPerSec / 1_024.0))
        else -> context.getString(R.string.unit_speed_b_s, bytesPerSec)
    }
}

/**
 * Expires an uncommitted preview position ~3 s after the last
 * preview event, snapping the timeline back to the real position. Covers commits
 * swallowed by panels opening mid-gesture and controls-visibility changes.
 */
/**
 * Position the bar and time show for a pending seek: the keyframe the commit will land on when thumbnails are
 * active, else the target itself. Null when no seek is pending.
 */
internal fun PlayerRuntimeController.previewDisplayPosition(): Long? =
    pendingPreviewSeekPosition?.let { SeekThumbnails.landingFor(it) ?: it }

/** Seeks to the pending target, if any, and lets a held playback go on. */
private fun PlayerRuntimeController.commitPendingSeek() {
    val target = pendingPreviewSeekPosition
    val tapBase = scrubTapBase
    scrubTapBase = null
    if (target == null) {
        releaseScrubHold()
        return
    }
    pendingPreviewSeekExpiryJob?.cancel()
    // Land on the keyframe whose thumbnail was shown; a tap without one steps past the one playing.
    val landing = SeekLanding.of(
        targetMs = target,
        keyframeMs = SeekThumbnails.landingFor(target),
        tapDeltaMs = tapBase?.let { target - it },
        durationMs = currentPlaybackDurationMs()
    )
    seekPlaybackTo(landing.positionMs, landing.parameters)
    releaseScrubHold()
    updatePlaybackTimeline(currentPosition = landing.positionMs)
    pendingPreviewSeekPosition = null
    // Paused, the thumbnail of the landing stays up; playing on, nothing is left to show.
    val shownAfter = if (userPausedManually) target else null
    _uiState.update { it.copy(pendingPreviewSeekPosition = null, previewThumbPositionMs = shownAfter) }
    scheduleProgressSyncAfterSeek()
    if (_uiState.value.showControls) {
        showControlsTemporarily()
    } else {
        showSeekOverlayTemporarily()
    }
}

/**
 * Seek keys while playing settle first ([SeekSettle]); paused, in a party or without thumbnails they seek on release,
 * except on Confirm with OK, where only a party seeks on release.
 */
private fun PlayerRuntimeController.seekSettleApplies(): Boolean = SeekSettle.applies(
    open = seekSettle.open,
    userPaused = userPausedManually,
    inParty = partyBridge?.inParty == true,
    playing = hasActivePlayIntent(),
    thumbnails = SeekThumbnails.pausesPlaybackWhileScrubbing(),
    mode = seekingMode
)

/** The last key of a run came up: playback holds on the target for [SeekSettle.waitMs], then seeks. */
private fun PlayerRuntimeController.startSeekSettle() {
    pendingPreviewSeekExpiryJob?.cancel()
    holdPlaybackForScrub(always = true)
    // A glide publishes a few times a second; the bar and the thumbnail take its last position.
    pendingPreviewSeekPosition?.let { target ->
        _uiState.update { it.copy(pendingPreviewSeekPosition = target, previewThumbPositionMs = target) }
        updatePlaybackTimeline(
            currentPosition = previewDisplayPosition() ?: target,
            playbackPosition = currentPlaybackPositionMs() ?: _playbackTimeline.value.playbackPosition
        )
    }
    seekSettleJob?.cancel()
    seekSettleJob = scope.launch {
        kotlinx.coroutines.delay(SeekSettle.waitMs(afterHold = scrubHoldClock.releasedAfterHold, mode = seekingMode))
        seekSettleJob = null
        finishSeekSettle(seek = SeekSettle.seeksAfterWait(seekingMode), reason = "timeout")
    }
    if (_uiState.value.showControls) showControlsTemporarily() else showSeekOverlayTemporarily()
}

/** Ends an open settle: [seek] goes to the target, otherwise playback goes on where it was. False when none was open. */
internal fun PlayerRuntimeController.finishSeekSettle(seek: Boolean, reason: String): Boolean {
    if (!seekSettle.open) return false
    seekSettleJob?.let { job ->
        seekSettleJob = null
        job.cancel()
    }
    stopScrubGlide()
    val target = pendingPreviewSeekPosition
    val run = seekSettle.end() ?: return false
    android.util.Log.d(
        PlayerRuntimeController.TAG,
        "SEEK_SETTLE: ${if (seek && target != null) "seek" else "cancel"} reason=$reason targetMs=${target ?: -1L} " +
            "fromMs=${run.fromMs} taps=${run.presses}"
    )
    if (seek) {
        commitPendingSeek()
        return true
    }
    scrubTapBase = null
    pendingPreviewSeekExpiryJob?.cancel()
    pendingPreviewSeekPosition = null
    _uiState.update { it.copy(pendingPreviewSeekPosition = null, previewThumbPositionMs = null) }
    releaseScrubHold()
    currentPlaybackPositionMs()?.let { updatePlaybackTimeline(currentPosition = it) }
    if (!_uiState.value.showControls) {
        hideSeekOverlayJob?.cancel()
        _uiState.update { it.copy(showSeekOverlay = false) }
    }
    return true
}

/** Back during a settle: no seek, playback goes on where it was. */
internal fun PlayerRuntimeController.cancelSeekSettle(): Boolean = finishSeekSettle(seek = false, reason = "back")

/** Forgets an open settle without touching playback or the pending target. */
internal fun PlayerRuntimeController.dropSeekSettle() {
    seekSettleJob?.let { job ->
        seekSettleJob = null
        job.cancel()
    }
    seekSettle.end()
}

/**
 * Pauses playback while a seek key is held, so the CPU goes to the thumbnails (a small box decodes one in about
 * 0.6 s paused, up to 5 s while playing). Not a user pause; released at the commit, a cancel or the expiry.
 */
internal fun PlayerRuntimeController.holdPlaybackForScrub(always: Boolean = false) {
    if (scrubHoldPaused) return
    if (isUsingMpvEngine()) {
        if (!always || mpvView?.isPlayingNow() != true) return
        scrubHoldPaused = true
        setPlaybackPaused(true)
        android.util.Log.d("ThumbWorker", "scrub: playback held while seeking")
        return
    }
    if (!always && !SeekThumbnails.pausesPlaybackWhileScrubbing()) return
    val player = _exoPlayer ?: return
    if (!player.playWhenReady) return
    scrubHoldPaused = true
    player.pause()
    android.util.Log.d("ThumbWorker", "scrub: playback held while seeking")
}

/** Drops the thumbnail kept from a paused tap; a seek still in progress keeps its own. */
internal fun PlayerRuntimeController.dropPausedThumbnail() {
    if (pendingPreviewSeekPosition != null || _uiState.value.previewThumbPositionMs == null) return
    _uiState.update { it.copy(previewThumbPositionMs = null) }
}

/** Ends a [holdPlaybackForScrub] pause unless the user paused meanwhile. */
internal fun PlayerRuntimeController.releaseScrubHold() {
    if (!scrubHoldPaused) return
    scrubHoldPaused = false
    if (userPausedManually) return
    if (isUsingMpvEngine()) {
        if (mpvView?.isPlayingNow() == false) setPlaybackPaused(false)
    } else {
        _exoPlayer?.takeIf { !it.playWhenReady }?.play()
    }
    android.util.Log.d("ThumbWorker", "scrub: playback resumed")
}

/**
 * Moves the seek cursor every frame while the key is held, from its first repeat until the commit. Thumbnails,
 * the time and the timeline follow every [PlayerScrubRates.GLIDE_PUBLISH_MS]; [ScrubGlide] carries every frame.
 * On Fast the cursor follows the stepped target of [PlayerScrubRates.StepChase] until it gets there, even when a
 * TV sends the release late.
 */
internal fun PlayerRuntimeController.startScrubGlide(forward: Boolean, holdStart: Long, repeatAt: Long) {
    stopScrubGlide()
    scrubTapBase = null
    val end = (currentPlaybackDurationMs().takeIf { it > 0L } ?: Long.MAX_VALUE / 4).toDouble()
    holdPlaybackForScrub()
    var cursor = (pendingPreviewSeekPosition ?: currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L)
        .toDouble().coerceIn(0.0, end)
    scrubGlideForward = forward
    val chase = if (seekingMode == com.nuvio.tv.data.local.SeekingMode.FAST) {
        PlayerScrubRates.StepChase(cursor, end).also { it.repeat(repeatAt, forward) }
    } else null
    scrubStepChase = chase
    val filmMs = currentPlaybackDurationMs().coerceAtLeast(0L)
    ScrubGlide.positionMs = cursor.toLong()
    ScrubGlide.active = true
    publishScrubGlide(cursor.toLong())
    scrubGlideJob = scope.launch {
        var last = android.os.SystemClock.uptimeMillis()
        var lastPublish = last
        while (isActive) {
            kotlinx.coroutines.android.awaitFrame()
            val now = android.os.SystemClock.uptimeMillis()
            val dt = (now - last).coerceIn(0L, 100L)
            last = now
            if (chase != null) {
                val speed = chase.advance(dt)
                cursor = chase.cursorMs
                // The strip's spacing follows the fastest speed of this hold, so it never squeezes back while slowing.
                ScrubGlide.speed = maxOf(ScrubGlide.speed, speed.toFloat())
            } else {
                if (!scrubHoldClock.moving(now)) {
                    ScrubGlide.speed = 0f
                    continue
                }
                val speed = PlayerScrubRates.glideSpeed(now - holdStart, seekingMode, filmMs, scrubHoldClock.fromTv)
                cursor = (cursor + (if (forward) speed else -speed) * dt).coerceIn(0.0, end)
                ScrubGlide.speed = speed.toFloat()
            }
            ScrubGlide.positionMs = cursor.toLong()
            if (now - lastPublish >= PlayerScrubRates.GLIDE_PUBLISH_MS) {
                lastPublish = now
                publishScrubGlide(cursor.toLong())
            }
        }
    }
}

private fun PlayerRuntimeController.publishScrubGlide(positionMs: Long) {
    pendingPreviewSeekPosition = positionMs
    _uiState.update { it.copy(pendingPreviewSeekPosition = positionMs, previewThumbPositionMs = positionMs) }
    SeekThumbnails.notePriority(positionMs)
    schedulePendingPreviewSeekExpiry()
    updatePlaybackTimeline(
        currentPosition = positionMs,
        playbackPosition = currentPlaybackPositionMs() ?: _playbackTimeline.value.playbackPosition
    )
    if (_uiState.value.showControls) showControlsTemporarily() else showSeekOverlayTemporarily()
}

/** Ends a glide; the pending position stays for the commit (or the expiry). */
internal fun PlayerRuntimeController.stopScrubGlide() {
    val job = scrubGlideJob ?: return
    scrubGlideJob = null
    scrubStepChase = null
    job.cancel()
    if (ScrubGlide.active) pendingPreviewSeekPosition = ScrubGlide.positionMs
    ScrubGlide.stop()
}

private fun PlayerRuntimeController.undoScrubTap() {
    val before = scrubTapFrom
    pendingPreviewSeekPosition = before
    _uiState.update { it.copy(pendingPreviewSeekPosition = before, previewThumbPositionMs = before) }
    if (before == null) pendingPreviewSeekExpiryJob?.cancel()
    (before?.let { previewDisplayPosition() ?: it } ?: currentPlaybackPositionMs())
        ?.let { updatePlaybackTimeline(currentPosition = it, playbackPosition = currentPlaybackPositionMs() ?: it) }
}

internal fun PlayerRuntimeController.schedulePendingPreviewSeekExpiry() {
    pendingPreviewSeekExpiryJob?.cancel()
    pendingPreviewSeekExpiryJob = scope.launch {
        kotlinx.coroutines.delay(3_000L)
        if (pendingPreviewSeekPosition != null) {
            dropSeekSettle()
            stopScrubGlide()
            releaseScrubHold()
            pendingPreviewSeekPosition = null
            _uiState.update { it.copy(pendingPreviewSeekPosition = null, previewThumbPositionMs = null) }
            currentPlaybackPositionMs()?.let { updatePlaybackTimeline(currentPosition = it) }
        }
    }
}


/** Selected native ownership is independent of factory preference or HUD labels. */
internal fun PlayerRuntimeController.canChangePlaybackSpeed(): Boolean =
    isUsingMpvEngine() || (!nativeVideoSelection.isSelected && !_uiState.value.tunnelingEnabled &&
        _exoPlayer?.isTunnelingEnabled != true)

private fun PlayerRuntimeController.showPlaybackSpeedUnavailable() {
    _uiState.update { it.copy(showSpeedDialog = false, showAspectRatioIndicator = true,
        aspectRatioIndicatorText = context.getString(R.string.player_speed_unavailable)) }
    hideAspectRatioIndicatorJob?.cancel()
    hideAspectRatioIndicatorJob = scope.launch {
        delay(1500)
        _uiState.update { it.copy(showAspectRatioIndicator = false) }
    }
}

/** Lets Automatic speed-up start a remux on several downloads before its first byte, see [AutoSpeedup.guessBitrate]. */
internal fun PlayerRuntimeController.noteSpeedupHint(url: String) {
    AutoSpeedup.startHint(
        url,
        AutoSpeedup.guessBitrate(currentVideoSize, expectedRuntimeMinutes, currentFilename, _uiState.value.currentStreamName)
    )
}

/** A heavy film's first start waits for a few seconds of film, see [com.nuvio.tv.core.player.StartCushion]. */
internal fun PlayerRuntimeController.armStartCushion(url: String) {
    val cushion = (_loadControl as? com.nuvio.tv.core.player.StartCushionControl)?.startCushion ?: return
    val fileBps = fileBitrateBeforeBuild(url)
    val guessedBps = if (fileBps > 0L) 0L else
        AutoSpeedup.guessBitrate(currentVideoSize, expectedRuntimeMinutes, currentFilename, _uiState.value.currentStreamName)
    val heavy = com.nuvio.tv.core.player.StartCushion.isHeavy(fileBps, guessedBps, AutoSpeedupPolicy.START_THREE_BPS)
    val startMs = cushion.arm(heavy)
    if (startMs > 0) {
        Log.i(
            PlayerRuntimeController.TAG,
            "START_CUSHION: heavy film fileKbps=${fileBps / 1000} guessedKbps=${guessedBps / 1000} " +
                "startMs=$startMs (set=${cushion.userStartMs})"
        )
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun PlayerRuntimeController.sampleAutoSpeedup(player: Player, positionMs: Long) {
    val keeper = autoSpeedupKeeper()
    if (!AutoSpeedup.active) {
        if (keeper != null && keeper.lentBytes > 0L) keeper.setLentBytes(0L)
        return
    }
    val control = keeper?.control
    val bufferFull = control != null && control.allocatorTargetBytes() > 0 &&
        control.bufferAllocator.totalBytesAllocated >= control.allocatorTargetBytes() - FULL_MARGIN_BYTES
    val nowMs = android.os.SystemClock.elapsedRealtime()
    val free = if (bufferMemoryKeeper == null) {
        com.nuvio.tv.core.player.BufferSizing.read(context.applicationContext)
    } else {
        null
    }
    val userBudget = bufferMemoryKeeper?.budgetManaged == false
    val roomBytes = AutoSpeedup.roomBytes(nowMs) {
        free ?: com.nuvio.tv.core.player.BufferSizing.read(context.applicationContext)
    }
    val memoryShort = AutoSpeedupPolicy.memoryShort(
        warned = bufferMemoryKeeper?.let { it.pressureLevel > 0 },
        warnedCritical = bufferMemoryKeeper?.pressureLevel == com.nuvio.tv.core.player.BufferMemoryKeeper.LEVEL_CRITICAL,
        roomBytes = roomBytes,
        lowMemory = free?.lowMemory == true
    )
    // The thumbnails' memory floor only holds the read-ahead back while their screen holds the film; once it plays,
    // the buffer comes first and thumbnails decode only with the memory that is left.
    AutoSpeedup.updateReadAhead(roomBytes) {
        if (player.playWhenReady) 0L else com.nuvio.tv.core.player.thumbnail.SeekThumbnails.memoryFloorBytes()
    }
    val fileBitrate = AutoSpeedup.fileBitrate(
        PlaybackByteCounter.contentLengthFor(currentStreamUrl),
        currentVideoSize,
        player.duration
    )
    // The buffer lends nothing until the film is on screen and past its start cushion, and never so much that
    // fewer than a few seconds of film fit: a 5 s buffer against a 6 s cushion froze a 4K remux on a 2 GB box.
    if (keeper != null) {
        val cushionArmed = (_loadControl as? com.nuvio.tv.core.player.StartCushionControl)?.startCushion?.armed == true
        keeper.setLendingHeld(cushionArmed || !hasRenderedFirstFrame)
        keeper.setLendFloorBytes(
            com.nuvio.tv.core.player.BufferMemoryKeeper.lendFloorBytes(
                fileBitrate, maxOf(AutoSpeedup.pieceBytes, AutoSpeedup.activePieceBytes)
            )
        )
    }
    // A guessed start runs several downloads before the first sample; their memory comes off the buffer as usual.
    if (keeper != null && keeper.lentBytes == 0L && AutoSpeedup.downloads > 1) keeper.setLentBytes(AutoSpeedup.lentBytes())
    val before = AutoSpeedup.downloads
    val targetBytes = control?.allocatorTargetBytes()?.toLong() ?: 0L
    val maxBufferMs = control?.maxBufferMs?.toLong() ?: 0L
    val bufferTargetMs = AutoSpeedup.bufferTargetMs(maxBufferMs, targetBytes, fileBitrate)
    val changed = AutoSpeedup.onPlayerSample(
        AutoSpeedupPolicy.Sample(
            nowMs = nowMs,
            aheadMs = (player.bufferedPosition - positionMs).coerceAtLeast(0L),
            loading = player.isLoading,
            rebuffers = rebufferCount,
            bytes = 0L,
            memoryShort = memoryShort,
            playing = player.isPlaying,
            bufferFull = bufferFull,
            roomBytes = roomBytes,
            swapLow = AutoSpeedup.swapLow(nowMs) { com.nuvio.tv.core.player.thumbnail.MemoryGovernor.readMeminfo() },
            fileBitrate = fileBitrate,
            idleBytes = control?.bufferAllocator?.availableBytes?.toLong() ?: 0L,
            userBudget = userBudget,
            maxBufferMs = maxBufferMs,
            playerBytes = PlaybackByteCounter.snapshot().readBytes,
            bufferedMs = player.bufferedPosition,
            bufferTargetMs = bufferTargetMs,
            seekAtMs = lastSeekWallMs
        )
    )
    AutoSpeedup.logMemoryPlan(nowMs, control?.bufferAllocator?.totalBytesAllocated?.toLong() ?: 0L, targetBytes,
        bufferMemoryKeeper?.pressureLevel ?: 0, roomBytes)
    if (!changed) {
        // A new piece size changes what the downloads hold with the same count.
        val lent = AutoSpeedup.lentBytes()
        if (keeper != null && keeper.lentBytes > 0L && keeper.lentBytes != lent && AutoSpeedup.downloads > 1) {
            keeper.setLentBytes(lent)
        }
        return
    }
    if (AutoSpeedup.downloads > before) {
        keeper?.setLentBytes(AutoSpeedup.lentBytes())
    } else {
        // Give the buffer its memory back only once the pieces above the new count are gone.
        val keep = AutoSpeedup.downloads
        scope.launch(Dispatchers.IO) {
            ParallelRangeDataSource.trimRetainedSession(keepAhead = keep)
            keeper?.setLentBytes(AutoSpeedup.lentBytes())
        }
    }
}

/** The buffer keeper, or on stock buffers (which have none) one used only to lend memory to the extra downloads. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun PlayerRuntimeController.autoSpeedupKeeper(): com.nuvio.tv.core.player.BufferMemoryKeeper? {
    bufferMemoryKeeper?.let { return it }
    val control = _loadControl as? com.nuvio.tv.core.player.BufferBudgetControl ?: return null
    autoSpeedupLender?.takeIf { it.control === control }?.let { return it }
    return com.nuvio.tv.core.player.BufferMemoryKeeper(control, { null }).also { autoSpeedupLender = it }
}

private const val FULL_MARGIN_BYTES = 2 * 1024 * 1024
