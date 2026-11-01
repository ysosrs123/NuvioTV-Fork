package com.nuvio.tv.ui.screens.player

/**
 * How the film comes back after the thumbnails screen. A player that let go of the film (stopped) is built again
 * from scratch, as an error recovery would: preparing the stopped player once more read the file's start wrongly on
 * a Fire TV Stick (source error 3003 a second later, on every such hand-back), while a newly built player opens it
 * cleanly.
 */
internal enum class HandBackReload {
    REBUILD, PLAY, NONE;

    companion object {
        fun of(stopped: Boolean, failed: Boolean): HandBackReload = when {
            failed -> NONE
            stopped -> REBUILD
            else -> PLAY
        }
    }
}
