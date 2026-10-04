package com.nuvio.tv.core.performance

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.FrameMetrics
import android.view.Window

/** Opt-in, bounded diagnostics. No titles, IDs, URLs or credentials are recorded. */
object DetailEntryTrace {
    private const val TAG = "NuvioDetails"
    private var started = 0L
    private var journey = 0
    private var frames = 0
    private val seen = mutableSetOf<String>()
    fun enabled() = Log.isLoggable(TAG, Log.VERBOSE)
    fun begin() {
        if (!enabled()) return
        started = SystemClock.elapsedRealtimeNanos()
        journey++
        frames = 0
        seen.clear()
        mark("home_selection")
    }
    fun mark(stage: String, once: Boolean = false) {
        if (!enabled() || started == 0L) return
        if (once && !seen.add(stage)) return
        val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        if (elapsed > 8000) return
        Trace.beginSection("Details:$stage")
        Log.i(TAG, "journey=$journey stage=$stage elapsedMs=$elapsed thread=${Thread.currentThread().name}")
        Trace.endSection()
    }
    fun attach(window: Window) {
        if (!enabled()) return
        window.addOnFrameMetricsAvailableListener({ _, metrics, dropped ->
            if (started != 0L && frames < 120 && SystemClock.elapsedRealtimeNanos() - started < 8_000_000_000L) {
                frames++
                Log.i(TAG, "journey=$journey frame=$frames totalNs=${metrics.getMetric(FrameMetrics.TOTAL_DURATION)} " +
                    "layoutNs=${metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)} " +
                    "drawNs=${metrics.getMetric(FrameMetrics.DRAW_DURATION)} " +
                    "syncNs=${metrics.getMetric(FrameMetrics.SYNC_DURATION)} dropped=$dropped")
            }
        }, Handler(Looper.getMainLooper()))
    }
}
