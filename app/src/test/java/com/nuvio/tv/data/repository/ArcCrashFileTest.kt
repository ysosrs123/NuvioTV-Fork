package com.nuvio.tv.data.repository

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcCrashFileTest {
    private fun saved(atMs: Long): File {
        val dir = Files.createTempDirectory("crash").toFile()
        val error = IllegalStateException("boom", RuntimeException("inner"))
        return File(dir, "arc-last-crash.txt").apply {
            writeText(ArcCrashFile.lines(atMs, "main", error, playing = true, facts = listOf("Threads: 42")).joinToString("\n"))
        }
    }

    @Test fun aCrashJustBeforeTheExitIsFound() {
        val lines = ArcCrashFile.linesFor(saved(1_000_000L), exitMs = 1_030_000L)!!
        assertEquals("Crash in thread: main", lines[0])
        assertEquals("Playing: yes", lines[1])
        assertEquals("Threads: 42", lines[2])
        assertTrue(lines[3].startsWith("java.lang.IllegalStateException: boom"))
        assertTrue(lines.any { it.startsWith("Caused by: java.lang.RuntimeException: inner") })
    }

    @Test fun aCrashFromAnotherExitIsNotUsed() {
        assertNull(ArcCrashFile.linesFor(saved(1_000_000L), exitMs = 1_000_000L + 10 * 60_000L))
        assertNull(ArcCrashFile.linesFor(saved(1_000_000L), exitMs = 1_000_000L - 60_000L))
    }

    @Test fun recentScreensAndTheLogComeAfterTheStack() {
        val lines = ArcCrashFile.lines(
            1L, "main", IllegalStateException("boom"), playing = false, facts = emptyList(),
            recent = listOf("10:00:00 screen home"), log = listOf("11-10 10:00:01.000 W/Tag: last")
        )
        val stack = lines.indexOfFirst { it.startsWith("java.lang.IllegalStateException") }
        val recent = lines.indexOf("10:00:00 screen home")
        val log = lines.indexOf(ArcCrashFile.LOG_HEADER)
        assertTrue(stack in 1 until recent)
        assertTrue(recent < log)
        assertEquals("11-10 10:00:01.000 W/Tag: last", lines.last())
        val file = File(Files.createTempDirectory("crash").toFile(), "c.txt").apply { writeText(lines.joinToString("\n")) }
        assertEquals(1L, ArcCrashFile.savedAtMs(file))
    }

    @Test fun noFileGivesNothing() {
        assertNull(ArcCrashFile.linesFor(File(Files.createTempDirectory("crash").toFile(), "none.txt"), 1L))
    }
}
