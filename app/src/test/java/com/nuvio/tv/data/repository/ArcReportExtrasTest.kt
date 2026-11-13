package com.nuvio.tv.data.repository

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcReportExtrasTest {
    private fun exit(kind: UnexpectedExitKind, status: Int = 0) = UnexpectedExit(kind, 0, 100, status, null, 10_000L)

    @Test fun threadPoolsAreCountedWithoutTheirNumbers() {
        val names = listOf("DefaultDispatcher-worker-1", "DefaultDispatcher-worker-12", "OkHttp Dispatcher", "main", "plugin-worker", "plugin-worker")
        assertEquals("DefaultDispatcher-worker 2, plugin-worker 2, OkHttp Dispatcher 1, main 1", ArcHeartbeat.pools(names))
    }

    @Test fun theSnapshotFromTheMinutesBeforeTheExitIsUsed() {
        val dir = Files.createTempDirectory("hb").toFile()
        val current = File(dir, "c.txt").apply { writeText("time=200000\nJava heap: 50 of 384 MB") }
        val earlier = File(dir, "p.txt").apply { writeText("time=100000\nJava heap: 70 of 384 MB") }
        val lines = ArcHeartbeat.linesFor(listOf(current, earlier), exitMs = 230_000L)!!
        assertEquals(ArcHeartbeat.HEADER, lines[0])
        assertEquals("Java heap: 50 of 384 MB", lines[1])
        assertEquals("Java heap: 70 of 384 MB", ArcHeartbeat.linesFor(listOf(current, earlier), exitMs = 120_000L)!![1])
        assertNull(ArcHeartbeat.linesFor(listOf(current, earlier), exitMs = 2_000_000L))
    }

    @Test fun factsNameTheBuildTheSignalAndRecentExits() {
        val lines = ExitFacts.lines(
            exit(UnexpectedExitKind.NativeCrash, status = 6), "abc123def", "onn/jarvis/SNA:14/x/y:user/release-keys", "2026-03-05",
            mapOf(UnexpectedExitKind.NotResponding to 3, UnexpectedExitKind.Crash to 1)
        )
        assertEquals("Build commit: abc123def", lines[1])
        assertTrue(lines.contains("Signal: SIGABRT (6)"))
        assertEquals("Unexpected exits in the last 7 days: crash 1, anr 3", lines.last())
        assertTrue(ExitFacts.lines(exit(UnexpectedExitKind.Crash), "", null, null, emptyMap()).none { it.startsWith("Signal") })
    }

    @Test fun factsNameTheBuildThatEndedWhenANewerOneSendsTheReport() {
        val dir = Files.createTempDirectory("builds").toFile()
        val log = File(dir, "arc-builds.txt")
        val old = ArcBuildLog.Entry(1_000L, 1619, "c2aff7c2e", "1.1.0-arc.2")
        val new = ArcBuildLog.Entry(9_000L, 1626, "959c70a44", "1.1.0-arc.3")
        ArcBuildLog.note(log, old)
        ArcBuildLog.note(log, old.copy(sinceMs = 5_000L))
        ArcBuildLog.note(log, new)
        assertNull(ArcBuildLog.at(log, 500L))
        assertEquals(old, ArcBuildLog.at(log, 8_000L))
        assertEquals(new, ArcBuildLog.at(log, 10_000L))

        val crash = exit(UnexpectedExitKind.NotResponding)
        val after = ExitFacts.lines(crash, "959c70a44", null, null, emptyMap(), ArcBuildLog.at(log, 8_000L), "1.1.0-arc.3 (1626)")
        assertEquals("Build commit: c2aff7c2e", after[1])
        assertEquals("Ended on: 1.1.0-arc.2 (1619), report sent from 1.1.0-arc.3 (1626)", after[2])
        val same = ExitFacts.lines(crash, "959c70a44", null, null, emptyMap(), ArcBuildLog.at(log, 10_000L), "1.1.0-arc.3 (1626)")
        assertEquals("Build commit: 959c70a44", same[1])
        assertTrue(same.none { it.startsWith("Ended on") })
    }

    @Test fun theBuildLogKeepsOnlyTheLastFewBuilds() {
        val log = File(Files.createTempDirectory("builds").toFile(), "arc-builds.txt")
        (1..9).forEach { ArcBuildLog.note(log, ArcBuildLog.Entry(it * 1_000L, 1600L + it, "c$it", "v$it")) }
        assertEquals(6, log.readLines().size)
        assertNull(ArcBuildLog.at(log, 3_500L))
        assertEquals(1609L, ArcBuildLog.at(log, 99_000L)!!.versionCode)
    }

    @Test fun anAnrDumpPutsMainAndArcThreadsFirstAndShortensIdleOnes() {
        val dump = listOf(
            "Subject: Input dispatching timed out",
            "DALVIK THREADS (4):",
            "\"Signal Catcher\" daemon prio=10 tid=7 WaitingInMainSignalCatcherLoop",
            "  native: #00 pc 1",
            "\"OkHttp TaskRunner\" daemon prio=5 tid=20 TimedWaiting",
            "  at java.lang.Object.wait(Native method)",
            "\"main\" prio=5 tid=1 Runnable",
            "  at kotlin.text.Regex.find(SourceFile:8)",
            "\"DefaultDispatcher-worker-12\" daemon prio=5 tid=30 Runnable",
            "  at com.nuvio.tv.core.Foo.bar(SourceFile:1)",
            "\"HeapTaskDaemon\" daemon prio=5 tid=9 WaitingPerformingGc",
            "  at dalvik.system.VMRuntime.runHeapTasks(Native method)"
        )
        val ordered = ExitTrace.ordered(dump)
        assertEquals(dump.take(2), ordered.take(2))
        assertEquals("\"main\" prio=5 tid=1 Runnable", ordered[2])
        assertEquals("\"DefaultDispatcher-worker-12\" daemon prio=5 tid=30 Runnable", ordered[4])
        assertEquals("\"HeapTaskDaemon\" daemon prio=5 tid=9 WaitingPerformingGc", ordered[6])
        assertEquals(listOf("\"Signal Catcher\" daemon prio=10 tid=7 WaitingInMainSignalCatcherLoop", "\"OkHttp TaskRunner\" daemon prio=5 tid=20 TimedWaiting"), ordered.takeLast(2))
        assertEquals(10, ordered.size)
    }

    @Test fun logLinesLoseMediaServerNames() {
        val line = "11-10 22:01:05.123 I/ServerStreams: SERVER_SOURCE name=Living Room Jellyfin outcome=skipped reason=offline type=movie"
        val cleaned = ExitTrace.capped(sequenceOf(line)).single()
        assertTrue(cleaned.contains("SERVER_SOURCE name=[server] outcome=skipped"))
        assertTrue(!cleaned.contains("Jellyfin"))
        val thumb = ExitTrace.capped(sequenceOf("I/Thumbs: ServerThumbSet: Living room Emby 120 frames")).single()
        assertTrue(!thumb.contains("Living room"))
    }

    @Test fun threadNamesSurviveTheCleaner() {
        assertEquals("\"DefaultDispatcher-worker-12\" daemon prio=5 tid=30 Runnable",
            ExitTrace.cleanLine("\"DefaultDispatcher-worker-12\" daemon prio=5 tid=30 Runnable"))
        assertTrue(ExitTrace.cleanLine("  at x(token=abcdef0123456789abcdef0123456789)")!!.contains("[redacted]"))
    }
}
