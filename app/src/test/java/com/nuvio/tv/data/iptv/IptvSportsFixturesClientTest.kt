package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.SportsCacheEntry
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsService
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class IptvSportsFixturesClientTest {
    private val afl = SportsLeagues.byId("afl")!!
    private val epl = SportsLeagues.byId("epl")!!
    private val bigBash = SportsLeagues.byId("big-bash")!!
    private val date = LocalDate.of(2026, 10, 9)

    @Test fun buildsServiceAddresses() {
        val client = IptvSportsFixturesClient()
        assertEquals("https://site.api.espn.com/apis/site/v2/sports/australian-football/afl/scoreboard?dates=20261009",
            client.url(SportsService.ESPN, afl, date, null).toString())
        assertEquals("https://www.thesportsdb.com/api/v1/json/123/eventsday.php?d=2026-10-09&l=English_Premier_League",
            client.url(SportsService.THESPORTSDB, epl, date, "123").toString())
        assertNull(client.url(SportsService.ESPN, bigBash, date, null))
        assertNull(client.url(SportsService.THESPORTSDB, epl, date, null))
        assertNull(client.url(SportsService.THESPORTSDB, epl, date, "../x"))
        assertNull(client.url(SportsService.OFF, epl, date, "123"))
    }

    @Test fun fetchesAndParsesWithUserAgent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"events":[{"id":"1","date":"2026-10-09T08:40Z","competitions":[{"status":{"type":{"state":"pre"}},
                "competitors":[{"homeAway":"home","team":{"displayName":"Richmond"}},{"homeAway":"away","team":{"displayName":"Carlton"}}]}]}]}"""))
            val client = IptvSportsFixturesClient(espnBase = server.url("/sports/"))
            val fixtures = client.fixtures(SportsService.ESPN, afl, date, null, 0L)
            assertEquals("Richmond v Carlton", fixtures.single().title)
            val request = server.takeRequest()
            assertEquals("/sports/australian-football/afl/scoreboard?dates=20261009", request.path)
            assertEquals("Nuvio-Live/1", request.getHeader("User-Agent"))
        }
    }

    @Test fun failuresAreTyped() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setBody("<html>"))
            server.enqueue(MockResponse().setBody("x".repeat((IptvSportsFixturesClient.MAX_BYTES + 10).toInt())))
            val client = IptvSportsFixturesClient(sportsDbBase = server.url("/json/"))
            suspend fun failure() = try { client.fixtures(SportsService.THESPORTSDB, epl, date, "123", 0L); null } catch (error: SportsFetchException) { error }
            assertEquals(429, failure()?.status)
            assertEquals(SportsFetchFailure.INVALID_RESPONSE, failure()?.failure)
            assertEquals(SportsFetchFailure.BODY_LIMIT, failure()?.failure)
            assertEquals("/json/123/eventsday.php?d=2026-10-09&l=English_Premier_League", server.takeRequest().path)
        }
    }

    @Test fun storeRoundTripsAndPrunes() {
        val directory = Files.createTempDirectory("sports").toFile()
        try {
            val store = IptvSportsFixturesStore(File(directory, "cache"))
            val key = IptvSportsFixturesStore.key(SportsService.ESPN, afl, date)
            assertEquals("espn-afl-20261009", key)
            val entry = SportsCacheEntry(listOf(SportsFixture("1", "afl", "australian-football", "A v B", null, null, 5L, FixtureStatus.SCHEDULED)), 7L)
            store.write(key, entry)
            store.write("espn-afl-20261001", entry)
            store.write("../escape", entry)
            assertEquals(entry, store.read(key))
            assertNull(store.read("missing"))
            store.prune(setOf(key))
            assertEquals(listOf("$key.json"), File(directory, "cache").list()!!.toList())
            assertFalse(File(directory, "escape.json").exists())
        } finally { directory.deleteRecursively() }
    }
}
