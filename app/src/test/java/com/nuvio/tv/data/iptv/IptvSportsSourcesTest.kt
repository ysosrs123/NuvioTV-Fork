package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.SportsDbLeague
import com.nuvio.tv.core.iptv.SportsDbLeagues
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsService
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class IptvSportsSourcesTest {
    private val afl = SportsLeagues.byId("afl")!!
    private val now = Instant.parse("2026-10-09T10:00:00Z").toEpochMilli()
    private val zone = ZoneOffset.UTC
    private val key = "testkey"
    private val minute = 60_000L

    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/$name")!!.bufferedReader().use { it.readText() }

    private class Fake(val espnUp: AtomicBoolean = AtomicBoolean(true), val routes: (RecordedRequest) -> String?) : Dispatcher() {
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val paths: List<String> get() = requests.map { it.first }
        fun count(part: String) = paths.count { part in it }
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            requests += path to request.getHeader("X-API-KEY")
            if (path.startsWith("/sports/") && !espnUp.get()) return MockResponse().setResponseCode(500)
            return MockResponse().setBody(routes(request) ?: """{"events":null}""")
        }
    }

    private fun withCache(fake: Fake, block: suspend (IptvSportsFixturesCache, () -> IptvSportsFixturesCache) -> Unit) {
        val directory = Files.createTempDirectory("sports-sources").toFile()
        val server = MockWebServer()
        server.dispatcher = fake
        try {
            fun cache() = IptvSportsFixturesCache(IptvSportsFixturesClient(espnBase = server.url("/sports/"), sportsDbBase = server.url("/json/"),
                sportsDbV2Base = server.url("/v2/")), IptvSportsFixturesStore(File(directory, "cache")))
            runBlocking { block(cache(), ::cache) }
        } finally {
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    private val espnRichmond = """{"events":[{"id":"401","date":"2026-10-09T09:00Z","competitions":[{"status":{"type":{"state":"pre","name":"STATUS_SCHEDULED"}},
        "competitors":[{"homeAway":"home","team":{"displayName":"Richmond Tigers","location":"Richmond","name":"Tigers"}},
        {"homeAway":"away","team":{"displayName":"Carlton Blues","location":"Carlton","name":"Blues"}}]}]}]}"""
    private val sportsDbRichmond = """{"events":[{"idEvent":"2470001","idLeague":"4456","strLeague":"Australian AFL","strTimestamp":"2026-10-09T09:00:00",
        "strStatus":"NS","strHomeTeam":"Richmond","strAwayTeam":"Carlton"}]}"""

    private fun aflRoutes(request: RecordedRequest): String? {
        val path = request.path.orEmpty()
        return when {
            path.startsWith("/sports/australian-football/afl/scoreboard?dates=20261009") -> espnRichmond
            path.startsWith("/json/testkey/eventsday.php?d=2026-10-09&l=Australian_AFL") -> sportsDbRichmond
            path.startsWith("/json/testkey/lookuptv.php?id=2470001") -> sample("tsdb-lookuptv-synthetic.json")
            else -> null
        }
    }

    @Test fun espnFailuresFallBackToTheSportsDbUntilEspnRecovers() {
        val fake = Fake(AtomicBoolean(false), ::aflRoutes)
        withCache(fake) { cache, _ ->
            val first = cache.load(listOf(afl), key, now, zone, refresh = true)
            assertTrue(first.failed)
            assertTrue(first.fixtures.isEmpty())
            assertEquals(0, fake.count("/json/"))
            val espnDates = fake.count("/sports/")
            assertEquals(4, espnDates)
            val fallback = cache.load(listOf(afl), key, now + 2 * minute, zone, refresh = true)
            assertEquals(2 * espnDates, fake.count("/sports/"))
            assertEquals(3, fake.count("eventsday.php"))
            assertFalse(fallback.failed)
            assertEquals(SportsService.THESPORTSDB, fallback.fixtures.single().source)
            assertEquals("afl:sdb-2470001", fallback.fixtures.single().key)
            val waiting = cache.load(listOf(afl), key, now + 3 * minute, zone, refresh = true)
            assertEquals(SportsService.THESPORTSDB, waiting.fixtures.single().source)
            assertEquals(2 * espnDates, fake.count("/sports/"))
            assertEquals(3, fake.count("eventsday.php"))
            fake.espnUp.set(true)
            val recovered = cache.load(listOf(afl), key, now + 6 * minute, zone, refresh = true)
            assertEquals(3 * espnDates, fake.count("/sports/"))
            assertEquals(3, fake.count("eventsday.php"))
            assertEquals(SportsService.ESPN, recovered.fixtures.single().source)
            assertEquals("afl:401", recovered.fixtures.single().key)
        }
    }

    @Test fun withoutAKeyEspnFailuresStayOnEspnAndKeyOnlyLeaguesAreSkipped() {
        val fake = Fake(AtomicBoolean(false), ::aflRoutes)
        withCache(fake) { cache, _ ->
            cache.load(listOf(afl), null, now, zone, refresh = true)
            val result = cache.load(listOf(afl), null, now + 2 * minute, zone, refresh = true)
            assertTrue(result.failed)
            assertEquals(0, fake.count("/json/"))
            val bigBash = cache.load(listOf(SportsLeagues.byId("big-bash")!!), null, now, zone, refresh = true)
            assertTrue(bigBash.fixtures.isEmpty())
            assertEquals(0, fake.count("/json/"))
        }
    }

    @Test fun followedTeamsGetTvChannelsFromLookupTvCachedForADay() {
        val fake = Fake(routes = ::aflRoutes)
        val favourites = setOf("afl:Richmond Tigers")
        withCache(fake) { cache, fresh ->
            val result = cache.load(listOf(afl), key, now, zone, refresh = true, favourites = favourites, country = "AU")
            val game = result.fixtures.single()
            assertEquals(SportsService.ESPN, game.source)
            assertEquals(listOf("Stan Sport"), game.broadcasters)
            assertEquals(1, fake.count("eventsday.php"))
            assertEquals(1, fake.count("lookuptv.php"))
            val again = cache.load(listOf(afl), key, now + minute, zone, refresh = true, favourites = favourites, country = "AU")
            assertEquals(listOf("Stan Sport"), again.fixtures.single().broadcasters)
            val restarted = fresh().load(listOf(afl), key, now + 2 * minute, zone, refresh = true, favourites = favourites, country = "GB")
            assertEquals(listOf("Sky Sports Premier League"), restarted.fixtures.single().broadcasters)
            assertEquals(1, fake.count("eventsday.php"))
            assertEquals(1, fake.count("lookuptv.php"))
            val unfollowed = fresh().load(listOf(afl), key, now + 3 * minute, zone, refresh = true, favourites = emptySet())
            assertTrue(unfollowed.fixtures.single().broadcasters.isEmpty())
            assertEquals(1, fake.count("lookuptv.php"))
            val noKey = fresh().load(listOf(afl), null, now + 4 * minute, zone, refresh = true, favourites = favourites)
            assertTrue(noKey.fixtures.single().broadcasters.isEmpty())
            assertFalse(fake.paths.any { "livescore" in it })
        }
    }

    @Test fun tvRequestsAreCappedPerRefresh() {
        val events = (1..6).joinToString(",") { """{"id":"40$it","date":"2026-10-09T0$it:00Z","competitions":[{"status":{"type":{"state":"pre"}},
            "competitors":[{"homeAway":"home","team":{"displayName":"Richmond Tigers"}},{"homeAway":"away","team":{"displayName":"Team $it"}}]}]}""" }
        val fake = Fake { request -> if (request.path.orEmpty().startsWith("/sports/australian-football/afl/scoreboard?dates=20261009")) """{"events":[$events]}""" else null }
        withCache(fake) { cache, _ ->
            cache.load(listOf(afl), key, now - 10 * 60 * minute, zone, refresh = true, favourites = setOf("afl:Richmond Tigers"))
            assertTrue(fake.count("/json/") <= 3)
        }
    }

    @Test fun theSportsDbLivescoresUpdateItsLeaguesWithTheKeyHeader() {
        val custom = SportsDbLeagues.league(SportsDbLeague("4999", "Test League", "Soccer"))
        val started = """{"events":[{"idEvent":"2494100","idLeague":"4999","strLeague":"Test League","strTimestamp":"2026-10-09T09:40:00","strStatus":"NS",
            "strHomeTeam":"Fulham","strAwayTeam":"Brentford"}]}"""
        val fake = Fake { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/json/testkey/eventsday.php?d=2026-10-09&l=Test_League") -> started
                path == "/v2/livescore/soccer" -> sample("tsdb-livescore-soccer-synthetic.json")
                else -> null
            }
        }
        withCache(fake) { cache, _ ->
            val result = cache.load(listOf(custom), key, now, zone, refresh = true)
            val game = result.fixtures.single()
            assertEquals(FixtureStatus.LIVE, game.status)
            assertEquals("1–0", game.score)
            assertEquals("67'", game.clock)
            assertEquals(1, fake.count("/v2/livescore/soccer"))
            assertEquals(key, fake.requests.first { it.first == "/v2/livescore/soccer" }.second)
            assertTrue(fake.requests.filter { it.first.startsWith("/json/") }.all { it.second == null })
            assertFalse(fake.paths.any { key in it && it.startsWith("/v2/") })
            val soon = cache.load(listOf(custom), key, now + minute, zone, refresh = true)
            assertEquals(FixtureStatus.LIVE, soon.fixtures.single().status)
            assertEquals(1, fake.count("/v2/livescore/soccer"))
            cache.load(listOf(custom), key, now + 3 * minute, zone, refresh = true)
            assertEquals(2, fake.count("/v2/livescore/soccer"))
            val shown = cache.load(listOf(custom), key, now + 4 * minute, zone, refresh = false)
            assertEquals("1–0", shown.fixtures.single().score)
        }
    }

    @Test fun clientBuildsTheSportsDbExtraAddresses() {
        val client = IptvSportsFixturesClient()
        assertEquals("https://www.thesportsdb.com/api/v2/json/livescore/ice_hockey", client.livescoreUrl("ice-hockey", key).toString())
        assertNull(client.livescoreUrl("netball", key))
        assertNull(client.livescoreUrl("soccer", null))
        assertEquals("https://www.thesportsdb.com/api/v1/json/testkey/lookuptv.php?id=2494100", client.tvUrl("2494100", key).toString())
        assertNull(client.tvUrl("../1", key))
        assertNull(client.tvUrl("1", null))
        assertEquals("https://www.thesportsdb.com/api/v1/json/testkey/all_leagues.php", client.leaguesUrl(key).toString())
        assertNull(client.leaguesUrl("bad/key"))
    }

    @Test fun oldCachesLoadAsTheirServiceAndMetaFilesSurvivePruning() {
        val directory = Files.createTempDirectory("sports-store").toFile()
        try {
            val store = IptvSportsFixturesStore(File(directory, "cache"))
            val old = """{"version":2,"fetched":7,"failures":0,"fixtures":[{"id":"9","league":"afl","start":5,"status":"SCHEDULED","title":"A v B"}]}"""
            val date = LocalDate.of(2026, 10, 9)
            val db = IptvSportsFixturesStore.key(SportsService.THESPORTSDB, afl, date)
            val espn = IptvSportsFixturesStore.key(SportsService.ESPN, afl, date)
            store.writeText(db, old)
            store.writeText(espn, old)
            store.writeText(IptvSportsFixturesStore.TV, "{}")
            assertEquals(SportsService.THESPORTSDB, store.read(db)!!.fixtures.single().source)
            assertEquals(SportsService.ESPN, store.read(espn)!!.fixtures.single().source)
            store.prune(setOf(espn))
            assertNull(store.read(db))
            assertNotNull(store.read(espn))
            assertEquals("{}", store.readText(IptvSportsFixturesStore.TV))
        } finally { directory.deleteRecursively() }
    }

    @Test fun leagueListIsCachedForAWeek() {
        val fake = Fake { request -> if (request.path.orEmpty().startsWith("/json/testkey/all_leagues.php")) sample("tsdb-all-leagues-synthetic.json") else null }
        withCache(fake) { cache, fresh ->
            assertEquals(5, cache.leagues(key, now).size)
            assertEquals(5, fresh().leagues(key, now + 6L * 24 * 60 * minute).size)
            assertEquals(1, fake.count("all_leagues.php"))
            assertEquals(5, fresh().leagues(key, now + 8L * 24 * 60 * minute).size)
            assertEquals(2, fake.count("all_leagues.php"))
        }
    }
}
