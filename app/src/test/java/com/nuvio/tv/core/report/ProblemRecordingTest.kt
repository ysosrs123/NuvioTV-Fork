package com.nuvio.tv.core.report

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemRecordingTest {
    @Test fun `a frame gap lands in the bucket whose upper edge it is under`() {
        assertEquals(0, frameBucketIndex(16))
        assertEquals(1, frameBucketIndex(18))
        assertEquals(2, frameBucketIndex(33))
        assertEquals(3, frameBucketIndex(41))
        assertEquals(4, frameBucketIndex(79))
        assertEquals(FRAME_BUCKET_COUNT - 1, frameBucketIndex(80))
        assertEquals(FRAME_BUCKET_COUNT - 1, frameBucketIndex(5_000))
        assertEquals(FRAME_BUCKET_COUNT, FRAME_BUCKET_EDGES_MS.size + 1)
    }

    @Test fun `frames are folded into counts and a drain empties them`() {
        val counter = FrameBucketCounter()
        var now = 1_000_000_000L
        counter.onFrame(now)
        repeat(3) { now += 16_000_000L; counter.onFrame(now) }
        now += 45_000_000L
        counter.onFrame(now)
        val out = IntArray(FRAME_BUCKET_COUNT)
        counter.drainInto(out)
        assertArrayEquals(intArrayOf(3, 0, 0, 1, 0, 0), out)
        counter.drainInto(out)
        assertArrayEquals(IntArray(FRAME_BUCKET_COUNT), out)
    }

    @Test fun `the first frame after a restart has no gap to count`() {
        val counter = FrameBucketCounter()
        counter.onFrame(5_000_000_000L)
        counter.restart()
        counter.onFrame(9_000_000_000L)
        val out = IntArray(FRAME_BUCKET_COUNT)
        counter.drainInto(out)
        assertEquals(0, out.sum())
    }

    @Test fun `marks keep their order and times and stop at the limit without losing earlier ones`() {
        val marks = RecordingMarks(max = 3)
        assertEquals(1_000L, marks.add(wallMs = 1_000L, positionMs = 60_000L, elapsedMs = 5_000L)!!.wallMs)
        marks.add(2_000L, 61_000L, 6_000L)
        marks.add(3_000L, -1L, 7_000L)
        assertNull(marks.add(4_000L, 1L, 8_000L))
        val kept = marks.snapshot()
        assertEquals(listOf(1_000L, 2_000L, 3_000L), kept.map { it.wallMs })
        assertEquals(listOf(60_000L, 61_000L, -1L), kept.map { it.positionMs })
        assertEquals(listOf(5_000L, 6_000L, 7_000L), kept.map { it.elapsedMs })
    }

    @Test fun `a recording ends at thirty minutes or when storage runs short`() {
        val plenty = 2L * 1024 * 1024 * 1024
        assertNull(recordingStopReason(RECORDING_MAX_MS - 1, plenty))
        assertEquals(RecordingEnd.TIME_UP, recordingStopReason(RECORDING_MAX_MS, plenty))
        assertEquals(RecordingEnd.LOW_STORAGE, recordingStopReason(1_000L, RECORDING_MIN_FREE_BYTES - 1))
        assertNull(recordingStopReason(1_000L, RECORDING_MIN_FREE_BYTES))
        assertNull(recordingStopReason(1_000L, -1L))
        assertEquals(30L * 60_000L, RECORDING_MAX_MS)
        assertEquals(300L * 1024 * 1024, RECORDING_MIN_FREE_BYTES)
    }

    @Test fun `the clock reads minutes and seconds`() {
        assertEquals("0:00", recordingClock(0L))
        assertEquals("0:09", recordingClock(9_999L))
        assertEquals("12:48", recordingClock(768_400L))
        assertEquals("30:00", recordingClock(RECORDING_MAX_MS))
        assertEquals("0:00", recordingClock(-5L))
    }

    @Test fun `clock jumps are handed over once and only the newest survive a burst`() {
        val queue = ClockJumpQueue(capacity = 4)
        for (i in 1..6) queue.offer(i * 10, wallMs = i.toLong(), positionMs = i * 100L)
        val seen = ArrayList<Int>()
        queue.drain { size, _, _ -> seen.add(size) }
        assertEquals(listOf(30, 40, 50, 60), seen)
        seen.clear()
        queue.drain { size, _, _ -> seen.add(size) }
        assertTrue(seen.isEmpty())
        queue.offer(-45, 9L, 900L)
        queue.drain { size, wall, position ->
            assertEquals(-45, size)
            assertEquals(9L, wall)
            assertEquals(900L, position)
            seen.add(size)
        }
        assertEquals(listOf(-45), seen)
    }

    @Test fun `frame lead is the average over the frames since the last read`() {
        val tracker = FrameLeadTracker()
        assertEquals(RECORDER_UNKNOWN, tracker.update(totalUs = 0L, count = 0))
        assertEquals(2_000, tracker.update(totalUs = 200_000L, count = 100))
        assertEquals(4_000, tracker.update(totalUs = 600_000L, count = 200))
        assertEquals(RECORDER_UNKNOWN, tracker.update(totalUs = 600_000L, count = 200))
        assertEquals(RECORDER_UNKNOWN, tracker.update(totalUs = 50_000L, count = 50))
        assertEquals(1_000, tracker.update(totalUs = 150_000L, count = 150))
    }

    @Test fun `the remote job adds a mark while recording and opens the panel otherwise`() {
        assertEquals(ReportKeyAction.ADD_MARK, reportKeyAction(reportsAvailable = true, recording = true))
        assertEquals(ReportKeyAction.ADD_MARK, reportKeyAction(reportsAvailable = false, recording = true))
        assertEquals(ReportKeyAction.OPEN_PANEL, reportKeyAction(reportsAvailable = true, recording = false))
        assertEquals(ReportKeyAction.UNAVAILABLE, reportKeyAction(reportsAvailable = false, recording = false))
    }
}
