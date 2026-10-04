package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackTransferRateSamplerTest {
    private fun sample(bytes: Long, ms: Long, id: Long = 1, coverage: PlaybackTransferCoverage = PlaybackTransferCoverage.PROGRESSIVE) =
        PlaybackTransferSnapshot(id, ms, coverage, bytes, bytes, null, null)

    @Test fun `first sample is unavailable then wall-time delivery is measured`() {
        val rate = PlaybackTransferRateSampler()
        assertNull(rate.sample(sample(800, 0)).currentBps)
        assertEquals(8000.0, rate.sample(sample(1800, 1000)).currentBps!!, 0.0)
    }
    @Test fun `zero means no counted bytes and does not retain an old positive rate`() {
        val rate = PlaybackTransferRateSampler()
        rate.sample(sample(0, 0)); rate.sample(sample(1000, 1000))
        assertEquals(0.0, rate.sample(sample(1000, 2000)).currentBps!!, 0.0)
    }
    @Test fun `short refill across windows retains sampled average peak bytes and age origin`() {
        val rate = PlaybackTransferRateSampler()
        rate.sample(sample(0, 0))
        rate.sample(sample(1000, 1000)); rate.sample(sample(4000, 2000))
        val burst = rate.sample(sample(4000, 3000)).lastBurst!!
        assertEquals(4000L, burst.bytes)
        assertEquals(16000.0, burst.averageBps, 0.0)
        assertEquals(24000.0, burst.peakBps, 0.0)
        assertEquals(2000L, burst.endedAtMs)
        assertEquals(burst, rate.sample(sample(4000, 4000)).lastBurst)
    }
    @Test fun `variable sample duration uses elapsed time without loader or pause gating`() {
        val rate = PlaybackTransferRateSampler(); rate.sample(sample(0, 0))
        assertEquals(8000.0, rate.sample(sample(250, 250)).currentBps!!, 0.0)
        assertEquals(8000.0, rate.sample(sample(2500, 2500)).currentBps!!, 0.0)
    }
    @Test fun `tiny window accumulates until meaningful interval`() {
        val rate = PlaybackTransferRateSampler(); rate.sample(sample(0, 0))
        assertNull(rate.sample(sample(500, 10)).currentBps)
        assertEquals(8000.0, rate.sample(sample(1000, 1000)).currentBps!!, 0.0)
    }
    @Test fun `session replacement counter reset and long gap cannot spike or reuse last burst`() {
        for (next in listOf(sample(900000, 3000, id = 2), sample(0, 3000), sample(900000, 10000), sample(100, 1))) {
            val rate = PlaybackTransferRateSampler()
            rate.sample(sample(0, 0)); rate.sample(sample(100, 1000)); rate.sample(sample(100, 2000))
            val result = rate.sample(next)
            assertNull(result.currentBps); assertNull(result.lastBurst)
        }
    }
    @Test fun `unsupported route is unavailable and partial route retains explicit scope`() {
        val rate = PlaybackTransferRateSampler()
        assertNull(rate.sample(sample(0, 0, coverage = PlaybackTransferCoverage.UNAVAILABLE)).currentBps)
        assertNull(rate.sample(sample(100, 1000, coverage = PlaybackTransferCoverage.UNAVAILABLE)).currentBps)
        rate.sample(sample(0, 2000, coverage = PlaybackTransferCoverage.CHUNKED_PARTIAL))
        val result = rate.sample(sample(1000, 3000, coverage = PlaybackTransferCoverage.CHUNKED_PARTIAL))
        assertEquals(PlaybackTransferCoverage.CHUNKED_PARTIAL, result.sample.coverage)
        assertEquals(8000.0, result.currentBps!!, 0.0)
    }
    @Test fun `large byte delta uses floating conversion without long multiplication overflow`() {
        val rate = PlaybackTransferRateSampler(); rate.sample(sample(0, 0))
        val bps = rate.sample(sample(Long.MAX_VALUE, 1000)).currentBps!!
        assertTrue(bps > 0); assertTrue(bps.isFinite())
    }
}
