package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingSeekScrobblePolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FloppyTrackingProviderTest {
    private val movie = TrackingMediaReference(TrackingMediaKind.MOVIE, ids = TrackingExternalIds(tmdb = 603L))

    @Test
    fun `nothing is sent until a server is connected`() = runTest {
        val h = FloppyTestHarness()
        h.floppyServer()
        FloppyTrackingScrobbler(h.store, h.api).scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 5.0))
        assertTrue(h.engine.requests.isEmpty())
        assertFalse(FloppyTrackingHistoryWriter(h.store, h.api).isConnected.first())
    }

    @Test
    fun `playback goes to the scrobble endpoint with the access token and seeks restart`() = runTest {
        val h = manualToken()
        val scrobbler = FloppyTrackingScrobbler(h.store, h.api)
        scrobbler.scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 5.0, durationSeconds = 6_000L))
        val request = h.engine.requests.single()
        assertEquals("/api/v1/scrobble/", request.path)
        assertEquals("flp_good", request.accessToken)
        assertTrue(request.json!!.contains("\"position_seconds\":300"))
        assertEquals(TrackingSeekScrobblePolicy.STOP_AND_RESTART, scrobbler.seekScrobblePolicy)
    }

    @Test
    fun `a refused event with a working token keeps the connection`() = runTest {
        val h = manualToken()
        h.engine.onceFirst(com.nuvio.tv.data.floppy.FloppyHttpMethod.POST, "/api/v1/scrobble/", 403)
        val error = apiError { h.api.scrobble(h.store.scope(), floppyScrobbleBody(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 5.0))!!) }
        assertEquals(403, error.status)
        assertTrue(h.store.state.value.isAuthenticated)
    }

    @Test
    fun `a revoked app token signs out`() = runTest {
        val h = manualToken()
        h.engine.onceFirst(FloppyHttpMethod.POST, "/api/v1/scrobble/", 403)
        h.engine.onceFirst(FloppyHttpMethod.GET, FloppyAuthRepository.CONNECTION_CHECK_PATH, 403)
        try {
            h.api.scrobble(h.store.scope(), floppyScrobbleBody(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 5.0))!!)
            fail("expected sign-out")
        } catch (error: FloppyAuthException) {
            assertEquals(FloppyAuthError.AUTHORIZATION_REVOKED, error.error)
        }
        assertFalse(h.store.state.value.isAuthenticated)
    }

    @Test
    fun `an expired code sign-in refreshes and sends again`() = runTest {
        val h = FloppyTestHarness()
        h.floppyServer()
        h.auth.startDeviceAuthorization(FloppyTestHarness.SERVER)
        h.now += 5_000L
        h.auth.pollDeviceAuthorization()
        h.engine.requests.clear()
        h.engine.onceFirst(FloppyHttpMethod.POST, "/api/v1/scrobble/", 401)
        h.engine.onceFirst(FloppyHttpMethod.GET, FloppyAuthRepository.CONNECTION_CHECK_PATH, 401)
        h.engine.onceFirst(
            FloppyHttpMethod.POST, "/oauth/token", 200,
            """{"access_token":"flp_access_2","token_type":"Bearer","expires_in":3600,"refresh_token":"flp_refresh_2"}"""
        )

        h.api.scrobble(h.store.scope(), floppyScrobbleBody(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 5.0))!!)

        assertEquals(
            listOf("/api/v1/scrobble/", FloppyAuthRepository.CONNECTION_CHECK_PATH, "/oauth/token", "/api/v1/scrobble/"),
            h.engine.paths()
        )
        assertEquals("flp_access_2", h.engine.requests.last().accessToken)
    }

    @Test
    fun `mark as watched is a manual stop and unmark is an unwatch action`() = runTest {
        val h = manualToken()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api)
        assertTrue(writer.isConnected.first())
        val show = TrackingMediaReference(TrackingMediaKind.SHOW, ids = TrackingExternalIds(tvdb = "371980"))
        val result = writer.addToHistory(
            1,
            listOf(
                TrackingHistoryItem(movie, 10L),
                TrackingHistoryItem(show),
                TrackingHistoryItem(show.copy(episode = TrackingEpisode(season = 1, number = 2)), 10L)
            )
        )
        assertEquals(2, result.attemptedCount)
        assertEquals(2, h.engine.requests.size)
        assertTrue(h.engine.requests.all { it.json!!.contains("\"manual\":true") })

        h.engine.requests.clear()
        val removal = writer.removeFromHistory(1, listOf(movie))
        assertEquals(1, removal.attemptedCount)
        assertEquals(1, h.engine.requests.size)
        assertTrue(h.engine.requests.single().json!!.contains("\"action\":\"unwatch\""))
        assertEquals(0, writer.addToHistory(2, listOf(TrackingHistoryItem(movie))).attemptedCount)
        assertEquals(1, h.engine.requests.size)
    }

    private suspend fun manualToken(): FloppyTestHarness {
        val h = FloppyTestHarness()
        h.floppyServer()
        h.auth.connectWithToken(FloppyTestHarness.SERVER, "flp_good")
        h.engine.requests.clear()
        return h
    }

    private suspend fun apiError(block: suspend () -> Unit): FloppyApiException {
        try {
            block()
        } catch (error: FloppyApiException) {
            return error
        }
        fail("expected a Floppy error")
        throw AssertionError()
    }
}
