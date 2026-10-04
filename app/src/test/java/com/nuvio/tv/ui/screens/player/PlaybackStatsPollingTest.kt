package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStatsPollingTest {
    @Test fun `slow probe does not delay immediate or one second HUD samples`() = runTest {
        val ticks = mutableListOf<Long>()
        var probeCancelled = false
        val job = launch { pollPlaybackStats(
            probe = { try { awaitCancellation() } finally { probeCancelled = true } },
            sample = { ticks += testScheduler.currentTime }) }
        runCurrent(); advanceTimeBy(4000); runCurrent()
        assertEquals(listOf(0L, 1000L, 2000L, 3000L, 4000L), ticks)
        job.cancelAndJoin()
        assertTrue(probeCancelled)
        advanceTimeBy(3000); runCurrent(); assertEquals(5, ticks.size)
    }

    @Test fun `completed probe is shared until a later unavailable result clears it`() = runTest {
        val endpoint = PlaybackEndpoint("https", "media.invalid", 443)
        val value = PlaybackConnectSample(1, endpoint, 50, 0)
        val samples = mutableListOf<PlaybackConnectSample?>()
        var probes = 0
        val job = launch { pollPlaybackStats(
            probe = { probes++; if (probes == 1) value else null },
            sample = { samples += it }) }
        runCurrent(); advanceTimeBy(4000); runCurrent()
        assertEquals(value, samples.last())
        advanceTimeBy(2000); runCurrent()
        assertNull(samples.last())
        assertEquals(2, probes)
        job.cancelAndJoin()
    }
}
