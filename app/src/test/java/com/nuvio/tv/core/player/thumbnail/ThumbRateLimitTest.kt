package com.nuvio.tv.core.player.thumbnail

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thumbnail fetches against servers that rate limit: ramp-up, Retry-After, back-off and the host memory. */
class ThumbRateLimitTest {
    private val now = 1_445_412_480_000L    // Wed, 21 Oct 2015 07:28:00 GMT

    @Test fun retryAfterInSeconds() {
        assertEquals(5_000L, ThumbRateLimit.retryAfterMs("5", now))
        assertEquals(120_000L, ThumbRateLimit.retryAfterMs(" 120 ", now))
    }

    @Test fun retryAfterAsAnHttpDate() {
        assertEquals(30_000L, ThumbRateLimit.retryAfterMs("Wed, 21 Oct 2015 07:28:30 GMT", now))
    }

    @Test fun retryAfterIsKeptWithinBounds() {
        assertEquals(1_000L, ThumbRateLimit.retryAfterMs("0", now))
        assertEquals(1_000L, ThumbRateLimit.retryAfterMs("Wed, 21 Oct 2015 07:27:00 GMT", now))
        assertEquals(30 * 60_000L, ThumbRateLimit.retryAfterMs("86400", now))
        assertEquals(1_000L, ThumbRateLimit.retryAfterMs("-5", now))
    }

    @Test fun noUsableRetryAfterIsNull() {
        assertNull(ThumbRateLimit.retryAfterMs(null, now))
        assertNull(ThumbRateLimit.retryAfterMs("", now))
        assertNull(ThumbRateLimit.retryAfterMs("soon", now))
        assertNull(ThumbRateLimit.retryAfterMs("   ", now))
    }

    @Test fun backoffDoublesFromTwoSecondsUpToThirty() {
        assertEquals(listOf(0L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..6).map { ThumbRateLimit.backoffMs(it) })
        assertEquals(30_000L, ThumbRateLimit.backoffMs(1_000))
    }

    @Test fun rampStartsAtThreeAndGrowsToTheCeilingWhileFetchesGetThrough() {
        val r = FetchRamp()
        assertEquals(3, r.limit(8))
        var t = 0L
        val seen = ArrayList<Int>()
        repeat(12) {
            r.succeeded(++t)
            seen.add(r.limit(8))
        }
        assertEquals(listOf(3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 8), seen)
    }

    @Test fun rampNeverPassesThePhaseCeiling() {
        val r = FetchRamp()
        repeat(40) { r.succeeded(it.toLong() + 1) }
        assertEquals(ThumbFetchLanes.MAX, r.lanes)
        assertEquals(3, r.limit(3))       // while playing
        assertEquals(2, r.limit(2))       // TorBox with two playback connections
        assertEquals(1, r.limit(0))
    }

    @Test fun aRateLimitMeansOneAtATimeAndNoMoreGrowth() {
        val r = FetchRamp()
        repeat(4) { r.succeeded(it.toLong() + 1) }
        assertEquals(5, r.limit(8))
        assertEquals(2_000L, r.rateLimited(null, startedAtMs = 10, nowMs = 20))
        assertTrue(r.limited)
        assertEquals(1, r.limit(8))
        repeat(20) { r.succeeded(100L + it) }
        assertEquals(1, r.limit(8))
    }

    @Test fun backoffGrowsWhileRefusedAndResetsOnceAFetchGetsThrough() {
        val r = FetchRamp()
        assertEquals(2_000L, r.rateLimited(null, startedAtMs = 0, nowMs = 1_000))
        assertEquals(4_000L, r.rateLimited(null, startedAtMs = 3_000, nowMs = 3_500))
        assertEquals(8_000L, r.rateLimited(null, startedAtMs = 8_000, nowMs = 8_200))
        r.succeeded(startedAtMs = 16_500)
        assertEquals(0, r.hitsInRow)
        assertEquals(2_000L, r.rateLimited(null, startedAtMs = 20_000, nowMs = 20_300))
    }

    @Test fun fetchesRefusedTogetherCountOnce() {
        val r = FetchRamp()
        assertEquals(2_000L, r.rateLimited(null, startedAtMs = 100, nowMs = 900))
        assertNull(r.rateLimited(null, startedAtMs = 120, nowMs = 950))
        assertNull(r.rateLimited(null, startedAtMs = 900, nowMs = 960))
        assertEquals(1, r.hitsInRow)
        // A fetch that started before the refusal and got through says nothing about the server now.
        r.succeeded(startedAtMs = 500)
        assertEquals(1, r.hitsInRow)
    }

    @Test fun theServersRetryAfterWinsOverTheBackoff() {
        val r = FetchRamp()
        assertEquals(45_000L, r.rateLimited(45_000L, startedAtMs = 0, nowMs = 100))
        assertEquals(10_000L, r.rateLimited(10_000L, startedAtMs = 50, nowMs = 150))
    }

    @Test fun aRememberedHostStartsAtOneFetch() {
        val r = FetchRamp(limitedFromStart = true)
        assertEquals(1, r.limit(8))
        r.succeeded(1)
        r.succeeded(2)
        assertEquals(1, r.limit(8))
    }

    @Test fun hostsAreRememberedByHostOnly() {
        val hosts = RateLimitedHosts()
        assertFalse(hosts.contains("https://stremthru.example.xyz/v0/store/link/a"))
        hosts.remember("https://StremThru.example.xyz/v0/store/link/a?token=1")
        assertTrue(hosts.contains("https://stremthru.example.xyz/v0/store/link/other"))
        assertFalse(hosts.contains("https://nexus-114.japn.tb-cdn.pw/dl/x"))
        hosts.remember("https://user:pw@nexus-114.japn.tb-cdn.pw/dl/x")
        assertTrue(hosts.contains("https://nexus-114.japn.tb-cdn.pw/dl/y"))
        assertFalse(hosts.contains(null))
        hosts.remember(null)
        hosts.remember("not a url")
        assertFalse(hosts.contains("not a url"))
    }

    @Test fun aRememberedHostIsForgottenHalfAnHourAfterItsLast429() {
        val hosts = RateLimitedHosts()
        val url = "https://nexus-114.japn.tb-cdn.pw/dl/x"
        hosts.remember(url, nowMs = 1_000L)
        assertTrue(hosts.contains(url, nowMs = 1_000L + RateLimitedHosts.TTL_MS - 1))
        hosts.remember(url, nowMs = 20 * 60_000L)
        assertTrue(hosts.contains(url, nowMs = 20 * 60_000L + RateLimitedHosts.TTL_MS - 1))
        assertFalse(hosts.contains(url, nowMs = 20 * 60_000L + RateLimitedHosts.TTL_MS))
    }

    @Test fun only429IsRemembered() {
        val hosts = RateLimitedHosts()
        val url = "https://real-debrid.example/d/file.mkv"
        hosts.note(RateLimitedException(null, 503, url), nowMs = 0L)
        assertFalse(hosts.contains(url, nowMs = 1L))
        hosts.note(RateLimitedException(5_000L, 429, url), nowMs = 0L)
        assertTrue(hosts.contains(url, nowMs = 1L))
        hosts.note(RateLimitedException(null, 429, null), nowMs = 0L)
    }

    @Test fun theHostThatAnswered429IsRememberedNotTheAddonThatRedirected() {
        MockWebServer().use { files ->
            MockWebServer().use { addon ->
                files.enqueue(MockResponse().setResponseCode(429))
                addon.enqueue(
                    MockResponse().setResponseCode(302).setHeader("Location", files.url("/dl/film.mkv").toString())
                )
                val link = addon.url("/v0/store/link/a").toString()
                val reader = HttpRangeReader(link, emptyMap(), OkHttpClient())
                val e = assertThrows(RateLimitedException::class.java) { reader.read(0, 1024) }
                assertEquals(429, e.status)
                val hosts = RateLimitedHosts()
                hosts.note(e)
                assertTrue(hosts.contains(files.url("/dl/other.mkv").toString()))
                assertFalse(hosts.contains(link))
            }
        }
    }

    @Test fun a503StillPausesButIsNotRemembered() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "7"))
            val url = server.url("/dl/film.mkv").toString()
            val e = assertThrows(RateLimitedException::class.java) {
                HttpRangeReader(url, emptyMap(), OkHttpClient()).read(0, 1024)
            }
            assertEquals(503, e.status)
            assertEquals(7_000L, e.retryAfterMs)
            val hosts = RateLimitedHosts()
            hosts.note(e)
            assertFalse(hosts.contains(url))
        }
    }

    @Test fun hostOf() {
        assertEquals("host.example:8443", RateLimitedHosts.hostOf("https://host.example:8443/a/b?c=d"))
        assertEquals("host.example", RateLimitedHosts.hostOf("http://host.example?x=1"))
        assertNull(RateLimitedHosts.hostOf("https:///path"))
    }
}
