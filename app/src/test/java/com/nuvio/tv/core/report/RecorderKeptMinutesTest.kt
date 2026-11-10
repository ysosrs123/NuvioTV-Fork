package com.nuvio.tv.core.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecorderKeptMinutesTest {
    private fun ring(vararg timesMs: Long) = SampleRing().apply {
        timesMs.forEach { add(it, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1) }
    }

    @Test fun `no samples means no claim`() {
        assertNull(recorderKeptMinutes(0L))
    }

    @Test fun `under a minute reads as one and whole minutes are never rounded up`() {
        assertEquals(1, recorderKeptMinutes(1_000L))
        assertEquals(1, recorderKeptMinutes(119_000L))
        assertEquals(3, recorderKeptMinutes(200_000L))
    }

    @Test fun `never more than the five minutes the recorder can hold`() {
        assertEquals(4, recorderKeptMinutes(299_000L))
        assertEquals(5, recorderKeptMinutes(330_000L))
    }

    @Test fun `samples from an earlier film do not count towards this one`() {
        val snapshot = ring(0L, 1_000L, 2_000L, 60_000L, 61_000L, 62_000L).snapshot(62_000L)
        assertEquals(4_000L, snapshot.coveredMs)
        assertEquals(2_000L, snapshot.coveredMsWithin(2_000L))
    }

    @Test fun `a sample older than this playback adds nothing`() {
        assertEquals(0L, ring(0L, 1_000L).snapshot(10_000L).coveredMsWithin(5_000L))
    }
}
