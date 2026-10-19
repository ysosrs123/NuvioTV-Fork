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
        override suspend fun tvMazeSchedule(wanted: Set<String>): TvMazeSchedule = throw IOException("not used by the fetcher")
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

        assertEquals(listOf("tmdbShow:5", "lookup", "tvmaze:83", "tmdbSeason:5/2"), remote.calls)
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
        assertEquals(listOf("find:tt1", "tmdbShow:77", "lookup", "trakt"), remote.calls)
        assertEquals(77L, outcome.record.ids.tmdb)
        assertEquals(CalendarShowStatus.IN_PRODUCTION, outcome.record.status)
        assertEquals("2026-11-02", outcome.record.entries.single().airDate)
        assertNull(outcome.tvMazeIds.getValue("imdb:tt1").id)

        val again = FakeRemote()
        CalendarTitleFetcher(again, "AU").refresh(show, outcome.tvMazeIds, false, now.plus(Duration.ofDays(29)))
        assertEquals("lookup" in again.calls, false)
        val monthLater = FakeRemote()
        CalendarTitleFetcher(monthLater, "AU").refresh(show, outcome.tvMazeIds, false, now.plus(Duration.ofDays(31)))
        assertTrue("lookup" in monthLater.calls)
    }

    @Test fun traktsNextEpisodeIsUsedWhenTvMazeHasNothing() = runBlocking {
        val remote = FakeRemote().apply {
            traktNext = CalendarShowDates(entries = listOf(CalendarEntry(season = 4, episode = 1, airInstantMs = inDays(40), source = CalendarSource.TRAKT)))
        }
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(show, emptyMap(), false, now)
        assertEquals(listOf("find:tt1", "lookup", "trakt"), remote.calls)
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
        assertEquals(listOf("find:tt1", "lookup", "tvmaze:83"), remote.calls)
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
        assertTrue("lookup" in later.calls)
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

    @Test fun aShowTmdbKnowsWithNothingSoonIsLeftWithTmdbsDates() = runBlocking {
        val remote = FakeRemote().apply {
            tmdbId = 77
            tmdbShow = CalendarShowDates(
                status = CalendarShowStatus.RUNNING,
                entries = listOf(CalendarEntry(season = 3, episode = 1, airDate = "2027-02-01", source = CalendarSource.TMDB))
            )
        }
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(show, emptyMap(), false, now)
        assertEquals(listOf("find:tt1", "tmdbShow:77"), remote.calls)
        assertEquals(77L, outcome.record.ids.tmdb)
        assertNull(outcome.record.tvmazeId)
        assertEquals("2027-02-01", outcome.record.entries.single().airDate)
        assertEquals(now.plus(Duration.ofDays(7)).toEpochMilli(), outcome.record.nextRefreshAtMs)
        assertTrue(outcome.tvMazeIds.isEmpty())
    }

    @Test fun endedAndBetweenSeasonShowsAreLeftWithTmdb() = runBlocking {
        for (status in listOf(CalendarShowStatus.ENDED, CalendarShowStatus.RUNNING)) {
            val remote = FakeRemote().apply {
                tmdbId = 77
                tmdbShow = CalendarShowDates(
                    status = status,
                    entries = listOf(CalendarEntry(season = 2, episode = 10, airDate = "2025-05-01", source = CalendarSource.TMDB))
                )
            }
            val outcome = CalendarTitleFetcher(remote, "AU").refresh(show, emptyMap(), false, now)
            assertEquals(listOf("find:tt1", "tmdbShow:77"), remote.calls)
            assertEquals(status, outcome.record.status)
        }
    }

    @Test fun aReleaseWithinSixtyDaysAsksTvMazeForTheTime() = runBlocking {
        val remote = FakeRemote().apply {
            tmdbId = 77
            tmdbShow = CalendarShowDates(
                status = CalendarShowStatus.RUNNING,
                entries = listOf(CalendarEntry(season = 3, episode = 1, airDate = "2026-11-20", source = CalendarSource.TMDB))
            )
            lookup = TvMazeLookup.Found(83, CalendarIds(imdb = "tt1"))
            tvMazeShow = CalendarShowDates(
                status = CalendarShowStatus.RUNNING,
                entries = listOf(CalendarEntry(season = 3, episode = 1, airInstantMs = inDays(45), source = CalendarSource.TVMAZE))
            )
        }
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(show, emptyMap(), false, now)
        assertEquals(listOf("find:tt1", "tmdbShow:77", "lookup", "tvmaze:83", "tmdbSeason:77/3"), remote.calls)
        assertEquals(83, outcome.record.tvmazeId)
        assertEquals(inDays(45), outcome.record.entries.single().airInstantMs)
    }

    @Test fun aShowWhoseTvMazeIdIsKnownSkipsTmdb() = runBlocking {
        val remote = FakeRemote().apply { tmdbId = 77 }
        CalendarTitleFetcher(remote, "AU").refresh(show.copy(tvmazeId = 9), emptyMap(), false, now)
        assertEquals(listOf("tvmaze:9"), remote.calls)
    }

    @Test fun aTmdbFailureFallsBackToTvMazeWithoutFailingTheShow() = runBlocking {
        val remote = FakeRemote().apply {
            tmdbId = 77
            failOn = "tmdbShow:77"
            lookup = TvMazeLookup.Found(83, CalendarIds(imdb = "tt1"))
            tvMazeShow = CalendarShowDates(entries = listOf(CalendarEntry(season = 1, episode = 2, airInstantMs = inDays(2))))
        }
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(show, emptyMap(), false, now)
        assertEquals(listOf("find:tt1", "tmdbShow:77", "lookup", "tvmaze:83", "tmdbSeason:77/1"), remote.calls)
        assertEquals(0, outcome.record.failures)
        assertEquals(83, outcome.record.tvmazeId)
    }

    @Test fun tmdbIsAskedOnceWhenNeitherTvMazeNorTraktKnowTheShow() = runBlocking {
        val remote = FakeRemote().apply {
            tmdbId = 77
            traktNext = CalendarShowDates()
        }
        CalendarTitleFetcher(remote, "AU").refresh(show, emptyMap(), false, now)
        assertEquals(listOf("find:tt1", "tmdbShow:77", "lookup", "trakt"), remote.calls)
    }

    @Test fun aShowTheScheduleCoversWithNothingNearMakesNoCalls() = runBlocking {
        val remote = FakeRemote().apply { tmdbId = 77 }
        val scheduled = show.copy(tvmazeId = 83, scheduleCheckedAtMs = now.toEpochMilli())
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(scheduled, emptyMap(), false, now)
        assertTrue(remote.calls.isEmpty())
        assertEquals(now.plus(Duration.ofDays(30)).toEpochMilli(), outcome.record.nextRefreshAtMs)
    }

    @Test fun aShowTheScheduleCoversOnlyAsksTmdbForFinalesWhileAReleaseIsNear() = runBlocking {
        val remote = FakeRemote().apply {
            tmdbId = 77
            tmdbSeason = listOf(
                CalendarEntry(season = 1, episode = 8, airDate = "2026-10-12", label = CalendarEpisodeLabel.SEASON_FINALE, source = CalendarSource.TMDB)
            )
        }
        val scheduled = show.copy(
            tvmazeId = 83, scheduleCheckedAtMs = now.toEpochMilli(),
            sources = mapOf(CalendarSource.TVMAZE to listOf(CalendarEntry(season = 1, episode = 8, airInstantMs = inDays(6))))
        )
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(scheduled, emptyMap(), false, now)
        assertEquals(listOf("find:tt1", "tmdbSeason:77/1"), remote.calls)
        assertEquals(CalendarEpisodeLabel.SEASON_FINALE, outcome.record.entries.single().label)
        assertEquals(now.plus(Duration.ofDays(1)).toEpochMilli(), outcome.record.nextRefreshAtMs)

        val again = FakeRemote().apply { tmdbId = 77 }
        CalendarTitleFetcher(again, "AU").refresh(outcome.record, emptyMap(), false, now.plus(Duration.ofHours(12)))
        assertTrue(again.calls.isEmpty())
    }

    @Test fun aFinaleLookupThatFailsLeavesTheScheduleDates() = runBlocking {
        val remote = FakeRemote().apply { failOn = "find:tt1" }
        val entry = CalendarEntry(season = 1, episode = 8, airInstantMs = inDays(6))
        val scheduled = show.copy(
            tvmazeId = 83, scheduleCheckedAtMs = now.toEpochMilli(), sources = mapOf(CalendarSource.TVMAZE to listOf(entry))
        )
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(scheduled, emptyMap(), false, now)
        assertEquals(0, outcome.record.failures)
        assertEquals(listOf(entry), outcome.record.entries)
    }

    @Test fun aFilmTheLibraryDatesYearsBackMakesNoCalls() = runBlocking {
        val remote = FakeRemote().apply { tmdbId = 603 }
        val film = CalendarTitleRecord("movie:imdb:tt0133093", CalendarTitleType.MOVIE, CalendarIds(imdb = "tt0133093"), releaseYear = 2024)
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(film, emptyMap(), false, now)
        assertTrue(remote.calls.isEmpty())
        assertEquals(now.plus(Duration.ofDays(365)).toEpochMilli(), outcome.record.nextRefreshAtMs)

        val recent = FakeRemote().apply { tmdbId = 603 }
        CalendarTitleFetcher(recent, "AU").refresh(film.copy(releaseYear = 2025), emptyMap(), false, now)
        assertEquals(listOf("find:tt0133093", "tmdbFilm:603/AU"), recent.calls)
    }

    @Test fun aShowTheScheduleDoesNotListIsLookedUpOnceAndThenLeftAlone() = runBlocking {
        val remote = FakeRemote().apply { lookup = TvMazeLookup.Found(83, CalendarIds(imdb = "tt1")) }
        val unlisted = show.copy(scheduleCheckedAtMs = now.toEpochMilli())
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(unlisted, emptyMap(), false, now)
        assertEquals(listOf("lookup"), remote.calls)
        assertEquals(83, outcome.record.tvmazeId)
        assertEquals(now.toEpochMilli(), outcome.record.scheduleCheckedAtMs)
        assertEquals(CalendarTvMazeId(83, now.toEpochMilli()), outcome.tvMazeIds["imdb:tt1"])
        assertEquals(now.plus(Duration.ofDays(30)).toEpochMilli(), outcome.record.nextRefreshAtMs)

        val later = FakeRemote()
        CalendarTitleFetcher(later, "AU").refresh(outcome.record, outcome.tvMazeIds, false, now.plus(Duration.ofDays(30)))
        assertTrue(later.calls.isEmpty())
    }

    @Test fun aShowTvMazeCannotMatchMovesToThePerTitleRefresh() = runBlocking {
        val remote = FakeRemote().apply {
            traktNext = CalendarShowDates(entries = listOf(CalendarEntry(season = 4, episode = 1, airInstantMs = inDays(12), source = CalendarSource.TRAKT_NEXT)))
        }
        val unlisted = show.copy(scheduleCheckedAtMs = now.toEpochMilli())
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(unlisted, emptyMap(), false, now)
        assertEquals(listOf("lookup", "find:tt1", "trakt"), remote.calls)
        assertEquals(0L, outcome.record.scheduleCheckedAtMs)
        assertEquals(inDays(12), outcome.record.entries.single().airInstantMs)
        assertEquals(null, outcome.tvMazeIds.getValue("imdb:tt1").id)
    }

    @Test fun aShowOnAirIsSentToTvMazeEvenWhenTmdbHasNoNextEpisodeYet() = runBlocking {
        val remote = FakeRemote().apply {
            tmdbId = 77
            tmdbShow = CalendarShowDates(
                status = CalendarShowStatus.RUNNING,
                entries = listOf(CalendarEntry(season = 2, episode = 4, airDate = "2026-09-25", source = CalendarSource.TMDB))
            )
            lookup = TvMazeLookup.Found(83, CalendarIds(imdb = "tt1"))
            tvMazeShow = CalendarShowDates(
                status = CalendarShowStatus.RUNNING,
                entries = listOf(CalendarEntry(season = 2, episode = 5, airInstantMs = inDays(2), source = CalendarSource.TVMAZE))
            )
        }
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(show, emptyMap(), false, now)
        assertEquals(listOf("find:tt1", "tmdbShow:77", "lookup", "tvmaze:83", "tmdbSeason:77/2"), remote.calls)
        assertEquals(83, outcome.record.tvmazeId)
        assertEquals(inDays(2), outcome.record.entries.first { it.episode == 5 }.airInstantMs)

        val lastMonth = FakeRemote().apply {
            tmdbId = 77
            tmdbShow = CalendarShowDates(
                status = CalendarShowStatus.RUNNING,
                entries = listOf(CalendarEntry(season = 2, episode = 4, airDate = "2026-09-15", source = CalendarSource.TMDB))
            )
        }
        CalendarTitleFetcher(lastMonth, "AU").refresh(show, emptyMap(), false, now)
        assertEquals(listOf("find:tt1", "tmdbShow:77"), lastMonth.calls)
    }

    @Test fun aFailedFirstTmdbReadIsTriedAgainAtTheEndAndASecondFailureCounts() = runBlocking {
        val remote = FakeRemote().apply {
            tmdbId = 77
            failOn = "tmdbShow:77"
        }
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(show, emptyMap(), false, now)
        assertEquals(listOf("find:tt1", "tmdbShow:77", "lookup", "trakt", "tmdbShow:77"), remote.calls)
        assertEquals(1, outcome.record.failures)
        assertEquals(now.plus(Duration.ofHours(1)).toEpochMilli(), outcome.record.nextRefreshAtMs)
    }

    @Test fun aShowOnlyTmdbKnowsGetsItsDatesWhenTheSecondReadWorks() = runBlocking {
        var reads = 0
        val remote = object : CalendarRemoteSources by FakeRemote() {
            override suspend fun tmdbFind(imdb: String, type: CalendarTitleType) = 77L
            override suspend fun tmdbShow(tmdbId: Long): CalendarShowDates {
                if (reads++ == 0) throw IOException("timeout")
                return CalendarShowDates(
                    status = CalendarShowStatus.RUNNING,
                    entries = listOf(CalendarEntry(season = 1, episode = 2, airDate = "2026-10-20", source = CalendarSource.TMDB))
                )
            }
        }
        val outcome = CalendarTitleFetcher(remote, "AU").refresh(show, emptyMap(), false, now)
        assertEquals(2, reads)
        assertEquals(0, outcome.record.failures)
        assertEquals("2026-10-20", outcome.record.entries.single().airDate)
    }
}
