package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioClockGapTest {
    @Test fun `readings the jitter figures throw away become gaps while playing`() {
        assertTrue(isAudioClockGap(playing = true, wallDeltaMs = 400L, offMs = 0L))
        assertTrue(isAudioClockGap(playing = true, wallDeltaMs = 100L, offMs = 900L))
        assertTrue(isAudioClockGap(playing = true, wallDeltaMs = 100L, offMs = -700L))
    }

    @Test fun `normal readings, pauses and seeks are not gaps`() {
        assertFalse(isAudioClockGap(playing = true, wallDeltaMs = 100L, offMs = 30L))
        assertFalse(isAudioClockGap(playing = true, wallDeltaMs = 250L, offMs = 500L))
        assertFalse(isAudioClockGap(playing = false, wallDeltaMs = 900L, offMs = 900L))
        assertFalse(isAudioClockGap(playing = true, wallDeltaMs = 100L, offMs = 600_000L))
        assertFalse(isAudioClockGap(playing = true, wallDeltaMs = 5L, offMs = 900L))
    }

    @Test fun `at most one gap line every two seconds, the held back ones counted on the next`() {
        val limiter = GapLimiter(everyMs = 2_000L)
        assertEquals(0, limiter.take(10_000L))
        assertEquals(-1, limiter.take(10_500L))
        assertEquals(-1, limiter.take(11_900L))
        assertEquals(2, limiter.take(12_000L))
        assertEquals(0, limiter.take(20_000L))
    }

    @Test fun `loading lines stay out of the report's event lists`() {
        assertFalse("is_loading".keptInEventLists())
        assertTrue("position_stall".keptInEventLists())
        assertTrue("is_playing".keptInEventLists())
    }
}
