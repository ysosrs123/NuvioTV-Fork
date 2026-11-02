package com.nuvio.tv.ui.screens.player.iec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IecKeepAliveLevelsTest {

    @Test
    fun fullSizeTrack_keepsTheFixedLevels() {
        assertEquals(RATE * 240L / 1000L, IecKeepAliveLevels.capFrames(RATE, RATE.toLong()))
        assertEquals(RATE * 80L / 1000L, IecKeepAliveLevels.lowWaterFrames(RATE, RATE.toLong()))
    }

    @Test
    fun minimumSizeTrack_levelsFitInsideTheBuffer() {
        // A minimum-size TrueHD track: about 45 ms at 192 kHz.
        val bufferFrames = 8_640L
        val cap = IecKeepAliveLevels.capFrames(RATE, bufferFrames)
        val lowWater = IecKeepAliveLevels.lowWaterFrames(RATE, bufferFrames)
        assertEquals(bufferFrames * 3L / 4L, cap)
        assertEquals(bufferFrames / 4L, lowWater)
        assertTrue(lowWater < cap)
        assertTrue("a whole padding frame still fits above the low water", bufferFrames - lowWater >= MAT_FRAMES)
    }

    @Test
    fun levelsShrinkOnlyWhenTheBufferIsSmall() {
        val atCap = RATE * 240L / 1000L
        assertEquals(atCap, IecKeepAliveLevels.capFrames(RATE, atCap * 4L / 3L + 3L))
        assertEquals(atCap / 2L * 3L / 4L, IecKeepAliveLevels.capFrames(RATE, atCap / 2L))
        val lowWater = RATE * 80L / 1000L
        assertEquals(lowWater, IecKeepAliveLevels.lowWaterFrames(RATE, lowWater * 4L))
        assertEquals((lowWater * 4L - 4L) / 4L, IecKeepAliveLevels.lowWaterFrames(RATE, lowWater * 4L - 4L))
    }

    @Test
    fun unknownBufferSize_keepsTheFixedLevels() {
        assertEquals(RATE * 240L / 1000L, IecKeepAliveLevels.capFrames(RATE, 0L))
        assertEquals(RATE * 80L / 1000L, IecKeepAliveLevels.lowWaterFrames(RATE, 0L))
    }

    @Test
    fun levelsFollowTheSampleRate() {
        val rate = 176_400
        assertEquals(rate * 240L / 1000L, IecKeepAliveLevels.capFrames(rate, rate.toLong()))
        assertEquals(rate * 80L / 1000L, IecKeepAliveLevels.lowWaterFrames(rate, rate.toLong()))
    }

    private companion object {
        const val RATE = 192_000
        const val MAT_FRAMES = 3_840L
    }
}
