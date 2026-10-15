package com.nuvio.tv.core.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarPostersTest {
    private fun candidate(imdb: String, poster: String? = null, type: CalendarTitleType = CalendarTitleType.SERIES) =
        CalendarTitleCandidate(CalendarIds(imdb = imdb), type, CalendarOrigin.TRAKT_WATCHED, contentId = imdb, poster = poster)

    @Test fun theAddonPosterInMemoryComesBeforeContinueWatching() {
        val posters = calendarCachedPosters(
            listOf(candidate("tt1")),
            metaPoster = { type, id -> if (type == "series" && id == "tt1") "addon.jpg" else null },
            nextUpPosters = listOf("tt1" to "next-up.jpg")
        )
        assertEquals("addon.jpg", posters["imdb:tt1"])
    }

    @Test fun continueWatchingFillsInWhenTheDetailsAreNotInMemory() {
        val posters = calendarCachedPosters(
            listOf(candidate("tt1")),
            metaPoster = { _, _ -> null },
            nextUpPosters = listOf("tt1" to "next-up.jpg", "tt9" to "other.jpg")
        )
        assertEquals(mapOf("imdb:tt1" to "next-up.jpg"), posters)
    }

    @Test fun titlesWithAPosterAskForNothing() {
        var asked = 0
        val posters = calendarCachedPosters(
            listOf(candidate("tt1", poster = "own.jpg")),
            metaPoster = { _, _ -> asked++; "addon.jpg" },
            nextUpPosters = listOf("tt1" to "next-up.jpg")
        )
        assertTrue(posters.isEmpty())
        assertEquals(0, asked)
    }

    @Test fun filmsAreLookedUpAsFilms() {
        val posters = calendarCachedPosters(
            listOf(candidate("tt2", type = CalendarTitleType.MOVIE)),
            metaPoster = { type, _ -> if (type == "movie") "film.jpg" else null },
            nextUpPosters = emptyList()
        )
        assertEquals("film.jpg", posters["imdb:tt2"])
    }

    @Test fun onlyTitlesWithoutAPosterAreFilled() {
        val bare = CalendarFollowedTitle("series:imdb:tt1", CalendarIds(imdb = "tt1"), CalendarTitleType.SERIES, emptySet(), null)
        val own = bare.copy(key = "series:imdb:tt2", ids = CalendarIds(imdb = "tt2"), poster = "own.jpg")
        val other = bare.copy(key = "series:imdb:tt3", ids = CalendarIds(imdb = "tt3"))
        val filled = listOf(bare, own, other).withCachedPosters(mapOf("imdb:tt1" to "cached.jpg", "imdb:tt2" to "cached2.jpg"))
        assertEquals("cached.jpg", filled[0].poster)
        assertEquals("own.jpg", filled[1].poster)
        assertNull(filled[2].poster)
    }

    @Test fun aCachedPosterReplacesAStoredFallbackAndAStoredOneStaysWithoutIt() {
        val title = CalendarFollowedTitle("series:imdb:tt1", CalendarIds(imdb = "tt1"), CalendarTitleType.SERIES, emptySet(), null)
        val stored = CalendarTitleRecord("series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"), poster = "tvmaze.jpg")
        val withCached = reconcileCalendarRecords(listOf(title).withCachedPosters(mapOf("imdb:tt1" to "addon.jpg")), listOf(stored))
        assertEquals("addon.jpg", withCached.single().poster)
        val without = reconcileCalendarRecords(listOf(title), listOf(stored))
        assertEquals("tvmaze.jpg", without.single().poster)
    }
}
