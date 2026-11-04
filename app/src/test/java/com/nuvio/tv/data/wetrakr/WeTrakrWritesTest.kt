package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTrakrWritesTest {
    private val movie = TrackingMediaReference(TrackingMediaKind.MOVIE, "Dune", 2021, TrackingExternalIds(imdb = "tt15239678"))
    private val episode = TrackingMediaReference(
        TrackingMediaKind.SHOW, "Severance", 2022, TrackingExternalIds(imdb = "tt11280740", tmdb = 95396),
        episode = TrackingEpisode(season = 2, number = 3)
    )

    @Test
    fun `episodes are sent under their show with the season and number`() {
        val body = episode.toWeTrakrTarget()!!.scrobbleBody(41.256, "1.0")
        assertEquals("tt11280740", body["show"]!!.jsonObject["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content)
        assertEquals(2, body["episode"]!!.jsonObject["season"]!!.jsonPrimitive.int)
        assertEquals(3, body["episode"]!!.jsonObject["number"]!!.jsonPrimitive.int)
        assertEquals("41.25", body["progress"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anime known only by Kitsu is sent the anime way, without a season`() {
        val anime = TrackingMediaReference(
            TrackingMediaKind.ANIME, "Frieren", ids = TrackingExternalIds(kitsu = 46474),
            episode = TrackingEpisode(season = 1, number = 7)
        )
        val body = anime.toWeTrakrTarget()!!.scrobbleBody(10.0, "1.0")
        assertEquals(46474L, body["show"]!!.jsonObject["ids"]!!.jsonObject["kitsu"]!!.jsonPrimitive.content.toLong())
        assertNull(body["episode"]!!.jsonObject["season"])
    }

    @Test
    fun `titles without any usable id are not sent`() {
        assertNull(TrackingMediaReference(TrackingMediaKind.MOVIE, "Home video").toWeTrakrTarget())
    }

    @Test
    fun `watched marks go in one nested body and never add a rewatch`() {
        val targets = listOf(movie.toWeTrakrTarget()!!, episode.toWeTrakrTarget()!!)
        val body = trackingBody(targets, "watched", { 1_793_750_400_000L }, allowRewatch = false)
        assertEquals("false", body["allow_rewatch"]!!.jsonPrimitive.content)
        assertEquals("watched", body["movies"]!!.jsonArray.single().jsonObject["status"]!!.jsonPrimitive.content)
        val show = body["shows"]!!.jsonArray.single().jsonObject
        assertEquals("watched", show["status"]!!.jsonPrimitive.content)
        val season = show["seasons"]!!.jsonArray.single().jsonObject
        assertEquals(2, season["number"]!!.jsonPrimitive.int)
        assertEquals(3, season["episodes"]!!.jsonArray.single().jsonObject["number"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a play that stops in the first minute is not reported`() = runTest {
        val (service, harness) = scrobbler(TestScope(testScheduler))
        service.scrobble(harness.store.scope(), TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 0.5))
        advanceTimeBy(30_000)
        service.scrobble(harness.store.scope(), TrackingScrobbleAction.STOP, TrackingScrobbleEvent(movie, 1.0))
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(harness.engine.requests.none { it.path.startsWith("/scrobble") })
    }

    @Test
    fun `a play past the first minute is reported, pauses and resumes follow at once`() = runTest {
        val scope = TestScope(testScheduler)
        val (service, harness) = scrobbler(scope)
        service.scrobble(harness.store.scope(), TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 0.5))
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(listOf("/scrobble/start"), harness.engine.requests.map { it.path })
        assertEquals("90", harness.engine.requests.single().query["min_progress"])
        service.scrobble(harness.store.scope(), TrackingScrobbleAction.PAUSE, TrackingScrobbleEvent(movie, 12.0))
        service.scrobble(harness.store.scope(), TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 12.0))
        assertEquals(listOf("/scrobble/start", "/scrobble/pause", "/scrobble/start"), harness.engine.requests.map { it.path })
    }

    @Test
    fun `finishing a title is reported even when the start was never sent`() = runTest {
        val (service, harness) = scrobbler(TestScope(testScheduler))
        service.scrobble(harness.store.scope(), TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 88.0))
        service.scrobble(harness.store.scope(), TrackingScrobbleAction.STOP, TrackingScrobbleEvent(movie, 95.0))
        assertEquals(listOf("/scrobble/stop"), harness.engine.requests.map { it.path })
        val body = Json.parseToJsonElement(harness.engine.requests.single().body!!).jsonObject
        assertEquals("tt15239678", body["movie"]!!.jsonObject["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content)
    }

    @Test
    fun `marking watched writes once and updates the local copy`() = runTest {
        val harness = WeTrakrHarness()
        harness.signIn()
        harness.engine.on(WeTrakrHttpMethod.POST, "/sync/tracking", ok("""{"added":{"movies":1},"not_found":{"movies":[]}}"""))
        val repository = harness.repository(TestScope(testScheduler))
        repository.ensureLoaded()
        val result = WeTrakrHistoryService(harness.api, repository) { harness.now }
            .add(harness.store.scope(), listOf(TrackingHistoryItem(movie, 1_793_700_000_000L)))
        assertTrue(result.isComplete)
        assertEquals(1, harness.engine.calls("/sync/tracking").size)
        assertTrue(repository.currentProjection().isWatched("tt15239678", null, null))
    }

    @Test
    fun `unmarking removes every play of the title`() = runTest {
        val harness = WeTrakrHarness()
        harness.signIn()
        harness.engine.on(WeTrakrHttpMethod.POST, "/sync/tracking/remove/all", ok("{}"))
        val repository = harness.repository(TestScope(testScheduler))
        repository.ensureLoaded()
        repository.mutate(harness.store.scope()) {
            it.copy(watchedMovies = listOf(WeTrakrWatchedMovie(WeTrakrIds(imdb = "tt15239678"), "2026-11-01T00:00:00.000Z", 3))) to Unit
        }
        WeTrakrHistoryService(harness.api, repository) { harness.now }.remove(harness.store.scope(), listOf(movie))
        assertFalse(repository.currentProjection().isWatched("tt15239678", null, null))
    }

    private fun scrobbler(scope: TestScope): Pair<WeTrakrScrobbleService, WeTrakrHarness> {
        val harness = WeTrakrHarness()
        harness.signIn()
        harness.engine.on(WeTrakrHttpMethod.POST, "/scrobble/start", ok("""{"action":"start"}""", status = 201))
        harness.engine.on(WeTrakrHttpMethod.POST, "/scrobble/pause", ok("""{"action":"pause"}"""))
        harness.engine.on(WeTrakrHttpMethod.POST, "/scrobble/stop", ok("""{"action":"scrobble"}""", status = 201))
        val repository = harness.repository(scope)
        return WeTrakrScrobbleService(harness.api, repository, "1.0", scope, now = { harness.now }) to harness
    }
}
