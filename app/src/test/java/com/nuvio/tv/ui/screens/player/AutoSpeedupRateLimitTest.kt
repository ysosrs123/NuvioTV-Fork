package com.nuvio.tv.ui.screens.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Too many requests" from a server with no history, playing a 4K remux at 54.6 Mbit/s: the old refill went 2 -> 8
 * at once, the server answered 429 at 8 requests in flight (once at 6), and the back-off
 * read as a slow line, so a try added a 9th.
 */
class AutoSpeedupRateLimitTest {
    private var now = 0L
    private var bytes = 0L
    private var ahead = 0L
    private var position = 0L
    private var rebuffers = 0
    private var seekAt = 0L
    private val log = mutableListOf<String>()

    @After
    fun clear() = HostSpeeds.clearForTest()

    private fun AutoSpeedupPolicy.play(seconds: Int, kbps: Long, aheadPerSecondMs: Long = 0L, loading: Boolean = true): Int {
        repeat(seconds * 2) {
            now += 500L
            bytes += kbps * 1000L / 8L / 2L
            position += 500L
            ahead = (ahead + aheadPerSecondMs / 2L).coerceAtLeast(0L)
            onSample(
                AutoSpeedupPolicy.Sample(
                    now, ahead, loading, rebuffers, bytes, fileBitrate = 54_554_000L, bufferTargetMs = 10_000L,
                    bufferedMs = position + ahead, seekAtMs = seekAt
                )
            )
            lastChange?.let { log += it }
        }
        return downloads
    }

    private fun seek() {
        position += 600_000L
        ahead = 0L
        seekAt = now + 1L
    }

    private fun steady(n: Int, stepBoost: Boolean): AutoSpeedupPolicy {
        val p = AutoSpeedupPolicy(10)
        p.stepBoost = stepBoost
        p.preset(n)
        ahead = 9_000L
        p.play(12, 120_000, loading = false)
        assertEquals(n, p.downloads)
        return p
    }

    @Test
    fun `a server with no history is boosted two requests at a time while the buffer falls`() {
        val p = steady(2, stepBoost = true)
        seek()
        assertEquals(4, p.play(1, 0))
        assertTrue(log.last(), log.last().startsWith("downloads 2->4 boost seek base=2") && log.last().contains("to=8"))
        assertEquals(6, p.play(2, 20_000))
        assertEquals(8, p.play(2, 20_000))
    }

    @Test
    fun `steps stop once the buffer grows`() {
        val p = steady(2, stepBoost = true)
        seek()
        assertEquals(4, p.play(1, 0))
        assertEquals(4, p.play(4, 120_000, aheadPerSecondMs = 1_500))
    }

    @Test
    fun `a 429 at eight in flight holds the server to seven at once and for the next boost`() {
        val p = steady(2, stepBoost = false)
        seek()
        assertEquals(8, p.play(1, 0))
        // 10:29:52 cee10: 429 with 8 in flight.
        p.onRateLimited(p.limitAfterRefusal(inFlight = 8))
        assertEquals(7, p.play(1, 0))
        assertTrue(log.any { it.startsWith("downloads 8->7 too many requests limit=7") })
        // The back-off empties the buffer: that is not a slow line, so no try adds one.
        assertEquals(7, p.play(12, 5_000))
        assertTrue(log.none { it.contains("trial") })
        assertEquals(7, p.requestLimit)
    }

    @Test
    fun `the limit is never below the usual start`() {
        val p = steady(2, stepBoost = false)
        val usual = AutoSpeedupPolicy.startDownloads(54_554_000L, 10, p.pieceBytes)
        assertEquals(maxOf(usual, 1), p.limitAfterRefusal(inFlight = 1))
        assertEquals(maxOf(usual, 5), p.limitAfterRefusal(inFlight = 6))
    }

    @Test
    fun `a server's limit is remembered for its next films and eases back after half an hour each`() {
        HostSpeeds.noteLimit("cee.cdn.example.com", 7, nowMs = 1_000L)
        // 10:31:53: refused again at 6 in flight.
        HostSpeeds.noteLimit("cee.cdn.example.com", 5, nowMs = 2_000L)
        HostSpeeds.noteLimit("cee.cdn.example.com", 7, nowMs = 3_000L)
        assertEquals(5, HostSpeeds.limitOf("cee.cdn.example.com", nowMs = 3_000L))
        assertEquals(6, HostSpeeds.limitOf("cee.cdn.example.com", nowMs = 3_000L + HostSpeeds.LIMIT_EASE_MS))
        assertNull(HostSpeeds.limitOf("cee.cdn.example.com", nowMs = 3_000L + 3 * HostSpeeds.LIMIT_EASE_MS))
        assertNull(HostSpeeds.limitOf("other.example.com", nowMs = 3_000L))
    }

    @Test
    fun `a boost never goes above the server's learned limit`() {
        val p = steady(2, stepBoost = false)
        p.requestLimit = 5
        seek()
        assertEquals(5, p.play(1, 0))
        rebuffers++
        assertEquals(5, p.play(10, 5_000))
    }
}
