package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusStepPaceTest {

    @Test
    fun `first focus has no extra wait`() {
        val pace = FocusStepPace()
        assertEquals(0L, pace.onFocus(10_000L))
        assertEquals(0L, pace.extraWaitNowMs)
    }

    @Test
    fun `a move after a rest has no extra wait`() {
        val pace = FocusStepPace()
        pace.onFocus(10_000L)
        assertEquals(0L, pace.onFocus(10_000L + FocusStepPace.QUICK_STEP_MS))
        assertEquals(0L, pace.onFocus(20_000L))
    }

    @Test
    fun `quick steps wait longer`() {
        val pace = FocusStepPace()
        pace.onFocus(10_000L)
        assertEquals(FocusStepPace.EXTRA_WAIT_MS, pace.onFocus(10_300L))
        assertEquals(FocusStepPace.EXTRA_WAIT_MS, pace.onFocus(10_600L))
        assertEquals(FocusStepPace.EXTRA_WAIT_MS, pace.extraWaitNowMs)
    }

    @Test
    fun `the wait ends with the first move after a rest`() {
        val pace = FocusStepPace()
        pace.onFocus(10_000L)
        pace.onFocus(10_300L)
        assertEquals(0L, pace.onFocus(12_000L))
        assertEquals(0L, pace.extraWaitNowMs)
    }

    @Test
    fun `a clock that goes backwards is not a quick step`() {
        val pace = FocusStepPace()
        pace.onFocus(10_000L)
        assertEquals(0L, pace.onFocus(9_000L))
    }
}
