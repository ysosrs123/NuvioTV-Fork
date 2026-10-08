package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class IptvSportsSummaryClientTest {
    private val afl = SportsLeagues.byId("afl")!!
    private val bigBash = SportsLeagues.byId("big-bash")!!

    private fun body(state: String) = """{"header":{"id":"7","competitions":[{"status":{"type":{"state":"$state"}},"competitors":[
        {"id":"1","homeAway":"home","score":"12","team":{"id":"1","displayName":"Richmond"}},{"id":"2","homeAway":"away","score":"6","team":{"id":"2","displayName":"Carlton"}}]}]}}"""

    private fun fixture(status: FixtureStatus) = SportsFixture("7", "afl", "australian-football", "Richmond v Carlton", null, null, 0L, status)

    private fun waitFor(timeout: Long = 5_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!check() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    @Test fun buildsSummaryAddress() {
        val client = IptvSportsSummaryClient()
        assertEquals("https://site.api.espn.com/apis/site/v2/sports/australian-football/afl/summary?event=401850001", client.url(afl, "401850001").toString())
        assertNull(client.url(afl, "../x"))
        assertNull(client.url(bigBash, "1"))
    }

    @Test fun fetchesParsesAndCapsTheBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body("in")))
            server.enqueue(MockResponse().setBody("x".repeat((IptvSportsSummaryClient.MAX_BYTES + 10).toInt())))
            server.enqueue(MockResponse().setBody("<html>"))
            server.enqueue(MockResponse().setResponseCode(503))
            val client = IptvSportsSummaryClient(espnBase = server.url("/sports/"))
            val summary = client.summary(afl, "7")
            assertEquals(FixtureStatus.LIVE, summary.status)
            assertEquals("Richmond", summary.homeName)
            assertEquals("12", summary.homeLine?.score)
            val request = server.takeRequest()
            assertEquals("/sports/australian-football/afl/summary?event=7", request.path)
            assertEquals("Nuvio-Live/1", request.getHeader("User-Agent"))
            suspend fun failure() = try { client.summary(afl, "7"); null } catch (error: SportsFetchException) { error }
            assertEquals(SportsFetchFailure.BODY_LIMIT, failure()?.failure)
            assertEquals(SportsFetchFailure.INVALID_RESPONSE, failure()?.failure)
            assertEquals(503, failure()?.status)
        }
    }

    @Test fun watchFetchesFinalGamesOnceAndPollsLiveGames() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body("post")))
            repeat(4) { server.enqueue(MockResponse().setBody(body("in"))) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val watch = IptvSportsSummaryWatch(IptvSportsSummaryClient(espnBase = server.url("/sports/")), scope, liveMillis = 20, waitingMillis = 20)
                watch.start(fixture(FixtureStatus.FINAL))
                waitFor { watch.summary.value != null }
                Thread.sleep(200)
                assertEquals(FixtureStatus.FINAL, watch.summary.value?.status)
                assertEquals(1, server.requestCount)
                watch.start(fixture(FixtureStatus.LIVE).copy(id = "8"))
                waitFor { server.requestCount >= 4 }
                watch.stop()
                assertEquals(FixtureStatus.LIVE, watch.summary.value?.status)
                assertTrue(server.requestCount >= 4)
                watch.start(fixture(FixtureStatus.LIVE).copy(source = SportsService.THESPORTSDB))
                assertNull(watch.summary.value)
            } finally { scope.cancel() }
        }
    }

    @Test fun watchBacksOffAfterFailures() {
        val watch = IptvSportsSummaryWatch(IptvSportsSummaryClient(), CoroutineScope(Dispatchers.Default))
        assertEquals(30_000L, watch.backoff(1))
        assertEquals(60_000L, watch.backoff(2))
        assertEquals(IptvSportsSummaryWatch.MAX_BACKOFF_MILLIS, watch.backoff(9))
    }
}
