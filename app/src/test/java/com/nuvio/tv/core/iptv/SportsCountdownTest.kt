package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SportsCountdownTest {
    private val minute = 60_000L

    @Test fun countdownRoundsUpToTheMinute() {
        assertNull(SportsCountdown.until(1000, 1000))
        assertNull(SportsCountdown.until(2000, 1000))
        assertEquals(Countdown(0, 0, 1), SportsCountdown.until(0, 1000))
        assertEquals(Countdown(0, 2, 15), SportsCountdown.until(0, 135 * minute))
        assertEquals(Countdown(1, 9, 0), SportsCountdown.until(0, (33 * 60) * minute))
    }
}
