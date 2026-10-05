package com.nuvio.tv.core.iptv

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class HlsCapturePlaylistTest {
    private val address = URI("https://fixture.invalid/live/list.m3u8?secret=hidden")
    private val valid = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:4\n#EXTINF:1.5,title\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:2,\nb.ts\n#EXT-X-ENDLIST\n"
    private fun parse(text: String) = HlsCapturePlaylistParser().parse(text.toByteArray(), address)
    private fun rejects(text: String, reason: HlsCaptureFailure = HlsCaptureFailure.INVALID_PLAYLIST) {
        try { parse(text); fail("Accepted invalid playlist") }
        catch (error: HlsCaptureException) { assertEquals(reason, error.failure); assertNull(error.cause); assertFalse(error.message!!.contains("hidden")) }
    }
    @Test fun relativeUrisSequencesAndDiscontinuitiesAreExplicit() {
        val result = parse(valid)
        assertEquals(listOf(4L, 5L), result.segments.map { it.sequence })
        assertEquals(listOf(1500L, 2000L), result.segments.map { it.durationMs })
        assertEquals(listOf(0L, 1L), result.segments.map { it.discontinuity })
        assertEquals(URI("https://fixture.invalid/live/a.ts"), result.segments.first().address)
        assertTrue(result.ended); assertFalse(result.toString().contains("secret"))
        assertFalse(result.segments.first().toString().contains("fixture.invalid"))
    }
    @Test fun discontinuitySequenceSurvivesSlidingWindow() {
        val result = parse(valid.replace("#EXT-X-MEDIA-SEQUENCE:4", "#EXT-X-MEDIA-SEQUENCE:4\n#EXT-X-DISCONTINUITY-SEQUENCE:8"))
        assertEquals(listOf(8L, 9L), result.segments.map { it.discontinuity })
    }
    @Test fun unsupportedTransportFeaturesAreRejectedBeforeMediaFetch() {
        for (tag in listOf("#EXT-X-KEY:METHOD=AES-128,URI=\"key\"", "#EXT-X-MAP:URI=\"init\"",
            "#EXT-X-BYTERANGE:188@0", "#EXT-X-STREAM-INF:BANDWIDTH=100", "#EXT-X-MEDIA:TYPE=AUDIO",
            "#EXT-X-PART:DURATION=1,URI=\"part\"", "#EXT-X-GAP", "#EXT-X-I-FRAMES-ONLY")) {
            rejects(valid.replace("#EXT-X-TARGETDURATION:2", "$tag\n#EXT-X-TARGETDURATION:2"), HlsCaptureFailure.UNSUPPORTED_PLAYLIST)
        }
    }
    @Test fun crossOriginUserinfoAndDowngradeAreRejected() {
        for (uri in listOf("https://other.invalid/a.ts", "http://fixture.invalid/a.ts",
            "https://user:password@fixture.invalid/a.ts", "file:///a.ts", "a.ts#fragment")) {
            rejects(valid.replace("a.ts", uri), HlsCaptureFailure.ADDRESS)
        }
    }
    @Test fun targetDurationIsRequiredBoundedAndNotDuplicated() {
        rejects(valid.replace("#EXT-X-TARGETDURATION:2\n", ""))
        rejects(valid.replace("#EXT-X-TARGETDURATION:2", "#EXT-X-TARGETDURATION:0"))
        rejects(valid.replace("#EXT-X-TARGETDURATION:2", "#EXT-X-TARGETDURATION:121"))
        rejects(valid.replace("#EXT-X-TARGETDURATION:2", "#EXT-X-TARGETDURATION:2\n#EXT-X-TARGETDURATION:2"))
        rejects(valid.replace("#EXTINF:1.5", "#EXTINF:3"))
    }
    @Test fun malformedDurationsAndDanglingSegmentsAreRejected() {
        for (duration in listOf("NaN", "-1", "0", "1e2", "0.000001", "1.5.1")) rejects(valid.replace("#EXTINF:1.5", "#EXTINF:$duration"))
        rejects(valid.replace("#EXTINF:1.5,title\n", ""))
        rejects(valid.replace("a.ts", "#EXTINF:1,\na.ts"))
        rejects(valid.replace("a.ts", ""))
    }
    @Test fun sequenceOverflowAndDuplicateOrLateSequenceFail() {
        rejects(valid.replace(":4", ":9223372036854775807"), HlsCaptureFailure.LIMIT)
        rejects(valid.replace("#EXT-X-MEDIA-SEQUENCE:4", "#EXT-X-MEDIA-SEQUENCE:4\n#EXT-X-MEDIA-SEQUENCE:4"))
        rejects(valid.replace("#EXT-X-MEDIA-SEQUENCE:4\n", "").replace("a.ts", "a.ts\n#EXT-X-MEDIA-SEQUENCE:4"))
        rejects(valid.replace("#EXT-X-DISCONTINUITY\n", "#EXT-X-DISCONTINUITY\n#EXT-X-DISCONTINUITY-SEQUENCE:1\n"))
    }
    @Test fun strictUtf8ControlCharactersAndMissingHeaderFail() {
        rejects(valid.replace("#EXTM3U", "#NOHEADER")); rejects(valid.replace("a.ts", "a\u0000.ts"))
        rejects("\uFEFF$valid")
        try { HlsCapturePlaylistParser().parse(byteArrayOf(0xc3.toByte(), 0x28), address); fail() }
        catch (error: HlsCaptureException) { assertEquals(HlsCaptureFailure.INVALID_PLAYLIST, error.failure) }
    }
    @Test fun byteAndSegmentBudgetsFail() {
        try { HlsCapturePlaylistParser(maxBytes = 8).parse(valid.toByteArray(), address); fail() }
        catch (error: HlsCaptureException) { assertEquals(HlsCaptureFailure.LIMIT, error.failure) }
        try { HlsCapturePlaylistParser(maxSegments = 1).parse(valid.toByteArray(), address); fail() }
        catch (error: HlsCaptureException) { assertEquals(HlsCaptureFailure.LIMIT, error.failure) }
    }
    @Test fun commentsAndCrLfAreAcceptedButMediaAfterEndIsNot() {
        assertTrue(parse(valid.replace("#EXTINF:1.5", "#comment\n#EXTINF:1.5").replace("\n", "\r\n")).ended)
        rejects(valid + "#EXTINF:1,\nc.ts\n")
    }
}
