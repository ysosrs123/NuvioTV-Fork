package com.nuvio.tv.core.calendar

import com.nuvio.tv.data.remote.api.TvMazeApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class TvMazeCalendarClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: TvMazeCalendarClient
    private var fakeNow = 0L
    private val slept = mutableListOf<Long>()
    private val now = Instant.parse("2026-10-06T05:00:00Z")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TvMazeApi::class.java)
        val limiter = CalendarRateLimiter(nowMs = { fakeNow }, sleep = { fakeNow += it; slept += it })
        client = TvMazeCalendarClient(api, limiter)
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    @Test fun lookupTriesImdbThenTvdb() = runBlocking {
        server.enqueue(json("null", 404))
        server.enqueue(json(SHOW_FAR))
        val result = client.lookup(CalendarIds(imdb = "tt0096697", tvdb = 71663))
        assertEquals(TvMazeLookup.Found(83, CalendarIds(imdb = "tt0096697", tvdb = 71663)), result)
        assertEquals("/lookup/shows?imdb=tt0096697", server.takeRequest().path)
        assertEquals("/lookup/shows?thetvdb=71663", server.takeRequest().path)
    }

    @Test fun lookupWithNoMatchIsNotFound() = runBlocking {
        server.enqueue(json("null", 404))
        assertEquals(TvMazeLookup.NotFound, client.lookup(CalendarIds(imdb = "tt9999999")))
        assertEquals(TvMazeLookup.NotFound, client.lookup(CalendarIds(tmdb = 5)))
        assertEquals(1, server.requestCount)
    }

    @Test fun aNextEpisodeWithinSixtyDaysReadsItsWholeSeason() = runBlocking {
        server.enqueue(json(SHOW_SOON))
        server.enqueue(json(SEASON_EPISODES))
        val dates = client.show(83, now)

        val showRequest = server.takeRequest().path!!
        assertTrue(showRequest, showRequest.startsWith("/shows/83?"))
        assertTrue(showRequest, listOf("nextepisode", "previousepisode", "seasons").all { it in showRequest })
        assertEquals("/seasons/201386/episodes", server.takeRequest().path)

        assertEquals(CalendarShowStatus.RUNNING, dates.status)
        assertEquals("FOX", dates.network)
        assertEquals("The Simpsons", dates.name)
        assertEquals(CalendarIds(imdb = "tt0096697", tvdb = 71663), dates.ids)
        assertEquals(listOf("S38E2", "S38E3", "S38E1", "S38E4", "S38E5"), dates.entries.map { it.slot })
        val e3 = dates.entries.first { it.episode == 3 }
        assertEquals(Instant.parse("2026-10-19T00:00:00Z").toEpochMilli(), e3.airInstantMs)
        assertEquals("Treehouse of Horror Presents: The Revengening", e3.title)
        val noTime = dates.entries.first { it.episode == 5 }
        assertNull(noTime.airInstantMs)
        assertEquals("2026-11-01", noTime.airDate)
        assertEquals(CalendarEpisodeLabel.NONE, e3.label)
        assertEquals(CalendarEpisodeLabel.SEASON_PREMIERE, dates.entries.first { it.episode == 1 }.label)
    }

    @Test fun aSeasonIdMissingFromTheEmbedIsAskedFor() = runBlocking {
        server.enqueue(json(SHOW_SOON.replace("\"seasons\"", "\"unused\"")))
        server.enqueue(json("""[{"id":201385,"number":37},{"id":201386,"number":38}]"""))
        server.enqueue(json(SEASON_EPISODES))
        client.show(83, now)
        server.takeRequest()
        assertEquals("/shows/83/seasons", server.takeRequest().path)
        assertEquals("/seasons/201386/episodes", server.takeRequest().path)
    }

    @Test fun theShowImageComesWithTheShowCall() = runBlocking {
        server.enqueue(json(SHOW_FAR.replace("\"externals\"", """"image":{"medium":"https://static.tvmaze.com/m.jpg","original":"https://static.tvmaze.com/o.jpg"},"externals"""")))
        val dates = client.show(83, now)
        assertEquals(1, server.requestCount)
        assertEquals("https://static.tvmaze.com/m.jpg", dates.poster)
    }

    @Test fun aShowWithoutAnImageHasNoPoster() = runBlocking {
        server.enqueue(json(SHOW_FAR))
        assertNull(client.show(83, now).poster)
    }

    @Test fun aNextEpisodeFarAwayNeedsNoSeasonCall() = runBlocking {
        server.enqueue(json(SHOW_FAR))
        val dates = client.show(83, now)
        assertEquals(1, server.requestCount)
        assertEquals(listOf("S38E5", "S39E1"), dates.entries.map { it.slot })
    }

    @Test fun aRateLimitWaitsForRetryAfterAndTriesAgain() = runBlocking {
        server.enqueue(json("", 429).addHeader("Retry-After", "3"))
        server.enqueue(json(SHOW_FAR))
        val result = client.lookup(CalendarIds(imdb = "tt0096697"))
        assertEquals(83, (result as TvMazeLookup.Found).id)
        assertEquals(2, server.requestCount)
        assertTrue("slept $slept", slept.sum() >= 3_000L)
    }

    @Test fun aServerErrorIsAFailureToRetryLater() {
        server.enqueue(json("", 503))
        assertThrows(CalendarFetchException::class.java) { runBlocking { client.show(83, now) } }
    }

    @Test fun anEndedShowAndOtherStatuses() {
        assertEquals(CalendarShowStatus.ENDED, tvMazeStatus("Ended"))
        assertEquals(CalendarShowStatus.IN_PRODUCTION, tvMazeStatus("In Development"))
        assertEquals(CalendarShowStatus.UNKNOWN, tvMazeStatus("To Be Determined"))
    }

    private companion object {
        val SHOW_SOON = """
            {"id":83,"url":"https://www.tvmaze.com/shows/83/the-simpsons","name":"The Simpsons","type":"Animation",
             "status":"Running","schedule":{"time":"20:00","days":["Sunday"]},
             "network":{"id":4,"name":"FOX","country":{"name":"United States","code":"US","timezone":"America/New_York"}},
             "webChannel":null,"externals":{"tvrage":6190,"thetvdb":71663,"imdb":"tt0096697"},
             "_embedded":{
               "nextepisode":{"id":3773294,"name":"Treehouse of Horror Presents: The Revengening","season":38,"number":3,
                 "type":"regular","airdate":"2026-10-18","airtime":"20:00","airstamp":"2026-10-19T00:00:00+00:00","runtime":30},
               "previousepisode":{"id":3765220,"name":"Diary of a Chimpy Kid","season":38,"number":2,"type":"regular",
                 "airdate":"2026-10-04","airtime":"20:00","airstamp":"2026-10-05T00:00:00+00:00","runtime":30},
               "seasons":[{"id":201385,"number":37,"premiereDate":"2025-09-28","endDate":"2026-05-17"},
                          {"id":201386,"number":38,"premiereDate":"2026-09-27","endDate":"2026-11-01"}]}}
        """.trimIndent()

        val SHOW_FAR = """
            {"id":83,"name":"The Simpsons","status":"Running","network":null,"webChannel":{"id":1,"name":"Netflix"},
             "externals":{"tvrage":6190,"thetvdb":71663,"imdb":"tt0096697"},
             "_embedded":{
               "nextepisode":{"id":9,"name":"Premiere","season":39,"number":1,"type":"regular","airdate":"2027-09-26",
                 "airtime":"","airstamp":"2027-09-26T12:00:00+00:00","runtime":30},
               "previousepisode":{"id":8,"name":"Finale","season":38,"number":5,"type":"regular","airdate":"2026-10-04",
                 "airtime":"20:00","airstamp":"2026-10-05T00:00:00+00:00","runtime":30}}}
        """.trimIndent()

        val SEASON_EPISODES = """
            [{"id":3673808,"name":"The Children's Book Job","season":38,"number":1,"type":"regular","airdate":"2026-09-27",
              "airtime":"20:00","airstamp":"2026-09-28T00:00:00+00:00","runtime":30},
             {"id":3765220,"name":"Diary of a Chimpy Kid","season":38,"number":2,"type":"regular","airdate":"2026-10-04",
              "airtime":"20:00","airstamp":"2026-10-05T00:00:00+00:00","runtime":30},
             {"id":3773294,"name":"Treehouse of Horror Presents: The Revengening","season":38,"number":3,"type":"regular",
              "airdate":"2026-10-18","airtime":"20:00","airstamp":"2026-10-19T00:00:00+00:00","runtime":30},
             {"id":3773295,"name":"Behind the Laughter","season":38,"number":null,"type":"insignificant_special",
              "airdate":"2026-10-20","airtime":"20:00","airstamp":"2026-10-21T00:00:00+00:00","runtime":30},
             {"id":3773296,"name":"TBA","season":38,"number":4,"type":"regular","airdate":"2026-10-25","airtime":"20:00",
              "airstamp":"2026-10-26T00:00:00+00:00","runtime":30},
             {"id":3773297,"name":"TBA","season":38,"number":5,"type":"regular","airdate":"2026-11-01","airtime":"",
              "airstamp":"2026-11-01T12:00:00+00:00","runtime":30}]
        """.trimIndent()
    }
}
