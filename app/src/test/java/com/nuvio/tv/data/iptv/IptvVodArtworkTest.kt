package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.VodArt
import com.nuvio.tv.core.iptv.VodArtCandidate
import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.core.iptv.VodRef
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IptvVodArtworkTest {
    @get:Rule val temp = TemporaryFolder()

    private class FakeLookup : IptvVodArtLookup {
        val calls = AtomicInteger()
        var limited = false
        var failing = false
        override suspend fun details(kind: VodKind, tmdbId: String, language: String): VodArt? {
            calls.incrementAndGet()
            if (limited) throw IptvVodArtRateLimited(5_000)
            if (failing) throw java.io.IOException("offline")
            return if (tmdbId == "404") null else VodArt(tmdbId, null, "Title $tmdbId", "https://image.tmdb.org/t/p/w500/$tmdbId.jpg", null, "Plot", 1999, 7.5)
        }
        override suspend fun tmdbId(kind: VodKind, imdbId: String): String? { calls.incrementAndGet(); return if (imdbId == "tt0133093") "603" else null }
        override suspend fun search(kind: VodKind, title: String, year: Int, language: String): List<VodArtCandidate> {
            calls.incrementAndGet()
            return listOf(VodArtCandidate("949", "Heat", null, 1995), VodArtCandidate("950", "Heat", null, 1986))
        }
        override suspend fun addon(kind: VodKind, imdbId: String): VodArt? { calls.incrementAndGet(); return VodArt(null, imdbId, "Addon", null, null, "From add-on", null, null) }
    }

    private fun title(id: String, name: String, year: Int? = null, tmdb: String? = null, imdb: String? = null, kind: VodKind = VodKind.MOVIE) =
        IptvVodTitle(VodRef(1, "src", kind, id), name, name, year, null, "http://provider.invalid/$id.jpg", null, null, null, tmdb, imdb)

    @Test fun resolvesByIdsTitleAndAddonThenCaches() = runBlocking {
        val lookup = FakeLookup()
        val artwork = IptvVodArtwork(temp.root, lookup)
        val titles = listOf(title("1", "The Matrix", tmdb = "603"), title("2", "Matrix", imdb = "tt0133093"), title("3", "EN - Heat (1995)"),
            title("4", "Unknown"), title("5", "Old", imdb = "tt0000001"), title("6", "Nothing", 2001))
        val found = artwork.resolve(titles, "en")
        assertEquals("603", found[titles[0].ref]?.tmdbId)
        assertEquals("tt0133093", found[titles[1].ref]?.imdbId)
        assertEquals("603", found[titles[1].ref]?.tmdbId)
        assertEquals("949", found[titles[2].ref]?.tmdbId)
        assertNull(found[titles[3].ref])
        assertEquals("From add-on", found[titles[4].ref]?.overview)
        assertNull(found[titles[5].ref])
        assertFalse(artwork.known(title("7", "Other", 2000), "en"))
        assertTrue(artwork.known(titles[5], "en"))
        val before = lookup.calls.get()
        assertEquals(found, artwork.resolve(titles, "en"))
        assertEquals(before, lookup.calls.get())
        val reloaded = IptvVodArtwork(temp.root, FakeLookup().apply { failing = true })
        assertEquals(found, reloaded.resolve(titles, "en"))
        assertEquals("949", reloaded.cached(titles[2], "en")?.tmdbId)
    }

    @Test fun failuresAreNotCachedAndRateLimitsPause() = runBlocking {
        var now = 1_000_000L
        val lookup = FakeLookup().apply { failing = true }
        val artwork = IptvVodArtwork(temp.root, lookup, now = { now })
        val matrix = title("1", "The Matrix", tmdb = "603")
        assertTrue(artwork.resolve(listOf(matrix), "en").isEmpty())
        assertFalse(artwork.known(matrix, "en"))
        lookup.failing = false
        lookup.limited = true
        assertTrue(artwork.resolve(listOf(matrix), "en").isEmpty())
        lookup.limited = false
        val calls = lookup.calls.get()
        assertTrue(artwork.resolve(listOf(matrix), "en").isEmpty())
        assertEquals(calls, lookup.calls.get())
        now += 6_000
        assertEquals("603", artwork.resolve(listOf(matrix), "en")[matrix.ref]?.tmdbId)
        assertNull(artwork.cached(matrix, "de"))
    }
}
