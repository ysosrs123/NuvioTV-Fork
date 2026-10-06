package com.nuvio.tv.core.iptv

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class RecordingHlsTest {
    private val base = URI("http://cdn.invalid/live/channel/index.m3u8?token=t")

    private fun media(first: Long, count: Int, ended: Boolean = false) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:$first\n")
        repeat(count) { append("#EXTINF:6.000,\nseg${first + it}.ts\n") }
        if (ended) append("#EXT-X-ENDLIST\n")
    }

    private fun failure(expected: RecordingFailure, text: String) {
        try { RecordingPlaylistParser.parse(text, base); fail() } catch (error: RecordingStreamException) { assertEquals(expected, error.failure) }
    }

    @Test fun mediaPlaylistResolvesSegmentsInOrder() {
        val playlist = RecordingPlaylistParser.parse(media(100, 3), base) as RecordingPlaylist.Media
        assertEquals(6_000L, playlist.targetMs)
        assertEquals(100L, playlist.mediaSequence)
        assertEquals(listOf(100L, 101L, 102L), playlist.segments.map { it.sequence })
        assertEquals(URI("http://cdn.invalid/live/channel/seg101.ts"), playlist.segments[1].address)
        assertFalse(playlist.ended)
        assertTrue((RecordingPlaylistParser.parse(media(0, 1, ended = true), base) as RecordingPlaylist.Media).ended)
    }

    @Test fun crossOriginAndDiscontinuitiesAreAccepted() {
        val text = "#EXTM3U\r\n#EXT-X-TARGETDURATION:4\r\n#EXTINF:4,\r\nhttps://edge.invalid/a.ts\r\n#EXT-X-DISCONTINUITY\r\n#EXTINF:4,\r\n/b.ts?x=1\r\n"
        val playlist = RecordingPlaylistParser.parse(text, base) as RecordingPlaylist.Media
        assertEquals(listOf(URI("https://edge.invalid/a.ts"), URI("http://cdn.invalid/b.ts?x=1")), playlist.segments.map { it.address })
        assertEquals(listOf(false, true), playlist.segments.map { it.discontinuity })
    }

    @Test fun masterPlaylistPrefersHighestBandwidth() {
        val text = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n" +
            "#EXT-X-STREAM-INF:CODECS=\"avc1.4d401f,mp4a.40.2\",BANDWIDTH=4000000\nhigh/index.m3u8\n"
        val playlist = RecordingPlaylistParser.parse(text, base) as RecordingPlaylist.Variants
        assertEquals(listOf(URI("http://cdn.invalid/live/channel/high/index.m3u8"), URI("http://cdn.invalid/live/channel/low.m3u8")), playlist.addresses)
    }

    @Test fun encryptedFragmentedAndInvalidPlaylistsFailClearly() {
        failure(RecordingFailure.ENCRYPTED_STREAM, "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:6,\na.ts\n")
        failure(RecordingFailure.ENCRYPTED_STREAM, "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:6,\na.ts\n")
        failure(RecordingFailure.UNSUPPORTED_STREAM, "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\na.m4s\n")
        failure(RecordingFailure.UNSUPPORTED_STREAM, "#EXTM3U\n#EXTINF:6,\na.m4s\n")
        failure(RecordingFailure.UNSUPPORTED_STREAM, "#EXTM3U\n#EXT-X-BYTERANGE:100@0\n#EXTINF:6,\na.ts\n")
        failure(RecordingFailure.UNSUPPORTED_STREAM, "not a playlist")
        failure(RecordingFailure.UNSUPPORTED_STREAM, "#EXTM3U\n#EXT-X-TARGETDURATION:6\n")
        failure(RecordingFailure.UNSUPPORTED_STREAM, "#EXTM3U\n#EXTINF:6,\nftp://x.invalid/a.ts\n")
        failure(RecordingFailure.UNSUPPORTED_STREAM, "#EXTM3U\n#EXTINF:6,\nhttp://user:pw@x.invalid/a.ts\n")
        val clear = RecordingPlaylistParser.parse("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:6,\na.ts\n", base) as RecordingPlaylist.Media
        assertEquals(1, clear.segments.size)
    }

    @Test fun attributesHandleQuotedCommas() {
        assertEquals("4000000", RecordingPlaylistParser.attribute("CODECS=\"a,b\",BANDWIDTH=4000000", "BANDWIDTH"))
        assertEquals("a,b", RecordingPlaylistParser.attribute("CODECS=\"a,b\",BANDWIDTH=4000000", "codecs"))
        assertNull(RecordingPlaylistParser.attribute("BANDWIDTH=1", "METHOD"))
    }

    @Test fun cursorStartsNearLiveEdgeAndAppendsInOrderWithoutRepeats() {
        val cursor = RecordingSegmentCursor(liveEdgeSegments = 2)
        val first = cursor.next(RecordingPlaylistParser.parse(media(10, 5), base) as RecordingPlaylist.Media)
        assertEquals(listOf(13L, 14L), first.segments.map { it.sequence })
        assertFalse(first.gap)
        first.segments.forEach(cursor::appended)
        val same = cursor.next(RecordingPlaylistParser.parse(media(10, 5), base) as RecordingPlaylist.Media)
        assertTrue(same.segments.isEmpty())
        val next = cursor.next(RecordingPlaylistParser.parse(media(12, 5), base) as RecordingPlaylist.Media)
        assertEquals(listOf(15L, 16L), next.segments.map { it.sequence })
        assertFalse(next.gap)
        next.segments.forEach(cursor::appended)
        val stale = cursor.next(RecordingPlaylistParser.parse(media(11, 5), base) as RecordingPlaylist.Media)
        assertTrue(stale.segments.isEmpty())
        assertFalse(stale.gap)
    }

    @Test fun cursorReportsMissedSegmentsAndSequenceResets() {
        val cursor = RecordingSegmentCursor(liveEdgeSegments = 1)
        cursor.next(RecordingPlaylistParser.parse(media(10, 3), base) as RecordingPlaylist.Media).segments.forEach(cursor::appended)
        assertEquals(12L, cursor.lastSequence)
        val jumped = cursor.next(RecordingPlaylistParser.parse(media(20, 3), base) as RecordingPlaylist.Media)
        assertEquals(listOf(20L, 21L, 22L), jumped.segments.map { it.sequence })
        assertTrue(jumped.gap)
        jumped.segments.forEach(cursor::appended)
        val reset = cursor.next(RecordingPlaylistParser.parse(media(0, 3), base) as RecordingPlaylist.Media)
        assertEquals(listOf(2L), reset.segments.map { it.sequence })
        assertTrue(reset.gap)
        cursor.restart()
        assertNull(cursor.lastSequence)
    }
}
