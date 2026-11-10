package com.nuvio.tv.core.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CalendarDateRulesTest {

    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    private fun country(code: String, type: Int, date: String) = com.nuvio.tv.data.remote.api.TmdbMovieReleaseDateCountry(
        iso31661 = code,
        releaseDates = listOf(com.nuvio.tv.data.remote.api.TmdbMovieReleaseDateItem(type = type, releaseDate = date))
    )

    private fun episode(source: CalendarSource, date: String? = null, instant: String? = null, number: Int = 2) =
        CalendarEntry(season = 2, episode = number, airDate = date, airInstantMs = instant?.let(::at), source = source)

    private fun show(
        vararg sources: Pair<CalendarSource, List<CalendarEntry>>,
        streaming: Boolean = false,
        anime: Boolean = false
    ) = CalendarTitleRecord(
        key = "k",
        type = CalendarTitleType.SERIES,
        ids = CalendarIds(imdb = "tt1"),
        tvmazeStreaming = streaming,
        tvmazeAnime = anime,
        sources = mapOf(*sources)
    )

    @Test fun `a streaming date two or three days ahead of the others wins`() {
        val record = show(
            CalendarSource.TVMAZE to listOf(episode(CalendarSource.TVMAZE, date = "2026-11-06")),
            CalendarSource.TRAKT to listOf(episode(CalendarSource.TRAKT, instant = "2026-11-09T01:00:00Z")),
            CalendarSource.TMDB to listOf(episode(CalendarSource.TMDB, date = "2026-11-08")),
            streaming = true
        )
        assertEquals("2026-11-06", record.entries.single().airDate)
    }

    @Test fun `without a streaming service the exact time still beats the plain date`() {
        val record = show(
            CalendarSource.TVMAZE to listOf(episode(CalendarSource.TVMAZE, date = "2026-11-06")),
            CalendarSource.TRAKT to listOf(episode(CalendarSource.TRAKT, instant = "2026-11-09T01:00:00Z"))
        )
        assertEquals(at("2026-11-09T01:00:00Z"), record.entries.single().airInstantMs)
    }

    @Test fun `a streaming date a week ahead is not trusted over the others`() {
        val record = show(
            CalendarSource.TVMAZE to listOf(episode(CalendarSource.TVMAZE, date = "2026-11-06")),
            CalendarSource.TRAKT to listOf(episode(CalendarSource.TRAKT, instant = "2026-11-13T01:00:00Z")),
            streaming = true
        )
        assertEquals(at("2026-11-13T01:00:00Z"), record.entries.single().airInstantMs)
    }

    @Test fun `a streaming date later than the others is not trusted over them`() {
        val record = show(
            CalendarSource.TVMAZE to listOf(episode(CalendarSource.TVMAZE, date = "2026-11-08")),
            CalendarSource.TRAKT to listOf(episode(CalendarSource.TRAKT, instant = "2026-11-05T05:00:00Z")),
            streaming = true
        )
        assertEquals(at("2026-11-05T05:00:00Z"), record.entries.single().airInstantMs)
    }

    @Test fun `anime uses only TVmaze when it has the show so a differently numbered copy does not appear`() {
        val record = show(
            CalendarSource.TVMAZE to listOf(episode(CalendarSource.TVMAZE, instant = "2026-11-07T13:00:00Z", number = 7)),
            CalendarSource.TRAKT to listOf(episode(CalendarSource.TRAKT, instant = "2026-11-07T13:00:00Z", number = 6)),
            anime = true
        )
        assertEquals(listOf(7), record.entries.map { it.episode })
    }

    @Test fun `anime TVmaze does not know keeps the other sources`() {
        val record = show(
            CalendarSource.TRAKT to listOf(episode(CalendarSource.TRAKT, instant = "2026-11-07T13:00:00Z", number = 6)),
            anime = true
        )
        assertEquals(listOf(6), record.entries.map { it.episode })
    }

    @Test fun `under the Trakt calendar a TVmaze number on a day Trakt has is not a second release`() {
        val record = show(
            CalendarSource.TVMAZE to listOf(episode(CalendarSource.TVMAZE, date = "2026-11-07", number = 7)),
            CalendarSource.TRAKT to listOf(episode(CalendarSource.TRAKT, instant = "2026-11-07T13:00:00Z", number = 6))
        )
        assertEquals(listOf(6), record.entries.map { it.episode })
    }

    @Test fun `under the Trakt calendar TVmaze still adds a release on a day Trakt has nothing`() {
        val record = show(
            CalendarSource.TVMAZE to listOf(episode(CalendarSource.TVMAZE, date = "2026-11-20", number = 8)),
            CalendarSource.TRAKT to listOf(episode(CalendarSource.TRAKT, instant = "2026-11-07T13:00:00Z", number = 6))
        )
        assertEquals(listOf(6, 8), record.entries.map { it.episode })
    }

    @Test fun `Trakt's time for the episode beats TheTVDB's regular slot whichever was read last`() {
        val record = show(
            CalendarSource.TVMAZE to listOf(episode(CalendarSource.TVMAZE, date = "2027-07-09")),
            CalendarSource.TVDB to listOf(episode(CalendarSource.TVDB, instant = "2027-07-09T04:00:00Z")),
            CalendarSource.TRAKT_NEXT to listOf(episode(CalendarSource.TRAKT_NEXT, instant = "2027-07-09T01:00:00Z"))
        ).copy(sourceFetchedAtMs = mapOf(CalendarSource.TVDB to 200L, CalendarSource.TRAKT_NEXT to 100L))
        assertEquals(at("2027-07-09T01:00:00Z"), record.entries.single().airInstantMs)
    }

    @Test fun `a Calendar date up to three days earlier replaces the add-on's`() {
        assertEquals("2026-11-06", calendarEarlierRelease("2026-11-08", episode(CalendarSource.TVMAZE, date = "2026-11-06")))
        assertEquals(
            "2026-11-06T01:00:00Z",
            calendarEarlierRelease("2026-11-09T00:00:00.000Z", episode(CalendarSource.TRAKT, instant = "2026-11-06T01:00:00Z"))
        )
    }

    @Test fun `a later or far earlier Calendar date never touches the add-on's`() {
        assertNull(calendarEarlierRelease("2026-11-06", episode(CalendarSource.TRAKT, date = "2026-11-08")))
        assertNull(calendarEarlierRelease("2026-11-08", episode(CalendarSource.TRAKT, date = "2026-11-08")))
        assertNull(calendarEarlierRelease("2026-11-20", episode(CalendarSource.TVMAZE, date = "2026-11-06")))
        assertNull(calendarEarlierRelease(null, episode(CalendarSource.TVMAZE, date = "2026-11-06")))
    }

    @Test fun `a film with no date nearby takes the earliest anywhere`() {
        val results = listOf(
            country("JP", 3, "2026-12-01"),
            country("KR", 3, "2026-11-20"),
            country("FR", 4, "2027-03-01")
        )
        val entries = tmdbFilmReleaseEntries(results, "AU")
        assertEquals("2026-11-20", entries.first { it.kind == CalendarEntryKind.MOVIE_THEATRICAL }.airDate)
        assertEquals("2027-03-01", entries.first { it.kind == CalendarEntryKind.MOVIE_DIGITAL }.airDate)
    }

    @Test fun `a film with a date nearby does not borrow the other kind from far away`() {
        val results = listOf(
            country("AU", 3, "2026-12-01"),
            country("FR", 4, "2027-03-01")
        )
        val entries = tmdbFilmReleaseEntries(results, "AU")
        assertTrue(entries.none { it.kind == CalendarEntryKind.MOVIE_DIGITAL })
        assertEquals(1, entries.size)
    }
}
