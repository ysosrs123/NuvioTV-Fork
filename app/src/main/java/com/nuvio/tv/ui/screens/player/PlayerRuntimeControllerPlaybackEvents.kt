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
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.core.tracking.scrobbleDiagnosticIdentity
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.repository.PlaybackIssueErrorInput
import com.nuvio.tv.data.repository.PlaybackIssuePlaybackSettingsInput
import com.nuvio.tv.data.repository.PlaybackIssueReportInput
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import com.nuvio.tv.core.player.PlaceholderStreamPolicy
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import kotlinx.coroutines.launch

internal const val AUDIO_AMPLIFICATION_MIN_DB = 0
internal const val AUDIO_AMPLIFICATION_MAX_DB = 10
internal const val CENTER_MIX_LEVEL_MIN_DB = -10
internal const val CENTER_MIX_LEVEL_MAX_DB = 30
internal const val AUDIO_DELAY_MIN_MS = -3000
internal const val AUDIO_DELAY_MAX_MS = 3000
internal const val AUDIO_DELAY_STEP_MS = 25
internal const val WATCH_PROGRESS_SAVE_INTERVAL_MS = 90_000L

// nt15: after this long with no storm-recovery seek, the per-playback recovery
// attempt budget resets, so a distinct later storm cluster gets a fresh 2 attempts
// instead of being starved by a cap the earlier cold-start cluster already spent.
// Chosen > the D >=3s spacing so a normal 2-attempt cluster cannot self-reset
// mid-cluster; < the gap that separated the run-2 clusters (~10.7s).
internal const val TRUEHD_STORM_ATTEMPT_RESET_MS = 6_000L
// nt11 (0.8.2): shadow lock-snap classifier -- minimum one-tick forward stride
// treated as a snap suspect (10x a normal ~500 ms tick's advance).
internal const val SNAP_SHADOW_MIN_STRIDE_MS = 5_000L

// nt12 (0.8.2): a pending snap-recovery latch older than this is dropped --
// covers the 6 s budget reset plus spacing with margin, while guaranteeing a
// long-blocked recovery can never fire as a surprise rollback much later.
internal const val SNAP_RECOVERY_PENDING_TTL_MS = 15_000L

internal fun PlayerRuntimeController.applyAudioDelay(
    delayMs: Int,
    persistForCurrentRoute: Boolean = true
) {
    val clampedDelayMs = delayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    audioDelayUs.set(clampedDelayMs.toLong() * 1000L)
    _uiState.update { it.copy(audioDelayMs = clampedDelayMs) }
    if (isUsingMpvEngine()) {
        mpvView?.setAudioDelayMs(clampedDelayMs)
    }
    if (persistForCurrentRoute) {
        persistAudioDelayForCurrentRoute(clampedDelayMs)
    }
}

internal fun PlayerRuntimeController.skipActiveInterval(): Boolean {
    return skipInterval(_uiState.value.activeSkipInterval ?: return false)
}

internal fun PlayerRuntimeController.skipInterval(interval: SkipInterval): Boolean {
    val duration = currentPlaybackDurationMs().takeIf { it > 0 } ?: Long.MAX_VALUE
    val seekMs = if (interval.endTime == Double.MAX_VALUE) {
        duration
    } else {
        (interval.endTime * 1000).toLong()
    }
    seekPlaybackTo(seekMs.coerceAtMost(duration), SeekParameters.NEXT_SYNC)
    scheduleProgressSyncAfterSeek()
    _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = true) }
    return true
}

internal fun PlayerRuntimeController.applyAudioAmplification(db: Int) {
    val clampedDb = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    // Audio review F8: gain is a PCM processor — during bitstream bypass it is
    // a silent no-op, so the control reports unavailable instead of offering a
    // dead slider. MPV always decodes, so bypass only applies on ExoPlayer.
    val isAudioAmplificationAvailable =
        isUsingMpvEngine() || (_exoPlayer != null && !isAudioOutputBypassing)
    val wasActive = gainAudioProcessor.isGainEnabled()
    gainAudioProcessor.setGainDb(if (isAudioAmplificationAvailable) clampedDb else AUDIO_AMPLIFICATION_MIN_DB)
    val isActiveNow = gainAudioProcessor.isGainEnabled()

    if (wasActive != isActiveNow && !isUsingMpvEngine()) {
        playbackSpeedAwareAudioSink?.notifyAudioProcessingRequirementChanged()
        _exoPlayer?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().build()
        }
    }

    if (isUsingMpvEngine()) {
        mpvView?.applyAudioAmplificationDb(clampedDb)
    }
    _uiState.update {
        it.copy(
            audioAmplificationDb = clampedDb,
            isAudioAmplificationAvailable = isAudioAmplificationAvailable
        )
    }
}

internal fun PlayerRuntimeController.applyCenterMixLevel(db: Int) {
    val clampedDb = db.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    ffmpegAudioRenderer?.setCenterMixLevelDb(clampedDb)
    _uiState.update { state ->
        state.copy(centerMixLevelDb = clampedDb)
    }
}

internal fun PlayerRuntimeController.updateAudioControlAvailability(
    audioTracks: List<TrackInfo> = _uiState.value.audioTracks,
    selectedAudioIndex: Int = _uiState.value.selectedAudioTrackIndex
) {
    val selectedTrack = audioTracks.getOrNull(selectedAudioIndex)
    // Audio review F8: see applyAudioAmplification.
    val isAudioAmplificationAvailable =
        isUsingMpvEngine() || (_exoPlayer != null && !isAudioOutputBypassing)
    val isCenterMixAvailable =
        ffmpegAudioRenderer?.isCenterMixActive() == true && (selectedTrack?.channelCount ?: 0) > 2
    val clampedDb = _uiState.value.audioAmplificationDb
        .coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    gainAudioProcessor.setGainDb(
        if (isAudioAmplificationAvailable) clampedDb else AUDIO_AMPLIFICATION_MIN_DB
    )
    _uiState.update { state ->
        state.copy(
            isAudioAmplificationAvailable = isAudioAmplificationAvailable,
            isCenterMixAvailable = isCenterMixAvailable
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
    if (state.postPlayDismissedForCurrentEpisode &&
        streamAutoPlayNextEpisodeEnabledSetting &&
        state.nextEpisode?.hasAired == true &&
        nextEpisodeVideo != null
    ) {
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
 * 5c note: this 2:01 threshold is intentionally NOT aligned with
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
            // nt32: the MAT wrapper engages AFTER isPlaying flips true under AFR
            // (the play-before-configure ordering, third confirmed instance), so the
            // one-shot analytics writers evaluate isMatActive() while it is still
            // false and never re-fire. Lazily fill on the tick instead - ordering-
            // immune, lands within one tick of engagement, and the null guard keeps
            // any earlier writer authoritative.
            if (currentAudioPathDescription == null &&
                matRoutingAudioSink?.isMatActive() == true
            ) {
                currentAudioPathDescription =
                    "TrueHD \u2192 MAT passthrough, app-packed (IEC61937 192 kHz, 8ch)"
            }
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
                    val cacheBuffering = view.isPausedForCacheNow() || view.isCoreIdleNow()
                    var firstFrameReady = hasRenderedFirstFrame
                        if (!firstFrameReady) {
                            firstFrameReady = pos > 0L || (playingNow && !cacheBuffering && playerDuration > 0L)
                            if (firstFrameReady) {
                                hasRenderedFirstFrame = true
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
                    if (playerDuration > lastKnownDuration) {
                        lastKnownDuration = playerDuration
                    }
                    val displayPosition = pendingPreviewSeekPosition ?: pos
                    val playingForWatchClock = playingNow && !cacheBuffering
                    publishPlaybackTimeline(
                        currentPosition = displayPosition,
                        duration = playerDuration,
                        bufferedPosition = (pos + (view.demuxerCacheDurationSec() * 1000.0).toLong())
                            .coerceAtLeast(displayPosition),
                        playerReportsLive = view.isLiveStreamNow(),
                        isPlaying = playingForWatchClock
                    )
                    val nearEnd = playerDuration > 0L && pos >= (playerDuration - 500L)
                    val naturalEnded = !view.isLiveStreamNow() && nearEnd && shouldTreatAsNaturalPlaybackCompletion(
                        hasRenderedFirstFrame = firstFrameReady,
                        hasFatalError = !_uiState.value.error.isNullOrBlank(),
                        durationMs = playerDuration
                    )
                    val wasEnded = _uiState.value.playbackEnded
                    _uiState.update { state ->
                        state.copy(
                            isPlaying = playingNow,
                            isBuffering = !firstFrameReady || cacheBuffering,
                            showLoadingOverlay = if (state.loadingOverlayEnabled) !firstFrameReady else false,
                            // Snap the loading-logo fill to 100% once playback is
                            // ready so the logo finishes filling on dismissal.
                            loadingProgress = if (firstFrameReady && state.loadingProgress != null) 1f else state.loadingProgress,
                            playbackEnded = naturalEnded
                        )
                    }
                    updateMpvAvailableTracks()
                    updateActiveSkipInterval(pos)
                    if (!_playbackTimeline.value.isLive) {
                        evaluatePostPlayOverlayVisibility(
                            positionMs = pos,
                            durationMs = playerDuration
                        )
                    }
                    if (naturalEnded && !wasEnded) {
                        // Short placeholders never set naturalEnded, so they cannot mark
                        // watched or auto-advance (see #2819).
                        handleNaturalPlaybackEnded()
                    }
                }
                delay(500)
                continue
            }

            _exoPlayer?.let { player ->
                val pos = player.currentPosition.coerceAtLeast(0L)
                val playerDuration = player.duration
                if (playerDuration > lastKnownDuration) {
                    lastKnownDuration = playerDuration
                }
                // 5c: duration backstop. Content-length was cleared at READY (2a);
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

                // nt8: TrueHD startup-storm auto-recovery. The Amlogic MS12 TrueHD
                // bypass parser can start misaligned after a display-mode switch and
                // then hunts for a major sync indefinitely, consuming buffered audio
                // 3-4x faster than real time (the racing master clock is the visible
                // position jump). The proven cure is an in-place seek: it recreates
                // the AudioTrack on the settled system (reuse-on-flush is disabled),
                // after which MS12 locks instantly -- measured on device. Roll back by
                // the burned lead so the viewer resumes roughly where the storm began.
                // nt11: latch the player-timeline position on the FIRST tick that
                // observes an un-consumed storm (<=500ms after onset). D's spacing
                // gate can defer the actual recovery by ~3s, during which the racing
                // clock moves `pos` ~10s past onset; latching here captures onset
                // before that drift. Non-consuming peek -- the flag is cleared only
                // by consumeTruehdStormRecoverySignal() below.
                if (truehdStormOnsetPosMs < 0L &&
                    playbackSpeedAwareAudioSink?.isTruehdStormDetected() == true
                ) {
                    truehdStormOnsetPosMs = pos
                }
                // nt15: reset the spent per-playback budget after a clean interval,
                // so a distinct later storm cluster is not starved by the earlier
                // cluster's spent cap. Only acts when already capped; never touches
                // lastRecoveryAtMs (the >=3s spacing gate) or the onset latch.
                if (truehdStormRecoveryAttempts >= 2 &&
                    truehdStormLastRecoveryAtMs != 0L &&
                    android.os.SystemClock.elapsedRealtime() - truehdStormLastRecoveryAtMs >= TRUEHD_STORM_ATTEMPT_RESET_MS
                ) {
                    truehdStormRecoveryAttempts = 0
                }
                // nt14: corroborated early budget reset. SNAP_GATE proved the
                // spent cap was the only constraint holding back a cure in the
                // R2 warm-switch capture while both instruments already agreed
                // the failure was live. When the sink holds a preserved
                // detection AND the timeline classifier flagged a suspect
                // within 5 s AND the budget is spent, open the budget now
                // instead of waiting out the 6 s reset. Bounds: once per 15 s,
                // stand-down after 8 total recoveries this playback; the >=3 s
                // D-spacing still applies to the consume itself.
                // nt15: a LATCHED snap verdict (snapRecoveryPendingPosMs >= 0)
                // with a fresh suspect also opens the budget -- the undetected-
                // snap class queued 2.0 s behind the spent cap in the nt14
                // acceptance capture (SNAP_GATE, four consecutive ticks), and
                // the classifier's five-capture zero-false-positive record plus
                // the bounded consume make single-instrument opening safe.
                if (truehdStormRecoveryAttempts >= 2 &&
                    (playbackSpeedAwareAudioSink?.isTruehdStormDetected() == true ||
                        snapRecoveryPendingPosMs >= 0L) &&
                    android.os.SystemClock.elapsedRealtime() - snapLastSuspectWallMs <= 5_000L &&
                    android.os.SystemClock.elapsedRealtime() - snapEarlyResetLastAtMs >= 15_000L &&
                    stormRecoveryTotalThisPlayback < 8
                ) {
                    truehdStormRecoveryAttempts = 0
                    snapEarlyResetLastAtMs = android.os.SystemClock.elapsedRealtime()
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "SNAP_EARLY_RESET corroborated: " +
                            "sinceSuspectMs=${android.os.SystemClock.elapsedRealtime() - snapLastSuspectWallMs} " +
                            "detected=${playbackSpeedAwareAudioSink?.isTruehdStormDetected() == true} " +
                            "snapPendMs=$snapRecoveryPendingPosMs " +
                            "total=$stormRecoveryTotalThisPlayback"
                    )
                }
                if (_uiState.value.error.isNullOrBlank() && truehdStormRecoveryAttempts < 2 &&
                    android.os.SystemClock.elapsedRealtime() - truehdStormLastRecoveryAtMs >= 3_000L
                ) {
                    playbackSpeedAwareAudioSink?.consumeTruehdStormRecoverySignal()?.let { leadMs ->
                        truehdStormRecoveryAttempts += 1
                        stormRecoveryTotalThisPlayback += 1
                        truehdStormLastRecoveryAtMs = android.os.SystemClock.elapsedRealtime()
                        // nt15: a storm rollback supersedes any pending snap latch --
                        // stale pre-rollback timeline evidence must not fire a second
                        // rollback for the same event. Fresh events re-flag (proven
                        // across five captures).
                        snapRecoveryPendingPosMs = -1L
                        // nt11: roll back to the latched storm onset, not the raced
                        // consume-time pos. leadMs retained in the log for continuity.
                        val onsetPos = if (truehdStormOnsetPosMs >= 0L) truehdStormOnsetPosMs else pos
                        val target = (onsetPos - 500L).coerceAtLeast(0L)
                        Log.w(
                            PlayerRuntimeController.TAG,
                            "TRUEHD_STORM_RECOVERY: attempt=$truehdStormRecoveryAttempts " +
                                "leadMs=$leadMs onsetPos=${onsetPos}ms pos=${pos}ms seekTo=${target}ms"
                        )
                        player.seekTo(target)
                        // nt11: clear the latch so a second storm latches its own onset.
                        truehdStormOnsetPosMs = -1L
                    }
                }
                val displayPosition = pendingPreviewSeekPosition ?: pos
                // nt11 (0.8.2): SHADOW lock-snap classifier -- log-only, acts on
                // nothing. A class-3 MS12 lock-snap steps the player timeline
                // forward in one stride with NO discontinuity event (media3 is
                // following the poisoned master clock); every legitimate jump
                // (seek, scrub, auto-transition) announces itself via
                // onPositionDiscontinuity first. Wiring to the existing recovery
                // machinery is a later build, gated on this shadow's capture.
                run {
                    val snapNowWall = android.os.SystemClock.elapsedRealtime()
                    val snapLastPos = snapShadowLastTickPosMs
                    val snapLastWall = snapShadowLastTickWallMs
                    if (snapLastPos >= 0L && snapLastWall != 0L) {
                        val strideMs = pos - snapLastPos
                        val wallMs = snapNowWall - snapLastWall
                        val discExplained = snapShadowLastDiscontinuityWallMs >= snapLastWall
                        if (strideMs >= SNAP_SHADOW_MIN_STRIDE_MS &&
                            wallMs in 50L..5_000L &&
                            !discExplained &&
                            _uiState.value.playbackSpeed == 1.0f
                        ) {
                            Log.w(
                                PlayerRuntimeController.TAG,
                                "SNAP_TRACE SUSPECT pos=${pos}ms last=${snapLastPos}ms " +
                                    "strideMs=$strideMs wallMs=$wallMs " +
                                    "sinceDiscMs=${snapNowWall - snapShadowLastDiscontinuityWallMs} " +
                                    "buffering=${_uiState.value.isBuffering}"
                            )
                            // nt14: stamp every suspect verdict (any disposition)
                            // -- the early-reset corroboration window reads this.
                            snapLastSuspectWallMs = snapNowWall
                            // nt12: latch the pre-snap position for recovery, gated
                            // to the TrueHD passthrough context and deferring to the
                            // sink detector whenever its signal is live. First flag
                            // wins; consumed below through the shared budget.
                            // nt13: claim an ORPHANED onset latch -- onset >= 0 with
                            // the detection flag false means the storm path can never
                            // consume (its signal was wiped); roll back to the onset,
                            // which is earlier than the tick latch and so safer.
                            if (snapRecoveryPendingPosMs < 0L &&
                                playbackSpeedAwareAudioSink?.isTruehdStormDetected() != true &&
                                playbackSpeedAwareAudioSink?.isTruehdPassthroughActive() == true
                            ) {
                                if (truehdStormOnsetPosMs >= 0L) {
                                    snapRecoveryPendingPosMs = truehdStormOnsetPosMs
                                    truehdStormOnsetPosMs = -1L
                                    Log.w(
                                        PlayerRuntimeController.TAG,
                                        "SNAP_ORPHAN_CLAIM onsetPos=${snapRecoveryPendingPosMs}ms"
                                    )
                                } else {
                                    snapRecoveryPendingPosMs = snapLastPos
                                }
                                snapRecoveryPendingAtWallMs = snapNowWall
                            }
                        }
                    }
                    snapShadowLastTickPosMs = pos
                    snapShadowLastTickWallMs = snapNowWall
                }

                // nt13: consume-gate visibility while anything is pending --
                // names the blocker on any future stall instead of leaving it
                // inferred. Strip with the rest of the diagnostics.
                run {
                    val snapDetectedNow = playbackSpeedAwareAudioSink?.isTruehdStormDetected() == true
                    if (snapDetectedNow || truehdStormOnsetPosMs >= 0L || snapRecoveryPendingPosMs >= 0L) {
                        Log.w(
                            PlayerRuntimeController.TAG,
                            "SNAP_GATE attempts=$truehdStormRecoveryAttempts " +
                                "sinceRecMs=${android.os.SystemClock.elapsedRealtime() - truehdStormLastRecoveryAtMs} " +
                                "errBlank=${_uiState.value.error.isNullOrBlank()} " +
                                "detected=$snapDetectedNow onsetMs=$truehdStormOnsetPosMs " +
                                "snapPendMs=$snapRecoveryPendingPosMs pos=${pos}ms"
                        )
                    }
                }
                // nt12 (0.8.2): drop a stale pending latch before consuming.
                if (snapRecoveryPendingPosMs >= 0L &&
                    android.os.SystemClock.elapsedRealtime() - snapRecoveryPendingAtWallMs > SNAP_RECOVERY_PENDING_TTL_MS
                ) {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "SNAP_RECOVERY_EXPIRED latchedPos=${snapRecoveryPendingPosMs}ms"
                    )
                    snapRecoveryPendingPosMs = -1L
                }
                // nt12 (0.8.2): consume a pending snap-recovery latch through the
                // SHARED storm budget -- same cap (2), same nt15 6 s reset (already
                // applied earlier this tick), same >=3 s spacing, same rollback
                // shape. The recovery seek is the proven cure (in-place seek,
                // AudioTrack recreated, MS12 locks instantly), and its
                // discontinuity stamps the classifier token, so the rollback
                // itself can never re-flag.
                if (snapRecoveryPendingPosMs >= 0L &&
                    _uiState.value.error.isNullOrBlank() &&
                    truehdStormRecoveryAttempts < 2 &&
                    android.os.SystemClock.elapsedRealtime() - truehdStormLastRecoveryAtMs >= 3_000L
                ) {
                    truehdStormRecoveryAttempts += 1
                    stormRecoveryTotalThisPlayback += 1
                    truehdStormLastRecoveryAtMs = android.os.SystemClock.elapsedRealtime()
                    val snapTarget = (snapRecoveryPendingPosMs - 500L).coerceAtLeast(0L)
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "SNAP_RECOVERY: attempt=$truehdStormRecoveryAttempts " +
                            "latchedPos=${snapRecoveryPendingPosMs}ms pos=${pos}ms seekTo=${snapTarget}ms"
                    )
                    snapRecoveryPendingPosMs = -1L
                    player.seekTo(snapTarget)
                }
                publishPlaybackTimeline(
                    currentPosition = displayPosition,
                    duration = playerDuration.coerceAtLeast(0L),
                    bufferedPosition = player.bufferedPosition.coerceAtLeast(displayPosition),
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
                        Log.d(PlayerRuntimeController.TAG, "BUFFER: ahead=${bufAhead}s, loading=$loading, heap=$usedMb/${maxMb}MB, pos=${pos / 1000}s")
                        
                        if (NuvioExoPlayerPerformanceHelper.shouldLogMemoryFootprint()) {
                            val defaultAllocator = _loadControl?.allocator as? androidx.media3.exoplayer.upstream.DefaultAllocator
                            val totalFootprintBytes = defaultAllocator?.let { allocator ->
                                try {
                                    val method = allocator.javaClass.getMethod("getMemoryFootprint")
                                    method.invoke(allocator) as? Int ?: 0
                                } catch (e: Exception) {
                                    0
                                }
                            } ?: 0
                            val totalActiveBytes = defaultAllocator?.totalBytesAllocated ?: 0
                            val footprintMb = totalFootprintBytes / (1024 * 1024)
                            val activeMb = totalActiveBytes / (1024 * 1024)
                            Log.d("ExoMemory", "Off-heap OS ahead: $footprintMb MB, active: $activeMb MB")
                        }
                    }
                }
            }
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
    ).copy(startupStages = loadingInput.events)
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
        // stored flag is not wired to the engine, so reporting it made every issue
        // report understate back-buffer retention.
        retainBackBufferFromKeyframe = PlayerRuntimeController.ENGINE_RETAIN_BACK_BUFFER_FROM_KEYFRAME,
        parallelNetworkEnabled = settings.parallelNetworkEnabled,
        bufferBudgetManaged = settings.bufferBudgetManaged,
        allowLargeTargetBuffer = settings.allowLargeTargetBuffer,
        vodCacheEnabled = settings.vodCacheEnabled,
        vodCacheSizeMode = settings.vodCacheSizeMode.name,
        vodCacheSizeMb = settings.vodCacheSizeMb,
        useParallelConnections = settings.useParallelConnections,
        parallelConnectionCount = settings.parallelConnectionCount,
        parallelChunkSizeKb = settings.parallelChunkSizeKb,
        enableHttp2 = settings.enableHttp2,
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
    val currentPosition = currentPlaybackPositionMs() ?: return
    val duration = getEffectiveDuration(currentPosition)
    // Don't save progress for very short streams (< 2:01) — these are
    // typically error/warning messages or "stream not ready" placeholders that
    // would incorrectly mark content as watched when the user exits.
    if (isShortPlaceholderDuration(duration)) return

    if (kotlin.math.abs(currentPosition - lastSavedPosition) >= saveThresholdMs) {
        lastSavedPosition = currentPosition
        saveWatchProgressInternal(currentPosition, duration, syncRemote = false)
    }
}

internal fun PlayerRuntimeController.saveWatchProgress() {
    if (!hasRenderedFirstFrame) return
    val currentPosition = currentPlaybackPositionMs() ?: return
    val duration = getEffectiveDuration(currentPosition)
    if (isShortPlaceholderDuration(duration)) return
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

private fun PlayerRuntimeController.isShortPlaceholderStream(): Boolean {
    val position = currentPlaybackPositionMs() ?: return false
    return isShortPlaceholderDuration(getEffectiveDuration(position))
}

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
    if (!shouldTreatAsNaturalPlaybackCompletion(
            hasRenderedFirstFrame = hasRenderedFirstFrame,
            hasFatalError = hasFatalError,
            durationMs = duration
        )
    ) {
        Log.i(
            PlayerRuntimeController.TAG,
            "Ignoring non-natural ENDED: firstFrame=$hasRenderedFirstFrame " +
                "error=$hasFatalError durationMs=$duration positionMs=$position"
        )
        // Prevent PlayerScreen from dispatching onPlaybackEnded / next-episode navigation.
        _uiState.update { it.copy(playbackEnded = false) }
        nextEpisodeAutoPlayJob?.cancel()
        nextEpisodeAutoPlayJob = null
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

internal fun PlayerRuntimeController.saveWatchProgressInternal(position: Long, duration: Long, syncRemote: Boolean = true) {
    if (contentType.equals("cloud", ignoreCase = true)) {
        saveCloudLibraryProgress(position, duration, completed = false)
        return
    }
    val parentContentId = contentId?.takeIf { it.isNotEmpty() } ?: return
    val parentContentType = contentType?.takeIf { it.isNotEmpty() } ?: return

    if (position < 1000) return

    val fallbackPercent = if (duration <= 0L) 5f else null

    val progress = WatchProgress(
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
        lastWatched = System.currentTimeMillis(),
        progressPercent = fallbackPercent
    )

    scope.launch(kotlinx.coroutines.NonCancellable) {
        val effectiveContentId = watchProgressRepository.normalizeParentContentId(
            parentContentId = progress.contentId,
            videoId = progress.videoId
        )
        val normalizedProgress = progress.copy(contentId = effectiveContentId)
        if (normalizedProgress.isCompleted()) {
            if (!hasMarkedCurrentEpisodeCompleted) {
                hasMarkedCurrentEpisodeCompleted = true
                watchProgressRepository.markAsCompleted(
                    normalizedProgress,
                    broadcastTrackingHistory = false
                )
            }
            runCatching { tvRecommendationManager.onProgressRemoved(normalizedProgress.contentId) }
        } else {
            watchProgressRepository.saveProgress(normalizedProgress, syncRemote = syncRemote)
            runCatching { tvRecommendationManager.updateSingleWatchNextProgram(normalizedProgress) }
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
    logScrobbleDiagnostic("item_refreshed")
}

internal fun PlayerRuntimeController.buildScrobbleItem(): TrackingMediaReference? {
    val rawContentId = contentId ?: return null
    val reference = buildTrackingMediaReference(
        contentType = contentType ?: "movie",
        parentMetaId = rawContentId,
        videoId = currentVideoId,
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
    if (isShortPlaceholderStream()) {
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
        val progressPercent = currentPlaybackProgressPercent()
        logScrobbleDiagnostic("start_dispatching", "requestGeneration=$requestGeneration progress=$progressPercent")
        val failures = trackingScrobbleCoordinator.scrobble(
            action = TrackingScrobbleAction.START,
            event = TrackingScrobbleEvent(item, progressPercent.toDouble())
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
    if (isShortPlaceholderStream()) {
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
    scope.launch(kotlinx.coroutines.NonCancellable) {
        logScrobbleDiagnostic("stop_dispatching", "progress=$percent")
        val failures = trackingScrobbleCoordinator.scrobble(
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(item, percent.toDouble())
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
    if (isShortPlaceholderStream()) {
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
    scope.launch(kotlinx.coroutines.NonCancellable) {
        logScrobbleDiagnostic("pause_dispatching", "progress=$percent")
        val failures = trackingScrobbleCoordinator.scrobble(
            action = TrackingScrobbleAction.PAUSE,
            event = TrackingScrobbleEvent(item, percent.toDouble())
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
    if (isShortPlaceholderStream()) return
    val item = currentScrobbleItem ?: return
    if (!hasRequestedScrobbleStartForCurrentItem) return
    scope.launch {
        trackingScrobbleCoordinator.scrobbleSeek(
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(item, progressPercent.toDouble())
        )
        if (isPlaybackCurrentlyPlaying()) {
            trackingScrobbleCoordinator.scrobbleSeek(
                action = TrackingScrobbleAction.START,
                event = TrackingScrobbleEvent(item, currentPlaybackProgressPercent().toDouble())
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
            !_uiState.value.showEpisodesPanel && !_uiState.value.showSourcesPanel &&
            !_uiState.value.showStreamInfoOverlay) {
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
    if (isUsingMpvEngine()) {
        mpvView?.setSubtitleDelayMs(newDelayMs)
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
                showSubtitleDelayOverlay = false,
                showControls = true
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
    pauseOverlayJob?.cancel()

    if (!_uiState.value.pauseOverlayEnabled || !hasRenderedFirstFrame || !userPausedManually) {
        _uiState.update { it.copy(showPauseOverlay = false) }
        return
    }

    _uiState.update { it.copy(showPauseOverlay = false) }
    pauseOverlayJob = scope.launch {
        delay(pauseOverlayDelayMs)
        val s = _uiState.value
        val anyPanelOpen = s.showSubtitleOverlay || s.showSubtitleStylePanel ||
            s.showSpeedDialog || s.showMoreDialog || s.showEpisodesPanel ||
            s.showSourcesPanel || s.showAudioOverlay || s.showStreamInfoOverlay ||
            s.showSubtitleTimingDialog || s.showSubtitleDelayOverlay
        if (!s.isPlaying && s.pauseOverlayEnabled && s.error == null && !anyPanelOpen) {
            _uiState.update { it.copy(showPauseOverlay = true, showControls = false) }
        }
    }
}

internal fun PlayerRuntimeController.cancelPauseOverlay() {
    pauseOverlayJob?.cancel()
    pauseOverlayJob = null
    _uiState.update { it.copy(showPauseOverlay = false) }
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

fun PlayerRuntimeController.onEvent(event: PlayerEvent) {
    if (event != PlayerEvent.OnParentalGuideHide) {
        onUserInteraction()
    }
    when (event) {
        PlayerEvent.OnPlayPause -> {
            if (isUsingMpvEngine()) {
                val playing = isPlaybackCurrentlyPlaying()
                if (playing) {
                    userPausedManually = true
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
                        player.pause()
                        schedulePauseOverlay()
                        // nt6: a parked auto-restore subtitle attaches here,
                        // while paused, so the reload lands invisibly.
                        maybeAttachDeferredAddonSubtitle()
                    } else {
                        userPausedManually = false
                        cancelPauseOverlay()
                        player.play()
                    }
                }
            }
            showControlsTemporarily()
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
            pendingPreviewSeekPosition = null
            _uiState.update { it.copy(pendingPreviewSeekPosition = null, previewThumbPositionMs = null) }
            val current = currentPlaybackPositionMs() ?: 0L
            val maxDuration = currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
            val target = (current + event.deltaMs)
                .coerceAtLeast(0L)
                .coerceAtMost(maxDuration)
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
        is PlayerEvent.OnPreviewSeekBy -> {
            if (_playbackTimeline.value.isLive) return
            val maxDuration = currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
            val basePosition = pendingPreviewSeekPosition ?: currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L
            val target = (basePosition + event.deltaMs)
                .coerceAtLeast(0L)
                .coerceAtMost(maxDuration)
            pendingPreviewSeekPosition = target
            _uiState.update { it.copy(pendingPreviewSeekPosition = target, previewThumbPositionMs = target) }
            // Build 12b (Lever 1): tell the thumbnail worker to prioritise this bucket.
            SeekThumbnails.notePriority(target)
            schedulePendingPreviewSeekExpiry()
            updatePlaybackTimeline(currentPosition = target)
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        PlayerEvent.OnCommitPreviewSeek -> {
            if (_playbackTimeline.value.isLive) return
            val target = pendingPreviewSeekPosition
            if (target != null) {
                pendingPreviewSeekExpiryJob?.cancel()
                seekPlaybackTo(target, SeekParameters.CLOSEST_SYNC)
                updatePlaybackTimeline(currentPosition = target)
                pendingPreviewSeekPosition = null
                _uiState.update { it.copy(pendingPreviewSeekPosition = null, previewThumbPositionMs = target) }
                scheduleProgressSyncAfterSeek()
                if (_uiState.value.showControls) {
                    showControlsTemporarily()
                } else {
                    showSeekOverlayTemporarily()
                }
            }
        }
        is PlayerEvent.OnSeekTo -> {
            if (_playbackTimeline.value.isLive) return
            pendingPreviewSeekPosition = null
            _uiState.update { it.copy(pendingPreviewSeekPosition = null, previewThumbPositionMs = null) }
            seekPlaybackTo(event.position, SeekParameters.CLOSEST_SYNC)
            updatePlaybackTimeline(currentPosition = event.position)
            scheduleProgressSyncAfterSeek()
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        is PlayerEvent.OnSelectAudioTrack -> {
            logSwitchTrace(
                stage = "event-select-audio",
                message = "index=${event.index}"
            )
            rememberAudioSelection(event.index)
            // Tunnelled playback: in-place AudioTrack recreation inside a live
            // tunnel latches bad frame pacing on some vendor HALs (Prism+
            // report). Rebuild at position instead; no-op when tunneling off.
            if (!maybeRebuildForTunneledAudioSwitch(event.index)) {
                selectAudioTrack(event.index)
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
        is PlayerEvent.OnSetCenterMixLevelDb -> {
            val clampedDb = event.db.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
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
            rememberInternalSubtitleSelection(event.index)
            selectSubtitleTrack(event.index)
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = true,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true,
                    selectedAddonSubtitle = null
                )
            }
        }
        PlayerEvent.OnDisableSubtitles -> {
            logSwitchTrace(
                stage = "event-disable-subtitles",
                message = "selectedSubtitleIndex=${_uiState.value.selectedSubtitleTrackIndex}"
            )
            autoSubtitleSelected = true
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            resetSubtitleAutoSyncState()
            rememberSubtitleDisabled()
            disableSubtitles()
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = true,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true,
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
            _uiState.update {
                it.copy(
                    showSubtitleOverlay = true,
                    showSubtitleStylePanel = false,
                    showSubtitleTimingDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        is PlayerEvent.OnSetPlaybackSpeed -> {
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
            val state = _uiState.value
            if (state.tunnelingEnabled) {
                _uiState.update {
                    it.copy(
                        showAspectRatioIndicator = true,
                        aspectRatioIndicatorText = context.getString(R.string.player_aspect_tunneling_unavailable)
                    )
                }
                hideAspectRatioIndicatorJob?.cancel()
                hideAspectRatioIndicatorJob = scope.launch {
                    delay(1500)
                    _uiState.update { it.copy(showAspectRatioIndicator = false) }
                }
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
            loadStreamsForEpisode(event.video)
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
            hasRetriedCurrentStreamAfter416 = false
            playbackIssueReportRequestVersion.incrementAndGet()
            resetErrorRetryState()
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
        is PlayerEvent.OnSetSubtitleSize -> {
            scope.launch { playerSettingsDataStore.setSubtitleSize(event.size) }
        }
        is PlayerEvent.OnSetSubtitleTextColor -> {
            scope.launch { playerSettingsDataStore.setSubtitleTextColor(event.color) }
        }
        is PlayerEvent.OnSetSubtitleBold -> {
            scope.launch { playerSettingsDataStore.setSubtitleBold(event.bold) }
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
            scope.launch {
                val defaults = SubtitleStyleSettings()
                playerSettingsDataStore.setSubtitleSize(defaults.size)
                playerSettingsDataStore.setSubtitleTextColor(defaults.textColor)
                playerSettingsDataStore.setSubtitleBold(defaults.bold)
                playerSettingsDataStore.setSubtitleOutlineEnabled(defaults.outlineEnabled)
                playerSettingsDataStore.setSubtitleOutlineColor(defaults.outlineColor)
                playerSettingsDataStore.setSubtitleOutlineWidth(defaults.outlineWidth)
                playerSettingsDataStore.setSubtitleVerticalOffset(defaults.verticalOffset)
                playerSettingsDataStore.setSubtitleBackgroundColor(defaults.backgroundColor)
            }
        }
        PlayerEvent.OnToggleAspectRatio -> {
            val state = _uiState.value
            if (state.tunnelingEnabled) {
                _uiState.update {
                    it.copy(
                        showAspectRatioIndicator = true,
                        aspectRatioIndicatorText = context.getString(R.string.player_aspect_tunneling_unavailable)
                    )
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
        PlayerEvent.OnTogglePlaybackStats -> {
            _uiState.update { it.copy(showPlaybackStatsOverlay = !it.showPlaybackStatsOverlay) }
        }
        PlayerEvent.OnTogglePlayerStatsHud -> {
            // Writing the setting here would hide the button along with the overlay and leave no way
            // back without going to settings mid playback.
            _uiState.update { it.copy(playerStatsHudVisible = !it.playerStatsHudVisible) }
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
        CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
            ?: CustomDefaultTrackNameProvider.formatNameFromMime(format.codecs)
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
        }
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
 * Seek review F3: expire an uncommitted preview position ~3 s after the last
 * preview event, snapping the timeline back to the real position. Covers commits
 * swallowed by panels opening mid-gesture and controls-visibility changes.
 */
internal fun PlayerRuntimeController.schedulePendingPreviewSeekExpiry() {
    pendingPreviewSeekExpiryJob?.cancel()
    pendingPreviewSeekExpiryJob = scope.launch {
        kotlinx.coroutines.delay(3_000L)
        if (pendingPreviewSeekPosition != null) {
            pendingPreviewSeekPosition = null
            _uiState.update { it.copy(pendingPreviewSeekPosition = null, previewThumbPositionMs = null) }
            currentPlaybackPositionMs()?.let { updatePlaybackTimeline(currentPosition = it) }
        }
    }
}
