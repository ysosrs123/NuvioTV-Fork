package com.nuvio.tv.core.report

import com.nuvio.tv.ui.screens.player.iec.IecAudioTimestampClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecorderMeasuresTest {

    private val meminfo = (
        "MemTotal:        1989104 kB\nMemFree:           41236 kB\nMemAvailable:     512340 kB\n" +
            "Buffers:            3120 kB\nCached:           498712 kB\nSwapCached:        20480 kB\n" +
            "Active:           612000 kB\nInactive:         402000 kB\nSwapTotal:       1048572 kB\nSwapFree:              0 kB\n"
        ).toByteArray(Charsets.US_ASCII)

    @Test
    fun swapFree_isReadNextToMemAvailable() {
        assertEquals(512_340, ProcParsers.parseMemAvailableKb(meminfo, meminfo.size))
        assertEquals(0, ProcParsers.parseSwapFreeKb(meminfo, meminfo.size))
    }

    @Test
    fun swapFree_isUnknownWhenTheFileStopsBeforeIt() {
        assertEquals(RECORDER_UNKNOWN, ProcParsers.parseSwapFreeKb(meminfo, 120))
    }

    @Test
    fun newColumns_comeBackInOrder_andStayUnknownWhenNotGiven() {
        val ring = SampleRing(capacity = 4)
        ring.addSample(1_000L, swapFreeKb = 0, gcCount = 7, gcMs = 1_200, gcBlockingMs = 800, audioQueueMs = 35,
            busyFlags = ReportBusy.THUMBNAILS, subtitleCues = 2, subtitlePictureCues = 2)
        ring.addSample(2_000L)
        val held = ring.snapshot(3_000L)
        assertEquals(listOf(0, RECORDER_UNKNOWN), held.swapFreeKb.toList())
        assertEquals(listOf(7, RECORDER_UNKNOWN), held.gcCount.toList())
        assertEquals(listOf(1_200, RECORDER_UNKNOWN), held.gcMs.toList())
        assertEquals(listOf(800, RECORDER_UNKNOWN), held.gcBlockingMs.toList())
        assertEquals(listOf(35, RECORDER_UNKNOWN), held.audioQueueMs.toList())
        assertEquals(listOf(ReportBusy.THUMBNAILS, RECORDER_UNKNOWN), held.busyFlags.toList())
        assertEquals(listOf(2, RECORDER_UNKNOWN), held.subtitleCues.toList())
        assertEquals(listOf(2, RECORDER_UNKNOWN), held.subtitlePictureCues.toList())
    }

    @Test
    fun underruns_countOnlyWhatWasAdded() {
        assertEquals(0, underrunsAdded(RECORDER_UNKNOWN, 5))
        assertEquals(3, underrunsAdded(5, 8))
        assertEquals(0, underrunsAdded(8, 8))
        assertEquals(0, underrunsAdded(8, 1))
    }

    @Test
    fun soundClockColumns_comeBackInOrder_andStayUnknownWhenNotGiven() {
        val ring = SampleRing(capacity = 4)
        ring.add(
            timeMs = 1_000L, readyAheadMs = 0, droppedFrames = 0, audioUnderruns = 0, ownCpuTenths = 0, rssKb = 0,
            javaHeapKb = 0, nativeHeapKb = 0, memAvailableKb = 0, bufferCapKb = 0, thermalStatus = 0, loaderActive = 0,
            tunnelled = 0, playing = 1, audioHeadMs = 3_560_000, audioLatencyMs = 120, audioClockSource = 2,
            audioWriteStalls = 4
        )
        ring.addSample(2_000L)
        val held = ring.snapshot(3_000L)
        assertEquals(listOf(3_560_000, RECORDER_UNKNOWN), held.audioHeadMs.toList())
        assertEquals(listOf(120, RECORDER_UNKNOWN), held.audioLatencyMs.toList())
        assertEquals(listOf(2, RECORDER_UNKNOWN), held.audioClockSource.toList())
        assertEquals(listOf(4, RECORDER_UNKNOWN), held.audioWriteStalls.toList())
    }

    @Test
    fun clockSource_namesMatchTheSender_andOnlyARealChangeIsReported() {
        assertEquals("head", clockSourceLabel(IecAudioTimestampClock.SOURCE_HEAD))
        assertEquals("timestamp", clockSourceLabel(IecAudioTimestampClock.SOURCE_TIMESTAMP))
        assertEquals("steadied", clockSourceLabel(IecAudioTimestampClock.SOURCE_STEADIED))
        assertNull(clockSourceLabel(IecAudioTimestampClock.SOURCE_AT_WRITTEN))
        assertNull(clockSourceLabel(RECORDER_UNKNOWN))

        assertNull(clockSourceChange(RECORDER_UNKNOWN, IecAudioTimestampClock.SOURCE_TIMESTAMP))
        assertNull(clockSourceChange(IecAudioTimestampClock.SOURCE_TIMESTAMP, IecAudioTimestampClock.SOURCE_TIMESTAMP))
        assertNull(clockSourceChange(IecAudioTimestampClock.SOURCE_TIMESTAMP, IecAudioTimestampClock.SOURCE_AT_WRITTEN))
        assertEquals(
            "steadied",
            clockSourceChange(IecAudioTimestampClock.SOURCE_TIMESTAMP, IecAudioTimestampClock.SOURCE_STEADIED)
        )
    }

    @Test
    fun busyFlags_areOneBitPerKindOfWork() {
        assertEquals(0, ReportBusy.flagsOf(thumbnailsWorking = false, syncRunning = 0, subtitleSyncRunning = 0))
        assertEquals(ReportBusy.THUMBNAILS, ReportBusy.flagsOf(true, 0, 0))
        assertEquals(ReportBusy.SYNC or ReportBusy.SUBTITLE_SYNC, ReportBusy.flagsOf(false, 2, 1))
    }

    private fun SampleRing.addSample(
        timeMs: Long,
        swapFreeKb: Int = RECORDER_UNKNOWN,
        gcCount: Int = RECORDER_UNKNOWN,
        gcMs: Int = RECORDER_UNKNOWN,
        gcBlockingMs: Int = RECORDER_UNKNOWN,
        audioQueueMs: Int = RECORDER_UNKNOWN,
        busyFlags: Int = RECORDER_UNKNOWN,
        subtitleCues: Int = RECORDER_UNKNOWN,
        subtitlePictureCues: Int = RECORDER_UNKNOWN
    ) = add(
        timeMs = timeMs, readyAheadMs = 0, droppedFrames = 0, audioUnderruns = 0, ownCpuTenths = 0, rssKb = 0,
        javaHeapKb = 0, nativeHeapKb = 0, memAvailableKb = 0, bufferCapKb = 0, thermalStatus = 0, loaderActive = 0,
        tunnelled = 0, playing = 1, swapFreeKb = swapFreeKb, gcCount = gcCount, gcMs = gcMs, gcBlockingMs = gcBlockingMs,
        audioQueueMs = audioQueueMs, busyFlags = busyFlags, subtitleCues = subtitleCues,
        subtitlePictureCues = subtitlePictureCues
    )
}
