package com.nuvio.tv.data.repository

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcMainWatchdogTest {
    private fun stall(at: Long, ms: Long, top: String = "at a.b.c(SourceFile:1)") =
        ArcMainWatchdog.Stall(at, ms, listOf(top, "at android.os.Looper.loop(Looper.java:294)"), listOf("11-10 14:40:59.123 I/Tag: line"))

    @Test fun savedStallsReadBackTheSame() {
        val stalls = listOf(stall(1_000L, 2_500L), stall(5_000L, 4_000L))
        assertEquals(stalls, ArcMainWatchdog.parse(ArcMainWatchdog.format(stalls)))
    }

    @Test fun onlyTheWorstThreeAreKeptAndAStallReplacesItsEarlierCapture() {
        val kept = mutableListOf<ArcMainWatchdog.Stall>()
        ArcMainWatchdog.keep(kept, stall(1L, 2_000L))
        ArcMainWatchdog.keep(kept, stall(1L, 4_000L))
        assertEquals(listOf(4_000L), kept.map { it.stuckMs })
        listOf(2L to 3_000L, 3L to 2_100L, 4L to 9_000L).forEach { (at, ms) -> ArcMainWatchdog.keep(kept, stall(at, ms)) }
        assertEquals(listOf(9_000L, 4_000L, 3_000L), kept.map { it.stuckMs })
    }

    @Test fun aFreezeReportGetsStallsFromTheMinuteBeforeItWorstFirst() {
        val dir = Files.createTempDirectory("stalls").toFile()
        val earlier = File(dir, "previous.txt").apply { writeText(ArcMainWatchdog.format(listOf(stall(100_000L, 3_000L, "at x.y.z(SourceFile:2)")))) }
        val current = File(dir, "current.txt").apply { writeText(ArcMainWatchdog.format(listOf(stall(110_000L, 5_000L), stall(1_000L, 9_000L)))) }
        val lines = ArcMainWatchdog.linesFor(listOf(earlier, current), exitMs = 120_000L)!!
        assertTrue(lines[0].startsWith("--- Arc watchdog: screen thread stuck 5000 ms (1 of 2)"))
        assertEquals("at a.b.c(SourceFile:1)", lines[1])
        assertTrue(lines.any { it.startsWith("--- Arc watchdog: screen thread stuck 3000 ms (2 of 2)") })
        assertTrue(lines.contains(ArcCrashFile.LOG_HEADER))
        assertNull(ArcMainWatchdog.linesFor(listOf(current), exitMs = 10_000_000L))
    }
}
