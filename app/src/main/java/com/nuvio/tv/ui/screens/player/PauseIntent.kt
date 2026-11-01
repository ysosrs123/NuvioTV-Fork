package com.nuvio.tv.ui.screens.player

/**
 * Who paused the player. The thumbnails screen holds playback through the same pause flag, so start-up autoplay
 * leaves it alone, but that hold is not the user's: it ends with the next play, any pause or play the user chooses,
 * or a new player, and a rebuilt player plays again unless the user paused.
 */
internal class PauseIntent {
    /** The player is to stay paused: the user's pause, a paused start, or the thumbnails' hold. */
    var paused: Boolean = false
        set(value) {
            field = value
            heldForThumbnails = false
        }

    /** [paused] comes from the thumbnails' hold only. */
    var heldForThumbnails: Boolean = false
        private set

    /** A rebuilt player starts paused only for this. */
    val pausedByUser: Boolean get() = paused && !heldForThumbnails

    fun holdForThumbnails() {
        paused = true
        heldForThumbnails = true
    }

    /** The player was asked to play: a hold is over, a user pause is left to the play/pause handling. */
    fun onPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady && heldForThumbnails) paused = false
    }
}
