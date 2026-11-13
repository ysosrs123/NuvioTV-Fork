package com.nuvio.tv.data.repository

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitRunGenerationsTest {
    private fun dir() = Files.createTempDirectory("runs").toFile()

    @Test fun `three runs of files are kept and shift along at each start`() {
        val dir = dir()
        File(dir, "now.txt").writeText("run 2")
        File(dir, "prev.txt").writeText("run 1")
        File(dir, "old.txt").writeText("run 0")
        val files = RunFiles.rotate(dir, "now.txt", "prev.txt", "old.txt")
        assertFalse(files[0].exists())
        assertEquals("run 2", files[1].readText())
        assertEquals("run 1", files[2].readText())
    }

    @Test fun `the file of the process that ended is picked, else none`() {
        assertEquals(1, RunFiles.byPid(listOf(300, 200, null), exitPid = 200))
        assertEquals(-1, RunFiles.byPid(listOf(300, 200), exitPid = 0))
        assertEquals(-1, RunFiles.byPid(listOf(300, null), exitPid = 200))
        assertEquals(200, RunFiles.pidOf(listOf("time=1", "pid=200", "Java heap: 1 MB")))
        assertNull(RunFiles.pidOf(listOf("time=1", "Java heap: 1 MB")))
    }

    @Test fun `the heartbeat of the exiting process is used even when it is older than the usual window`() {
        val dir = dir()
        val current = File(dir, "c.txt").apply { writeText("time=900000\npid=300\nJava heap: 10 of 384 MB") }
        val earlier = File(dir, "p.txt").apply { writeText("time=200000\npid=200\nJava heap: 50 of 384 MB") }
        val older = File(dir, "o.txt").apply { writeText("time=100000\npid=100\nJava heap: 70 of 384 MB") }
        val lines = ArcHeartbeat.linesFor(listOf(current, earlier, older), exitMs = 800_000L, exitPid = 200)!!
        assertEquals(listOf(ArcHeartbeat.HEADER, "Java heap: 50 of 384 MB"), lines)
        assertEquals("Java heap: 70 of 384 MB", ArcHeartbeat.linesFor(listOf(current, earlier, older), 150_000L, exitPid = 100)!![1])
        assertNull(ArcHeartbeat.linesFor(listOf(current, earlier, older), exitMs = 800_000L, exitPid = 999))
        assertEquals("Java heap: 50 of 384 MB", ArcHeartbeat.linesFor(listOf(current, earlier, older), 230_000L, exitPid = 999)!![1])
    }

    @Test fun `a freeze that began long before Android ended the app keeps its stalls and last screens`() {
        val dir = dir()
        val stall = ArcMainWatchdog.Stall(
            startedAtMs = 100_000L, stuckMs = 400_000L, frames = listOf("at a.b.c(SourceFile:1)"),
            screens = listOf("14:02:11 screen=Player", "14:02:15 play")
        )
        val earlier = File(dir, "p.txt").apply { writeText(ArcMainWatchdog.format(listOf(stall), pid = 200)) }
        val current = File(dir, "c.txt").apply { writeText(ArcMainWatchdog.format(emptyList(), pid = 300)) }
        val lines = ArcMainWatchdog.linesFor(listOf(current, earlier), exitMs = 505_000L, exitPid = 200)!!
        assertTrue(lines[0].startsWith("--- Arc watchdog: screen thread stuck 400000 ms"))
        assertTrue(lines.contains(ArcMainWatchdog.SCREENS_HEADER))
        assertTrue(lines.contains("14:02:15 play"))
        assertNull(ArcMainWatchdog.linesFor(listOf(current, earlier), exitMs = 505_000L))
        assertEquals(listOf(stall), ArcMainWatchdog.parse(earlier.readText()))
    }

    @Test fun `an exit with no trace still carries the facts and the snapshot`() {
        val facts = listOf("--- Arc facts ---", "Build commit: abc123")
        val heartbeat = listOf(ArcHeartbeat.HEADER, "Recent: 14:02:11 screen=Player")
        assertEquals(facts, ExitRules.traceLines(emptyList(), facts, emptyList(), emptyList()))
        assertEquals(facts + heartbeat, ExitRules.traceLines(emptyList(), facts, heartbeat, emptyList()))
        val own = listOf("--- Arc watchdog: screen thread stuck 5000 ms (1 of 1) ---")
        assertEquals(own + facts, ExitRules.traceLines(own, facts, emptyList(), emptyList()))
    }

    @Test fun `the exit's process id comes through from Android's record`() {
        val record = ExitRecord(android.app.ApplicationExitInfo.REASON_ANR, android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND, 0, null, 5_000L, pid = 4242)
        assertEquals(4242, ExitRules.unexpected(record)!!.pid)
    }

    @Test fun `the native memory line names each item and marks what could not be read`() {
        val names = listOf("ExoPlayer:Loader:ProgressiveMediaPeriod", "ExoPlayer:Loader:ProgressiveMediaPeriod", "DefaultDispatcher-worker-3", "ExoPlayer:Playback", "main")
        val line = NativeMemoryItems.format(
            NativeMemoryItems.Items(
                rangeHeldBytes = 48L shl 20, rangePieces = 12, rangeIdleBytes = 8L shl 20, bufferBytes = 3L shl 20,
                pictureBytes = null, libassPlayers = 1, pluginRuntime = false,
                loaderThreads = NativeMemoryItems.count(names, "ExoPlayer:Loader"),
                dispatcherWorkers = NativeMemoryItems.count(names, "DefaultDispatcher-worker"),
                playbackThreads = 1, rangeWorkers = 0, openFiles = null
            )
        )
        assertEquals(
            "Native memory items: range pieces 48 MB (12), range pool 8 MB, player buffer 3 MB, pictures ? MB, libass 1, " +
                "plugin runtime off, loader threads 2, dispatcher workers 1, playback threads 1, range workers 0, open files ?",
            line
        )
    }
}
