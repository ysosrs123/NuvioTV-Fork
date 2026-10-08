package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.SportsCacheEntry
import com.nuvio.tv.core.iptv.SportsDays
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsService
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class IptvSportsFixturesCacheTest {
    private val afl = SportsLeagues.byId("afl")!!
    private val now = Instant.parse("2026-10-09T10:00:00Z").toEpochMilli()
    private val zone = ZoneOffset.UTC
    private val cached = SportsFixture("1", "afl", "australian-football", "Richmond v Carlton", null, null, now + 2 * 60 * 60 * 1000, FixtureStatus.SCHEDULED)

    private fun keys(): List<String> {
        val (from, until) = SportsDays.window(now, zone)
        return SportsDays.serviceDates(from, until, SportsDays.zone(SportsService.ESPN)).map { IptvSportsFixturesStore.key(SportsService.ESPN, afl, it) }
    }

    private fun withCache(fetchedAt: Long, block: suspend CoroutineScope.(IptvSportsFixturesCache, MockWebServer, CountDownLatch) -> Unit) {
        val directory = Files.createTempDirectory("sports-cache").toFile()
        val release = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                release.await(10, TimeUnit.SECONDS)
                return MockResponse().setBody("""{"events":[]}""")
            }
        }
        try {
            val store = IptvSportsFixturesStore(File(directory, "cache"))
            keys().forEachIndexed { index, key -> store.write(key, SportsCacheEntry(if (index == 1) listOf(cached) else emptyList(), fetchedAt)) }
            runBlocking { block(IptvSportsFixturesCache(IptvSportsFixturesClient(espnBase = server.url("/sports/")), store), server, release) }
        } finally {
            release.countDown()
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test fun cachedFixturesDoNotWaitForARunningFetch() = withCache(now - 2 * 60 * 60 * 1000) { cache, server, release ->
        val refresh = async(Dispatchers.Default) { cache.load(listOf(afl), null, now, zone, refresh = true) }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val shown = withTimeout(2_000) { cache.load(listOf(afl), null, now, zone, refresh = false) }
        assertEquals(listOf("Richmond v Carlton"), shown.fixtures.map { it.title })
        release.countDown()
        assertTrue(refresh.await().fixtures.isEmpty())
        assertEquals(keys().size, server.requestCount)
    }

    @Test fun freshEntriesAreNotRequestedAgain() = withCache(now - 60_000) { cache, server, release ->
        release.countDown()
        val result = cache.load(listOf(afl), null, now, zone, refresh = true)
        assertEquals(listOf("Richmond v Carlton"), result.fixtures.map { it.title })
        assertFalse(result.failed)
        assertEquals(0, server.requestCount)
    }
}
