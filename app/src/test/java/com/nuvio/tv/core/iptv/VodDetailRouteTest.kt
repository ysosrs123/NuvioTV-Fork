package com.nuvio.tv.core.iptv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VodDetailRouteTest {
    private val cinemeta = VodMetaAddon(listOf("movie", "series"), listOf("tt"))
    private val tmdbAddon = VodMetaAddon(listOf("movie", "series"), listOf("tmdb:"))
    private val anyId = VodMetaAddon(listOf("movie"), emptyList())

    @Test fun cinemetaNeverGetsTmdbIds() {
        assertTrue(VodDetailRoute.candidates(VodKind.MOVIE, "1401539", null, listOf(cinemeta)).isEmpty())
        assertEquals(listOf(VodDetailTarget("tt0133093", "movie")), VodDetailRoute.candidates(VodKind.MOVIE, "603", "TT0133093", listOf(cinemeta)))
    }

    @Test fun noAddonsOrNoIdsStayOnTheIptvScreen() {
        assertTrue(VodDetailRoute.candidates(VodKind.MOVIE, "603", "tt0133093", emptyList()).isEmpty())
        assertTrue(VodDetailRoute.candidates(VodKind.SERIES, null, null, listOf(cinemeta, tmdbAddon, anyId)).isEmpty())
        assertTrue(VodDetailRoute.candidates(VodKind.EPISODE, "603", "tt0133093", listOf(cinemeta)).isEmpty())
        assertTrue(VodDetailRoute.candidates(VodKind.MOVIE, "abc", "nm123", listOf(cinemeta, tmdbAddon)).isEmpty())
        assertNull(VodDetailRoute.type(VodKind.EPISODE))
    }

    @Test fun imdbComesFirstThenTmdbForAddonsThatTakeIt() {
        assertEquals(listOf(VodDetailTarget("tt0903747", "series"), VodDetailTarget("tmdb:1396", "series")),
            VodDetailRoute.candidates(VodKind.SERIES, "1396", "tt0903747", listOf(cinemeta, tmdbAddon)))
        assertEquals(listOf(VodDetailTarget("tmdb:1401539", "movie")), VodDetailRoute.candidates(VodKind.MOVIE, "1401539", null, listOf(cinemeta, tmdbAddon)))
        assertEquals(listOf(VodDetailTarget("tmdb:5", "movie")), VodDetailRoute.candidates(VodKind.MOVIE, "https://www.themoviedb.org/movie/5", null, listOf(anyId)))
        assertTrue(VodDetailRoute.candidates(VodKind.SERIES, "5", null, listOf(anyId)).isEmpty())
    }

    @Test fun addonMatchingFollowsTypesAndPrefixes() {
        assertTrue(VodMetaAddon(emptyList(), emptyList()).serves("series", "tmdb:1"))
        assertTrue(VodMetaAddon(listOf(" Movie "), listOf("TT")).serves("movie", "tt1"))
        assertFalse(VodMetaAddon(listOf("channel"), emptyList()).serves("movie", "tt1"))
        assertFalse(VodMetaAddon(listOf("movie"), listOf("kitsu:")).serves("movie", "tt1"))
        assertFalse(VodMetaAddon(listOf("movie"), listOf("")).serves("movie", "tt1"))
    }

    @Test fun detailsAreOfferedOnlyWhenAnAddonCanAnswer() {
        assertFalse(VodDetailRoute.offered(VodKind.MOVIE, "1401539", null, listOf(cinemeta), tmdbKey = false))
        assertTrue(VodDetailRoute.offered(VodKind.MOVIE, "1401539", null, listOf(cinemeta), tmdbKey = true))
        assertTrue(VodDetailRoute.offered(VodKind.MOVIE, null, "tt0133093", listOf(cinemeta), tmdbKey = false))
        assertTrue(VodDetailRoute.offered(VodKind.MOVIE, null, "tt0133093", listOf(tmdbAddon), tmdbKey = true))
        assertFalse(VodDetailRoute.offered(VodKind.MOVIE, null, "tt0133093", listOf(tmdbAddon), tmdbKey = false))
        assertTrue(VodDetailRoute.offered(VodKind.MOVIE, "603", "tt0133093", emptyList(), tmdbKey = true))
        assertFalse(VodDetailRoute.offered(VodKind.MOVIE, "603", null, emptyList(), tmdbKey = false))
        assertFalse(VodDetailRoute.offered(VodKind.SERIES, "1396", null, emptyList(), tmdbKey = true))
        assertFalse(VodDetailRoute.offered(VodKind.MOVIE, null, null, listOf(cinemeta, anyId), tmdbKey = true))
        assertTrue(VodDetailRoute.wantsImdb(VodKind.SERIES, listOf(cinemeta)))
        assertFalse(VodDetailRoute.wantsTmdb(VodKind.SERIES, listOf(cinemeta, VodMetaAddon(listOf("series"), emptyList()))))
    }

    @Test fun tmdbPathIsForMoviesOnly() {
        assertEquals(VodDetailTarget("tmdb:1401539", "movie"), VodDetailRoute.tmdbMovie(VodKind.MOVIE, "01401539"))
        assertNull(VodDetailRoute.tmdbMovie(VodKind.SERIES, "1396"))
        assertNull(VodDetailRoute.tmdbMovie(VodKind.MOVIE, "0"))
        assertNull(VodDetailRoute.tmdbMovie(VodKind.MOVIE, null))
    }

    @Test fun ownTargetsUseTheProviderReference() {
        val movie = VodRef(0, "src1", VodKind.MOVIE, "77")
        val series = VodRef(2, "src1", VodKind.SERIES, "9")
        val episode = VodRef.episode(series, "123")
        assertEquals(VodDetailTarget("iptv-vod:0:src1:movie:77", "movie"), VodDetailRoute.own(movie))
        assertEquals(VodDetailTarget("iptv-vod:2:src1:series:9", "series"), VodDetailRoute.own(series))
        assertNull(VodDetailRoute.own(episode))
        assertEquals(series, VodDetailRoute.ownTitle(series.format()))
        assertNull(VodDetailRoute.ownTitle(episode.format()))
        assertNull(VodDetailRoute.ownTitle("tt0133093"))
        assertEquals(episode, VodDetailRoute.playable(episode.format()))
        assertEquals(movie, VodDetailRoute.playable(movie.format()))
        assertNull(VodDetailRoute.playable(series.format()))
        assertNull(VodDetailRoute.playable("tmdb:5"))
    }

    @Test fun providerTextBecomesDetailFields() {
        assertEquals(listOf("Tom Hardy", "Pierce Brosnan", "Helen Mirren"), VodDetailRoute.names(" Tom Hardy, Pierce Brosnan ,, tom hardy / Helen Mirren"))
        assertEquals(listOf("Crime", "Drama"), VodDetailRoute.names("Crime / Drama"))
        assertTrue(VodDetailRoute.names(null).isEmpty())
        assertEquals(2, VodDetailRoute.names("a, b, c", limit = 2).size)
        assertEquals(52, VodDetailRoute.minutes(3100))
        assertNull(VodDetailRoute.minutes(0))
        assertEquals(8.0f, VodDetailRoute.rating(8.0))
        assertNull(VodDetailRoute.rating(0.0))
        assertNull(VodDetailRoute.rating(Double.NaN))
        assertNull(VodDetailRoute.rating(11.0))
    }

    @Test fun openUsesTheFirstIdAnAddonActuallyLoads() = runBlocking {
        val tried = mutableListOf<String>()
        val both = listOf(cinemeta, tmdbAddon)
        assertEquals(VodDetailTarget("tmdb:1396", "series"),
            VodDetailRoute.open(VodKind.SERIES, "1396", "tt0903747", both) { tried += it.itemId; it.itemId.startsWith("tmdb:") })
        assertEquals(listOf("tt0903747", "tmdb:1396"), tried)
        assertNull(VodDetailRoute.open(VodKind.MOVIE, "1401539", null, listOf(cinemeta)) { fail("no addon takes this id"); true })
        assertNull(VodDetailRoute.open(VodKind.MOVIE, "603", "tt0133093", both) { false })
        assertEquals(VodDetailTarget("tt0133093", "movie"), VodDetailRoute.open(VodKind.MOVIE, "603", "tt0133093", both) { true })
    }
}
