package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvNoProgressCheckTest {

    private val check = MpvNoProgressCheck()

    private fun run(
        fromMs: Long,
        toMs: Long,
        position: (Long) -> Long,
        playing: Boolean = true,
        userPaused: Boolean = false,
        pausedForCache: Boolean = false,
        seeking: Boolean = false
    ): Int {
        var fires = 0
        var now = fromMs
        while (now <= toMs) {
            if (check.step(now, position(now), playing, userPaused, pausedForCache, seeking)) fires++
            now += 500L
        }
        return fires
    }

    @Test
    fun `a resume into stuck sound fires once after six seconds`() {
        check.arm(0L)
        var firedAt = -1L
        var now = 0L
        while (now <= 20_000L) {
            if (check.step(now, 55_339L, playing = true, userPaused = false, pausedForCache = false, seeking = false)) {
                assertEquals(-1L, firedAt)
                firedAt = now
            }
            now += 500L
        }
        assertEquals(6_000L, firedAt)
    }

    @Test
    fun `normal playback never fires`() {
        check.arm(0L)
        assertEquals(0, run(0L, 30_000L, position = { 55_000L + it }))
    }

    @Test
    fun `a user pause never fires`() {
        check.arm(0L)
        assertEquals(0, run(0L, 30_000L, position = { 55_000L }, playing = false, userPaused = true))
    }

    @Test
    fun `buffering never fires`() {
        check.arm(0L)
        assertEquals(0, run(0L, 30_000L, position = { 55_000L }, pausedForCache = true))
    }

    @Test
    fun `seeking never fires`() {
        check.arm(0L)
        assertEquals(0, run(0L, 30_000L, position = { 55_000L }, seeking = true))
    }

    @Test
    fun `buffering resets the stall clock`() {
        check.arm(0L)
        assertEquals(0, run(0L, 4_000L, position = { 1_000L }))
        assertEquals(0, run(4_500L, 6_000L, position = { 1_000L }, pausedForCache = true))
        assertEquals(0, run(6_500L, 11_500L, position = { 1_000L }))
        assertTrue(check.step(12_000L, 1_000L, true, false, false, false))
    }

    @Test
    fun `a stopped progress loop starts the stall clock again`() {
        check.arm(0L)
        assertEquals(0, run(0L, 4_000L, position = { 1_000L }))
        assertFalse(check.step(9_000L, 1_000L, true, false, false, false))
        assertEquals(0, run(9_500L, 14_500L, position = { 1_000L }))
        assertTrue(check.step(15_000L, 1_000L, true, false, false, false))
    }

    @Test
    fun `a stall after the first thirty seconds is left alone`() {
        check.arm(0L)
        assertEquals(0, run(0L, 26_000L, position = { it }))
        assertEquals(0, run(26_500L, 40_000L, position = { 26_000L }))
    }

    @Test
    fun `nothing fires before a start arms it`() {
        assertEquals(0, run(0L, 30_000L, position = { 55_000L }))
    }

    @Test
    fun `a new start arms it again`() {
        check.arm(0L)
        assertEquals(1, run(0L, 10_000L, position = { 5_000L }))
        check.arm(20_000L)
        assertEquals(1, run(20_000L, 30_000L, position = { 5_000L }))
    }

    @Test
    fun `the decoded-sound retry is used once per stream and only for a bitstream`() {
        assertTrue(mpvNoProgressRetriesDecodedSound(true, "https://a/film.m2ts", emptySet()))
        assertFalse(mpvNoProgressRetriesDecodedSound(true, "https://a/film.m2ts", setOf("https://a/film.m2ts")))
        assertFalse(mpvNoProgressRetriesDecodedSound(false, "https://a/film.m2ts", emptySet()))
        assertFalse(mpvNoProgressRetriesDecodedSound(true, "", emptySet()))
    }
}
