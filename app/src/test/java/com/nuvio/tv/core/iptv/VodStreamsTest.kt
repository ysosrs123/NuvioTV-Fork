package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class VodStreamsTest {
    @Test fun requestsParseNuvioIds() {
        assertEquals(VodStreamRequest(VodKind.MOVIE, "tt0133093", null, null, null), VodStreams.request("movie", "tt0133093"))
        assertEquals(VodStreamRequest(VodKind.MOVIE, null, "603", null, null), VodStreams.request("movie", "tmdb:603"))
        assertEquals(VodStreamRequest(VodKind.MOVIE, null, "603", null, null), VodStreams.request("Movie", "tmdb:movie:603"))
        assertEquals(VodStreamRequest(VodKind.SERIES, "tt0903747", null, 1, 2), VodStreams.request("series", "tt0903747:1:2"))
        assertEquals(VodStreamRequest(VodKind.SERIES, null, "1396", 3, 4), VodStreams.request("tv", "tmdb:1396:3:4"))
        assertEquals(VodStreamRequest(VodKind.SERIES, "tt0903747", null, 5, 6), VodStreams.request("series", "tt0903747", 5, 6))
        assertEquals(VodStreamRequest(VodKind.SERIES, null, "1396", 0, 1), VodStreams.request("series", "tmdb:series:1396:0:1"))
    }

    @Test fun unsupportedRequestsAreSkipped() {
        assertNull(VodStreams.request("channel", "tt0133093"))
        assertNull(VodStreams.request("movie", "kitsu:1"))
        assertNull(VodStreams.request("movie", "tmdb:abc"))
        assertNull(VodStreams.request("movie", "iptv-vod:0:src:movie:1"))
        assertNull(VodStreams.request("series", "tt0903747"))
        assertNull(VodStreams.request("series", "tt0903747:1"))
        assertNull(VodStreams.request("series", "tt0903747:x:y"))
        assertNull(VodStreams.request("series", "tt0903747:1:9999"))
    }

    @Test fun streamUrlIsTheReferenceNeverTheProviderAddress() {
        val ref = VodRef(2, "src-1", VodKind.MOVIE, "603")
        val url = ref.format()
        assertEquals("iptv-vod:2:src-1:movie:603", url)
        assertTrue(VodRef.isVod(url))
        assertEquals(ref, VodRef.parse(url))
        assertFalse(url.contains("http"))
        val episode = VodRef.episode(VodRef(2, "src-1", VodKind.SERIES, "77"), "9001")
        assertEquals(episode, VodRef.parse(episode.format()))
        assertNull(VodRef.parse("http://host.invalid/movie/user/pass/603.mkv"))
    }

    @Test fun movieTextKeepsProviderNameAndTags() {
        val text = VodStreams.movie("EN - The Matrix (1999) [4K HEVC]", null, "MKV")
        assertEquals("EN - The Matrix (1999) [4K HEVC]", text.title)
        assertEquals("4K • HEVC • MKV", text.description)
        assertEquals("The Matrix (1999).mkv", text.filename)
        val plain = VodStreams.movie("Heat", 1995, null)
        assertNull(plain.description)
        assertNull(plain.filename)
    }

    @Test fun episodeTextNamesTheEpisode() {
        val text = VodStreams.episode("|UK| Breaking Bad FHD", 1, 2, "Cat's in the Bag...", "mp4")
        assertEquals("|UK| Breaking Bad FHD · S01E02 · Cat's in the Bag...", text.title)
        assertEquals("1080p • MP4", text.description)
        assertEquals("Breaking Bad S01E02.mp4", text.filename)
        assertEquals("Show · S10E120", VodStreams.episode("Show", 10, 120, "Show", null).title)
    }

    @Test fun tagsAreCanonicalAndDistinct() {
        assertEquals(listOf("4K", "HDR", "HEVC"), VodStreams.tags("Film 2160p UHD HDR x265 H.265"))
        assertEquals(listOf("WEB-DL", "Multi"), VodStreams.tags("Film (WEB-DL) MULTI"))
        assertEquals(listOf("4K"), VodStreams.tags("EN-4K Film"))
        assertTrue(VodStreams.tags("The Hidden World").isEmpty())
    }

    @Test fun extensionsAndFilenamesAreSafe() {
        assertEquals("mkv", VodStreams.extension(".MKV"))
        assertNull(VodStreams.extension("m k v"))
        assertNull(VodStreams.extension("toolongext"))
        assertEquals("m3u8", VodStreams.extensionOfUrl("http://host.invalid/vod/a/b/film.m3u8?token=1#x"))
        assertNull(VodStreams.extensionOfUrl("http://host.invalid/vod/123"))
        assertEquals("AC DC Live.mp4", VodStreams.filename("AC/DC: Live", null, "mp4"))
        assertNull(VodStreams.filename("  ", 2000, "mp4"))
    }

    @Test fun connectionsAndRefusals() {
        assertFalse(VodStreams.connectionsBusy(0, 1))
        assertTrue(VodStreams.connectionsBusy(1, 1))
        assertFalse(VodStreams.connectionsBusy(1, 2))
        assertFalse(VodStreams.connectionsBusy(0, 0))
        assertTrue(VodStreams.providerRefusal(403))
        assertTrue(VodStreams.providerRefusal(509))
        assertFalse(VodStreams.providerRefusal(404))
        assertFalse(VodStreams.providerRefusal(200))
    }

    @Test fun revisionTracksStateButNotOrder() {
        assertEquals("off", VodStreams.revision(false, listOf("a")))
        assertEquals(VodStreams.revision(true, listOf("a", "b")), VodStreams.revision(true, listOf("b", "a")))
        assertNotEquals(VodStreams.revision(true, listOf("a:1")), VodStreams.revision(true, listOf("a:2")))
        assertNotEquals("off", VodStreams.revision(true, emptyList()))
    }
}
