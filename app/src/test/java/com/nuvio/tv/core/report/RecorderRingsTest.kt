package com.nuvio.tv.core.report

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderRingsTest {
    private fun SampleRing.addAt(timeMs: Long, readyAheadMs: Int = 1000, dropped: Int = 0) = add(
        timeMs = timeMs,
        readyAheadMs = readyAheadMs,
        droppedFrames = dropped,
        audioUnderruns = RECORDER_UNKNOWN,
        ownCpuTenths = 120,
        rssKb = 1,
        javaHeapKb = 2,
        nativeHeapKb = 3,
        memAvailableKb = 4,
        bufferCapKb = RECORDER_UNKNOWN,
        thermalStatus = RECORDER_UNKNOWN,
        loaderActive = 1,
        tunnelled = 0,
        playing = 1
    )

    @Test fun `an empty ring has no samples and no length`() {
        val snapshot = SampleRing(5).snapshot(10_000L)
        assertEquals(0, snapshot.count)
        assertEquals(0L, snapshot.coveredMs)
        assertEquals(0, snapshot.ageMs.size)
    }

    @Test fun `a fresh start reports only the time actually recorded`() {
        val ring = SampleRing(300)
        for (i in 0 until 60) ring.addAt(1_000L * i)
        val snapshot = ring.snapshot(59_500L)
        assertEquals(60, snapshot.count)
        assertEquals(59_000L, snapshot.coveredMs)
        assertEquals(59_500L, snapshot.ageMs.first())
        assertEquals(500L, snapshot.ageMs.last())
    }

    @Test fun `the ring wraps keeping the newest samples oldest first`() {
        val ring = SampleRing(3)
        for (i in 1..5) ring.addAt(1_000L * i, readyAheadMs = i, dropped = i * 10)
        val snapshot = ring.snapshot(5_000L)
        assertEquals(3, snapshot.count)
        assertArrayEquals(longArrayOf(2_000L, 1_000L, 0L), snapshot.ageMs)
        assertArrayEquals(intArrayOf(3, 4, 5), snapshot.readyAheadMs)
        assertArrayEquals(intArrayOf(30, 40, 50), snapshot.droppedFrames)
        assertEquals(2_000L, snapshot.coveredMs)
    }

    @Test fun `a long pause between samples does not count as recorded time`() {
        val ring = SampleRing(10)
        ring.addAt(0L)
        ring.addAt(1_000L)
        ring.addAt(601_000L)
        ring.addAt(602_000L)
        assertEquals(2_000L, ring.snapshot(602_000L).coveredMs)
    }

    @Test fun `unknown values stay unknown`() {
        val ring = SampleRing(2)
        ring.addAt(0L)
        val snapshot = ring.snapshot(0L)
        assertEquals(RECORDER_UNKNOWN, snapshot.audioUnderruns[0])
        assertEquals(RECORDER_UNKNOWN.toByte(), snapshot.thermalStatus[0])
        assertEquals(1.toByte(), snapshot.loaderActive[0])
    }

    @Test fun `event lines wrap at the cap and read oldest first`() {
        val ring = EventRing(3)
        for (i in 1..4) ring.add(i * 1_000L, RecorderEvent.PLAY, "n=$i")
        val lines = ring.snapshot(4_000L)
        assertEquals(listOf("-2000ms play n=2", "-1000ms play n=3", "-0ms play n=4"), lines.map { it.text })
    }

    @Test fun `a long event detail is cut at intake`() {
        val ring = EventRing(2)
        ring.add(0L, RecorderEvent.ERROR, "x".repeat(5_000))
        assertEquals(RECORDER_EVENT_DETAIL_CHARS, ring.snapshot(0L).single().detail.length)
    }

    @Test fun `the newest line can be replaced and its kind is known`() {
        val ring = EventRing(3)
        assertNull(ring.lastTimeOf(RecorderEvent.SEEK))
        ring.add(100L, RecorderEvent.SEEK, "a")
        assertEquals(100L, ring.lastTimeOf(RecorderEvent.SEEK))
        ring.replaceLast(250L, "b")
        val line = ring.snapshot(250L).single()
        assertEquals(0L, line.ageMs)
        assertEquals("b", line.detail)
        assertNull(ring.lastTimeOf(RecorderEvent.PLAY))
    }

    @Test fun `proc stat gives cpu ticks and resident pages even with odd process names`() {
        val text = "1234 (com.nuvio (test) tv) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 999 22 23 24 25\n"
        val bytes = text.toByteArray()
        val out = LongArray(2)
        assertTrue(ProcParsers.parseStat(bytes, bytes.size, out))
        // Field 14 is utime and 15 stime, counted from the process id; 24 is resident pages.
        assertEquals(11L + 12L, out[0])
        assertEquals(999L, out[1])
    }

    @Test fun `proc stat that does not parse is refused`() {
        val out = LongArray(2)
        val noParen = "1234 nothing here".toByteArray()
        assertFalse(ProcParsers.parseStat(noParen, noParen.size, out))
        val short = "1 (a) S 1 2 3".toByteArray()
        assertFalse(ProcParsers.parseStat(short, short.size, out))
    }

    @Test fun `meminfo gives the available figure or unknown`() {
        val good = "MemTotal:  3985648 kB\nMemFree:  100 kB\nMemAvailable:  2041212 kB\nBuffers: 1 kB\n".toByteArray()
        assertEquals(2_041_212, ProcParsers.parseMemAvailableKb(good, good.size))
        val missing = "MemTotal: 1 kB\n".toByteArray()
        assertEquals(RECORDER_UNKNOWN, ProcParsers.parseMemAvailableKb(missing, missing.size))
    }

    @Test fun `own cpu is tenths of a percent of one core`() {
        assertEquals(500, ProcParsers.cpuTenths(deltaTicks = 50L, clockTicksPerSecond = 100L, elapsedMs = 1_000L))
        assertEquals(RECORDER_UNKNOWN, ProcParsers.cpuTenths(deltaTicks = 50L, clockTicksPerSecond = 100L, elapsedMs = 0L))
        assertEquals(RECORDER_UNKNOWN, ProcParsers.cpuTenths(deltaTicks = -1L, clockTicksPerSecond = 100L, elapsedMs = 1_000L))
    }
}
