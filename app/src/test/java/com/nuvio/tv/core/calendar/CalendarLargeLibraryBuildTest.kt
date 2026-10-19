package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first Calendar build for a large library, in virtual time. 1 500 library items, of which the follow rules keep
 * 300. It runs the real follow rules, record reconcile, Trakt calendar merge, TVmaze schedule pass, fetcher and refresh
 * policy through the refresher's queue (most recent first, N at a time). The services are fakes that cost [LATENCY_MS]
 * per request behind the rate limits the real clients use (TVmaze 15 per 10 s, TMDB 20 per second); reading the full
 * schedule costs [SCHEDULE_MS]. One in ten shows TVmaze has carries no IMDb id there, so it cannot be matched by one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarLargeLibraryBuildTest {
    private val now = Instant.parse("2026-10-05T23:00:00Z")

    private enum class Profile { THIS_WEEK, WITHIN_60_DAYS, ANNOUNCED_LATER, BETWEEN_SEASONS, ENDED }

    private class Show(
        val n: Int,
        val film: Boolean,
        val profile: Profile,
        val next: Instant?,
        val nextEpisode: Int,
        val seasonLength: Int,
        val tvMazeKnows: Boolean,
        val tvMazeHasImdb: Boolean,
        val tmdbKnows: Boolean,
        val withTmdbId: Boolean,
        val releaseYear: Int
    ) {
        val imdb = "tt%07d".format(1_000_000 + n)
        val tmdb = 10_000L + n
        val tvMaze = 1 + n
        val season = 3
        val ids get() = CalendarIds(imdb = imdb, tmdb = if (withTmdbId) tmdb else null)
        val status get() = if (profile == Profile.ENDED) CalendarShowStatus.ENDED else CalendarShowStatus.RUNNING
        val tvMazeMatches get() = tvMazeKnows && tvMazeHasImdb
        fun airs(episode: Int): Instant? = next?.plus(Duration.ofDays(7L * (episode - nextEpisode)))
    }

    private fun library(size: Int = 1_500): List<Show> {
        val random = Random(42)
        return List(size) { n ->
            val film = n % 3 == 2
            val r = random.nextDouble()
            val profile = when {
                r < 0.15 -> Profile.THIS_WEEK
                r < 0.25 -> Profile.WITHIN_60_DAYS
                r < 0.35 -> Profile.ANNOUNCED_LATER
                r < 0.65 -> Profile.BETWEEN_SEASONS
                else -> Profile.ENDED
            }
            val days = when (profile) {
                Profile.THIS_WEEK -> random.nextLong(0, 7)
                Profile.WITHIN_60_DAYS -> random.nextLong(8, 60)
                Profile.ANNOUNCED_LATER -> random.nextLong(61, 180)
                else -> null
            }
            Show(
                n = n,
                film = film,
                profile = profile,
                next = days?.let { now.plus(Duration.ofDays(it)).plus(Duration.ofHours(random.nextLong(1, 23))) },
                nextEpisode = if (profile == Profile.THIS_WEEK) random.nextInt(1, 8) else 1,
                seasonLength = 8,
                tvMazeKnows = random.nextDouble() < 0.95,
                tvMazeHasImdb = random.nextDouble() < 0.90,
                tmdbKnows = random.nextDouble() < 0.98,
                withTmdbId = random.nextDouble() < 0.15,
                releaseYear = if (days != null) 2026 else random.nextInt(2012, 2026)
            )
        }
    }

    private class Services(private val scope: TestScope, private val shows: List<Show>) : CalendarRemoteSources {
        private val byImdb = shows.associateBy { it.imdb }
        private val byTmdb = shows.associateBy { it.tmdb }
        private val byTvMaze = shows.associateBy { it.tvMaze }
        private val tvMazeLimiter = CalendarRateLimiter(nowMs = { scope.testScheduler.currentTime })
        private val tmdbLimiter = CalendarRateLimiter(limit = 20, burst = 5, windowMs = 1_000L, nowMs = { scope.testScheduler.currentTime })
        var tvMaze = 0
        var tmdb = 0
        var trakt = 0

        private suspend fun tvMazeRequests(count: Int) {
            repeat(count) {
                tvMazeLimiter.acquire()
                tvMaze++
                delay(LATENCY_MS)
            }
        }

        private suspend fun tmdbRequest() {
            tmdbLimiter.acquire()
            tmdb++
            delay(LATENCY_MS)
        }

        suspend fun traktRequest() {
            trakt++
            delay(LATENCY_MS)
        }

        override suspend fun tvMazeLookup(ids: CalendarIds): TvMazeLookup {
            tvMazeRequests(2)
            val show = ids.imdb?.let(byImdb::get)?.takeIf { it.tvMazeMatches } ?: return TvMazeLookup.NotFound
            return TvMazeLookup.Found(show.tvMaze, CalendarIds(imdb = show.imdb))
        }

        override suspend fun tvMazeShow(id: Int, now: Instant): CalendarShowDates {
            val show = byTvMaze.getValue(id)
            val seasonSoon = show.next?.isBefore(now.plus(Duration.ofDays(60))) == true
            tvMazeRequests(if (seasonSoon) 2 else 1)
            val episodes = if (seasonSoon) (1..show.seasonLength).toList() else listOf(show.nextEpisode - 1, show.nextEpisode)
            return CalendarShowDates(
                status = show.status,
                entries = if (show.next == null) emptyList() else episodes.filter { it >= 1 }.map { exact(show, it, CalendarSource.TVMAZE) },
                name = "Show ${show.n}",
                ids = CalendarIds(imdb = show.imdb)
            )
        }

        // after-only-start
        override suspend fun tvMazeSchedule(wanted: Set<String>): TvMazeSchedule {
            tvMazeLimiter.acquire()
            tvMaze++
            delay(SCHEDULE_MS)
            val listed = shows.filter { show ->
                !show.film && show.tvMazeMatches && show.next != null && "imdb:${show.imdb}" in wanted
            }
            return TvMazeSchedule(listed.map { show ->
                TvMazeScheduledShow(
                    id = show.tvMaze,
                    ids = CalendarIds(imdb = show.imdb),
                    name = "Show ${show.n}",
                    status = show.status,
                    entries = (1..show.seasonLength).filter { !show.airs(it)!!.isBefore(now.minus(Duration.ofDays(3))) }
                        .map { exact(show, it, CalendarSource.TVMAZE) }
                )
            })
        }

        private val now get() = Instant.parse("2026-10-05T23:00:00Z")
        // after-only-end

        override suspend fun traktNextEpisode(ids: CalendarIds): CalendarShowDates? {
            traktRequest()
            val show = ids.imdb?.let(byImdb::get) ?: return null
            return CalendarShowDates(entries = listOfNotNull(show.next?.let { exact(show, show.nextEpisode, CalendarSource.TRAKT_NEXT) }))
        }

        override suspend fun tmdbFind(imdb: String, type: CalendarTitleType): Long? {
            tmdbRequest()
            return byImdb[imdb]?.takeIf { it.tmdbKnows }?.tmdb
        }

        override suspend fun tmdbShow(tmdbId: Long): CalendarShowDates {
            tmdbRequest()
            val show = byTmdb[tmdbId]?.takeIf { it.tmdbKnows } ?: return CalendarShowDates()
            val episodes = if (show.next == null) emptyList() else listOf(show.nextEpisode - 1, show.nextEpisode).filter { it >= 1 }
            return CalendarShowDates(status = show.status, entries = episodes.map { plain(show, it) }, name = "Show ${show.n}")
        }

        override suspend fun tmdbSeason(tmdbId: Long, season: Int): List<CalendarEntry> {
            tmdbRequest()
            val show = byTmdb[tmdbId]?.takeIf { it.tmdbKnows && it.next != null } ?: return emptyList()
            return (1..show.seasonLength).map { episode ->
                plain(show, episode).copy(label = if (episode == show.seasonLength) CalendarEpisodeLabel.SEASON_FINALE else CalendarEpisodeLabel.NONE)
            }
        }

        override suspend fun tmdbFilm(tmdbId: Long, region: String): CalendarShowDates {
            tmdbRequest()
            val film = byTmdb[tmdbId]?.takeIf { it.tmdbKnows } ?: return CalendarShowDates()
            return CalendarShowDates(entries = listOfNotNull(film.next?.let {
                CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = it.atZone(ZoneOffset.UTC).toLocalDate().toString(), source = CalendarSource.TMDB)
            }))
        }

        private fun exact(show: Show, episode: Int, source: CalendarSource) = CalendarEntry(
            season = show.season, episode = episode, airInstantMs = show.airs(episode)!!.toEpochMilli(), source = source
        )

        private fun plain(show: Show, episode: Int) = CalendarEntry(
            season = show.season, episode = episode, airDate = show.airs(episode)!!.atZone(ZoneOffset.UTC).toLocalDate().toString(),
            source = CalendarSource.TMDB
        )
    }

    private data class Build(
        val titles: Int,
        val tvMaze: Int,
        val tmdb: Int,
        val trakt: Int,
        val thisWeekReadyMs: Long,
        val doneMs: Long,
        val upcoming: Int,
        val upcomingOnCalendar: Int,
        val upcomingAtStart: Int,
        val upcomingWithTime: Int,
        val films: Int,
        val queueStartMs: Long,
        val filmsDated: Int,
        val failures: Int,
        val records: List<CalendarTitleRecord>,
        val tvMazeIds: Map<String, CalendarTvMazeId>
    )

    /** One refresh as [CalendarRefresher.runRefresh] runs it, without the storage and the backdrops. */
    private suspend fun TestScope.build(
        shows: List<Show>,
        workers: Int,
        trakt: Boolean,
        at: Instant = now,
        stored: List<CalendarTitleRecord> = emptyList(),
        storedTvMazeIds: Map<String, CalendarTvMazeId> = emptyMap()
    ): Build {
        val services = Services(this, shows)
        val start = testScheduler.currentTime
        val candidates = shows.map { show ->
            CalendarTitleCandidate(
                show.ids, if (show.film) CalendarTitleType.MOVIE else CalendarTitleType.SERIES, CalendarOrigin.LIBRARY,
                lastActivity = now.minus(Duration.ofHours(6L * show.n)), name = "Show ${show.n}", contentId = show.imdb,
                poster = "https://example.org/${show.n}.jpg",
                // after-only-start
                releaseYear = show.releaseYear,
                // after-only-end
            )
        }
        val followed = followCalendarTitles(candidates, CalendarFollowRules(), at).titles
        var records = reconcileCalendarRecords(followed, stored)
        var traktFetchedAt = 0L
        if (trakt) {
            repeat(2) { services.traktRequest() }
            val items = shows.filter { !it.film && it.next != null }.flatMap { show ->
                (1..show.seasonLength).mapNotNull { episode ->
                    val airs = show.airs(episode)!!
                    if (airs.isBefore(at.minus(Duration.ofDays(7))) || !airs.isBefore(at.plus(Duration.ofDays(59)))) return@mapNotNull null
                    TraktCalendarItem(
                        CalendarIds(imdb = show.imdb), "Show ${show.n}", show.status,
                        CalendarEntry(season = show.season, episode = episode, airInstantMs = airs.toEpochMilli(), source = CalendarSource.TRAKT)
                    )
                }
            }
            records = applyTraktCalendar(records, items, at)
            traktFetchedAt = at.toEpochMilli()
        }
        val tvMazeIds = storedTvMazeIds.toMutableMap()
        // after-only-start
        val wanted = records.filter { it.type == CalendarTitleType.SERIES }.flatMapTo(HashSet()) { calendarTvMazeAliases(it.ids) }
        val schedule = services.tvMazeSchedule(wanted)
        records = applyTvMazeSchedule(records, schedule, tvMazeIds, at)
        tvMazeIds += schedule.learnedTvMazeIds(at)
        // after-only-end
        val queueStartMs = testScheduler.currentTime - start
        val byKey = LinkedHashMap(records.associateBy { it.key })
        val shownBefore = records.associateBy({ it.key }, { record -> record.entries })
        val queue = ArrayDeque(
            records.filter { it.isDue(at) }
                .sortedWith(compareByDescending<CalendarTitleRecord> { CalendarOrigin.BELL in it.origins }.thenBy { it.nextRefreshAtMs })
                .map { it.key }
        )
        val doneAt = HashMap<String, Long>()
        val fetcher = CalendarTitleFetcher(services, "US")
        val jobs = List(workers) {
            launch {
                while (true) {
                    val key = queue.removeFirstOrNull() ?: break
                    val record = byKey.getValue(key)
                    val outcome = fetcher.refresh(record, tvMazeIds, record.isCoveredByTrakt(traktFetchedAt, at), at)
                    byKey[key] = outcome.record
                    tvMazeIds += outcome.tvMazeIds
                    doneAt[key] = testScheduler.currentTime - start
                }
            }
        }
        jobs.forEach { it.join() }

        val showsByImdb = shows.associateBy { it.imdb }
        val upcoming = byKey.values.mapNotNull { record ->
            val show = record.ids.imdb?.let(showsByImdb::get) ?: return@mapNotNull null
            val airs = show.next ?: return@mapNotNull null
            if (show.film || airs.isBefore(at) || !airs.isBefore(at.plus(Duration.ofDays(60)))) null else record to show
        }
        val nextEntry = { record: CalendarTitleRecord, show: Show ->
            record.entries.firstOrNull { it.season == show.season && it.episode == show.nextEpisode }
        }
        val thisWeek = upcoming.filter { (_, show) -> show.next!!.isBefore(at.plus(Duration.ofDays(7))) }
        val upcomingFilms = byKey.values.mapNotNull { record ->
            val show = record.ids.imdb?.let(showsByImdb::get) ?: return@mapNotNull null
            if (show.film && show.next?.isAfter(at) == true && show.tmdbKnows) record else null
        }
        return Build(
            titles = records.size,
            tvMaze = services.tvMaze,
            tmdb = services.tmdb,
            trakt = services.trakt,
            thisWeekReadyMs = thisWeek.filter { (record, show) -> nextEntry(record, show) != null }.maxOfOrNull { (record, show) ->
                val shown = shownBefore[record.key].orEmpty().any { it.season == show.season && it.episode == show.nextEpisode }
                if (shown) queueStartMs else doneAt[record.key] ?: queueStartMs
            } ?: 0L,
            doneMs = testScheduler.currentTime - start,
            upcoming = upcoming.size,
            upcomingOnCalendar = upcoming.count { (record, show) -> nextEntry(record, show) != null },
            upcomingAtStart = upcoming.count { (record, show) ->
                shownBefore[record.key].orEmpty().any { it.season == show.season && it.episode == show.nextEpisode }
            },
            upcomingWithTime = upcoming.count { (record, show) -> nextEntry(record, show)?.timeKnown == true },
            films = upcomingFilms.size,
            queueStartMs = queueStartMs,
            filmsDated = upcomingFilms.count { record -> record.entries.any { it.kind == CalendarEntryKind.MOVIE_DIGITAL } },
            failures = byKey.values.count { it.failures > 0 },
            records = byKey.values.toList(),
            tvMazeIds = tvMazeIds
        )
    }

    private fun report(name: String, build: Build) {
        fun clock(ms: Long) = "%d:%02d.%d".format(ms / 60_000, ms / 1_000 % 60, ms / 100 % 10)
        println(
            "CALENDAR-BUILD $name: titles=${build.titles} tvmaze=${build.tvMaze} tmdb=${build.tmdb} trakt=${build.trakt} " +
                "thisWeekReady=${clock(build.thisWeekReadyMs)} done=${clock(build.doneMs)} " +
                "next60days=${build.upcomingOnCalendar}/${build.upcoming} atQueueStart(${clock(build.queueStartMs)})=${build.upcomingAtStart} withTime=${build.upcomingWithTime}/${build.upcoming} " +
                "upcomingFilms=${build.filmsDated}/${build.films} failures=${build.failures}"
        )
    }

    private fun scenario(name: String, workers: Int, trakt: Boolean) = runTest(timeout = 2.minutes) {
        val shows = library()
        val first = build(shows, workers, trakt)
        report("$name first", first)
        assertEquals(CALENDAR_MAX_TITLES, first.titles)
        assertEquals(0, first.failures)
        assertEquals(first.films, first.filmsDated)

        val weekLater = build(shows, workers, trakt, now.plus(Duration.ofDays(7)), first.records, first.tvMazeIds)
        report("$name week-later", weekLater)
        assertEquals(0, weekLater.failures)
        assertTrue(weekLater.doneMs <= first.doneMs)
    }

    @Test fun noTraktFourAtATime() = scenario("no-trakt x4", workers = 4, trakt = false)

    @Test fun noTraktTwoAtATime() = scenario("no-trakt x2", workers = 2, trakt = false)

    @Test fun traktFourAtATime() = scenario("trakt x4", workers = 4, trakt = true)

    private companion object {
        const val LATENCY_MS = 200L
        /** Downloading the 3 MB file and stream-parsing it on a TV box, kept on the slow side. */
        const val SCHEDULE_MS = 5_000L
    }
}
