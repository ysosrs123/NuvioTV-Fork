package com.nuvio.tv.ui.v2.diagnostics

import kotlin.math.ceil

data class UiFrameSummary(
    val frames: Int,
    val jankyFrames: Int,
    val p50UiMs: Double?,
    val p95UiMs: Double?,
    val p99UiMs: Double?
) {
    val jankPercent: Double?
        get() = if (frames == 0) null else jankyFrames * 100.0 / frames
}

/** Bounded local diagnostics. No state emission, sorting or allocation in the frame callback. */
class UiFrameWindow(private val capacity: Int = 2048) {
    init { require(capacity > 0) }

    private val timestamps = LongArray(capacity)
    private val durations = LongArray(capacity)
    private val janky = BooleanArray(capacity)
    private var next = 0
    private var size = 0

    @Synchronized
    fun record(nowNanos: Long, durationUiNanos: Long, isJank: Boolean) {
        if (durationUiNanos <= 0) return
        timestamps[next] = nowNanos
        durations[next] = durationUiNanos
        janky[next] = isJank
        next = (next + 1) % capacity
        size = minOf(size + 1, capacity)
    }

    @Synchronized
    fun clear() {
        next = 0
        size = 0
    }

    @Synchronized
    fun snapshot(nowNanos: Long, maxAgeNanos: Long = 30_000_000_000L): UiFrameSummary {
        require(maxAgeNanos >= 0)
        val values = LongArray(size)
        var count = 0
        var jankCount = 0
        for (index in 0 until size) {
            val age = nowNanos - timestamps[index]
            if (age in 0..maxAgeNanos) {
                values[count++] = durations[index]
                if (janky[index]) jankCount++
            }
        }
        values.sort(0, count)
        fun percentile(p: Double): Double? = if (count == 0) null else
            values[(ceil(count * p).toInt() - 1).coerceAtLeast(0)] / 1_000_000.0
        return UiFrameSummary(count, jankCount, percentile(0.5), percentile(0.95), percentile(0.99))
    }
}

/** Shared with the existing Activity JankStats listener; never samples the player separately. */
object UiFrameDiagnostics {
    val frames = UiFrameWindow()
}
