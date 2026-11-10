package com.nuvio.tv.data.repository

import com.nuvio.tv.core.report.EventRing
import com.nuvio.tv.core.report.RecorderEvent
import com.nuvio.tv.data.remote.dto.AppReportCrashDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueAppDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueDeviceDto
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReportTest {
    private val app = PlaybackIssueAppDto("com.nuvio.tv.test", "1.1.0", 1600L, false)
    private val device = PlaybackIssueDeviceDto("Ugoos", "Ugoos", "AM9 Pro", "am9", "14", 34, listOf("arm64-v8a"))

    private fun events() = EventRing().apply {
        add(1_000L, RecorderEvent.PLAY, "")
        add(2_000L, RecorderEvent.ERROR, "open https://eu1.tb-cdn.st/x?token=SECRETSECRET123456")
    }.snapshot(3_000L)

    private fun build(choices: AppReportChoices) =
        buildAppReport(choices, 42L, app, device, network = null, box = null, events = events())

    @Test fun `a problem report carries where and a cleaned note`() {
        val report = build(AppReportChoices(AppReportKind.Bug, "search", "Broken at http://192.168.1.2/x for me@example.com"))
        assertEquals(1, report.schemaVersion)
        assertEquals("bug", report.kind)
        assertEquals("search", report.where)
        assertFalse(report.note!!.contains("192.168.1.2"))
        assertFalse(report.note!!.contains("me@example.com"))
    }

    @Test fun `the note stops at a thousand characters and a blank note is left out`() {
        assertEquals(1000, build(AppReportChoices(AppReportKind.Idea, note = "a".repeat(1500))).note!!.length)
        assertNull(build(AppReportChoices(AppReportKind.Idea, note = "   ")).note)
    }

    @Test fun `an idea never says where`() {
        assertNull(build(AppReportChoices(AppReportKind.Idea, where = "search", note = "x")).where)
    }

    @Test fun `log lines go only with the log switch on, and are cleaned recorder lines`() {
        assertNull(build(AppReportChoices(AppReportKind.Bug, includeLog = false)).logLines)
        val lines = build(AppReportChoices(AppReportKind.Bug, includeLog = true)).logLines!!
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("play"))
        assertFalse(lines[1].contains("SECRETSECRET"))
        assertFalse(lines[1].contains("tb-cdn"))
    }

    private val crash = AppReportCrashDto("anr", 6, 100, 0, "user request after error", 9L, listOf("main thread"))

    @Test fun `a crash report carries what Android said and never the recorder log`() {
        val report = build(AppReportChoices(AppReportKind.Crash, where = "search", includeLog = true, crash = crash))
        assertEquals("crash", report.kind)
        assertEquals(crash, report.crash)
        assertNull(report.where)
        assertNull(report.logLines)
    }

    @Test fun `only a crash report can hold crash details`() {
        assertNull(build(AppReportChoices(AppReportKind.Bug, crash = crash)).crash)
    }

    @Test fun `extra facts travel only when there are any`() {
        val choices = AppReportChoices(AppReportKind.Bug)
        val with = buildAppReport(choices, 42L, app, device, null, null, emptyList(), mapOf("a" to "b"))
        assertEquals(mapOf("a" to "b"), with.extra)
        assertNull(build(choices).extra)
    }

    @Test fun `the upload holds nothing but the agreed fields`() {
        val json = Moshi.Builder().build().adapter(com.nuvio.tv.data.remote.dto.AppReportRequestDto::class.java)
            .toJson(build(AppReportChoices(AppReportKind.Bug, "addons", "Note", includeLog = false)))
        val names = Regex("\"(\\w+)\":").findAll(json).map { it.groupValues[1] }.toSet() -
            setOf("applicationId", "versionName", "versionCode", "debugBuild", "manufacturer", "brand", "model", "product",
                "androidRelease", "sdkInt", "supportedAbis")
        assertEquals(setOf("schemaVersion", "createdAtMs", "app", "device", "kind", "where", "note"), names)
    }
}
