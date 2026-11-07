package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloppyScrobbleBodyTest {
    private val movie = TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE,
        title = "The Matrix",
        ids = TrackingExternalIds(imdb = "tt0133093", tmdb = 603L)
    )
    private val episode = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "Severance",
        ids = TrackingExternalIds(imdb = "tt11280740", tvdb = "371980"),
        episode = TrackingEpisode(season = 2, number = 3, title = "Who Is Alive?")
    )

    @Test
    fun `start with a known length sends seconds as well as the percentage`() {
        val body = floppyScrobbleBody(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 50.0, durationSeconds = 8_160L))!!
        assertEquals(
            json(
                """{"action":"start","media_type":"movie","ids":{"imdb":"tt0133093","tmdb":"603"},"title":"The Matrix",
                "progress_percent":50.0,"duration_seconds":8160,"position_seconds":4080}"""
            ),
            body
        )
    }

    @Test
    fun `stop is completed from the 80 percent mark and a skim is not sent`() {
        val finished = floppyScrobbleBody(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(movie, 80.0))!!
        assertEquals("true", finished["completed"].toString())
        assertNull(finished["position_seconds"])
        val stopped = floppyScrobbleBody(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(movie, 42.123))!!
        assertEquals("false", stopped["completed"].toString())
        assertEquals("42.12", stopped["progress_percent"].toString())
        assertNull(floppyScrobbleBody(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 42.0))!!["client_event_id"])
        assertNull(floppyScrobbleBody(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(movie, 0.5)))
        assertNull(floppyScrobbleBody(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, Double.NaN)))
        assertNull(floppyScrobbleBody(TrackingScrobbleAction.PAUSE, TrackingScrobbleEvent(movie, 30.0))!!["completed"])
    }

    @Test
    fun `episode carries the show and its coordinates`() {
        val body = floppyScrobbleBody(TrackingScrobbleAction.PAUSE, TrackingScrobbleEvent(episode, 10.0, durationSeconds = 3_000L))!!
        assertEquals(
            json(
                """{"action":"pause","media_type":"episode","ids":{"imdb":"tt11280740","tvdb":"371980"},
                "series_title":"Severance","title":"Who Is Alive?","season_number":2,"episode_number":3,
                "progress_percent":10.0,"duration_seconds":3000,"position_seconds":300}"""
            ),
            body
        )
    }

    @Test
    fun `titles Floppy cannot match by id are left out`() {
        val noIds = movie.copy(ids = TrackingExternalIds(imdb = "not-imdb", mal = 5L))
        assertNull(floppyScrobbleBody(TrackingScrobbleAction.START, TrackingScrobbleEvent(noIds, 10.0)))
        val noEpisode = episode.copy(episode = null)
        assertNull(floppyScrobbleBody(TrackingScrobbleAction.START, TrackingScrobbleEvent(noEpisode, 10.0)))
        val tvdbOrder = episode.copy(episode = episode.episode!!.copy(usesTvdbSeasonMapping = true))
        assertNull(floppyScrobbleBody(TrackingScrobbleAction.START, TrackingScrobbleEvent(tvdbOrder, 10.0)))
        val noSeason = episode.copy(episode = episode.episode!!.copy(season = null))
        assertNull(floppyScrobbleBody(TrackingScrobbleAction.START, TrackingScrobbleEvent(noSeason, 10.0)))
    }

    @Test
    fun `mark as watched is a manual stop with the time it was marked`() {
        val body = floppyManualMarkBody(TrackingHistoryItem(movie, watchedAtEpochMs = 1_762_150_000_000L))!!
        assertEquals("stop", body["action"].toString().trim('"'))
        assertEquals("true", body["manual"].toString())
        assertEquals("true", body["completed"].toString())
        assertEquals("\"2025-11-03T06:06:40Z\"", body["played_at"].toString())
        assertNull(body["position_seconds"])
        assertNull(body["progress_percent"])
    }

    @Test
    fun `unwatch names the title the same way a mark does`() {
        val movieBody = floppyUnwatchBody(movie)!!
        assertEquals("unwatch", movieBody["action"].toString().trim('"'))
        assertEquals("{\"imdb\":\"tt0133093\",\"tmdb\":\"603\"}", movieBody["ids"].toString())
        val episodeBody = floppyUnwatchBody(episode)!!
        assertEquals("episode", episodeBody["media_type"].toString().trim('"'))
        assertEquals("2", episodeBody["season_number"].toString())
        assertEquals("3", episodeBody["episode_number"].toString())
        assertNull(floppyUnwatchBody(episode.copy(episode = null)))
    }

    @Test
    fun `two episodes marked together get different event ids`() {
        val first = floppyManualMarkBody(TrackingHistoryItem(episode, watchedAtEpochMs = 5L))!!
        val second = floppyManualMarkBody(
            TrackingHistoryItem(episode.copy(episode = episode.episode!!.copy(number = 4)), watchedAtEpochMs = 5L)
        )!!
        assertNotEquals(first["client_event_id"], second["client_event_id"])
        assertEquals("\"arc-mark-${movie.stableKey}\"", floppyManualMarkBody(TrackingHistoryItem(movie))!!["client_event_id"].toString())
    }

    @Test
    fun `every stop carries its own retry id`() {
        val ids = ArrayDeque(listOf("one", "two"))
        val first = floppyScrobbleBody(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(movie, 90.0)) { ids.removeFirst() }!!
        val second = floppyScrobbleBody(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(movie, 90.0)) { ids.removeFirst() }!!
        assertEquals("\"arc-stop-one\"", first["client_event_id"].toString())
        assertEquals("\"arc-stop-two\"", second["client_event_id"].toString())
    }

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
