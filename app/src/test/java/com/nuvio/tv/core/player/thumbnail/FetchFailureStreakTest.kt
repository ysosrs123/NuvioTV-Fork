package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Back-off and give-up decision after failed thumbnail fetches. */
class FetchFailureStreakTest {
    @Test fun waitGrowsThenStaysAtTheCap() {
        val f = FetchFailureStreak(giveUpAfter = 100, firstWaitMs = 2_000L, maxWaitMs = 60_000L)
        assertEquals(2_000L, f.failed())
        assertEquals(4_000L, f.failed())
        assertEquals(8_000L, f.failed())
        assertEquals(16_000L, f.failed())
        assertEquals(32_000L, f.failed())
        assertEquals(60_000L, f.failed())
        repeat(50) { assertEquals(60_000L, f.failed()) }
        assertEquals(56, f.count)
    }

    @Test fun givesUpAfterTheLimitInARow() {
        val f = FetchFailureStreak(giveUpAfter = 5)
        repeat(4) {
            f.failed()
            assertFalse(f.gaveUp)
        }
        f.failed()
        assertTrue(f.gaveUp)
        assertEquals(5, f.count)
    }

    @Test fun aSuccessResetsTheStreakAndTheWait() {
        val f = FetchFailureStreak(giveUpAfter = 5)
        repeat(4) { f.failed() }
        f.succeeded()
        assertEquals(0, f.count)
        assertFalse(f.gaveUp)
        assertEquals(2_000L, f.failed())
        repeat(3) { f.failed() }
        assertFalse(f.gaveUp)          // 4 in a row since the success, not 8
        f.failed()
        assertTrue(f.gaveUp)
    }

    @Test fun noFailuresNoWait() {
        val f = FetchFailureStreak(giveUpAfter = 1)
        assertEquals(0L, f.waitMs(0))
        assertFalse(f.gaveUp)
        f.failed()
        assertTrue(f.gaveUp)
    }

    @Test fun fetchesUnderWayTogetherCountOnce() {
        val f = FetchFailureStreak(giveUpAfter = 5)
        // Four fetches started at 1000-1003 ms, all failing at 1500 ms.
        assertEquals(2_000L, f.failedFetch(startedAtMs = 1_000L, nowMs = 1_500L))
        assertNull(f.failedFetch(startedAtMs = 1_001L, nowMs = 1_510L))
        assertNull(f.failedFetch(startedAtMs = 1_002L, nowMs = 1_520L))
        assertNull(f.failedFetch(startedAtMs = 1_500L, nowMs = 1_530L))
        assertEquals(1, f.count)
        // The retry after the wait is a new attempt.
        assertEquals(4_000L, f.failedFetch(startedAtMs = 3_600L, nowMs = 4_000L))
        assertEquals(2, f.count)
    }

    @Test fun parallelFailuresStillGiveUpAfterTheLimitOfRounds() {
        val f = FetchFailureStreak(giveUpAfter = 3)
        var now = 0L
        repeat(3) { round ->
            assertFalse(f.gaveUp)
            val started = now + 1
            now = started + 100
            assertNotNull(f.failedFetch(started, now))
            repeat(3) { assertNull(f.failedFetch(started, now + it)) }
            assertEquals(round + 1, f.count)
            now += f.waitMs(f.count)
        }
        assertTrue(f.gaveUp)
    }

    @Test fun aSuccessDoesNotLetAnOlderFailureCountAgain() {
        val f = FetchFailureStreak(giveUpAfter = 5)
        f.failedFetch(startedAtMs = 100L, nowMs = 200L)
        f.succeeded()
        assertNull(f.failedFetch(startedAtMs = 150L, nowMs = 300L))
        assertEquals(0, f.count)
    }
}
