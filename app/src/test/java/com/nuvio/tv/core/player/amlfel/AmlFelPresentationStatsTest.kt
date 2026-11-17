package com.nuvio.tv.core.player.amlfel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmlFelPresentationStatsTest {
    @Test fun `a picture that did not move between two playing polls is a hold`() {
        val detector = FelPictureHoldDetector()
        assertFalse(detector.poll(900_000L, showing = true))
        assertFalse(detector.poll(922_500L, showing = true))
        assertTrue(detector.poll(922_500L, showing = true))
        assertFalse(detector.poll(945_000L, showing = true))
    }

    @Test fun `the first poll after a pause or a start is never a hold`() {
        val detector = FelPictureHoldDetector()
        detector.poll(900_000L, showing = false)
        assertFalse(detector.poll(900_000L, showing = true))
        assertFalse(detector.poll(900_000L, showing = false))
        detector.reset()
        assertFalse(detector.poll(900_000L, showing = true))
    }

    @Test fun `a failed clock read neither counts nor breaks the next comparison`() {
        val detector = FelPictureHoldDetector()
        detector.poll(900_000L, showing = true)
        assertFalse(detector.poll(-22L, showing = true))
        assertTrue(detector.poll(900_000L, showing = true))
    }

    @Test fun `picture offset is signed and survives the 90 kHz clock wrapping`() {
        assertEquals(-250, AmlFelPresentationStats.offsetMs(900_000L, 922_500))
        assertEquals(41, AmlFelPresentationStats.offsetMs(903_750L, 900_000))
        val nearWrap = 0xFFFF_FF00L
        val afterWrap = (nearWrap + 9_000L) and 0xFFFF_FFFFL
        assertEquals(100, AmlFelPresentationStats.offsetMs(afterWrap, nearWrap.toInt()))
    }

    @Test fun `reset clears the counts and the offset`() {
        AmlFelPresentationStats.noteHold()
        AmlFelPresentationStats.noteClockCorrection()
        AmlFelPresentationStats.notePictureOffset(12)
        AmlFelPresentationStats.reset()
        assertEquals(0, AmlFelPresentationStats.holds)
        assertEquals(0, AmlFelPresentationStats.clockCorrections)
        assertEquals(AmlFelPresentationStats.NO_OFFSET, AmlFelPresentationStats.pictureOffsetMs)
    }
}
