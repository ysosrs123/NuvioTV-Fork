package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class VodArtworkTest {
    @Test fun keysPreferIdsThenTitleWithYear() {
        assertEquals("en:movie:tmdb:603", VodArtwork.key("en", VodKind.MOVIE, "603", "tt0133093", "The Matrix", 1999))
        assertEquals("pt-br:series:imdb:tt0903747", VodArtwork.key("pt-BR", VodKind.SERIES, null, "TT0903747", "Breaking Bad", null))
        assertEquals("en:movie:title:matrix:1999", VodArtwork.key("en", VodKind.MOVIE, null, null, "EN | The Matrix (1999) 4K", null))
        assertNull(VodArtwork.key("en", VodKind.MOVIE, null, null, "The Matrix", null))
        assertNotEquals(VodArtwork.key("en", VodKind.MOVIE, "603", null, "x", null), VodArtwork.key("de", VodKind.MOVIE, "603", null, "x", null))
    }

    @Test fun titleSearchNeedsOneConfidentMatch() {
        val matrix = VodArtCandidate("603", "The Matrix", "The Matrix", 1999)
        val other = VodArtCandidate("604", "The Matrix Reloaded", null, 2003)
        assertEquals(matrix, VodArtwork.pick("The Matrix (1999)", null, listOf(other, matrix)))
        assertEquals(matrix, VodArtwork.pick("Matrix, The", 1999, listOf(matrix)))
        assertNull(VodArtwork.pick("The Matrix", null, listOf(matrix)))
        assertNull(VodArtwork.pick("The Matrix", 2005, listOf(matrix)))
        assertEquals(matrix, VodArtwork.pick("The Matrix", 2000, listOf(matrix)))
        assertNull(VodArtwork.pick("The Matrix", 1999, listOf(matrix, VodArtCandidate("9", "Matrix", null, 1999))))
        assertEquals(matrix, VodArtwork.pick("The Matrix", 1999, listOf(matrix, matrix)))
        val remake = VodArtCandidate("700", "Dune", null, 2021)
        assertEquals(remake, VodArtwork.pick("Dune 2021", null, listOf(VodArtCandidate("841", "Dune", null, 1984), remake)))
        assertEquals(VodArtCandidate("5", "Amélie", "Le Fabuleux Destin d'Amélie Poulain", 2001),
            VodArtwork.pick("Le fabuleux destin d'Amelie Poulain", 2001, listOf(VodArtCandidate("5", "Amélie", "Le Fabuleux Destin d'Amélie Poulain", 2001))))
    }

    @Test fun detailTargetPrefersImdb() {
        assertEquals(VodDetailTarget("tt0133093", "movie"), VodArtwork.target(VodKind.MOVIE, "603", "tt0133093"))
        assertEquals(VodDetailTarget("tmdb:1396", "series"), VodArtwork.target(VodKind.SERIES, "1396", null))
        assertNull(VodArtwork.target(VodKind.MOVIE, null, null))
        assertNull(VodArtwork.target(VodKind.MOVIE, "abc", "nope"))
        assertNull(VodArtwork.target(VodKind.EPISODE, "1", null))
    }

    @Test fun artRoundTripsAndDropsUnsafeImages() {
        val art = VodArt("603", "tt0133093", "The Matrix", "https://image.tmdb.org/t/p/w500/a.jpg", "http://plain.invalid/b.jpg", "Plot", 1999, 8.2)
        val back = VodArtwork.decode(VodArtwork.encode(art)).getOrThrow()
        assertEquals(art.copy(backdrop = null), back)
        assertNull(VodArtwork.decode(VodArtwork.encode(null)).getOrThrow())
        assertTrue(VodArtwork.decode("not json").isFailure)
        assertFalse(art.toString().contains("tt0133093"))
    }

    @Test fun cacheExpiresBoundsAndPersists() {
        var now = 1_000L
        val cache = VodArtCache(2, 100, 10) { now }
        cache.put("a", VodArt(tmdbId = "1"))
        cache.put("b", null)
        assertTrue(cache.contains("b"))
        assertNull(cache.get("b"))
        now += 11
        assertFalse(cache.contains("b"))
        assertEquals("1", cache.get("a")?.tmdbId)
        cache.put("c", VodArt(tmdbId = "3"))
        cache.put("d", VodArt(tmdbId = "4"))
        assertEquals(2, cache.size)
        assertFalse(cache.contains("a"))
        val lines = cache.lines()
        val copy = VodArtCache(5, 100, 10) { now }
        assertEquals(2, copy.load(lines.asSequence() + sequenceOf("garbage", "{\"k\":\"x\",\"t\":-5,\"v\":\"{}\"}")))
        assertEquals("4", copy.get("d")?.tmdbId)
        now += 200
        assertFalse(copy.contains("d"))
        assertTrue(copy.lines().isEmpty())
    }

    @Test fun resumeKeepsMiddleAndClearsNearEnd() {
        assertFalse(VodResume.keep(10_000, 6_000_000))
        assertTrue(VodResume.keep(60_000, 6_000_000))
        assertTrue(VodResume.keep(60_000, 0))
        assertFalse(VodResume.keep(5_600_000, 6_000_000))
        assertTrue(VodResume.finished(5_600_000, 6_000_000))
        assertFalse(VodResume.finished(5_600_000, 0))
        assertEquals(.5f, VodResume.fraction(3_000_000, 6_000_000)!!, .001f)
        assertNull(VodResume.fraction(1, 0))
    }
}
