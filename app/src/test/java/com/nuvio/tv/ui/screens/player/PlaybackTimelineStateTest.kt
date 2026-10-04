package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimelineStateTest {
    @Test fun `duration update during preview preserves actual playhead`() {
        val preview = PlaybackTimelineState(currentPosition = 100, duration = 1000)
            .withPositions(preview = 700, actual = 100)
        val updated = preview.withPositions().copy(duration = 1100)
        assertEquals(100L, updated.playbackPosition)
        assertEquals(700L, updated.currentPosition)
    }
    @Test fun `commit resume and cancellation can replace both positions`() {
        val preview = PlaybackTimelineState(currentPosition = 700, playbackPosition = 100)
        for (position in listOf(700L, 100L, 900L)) {
            val updated = preview.withPositions(position)
            assertEquals(position, updated.currentPosition)
            assertEquals(position, updated.playbackPosition)
        }
    }
    @Test fun `fresh source clears both positions`() {
        val fresh = PlaybackTimelineState()
        assertEquals(0L, fresh.currentPosition)
        assertEquals(0L, fresh.playbackPosition)
    }
}
