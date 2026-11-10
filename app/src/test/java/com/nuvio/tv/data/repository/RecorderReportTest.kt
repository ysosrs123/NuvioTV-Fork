package com.nuvio.tv.data.repository

import com.nuvio.tv.core.report.EventRing
import com.nuvio.tv.core.report.RECORDER_EVENT_CAPACITY
import com.nuvio.tv.core.report.RECORDER_UNKNOWN
import com.nuvio.tv.core.report.RecorderEvent
import com.nuvio.tv.core.report.RecorderEventLine
import com.nuvio.tv.core.report.RecorderSnapshot
import com.nuvio.tv.core.report.SampleRing
import com.nuvio.tv.data.remote.dto.PlaybackIssueAppDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueContentDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueDeviceDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueDiagnosticsDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueErrorDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueLoadingDto
import com.nuvio.tv.data.remote.dto.PlaybackIssuePlayerDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueRecorderDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueReportRequestDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueStreamDto
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderReportTest {
    @Test fun `wifi standards map to the wifi generation`() {
        assertEquals(4, wifiGeneration(4))
        assertEquals(5, wifiGeneration(5))
        assertEquals(6, wifiGeneration(6))
        assertEquals(7, wifiGeneration(8))
        assertNull(wifiGeneration(0))
        assertNull(wifiGeneration(1))
        assertNull(wifiGeneration(7))
    }

    @Test fun `the band comes from the frequency`() {
        assertEquals("2.4", wifiBandGhz(2437))
        assertEquals("5", wifiBandGhz(5180))
        assertEquals("5", wifiBandGhz(5885))
        assertEquals("6", wifiBandGhz(5955))
        assertEquals("6", wifiBandGhz(7115))
        assertNull(wifiBandGhz(-1))
        assertNull(wifiBandGhz(60480))
    }

    @Test fun `a vpn over wifi still counts as wifi and a bare vpn as vpn`() {
        assertEquals("wifi", connectionKind(vpn = true, wifi = true, ethernet = false))
        assertEquals("ethernet", connectionKind(vpn = false, wifi = false, ethernet = true))
        assertEquals("vpn", connectionKind(vpn = true, wifi = false, ethernet = false))
        assertEquals("other", connectionKind(vpn = false, wifi = false, ethernet = false))
    }

    @Test fun `ethernet speed is a number or null, never a crash`() {
        assertEquals(1000, parseEthernetSpeedMbps("1000\n"))
        assertEquals(100, parseEthernetSpeedMbps(" 100 "))
        assertNull(parseEthernetSpeedMbps(null))
        assertNull(parseEthernetSpeedMbps(""))
        assertNull(parseEthernetSpeedMbps("-1\n"))
        assertNull(parseEthernetSpeedMbps("unknown"))
        assertNull(parseEthernetSpeedMbps("99999999999999"))
    }

    @Test fun `event lines lose links, keys and addresses`() {
        val line = ReportText.eventLine(
            "-1000ms error load_error https://eu1.tb-cdn.st/dl/abc?token=SECRET123 box 192.168.1.60 apikey=Zx81Qw7",
            300
        )!!
        assertFalse(line, line.contains("SECRET123") || line.contains("192.168.1.60") || line.contains("Zx81Qw7"))
        assertTrue(line, line.startsWith("-1000ms error load_error"))
    }

    @Test fun `media server names do not survive`() {
        val source = ReportText.eventLine("I NuvioServer: SERVER_SOURCE name=Pauls Basement Emby ms=120 streams=3 outcome=ok", 300)!!
        assertFalse(source, source.contains("Basement") || source.contains("Pauls"))
        assertTrue(source, source.contains("ms=120") && source.contains("outcome=ok"))
        val skipped = ReportText.eventLine("SERVER_SOURCE name=Dads Jellyfin outcome=skipped reason=offline type=jellyfin", 300)!!
        assertFalse(skipped, skipped.contains("Dads"))
        val thumbs = ReportText.eventLine("I ServerThumbSet: Grans Plex: manifest in 80 ms", 300)!!
        assertFalse(thumbs, thumbs.contains("Grans"))
    }

    @Test fun `the recorder drops a server name before keeping the line`() {
        val kept = ReportText.withoutServerNames("SERVER_SOURCE name=Pauls Emby ms=1")
        assertFalse(kept, kept.contains("Pauls"))
        assertEquals("seek fromMs=1 toMs=2", ReportText.withoutServerNames("seek fromMs=1 toMs=2"))
    }

    @Test fun `unknown sample values become null in the report and known ones stay`() {
        val dto = snapshotWithSamples(count = 2).toDto()
        val samples = dto.samples!!
        assertEquals(2, dto.sampleCount)
        assertEquals(listOf<Int?>(null, null), samples.droppedFrames)
        assertEquals(listOf<Int?>(1234, 1234), samples.readyAheadMs)
        assertEquals(listOf<Int?>(1, 1), samples.tunnelled)
        assertNull(samples.thermalStatus[0])
    }

    @Test fun `a recorder that is off sends no timeline`() {
        val dto = RecorderSnapshot(enabled = false, samples = null, events = emptyList()).toDto()
        assertFalse(dto.enabled)
        assertEquals(0, dto.sampleCount)
        assertEquals(0L, dto.coveredMs)
        assertNull(dto.samples)
        assertTrue(dto.eventLines.isEmpty())
    }

    @Test fun `event lines are cleaned and capped when the report is built`() {
        val dto = RecorderSnapshot(
            enabled = true,
            samples = null,
            events = listOf(RecorderEventLine(5L, "error", "https://example.com/a?token=SECRET123 " + "y".repeat(1_000)))
        ).toDto()
        val line = dto.eventLines.single()
        assertFalse(line, line.contains("SECRET123"))
        assertTrue(line.length <= RECORDER_LINE_MAX_CHARS)
    }

    @Test fun `the whole recorder section stays small even when every ring is full`() {
        val ring = EventRing()
        for (i in 0 until RECORDER_EVENT_CAPACITY * 2) ring.add(i.toLong(), RecorderEvent.ERROR, "z".repeat(5_000))
        val dto = RecorderSnapshot(true, fullSampleRing().snapshot(1_000_000L), ring.snapshot(2_000L)).toDto()
        val json = Moshi.Builder().build().adapter(PlaybackIssueRecorderDto::class.java).toJson(dto)
        assertEquals(300, dto.sampleCount)
        assertTrue(dto.eventLines.sumOf { it.length } <= RECORDER_LINES_MAX_CHARS)
        assertTrue("json was ${json.length} chars", json.length < 250_000)
        assertTrue(json.contains("\"droppedFrames\":[null"))
    }

    @Test fun `with the log switched off samples stay and event lines go`() {
        val report = baseReport().copy(recorder = recorderSection("seek fromMs=1 toMs=2"))
        val sent = report.applyViewerChoices(PlaybackIssueViewerChoices(includeLog = false))
        assertTrue(sent.recorder!!.eventLines.isEmpty())
        assertEquals(2, sent.recorder!!.sampleCount)
        assertEquals(2, sent.recorder!!.samples!!.ageMs.size)
    }

    @Test fun `with the log on event lines stay`() {
        val report = baseReport().copy(recorder = recorderSection("seek fromMs=1 toMs=2"))
        val sent = report.applyViewerChoices(PlaybackIssueViewerChoices(includeLog = true))
        assertEquals(listOf("seek fromMs=1 toMs=2"), sent.recorder!!.eventLines)
    }

    @Test fun `a report without a recorder section is unchanged by the log switch`() {
        assertNull(baseReport().applyViewerChoices(PlaybackIssueViewerChoices(includeLog = false)).recorder)
    }

    private fun recorderSection(line: String): PlaybackIssueRecorderDto =
        snapshotWithSamples(2).toDto().copy(eventLines = listOf(line))

    private fun snapshotWithSamples(count: Int): RecorderSnapshot {
        val ring = SampleRing(10)
        repeat(count) { addSample(ring, it * 1_000L, droppedFrames = RECORDER_UNKNOWN) }
        return RecorderSnapshot(true, ring.snapshot(count * 1_000L), emptyList())
    }

    private fun fullSampleRing(): SampleRing {
        val ring = SampleRing()
        repeat(400) { addSample(ring, it * 1_000L, droppedFrames = RECORDER_UNKNOWN, readyAheadMs = 1_999_999_999) }
        return ring
    }

    private fun addSample(ring: SampleRing, timeMs: Long, droppedFrames: Int, readyAheadMs: Int = 1234) = ring.add(
        timeMs = timeMs,
        readyAheadMs = readyAheadMs,
        droppedFrames = droppedFrames,
        audioUnderruns = 0,
        ownCpuTenths = 2000,
        rssKb = 1_500_000,
        javaHeapKb = 200_000,
        nativeHeapKb = 800_000,
        memAvailableKb = 900_000,
        bufferCapKb = 500_000,
        thermalStatus = RECORDER_UNKNOWN,
        loaderActive = 1,
        tunnelled = 1,
        playing = 1
    )

    private fun baseReport(title: String? = "A Film"): PlaybackIssueReportRequestDto = PlaybackIssueReportRequestDto(
        schemaVersion = 1,
        createdAtMs = 1L,
        app = PlaybackIssueAppDto("id", "1", 1L, false),
        device = PlaybackIssueDeviceDto("m", "b", "x", "p", "14", 34, emptyList()),
        content = PlaybackIssueContentDto(title, null, null, null, null, null, null, null, null),
        stream = PlaybackIssueStreamDto(null, null, null, null, null, null, null, null, null, null, null, emptyList(), emptyList()),
        player = PlaybackIssuePlayerDto("EXO", null, null, null, null, null, false),
        loading = PlaybackIssueLoadingDto(
            phase = "p", message = null, progress = null, elapsedMs = 0L, phaseElapsedMs = 0L, reportReason = "r",
            loadingOverlayVisible = false, loadingStatusVisible = false, hasRenderedFirstFrame = true,
            exoPlayerCreated = true, exoPlaybackState = null, exoPlaybackStateName = null, exoIsLoading = null,
            exoPlayWhenReady = null, mpvAttached = false, startupRetryCount = 0, errorRetryCount = 0,
            timeoutRecoveryAttempts = 0, isLoadingAddonSubtitles = false, addonSubtitlesCount = 0,
            isLoadingSourceStreams = false, isLoadingEpisodeStreams = false, torrentDownloadSpeed = 0L,
            torrentPeers = 0, torrentSeeds = 0, rawEventLines = emptyList(), events = emptyList()
        ),
        error = PlaybackIssueErrorDto(null, null, null, null, null, null, null),
        diagnostics = PlaybackIssueDiagnosticsDto(
            timestampMs = 1L, host = "h", hdrCapsKnown = false, displayDv = false, displayHdr10 = false,
            displayHdr10Plus = false, codecDv7Supported = false, dv81DecoderName = null, bridgeReady = false,
            bridgeVersion = null, bridgeReason = null, dv7ModeRequested = "a", dv7ModeEffective = "b",
            dv7AutoDecision = null, bufferEngineEnabled = false, parallelNetworkEnabled = false, firstFrameMs = 0L,
            dv7DoviCalls = 0, dv7DoviSuccess = 0, dv7DoviSignalRewrites = 0, dvSourceProfile = null,
            videoResolution = null, videoCodec = null, videoHdrType = null, rebufferCount = 0, rebufferTotalMs = 0L,
            result = "r"
        )
    )
}
