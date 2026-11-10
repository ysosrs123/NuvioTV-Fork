package com.nuvio.tv.data.repository

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportOutboxTest {
    private val dir: File = Files.createTempDirectory("outbox").toFile()
    private var nowMs = 1_000_000_000L
    private var free = ReportOutboxFiles.MIN_FREE_BYTES
    private val files = ReportOutboxFiles(File(dir, "waiting"), freeBytes = { free }, now = { nowMs })
    private val json = """{"schemaVersion":1}""".toByteArray()

    private fun noise(size: Int) = ByteArray(size).also { java.util.Random(size.toLong()).nextBytes(it) }

    @Test fun `a saved report comes back unchanged and knows its route and kind`() {
        assertTrue(files.save(ReportRoute.App, "crash", json))
        val waiting = files.pending().single()
        assertEquals(ReportRoute.App, waiting.route)
        assertEquals("crash", waiting.kind)
        assertArrayEquals(json, files.read(waiting))
    }

    @Test fun `the file on disk is compressed`() {
        val large = "cleaned report text ".repeat(5_000).toByteArray()
        assertTrue(files.save(ReportRoute.Playback, "playback", large))
        assertTrue(files.pending().single().file.length() < large.size / 10)
    }

    @Test fun `a fourth report is not saved`() {
        repeat(3) { nowMs += 1; assertTrue(files.save(ReportRoute.App, "bug", json)) }
        assertFalse(files.save(ReportRoute.App, "bug", json))
        assertEquals(3, files.pending().size)
    }

    @Test fun `the three megabyte total holds`() {
        val large = noise(1_000_000)
        val second = noise(1_000_001)
        val third = noise(1_000_002)
        assertTrue(files.save(ReportRoute.App, "bug", large))
        nowMs += 1
        assertTrue(files.save(ReportRoute.App, "bug", second))
        nowMs += 1
        assertTrue(files.save(ReportRoute.App, "bug", third))
        assertTrue(files.pending().sumOf { it.file.length() } <= ReportOutboxFiles.MAX_TOTAL_BYTES)
    }

    @Test fun `a report over the limit packed is not saved`() {
        assertFalse(files.save(ReportRoute.App, "bug", noise(1_500_000)))
        assertTrue(files.pending().isEmpty())
    }

    @Test fun `nothing is saved below 300 MB free`() {
        free = ReportOutboxFiles.MIN_FREE_BYTES - 1
        assertFalse(files.save(ReportRoute.App, "bug", json))
        assertTrue(files.pending().isEmpty())
    }

    @Test fun `reports older than seven days are removed, and a half written file with them`() {
        assertTrue(files.save(ReportRoute.App, "bug", json))
        File(dir, "waiting/12-abcd.app.bug.gz.part").writeText("half")
        nowMs += ReportOutboxFiles.MAX_AGE_MS + 1
        files.prune()
        assertTrue(File(dir, "waiting").listFiles().orEmpty().isEmpty())
    }

    @Test fun `a report exactly seven days old is still kept`() {
        assertTrue(files.save(ReportRoute.App, "bug", json))
        nowMs += ReportOutboxFiles.MAX_AGE_MS
        files.prune()
        assertEquals(1, files.pending().size)
    }

    @Test fun `a refusal is not a reason to keep a report, a 5xx or lost connection is`() {
        for (code in listOf(400, 401, 413, 429)) {
            assertEquals(ReportUpload.Refused(code), reportUploadResult(code, false, null, null))
        }
        for (code in listOf(408, 425, 503)) assertTrue(reportUploadResult(code, false, null, null) is ReportUpload.Unreachable)
        assertEquals(ReportUpload.Accepted("ARC-ABCD", "u"), reportUploadResult(200, true, " ARC-ABCD ", "u"))
        assertEquals(ReportUpload.Refused(200), reportUploadResult(200, true, null, null))
    }

    private fun send(answers: MutableList<ReportUpload>, accepted: MutableList<String>) = runBlocking {
        sendWaitingReports(files, upload = { _, _ -> answers.removeAt(0) }, onAccepted = { report, ok -> accepted += "${report.kind}:${ok.id}" })
    }

    @Test fun `sent and refused reports are deleted and the sent one is recorded under its kind`() {
        assertTrue(files.save(ReportRoute.App, "crash", json))
        nowMs += 1
        assertTrue(files.save(ReportRoute.App, "bug", json))
        val accepted = mutableListOf<String>()
        val stillOut = send(mutableListOf(ReportUpload.Accepted("ARC-AAAA", null), ReportUpload.Refused(400)), accepted)
        assertFalse(stillOut)
        assertEquals(listOf("crash:ARC-AAAA"), accepted)
        assertTrue(files.pending().isEmpty())
    }

    @Test fun `a waiting report stays when the daily limit answers`() {
        assertTrue(files.save(ReportRoute.App, "bug", json))
        assertTrue(send(mutableListOf(ReportUpload.Refused(429)), mutableListOf()))
        assertEquals(1, files.pending().size)
    }

    @Test fun `when the relay is out of reach the report stays and later ones wait`() {
        assertTrue(files.save(ReportRoute.App, "crash", json))
        nowMs += 1
        assertTrue(files.save(ReportRoute.App, "bug", json))
        val answers = mutableListOf<ReportUpload>(ReportUpload.Unreachable("timeout"), ReportUpload.Accepted("X", null))
        assertTrue(send(answers, mutableListOf()))
        assertEquals(2, files.pending().size)
        assertEquals(1, answers.size)
    }

    @Test fun `a damaged file is dropped without an upload`() {
        assertTrue(files.save(ReportRoute.App, "bug", json))
        files.pending().single().file.writeText("not gzip")
        val answers = mutableListOf<ReportUpload>()
        assertFalse(send(answers, mutableListOf()))
        assertTrue(files.pending().isEmpty())
    }

    @Test fun `waiting grows with each failure, stops at the last step and starts over after a success`() {
        val schedule = ReportRetrySchedule(listOf(1L, 5L, 15L))
        assertEquals(listOf(1L, 5L, 15L, 15L), List(4) { schedule.failed() })
        schedule.succeeded()
        assertEquals(1L, schedule.failed())
    }

    @Test fun `a network that flaps does not cause a try every time`() {
        val schedule = ReportRetrySchedule(networkGapMs = 30_000L)
        assertTrue(schedule.mayTryOnNetworkBack(0L))
        schedule.tried(1_000L)
        assertFalse(schedule.mayTryOnNetworkBack(30_999L))
        assertTrue(schedule.mayTryOnNetworkBack(31_000L))
    }
}
