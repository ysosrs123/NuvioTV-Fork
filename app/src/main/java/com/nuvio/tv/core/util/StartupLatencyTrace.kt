package com.nuvio.tv.core.util

import android.os.SystemClock
import android.util.Log

/**
 * Lightweight startup-latency trace for task 2.13 (profile-select to main UI).
 * Logs elapsed-time marks under a single tag so one logcat filter yields the
 * full attribution chain:
 *
 *   adb logcat -s NUVIO_STARTUP_TRACE
 *
 * begin() resets the clock; mark() logs the total since begin and the step
 * since the previous mark. Marks fired outside a profile-switch run (e.g. a
 * routine foreground settings pull) log against the stale start time and are
 * recognisable by their large t values. Logging only; no behavioural effect.
 */
object StartupLatencyTrace {
    private const val TAG = "NUVIO_STARTUP_TRACE"

    @Volatile private var traceStartElapsedMs: Long = 0L
    @Volatile private var lastMarkElapsedMs: Long = 0L

    fun begin(name: String) {
        val now = SystemClock.elapsedRealtime()
        traceStartElapsedMs = now
        lastMarkElapsedMs = now
        Log.i(TAG, "[$name] t=0 ms (trace begin)")
    }

    fun mark(name: String) {
        val now = SystemClock.elapsedRealtime()
        if (traceStartElapsedMs == 0L) {
            begin(name)
            return
        }
        Log.i(TAG, "[$name] t=+${now - traceStartElapsedMs} ms (step +${now - lastMarkElapsedMs} ms)")
        lastMarkElapsedMs = now
    }
}
