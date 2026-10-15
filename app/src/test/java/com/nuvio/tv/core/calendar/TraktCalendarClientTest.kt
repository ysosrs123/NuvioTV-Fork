package com.nuvio.tv.core.calendar

import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.repository.TraktAuthService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class TraktCalendarClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: TraktCalendarClient
    private val today = LocalDate.of(2026, 10, 6)
    private val now = Instant.parse("2026-10-05T23:00:00Z")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
            .build()
            .create(TraktApi::class.java)
        val auth = mockk<TraktAuthService>()
        coEvery { auth.executeAuthorizedRequest<Any>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<Any>>().invoke("Bearer token")
        }
        coEvery { auth.executePublicRequest<Any>(any()) } coAnswers {
            firstArg<suspend () -> Response<Any>>().invoke()
        }
        client = TraktCalendarClient(api, auth)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun twoCallsCoverTheWeekBeforeAndTwoMonthsAhead() = runBlocking {
        server.enqueue(MockResponse().setBody(CALENDAR))
        server.enqueue(MockResponse().setBody("[]"))
        val items = client.myShows(today)!!

        val first = server.takeRequest()
        assertEquals("/calendars/my/shows/2026-09-29/33?extended=full", first.path)
        assertEquals("Bearer token", first.getHeader("Authorization"))
        assertEquals("/calendars/my/shows/2026-11-01/33?extended=full", server.takeRequest().path)

        assertEquals(2, items.size)
        val finale = items.first()
        assertEquals(CalendarIds(imdb = "tt1000", tmdb = 30, tvdb = 20, trakt = 10), finale.ids)
        assertEquals("Night Shift", finale.showTitle)
        assertEquals(CalendarShowStatus.RUNNING, finale.status)
        assertEquals("S2E6", finale.entry.slot)
        assertEquals("Graveyard", finale.entry.title)
        assertEquals(Instant.parse("2026-10-06T09:30:00Z").toEpochMilli(), finale.entry.airInstantMs)
        assertEquals(CalendarEpisodeLabel.SEASON_FINALE, finale.entry.label)
        assertEquals(CalendarSource.TRAKT, finale.entry.source)
        assertEquals(CalendarEpisodeLabel.NEW_SHOW, items[1].entry.label)
    }

    @Test fun aFailedCallGivesNothingRatherThanHalfACalendar() = runBlocking {
        server.enqueue(MockResponse().setBody(CALENDAR))
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(client.myShows(today))
    }

    @Test fun nextEpisodeByImdbIdAndNoneWhenTraktHasNone() = runBlocking {
        server.enqueue(MockResponse().setBody(NEXT_EPISODE))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(503))
        val next = client.nextEpisode(CalendarIds(imdb = "tt1000"))!!
        assertEquals("/shows/tt1000/next_episode?extended=full", server.takeRequest().path)
        assertEquals(listOf("S3E1"), next.entries.map { it.slot })
        assertEquals(CalendarSource.TRAKT_NEXT, next.entries.single().source)
        assertEquals(CalendarEpisodeLabel.SEASON_PREMIERE, next.entries.single().label)
        assertTrue(client.nextEpisode(CalendarIds(trakt = 10))!!.entries.isEmpty())
        assertEquals("/shows/10/next_episode?extended=full", server.takeRequest().path)
        assertNull(client.nextEpisode(CalendarIds(imdb = "tt2")))
        assertNull(client.nextEpisode(CalendarIds(imdb = "tt3")))
        assertNull(client.nextEpisode(CalendarIds(tmdb = 3)))
    }

    @Test fun traktThatCannotBeReachedGivesNoAnswerSoTmdbIsAsked() = runBlocking {
        val auth = mockk<TraktAuthService>()
        coEvery { auth.executePublicRequest<Any>(any()) } returns null
        val offline = TraktCalendarClient(mockk(), auth)
        assertNull(offline.nextEpisode(CalendarIds(imdb = "tt1000")))
    }

    @Test fun theCalendarReplacesItsOwnDatesAndLeavesTraktNextEpisodeDates() {
        val items = traktCalendarItems(
            Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
                .adapter<List<com.nuvio.tv.data.remote.dto.trakt.TraktCalendarShowDto>>(
                    com.squareup.moshi.Types.newParameterizedType(
                        List::class.java, com.nuvio.tv.data.remote.dto.trakt.TraktCalendarShowDto::class.java
                    )
                ).fromJson(CALENDAR)!!
        )
        val oldCalendarDate = CalendarEntry(season = 2, episode = 5, airInstantMs = Instant.parse("2026-10-01T09:30:00Z").toEpochMilli(), source = CalendarSource.TRAKT)
        val nextEpisode = CalendarEntry(season = 1, episode = 4, airInstantMs = Instant.parse("2026-10-20T09:30:00Z").toEpochMilli(), source = CalendarSource.TRAKT_NEXT)
        val record = CalendarTitleRecord(
            key = "series:tmdb:30", type = CalendarTitleType.SERIES, ids = CalendarIds(tmdb = 30),
            sources = mapOf(CalendarSource.TRAKT to listOf(oldCalendarDate))
        )
        val libraryOnly = CalendarTitleRecord(
            key = "series:imdb:tt5", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt5"),
            origins = setOf(CalendarOrigin.LIBRARY),
            sources = mapOf(CalendarSource.TRAKT to listOf(oldCalendarDate), CalendarSource.TRAKT_NEXT to listOf(nextEpisode))
        )
        val (updated, cleared) = applyTraktCalendar(listOf(record, libraryOnly), items, now)
        assertEquals(listOf("S2E6"), updated.sources[CalendarSource.TRAKT]!!.map { it.slot })
        assertEquals(CalendarIds(imdb = "tt1000", tmdb = 30, tvdb = 20, trakt = 10), updated.ids)
        assertEquals(CalendarShowStatus.RUNNING, updated.status)
        assertNull(cleared.sources[CalendarSource.TRAKT])
        assertEquals(listOf(nextEpisode), cleared.sources[CalendarSource.TRAKT_NEXT])
        assertEquals(listOf("S1E4"), cleared.entries.map { it.slot })
        val other = cleared

        assertTrue(updated.isCoveredByTrakt(now.toEpochMilli(), now))
        assertTrue(!other.isCoveredByTrakt(now.toEpochMilli(), now))
        assertTrue(!updated.isCoveredByTrakt(now.toEpochMilli() - 13 * 3_600_000L, now))

        val candidates = traktCalendarCandidates(items)
        assertEquals(listOf(CalendarOrigin.TRAKT_CALENDAR, CalendarOrigin.TRAKT_CALENDAR), candidates.map { it.origin })
    }

    @Test fun statuses() {
        assertEquals(CalendarShowStatus.RUNNING, traktShowStatus("returning series"))
        assertEquals(CalendarShowStatus.IN_PRODUCTION, traktShowStatus("in production"))
        assertEquals(CalendarShowStatus.CANCELLED, traktShowStatus("canceled"))
        assertEquals(CalendarShowStatus.ENDED, traktShowStatus("ended"))
        assertEquals(CalendarShowStatus.UNKNOWN, traktShowStatus(null))
    }

    private companion object {
        val CALENDAR = """
            [{"first_aired":"2026-10-06T09:30:00.000Z",
              "episode":{"season":2,"number":6,"title":"Graveyard","ids":{"trakt":111,"tvdb":222,"imdb":"tt0001","tmdb":333,"tvrage":null},
                "number_abs":null,"overview":"","rating":0,"votes":0,"comment_count":0,"first_aired":"2026-10-06T09:30:00.000Z",
                "updated_at":"2026-10-01T10:00:00.000Z","available_translations":["en"],"runtime":45,"episode_type":"season_finale"},
              "show":{"title":"Night Shift","year":2025,"ids":{"trakt":10,"slug":"night-shift","tvdb":20,"imdb":"tt1000","tmdb":30,"tvrage":null},
                "status":"returning series","network":"HBO","country":"us"}},
             {"first_aired":"2026-10-07T01:00:00.000Z",
              "episode":{"season":1,"number":1,"title":"Seedlings","ids":{"trakt":112,"tvdb":null,"imdb":null,"tmdb":334},
                "first_aired":"2026-10-07T01:00:00.000Z","episode_type":"series_premiere"},
              "show":{"title":"Glasshouse","year":2026,"ids":{"trakt":11,"slug":"glasshouse","tvdb":null,"imdb":null,"tmdb":31},
                "status":"returning series"}},
             {"first_aired":"2026-10-07T01:00:00.000Z","episode":null,"show":{"title":"Broken","ids":{"trakt":12}}}]
        """.trimIndent()

        val NEXT_EPISODE = """
            {"season":3,"number":1,"title":"First Light","ids":{"trakt":500},"first_aired":"2027-01-10T09:30:00.000Z",
             "episode_type":null,"runtime":50}
        """.trimIndent()
    }
}
