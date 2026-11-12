package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaRequestGuardTest {

    private var now = 1_000_000L

    @Test
    fun `retry after in seconds sets the pause`() {
        val limits = AddonRateLimits(nowMs = { now })

        assertEquals(12_000L, limits.recordRateLimited("catalog.example.com", "12"))
        assertTrue(limits.isPaused("catalog.example.com"))
        now += 11_999L
        assertTrue(limits.isPaused("catalog.example.com"))
        now += 1L
        assertFalse(limits.isPaused("catalog.example.com"))
    }

    @Test
    fun `without retry after the pause doubles while 429s keep coming`() {
        val limits = AddonRateLimits(nowMs = { now }, defaultPauseMs = 30_000L)

        assertEquals(30_000L, limits.recordRateLimited("a.example", null))
        now += 30_000L
        assertEquals(60_000L, limits.recordRateLimited("a.example", null))
        now += 60_000L
        assertEquals(120_000L, limits.recordRateLimited("a.example", "not a number"))
    }

    @Test
    fun `a success after the pause resets the streak`() {
        val limits = AddonRateLimits(nowMs = { now }, defaultPauseMs = 30_000L)
        limits.recordRateLimited("a.example", null)
        now += 30_000L
        limits.recordSuccess("a.example")

        assertEquals(30_000L, limits.recordRateLimited("a.example", null))
    }

    @Test
    fun `the pause is capped and kept per host`() {
        val limits = AddonRateLimits(nowMs = { now }, maxPauseMs = 60_000L)

        assertEquals(60_000L, limits.recordRateLimited("a.example", "3600"))
        assertFalse(limits.isPaused("b.example"))
    }

    @Test
    fun `an error item gives a fixed short pause that is not a 429`() {
        val limits = AddonRateLimits(nowMs = { now }, defaultPauseMs = 30_000L, errorItemPauseMs = 20_000L)

        assertEquals(20_000L, limits.recordErrorItem("aio.example"))
        assertTrue(limits.isPaused("aio.example"))
        assertFalse(limits.isRateLimited("aio.example"))
        now += 20_000L
        assertEquals(20_000L, limits.recordErrorItem("aio.example"))
        now += 20_000L
        assertFalse(limits.isPaused("aio.example"))

        assertEquals("error items never feed the 429 ladder", 30_000L, limits.recordRateLimited("aio.example", null))
        assertTrue(limits.isRateLimited("aio.example"))
        limits.recordErrorItem("aio.example")
        assertTrue(limits.isRateLimited("aio.example"))
    }

    @Test
    fun `addon host ignores path and query`() {
        assertEquals("metadata.example.net",
            addonHost("https://metadata.example.net/stremio/abc/manifest.json?x=1"))
        assertEquals("not a url", addonHost("not a url"))
    }

    @Test
    fun `stats sum a window in one line and start a new window afterwards`() {
        val stats = MetaRequestStats(nowMs = { now }, firstWindowMs = 60_000L, laterWindowMs = 600_000L)

        assertTrue(stats.request("server-art", "Bingecat"))
        assertFalse(stats.request("server-art", "AIOMetadata"))
        assertFalse(stats.request("cw", "Bingecat"))
        stats.rateLimited("Bingecat")
        assertFalse(stats.skipped("server-art", "Bingecat"))
        assertEquals(60_000L, stats.currentWindowMs())
        now += 60_000L

        val line = stats.drain()
        assertNotNull(line)
        assertTrue(line!!, line.contains("total=3 429=1 paused=1"))
        assertTrue(line, line.contains("callers={server-art=2,cw=1,server-art(paused)=1}"))
        assertTrue(line, line.contains("addons={Bingecat=2,AIOMetadata=1}"))
        assertNull(stats.drain())

        assertTrue(stats.request("cw", "Bingecat"))
        assertEquals(600_000L, stats.currentWindowMs())
    }

    @Test
    fun `an AIOStreams error item is not a title`() {
        assertTrue(isAddonErrorMetaId("aiostreamserror.%7B%22errorDescription%22%3A%22Rate%20Limit%20Exceeded%22%7D"))
        assertTrue(isAddonErrorMetaId("aiostreamserror.{\"errorDescription\":\"Rate Limit Exceeded\"}"))
        assertFalse(isAddonErrorMetaId("tt11712058"))
        assertFalse(isAddonErrorMetaId(null))
    }

    @Test
    fun `an addon that keeps answering not found rests and comes back`() {
        val misses = AddonMetaMisses(nowMs = { now }, missesToRest = 3, restMs = 60_000L)

        assertFalse(misses.recordMiss("aios"))
        assertFalse(misses.recordMiss("aios"))
        assertFalse(misses.isResting("aios"))
        assertTrue(misses.recordMiss("aios"))
        assertTrue(misses.isResting("aios"))
        assertFalse(misses.isResting("cinemeta"))
        now += 60_000L
        assertFalse(misses.isResting("aios"))
        assertFalse(misses.recordMiss("aios"))
    }

    @Test
    fun `a title found clears the not found count`() {
        val misses = AddonMetaMisses(nowMs = { now }, missesToRest = 2)

        assertFalse(misses.recordMiss("aios"))
        misses.recordFound("aios")
        assertFalse(misses.recordMiss("aios"))
        assertFalse(misses.isResting("aios"))
    }
}
