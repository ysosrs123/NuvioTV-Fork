package com.nuvio.tv.ui.screens.player

internal data class PlaybackTransferBurst(val bytes: Long, val durationMs: Long, val peakBps: Double, val endedAtMs: Long) {
    val averageBps: Double get() = bytes.toDouble() * 8000.0 / durationMs
}
internal data class PlaybackTransferRate(val currentBps: Double?, val lastBurst: PlaybackTransferBurst?, val sample: PlaybackTransferSnapshot)

/** HUD-window delivery bursts, not LoadControl cycles or independent link-capacity measurements. */
internal class PlaybackTransferRateSampler {
    private var previous: PlaybackTransferSnapshot? = null
    private var burstBytes = 0L
    private var burstMs = 0L
    private var burstPeak = 0.0
    private var burstEnd = 0L
    private var lastBurst: PlaybackTransferBurst? = null

    fun sample(now: PlaybackTransferSnapshot): PlaybackTransferRate {
        val old = previous
        if (!now.coverage.available || old == null || old.sessionId != now.sessionId || old.coverage != now.coverage ||
            now.networkBytes < old.networkBytes || now.sampledAtMs <= old.sampledAtMs ||
            now.sampledAtMs - old.sampledAtMs > 5000L) {
            previous = now
            resetBurst()
            lastBurst = null
            return PlaybackTransferRate(null, null, now)
        }
        val elapsed = now.sampledAtMs - old.sampledAtMs
        // Two consumers or closely spaced UI ticks must not turn a tiny interval into a spike.
        if (elapsed < 250L) return PlaybackTransferRate(null, lastBurst, now)
        previous = now
        val bytes = now.networkBytes - old.networkBytes
        val bps = bytes.toDouble() * 8000.0 / elapsed
        if (bytes > 0) {
            burstBytes = if (Long.MAX_VALUE - burstBytes < bytes) Long.MAX_VALUE else burstBytes + bytes
            burstMs += elapsed
            burstPeak = maxOf(burstPeak, bps)
            burstEnd = now.sampledAtMs
        } else if (burstBytes > 0) {
            lastBurst = PlaybackTransferBurst(burstBytes, burstMs, burstPeak, burstEnd)
            resetBurst()
        }
        return PlaybackTransferRate(bps, lastBurst, now)
    }

    private fun resetBurst() { burstBytes = 0; burstMs = 0; burstPeak = 0.0; burstEnd = 0 }
}
