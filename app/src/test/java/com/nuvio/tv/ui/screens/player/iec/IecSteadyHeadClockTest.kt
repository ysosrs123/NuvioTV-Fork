package com.nuvio.tv.ui.screens.player.iec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class IecSteadyHeadClockTest {

    private fun play(clock: IecSteadyHeadClock, seconds: Int, startUs: Long = 0L, position: (Long) -> Long) {
        var now = startUs
        val end = startUs + seconds * 1_000_000L
        while (now < end) {
            clock.observe(now, position(now), playing = true)
            now += 10_000L
        }
    }

    private fun steps(nowUs: Long, stepUs: Long) = nowUs / stepUs * stepUs

    @Test
    fun `a healthy box never engages`() {
        val clock = IecSteadyHeadClock()
        play(clock, 600) { it + (it / 1_000_000L % 2) * 25_000L }
        assertFalse(clock.engaged)
    }

    @Test
    fun `a clock that keeps jumping engages after some seconds`() {
        val clock = IecSteadyHeadClock()
        play(clock, 3) { steps(it, 700_000L) }
        assertFalse(clock.engaged)
        play(clock, 20, startUs = 3_000_000L) { steps(it, 700_000L) }
        assertTrue(clock.engaged)
    }

    @Test
    fun `one stall or seek does not engage`() {
        val clock = IecSteadyHeadClock()
        play(clock, 60) { if (it > 30_000_000L) it + 20_000_000L else it }
        assertFalse(clock.engaged)
    }

    @Test
    fun `pauses and seeks do not count and the decision survives them`() {
        val clock = IecSteadyHeadClock()
        repeat(20) { i ->
            val start = i * 4_000_000L
            play(clock, 4, startUs = start) { it + i * 500_000L }
            clock.observe(start + 4_000_000L, 0L, playing = false)
        }
        assertFalse(clock.engaged)
        play(clock, 20, startUs = 100_000_000L) { steps(it, 700_000L) }
        assertTrue(clock.engaged)
        clock.reset()
        assertTrue(clock.engaged)
        clock.release()
        assertFalse(clock.engaged)
    }

    @Test
    fun `the steady position moves evenly while the head moves in big steps`() {
        val clock = IecSteadyHeadClock()
        var previous = -1L
        var now = 0L
        while (now < 30_000_000L) {
            val position = clock.positionUs(now, steps(now, 700_000L), playing = true)
            if (now > 8_000_000L) {
                val step = position - previous
                assertTrue("step $step at $now", abs(step - 10_000L) <= 20_000L)
                assertTrue(abs(position - now) < 700_000L)
            }
            previous = position
            now += 10_000L
        }
    }

    @Test
    fun `a big head jump restarts the average instead of sliding to it`() {
        val clock = IecSteadyHeadClock()
        clock.positionUs(0L, 0L, playing = true)
        clock.positionUs(200_000L, 200_000L, playing = true)
        assertEquals(60_400_000L, clock.positionUs(400_000L, 60_400_000L, playing = true))
    }
}
