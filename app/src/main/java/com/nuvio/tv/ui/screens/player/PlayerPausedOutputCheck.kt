package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Some TV audio outputs keep playing after the first pause of a stream, and a tunnelled picture
 * follows them. A seek empties the output, the same as a quick play and pause.
 */
internal object PausedOutputCheck {
    const val FIRST_READ_DELAY_MS = 400L
    const val STEP_MS = 500L
    const val MAX_STEPS = 5
    const val MIN_FRAMES = 2
    const val MIN_AUDIO_MS = 300L

    data class Sample(val audio: AudioOutputTimestamp?, val renderedFrames: Int?)

    data class Result(val audioAdvancedMs: Long?, val framesAdvanced: Int?, val stillPlaying: Boolean)

    fun evaluate(first: Sample, second: Sample): Result {
        val audioAdvancedMs = if (first.audio != null && second.audio != null &&
            first.audio.trackId == second.audio.trackId
        ) {
            (second.audio.positionUs - first.audio.positionUs) / 1000L
        } else {
            null
        }
        val framesAdvanced = if (first.renderedFrames != null && second.renderedFrames != null) {
            second.renderedFrames - first.renderedFrames
        } else {
            null
        }
        val audioRuns = audioAdvancedMs != null && audioAdvancedMs >= MIN_AUDIO_MS
        val pictureRuns = framesAdvanced != null && framesAdvanced >= MIN_FRAMES
        return Result(audioAdvancedMs, framesAdvanced, audioRuns || pictureRuns)
    }
}

internal fun PlayerRuntimeController.schedulePausedOutputCheck() {
    cancelPausedOutputCheck()
    val player = _exoPlayer ?: return
    if (!pausedOutputCheckApplies(player)) return
    pausedOutputCheckJob = scope.launch {
        delay(PausedOutputCheck.FIRST_READ_DELAY_MS)
        val first = samplePausedOutput(player)
        var result = PausedOutputCheck.evaluate(first, first)
        var stillPaused = true
        var steps = 0
        while (steps < PausedOutputCheck.MAX_STEPS && stillPaused && !result.stillPlaying) {
            delay(PausedOutputCheck.STEP_MS)
            if (_exoPlayer !== player || isReleasingPlayer) return@launch
            steps++
            stillPaused = pausedOutputCheckApplies(player)
            result = PausedOutputCheck.evaluate(first, samplePausedOutput(player))
        }
        val positionMs = player.currentPosition
        Log.i(
            PlayerRuntimeController.TAG,
            "PAUSE_CHECK: stillPlaying=${result.stillPlaying} audioAdvancedMs=${result.audioAdvancedMs} " +
                "framesAdvanced=${result.framesAdvanced} afterMs=${PausedOutputCheck.FIRST_READ_DELAY_MS + steps * PausedOutputCheck.STEP_MS} " +
                "stillPaused=$stillPaused playWhenReady=${player.playWhenReady} state=${player.playbackState} " +
                "userPaused=$userPausedManually tunnel=${player.isTunnelingEnabled} " +
                "direct=${playbackSpeedAwareAudioSink?.isDirectPlaybackActive()} positionMs=$positionMs"
        )
        // Outside the tunnel the picture cannot follow a running output, so the line above is enough.
        if (stillPaused && result.stillPlaying && player.isTunnelingEnabled) {
            queuePlaybackRawEventLine(
                "pause_check_reseek audioAdvancedMs=${result.audioAdvancedMs} framesAdvanced=${result.framesAdvanced}"
            )
            // A seek to the very same millisecond is skipped by the player.
            player.seekTo(positionMs + 1L)
        }
    }
}

internal fun PlayerRuntimeController.cancelPausedOutputCheck() {
    pausedOutputCheckJob?.cancel()
    pausedOutputCheckJob = null
}

private fun PlayerRuntimeController.samplePausedOutput(player: ExoPlayer): PausedOutputCheck.Sample {
    val counters = player.videoDecoderCounters
    counters?.ensureUpdated()
    return PausedOutputCheck.Sample(
        audio = playbackSpeedAwareAudioSink?.platformOutputTimestamp(),
        renderedFrames = counters?.renderedOutputBufferCount
    )
}

private fun PlayerRuntimeController.pausedOutputCheckApplies(player: ExoPlayer): Boolean {
    return _exoPlayer === player &&
        !isReleasingPlayer &&
        !isUsingMpvEngine() &&
        userPausedManually &&
        !scrubHoldPaused &&
        partyBridge?.inParty != true &&
        !player.playWhenReady &&
        player.isCurrentMediaItemSeekable &&
        player.playbackState == Player.STATE_READY
}
