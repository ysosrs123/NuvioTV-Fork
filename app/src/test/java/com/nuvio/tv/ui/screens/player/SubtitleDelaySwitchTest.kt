package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleDelaySwitchTest {

    @Test
    fun `another episode starts without the delay`() {
        assertEquals(
            0,
            subtitleDelayMsAfterSwitch(currentDelayMs = -1500, fromVideoId = "tt1:1:1", toVideoId = "tt1:1:2")
        )
    }

    @Test
    fun `a new source for the same episode keeps the delay`() {
        assertEquals(
            -1500,
            subtitleDelayMsAfterSwitch(currentDelayMs = -1500, fromVideoId = "tt1:1:1", toVideoId = "tt1:1:1")
        )
    }

    @Test
    fun `a title without a video id keeps the delay`() {
        assertEquals(
            250,
            subtitleDelayMsAfterSwitch(currentDelayMs = 250, fromVideoId = null, toVideoId = null)
        )
    }
}
