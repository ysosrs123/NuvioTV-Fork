package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RateLimitCeilingTest {
    @Test
    fun `a refusal before the first frame is given up on sooner than one in the middle of a film`() {
        assertEquals(15_000L, rateLimitStreakCeilingMs(beforeFirstFrame = true))
        assertEquals(120_000L, rateLimitStreakCeilingMs(beforeFirstFrame = false))
    }
}
