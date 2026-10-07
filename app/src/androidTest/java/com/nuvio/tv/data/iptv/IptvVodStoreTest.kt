package com.nuvio.tv.data.iptv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.iptv.PlaylistCatalogueParser
import com.nuvio.tv.core.iptv.VodCategory
import com.nuvio.tv.core.iptv.VodEpisode
import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.core.iptv.VodMovie
import com.nuvio.tv.core.iptv.VodRef
import com.nuvio.tv.core.iptv.VodSeries
import java.io.StringReader
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IptvVodStoreTest {
    private class PlainBox : IptvSecretBox {
        override fun seal(context: String, plaintext: String) = "$context\n$plaintext".toByteArray()
        override fun open(context: String, ciphertext: ByteArray): String = String(ciphertext).also { require(it.startsWith("$context\n")) }.substringAfter('\n')
    }

    @Test fun chunkedImportsPublishAtomicallyAndLookupsFindTitles() = fixture { vod, catalogue, _ ->
        val ref = catalogue.createSource(1, "Xtream", IptvSourceKind.XTREAM, "a", IptvSourceConnection("https://panel.invalid", "u", "p")).ref
        val first = vod.beginImport(ref)
        first.categories(listOf(VodCategory("1", "Action", VodKind.MOVIE), VodCategory("2", "Drama", VodKind.SERIES)))
        repeat(5) { first.movie(VodMovie("${100 + it}", "EN - Movie $it (2020)", categoryId = "1", extension = "mkv", tmdbId = "${500 + it}")) }
        first.series(VodSeries("1396", "Breaking Bad", 2008, "2", tmdbId = "1396"))
        assertNull(vod.state(ref)?.refreshedAtMillis)
        assertTrue(first.publish())
        assertEquals(5, vod.state(ref)!!.movies)
        assertEquals("101", vod.byTmdb(1, VodKind.MOVIE, "501").single().ref.id)
        assertEquals("Movie 3", vod.byMatchKey(1, VodKind.MOVIE, "movie3").single().title)
        assertEquals(listOf("Action"), vod.categories(ref, VodKind.MOVIE).map { it.name })
        val page = vod.page(ref, VodKind.MOVIE, "1", limit = 3)
        assertEquals(listOf("100", "101", "102"), page.items.map { it.ref.id }); assertEquals(3, page.nextOffset)
        assertEquals(listOf("104"), vod.search(1, VodKind.MOVIE, "movie 4").map { it.ref.id })
        assertTrue(vod.byTmdb(2, VodKind.MOVIE, "501").isEmpty())

        val failed = vod.beginImport(ref)
        failed.movie(VodMovie("900", "Other"))
        failed.discard()
        assertEquals(5, vod.page(ref, VodKind.MOVIE, null).items.size)

        val stale = vod.beginImport(ref)
        val next = vod.beginImport(ref)
        stale.movie(VodMovie("901", "Stale"))
        assertThrows(IllegalStateException::class.java) { stale.flush() }
        next.movie(VodMovie("902", "Fresh"))
        assertTrue(next.publish())
        assertFalse(stale.publish())
        assertEquals(listOf("902"), vod.page(ref, VodKind.MOVIE, null).items.map { it.ref.id })
    }

    @Test fun episodesAreCachedPerSeriesAndRemovedWithTheSource() = fixture { vod, catalogue, _ ->
        val ref = catalogue.createSource(1, "Xtream", IptvSourceKind.XTREAM, "a", IptvSourceConnection("https://panel.invalid", "u", "p")).ref
        vod.beginImport(ref).apply { series(VodSeries("1396", "Breaking Bad")); assertTrue(publish()) }
        val series = VodRef(1, ref.sourceId, VodKind.SERIES, "1396")
        assertNull(vod.episodes(series).fetchedAtMillis)
        assertTrue(vod.saveEpisodes(series, listOf(VodEpisode("101", 1, 1, "Pilot", "mkv"), VodEpisode("102", 1, 2, null, "mkv")), VodSeries("1396", "Breaking Bad", tmdbId = "1396")))
        assertEquals(1234L, vod.episodes(series).fetchedAtMillis)
        assertEquals("Pilot", vod.episode(series, 1, 1)?.title)
        assertEquals("1396", vod.title(series)?.tmdbId)
        assertFalse(vod.saveEpisodes(VodRef(1, ref.sourceId, VodKind.SERIES, "9"), emptyList()))
        catalogue.removeSource(ref)
        assertNull(vod.state(ref))
        assertTrue(vod.episodes(series).episodes.isEmpty())
    }

    @Test fun playlistEntriesKeepSealedAddressesAndPlayFromThem() = fixture { vod, catalogue, repository ->
        val ref = catalogue.createSource(2, "List", IptvSourceKind.M3U, "a", IptvSourceConnection("https://lists.invalid/l.m3u")).ref
        val parsed = PlaylistCatalogueParser().parse(StringReader("#EXTM3U\n#EXTINF:-1 group-title=\"VOD\",EN - Heat (1995)\nhttps://p.invalid/movie/u/p/949.mkv\n" +
            "#EXTINF:-1,Bluey S01E02\nhttps://p.invalid/series/u/p/12.mp4\n"))
        assertEquals(IptvVodRefresh.Saved(1, 1, true), repository.savePlaylist(ref, parsed.vod))
        assertTrue(repository.detected(ref))
        val heat = repository.byTitle(2, VodKind.MOVIE, "Heat", 1996).single()
        assertEquals("https://p.invalid/movie/u/p/949.mkv", runBlocking { repository.playback(heat.ref) }.url)
        val bluey = repository.search(2, VodKind.SERIES, "bluey").single()
        val episode = runBlocking { repository.episode(bluey.ref, 1, 2) }!!
        assertEquals("https://p.invalid/series/u/p/12.mp4", runBlocking { repository.playback(episode.ref) }.url)
        repository.setEnabled(ref, false)
        assertTrue(repository.byTitle(2, VodKind.MOVIE, "Heat", 1995).isEmpty())
        assertEquals(IptvVodRefresh.Disabled, repository.savePlaylist(ref, parsed.vod))
        catalogue.removeProfile(2)
        assertTrue(vod.states(2).isEmpty())
    }

    @Test fun xtreamPlaybackIsBuiltFromTheStoredLogin() = fixture { vod, catalogue, repository ->
        val ref = catalogue.createSource(1, "Xtream", IptvSourceKind.XTREAM, "a", IptvSourceConnection("https://panel.invalid:8080", "u", "p")).ref
        vod.beginImport(ref).apply { movie(VodMovie("603", "The Matrix", 1999, extension = "mkv", imdbId = "tt0133093")); assertTrue(publish()) }
        val matrix = repository.find(1, VodKind.MOVIE, null, "tt0133093", "Whatever", null).single()
        assertEquals("https://panel.invalid:8080/movie/u/p/603.mkv", runBlocking { repository.playback(matrix.ref) }.url)
        assertFalse(matrix.toString().contains("panel"))
    }

    @Test fun resumePositionsRecentOrderAndFoundIds() = fixture { vod, catalogue, repository ->
        val ref = catalogue.createSource(1, "Xtream", IptvSourceKind.XTREAM, "a", IptvSourceConnection("https://panel.invalid", "u", "p")).ref
        vod.beginImport(ref).apply {
            movie(VodMovie("1", "Old", addedSeconds = 100)); movie(VodMovie("2", "New", addedSeconds = 300)); movie(VodMovie("3", "Undated"))
            series(VodSeries("77", "Show"))
            assertTrue(publish())
        }
        assertEquals(listOf("2", "1", "3"), vod.page(ref, VodKind.MOVIE, null, recent = true).items.map { it.ref.id })
        val movie = VodRef(1, ref.sourceId, VodKind.MOVIE, "1")
        repository.saveResume(movie, 10_000, 6_000_000)
        assertNull(repository.resume(movie))
        repository.saveResume(movie, 600_000, 6_000_000)
        assertEquals(600_000L, repository.resume(movie)?.positionMillis)
        repository.saveResume(movie, 5_900_000, 6_000_000)
        assertNull(repository.resume(movie))
        val series = VodRef(1, ref.sourceId, VodKind.SERIES, "77")
        val episode = VodRef.episode(series, "9")
        repository.saveResume(episode, 120_000, 1_200_000)
        repository.saveResume(VodRef.episode(VodRef(1, ref.sourceId, VodKind.SERIES, "7"), "9"), 120_000, 1_200_000)
        assertEquals(listOf(episode), repository.resumes(series).map { it.ref })
        vod.saveIds(movie, "603", "tt0133093")
        assertEquals("603", vod.title(movie)?.tmdbId)
        assertEquals("tt0133093", vod.title(movie)?.imdbId)
        repository.setEnabled(ref, false)
        assertEquals(listOf(episode), repository.resumes(series).map { it.ref })
        catalogue.removeSource(ref)
        assertTrue(repository.resumes(series).isEmpty())
    }

    @Test fun versionOneDatabaseGainsResumeTable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "iptv-vod-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("CREATE TABLE vod_sources (source TEXT PRIMARY KEY, profile INTEGER NOT NULL, enabled INTEGER, detected INTEGER NOT NULL, pending INTEGER NOT NULL, active INTEGER, refreshed_at INTEGER, movies INTEGER NOT NULL, series INTEGER NOT NULL)")
                db.version = 1
            }
            IptvVodStore(context, name, PlainBox(), now = { 1234L }).use { vod ->
                val ref = VodRef(1, "src", VodKind.MOVIE, "1")
                vod.saveResume(ref, 60_000, 0)
                assertEquals(60_000L, vod.resume(ref)?.positionMillis)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun fixture(block: (IptvVodStore, IptvCatalogueStore, IptvVodRepository) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val vodName = "iptv-vod-$id.db"
        val catalogueName = "iptv-vod-catalogue-$id.db"
        try {
            IptvVodStore(context, vodName, PlainBox(), now = { 1234L }, chunkRows = 2).use { vod ->
                IptvCatalogueStore(context, catalogueName, PlainBox()).use { catalogue ->
                    catalogue.addRemovalListener(vod)
                    block(vod, catalogue, IptvVodRepository(vod, catalogue, now = { 1234L }))
                }
            }
        } finally {
            context.deleteDatabase(vodName)
            context.deleteDatabase(catalogueName)
        }
    }
}
