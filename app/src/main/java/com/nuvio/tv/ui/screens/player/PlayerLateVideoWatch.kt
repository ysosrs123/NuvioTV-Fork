package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.onSeekForLateVideoWatch() {
    val afterResync = lateVideoResyncPending
    lateVideoResyncPending = false
    lateVideoWatchJob?.cancel()
    lateVideoWatchJob = null
    if (lateVideoResyncFailed || isUsingMpvEngine()) return
    val player = _exoPlayer ?: return
    if (player.isTunnelingEnabled || !currentStreamHasVideoTrack) return
    if (nativeVideoSelection.isSelected || partyBridge?.inParty == true) return
    if (!player.isCurrentMediaItemSeekable || player.isCurrentMediaItemLive) return

    lateVideoWatchJob = scope.launch {
        delay(PlayerLateVideoPolicy.GRACE_MS)
        if (_exoPlayer !== player || isReleasingPlayer) return@launch
        val decoderName = playbackAnalyticsDiagnostics.hudSample().videoDecoderName
        if (!PlayerLateVideoPolicy.isHardwareDecoder(decoderName)) return@launch
        var state = PlayerLateVideoPolicy.State()
        val firstCounters = player.videoDecoderCounters ?: return@launch
        firstCounters.ensureUpdated()
        var lastRendered = firstCounters.renderedOutputBufferCount
        var lastDropped = firstCounters.droppedBufferCount
        var wasPlaying = false
        while (isActive) {
            val sampleStartMs = SystemClock.elapsedRealtime()
            delay(PlayerLateVideoPolicy.CHECK_MS)
            if (_exoPlayer !== player || isReleasingPlayer) return@launch
            val counters = player.videoDecoderCounters ?: return@launch
            counters.ensureUpdated()
            val rendered = counters.renderedOutputBufferCount
            val dropped = counters.droppedBufferCount
            val frameRate = player.videoFormat?.frameRate ?: Format.NO_VALUE.toFloat()
            if (frameRate != lateVideoBaselineFormatFps) {
                lateVideoBaselineFps = null
                lateVideoBaselineFormatFps = frameRate
            }
            val playingNow = player.isPlaying && player.playbackState == Player.STATE_READY &&
                player.playbackParameters.speed == 1f
            val sample = PlayerLateVideoPolicy.Sample(
                playing = playingNow && wasPlaying && lateVideoLastNotPlayingMs < sampleStartMs,
                rendered = (rendered - lastRendered).coerceAtLeast(0),
                dropped = (dropped - lastDropped).coerceAtLeast(0),
                baselineFps = lateVideoBaselineFps,
            )
            wasPlaying = playingNow
            lastRendered = rendered
            lastDropped = dropped
            val step = PlayerLateVideoPolicy.step(state, sample)
            state = step.state
            when (val decision = step.decision) {
                PlayerLateVideoPolicy.Decision.Continue -> Unit
                PlayerLateVideoPolicy.Decision.Stop -> return@launch
                is PlayerLateVideoPolicy.Decision.Healthy -> {
                    lateVideoBaselineFps = decision.fps
                    return@launch
                }
                PlayerLateVideoPolicy.Decision.Resync -> {
                    val positionMs = player.currentPosition
                    if (afterResync) {
                        lateVideoResyncFailed = true
                        Log.w(
                            PlayerRuntimeController.TAG,
                            "LATE_VIDEO: still slow after a resync, leaving this stream alone " +
                                "rendered=${sample.rendered} dropped=${sample.dropped} baselineFps=$lateVideoBaselineFps"
                        )
                        return@launch
                    }
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "LATE_VIDEO: picture slow after a seek, seeking again at ${positionMs}ms " +
                            "rendered=${sample.rendered} dropped=${sample.dropped} baselineFps=$lateVideoBaselineFps"
                    )
                    queuePlaybackRawEventLine(
                        "late_video_resync positionMs=$positionMs rendered=${sample.rendered} dropped=${sample.dropped}"
                    )
                    lateVideoResyncPending = true
                    player.seekTo(positionMs + 1L)
                    return@launch
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.resetLateVideoWatch() {
    lateVideoWatchJob?.cancel()
    lateVideoWatchJob = null
    lateVideoBaselineFps = null
    lateVideoBaselineFormatFps = Format.NO_VALUE.toFloat()
    lateVideoResyncPending = false
    lateVideoResyncFailed = false
}
