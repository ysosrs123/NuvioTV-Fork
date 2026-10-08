package com.nuvio.tv.data.trailer

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class TrailerSourceCacheTest {
    private class TestClock(var now: Long = 1_800_000_000_000L) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    @Test fun `video and audio signatures limit cache lifetime with a refresh margin`() {
        val clock = TestClock()
        val cache = TrailerSourceCache(clock)
        val source = TrailerPlaybackSource("https://cdn.test/v?Expires=1800000600", "https://cdn.test/a?expire=1800000120")
        cache.put("title", source)
        assertNotNull(cache.get("title"))
        clock.now += 60_000L
        assertNull(cache.get("title"))
    }

    @Test fun `CloudFront custom policies and YouTube path expirations are recognized`() {
        val policy = """{"Statement":[{"Condition":{"DateLessThan":{"AWS:EpochTime":1800000600}}}]}"""
        val encoded = Base64.getEncoder().encodeToString(policy.toByteArray()).replace('+','-').replace('=','_').replace('/','~')
        assertEquals(1_800_000_540_000L, TrailerSourceExpiry.expiresAtMs(TrailerPlaybackSource("https://cdn.test/v?Policy=$encoded")))
        assertEquals(1_800_000_540_000L, TrailerSourceExpiry.expiresAtMs(TrailerPlaybackSource("https://cdn.test/expire/1800000600/v")))
    }

    @Test fun `negative entries and unsigned sources expire rather than pinning session state`() {
        val clock = TestClock()
        val cache = TrailerSourceCache(clock)
        cache.put("miss", null, 30_000)
        cache.put("hit", TrailerPlaybackSource("https://cdn.test/v"), 60_000)
        assertNotNull(cache.get("miss"))
        assertNull(cache.get("miss")?.source)
        clock.now += 30_000
        assertNull(cache.get("miss"))
        assertNotNull(cache.get("hit"))
        clock.now += 30_000
        assertNull(cache.get("hit"))
    }

    @Test fun `LRU bound and invalidation remove all aliases to failed media`() {
        val cache = TrailerSourceCache(TestClock(), 2)
        val source = TrailerPlaybackSource("https://cdn.test/v")
        cache.put("a", source); cache.put("b", source)
        cache.get("a"); cache.put("c", source)
        assertNull(cache.get("b"))
        assertNotNull(cache.get("a"))
        cache.invalidate(source.videoUrl)
        assertNull(cache.get("a")); assertNull(cache.get("c"))
    }
}
