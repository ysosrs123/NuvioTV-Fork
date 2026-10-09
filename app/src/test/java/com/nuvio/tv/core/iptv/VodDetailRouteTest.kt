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
        assertFalse(VodDetailRoute.offered(VodKind.MOVIE, "603", "tt0133093", emptyList(), tmdbKey = true))
        assertFalse(VodDetailRoute.offered(VodKind.MOVIE, null, null, listOf(cinemeta, anyId), tmdbKey = true))
        assertTrue(VodDetailRoute.wantsImdb(VodKind.SERIES, listOf(cinemeta)))
        assertFalse(VodDetailRoute.wantsTmdb(VodKind.SERIES, listOf(cinemeta, VodMetaAddon(listOf("series"), emptyList()))))
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
