package com.nuvio.tv.ui.screens.player.autosync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSyncSubtitleTextRetimerTest {
    @Test
    fun srtShiftsTimestampsAndKeepsText() {
        val body = """
            1
            00:00:01,000 --> 00:00:02,500
            <i>Hello</i>

            2
            00:00:04,000 --> 00:00:05,000
            World
        """.trimIndent()

        val retimed = AutoSyncSubtitleTextRetimer.retime(body, shifted(body, offsetMs = 1_500L))

        assertEquals(
            """
                1
                00:00:02,500 --> 00:00:04,000
                <i>Hello</i>

                2
                00:00:05,500 --> 00:00:06,500
                World
            """.trimIndent(),
            retimed,
        )
    }

    @Test
    fun webVttKeepsCueSettingsAndAddsHoursOnlyWhenNeeded() {
        val body = """
            WEBVTT

            59:58.000 --> 59:59.500 align:start line:10%
            Late line
        """.trimIndent()

        val retimed = AutoSyncSubtitleTextRetimer.retime(body, shifted(body, offsetMs = 3_000L))

        assertEquals(
            """
                WEBVTT

                01:00:01.000 --> 01:00:02.500 align:start line:10%
                Late line
            """.trimIndent(),
            retimed,
        )
    }

    @Test
    fun assRewritesOnlyStartAndEndFieldsFromFormat() {
        val body = """
            [Script Info]
            Title: Test

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,20

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,{\an8}Top, with comma
            Comment: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,note
        """.trimIndent()
        val timeline = timeline(
            cues = listOf(AutoSyncRetimedCue(1_000L, 2_500L, 1_250L, 2_750L)),
            interceptMs = 250.0,
        )

        val retimed = AutoSyncSubtitleTextRetimer.retime(body, timeline)

        assertEquals(
            body.replace(
                "Dialogue: 0,0:00:01.00,0:00:02.50,",
                "Dialogue: 0,0:00:01.25,0:00:02.75,",
            ),
            retimed,
        )
    }

    @Test
    fun assWithEmbeddedFontsBeforeEventsIsStillRetimedAsAss() {
        val fonts = (0 until 2_000).joinToString("\n") { "M9TAT\"\"(\"\"0@*\"<@*\"9$it" }
        val body = "[Script Info]\nTitle: Test\n\n[Fonts]\nfontname: a.ttf\n$fonts\n\n" +
            "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,Line"
        val timeline = timeline(
            cues = listOf(AutoSyncRetimedCue(1_000L, 2_500L, 1_250L, 2_750L)),
            interceptMs = 250.0,
        )

        val retimed = AutoSyncSubtitleTextRetimer.retime(body, timeline)

        assertEquals(
            body.replace("0:00:01.00,0:00:02.50", "0:00:01.25,0:00:02.75"),
            retimed,
        )
        assertEquals(1, AutoSyncSubtitleCueParser.parse(body, sourceUrl = "https://host/sub?id=1").size)
    }

    @Test
    fun assSignNearADialogueLineKeepsItsOffsetFromIt() {
        val body = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:01:40.00,0:01:42.00,Default,,0,0,0,,Spoken line
            Dialogue: 0,0:01:40.20,0:01:45.20,Signs,,0,0,0,,{\pos(320,60)}SHOP
        """.trimIndent()
        val timeline = timeline(
            cues = listOf(AutoSyncRetimedCue(100_000L, 102_000L, 101_500L, 103_500L)),
            interceptMs = 1_500.0,
        )

        val retimed = AutoSyncSubtitleTextRetimer.retime(body, timeline)

        assertEquals(
            body
                .replace("0:01:40.00,0:01:42.00", "0:01:41.50,0:01:43.50")
                .replace("0:01:40.20,0:01:45.20", "0:01:41.70,0:01:46.70"),
            retimed,
        )
    }

    @Test
    fun assOneFrameEventsKeepTheirLengthBetweenUnevenlyMovedLines() {
        val body = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:01:38.00,0:01:40.03,Default,,0,0,0,,First line
            Dialogue: 0,0:01:39.99,0:01:43.00,Default,,0,0,0,,Second line
            Dialogue: 1,0:01:40.00,0:01:40.04,Signs,,0,0,0,,{\pos(100,100)}A
            Dialogue: 1,0:01:40.04,0:01:40.08,Signs,,0,0,0,,{\pos(102,100)}A
        """.trimIndent()
        val timeline = timeline(
            cues = listOf(
                AutoSyncRetimedCue(98_000L, 100_030L, 99_500L, 101_000L),
                AutoSyncRetimedCue(99_990L, 103_000L, 101_490L, 104_500L),
            ),
            interceptMs = 1_500.0,
        )

        val retimed = AutoSyncSubtitleTextRetimer.retime(body, timeline)

        assertEquals(
            body
                .replace("0:01:38.00,0:01:40.03", "0:01:39.50,0:01:41.00")
                .replace("0:01:39.99,0:01:43.00", "0:01:41.49,0:01:44.50")
                .replace("0:01:40.00,0:01:40.04", "0:01:41.50,0:01:41.54")
                .replace("0:01:40.04,0:01:40.08", "0:01:41.54,0:01:41.58"),
            retimed,
        )
    }

    @Test
    fun timesWithoutANearbyCueUseTheWholeFilmTransform() {
        val body = """
            1
            00:10:00,000 --> 00:10:01,000
            Only cue the matcher dropped
        """.trimIndent()
        val timeline = timeline(
            cues = listOf(AutoSyncRetimedCue(1_000L, 2_000L, 1_000L, 2_000L)),
            scale = 1.001,
            interceptMs = -400.0,
        )

        val retimed = AutoSyncSubtitleTextRetimer.retime(body, timeline)

        assertEquals(
            """
                1
                00:10:00,200 --> 00:10:01,201
                Only cue the matcher dropped
            """.trimIndent(),
            retimed,
        )
    }

    @Test
    fun retimedCueRunningIntoTheNextIsCutAtItsStart() {
        val body = """
            1
            00:00:01,000 --> 00:00:02,000
            First

            2
            00:00:02,000 --> 00:00:03,000
            Second
        """.trimIndent()
        val timeline = timeline(
            cues = listOf(
                AutoSyncRetimedCue(1_000L, 2_000L, 1_000L, 2_600L),
                AutoSyncRetimedCue(2_000L, 3_000L, 2_400L, 3_400L),
            ),
        )

        val retimed = AutoSyncSubtitleTextRetimer.retime(body, timeline)!!

        assertEquals(true, retimed.contains("00:00:01,000 --> 00:00:02,400"))
        assertEquals(true, retimed.contains("00:00:02,400 --> 00:00:03,400"))
    }

    @Test
    fun formatsWithoutRewritableTimingReturnNull() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="1s" end="2s">x</p></body></tt>"""
        val delayOnly = timeline(cues = emptyList(), interceptMs = 1_000.0)

        assertNull(AutoSyncSubtitleTextRetimer.retime(ttml, delayOnly))
        assertNull(AutoSyncSubtitleTextRetimer.retime("no timing here", delayOnly))
    }

    private fun shifted(srtOrVtt: String, offsetMs: Long): AutoSyncTimelineRetimeResult =
        timeline(
            cues = AutoSyncSubtitleCueParser.parse(srtOrVtt).map { cue ->
                AutoSyncRetimedCue(
                    originalStartTimeMs = cue.startTimeMs,
                    originalEndTimeMs = cue.endTimeMs,
                    startTimeMs = cue.startTimeMs + offsetMs,
                    endTimeMs = cue.endTimeMs + offsetMs,
                )
            },
            interceptMs = offsetMs.toDouble(),
        )

    private fun timeline(
        cues: List<AutoSyncRetimedCue>,
        scale: Double = 1.0,
        interceptMs: Double = 0.0,
    ) = AutoSyncTimelineRetimeResult(
        cues = cues,
        groups = emptyList(),
        targetCoverage = 1.0,
        referenceCoverage = 1.0,
        skippedTargetCues = 0,
        skippedReferenceCues = 0,
        longestTargetSkipRun = 0,
        averageGroupCost = 0.0,
        oneToOneGroups = cues.size,
        oneToTwoGroups = 0,
        twoToOneGroups = 0,
        oneToThreeGroups = 0,
        threeToOneGroups = 0,
        twoToTwoGroups = 0,
        confident = true,
        alignmentScale = scale,
        alignmentInterceptMs = interceptMs,
    )
}
