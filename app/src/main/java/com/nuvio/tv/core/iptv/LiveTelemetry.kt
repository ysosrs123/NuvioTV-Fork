package com.nuvio.tv.core.iptv

/** Per-acquisition measurements. Monotonic milliseconds are supplied by the adapter. */
class LiveTelemetry {
    private var startedAt: Long? = null
    private var firstFrameAt: Long? = null
    private var bufferStartedAt: Long? = null
    private var bufferTotal = 0L
    private var stalls = 0
    private var bytes = 0L
    private var lastSampleAt: Long? = null
    private var lastSampleBytes = 0L
    @Synchronized fun start(now: Long) { check(startedAt == null); startedAt = now }
    @Synchronized fun firstFrame(now: Long) { if (startedAt != null && firstFrameAt == null) firstFrameAt = now }
    @Synchronized fun buffering(active: Boolean, now: Long) {
        // Initial preparation is tune latency, not a rebuffer. Duplicate callbacks add nothing.
        if (active && firstFrameAt != null && bufferStartedAt == null) { bufferStartedAt = now; stalls++ }
        if (!active) bufferStartedAt?.let { bufferTotal += (now - it).coerceAtLeast(0); bufferStartedAt = null }
    }
    @Synchronized fun transferred(count: Int) { if (count > 0) bytes += count }
    @Synchronized fun sample(now: Long): LiveTelemetrySample {
        val elapsed = lastSampleAt?.let { now - it }
        val rate = elapsed?.takeIf { it > 0 }?.let { (bytes - lastSampleBytes) * 8000.0 / it }
        lastSampleAt = now; lastSampleBytes = bytes
        return LiveTelemetrySample(bytes, rate, firstFrameAt?.let { frame -> startedAt?.let { (frame-it).coerceAtLeast(0) } },
            stalls, bufferTotal + (bufferStartedAt?.let { (now-it).coerceAtLeast(0) } ?: 0))
    }
}
data class LiveTelemetrySample(val bytes: Long, val bitsPerSecond: Double?, val firstFrameMs: Long?,
    val rebuffers: Int, val rebufferMs: Long)
