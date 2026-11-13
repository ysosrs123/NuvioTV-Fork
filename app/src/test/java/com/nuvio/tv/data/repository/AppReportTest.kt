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

    @Test fun `a problem report from Settings adds the last screens and Arc's own log lines, cleaned`() {
        val log = listOf(
            "11-12 20:01:02.123 W/JankStats( 4242): JANK: 412ms | states: []",
            "11-12 20:01:03.456 D/Home( 4242): row loaded from https://addon.example.com/catalog?token=SECRETSECRET123456"
        )
        val lines = buildAppReport(
            AppReportChoices(AppReportKind.Bug, "home", includeLog = true), 42L, app, device, network = null, box = null,
            events = emptyList(), recent = listOf("20:00:59 screen home"), log = log
        ).logLines!!
        assertEquals(
            listOf(APP_REPORT_RECENT_HEADER, "20:00:59 screen home", ArcCrashFile.LOG_HEADER, log[0]),
            lines.take(4)
        )
        assertEquals(5, lines.size)
        assertFalse(lines[4].contains("SECRETSECRET"))
        assertFalse(lines[4].contains("addon.example.com"))
    }

    @Test fun `Arc's own log lines keep the newest within their budget and need the log switch`() {
        val log = List(APP_REPORT_LOG_MAX_CHARS / 200 + 50) { "line $it " + "x".repeat(200 - "line $it ".length) }
        val kept = appReportLogLines(emptyList(), emptyList(), log)
        assertEquals(ArcCrashFile.LOG_HEADER, kept.first())
        assertEquals(log.last(), kept.last())
        assertEquals(APP_REPORT_LOG_MAX_CHARS / 200, kept.size - 1)
        assertTrue(appReportLogLines(emptyList(), emptyList(), emptyList()).isEmpty())
        val off = buildAppReport(
            AppReportChoices(AppReportKind.Bug, includeLog = false), 42L, app, device, network = null, box = null,
            events = events(), recent = listOf("screen home"), log = log
        )
        assertNull(off.logLines)
    }

    @Test fun `slow frames are counted per minute and screen and outlive a long walk through Settings`() {
        ArcSlowFrames.clear()
        val start = 1_794_516_000_000L / 60_000L * 60_000L
        ArcSlowFrames.note(60, "home", start + 1_000)
        ArcSlowFrames.note(332, "home", start + 20_000)
        ArcSlowFrames.note(101, "home", start + 59_000)
        ArcSlowFrames.note(70, "settings", start + 59_500)
        ArcSlowFrames.note(80, null, start + 61_000)
        repeat(500) { ArcSlowFrames.note(55, "settings", start + 120_000) }
        val lines = ArcSlowFrames.lines()
        assertEquals(4, lines.size)
        assertTrue(lines[0].endsWith(" home: 3 slow frames, 2 over 100 ms, worst 332 ms"))
        assertTrue(lines[1].endsWith(" settings: 1 slow frames, 0 over 100 ms, worst 70 ms"))
        assertTrue(lines[2].endsWith(" unknown: 1 slow frames, 0 over 100 ms, worst 80 ms"))
        assertTrue(lines[3].endsWith(" settings: 500 slow frames, 0 over 100 ms, worst 55 ms"))
        val sent = appReportLogLines(emptyList(), emptyList(), emptyList(), lines)
        assertEquals(APP_REPORT_SLOW_HEADER, sent.first())
        assertEquals(5, sent.size)
        ArcSlowFrames.clear()
    }

    @Test fun `the slow frame list keeps only its newest minutes`() {
        ArcSlowFrames.clear()
        repeat(60) { ArcSlowFrames.note(60, "home", it * 60_000L) }
        assertEquals(40, ArcSlowFrames.lines().size)
        ArcSlowFrames.clear()
    }

    @Test fun `system lines about remote keys and hidden methods do not use up the log lines`() {
        val noise = List(APP_REPORT_LOG_LINES * 2) {
            "11-13 06:46:37.151 W/AmazonKeyEventLogging( 4195): remote_perf key event latency detected [KeyCode: KEYCODE_XXXX]"
        }
        val log = listOf("11-13 06:44:00.000 W/JankStats( 4195): JANK: 332ms | states: [Screen: home]") + noise +
            listOf("11-13 06:47:00.000 W/m.nuvio.tv.tes( 4195): Accessing hidden method Landroid/graphics/Canvas;->drawPatch")
        val kept = appReportLogLines(emptyList(), emptyList(), log)
        assertEquals(listOf(ArcCrashFile.LOG_HEADER, log.first()), kept)
        val many = List(APP_REPORT_LOG_LINES + 30) { "line $it" }
        assertEquals(APP_REPORT_LOG_LINES, appReportLogLines(emptyList(), emptyList(), many).size - 1)
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
