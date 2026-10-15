package com.nuvio.tv.core.calendar

import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarTitleFetcherTest {
    private val now = Instant.parse("2026-10-05T23:00:00Z")
    private fun inDays(days: Long) = now.plus(Duration.ofDays(days)).toEpochMilli()

    private class FakeRemote : CalendarRemoteSources {
        val calls = mutableListOf<String>()
        var lookup: TvMazeLookup = TvMazeLookup.NotFound
        var tvMazeShow = CalendarShowDates()
        var traktNext: CalendarShowDates? = null
        var tmdbId: Long? = null
        var tmdbShow = CalendarShowDates()
        var tmdbSeason = emptyList<CalendarEntry>()
        var tmdbFilm = CalendarShowDates()
        var failOn: String? = null
        var failWith: () -> Exception = { IOException("offline") }

        private fun call(name: String) {
            calls += name
            if (failOn == name) throw failWith()
        }

        override suspend fun tvMazeLookup(ids: CalendarIds) = lookup.also { call("lookup") }
        override suspend fun tvMazeShow(id: Int, now: Instant) = tvMazeShow.also { call("tvmaze:$id") }
        override suspend fun traktNextEpisode(ids: CalendarIds) = traktNext.also { call("trakt") }
        override suspend fun tmdbFind(imdb: String, type: CalendarTitleType) = tmdbId.also { call("find:$imdb") }
        override suspend fun tmdbShow(tmdbId: Long) = tmdbShow.also { call("tmdbShow:$tmdbId") }
        override suspend fun tmdbSeason(tmdbId: Long, season: Int) = tmdbSeason.also { call("tmdbSeason:$tmdbId/$season") }
        override suspend fun tmdbFilm(tmdbId: Long, region: String) = tmdbFilm.also { call("tmdbFilm:$tmdbId/$region") }
    }

    private val show = CalendarTitleRecord("series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"))

    @Test fun tvMazeFirstWithItsIdKeptAndFinalesFromTmdb() = runBlocking {
        val remote = FakeRemote().apply {
            lookup = TvMazeLookup.Found(83, CalendarIds(imdb = "tt1", tvdb = 71663))
            tvMazeShow = CalendarShowDates(
                status = CalendarShowStatus.RUNNING,
                entries = listOf(CalendarEntry(season = 2, episode = 8, airInstantMs = inDays(3), source = CalendarSource.TVMAZE)),
                name = "Show",
                network = "FOX",
                ids = CalendarIds(imdb = "tt1", tvdb = 71663)
            )
            tmdbSeason = listOf(
                CalendarEntry(season = 2, episode = 7, airDate = "2026-10-01", source = CalendarSource.TMDB),
                CalendarEntry(season = 2, episode = 8, airDate = "2026-10-08", label = CalendarEpisodeLabel.SEASON_FINALE, source = CalendarSource.TMDB)
            )
        }
        val record = show.copy(
            ids = CalendarIds(imdb = "tt1", tmdb = 5),
            sources = mapOf(CalendarSource.TRAKT_NEXT to listOf(CalendarEntry(season = 2, episode = 8, airInstantMs = inDays(9), source = CalendarSource.TRAKT_NEXT)))
        )
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(record, emptyMap(), coveredByTrakt = false, now = now)

        assertEquals(listOf("lookup", "tvmaze:83", "tmdbSeason:5/2"), remote.calls)
        val updated = outcome.record
        assertEquals(83, updated.tvmazeId)
        assertEquals(71663L, updated.ids.tvdb)
        assertEquals(CalendarShowStatus.RUNNING, updated.status)
        assertEquals("FOX", updated.network)
        assertEquals(setOf(CalendarSource.TVMAZE, CalendarSource.TMDB), updated.sources.keys)
        assertEquals(listOf("S2E8"), updated.sources.getValue(CalendarSource.TMDB).map { it.slot })
        val entry = updated.entries.single()
        assertEquals(inDays(3), entry.airInstantMs)
        assertEquals(CalendarEpisodeLabel.SEASON_FINALE, entry.label)
        assertEquals(0, updated.failures)
        assertEquals(now.toEpochMilli(), updated.fetchedAtMs)
        assertEquals(now.plus(Duration.ofHours(6)).toEpochMilli(), updated.nextRefreshAtMs)
        assertEquals(mapOf("imdb:tt1" to CalendarTvMazeId(83, now.toEpochMilli()), "tvdb:71663" to CalendarTvMazeId(83, now.toEpochMilli())), outcome.tvMazeIds)
    }

    @Test fun aKnownTvMazeIdNeedsNoLookup() = runBlocking {
        val remote = FakeRemote()
        CalendarTitleFetcher(remote, "AU").refresh(show, mapOf("imdb:tt1" to CalendarTvMazeId(7, 1L)), false, now)
        assertEquals(listOf("tvmaze:7"), remote.calls)
    }

    @Test fun showsTvMazeDoesNotKnowAskTraktThenTmdbAndTheMissIsRememberedForAMonth() = runBlocking {
        val remote = FakeRemote().apply {
            tmdbId = 77
            tmdbShow = CalendarShowDates(
                status = CalendarShowStatus.IN_PRODUCTION,
                entries = listOf(CalendarEntry(season = 1, episode = 1, airDate = "2026-11-02", source = CalendarSource.TMDB))
            )
        }
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(show, emptyMap(), false, now)
        assertEquals(listOf("lookup", "trakt", "find:tt1", "tmdbShow:77"), remote.calls)
        assertEquals(77L, outcome.record.ids.tmdb)
        assertEquals(CalendarShowStatus.IN_PRODUCTION, outcome.record.status)
        assertEquals("2026-11-02", outcome.record.entries.single().airDate)
        assertNull(outcome.tvMazeIds.getValue("imdb:tt1").id)

        val again = FakeRemote()
        CalendarTitleFetcher(again, "AU").refresh(show, outcome.tvMazeIds, false, now.plus(Duration.ofDays(29)))
        assertEquals("lookup" in again.calls, false)
        val monthLater = FakeRemote()
        CalendarTitleFetcher(monthLater, "AU").refresh(show, outcome.tvMazeIds, false, now.plus(Duration.ofDays(31)))
        assertEquals("lookup", monthLater.calls.first())
    }

    @Test fun traktsNextEpisodeIsUsedWhenTvMazeHasNothing() = runBlocking {
        val remote = FakeRemote().apply {
            traktNext = CalendarShowDates(entries = listOf(CalendarEntry(season = 4, episode = 1, airInstantMs = inDays(40), source = CalendarSource.TRAKT)))
        }
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(show, emptyMap(), false, now)
        assertEquals(listOf("lookup", "trakt"), remote.calls)
        assertEquals(inDays(40), outcome.record.entries.single().airInstantMs)
        assertEquals(now.plus(Duration.ofDays(7)).toEpochMilli(), outcome.record.nextRefreshAtMs)
    }

    @Test fun aShowTheTraktCalendarCoversMakesNoCalls() = runBlocking {
        val remote = FakeRemote()
        val covered = show.copy(
            poster = "https://example.org/p.jpg",
            sources = mapOf(CalendarSource.TRAKT to listOf(CalendarEntry(season = 1, episode = 2, airInstantMs = inDays(2), source = CalendarSource.TRAKT)))
        )
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(covered, emptyMap(), coveredByTrakt = true, now = now)
        assertTrue(remote.calls.isEmpty())
        assertEquals(now.plus(Duration.ofHours(6)).toEpochMilli(), outcome.record.nextRefreshAtMs)
    }

    @Test fun aCoveredShowWithoutAPosterIsReadOnceAMonthForTvMazesImage() = runBlocking {
        val remote = FakeRemote().apply {
            lookup = TvMazeLookup.Found(83, CalendarIds(imdb = "tt1"))
            tvMazeShow = CalendarShowDates(poster = "https://static.tvmaze.com/m.jpg")
        }
        val covered = show.copy(sources = mapOf(CalendarSource.TRAKT to listOf(CalendarEntry(season = 1, episode = 2, airInstantMs = inDays(2), source = CalendarSource.TRAKT))))
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(covered, emptyMap(), coveredByTrakt = true, now = now)
        assertEquals(listOf("lookup", "tvmaze:83"), remote.calls)
        assertEquals("https://static.tvmaze.com/m.jpg", outcome.record.poster)

        val none = FakeRemote().apply { lookup = TvMazeLookup.Found(83, CalendarIds(imdb = "tt1")) }
        val unchecked = CalendarTitleFetcher(none, "AU").refresh(covered, emptyMap(), coveredByTrakt = true, now = now).record
        assertNull(unchecked.poster)
        assertEquals(now.toEpochMilli(), unchecked.posterCheckedAtMs)
        val soon = FakeRemote()
        CalendarTitleFetcher(soon, "AU").refresh(unchecked, emptyMap(), coveredByTrakt = true, now = now.plus(Duration.ofDays(29)))
        assertTrue(soon.calls.isEmpty())
        val later = FakeRemote()
        CalendarTitleFetcher(later, "AU").refresh(unchecked.copy(tvmazeId = null), emptyMap(), coveredByTrakt = true, now = now.plus(Duration.ofDays(31)))
        assertEquals("lookup", later.calls.first())
    }

    @Test fun aPosterFromTheFollowedSourcesIsKept() = runBlocking {
        val remote = FakeRemote().apply { tvMazeShow = CalendarShowDates(poster = "https://static.tvmaze.com/m.jpg") }
        val withPoster = show.copy(poster = "https://addon.example/p.jpg", tvmazeId = 83)
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(withPoster, emptyMap(), false, now)
        assertEquals("https://addon.example/p.jpg", outcome.record.poster)
    }

    @Test fun tmdbsPosterIsUsedWhenTmdbGivesTheDates() = runBlocking {
        val remote = FakeRemote().apply {
            tmdbId = 77
            tmdbShow = CalendarShowDates(poster = tmdbPosterUrl("/abc.jpg"))
        }
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(show, emptyMap(), false, now)
        assertEquals("https://image.tmdb.org/t/p/w185/abc.jpg", outcome.record.poster)
        assertNull(tmdbPosterUrl(null))
        assertNull(tmdbPosterUrl(""))
    }

    @Test fun filmsUseTmdbReleaseDatesForTheBoxRegion() = runBlocking {
        val remote = FakeRemote().apply {
            tmdbId = 603
            tmdbFilm = CalendarShowDates(entries = listOf(CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = "2026-12-01", source = CalendarSource.TMDB)))
        }
        val film = CalendarTitleRecord("movie:imdb:tt0133093", CalendarTitleType.MOVIE, CalendarIds(imdb = "tt0133093"))
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(film, emptyMap(), false, now)
        assertEquals(listOf("find:tt0133093", "tmdbFilm:603/AU"), remote.calls)
        assertEquals(CalendarEntryKind.MOVIE_DIGITAL, outcome.record.entries.single().kind)
    }

    @Test fun aFailureKeepsTheOldDatesAndBacksOff() = runBlocking {
        val old = CalendarEntry(season = 1, episode = 2, airInstantMs = inDays(2))
        val record = show.copy(tvmazeId = 9, sources = mapOf(CalendarSource.TVMAZE to listOf(old)), failures = 1)
        val remote = FakeRemote().apply { failOn = "tvmaze:9" }
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(record, emptyMap(), false, now)
        assertEquals(2, outcome.record.failures)
        assertEquals(listOf(old), outcome.record.entries)
        assertEquals(now.plus(Duration.ofHours(6)).toEpochMilli(), outcome.record.nextRefreshAtMs)
    }

    @Test fun aFinaleLookupThatFailsDoesNotFailTheShow() = runBlocking {
        val remote = FakeRemote().apply {
            tvMazeShow = CalendarShowDates(entries = listOf(CalendarEntry(season = 2, episode = 3, airInstantMs = inDays(1))))
            failOn = "tmdbSeason:5/2"
        }
        val record = show.copy(tvmazeId = 9, ids = CalendarIds(imdb = "tt1", tmdb = 5))
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(record, emptyMap(), false, now)
        assertEquals(0, outcome.record.failures)
        assertEquals(1, outcome.record.entries.size)
    }

    @Test fun anyErrorCountsAsAFailureNotOnlyNetworkOnes() = runBlocking {
        val remote = FakeRemote().apply {
            failOn = "tvmaze:9"
            failWith = { IllegalStateException("unexpected JSON") }
        }
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(show.copy(tvmazeId = 9), emptyMap(), false, now)
        assertEquals(1, outcome.record.failures)
        assertEquals(now.plus(Duration.ofHours(1)).toEpochMilli(), outcome.record.nextRefreshAtMs)
    }

    @Test fun traktNextEpisodeReplacesItsOldAnswer() = runBlocking {
        val moved = CalendarEntry(season = 4, episode = 1, airInstantMs = inDays(50), source = CalendarSource.TRAKT_NEXT)
        val remote = FakeRemote().apply { traktNext = CalendarShowDates(entries = listOf(moved)) }
        val record = show.copy(sources = mapOf(CalendarSource.TRAKT_NEXT to listOf(moved.copy(airInstantMs = inDays(40)))))
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(record, emptyMap(), false, now)
        assertEquals(listOf(inDays(50)), outcome.record.entries.map { it.airInstantMs })
    }
}
