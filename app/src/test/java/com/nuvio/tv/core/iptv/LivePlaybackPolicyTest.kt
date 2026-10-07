package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class LivePlaybackPolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun liveRetriesForeverWithBackoff() {
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 5_000L, 10_000L, 10_000L), (0..5).map { LiveRetry.delay(true, it) })
        assertEquals(10_000L, LiveRetry.delay(true, 10_000))
        assertEquals(LiveRetryDecision.Retry(10_000), LiveRetry.decide(true, 500, 2001, null, null, now))
    }

    @Test fun catchupRetriesAreBounded() {
        assertEquals(5_000L, LiveRetry.delay(false, 5))
        assertNull(LiveRetry.delay(false, 6))
        assertEquals(LiveRetryDecision.Fail(LiveFailure.EXHAUSTED), LiveRetry.decide(false, 6, 2001, null, null, now))
    }

    @Test fun permanentErrorsStopAtOnce() {
        assertEquals(LiveRetryDecision.Fail(LiveFailure.DENIED), LiveRetry.decide(true, 0, 2004, 403, null, now))
        assertEquals(LiveRetryDecision.Fail(LiveFailure.DENIED), LiveRetry.decide(true, 0, 2004, 401, null, now))
        assertEquals(LiveRetryDecision.Fail(LiveFailure.MISSING), LiveRetry.decide(true, 0, 2004, 404, null, now))
        assertEquals(LiveRetryDecision.Fail(LiveFailure.MISSING), LiveRetry.decide(true, 0, 2004, 410, null, now))
        assertEquals(LiveRetryDecision.Fail(LiveFailure.UNSUPPORTED), LiveRetry.decide(true, 0, 3003, null, null, now))
        assertEquals(LiveRetryDecision.Fail(LiveFailure.UNSUPPORTED), LiveRetry.decide(true, 0, 4005, null, null, now))
        assertEquals(LiveRetryDecision.Fail(LiveFailure.UNSUPPORTED), LiveRetry.decide(true, 0, 6001, null, null, now))
        assertEquals(LiveRetryDecision.Fail(LiveFailure.DECODER), LiveRetry.decide(true, 0, 4001, null, null, now))
    }

    @Test fun transientErrorsRetry() {
        for (code in listOf(1002, 1003, 2000, 2001, 2002, 2004, 3001, 4003, 5001)) {
            assertTrue("$code", LiveRetry.decide(true, 0, code, if (code == 2004) 500 else null, null, now) is LiveRetryDecision.Retry)
        }
        assertEquals(LiveRetryDecision.Retry(1_000), LiveRetry.decide(true, 0, null, null, null, now))
    }

    @Test fun retryAfterIsHonouredWithinBounds() {
        assertEquals(LiveRetryDecision.Retry(30_000), LiveRetry.decide(true, 0, 2004, 503, "30", now))
        assertEquals(LiveRetryDecision.Retry(60_000), LiveRetry.decide(true, 0, 2004, 429, "86400", now))
        assertEquals(LiveRetryDecision.Retry(2_000), LiveRetry.decide(true, 1, 2004, 429, "0", now))
        assertEquals(LiveRetryDecision.Retry(1_000), LiveRetry.decide(true, 0, 2004, 503, "soon", now))
        assertEquals(LiveRetryDecision.Retry(1_000), LiveRetry.decide(true, 0, 2004, 500, "30", now))
        assertEquals(20_000L, LiveRetry.retryAfterMs("Fri, 15 Jan 2027 08:00:20 GMT", java.time.Instant.parse("2027-01-15T08:00:00Z").toEpochMilli()))
        assertEquals(0L, LiveRetry.retryAfterMs("Fri, 15 Jan 2027 08:00:20 GMT", java.time.Instant.parse("2027-01-15T09:00:00Z").toEpochMilli()))
        assertNull(LiveRetry.retryAfterMs("-5", now))
    }

    @Test fun frozenWatchNeedsEightSecondsWithoutProgress() {
        val watch = FrozenVideoWatch(8_000)
        assertFalse(watch.frozen(0, true, 10))
        assertFalse(watch.frozen(7_999, true, 10))
        assertTrue(watch.frozen(8_000, true, 10))
        watch.reset()
        assertFalse(watch.frozen(9_000, true, 10))
        assertFalse(watch.frozen(16_000, true, 11))
        assertFalse(watch.frozen(23_000, true, 11))
        assertFalse(watch.frozen(25_000, false, 11))
        assertFalse(watch.frozen(40_000, true, 11))
        assertTrue(watch.frozen(48_000, true, 11))
    }

    @Test fun frameRateEstimateSnapsToBroadcastRates() {
        assertEquals(25f, LiveFrameRate.estimate(76, 3_000))
        assertEquals(25f, LiveFrameRate.estimate(74, 3_000))
        assertEquals(50f, LiveFrameRate.estimate(150, 3_000))
        assertEquals(30000f / 1001f, LiveFrameRate.estimate(90, 3_000))
        assertEquals(60000f / 1001f, LiveFrameRate.estimate(180, 3_000))
        assertEquals(24000f / 1001f, LiveFrameRate.estimate(72, 3_000))
        assertNull(LiveFrameRate.estimate(40, 3_000))
        assertNull(LiveFrameRate.estimate(100, 2_000))
        assertNull(LiveFrameRate.estimate(120, 3_000))
    }
}
