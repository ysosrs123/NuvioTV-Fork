package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.DecoderCounters

internal data class PlaybackDroppedFrameSample(val count: Int?, val increasedAtMs: Long?)

/** Current renderer counters, never added to the overlapping analytics callback totals.
 * Call on the player's application thread. A renderer change begins a new counter scope.
 */
internal class PlaybackDroppedFrameSampler {
    private var previousCounters: DecoderCounters? = null
    private var previousCount: Int? = null
    private var increasedAtMs: Long? = null

    fun sample(counters: DecoderCounters?, supported: Boolean, nowMs: Long): PlaybackDroppedFrameSample {
        if (!supported || counters == null) {
            previousCounters = null
            previousCount = null
            increasedAtMs = null
            return PlaybackDroppedFrameSample(null, null)
        }
        counters.ensureUpdated()
        val count = counters.droppedBufferCount.coerceAtLeast(0)
        val previous = previousCount
        if (counters !== previousCounters || previous == null || count < previous) {
            increasedAtMs = null
        } else if (count > previous) {
            increasedAtMs = nowMs
        }
        previousCounters = counters
        previousCount = count
        return PlaybackDroppedFrameSample(count, increasedAtMs)
    }
}
