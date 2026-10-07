package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class VodRefTest {
    @Test fun referencesRoundTrip() {
        val movie = VodRef(3, "5f0c1e8a-2b7d-4c1e-9a55-0d1e2f3a4b5c", VodKind.MOVIE, "603")
        assertEquals("iptv-vod:3:5f0c1e8a-2b7d-4c1e-9a55-0d1e2f3a4b5c:movie:603", movie.format())
        assertEquals(movie, VodRef.parse(movie.format()))
        val series = VodRef(0, "src", VodKind.SERIES, "1396")
        val episode = VodRef.episode(series, "101")
        assertEquals("iptv-vod:0:src:episode:1396.101", episode.format())
        assertEquals(episode, VodRef.parse(episode.format()))
        assertEquals(series, episode.series)
        assertEquals("1396", episode.seriesId); assertEquals("101", episode.itemId)
        assertNull(movie.seriesId); assertEquals("603", movie.itemId)
        assertTrue(VodRef.isVod(movie.toString()))
    }

    @Test fun malformedReferencesAreRejected() {
        val bad = listOf(null, "", "iptv-vod:", "iptv-vod:1:src:movie", "iptv-vod:1:src:movie:603:x", "iptv-vod:-1:src:movie:603",
            "iptv-vod:1:src:film:603", "iptv-vod:1:s c:movie:603", "iptv-vod:1:src:episode:101", "iptv-vod:1:src:movie:1.2",
            "iptv-vod:x:src:movie:603", "iptv-vod:99999999999:src:movie:603", "http://host.invalid/movie/u/p/603.mkv",
            "iptv-vod:1:src:movie:" + "a".repeat(81))
        for (value in bad) assertNull(value, VodRef.parse(value))
        assertFalse(VodRef.isVod("http://host.invalid/movie/u/p/603.mkv"))
        assertThrows(IllegalArgumentException::class.java) { VodRef.episode(VodRef(1, "src", VodKind.MOVIE, "1"), "2") }
    }
}
