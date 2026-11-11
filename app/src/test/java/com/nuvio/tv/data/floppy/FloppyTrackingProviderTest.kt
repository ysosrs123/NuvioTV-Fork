package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingSeekScrobblePolicy
import java.time.Instant
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
        FloppyTrackingScrobbler(h.store, h.api, h.ledger).scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 5.0))
        assertTrue(h.engine.requests.isEmpty())
        assertFalse(FloppyTrackingHistoryWriter(h.store, h.api, h.ledger).isConnected.first())
    }

    @Test
    fun `playback goes to the scrobble endpoint with the access token and seeks restart`() = runTest {
        val h = manualToken()
        val scrobbler = FloppyTrackingScrobbler(h.store, h.api, h.ledger)
        scrobbler.scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 5.0, durationSeconds = 6_000L))
        val request = h.engine.requests.single()
        assertEquals("/api/v1/scrobble/", request.path)
        assertEquals("flp_good", request.accessToken)
        assertTrue(request.json!!.contains("\"position_seconds\":300"))
        assertEquals(TrackingSeekScrobblePolicy.STOP_AND_RESTART, scrobbler.seekScrobblePolicy)
        assertTrue(scrobbler.stopsWhenLeftPaused)
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
        h.engine.onceFirst(FloppyHttpMethod.GET, FloppyAuthRepository.CONNECTION_CHECK_PATH, 403, FLOPPY_REFUSAL)
        try {
            h.api.scrobble(h.store.scope(), floppyScrobbleBody(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 5.0))!!)
            fail("expected sign-out")
        } catch (error: FloppyAuthException) {
            assertEquals(FloppyAuthError.AUTHORIZATION_REVOKED, error.error)
        }
        assertFalse(h.store.state.value.isAuthenticated)
    }

    @Test
    fun `an older server's empty 401 signs out so sign-in is asked for`() = runTest {
        val h = manualToken()
        h.engine.onceFirst(FloppyHttpMethod.POST, scrobblePath, 401, "")
        h.engine.onceFirst(FloppyHttpMethod.GET, FloppyAuthRepository.CONNECTION_CHECK_PATH, 401, "")
        try {
            h.api.scrobble(h.store.scope(), floppyScrobbleBody(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 5.0))!!)
            fail("expected sign-out")
        } catch (error: FloppyAuthException) {
            assertEquals(FloppyAuthError.AUTHORIZATION_REVOKED, error.error)
        }
        assertFalse(h.store.state.value.isAuthenticated)
    }

    @Test
    fun `a proxy refusing the call and the check keeps a hand-entered token`() = runTest {
        listOf(401 to "<html>Sign in</html>", 403 to "", 403 to """{"message":"Forbidden"}""").forEach { (status, body) ->
            val h = manualToken()
            h.engine.onceFirst(FloppyHttpMethod.POST, scrobblePath, status, body)
            h.engine.onceFirst(FloppyHttpMethod.GET, FloppyAuthRepository.CONNECTION_CHECK_PATH, status, body)

            val error = apiError { h.api.scrobble(h.store.scope(), floppyScrobbleBody(TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 5.0))!!) }

            assertEquals(status, error.status)
            assertTrue(h.store.state.value.isAuthenticated)
            assertEquals("flp_good", h.store.authorization()!!.credentials.accessToken)
        }
    }

    @Test
    fun `an expired code sign-in refreshes and sends again`() = runTest {
        val h = FloppyTestHarness()
        h.floppyServer()
        h.auth.startDeviceAuthorization(FloppyTestHarness.SERVER)
        h.now += 6_000L
        h.auth.pollDeviceAuthorization()
        h.engine.requests.clear()
        h.engine.onceFirst(FloppyHttpMethod.POST, "/api/v1/scrobble/", 401)
        h.engine.onceFirst(FloppyHttpMethod.GET, FloppyAuthRepository.CONNECTION_CHECK_PATH, 401, FLOPPY_REFUSAL)
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
    fun `mark as watched is sent as a manual stop and another profile sends nothing`() = runTest {
        val h = manualToken()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api, h.ledger)
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
        assertEquals(0, writer.removeFromHistory(2, listOf(movie)).attemptedCount)
        assertEquals(0, writer.addToHistory(2, listOf(TrackingHistoryItem(movie))).attemptedCount)
        assertTrue(h.engine.requests.isEmpty())
    }

    private val history = "/api/v1/media/movie/tmdb/603/history/"
    private val scrobblePath = "/api/v1/scrobble/"
    private val episode = TrackingMediaReference(
        TrackingMediaKind.SHOW,
        ids = TrackingExternalIds(tmdb = 603L),
        episode = TrackingEpisode(season = 1, number = 2)
    )

    private fun FakeFloppyEngine.answersLikeAnOldServer() =
        onceFirst(FloppyHttpMethod.POST, scrobblePath, 400, OLD_SERVER_REFUSAL)

    @Test
    fun `an unmark sends unwatch for a film and an episode and never the delete route`() = runTest {
        val h = manualToken()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api, h.ledger)
        h.ledger.record(1, 603L, 1_000L)

        val result = writer.removeFromHistory(1, listOf(movie, episode))

        assertEquals(2, result.attemptedCount)
        assertEquals(0, result.notFoundCount)
        assertEquals(listOf(scrobblePath, scrobblePath), h.engine.paths())
        assertTrue(h.engine.requests.all { it.json!!.contains("\"action\":\"unwatch\"") })
        assertTrue(h.engine.requests[0].json!!.contains("\"media_type\":\"movie\""))
        assertTrue(h.engine.requests[1].json!!.contains("\"season_number\":1"))
        assertTrue(h.engine.requests.all { it.json!!.contains("\"client_event_id\":\"arc-unmark-") })
        assertTrue(h.ledger.stamps(1, 603L).isEmpty())
    }

    @Test
    fun `an episode with the same tmdb number as a film leaves the film's record alone`() = runTest {
        val h = manualToken()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api, h.ledger)
        h.ledger.record(1, 603L, 1_000L)

        writer.removeFromHistory(1, listOf(episode))

        assertEquals(1, h.ledger.stamps(1, 603L).size)
    }

    @Test
    fun `a whole series is skipped so a series id can't take back a film`() = runTest {
        val h = manualToken()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api, h.ledger)
        h.ledger.record(1, 603L, 1_000L)

        val result = writer.removeFromHistory(1, listOf(episode.copy(episode = null)))

        assertEquals(0, result.attemptedCount)
        assertTrue(h.engine.requests.isEmpty())
        assertEquals(1, h.ledger.stamps(1, 603L).size)
    }

    @Test
    fun `an unresolved item is counted as not found and the rest still go`() = runTest {
        val h = manualToken()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api, h.ledger)
        val unresolved = TrackingMediaReference(TrackingMediaKind.MOVIE, title = "No ids")

        val result = writer.removeFromHistory(1, listOf(unresolved, movie))

        assertEquals(2, result.attemptedCount)
        assertEquals(1, result.notFoundCount)
        assertEquals(1, h.engine.requests.size)
    }

    @Test
    fun `a 404 counts as not found and keeps the record`() = runTest {
        val h = manualToken()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api, h.ledger)
        h.ledger.record(1, 603L, 1_000L)
        h.engine.onceFirst(FloppyHttpMethod.POST, scrobblePath, 404, """{"detail":"Could not resolve media.","code":"MEDIA_NOT_FOUND"}""")

        val result = writer.removeFromHistory(1, listOf(movie))

        assertEquals(1, result.attemptedCount)
        assertEquals(1, result.notFoundCount)
        assertEquals(listOf(scrobblePath), h.engine.paths())
        assertEquals(1, h.ledger.stamps(1, 603L).size)
    }

    @Test
    fun `other failures surface and don't fall back`() = runTest {
        val h = manualToken()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api, h.ledger)
        h.ledger.record(1, 603L, 1_000L)
        val refusals = listOf(
            400 to """{"detail":"'media_type' must be one of ('movie', 'episode')."}""",
            400 to """<html>Bad Request</html>"""
        )
        refusals.forEach { (status, body) ->
            h.engine.requests.clear()
            h.engine.onceFirst(FloppyHttpMethod.POST, scrobblePath, status, body)
            assertEquals(status, apiError { writer.removeFromHistory(1, listOf(movie)) }.status)
            assertEquals(listOf(scrobblePath), h.engine.paths())
            assertEquals(1, h.ledger.stamps(1, 603L).size)
        }
    }

    private fun season(count: Int) = (1..count).map { number ->
        TrackingHistoryItem(episode.copy(episode = TrackingEpisode(season = 1, number = number)), 10L)
    }

    private val busy = """{"detail":"Could not resolve media.","code":"PROVIDER_UNAVAILABLE","retryable":true}"""

    @Test
    fun `one refused episode doesn't cost the rest of the season and still surfaces`() = runTest {
        val h = manualToken()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api, h.ledger)
        h.engine.onceFirst(FloppyHttpMethod.POST, scrobblePath, 400, """{"detail":"bad"}""")

        assertEquals(400, apiError { writer.addToHistory(1, season(4)) }.status)

        assertEquals(4, h.engine.requests.size)
        (1..4).forEach { number -> assertTrue(h.engine.requests[number - 1].json!!.contains("\"episode_number\":$number")) }
    }

    @Test
    fun `a busy server gets the same mark again once`() = runTest {
        val h = manualToken()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api, h.ledger)
        h.engine.onceFirst(FloppyHttpMethod.POST, scrobblePath, 503, busy)

        assertEquals(1, writer.addToHistory(1, season(1)).attemptedCount)

        assertEquals(2, h.engine.requests.size)
        assertEquals(h.engine.requests[0].json, h.engine.requests[1].json)
    }

    @Test
    fun `a busy server gets the same unmark again once and two refusals surface`() = runTest {
        val h = manualToken()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api, h.ledger)
        h.engine.onceFirst(FloppyHttpMethod.POST, scrobblePath, 503, busy)
        assertEquals(1, writer.removeFromHistory(1, listOf(movie)).attemptedCount)
        assertEquals(h.engine.requests[0].json, h.engine.requests[1].json)

        h.engine.requests.clear()
        h.engine.onceFirst(FloppyHttpMethod.POST, scrobblePath, 503, busy)
        h.engine.onceFirst(FloppyHttpMethod.POST, scrobblePath, 503, busy)
        assertEquals(503, apiError { writer.removeFromHistory(1, listOf(movie)) }.status)
        assertEquals(2, h.engine.requests.size)
    }

    @Test
    fun `three failures in a row end the batch`() = runTest {
        val h = manualToken()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api, h.ledger)
        repeat(3) { h.engine.onceFirst(FloppyHttpMethod.POST, scrobblePath, 400, """{"detail":"bad"}""") }

        assertEquals(400, apiError { writer.addToHistory(1, season(6)) }.status)

        assertEquals(3, h.engine.requests.size)
    }

    @Test
    fun `an unmark that fails part way still sends the others`() = runTest {
        val h = manualToken()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api, h.ledger)
        h.engine.onceFirst(FloppyHttpMethod.POST, scrobblePath, 400, """{"detail":"bad"}""")

        assertEquals(400, apiError { writer.removeFromHistory(1, season(3).map { it.media }) }.status)

        assertEquals(3, h.engine.requests.size)
    }

    @Test
    fun `a sign-out part way through stops the batch at once`() = runTest {
        val h = manualToken()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api, h.ledger)
        h.engine.onceFirst(FloppyHttpMethod.POST, scrobblePath, 403)
        h.engine.onceFirst(FloppyHttpMethod.GET, FloppyAuthRepository.CONNECTION_CHECK_PATH, 403, FLOPPY_REFUSAL)
        try {
            writer.addToHistory(1, season(3))
            fail("expected sign-out")
        } catch (error: FloppyAuthException) {
            assertEquals(FloppyAuthError.AUTHORIZATION_REVOKED, error.error)
        }
        assertEquals(listOf(scrobblePath, FloppyAuthRepository.CONNECTION_CHECK_PATH), h.engine.paths())
    }

    @Test
    fun `a retry after a refreshed sign-in repeats the same event id`() = runTest {
        val h = FloppyTestHarness()
        h.floppyServer()
        h.auth.startDeviceAuthorization(FloppyTestHarness.SERVER)
        h.now += 6_000L
        h.auth.pollDeviceAuthorization()
        h.engine.requests.clear()
        h.engine.onceFirst(FloppyHttpMethod.POST, scrobblePath, 401)
        h.engine.onceFirst(FloppyHttpMethod.GET, FloppyAuthRepository.CONNECTION_CHECK_PATH, 401, FLOPPY_REFUSAL)
        h.engine.onceFirst(
            FloppyHttpMethod.POST, "/oauth/token", 200,
            """{"access_token":"flp_access_2","token_type":"Bearer","expires_in":3600,"refresh_token":"flp_refresh_2"}"""
        )

        FloppyTrackingHistoryWriter(h.store, h.api, h.ledger).removeFromHistory(1, listOf(movie))

        val sent = h.engine.requests.filter { it.path == scrobblePath }
        assertEquals(2, sent.size)
        assertEquals(sent[0].json, sent[1].json)
    }

    @Test
    fun `an unmark on an old server deletes the play this box added and leaves the older one`() = runTest {
        val h = manualToken()
        val at = Instant.parse("2026-11-05T09:12:34.567Z").toEpochMilli()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api, h.ledger)
        writer.addToHistory(1, listOf(TrackingHistoryItem(movie, at)))
        h.engine.answersLikeAnOldServer()
        h.engine.on(
            FloppyHttpMethod.GET,
            history,
            200,
            """{"results":[{"consumption_id":7,"end_date":"2026-11-05T09:12:34.567000Z"},{"consumption_id":3,"end_date":"2024-01-01T10:00:00Z"},{"consumption_id":9,"end_date":null}]}"""
        )
        h.engine.on(FloppyHttpMethod.DELETE, "${history}7/", 204, "")
        assertEquals(1, writer.removeFromHistory(1, listOf(movie)).attemptedCount)
        assertEquals(listOf(scrobblePath, scrobblePath, history, "${history}7/"), h.engine.paths().filterNot { it.startsWith("/oauth") || it == FloppyAuthRepository.CONNECTION_CHECK_PATH })
        assertTrue(h.ledger.stamps(1, 603L).isEmpty())
    }

    @Test
    fun `a play finished in the player is matched though Floppy rounds its time down to the minute`() {
        val at = Instant.parse("2026-11-03T15:22:47Z").toEpochMilli()
        val plays = parseFloppyPlays("""[{"consumption_id":1,"end_date":"2026-11-03T15:22:00Z"},{"consumption_id":2,"end_date":"2026-11-03T15:25:00Z"}]""")
        assertEquals(listOf(1L), floppyPlaysMadeHere(plays, listOf(at)).map { it.id })
    }

    @Test
    fun `a finished film in the player is recorded and a part watched one is not`() = runTest {
        val h = manualToken()
        val scrobbler = FloppyTrackingScrobbler(h.store, h.api, h.ledger)
        scrobbler.scrobble(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(movie, 50.0))
        assertTrue(h.ledger.stamps(1, 603L).isEmpty())
        scrobbler.scrobble(TrackingScrobbleAction.STOP, TrackingScrobbleEvent(movie, 95.0))
        assertEquals(1, h.ledger.stamps(1, 603L).size)
    }

    @Test
    fun `on an old server episodes and a film with no match are left alone and a failed delete keeps the record`() = runTest {
        val h = manualToken()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api, h.ledger)
        val at = Instant.parse("2026-11-05T09:12:34Z").toEpochMilli()
        h.engine.answersLikeAnOldServer()
        assertEquals(0, writer.removeFromHistory(1, listOf(episode)).attemptedCount)
        h.ledger.record(1, 603L, at)
        h.engine.answersLikeAnOldServer()
        h.engine.onceFirst(FloppyHttpMethod.GET, history, 200, """[{"consumption_id":7,"end_date":"2026-11-05T09:12:34Z"}]""")
        h.engine.onceFirst(FloppyHttpMethod.DELETE, "${history}7/", 500)
        try {
            writer.removeFromHistory(1, listOf(movie))
            fail("expected the failure to surface")
        } catch (error: FloppyApiException) {
            assertEquals(500, error.status)
        }
        assertEquals(1, h.ledger.stamps(1, 603L).size)
        h.engine.answersLikeAnOldServer()
        h.engine.onceFirst(FloppyHttpMethod.GET, history, 200, """[{"consumption_id":3,"end_date":"2024-01-01T10:00:00Z"}]""")
        h.engine.requests.clear()
        writer.removeFromHistory(1, listOf(movie))
        assertEquals(listOf(scrobblePath, history), h.engine.paths())
    }

    @Test
    fun `an old server is asked once per unmark and a film with no record costs nothing more`() = runTest {
        val h = manualToken()
        val writer = FloppyTrackingHistoryWriter(h.store, h.api, h.ledger)
        val nextEpisode = episode.copy(episode = TrackingEpisode(season = 1, number = 3))
        h.engine.answersLikeAnOldServer()

        val result = writer.removeFromHistory(1, listOf(episode, nextEpisode, movie))

        assertEquals(0, result.attemptedCount)
        assertEquals(0, result.notFoundCount)
        assertEquals(listOf(scrobblePath), h.engine.paths())
    }

    private companion object {
        const val OLD_SERVER_REFUSAL = """{"detail":"'action' must be one of ('start', 'pause', 'stop')."}"""
        const val FLOPPY_REFUSAL = """{"detail":"Invalid token."}"""
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
