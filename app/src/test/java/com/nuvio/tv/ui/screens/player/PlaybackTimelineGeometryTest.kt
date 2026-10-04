package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackTimelineGeometryTest {
    @Test fun `forward seek preview does not move the playhead or invent buffered content`() {
        val g = PlaybackTimelineGeometry.from(20, 80, 35, 100)
        assertEquals(.2f, g.played, .0001f); assertEquals(.8f, g.preview, .0001f)
        assertEquals(.35f, g.buffered, .0001f); assertTrue(g.isPreviewing)
    }
    @Test fun `backward preview preserves actual played and forward buffer`() {
        val g = PlaybackTimelineGeometry.from(80, 10, 95, 100)
        assertEquals(.8f, g.played, .0001f); assertEquals(.1f, g.preview, .0001f)
        assertEquals(.95f, g.buffered, .0001f)
    }
    @Test fun `unknown and nonpositive durations do not manufacture timeline positions`() {
        for (duration in listOf(0L, -1L, Long.MIN_VALUE)) {
            val g = PlaybackTimelineGeometry.from(100, 200, 300, duration)
            assertFalse(g.hasDuration); assertFalse(g.isPreviewing); assertEquals(0f, g.played, 0f)
        }
    }
    @Test fun `stale buffer behind playhead does not draw forward fill`() {
        val g = PlaybackTimelineGeometry.from(50, 50, 20, 100)
        assertEquals(g.played, g.buffered, 0f); assertFalse(g.isPreviewing)
    }
    @Test fun `negative and beyond end positions stay inside timeline`() {
        val g = PlaybackTimelineGeometry.from(-10, 120, Long.MAX_VALUE, 100)
        assertEquals(0f, g.played, 0f); assertEquals(1f, g.preview, 0f); assertEquals(1f, g.buffered, 0f)
    }
    @Test fun `large positions avoid integer overflow`() {
        val g = PlaybackTimelineGeometry.from(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2, Long.MAX_VALUE, Long.MAX_VALUE)
        assertEquals(.5f, g.played, .0001f); assertEquals(1f, g.buffered, 0f)
    }
}
