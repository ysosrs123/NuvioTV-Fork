package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScrubRatesTest {

    @Test
    fun stepMsForHold_rampTiers() {
        assertEquals(PlayerScrubRates.STEP_SHORT_MS, PlayerScrubRates.stepMsForHold(0L))
        assertEquals(PlayerScrubRates.STEP_SHORT_MS, PlayerScrubRates.stepMsForHold(1_999L))
        assertEquals(30_000L, PlayerScrubRates.stepMsForHold(2_000L))
        assertEquals(30_000L, PlayerScrubRates.stepMsForHold(4_999L))
        assertEquals(60_000L, PlayerScrubRates.stepMsForHold(5_000L))
        assertEquals(60_000L, PlayerScrubRates.stepMsForHold(9_999L))
        assertEquals(120_000L, PlayerScrubRates.stepMsForHold(10_000L))
        assertEquals(120_000L, PlayerScrubRates.stepMsForHold(600_000L))
    }

    @Test
    fun deltaMsForHold_appliesDirection() {
        assertEquals(-PlayerScrubRates.STEP_SHORT_MS, PlayerScrubRates.deltaMsForHold(0L, forward = false))
        assertEquals(60_000L, PlayerScrubRates.deltaMsForHold(5_000L, forward = true))
    }

    @Test
    fun stepMsForHold_negativeHoldUsesBaseStep() {
        assertEquals(PlayerScrubRates.STEP_SHORT_MS, PlayerScrubRates.stepMsForHold(-1L))
    }

    @Test
    fun acceptStep_fixedCadenceWithinAHold() {
        val down = 1_000_000L
        assertTrue(PlayerScrubRates.acceptStep(down, down))
        assertFalse(PlayerScrubRates.acceptStep(down, down + 150))
        assertTrue(PlayerScrubRates.acceptStep(down, down + 200))
        assertFalse(PlayerScrubRates.acceptStep(down, down + 250))
        assertFalse(PlayerScrubRates.acceptStep(down, down + 350))
        assertTrue(PlayerScrubRates.acceptStep(down, down + 400))
    }

    @Test
    fun acceptStep_newHoldStepsImmediately() {
        assertTrue(PlayerScrubRates.acceptStep(2_000_000L, 2_000_000L))
        assertTrue(PlayerScrubRates.acceptStep(2_000_050L, 2_000_050L))  // quick second tap = new hold
    }

    @Test
    fun heldScrub_reachesTwoHoursInAboutNineteenSeconds() {
        // Fire TV-like repeats: first repeat after 500 ms, then every 50 ms.
        val down = 3_000_000L
        var position = 0L
        var t = 0L
        while (position < 7_200_000L) {
            if (PlayerScrubRates.acceptStep(down, down + t)) position += PlayerScrubRates.stepMsForHold(t)
            t = if (t == 0L) 500L else t + 50L
        }
        assertTrue("reached 2 h after $t ms", t in 17_000L..21_000L)
    }
}
