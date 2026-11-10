package com.nuvio.tv.data.repository

import com.nuvio.tv.core.report.EventRing
import com.nuvio.tv.core.report.FRAME_BUCKET_COUNT
import com.nuvio.tv.core.report.RECORDER_UNKNOWN
import com.nuvio.tv.core.report.RecorderEvent
import com.nuvio.tv.core.report.RecorderSnapshot
import com.nuvio.tv.core.report.RecordingEnd
import com.nuvio.tv.core.report.RecordingMarks
import com.nuvio.tv.core.report.RecordingInfo
import com.nuvio.tv.core.report.SampleRing
import com.nuvio.tv.data.local.PlayerSettings
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
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderRecordingReportTest {
    private val moshi = Moshi.Builder().build()
    private val requestAdapter = moshi.adapter(PlaybackIssueReportRequestDto::class.java)

    private fun serialize(request: PlaybackIssueReportRequestDto) = requestAdapter.toJson(request).toByteArray(Charsets.UTF_8)

    private fun recording(seconds: Int, lineChars: Int, lines: Int, marks: Int): RecorderSnapshot {
        val random = Random(42)
        val ring = SampleRing(seconds, withFrameBuckets = true)
        repeat(seconds) { second ->
            ring.add(
                timeMs = second * 1_000L, readyAheadMs = random.nextInt(60_000), droppedFrames = random.nextInt(500),
                audioUnderruns = random.nextInt(9), ownCpuTenths = random.nextInt(2_000), rssKb = 1_000_000 + random.nextInt(500_000),
                javaHeapKb = random.nextInt(300_000), nativeHeapKb = random.nextInt(900_000), memAvailableKb = random.nextInt(1_200_000),
                bufferCapKb = random.nextInt(500_000), thermalStatus = random.nextInt(3), loaderActive = random.nextInt(2),
                tunnelled = 0, playing = 1, skippedFrames = random.nextInt(50), frameLeadUs = random.nextInt(20_000) - 10_000,
                audioJitterAvgMs = random.nextInt(30), audioJitterMaxMs = random.nextInt(400), fillerCount = random.nextInt(9),
                audioPositionMs = random.nextInt(7_000_000), speedPercent = 100, connections = random.nextInt(9),
                watchedMs = second * 1_000,
                frameBuckets = IntArray(FRAME_BUCKET_COUNT) { random.nextInt(40) }
            )
        }
        val events = EventRing(lines.coerceAtLeast(1))
        repeat(lines) { line ->
            val text = buildString { repeat(lineChars) { append(('a' + random.nextInt(26))) } }
            events.add(line * 500L, RecorderEvent.DROPPED, text, wallMs = 1_731_000_000_000L + line, positionMs = line * 400L)
        }
        val marked = RecordingMarks()
        repeat(marks) { marked.add(1_731_000_000_000L + it * 60_000L, it * 60_000L, it * 60_000L) }
        return RecorderSnapshot(
            enabled = true,
            samples = ring.snapshot(seconds * 1_000L),
            events = events.snapshot(lines * 500L),
            marks = marked.snapshot(),
            recording = RecordingInfo(1_731_000_000_000L, seconds * 1_000L, RecordingEnd.STOPPED)
        )
    }

    private fun gzipOf(request: PlaybackIssueReportRequestDto) = gzippedSize(serialize(request))

    @Test fun `a full thirty minute recording with marks goes through the report mapping`() {
        val dto = recording(seconds = 1_800, lineChars = 120, lines = 600, marks = 5).toDto()
        assertEquals(1_800, dto.sampleCount)
        assertEquals(5, dto.marks.size)
        assertEquals(60_000L, dto.marks[1].positionMs)
        assertEquals(FRAME_BUCKET_COUNT, dto.samples!!.frameBuckets.first().size)
        assertEquals(1_800, dto.samples!!.audioJitterMaxMs.size)
        assertEquals("stopped", dto.recording!!.endedBy)
        assertEquals(1_800_000L, dto.recording!!.lengthMs)
        assertTrue(dto.eventLines.first().contains("wall="))
        assertTrue(dto.eventLines.first().contains("pos="))
    }

    @Test fun `unknown extras are null in the report`() {
        val ring = SampleRing(2)
        ring.add(
            timeMs = 0L, readyAheadMs = 1, droppedFrames = 0, audioUnderruns = 0, ownCpuTenths = 0, rssKb = 0, javaHeapKb = 0,
            nativeHeapKb = 0, memAvailableKb = 0, bufferCapKb = 0, thermalStatus = 0, loaderActive = 0, tunnelled = 0, playing = 1
        )
        val dto = RecorderSnapshot(true, ring.snapshot(0L), emptyList()).toDto()
        assertEquals(listOf<Int?>(null), dto.samples!!.skippedFrames)
        assertEquals(listOf<Int?>(null), dto.samples!!.audioJitterAvgMs)
        assertEquals(listOf<Int?>(null), dto.samples!!.connections)
        assertTrue(dto.samples!!.frameBuckets.isEmpty())
        assertNull(dto.recording)
        assertTrue(dto.marks.isEmpty())
    }

    @Test fun `a mark without a known position reports no position`() {
        val marks = RecordingMarks()
        marks.add(10L, -1L, 5L)
        val dto = RecorderSnapshot(true, null, emptyList(), marks = marks.snapshot()).toDto()
        assertNull(dto.marks.single().positionMs)
        assertEquals(5L, dto.marks.single().elapsedMs)
    }

    @Test fun `a worst case recording stays under the relay limit without trimming`() {
        val body = baseReport().copy(recorder = recording(seconds = 1_800, lineChars = 280, lines = 2_000, marks = 40).toDto())
        val sent = fitToRelay(body, serialize = { serialize(it) })
        assertTrue("gzip ${gzippedSize(sent)}", gzippedSize(sent) <= RELAY_MAX_GZIP_BYTES)
        val parsed = requestAdapter.fromJson(String(sent, Charsets.UTF_8))!!
        assertEquals(0, parsed.recorder!!.trimmedSamples)
        assertEquals(1_800, parsed.recorder!!.sampleCount)
        assertEquals(40, parsed.recorder!!.marks.size)
    }

    @Test fun `a body over the limit loses its oldest data first and keeps every mark`() {
        val body = baseReport().copy(recorder = recording(seconds = 1_800, lineChars = 280, lines = 2_000, marks = 40).toDto())
        val budget = gzipOf(body) / 3
        val sent = fitToRelay(body, maxGzipBytes = budget, serialize = { serialize(it) })
        assertTrue("gzip ${gzippedSize(sent)} over $budget", gzippedSize(sent) <= budget)
        val parsed = requestAdapter.fromJson(String(sent, Charsets.UTF_8))!!.recorder!!
        assertEquals(40, parsed.marks.size)
        assertTrue(parsed.trimmedSamples > 0)
        assertTrue(parsed.trimmedEventLines > 0)
        assertEquals(1_800 - parsed.trimmedSamples, parsed.sampleCount)
        val originalAges = body.recorder!!.samples!!.ageMs
        assertEquals(originalAges.last(), parsed.samples!!.ageMs.last())
        assertEquals(originalAges.size - parsed.trimmedSamples, parsed.samples!!.frameBuckets.size)
        assertEquals(body.recorder!!.eventLines.last(), parsed.eventLines.last())
        assertTrue(parsed.coveredMs in 1 until body.recorder!!.coveredMs)
    }

    @Test fun `when nothing else fits the marks alone are sent`() {
        val body = baseReport().copy(recorder = recording(seconds = 60, lineChars = 50, lines = 50, marks = 3).toDto())
        val sent = fitToRelay(body, maxGzipBytes = 10, serialize = { serialize(it) })
        val parsed = requestAdapter.fromJson(String(sent, Charsets.UTF_8))!!.recorder!!
        assertEquals(3, parsed.marks.size)
        assertEquals(0, parsed.sampleCount)
        assertNull(parsed.samples)
        assertTrue(parsed.eventLines.isEmpty())
    }

    @Test fun `a report without a recorder section is not touched`() {
        val body = baseReport()
        val sent = fitToRelay(body, maxGzipBytes = 10, serialize = { serialize(it) })
        assertEquals(String(serialize(body), Charsets.UTF_8), String(sent, Charsets.UTF_8))
    }

    @Test fun `viewer choice without the log keeps the marks`() {
        val body = baseReport().copy(recorder = recording(seconds = 10, lineChars = 20, lines = 5, marks = 2).toDto())
        val sent = body.applyViewerChoices(PlaybackIssueViewerChoices(includeLog = false))
        assertTrue(sent.recorder!!.eventLines.isEmpty())
        assertEquals(2, sent.recorder!!.marks.size)
        assertEquals(10, sent.recorder!!.sampleCount)
    }

    @Test fun `route facts come from the diagnostics card text, one fact per line`() {
        val text = "Box opens: AC3 EAC3 TrueHD\nBox cannot open: DTS DTS-HD\nHDMI device lists: PCM16 AC3 EAC3\n" +
            "Android surround: AUTO\nHDMI device max PCM: 8 ch\nHDMI plug report: PCM16 AC3\n" +
            "Listed by HDMI, not opened by this box: DTS\nHDMI device = the TV or receiver this box is plugged into, not a soundbar"
        val facts = routeFactsFromCapabilities(text)
        assertEquals("AC3 EAC3 TrueHD", facts["boxOpens"])
        assertEquals("DTS DTS-HD", facts["boxCannotOpen"])
        assertEquals("PCM16 AC3 EAC3", facts["hdmiDeviceLists"])
        assertEquals("AUTO", facts["androidSurround"])
        assertEquals("8 ch", facts["hdmiDeviceMaxPcm"])
        assertEquals("PCM16 AC3", facts["hdmiPlugReport"])
        assertTrue(facts.size >= 6)
        assertTrue(routeFactsFromCapabilities(null).isEmpty())
        assertTrue(routeFactsFromCapabilities("unavailable (API 28 < 29)").isEmpty())
    }

    @Test fun `route facts lose links and keys`() {
        val facts = routeFactsFromCapabilities("HDMI plug report: https://eu1.example.com/x?token=SECRET123456 PCM16")
        assertFalse(facts.values.joinToString(), facts.values.joinToString().contains("SECRET123456"))
    }

    @Test fun `settings in force are written out plainly`() {
        val facts = recorderSettingsFacts(
            PlayerSettings(
                surroundTvArcSoundbar = true,
                useSystemPassthrough = true,
                bufferEngineEnabled = true,
                bufferSettings = com.nuvio.tv.data.local.BufferSettings(minBufferMs = 20_000, maxBufferMs = 60_000),
                vodCacheEnabled = true,
                vodCacheSizeMb = 2_048
            ),
            audioDelayMs = -120
        )
        assertEquals("-120", facts["audioDelayMs"])
        assertEquals("AUTO", facts["surroundFormatMode"])
        assertEquals("true", facts["arcSoundbar"])
        assertEquals("true", facts["systemPassthrough"])
        assertEquals("AUTO", facts["dvMode"])
        assertTrue(facts["customBuffers"]!!.startsWith("on min=20000 max=60000"))
        assertTrue(facts["diskCache"]!!.contains("mb=2048"))
        val defaults = recorderSettingsFacts(PlayerSettings(), audioDelayMs = 0)
        assertEquals("off", defaults["customBuffers"])
        assertEquals("false", defaults["arcSoundbar"])
        assertEquals(RECORDER_UNKNOWN, -1)
    }

    private fun baseReport(): PlaybackIssueReportRequestDto = PlaybackIssueReportRequestDto(
        schemaVersion = 1,
        createdAtMs = 1L,
        app = PlaybackIssueAppDto("id", "1", 1L, false),
        device = PlaybackIssueDeviceDto("m", "b", "x", "p", "14", 34, emptyList()),
        content = PlaybackIssueContentDto("A Film", null, null, null, null, null, null, null, null),
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
