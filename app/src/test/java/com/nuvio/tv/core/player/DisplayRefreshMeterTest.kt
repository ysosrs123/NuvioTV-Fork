package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayRefreshMeterTest {
    private fun ticks(hz: Double, seconds: Double, skip: Set<Int> = emptySet(), jitterNs: Long = 0L): List<Long> {
        val period = 1_000_000_000.0 / hz
        return (0..(hz * seconds).toInt()).filter { it !in skip }
            .map { (it * period).toLong() + if (it % 2 == 0) jitterNs else -jitterNs }
    }

    @Test
    fun `tells 24 from 23_976`() {
        assertEquals(23.976f, DisplayRefreshMeter.hzFrom(ticks(23.976, 4.0))!!, 0.002f)
        assertEquals(24.000f, DisplayRefreshMeter.hzFrom(ticks(24.0, 4.0))!!, 0.002f)
        assertEquals(59.94f, DisplayRefreshMeter.hzFrom(ticks(59.94, 4.0))!!, 0.005f)
    }

    @Test
    fun `missed callbacks and a little jitter do not bend the reading`() {
        val missed = ticks(23.976, 4.0, skip = setOf(10, 11, 40, 70), jitterNs = 150_000)
        assertEquals(23.976f, DisplayRefreshMeter.hzFrom(missed)!!, 0.004f)
    }

    @Test
    fun `too few ticks give no reading`() {
        assertNull(DisplayRefreshMeter.hzFrom(ticks(24.0, 1.0)))
    }

    @Test
    fun `a screen ticking at 24 when Android says 23_976 is flagged, a matching one is not`() {
        val off = DisplayRefreshMeter.Reading(24.000f, 23.976f, seamless = true, ticks = 96)
        assertTrue(DisplayRefreshMeter.differs(off))
        assertEquals("measured 24.000 Hz, Android says 23.976 Hz, switched without a mode change (QMS or VRR)", DisplayRefreshMeter.describe(off))
        assertFalse(DisplayRefreshMeter.differs(DisplayRefreshMeter.Reading(23.977f, 23.976f, seamless = false, ticks = 96)))
    }

    @Test
    fun `a count nowhere near the new rate after a mode change is not taken as the screen's rate`() {
        assertTrue(DisplayRefreshMeter.ticksMissedModeChange(measuredHz = 59.939f, androidHz = 23.976f, seamless = false))
        assertTrue(DisplayRefreshMeter.ticksMissedModeChange(measuredHz = 37.555f, androidHz = 23.976f, seamless = false))
        assertFalse(DisplayRefreshMeter.ticksMissedModeChange(measuredHz = 24.000f, androidHz = 23.976f, seamless = false))
        assertFalse(DisplayRefreshMeter.ticksMissedModeChange(measuredHz = 23.976f, androidHz = 23.976f, seamless = false))
        assertFalse(DisplayRefreshMeter.ticksMissedModeChange(measuredHz = 37.555f, androidHz = 23.976f, seamless = true))
        assertFalse(DisplayRefreshMeter.ticksMissedModeChange(measuredHz = 59.939f, androidHz = 23.976f, seamless = null))
    }

    @Test
    fun `only a count on another real rate is flagged`() {
        fun reading(hz: Float, android: Float, seamless: Boolean?) = DisplayRefreshMeter.Reading(
            hz, android, seamless, ticks = 96, reliable = DisplayRefreshMeter.plausible(hz, android)
        )
        assertTrue(DisplayRefreshMeter.differs(reading(24.000f, 23.976f, seamless = false)))
        assertTrue(DisplayRefreshMeter.differs(reading(24.004f, 23.976f, seamless = true)))
        assertTrue(DisplayRefreshMeter.differs(reading(59.939f, 23.976f, seamless = true)))
        assertTrue(DisplayRefreshMeter.differs(reading(60.000f, 59.94f, seamless = true)))
        assertTrue(DisplayRefreshMeter.differs(reading(50.000f, 23.976f, seamless = null)))

        val amlogicQms = reading(24.088f, 23.976f, seamless = true)
        assertFalse(amlogicQms.reliable)
        assertFalse(DisplayRefreshMeter.differs(amlogicQms))
        assertEquals(
            "measured 24.088 Hz, Android says 23.976 Hz, switched without a mode change (QMS or VRR), count is not a rate a screen runs at",
            DisplayRefreshMeter.describe(amlogicQms)
        )
        assertFalse(DisplayRefreshMeter.differs(reading(37.555f, 23.976f, seamless = false)))
        assertFalse(reading(37.555f, 23.976f, seamless = true).reliable)

        val homatics = reading(23.970f, 23.976f, seamless = false)
        assertTrue(homatics.reliable)
        assertFalse(DisplayRefreshMeter.differs(homatics))
        assertFalse(DisplayRefreshMeter.differs(reading(59.939f, 59.94f, seamless = false)))
        assertFalse(DisplayRefreshMeter.differs(reading(23.976f, 23.976f, seamless = true)))
    }

    @Test
    fun `on such a box only a switch without a mode change is still judged`() {
        assertFalse(DisplayRefreshMeter.reliable(seamless = false, ticksIgnoreModeChanges = true))
        assertFalse(DisplayRefreshMeter.reliable(seamless = null, ticksIgnoreModeChanges = true))
        assertTrue(DisplayRefreshMeter.reliable(seamless = true, ticksIgnoreModeChanges = true))
        assertTrue(DisplayRefreshMeter.reliable(seamless = false, ticksIgnoreModeChanges = false))
        val fireStick = DisplayRefreshMeter.Reading(59.939f, 23.976f, seamless = false, ticks = 141, reliable = false)
        assertFalse(DisplayRefreshMeter.differs(fireStick))
        assertEquals(
            "measured 59.939 Hz, Android says 23.976 Hz, switched with a mode change, count does not follow mode changes on this box",
            DisplayRefreshMeter.describe(fireStick)
        )
    }
}
