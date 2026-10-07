package com.nuvio.tv.core.iptv

enum class LiveStartBuffer(val millis: Int) { FAST(1_000), NORMAL(2_500), SAFE(5_000) }

enum class LiveCushion(val seconds: Int) { OFF(0), SECONDS_10(10), SECONDS_20(20), SECONDS_30(30), SECONDS_60(60) }

data class LiveBufferPlan(val startMs: Int, val rebufferMs: Int, val minMs: Int, val maxMs: Int, val targetBytes: Int, val cushionMs: Long)

object LiveBufferPolicy {
    const val LOW_MEMORY_BYTES = 16 * 1024 * 1024
    const val NORMAL_BYTES = 48 * 1024 * 1024
    const val LOW_MEMORY_TOTAL = 2_800L * 1024 * 1024
    const val MARGIN_MS = 10_000
    const val PLAIN_MIN_MS = 1_500
    const val PLAIN_MAX_MS = 8_000
    const val HLS_HEAD_MS = 4_000L
    const val SLOW_SPEED = 0.97f
    private const val BYTES_FILL = 0.75

    fun lowMemory(totalBytes: Long, lowRamDevice: Boolean): Boolean = lowRamDevice || totalBytes in 1 until LOW_MEMORY_TOTAL

    fun capBytes(lowMemory: Boolean, heapBytes: Long): Int {
        val cap = if (lowMemory) LOW_MEMORY_BYTES else NORMAL_BYTES
        val heap = if (heapBytes > 0) (heapBytes / 5).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else cap
        return minOf(cap, heap).coerceAtLeast(LOW_MEMORY_BYTES / 2)
    }

    fun plan(start: LiveStartBuffer, cushion: LiveCushion, capBytes: Int, defaultBytes: Int): LiveBufferPlan {
        val rebuffer = start.millis * 2
        if (cushion == LiveCushion.OFF) {
            val min = maxOf(PLAIN_MIN_MS, rebuffer)
            return LiveBufferPlan(start.millis, rebuffer, min, maxOf(min, PLAIN_MAX_MS), defaultBytes, 0)
        }
        val cushionMs = cushion.seconds * 1_000
        val max = maxOf(cushionMs + MARGIN_MS, rebuffer)
        return LiveBufferPlan(start.millis, rebuffer, max, max, maxOf(capBytes, defaultBytes), cushionMs.toLong())
    }

    fun targetMs(cushionMs: Long, bitsPerSecond: Double?, capBytes: Int): Long {
        if (cushionMs <= 0) return 0
        val rate = bitsPerSecond?.takeIf { it > 0 } ?: return cushionMs
        val fits = (capBytes * 8.0 * 1_000 * BYTES_FILL / rate).toLong()
        return minOf(cushionMs, fits).coerceAtLeast(0)
    }

    fun hlsStartMs(defaultMs: Long, windowMs: Long, cushionMs: Long): Long? {
        if (cushionMs <= 0 || defaultMs <= 0 || windowMs <= 0) return null
        val floor = minOf(maxOf(HLS_HEAD_MS, windowMs / 4), defaultMs)
        val start = maxOf(defaultMs - cushionMs, floor)
        return start.takeIf { it < defaultMs }
    }
}

class LiveCushionSpeed(private val slow: Float = LiveBufferPolicy.SLOW_SPEED) {
    var speed = 1f
        private set

    fun update(bufferedMs: Long, targetMs: Long): Float {
        speed = when {
            targetMs <= 0 || bufferedMs >= targetMs -> 1f
            bufferedMs < targetMs - hysteresis(targetMs) -> slow
            else -> speed
        }
        return speed
    }

    fun reset() { speed = 1f }

    private fun hysteresis(targetMs: Long) = maxOf(MIN_HYSTERESIS_MS, targetMs / 5)

    private companion object { const val MIN_HYSTERESIS_MS = 2_000L }
}

class LiveHolds<T : Any> {
    private val holders = HashSet<T>()

    @Synchronized fun acquire(holder: T): Boolean = holders.add(holder) && holders.size == 1

    @Synchronized fun release(holder: T): Boolean = holders.remove(holder) && holders.isEmpty()

    @Synchronized fun held(): Boolean = holders.isNotEmpty()
}
