package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitStartGiveUpTest {

    @Test
    fun `a 429 from the first request with no bytes skips the player retry`() {
        assertTrue(RateLimitStartGiveUp.skipsPlayerRetry(429, firstFrameRendered = false, mediaBytesRead = 0L))
    }

    @Test
    fun `a 429 after bytes arrived keeps the retry`() {
        assertFalse(RateLimitStartGiveUp.skipsPlayerRetry(429, firstFrameRendered = false, mediaBytesRead = 65_536L))
    }

    @Test
    fun `a 429 after the first frame keeps the retry`() {
        assertFalse(RateLimitStartGiveUp.skipsPlayerRetry(429, firstFrameRendered = true, mediaBytesRead = 0L))
    }

    @Test
    fun `uncounted routes and other codes keep the retry`() {
        assertFalse(RateLimitStartGiveUp.skipsPlayerRetry(429, firstFrameRendered = false, mediaBytesRead = null))
        assertFalse(RateLimitStartGiveUp.skipsPlayerRetry(503, firstFrameRendered = false, mediaBytesRead = 0L))
        assertFalse(RateLimitStartGiveUp.skipsPlayerRetry(null, firstFrameRendered = false, mediaBytesRead = 0L))
    }
}
