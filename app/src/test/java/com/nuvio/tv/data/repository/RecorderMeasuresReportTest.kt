package com.nuvio.tv.data.repository

import com.nuvio.tv.core.report.RecorderSnapshot
import com.nuvio.tv.core.report.SampleRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderMeasuresReportTest {

    @Test
    fun logLines_keepOnlyMemoryCleanUpsAndArcsOwnSoundOutput() {
        val kept = PlaybackReportLog.keep(
            listOf(
                "11-11 20:15:01.100 I/nuvio.tv.test( 4321): Background concurrent copying GC freed 1200(27MB) LOS objects, paused 827ms total 1.702s",
                "11-11 20:15:01.300 I/IecPassthrough( 4321): iec_health mode=TRUEHD underruns=3 head=100 written=200",
                "11-11 20:15:01.400 D/SomeScreen( 4321): opened Game of Thrones S05E02 https://example.invalid/f?token=abc",
                "11-11 20:15:02.000 W/art( 4321): WaitForGcToComplete blocked Alloc on Background for 1.012s"
            )
        )
        assertEquals(3, kept.size)
        assertTrue(kept.none { "Thrones" in it || "token" in it })
    }

    @Test
    fun swapTotal_isReadInMegabytes_andAbsentWithoutTheLine() {
        assertEquals(1023L, swapTotalMb("MemTotal: 1989104 kB\nSwapTotal:       1048572 kB\nSwapFree: 0 kB\n"))
        assertEquals(0L, swapTotalMb("SwapTotal:             0 kB\n"))
        assertNull(swapTotalMb("MemTotal: 1989104 kB\n"))
        assertNull(swapTotalMb(null))
    }

    @Test
    fun trimmingOldSamples_trimsTheNewColumnsToo() {
        val ring = SampleRing(capacity = 4)
        for (i in 1..4) {
            ring.add(
                timeMs = i * 1_000L, readyAheadMs = 0, droppedFrames = 0, audioUnderruns = 0, ownCpuTenths = 0,
                rssKb = 0, javaHeapKb = 0, nativeHeapKb = 0, memAvailableKb = 0, bufferCapKb = 0, thermalStatus = 0,
                loaderActive = 0, tunnelled = 0, playing = 1, swapFreeKb = i, gcMs = i * 10, audioQueueMs = i * 100,
                audioHeadMs = i * 1_000, audioClockSource = 2
            )
        }
        val sent = RecorderSnapshot(enabled = true, samples = ring.snapshot(5_000L), events = emptyList()).toDto()
        val trimmed = sent.samples!!.dropOldest(1)
        assertEquals(listOf<Int?>(2, 3, 4), trimmed.swapFreeKb)
        assertEquals(listOf<Int?>(20, 30, 40), trimmed.gcMs)
        assertEquals(listOf<Int?>(200, 300, 400), trimmed.audioQueueMs)
        assertEquals(listOf<Int?>(2_000, 3_000, 4_000), trimmed.audioHeadMs)
        assertEquals(listOf<Int?>(2, 2, 2), trimmed.audioClockSource)
        assertEquals(listOf<Int?>(null, null, null), trimmed.audioLatencyMs)
        assertEquals(trimmed.ageMs.size, trimmed.audioWriteStalls.size)
        assertEquals(trimmed.ageMs.size, trimmed.busyFlags.size)
        assertEquals(trimmed.ageMs.size, trimmed.subtitleCues.size)
    }
}
