package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TvMazeScheduleTest {
    private val now = Instant.parse("2026-10-19T12:00:00Z")
    private fun inDays(days: Long) = now.plus(Duration.ofDays(days)).toEpochMilli()

    private fun parse(json: String, wanted: Set<String>) = parseTvMazeSchedule(Buffer().writeUtf8(json), wanted)

    @Test fun onlyWantedShowsAreKeptWithTheirEpisodes() {
        val schedule = parse(SCHEDULE, setOf("imdb:tt0000001", "tvdb:333"))

        assertEquals(listOf(10, 30), schedule.shows.map { it.id })
        val wanted = schedule.shows.first { it.id == 10 }
        assertEquals(CalendarIds(imdb = "tt0000001", tvdb = 111), wanted.ids)
        assertEquals("Wanted Show", wanted.name)
        assertEquals(CalendarShowStatus.RUNNING, wanted.status)
        assertEquals("HBO", wanted.network)
        assertEquals("https://static.tvmaze.com/m.jpg", wanted.poster)
        assertEquals(listOf("S2E1", "S2E2"), wanted.entries.map { it.slot })
        assertEquals(Instant.parse("2026-10-21T01:00:00Z").toEpochMilli(), wanted.entries[0].airInstantMs)
        assertNull("no air time, so only the date", wanted.entries[1].airInstantMs)
        assertEquals("2026-10-27", wanted.entries[1].airDate)

        val byTvdb = schedule.shows.first { it.id == 30 }
        assertEquals(CalendarIds(tvdb = 333), byTvdb.ids)
        assertEquals("Netflix", byTvdb.network)
        assertNull(byTvdb.poster)
        assertEquals("2026-11-01", byTvdb.entries.single().airDate)

        assertSame(wanted, schedule.find(CalendarIds(imdb = "tt0000001")))
        assertSame(byTvdb, schedule.find(CalendarIds(imdb = "tt7777777", tvdb = 333)))
        assertNull(schedule.find(CalendarIds(imdb = "tt0000009")))
    }

    @Test fun nothingWantedReadsTheWholeFileAndKeepsNothing() {
        assertTrue(parse(SCHEDULE, emptySet()).shows.isEmpty())
        assertTrue(parse("[]", setOf("imdb:tt0000001")).shows.isEmpty())
    }

    private fun series(imdb: String?, tvdb: Long? = null, vararg entries: CalendarEntry, tmdb: Long? = null) = CalendarTitleRecord(
        key = "series:${imdb ?: "tmdb:$tmdb"}", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = imdb, tvdb = tvdb, tmdb = tmdb),
        sources = if (entries.isEmpty()) emptyMap() else mapOf(CalendarSource.TVMAZE to entries.toList()),
        nextRefreshAtMs = inDays(5)
    )

    private val schedule = TvMazeSchedule(
        listOf(
            TvMazeScheduledShow(
                id = 10, ids = CalendarIds(imdb = "tt1", tvdb = 111), name = "Listed", status = CalendarShowStatus.RUNNING,
                network = "HBO", poster = "https://static.tvmaze.com/m.jpg",
                entries = listOf(CalendarEntry(season = 2, episode = 1, airInstantMs = inDays(3)))
            )
        )
    )

    @Test fun aListedShowTakesTheFileAndKeepsWhatAiredLately() {
        val aired = CalendarEntry(season = 1, episode = 10, airInstantMs = inDays(-10))
        val moved = CalendarEntry(season = 2, episode = 1, airInstantMs = inDays(9))
        val record = series("tt1", null, aired, moved).let {
            it.copy(sources = it.sources + (CalendarSource.TRAKT_NEXT to listOf(moved.copy(source = CalendarSource.TRAKT_NEXT))))
        }
        val updated = applyTvMazeSchedule(listOf(record), schedule, emptyMap(), now).single()

        assertEquals(10, updated.tvmazeId)
        assertEquals(111L, updated.ids.tvdb)
        assertEquals("Listed", updated.name)
        assertEquals("HBO", updated.network)
        assertEquals(CalendarShowStatus.RUNNING, updated.status)
        assertEquals(now.toEpochMilli(), updated.scheduleCheckedAtMs)
        assertEquals(listOf(inDays(-10), inDays(3)), updated.entries.mapNotNull { it.airInstantMs }.sorted())
        assertTrue(CalendarSource.TRAKT_NEXT !in updated.sources)
        assertEquals("a release within 60 days without finale marks is due now", now.toEpochMilli(), updated.nextRefreshAtMs)
    }

    @Test fun aShowTheFileDoesNotListIsMarkedCheckedAndLosesItsFutureTvMazeDates() {
        val aired = CalendarEntry(season = 4, episode = 8, airInstantMs = inDays(-3))
        val stale = CalendarEntry(season = 5, episode = 1, airInstantMs = inDays(20))
        val record = series("tt2", null, aired, stale).copy(tvmazeId = 77, status = CalendarShowStatus.ENDED)
        val updated = applyTvMazeSchedule(listOf(record), schedule, emptyMap(), now).single()

        assertEquals(now.toEpochMilli(), updated.scheduleCheckedAtMs)
        assertEquals(listOf(aired), updated.entries)
        assertEquals(CalendarShowStatus.ENDED, updated.status)
        assertEquals(inDays(5), updated.nextRefreshAtMs)
    }

    @Test fun anUnlistedShowWhoseTvMazeIdIsNotKnownIsDueForItsOneLookup() {
        val updated = applyTvMazeSchedule(listOf(series("tt4")), schedule, emptyMap(), now).single()
        assertEquals(now.toEpochMilli(), updated.scheduleCheckedAtMs)
        assertEquals(now.toEpochMilli(), updated.nextRefreshAtMs)
        val known = mapOf("imdb:tt4" to CalendarTvMazeId(44, 1L))
        assertEquals(inDays(5), applyTvMazeSchedule(listOf(series("tt4")), schedule, known, now).single().nextRefreshAtMs)
    }

    @Test fun showsTvMazeDoesNotHaveOrCannotMatchAreLeftToThePerTitleRefresh() {
        val unknown = series("tt3")
        val tmdbOnly = series(null, tmdb = 42)
        val film = CalendarTitleRecord("movie:imdb:tt1", CalendarTitleType.MOVIE, CalendarIds(imdb = "tt1"))
        val known = mapOf("imdb:tt3" to CalendarTvMazeId(null, now.toEpochMilli()))
        val records = listOf(unknown, tmdbOnly, film)

        assertEquals(records, applyTvMazeSchedule(records, schedule, known, now))
        val learned = schedule.learnedTvMazeIds(now)
        assertEquals(mapOf("imdb:tt1" to CalendarTvMazeId(10, now.toEpochMilli()), "tvdb:111" to CalendarTvMazeId(10, now.toEpochMilli())), learned)
    }

    @Test fun finalesAreWantedOncePerDayWhileAReleaseIsNear() {
        val near = series("tt1", null, CalendarEntry(season = 2, episode = 1, airInstantMs = inDays(10)))
        assertTrue(near.wantsFinales(now))
        val marked = near.copy(finalesCheckedAtMs = now.minus(Duration.ofHours(2)).toEpochMilli())
        assertTrue(!marked.wantsFinales(now))
        assertTrue(marked.wantsFinales(now.plus(Duration.ofHours(23))))
        val far = series("tt1", null, CalendarEntry(season = 2, episode = 1, airInstantMs = inDays(61)))
        assertTrue(!far.wantsFinales(now))
    }

    private companion object {
        val SCHEDULE = """
            [
              {"id":1,"url":"x","name":"Pilot","season":2,"number":1,"type":"regular","airdate":"2026-10-20","airtime":"21:00",
               "airstamp":"2026-10-21T01:00:00+00:00","runtime":60,"rating":{"average":null},"image":null,"summary":"<p>x</p>",
               "_links":{"self":{"href":"x"}},
               "_embedded":{"show":{"id":10,"name":"Wanted Show","type":"Scripted","genres":["Drama"],"status":"Running",
                 "schedule":{"time":"21:00","days":["Monday"]},"network":{"id":1,"name":"HBO","country":{"code":"US"}},
                 "webChannel":null,"externals":{"tvrage":null,"thetvdb":111,"imdb":"tt0000001"},
                 "image":{"medium":"http://static.tvmaze.com/m.jpg","original":"http://static.tvmaze.com/o.jpg"}}}},
              {"id":2,"name":"Second","season":2,"number":2,"type":"regular","airdate":"2026-10-27","airtime":"",
               "airstamp":"2026-10-27T12:00:00+00:00",
               "_embedded":{"show":{"id":10,"name":"Wanted Show","status":"Running","network":{"name":"HBO"},
                 "externals":{"thetvdb":111,"imdb":"tt0000001"},"image":{"medium":"http://static.tvmaze.com/m.jpg"}}}},
              {"id":3,"name":"Other","season":1,"number":1,"airdate":"2026-10-20","airtime":"20:00","airstamp":"2026-10-21T00:00:00+00:00",
               "_embedded":{"show":{"id":20,"name":"Unwanted","status":"Running","externals":{"imdb":"tt0000009","thetvdb":null}}}},
              {"id":4,"name":null,"season":1,"number":3,"type":"regular","airdate":"2026-11-01","airtime":null,"airstamp":null,
               "_embedded":{"show":{"id":30,"name":"Web Only","status":"Running","network":null,"webChannel":{"id":1,"name":"Netflix"},
                 "externals":{"imdb":null,"thetvdb":333},"image":null}}}
            ]
        """.trimIndent()
    }
}
