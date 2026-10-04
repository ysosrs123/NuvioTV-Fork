package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateDoubleRefreshTest {
    @Test
    fun `25 and 30 fps prefer the double refresh rate`() {
        assertTrue(FrameRateUtils.prefersDoubleRefresh(25f))
        assertTrue(FrameRateUtils.prefersDoubleRefresh(29.97f))
        assertTrue(FrameRateUtils.prefersDoubleRefresh(30f))
    }

    @Test
    fun `film and high frame rates keep the exact match`() {
        assertFalse(FrameRateUtils.prefersDoubleRefresh(23.976f))
        assertFalse(FrameRateUtils.prefersDoubleRefresh(24f))
        assertFalse(FrameRateUtils.prefersDoubleRefresh(50f))
        assertFalse(FrameRateUtils.prefersDoubleRefresh(59.94f))
    }
}
