package com.nuvio.tv.ui.screens.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Wraps a [Player] so that [getBufferedPercentage] never throws when
 * Media3 [MediaSession] internals call [androidx.media3.common.util.Util.percentInt].
 * [onSessionPlayPause] hears play and pause that arrive from outside the app (TV remote over HDMI, the system).
 */
internal class SafeMediaSessionPlayer(
    player: Player,
    private val onSessionPlayPause: (playing: Boolean) -> Unit = {},
) : ForwardingPlayer(player) {

    override fun play() {
        onSessionPlayPause(true)
        super.play()
    }

    override fun pause() {
        onSessionPlayPause(false)
        super.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        onSessionPlayPause(playWhenReady)
        super.setPlayWhenReady(playWhenReady)
    }

    override fun getBufferedPercentage(): Int {
        return try {
            super.getBufferedPercentage().coerceIn(0, 100)
        } catch (_: IllegalArgumentException) {
            // Util.percentInt overflow — live stream with huge bufferedPosition
            100
        }
    }
}
