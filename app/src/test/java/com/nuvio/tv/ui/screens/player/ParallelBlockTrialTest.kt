package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ParallelBlockTrialTest {
    private val spec: DataSpec by lazy { DataSpec.Builder().setUri(mockk<Uri>(relaxed = true)).build() }

    private fun refused(code: Int) =
        IOException(HttpDataSource.InvalidResponseCodeException(code, "x", null, emptyMap(), spec, byteArrayOf()))

    private val mb = 1024L * 1024L
    private val film = 300L * mb

    /** Three downloads served side by side, then all three refused within a moment of each other at 60 s. */
    private fun blockedAfterThree(t: ParallelBlockTrial): ParallelBlockTrial.Verdict {
        val first = t.failed(running = 3, served = 3, startedAtMs = 58_000L, beside = 2, block = true, bytes = film, nowMs = 60_000L)
        assertEquals(ParallelBlockTrial.Verdict.NONE,
            t.failed(running = 2, served = 3, startedAtMs = 58_500L, beside = 2, block = true, bytes = film, nowMs = 60_020L))
        assertEquals(ParallelBlockTrial.Verdict.NONE,
            t.failed(running = 1, served = 3, startedAtMs = 59_000L, beside = 1, block = true, bytes = film, nowMs = 60_040L))
        return first
    }

    @Test fun `served three, all refused, then one download carries again is learnt`() {
        val t = ParallelBlockTrial()
        assertEquals(ParallelBlockTrial.Verdict.TRYING, blockedAfterThree(t))
        assertTrue(t.trying)
        assertEquals(3, t.trialRunning)
        // The immediate retries, sent side by side, are refused again: still the block.
        assertEquals(ParallelBlockTrial.Verdict.NONE,
            t.failed(running = 2, served = 3, startedAtMs = 60_100L, beside = 1, block = true, bytes = film, nowMs = 60_300L))
        assertEquals(ParallelBlockTrial.Verdict.NONE, t.progress(bytes = film + mb, running = 1, nowMs = 65_000L))
        assertEquals(ParallelBlockTrial.Verdict.LEARNT, t.progress(bytes = film + 5 * mb, running = 1, nowMs = 70_000L))
        assertFalse(t.trying)
    }

    @Test fun `an outage where the lone download fails too teaches nothing`() {
        val t = ParallelBlockTrial()
        assertEquals(ParallelBlockTrial.Verdict.TRYING, blockedAfterThree(t))
        assertEquals(ParallelBlockTrial.Verdict.CLEARED,
            t.failed(running = 1, served = 0, startedAtMs = 61_000L, beside = 0, block = false, bytes = film, nowMs = 71_000L))
        assertFalse(t.trying)
        assertEquals(ParallelBlockTrial.Verdict.NONE, t.progress(bytes = film + 50 * mb, running = 1, nowMs = 120_000L))
    }

    @Test fun `an expired link refused on the single retry teaches nothing`() {
        val t = ParallelBlockTrial()
        assertEquals(ParallelBlockTrial.Verdict.TRYING, blockedAfterThree(t))
        assertEquals(ParallelBlockTrial.Verdict.CLEARED,
            t.failed(running = 1, served = 0, startedAtMs = 61_000L, beside = 0, block = true, bytes = film, nowMs = 61_200L))
    }

    @Test fun `a server serving as many again was an outage`() {
        val t = ParallelBlockTrial()
        blockedAfterThree(t)
        assertEquals(ParallelBlockTrial.Verdict.CLEARED, t.progress(bytes = film + 8 * mb, running = 3, nowMs = 75_000L))
    }

    @Test fun `no recovery within the trial teaches nothing`() {
        val t = ParallelBlockTrial(trialMs = 90_000L)
        blockedAfterThree(t)
        assertEquals(ParallelBlockTrial.Verdict.CLEARED, t.progress(bytes = film + 8 * mb, running = 1, nowMs = 150_000L))
    }

    @Test fun `a start, a lone download or the box's own network never opens a trial`() {
        val t = ParallelBlockTrial()
        // First start on a server: nothing served yet.
        assertEquals(ParallelBlockTrial.Verdict.NONE,
            t.failed(running = 3, served = 0, startedAtMs = 0L, beside = 2, block = true, bytes = 0L, nowMs = 500L))
        // One download running.
        assertEquals(ParallelBlockTrial.Verdict.NONE,
            t.failed(running = 1, served = 3, startedAtMs = 0L, beside = 0, block = true, bytes = film, nowMs = 60_000L))
        // Address not found or not reached.
        assertEquals(ParallelBlockTrial.Verdict.NONE,
            t.failed(running = 3, served = 3, startedAtMs = 0L, beside = 2, block = false, bytes = film, nowMs = 60_000L))
        assertFalse(t.trying)
    }

    @Test fun `refusals, refused connections and read time-outs are blocks, the box's network is not`() {
        assertTrue(ExtraDownloadRefusal.isBlock(refused(403)))
        assertTrue(ExtraDownloadRefusal.isBlock(IOException(SocketException("Connection reset"))))
        assertTrue(ExtraDownloadRefusal.isBlock(IOException(ConnectException("failed to connect: ECONNREFUSED (Connection refused)"))))
        assertTrue(ExtraDownloadRefusal.isBlock(IOException(SocketTimeoutException("timeout"))))
        assertFalse(ExtraDownloadRefusal.isBlock(IOException(SocketTimeoutException("failed to connect to x after 10000ms"))))
        assertFalse(ExtraDownloadRefusal.isBlock(IOException(UnknownHostException("x"))))
        assertFalse(ExtraDownloadRefusal.isBlock(IOException(ConnectException("Network is unreachable"))))
        assertFalse(ExtraDownloadRefusal.isBlock(refused(404)))
    }

    @Test fun `pieces served side by side are counted over the last half minute`() {
        assertEquals(2, ExtraDownloadRefusal.servedRecently(mapOf(1L to 50_000L, 2L to 59_000L, 3L to 10_000L), nowMs = 60_000L))
    }

    private val day = 24L * 60 * 60 * 1000

    @Test fun `a block holds a day at first, doubling up to a week`() {
        assertEquals(day, HostSpeeds.blockHoldMs(1))
        assertEquals(2 * day, HostSpeeds.blockHoldMs(2))
        assertEquals(4 * day, HostSpeeds.blockHoldMs(3))
        assertEquals(7 * day, HostSpeeds.blockHoldMs(4))
        assertEquals(7 * day, HostSpeeds.blockHoldMs(9))
    }

    @Test fun `a held block stays at one for its hold, then eases like any limit`() {
        val held = HostSpeeds.Limit(1, atMs = 0L, blocks = true, strikes = 1, blockAtMs = 0L)
        assertTrue(HostSpeeds.blockHeld(held, day - 1))
        assertEquals(1, HostSpeeds.easedLimit(held, day - 1))
        assertFalse(HostSpeeds.blockHeld(held, day))
        assertEquals(2, HostSpeeds.easedLimit(held, day + HostSpeeds.LIMIT_EASE_MS))
        assertNull(HostSpeeds.easedLimit(held, day + HostSpeeds.LIMIT_EASE_MS * AutoSpeedup.MAX_IN_FLIGHT))
        // "Too many requests" keeps its half-hour easing.
        assertEquals(2, HostSpeeds.easedLimit(HostSpeeds.Limit(1, atMs = 0L), HostSpeeds.LIMIT_EASE_MS))
    }

    /** Learns a block at [atMs] on top of [old], as [HostSpeeds.noteLimit] keeps it. */
    private fun block(old: HostSpeeds.Limit?, atMs: Long) = HostSpeeds.merged(old, limit = 1, nowMs = atMs, blocks = true)!!

    @Test fun `each block after a hold ran out holds twice as long, a week at most`() {
        var l = block(null, 0L)
        assertEquals(1, l.strikes)
        assertFalse(HostSpeeds.blockHeld(l, day))
        // Automatic tries parallel again after the hold and the server blocks again.
        l = block(l, day + 3 * 60 * 60 * 1000L)
        assertEquals(2, l.strikes)
        assertTrue(HostSpeeds.blockHeld(l, l.atMs + 2 * day - 1))
        assertFalse(HostSpeeds.blockHeld(l, l.atMs + 2 * day))
        l = block(l, l.atMs + 2 * day + 1_000L)
        assertEquals(3, l.strikes)
        assertEquals(4 * day, HostSpeeds.blockHoldMs(l.strikes))
        l = block(l, l.atMs + 4 * day + 1_000L)
        assertEquals(4, l.strikes)
        assertEquals(7 * day, HostSpeeds.blockHoldMs(l.strikes))
        l = block(l, l.atMs + 7 * day + 1_000L)
        assertEquals(7 * day, HostSpeeds.blockHoldMs(l.strikes))
    }

    @Test fun `fourteen clean days after the hold forget the blocks`() {
        val second = block(block(null, 0L), day + 1_000L)
        assertEquals(2, second.strikes)
        val holdEnd = second.atMs + 2 * day
        assertEquals(3, block(second, holdEnd + HostSpeeds.BLOCK_CLEAN_MS - 1).strikes)
        assertEquals(1, block(second, holdEnd + HostSpeeds.BLOCK_CLEAN_MS).strikes)
    }

    @Test fun `a softer refusal leaves the block verdict as it is and keeps the count`() {
        val held = block(null, 0L)
        assertNull(HostSpeeds.merged(held, limit = 2, nowMs = 1_000L, blocks = false))
        // After the hold a "too many requests" note replaces the limit but the count of blocks stays.
        val soft = HostSpeeds.merged(held, limit = 2, nowMs = day + 1_000L, blocks = false)!!
        assertFalse(soft.blocks)
        assertEquals(1, soft.strikes)
        assertEquals(2, block(soft, day + 60_000L).strikes)
        assertEquals(1, block(HostSpeeds.Limit(3, atMs = 0L), 9_000L).strikes)
    }

    @Test fun `the block verdict and its count are saved and read back, older entries still read`() {
        val held = HostSpeeds.Limit(1, atMs = 123L, blocks = true, strikes = 3, blockAtMs = 123L)
        assertEquals(held, HostSpeeds.decodeLimit(HostSpeeds.encodeLimit(held)))
        val soft = HostSpeeds.Limit(2, atMs = 900L, strikes = 2, blockAtMs = 100L)
        assertEquals(soft, HostSpeeds.decodeLimit(HostSpeeds.encodeLimit(soft)))
        assertEquals(HostSpeeds.Limit(2, 456L), HostSpeeds.decodeLimit("2,456"))
    }
}
