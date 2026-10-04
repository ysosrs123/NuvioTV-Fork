package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.DecoderCounters
import org.junit.Assert.*
import org.junit.Test

class PlaybackDroppedFrameSamplerTest {
    private fun counters(count: Int) = DecoderCounters().apply { droppedBufferCount = count }
    @Test fun `live values below callback threshold remain visible`() {
        val sampler = PlaybackDroppedFrameSampler()
        val counter = counters(0)
        assertEquals(0, sampler.sample(counter, true, 0).count)
        for (count in listOf(1, 2, 17, 49, 50, 51)) {
            counter.droppedBufferCount = count
            val sample = sampler.sample(counter, true, count.toLong())
            assertEquals(count, sample.count)
            assertEquals(count.toLong(), sample.increasedAtMs)
        }
        assertEquals(51L, sampler.sample(counter, true, 100).increasedAtMs)
    }
    @Test fun `seeks retaining renderer preserve its cumulative count`() {
        val sampler = PlaybackDroppedFrameSampler()
        val counter = counters(8)
        sampler.sample(counter, true, 0)
        assertEquals(8, sampler.sample(counter, true, 1000).count)
    }
    @Test fun `replacement renderer starts its own scope without stale warning`() {
        val sampler = PlaybackDroppedFrameSampler()
        sampler.sample(counters(49), true, 0)
        val replacement = sampler.sample(counters(1), true, 100)
        assertEquals(1, replacement.count)
        assertNull(replacement.increasedAtMs)
    }
    @Test fun `counter reset clears recent increase`() {
        val sampler = PlaybackDroppedFrameSampler()
        val counter = counters(0)
        sampler.sample(counter, true, 0)
        counter.droppedBufferCount = 2
        sampler.sample(counter, true, 10)
        counter.droppedBufferCount = 0
        assertNull(sampler.sample(counter, true, 20).increasedAtMs)
    }
    @Test fun `native missing and disabled counters are unavailable`() {
        val sampler = PlaybackDroppedFrameSampler()
        val counter = counters(10)
        sampler.sample(counter, true, 0)
        assertNull(sampler.sample(counter, false, 10).count)
        assertNull(sampler.sample(null, true, 20).count)
        assertNull(sampler.sample(counter, true, 30).increasedAtMs)
    }
}
