package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackConnectionStartTest {
    @Test fun `call start is measured from the last session clear`() {
        PlaybackConnectionEvents.clear()
        val base = PlaybackConnectionEvents.sinceSessionStart(0L)
        assertEquals(base + 1_500L, PlaybackConnectionEvents.sinceSessionStart(1_500L))
        assertEquals(base - 200L, PlaybackConnectionEvents.sinceSessionStart(-200L))
    }
}
