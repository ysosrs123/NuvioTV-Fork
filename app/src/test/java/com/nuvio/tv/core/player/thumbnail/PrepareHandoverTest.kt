package com.nuvio.tv.core.player.thumbnail

import com.nuvio.tv.core.player.thumbnail.PrepareHandover.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the generating screen stops waiting and playback starts. */
class PrepareHandoverTest {
    private fun reason(
        sinceStartMs: Long = 2_000L,
        sinceProgressMs: Long = 1_000L,
        leftS: Int = 90,
        done: Int = 10,
        total: Int = 105,
        finished: Boolean = false,
        rateLimited: Boolean = false,
    ): Reason? {
        val now = 100_000L
        return PrepareHandover.reason(now, now - sinceStartMs, now - sinceProgressMs, leftS, done, total, finished, rateLimited)
    }

    @Test fun aHealthySourceKeepsTheScreenUp() {
        assertNull(reason())
        assertNull(reason(sinceStartMs = 60_000L, sinceProgressMs = 19_999L, leftS = 240))
    }

    @Test fun aRateLimitedSourceStartsPlaybackAtOnce() {
        assertEquals(Reason.RATE_LIMITED, reason(rateLimited = true, sinceStartMs = 0L, sinceProgressMs = 0L, done = 0))
        assertEquals(Reason.RATE_LIMITED, reason(rateLimited = true, sinceProgressMs = 25_000L))
    }

    @Test fun noProgressForTwentySecondsGivesUp() {
        assertEquals(Reason.STALLED, reason(sinceStartMs = 20_000L, sinceProgressMs = 20_000L, done = 0))
    }

    @Test fun tooMuchLeftAfterTenSecondsGivesUp() {
        assertNull(reason(sinceStartMs = 9_999L, leftS = 500))
        assertEquals(Reason.HOPELESS, reason(sinceStartMs = 10_000L, leftS = 241))
    }

    @Test fun doneOrFinishedIsNeverAHandover() {
        assertNull(reason(done = 105, rateLimited = true))
        assertNull(reason(finished = true, rateLimited = true, sinceProgressMs = 30_000L))
    }
}
