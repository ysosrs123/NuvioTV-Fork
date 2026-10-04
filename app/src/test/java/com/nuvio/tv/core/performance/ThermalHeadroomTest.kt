package com.nuvio.tv.core.performance

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThermalHeadroomTest {
    @Before fun setUp() = ThermalHeadroom.resetForTest()
    @After fun tearDown() = ThermalHeadroom.resetForTest()

    @Test fun latchesAfterConsecutiveFailures() {
        repeat(ThermalHeadroom.FAILURES_BEFORE_DEAD - 1) { ThermalHeadroom.record(Float.NaN) }
        assertFalse(ThermalHeadroom.halDead)
        ThermalHeadroom.record(Float.NaN)
        assertTrue(ThermalHeadroom.halDead)
    }

    @Test fun aRealAnswerResetsTheCount() {
        repeat(30) {
            ThermalHeadroom.record(Float.NaN)
            ThermalHeadroom.record(0.4f)
        }
        repeat(ThermalHeadroom.FAILURES_BEFORE_DEAD - 1) { ThermalHeadroom.record(Float.NaN) }
        assertFalse(ThermalHeadroom.halDead)
    }

    @Test fun negativeAnswersCountAsFailures() {
        repeat(ThermalHeadroom.FAILURES_BEFORE_DEAD) { ThermalHeadroom.record(-1f) }
        assertTrue(ThermalHeadroom.halDead)
    }

    @Test fun noCallOnceLatched() {
        repeat(ThermalHeadroom.FAILURES_BEFORE_DEAD) { ThermalHeadroom.record(Float.NaN) }
        assertTrue(ThermalHeadroom.read(null, 0).isNaN())
        assertTrue(ThermalHeadroom.halDead)
    }
}
