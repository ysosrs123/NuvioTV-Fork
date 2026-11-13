package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerReportReasonTest {

    private fun reason(firstFrame: Boolean, error: String?, playing: Boolean) =
        PlayerStartupLoadingPolicy.reportReason(
            hasRenderedFirstFrame = firstFrame,
            error = error,
            wasPlaying = playing,
        )

    @Test
    fun `player error keeps the error reason`() {
        assertEquals("playback_error", reason(firstFrame = true, error = "Source error", playing = false))
        assertEquals("playback_error", reason(firstFrame = false, error = "Source error", playing = false))
    }

    @Test
    fun `no first frame yet is a loading report`() {
        assertEquals("loading_stall", reason(firstFrame = false, error = null, playing = true))
        assertEquals("loading_stall", reason(firstFrame = false, error = null, playing = false))
    }

    @Test
    fun `report sent while the film plays fine`() {
        assertEquals("while_playing", reason(firstFrame = true, error = null, playing = true))
    }

    @Test
    fun `report sent while paused`() {
        assertEquals("while_paused", reason(firstFrame = true, error = null, playing = false))
    }
}
