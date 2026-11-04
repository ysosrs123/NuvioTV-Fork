package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTrakrSyncRepositoryTest {
    private val movie = TrackingMediaReference(TrackingMediaKind.MOVIE, "Dune", 2021, TrackingExternalIds(imdb = "tt15239678"))
    private val activities = WeTrakrActivities(
        mapOf("all" to "2026-11-03T00:00:00.000Z", "journal_visible_until" to "2026-11-03T10:00:00.000Z")
    )

    private class SlowRemote(private val activities: WeTrakrActivities) : WeTrakrSyncRemote {
        val release = CompletableDeferred<Unit>()
        var calls = 0

        override suspend fun activities(): WeTrakrActivities {
            calls += 1
            if (calls == 1) release.await()
            return activities
        }
        override suspend fun watchedMovies() = emptyList<WeTrakrWatchedMovie>()
        override suspend fun watchedEpisodes() = emptyList<WeTrakrWatchedEpisode>()
        override suspend fun list(list: WeTrakrList, target: String, since: String?) = emptyList<WeTrakrListEntry>()
        override suspend fun playback() = emptyList<WeTrakrPlayback>()
        override suspend fun journal(since: String) = WeTrakrJournalPage(emptyList(), null)
        override suspend fun showIds(showId: Long): WeTrakrIds? = null
    }

    private fun WeTrakrHarness.seed() {
        storage.values[1] = Json.encodeToString(
            WeTrakrSnapshot.serializer(),
            WeTrakrSnapshot(42, activities = activities.stamps, journalMark = "2026-11-03T09:00:00.000Z", isInitialized = true)
        )
    }

    @Test
    fun `a long sync does not hold up a watched mark, and the mark survives the sync`() = runTest {
        val harness = WeTrakrHarness().apply { signIn(); seed() }
        val remote = SlowRemote(activities)
        val repository = harness.repository(TestScope(testScheduler), remote)
        val sync = launch { repository.refresh(TrackingRefreshIntent.USER_INITIATED) }
        runCurrent()
        assertEquals(1, remote.calls)

        val target = requireNotNull(movie.toWeTrakrTarget())
        val mark = async { repository.mutate(harness.store.scope()) { it.withLocalPlay(target, harness.now) to Unit } }
        runCurrent()
        assertTrue(mark.isCompleted)

        remote.release.complete(Unit)
        sync.join()
        assertEquals(2, remote.calls)
        assertTrue(repository.currentProjection().isWatched("tt15239678", null, null))
        assertFalse(repository.state.value.isLoading)
    }

    @Test
    fun `a stop is saved a few seconds later, a pause is not saved at all`() = runTest {
        val harness = WeTrakrHarness().apply { signIn(); seed() }
        harness.engine.on(WeTrakrHttpMethod.POST, "/scrobble/start", ok("""{"action":"start"}""", status = 201))
        harness.engine.on(WeTrakrHttpMethod.POST, "/scrobble/pause", ok("""{"action":"pause"}"""))
        harness.engine.on(WeTrakrHttpMethod.POST, "/scrobble/stop", ok("""{"action":"pause"}"""))
        val scope = TestScope(testScheduler)
        val repository = harness.repository(scope, SlowRemote(activities).apply { release.complete(Unit) })
        val service = WeTrakrScrobbleService(harness.api, repository, "1.0", scope, now = { harness.now })
        val stored = harness.storage.values[1]

        service.scrobble(harness.store.scope(), TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 5.0))
        advanceTimeBy(WeTrakrScrobbleService.SHORT_PLAY_MS + 1)
        runCurrent()
        service.scrobble(harness.store.scope(), TrackingScrobbleAction.PAUSE, TrackingScrobbleEvent(movie, 20.0))
        assertTrue(repository.currentSnapshot()!!.playback.isEmpty())

        service.scrobble(harness.store.scope(), TrackingScrobbleAction.STOP, TrackingScrobbleEvent(movie, 40.0))
        assertEquals(40f, repository.currentSnapshot()!!.playback.single().progress, 0.01f)
        assertEquals(stored, harness.storage.values[1])
        advanceTimeBy(WeTrakrSyncRepository.SAVE_DELAY_MS + 1)
        runCurrent()
        assertTrue(harness.storage.values[1] != stored)
    }

    @Test
    fun `the delayed start sends where playback has got to, not where it began`() = runTest {
        val harness = WeTrakrHarness().apply { signIn(); seed() }
        harness.engine.on(WeTrakrHttpMethod.POST, "/scrobble/start", ok("""{"action":"start"}""", status = 201))
        val scope = TestScope(testScheduler)
        val repository = harness.repository(scope, SlowRemote(activities).apply { release.complete(Unit) })
        val service = WeTrakrScrobbleService(harness.api, repository, "1.0", scope, now = { harness.now })

        service.scrobble(
            harness.store.scope(), TrackingScrobbleAction.START,
            TrackingScrobbleEvent(movie, 10.0, durationSeconds = 6_000L)
        )
        advanceTimeBy(WeTrakrScrobbleService.SHORT_PLAY_MS + 1)
        runCurrent()
        val body = Json.parseToJsonElement(harness.engine.calls("/scrobble/start").single().body!!).jsonObject
        assertEquals(11.0, body["progress"]!!.jsonPrimitive.double, 0.001)
    }

    @Test
    fun `extras and automatic syncs stop when only the write reserve is left`() = runTest {
        val harness = WeTrakrHarness().apply { signIn() }
        val scope = harness.store.scope()
        assertTrue(harness.api.hasQuotaToSpare(scope))
        harness.engine.on(
            WeTrakrHttpMethod.GET, "/account/settings",
            ok("""{"user":{"ids":{"wetrakr":42},"username":"alex"}}""", "X-Quota-Limit" to "300", "X-Quota-Remaining" to "31")
        )
        harness.api.get("/account/settings", scope = scope)
        assertTrue(harness.api.hasQuotaToSpare(scope))
        harness.engine.on(
            WeTrakrHttpMethod.GET, "/account/settings",
            ok("""{"user":{"ids":{"wetrakr":42},"username":"alex"}}""", "X-Quota-Limit" to "300", "X-Quota-Remaining" to "30")
        )
        harness.api.get("/account/settings", scope = scope)
        assertFalse(harness.api.hasQuotaToSpare(scope))
        harness.now = nextUtcMidnight(harness.now) + 1
        assertTrue(harness.api.hasQuotaToSpare(scope))
    }

    @Test
    fun `a changed account is an ordinary error, not a cancellation`() {
        val harness = WeTrakrHarness().apply { signIn() }
        val old = harness.store.scope()
        harness.store.selectProfile(2)
        val error = runCatching { harness.store.checkScope(old) }.exceptionOrNull()
        assertTrue(error is WeTrakrAccountChangedException)
        assertFalse(error is CancellationException)
    }

    @Test
    fun `the related cache keeps only the most recent titles`() {
        val cache = WeTrakrLruCache<String, Int>(2)
        cache["a"] = 1
        cache["b"] = 2
        assertEquals(1, cache["a"])
        cache["c"] = 3
        assertNull(cache["b"])
        assertEquals(1, cache["a"])
        assertEquals(2, cache.size)
    }
}
