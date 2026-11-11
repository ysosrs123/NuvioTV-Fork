package com.nuvio.tv.data.repository

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcExitReportsTest {
    private val foreground = RunningAppProcessInfo.IMPORTANCE_FOREGROUND

    private fun record(reason: Int, time: Long = 5_000L, importance: Int = foreground) =
        ExitRecord(reason, importance, 0, "desc", time)

    @Test fun `the four bad exits count while Arc is on screen`() {
        assertEquals(UnexpectedExitKind.Crash, ExitRules.unexpected(record(ApplicationExitInfo.REASON_CRASH))?.kind)
        assertEquals(UnexpectedExitKind.NativeCrash, ExitRules.unexpected(record(ApplicationExitInfo.REASON_CRASH_NATIVE))?.kind)
        assertEquals(UnexpectedExitKind.NotResponding, ExitRules.unexpected(record(ApplicationExitInfo.REASON_ANR))?.kind)
        assertEquals(UnexpectedExitKind.LowMemory, ExitRules.unexpected(record(ApplicationExitInfo.REASON_LOW_MEMORY))?.kind)
    }

    @Test fun `normal ends never count`() {
        val normal = listOf(
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_UNKNOWN
        )
        normal.forEach { assertNull(ExitRules.unexpected(record(it))) }
    }

    @Test fun `a bad exit in the background never counts`() {
        listOf(
            RunningAppProcessInfo.IMPORTANCE_VISIBLE,
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
            RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING,
            RunningAppProcessInfo.IMPORTANCE_CACHED
        ).forEach { assertNull(ExitRules.unexpected(record(ApplicationExitInfo.REASON_LOW_MEMORY, importance = it))) }
    }

    @Test fun `only the very last exit is offered`() {
        val crash = record(ApplicationExitInfo.REASON_CRASH, time = 1_000L)
        val swipe = record(ApplicationExitInfo.REASON_USER_REQUESTED, time = 2_000L)
        assertNull(ExitRules.forPrompt(listOf(swipe, crash), handledMs = 0L))
        assertNotNull(ExitRules.forPrompt(listOf(crash), handledMs = 0L))
    }

    @Test fun `each exit is offered once`() {
        val crash = record(ApplicationExitInfo.REASON_CRASH, time = 1_000L)
        assertNull(ExitRules.forPrompt(listOf(crash), handledMs = 1_000L))
        assertNotNull(ExitRules.forPrompt(listOf(crash), handledMs = 999L))
    }

    @Test fun `Settings finds the newest exit not yet reported and only for a week`() {
        val older = record(ApplicationExitInfo.REASON_CRASH, time = 1_000L)
        val newer = record(ApplicationExitInfo.REASON_ANR, time = 2_000L)
        val swipe = record(ApplicationExitInfo.REASON_USER_REQUESTED, time = 3_000L)
        assertEquals(2_000L, ExitRules.forSettings(listOf(swipe, older, newer), 0L, 4_000L)?.timestampMs)
        assertNull(ExitRules.forSettings(listOf(newer), 2_000L, 4_000L))
        assertNull(ExitRules.forSettings(listOf(newer), 0L, 2_000L + ExitRules.RECENT_MS + 1))
    }

    @Test fun `trace lines are cleaned one at a time`() {
        val trace = """
            Cmd line: com.nuvio.tv.test

            "OkHttp https://eu1.tb-cdn.st/dl/x?token=SECRETSECRET123456" prio=5
              at com.nuvio.tv.ui.Foo.bar(Foo.kt:12)
            server 192.168.1.60 for me@example.com
        """.trimIndent()
        val lines = ExitTrace.textLines(ByteArrayInputStream(trace.toByteArray()))
        assertEquals(4, lines.size)
        val joined = lines.joinToString("\n")
        assertFalse(joined.contains("SECRETSECRET"))
        assertFalse(joined.contains("tb-cdn"))
        assertFalse(joined.contains("192.168.1.60"))
        assertFalse(joined.contains("me@example.com"))
        assertTrue(joined.contains("com.nuvio.tv.ui.Foo.bar(Foo.kt:12)"))
    }

    @Test fun `a long trace stops at 200 KB, keeping the top`() {
        val line = "  at some.pkg.Klass.method(Klass.java:1)"
        val trace = (0 until 20_000).joinToString("\n") { "$line $it" }
        val lines = ExitTrace.textLines(ByteArrayInputStream(trace.toByteArray()))
        val bytes = lines.sumOf { it.toByteArray().size + 1 }
        assertTrue(bytes <= ExitTrace.MAX_BYTES)
        assertTrue(bytes > ExitTrace.MAX_BYTES - 200)
        assertTrue(lines.first().endsWith(" 0"))
    }

    private class Proto {
        val out = ByteArrayOutputStream()
        private fun raw(value: Long) {
            var left = value
            while (left and 0x7fL.inv() != 0L) {
                out.write(((left and 0x7f) or 0x80).toInt())
                left = left ushr 7
            }
            out.write(left.toInt())
        }

        fun number(field: Int, value: Long) = apply { raw((field shl 3).toLong()); raw(value) }
        fun bytes(field: Int, value: ByteArray) = apply { raw(((field shl 3) or 2).toLong()); raw(value.size.toLong()); out.write(value) }
        fun text(field: Int, value: String) = bytes(field, value.toByteArray())
        fun message(field: Int, value: Proto) = bytes(field, value.out.toByteArray())
    }

    private fun frame(pc: Long, function: String, file: String) =
        Proto().number(1, pc).text(4, function).number(5, 12).text(6, file).text(8, "buildid")

    private fun thread(name: String, vararg frames: Proto) = Proto().number(1, 77).text(2, name).apply {
        message(5, Proto().text(1, "x0").bytes(4, "https://secret.example/memory?token=SECRETSECRET123456".toByteArray()))
        frames.forEach { message(4, it) }
    }

    private fun threadEntry(tid: Long, value: Proto) = Proto().number(1, tid).message(2, value)

    private fun tombstone() = Proto()
        .text(2, "Ugoos/am9/am9:14/fingerprint")
        .number(5, 500).number(6, 77)
        .text(9, "com.nuvio.tv.test")
        .message(10, Proto().number(1, 11).text(2, "SIGSEGV").number(3, 1).text(4, "SEGV_MAPERR"))
        .text(14, "failed on https://eu1.tb-cdn.st/x?token=SECRETSECRET123456")
        .message(16, threadEntry(12, thread("OtherThread", frame(1, "other", "/system/lib64/libother.so"))))
        .message(16, threadEntry(77, thread("Decoder", frame(0x1234, "decode", "/data/app/~~abc==/com.nuvio.tv.test-x==/lib/arm64/libmpv.so"))))
        .message(17, Proto().text(1, "/data/app/mapping"))

    @Test fun `a native crash record gives the signal, the abort text and the crashing thread only`() {
        val lines = ExitTrace.nativeLines(ByteArrayInputStream(tombstone().out.toByteArray()))
        assertEquals("Signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)", lines[0])
        assertTrue(lines[1].startsWith("Abort: failed on"))
        assertEquals("Thread: Decoder", lines[2])
        assertEquals("#00 pc 0000000000001234  libmpv.so (decode+12)", lines[3])
        assertEquals(4, lines.size)
        val joined = lines.joinToString("\n")
        assertFalse(joined.contains("SECRETSECRET"))
        assertFalse(joined.contains("OtherThread"))
        assertFalse(joined.contains("secret.example"))
    }

    @Test fun `a cut off or foreign record gives what could be read and no crash`() {
        val whole = tombstone().out.toByteArray()
        val partial = ExitTrace.nativeLines(ByteArrayInputStream(whole.copyOf(whole.size / 3)))
        assertTrue(partial.size <= 2)
        val foreign = ByteArray(4_096) { (it * 31).toByte() }
        ExitTrace.nativeLines(ByteArrayInputStream(foreign))
    }

    @Test fun `a saved crash stands in for an exit record on older Android`() {
        val exit = ExitRules.savedCrash(9_000L, handledMs = 5_000L)!!
        assertEquals(UnexpectedExitKind.Crash, exit.kind)
        assertEquals(9_000L, exit.timestampMs)
        assertTrue(exit.hasTrace)
        assertNull(ExitRules.savedCrash(9_000L, handledMs = 9_000L))
        assertNull(ExitRules.savedCrash(null, handledMs = 0L))
    }
}
