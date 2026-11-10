package com.nuvio.tv.core.report

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderEventsTest {
    @Test fun `an event line carries wall time and film position when they are known`() {
        val ring = EventRing(4)
        ring.add(1_000L, RecorderEvent.DROPPED, "count=12 total=40", wallMs = 1_731_000_000_123L, positionMs = 3_723_400L)
        ring.add(1_500L, RecorderEvent.REBUFFER, "start aheadMs=0", wallMs = 1_731_000_000_623L)
        ring.add(2_000L, RecorderEvent.PLAY, "")
        val lines = ring.snapshot(3_000L).map { it.text }
        assertEquals("-2000ms dropped_frames wall=1731000000123 pos=3723400 count=12 total=40", lines[0])
        assertEquals("-1500ms rebuffer wall=1731000000623 start aheadMs=0", lines[1])
        assertEquals("-1000ms play", lines[2])
    }

    @Test fun `a replaced seek line takes the new wall time and position`() {
        val ring = EventRing(3)
        ring.add(100L, RecorderEvent.SEEK, "fromMs=0 toMs=10", wallMs = 5L, positionMs = 10L)
        ring.replaceLast(300L, "fromMs=0 toMs=40", wallMs = 7L, positionMs = 40L)
        val line = ring.snapshot(300L).single()
        assertEquals(7L, line.wallMs)
        assertEquals(40L, line.positionMs)
        assertEquals("-0ms seek wall=7 pos=40 fromMs=0 toMs=40", line.text)
    }

    @Test fun `every timed kind has its own label`() {
        val labels = RecorderEvent.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        listOf(
            "mark", "dropped_frames", "skipped_frames", "audio_clock_jump", "filler", "rebuffer", "tunnel",
            "audio_path", "hdmi", "audio_delay", "surround_format"
        ).forEach { assertTrue(it, it in labels) }
    }

    @Test fun `the extra sample columns keep their values and unknown ones stay unknown`() {
        val ring = SampleRing(5)
        ring.add(
            timeMs = 0L, readyAheadMs = 10, droppedFrames = 1, audioUnderruns = 0, ownCpuTenths = 5, rssKb = 1,
            javaHeapKb = 2, nativeHeapKb = 3, memAvailableKb = 4, bufferCapKb = 5, thermalStatus = 0, loaderActive = 1,
            tunnelled = 0, playing = 1,
            skippedFrames = 7, frameLeadUs = -1_500, audioJitterAvgMs = 3, audioJitterMaxMs = 41, fillerCount = 2,
            audioPositionMs = 61_000, speedPercent = 100, connections = 4, watchedMs = 62_000
        )
        ring.add(
            timeMs = 1_000L, readyAheadMs = 10, droppedFrames = 1, audioUnderruns = 0, ownCpuTenths = 5, rssKb = 1,
            javaHeapKb = 2, nativeHeapKb = 3, memAvailableKb = 4, bufferCapKb = 5, thermalStatus = 0, loaderActive = 1,
            tunnelled = 0, playing = 1
        )
        val snapshot = ring.snapshot(1_000L)
        assertArrayEquals(intArrayOf(7, RECORDER_UNKNOWN), snapshot.skippedFrames)
        assertArrayEquals(intArrayOf(-1_500, RECORDER_UNKNOWN), snapshot.frameLeadUs)
        assertArrayEquals(intArrayOf(3, RECORDER_UNKNOWN), snapshot.audioJitterAvgMs)
        assertArrayEquals(intArrayOf(41, RECORDER_UNKNOWN), snapshot.audioJitterMaxMs)
        assertArrayEquals(intArrayOf(2, RECORDER_UNKNOWN), snapshot.fillerCount)
        assertArrayEquals(intArrayOf(61_000, RECORDER_UNKNOWN), snapshot.audioPositionMs)
        assertArrayEquals(intArrayOf(100, RECORDER_UNKNOWN), snapshot.speedPercent)
        assertArrayEquals(intArrayOf(4, RECORDER_UNKNOWN), snapshot.connections)
        assertArrayEquals(intArrayOf(62_000, RECORDER_UNKNOWN), snapshot.watchedMs)
        assertNull(snapshot.frameBuckets)
    }

    @Test fun `frame buckets follow the samples as the ring wraps`() {
        val ring = SampleRing(2, withFrameBuckets = true)
        for (i in 1..3) {
            ring.add(
                timeMs = i * 1_000L, readyAheadMs = 0, droppedFrames = 0, audioUnderruns = 0, ownCpuTenths = 0, rssKb = 0,
                javaHeapKb = 0, nativeHeapKb = 0, memAvailableKb = 0, bufferCapKb = 0, thermalStatus = 0, loaderActive = 0,
                tunnelled = 0, playing = 1,
                frameBuckets = IntArray(FRAME_BUCKET_COUNT) { bucket -> i * 10 + bucket }
            )
        }
        val flat = ring.snapshot(3_000L).frameBuckets!!
        assertEquals(2 * FRAME_BUCKET_COUNT, flat.size)
        assertArrayEquals(IntArray(FRAME_BUCKET_COUNT) { 20 + it }, flat.copyOfRange(0, FRAME_BUCKET_COUNT))
        assertArrayEquals(IntArray(FRAME_BUCKET_COUNT) { 30 + it }, flat.copyOfRange(FRAME_BUCKET_COUNT, flat.size))
    }

    @Test fun `a thirty minute recording fits the sample ring and stays small`() {
        val ring = SampleRing(RECORDING_SAMPLE_CAPACITY, withFrameBuckets = true)
        repeat(RECORDING_SAMPLE_CAPACITY + 100) {
            ring.add(
                timeMs = it * 1_000L, readyAheadMs = 1, droppedFrames = 0, audioUnderruns = 0, ownCpuTenths = 0, rssKb = 0,
                javaHeapKb = 0, nativeHeapKb = 0, memAvailableKb = 0, bufferCapKb = 0, thermalStatus = 0, loaderActive = 0,
                tunnelled = 0, playing = 1
            )
        }
        val snapshot = ring.snapshot(2_000_000L)
        assertEquals(1_800, snapshot.count)
        val bytes = snapshot.count * (8 + 4 * 14 + 3 + 4 * 9 + 4 * FRAME_BUCKET_COUNT)
        assertTrue("$bytes bytes of columns", bytes < 400_000)
    }
}
