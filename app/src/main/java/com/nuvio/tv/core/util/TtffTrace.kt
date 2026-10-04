package com.nuvio.tv.core.util

import android.os.SystemClock
import android.util.Log

/**
 * Time-to-first-frame stage trace. Logs elapsed marks under one tag
 * (`adb logcat -s TTFF_STAGE`) across the stream screen and the player.
 *
 * begin() resets the clock: on stream screen entry, and again on a manual
 * stream selection. t0ElapsedMsOrNull() carries that start into the player's
 * launchStartedAtMs. mirror() copies the player's STARTUP_STAGE /
 * PLAYBACK_STARTUP report lines to logcat under the same tag.
 * Logging only.
 */
object TtffTrace {
    private const val TAG = "TTFF_STAGE"

    @Volatile private var t0ElapsedMs: Long = 0L
    @Volatile private var lastMarkElapsedMs: Long = 0L

    fun begin(name: String) {
        val now = SystemClock.elapsedRealtime()
        t0ElapsedMs = now
        lastMarkElapsedMs = now
        Log.i(TAG, "[$name] t=0 ms (trace begin)")
    }

    fun mark(name: String) {
        val now = SystemClock.elapsedRealtime()
        if (t0ElapsedMs == 0L) {
            begin(name)
            return
        }
        Log.i(TAG, "[$name] t=+${now - t0ElapsedMs} ms (step +${now - lastMarkElapsedMs} ms)")
        lastMarkElapsedMs = now
    }

    /** T0 of the current run in SystemClock.elapsedRealtime() terms, if any. */
    fun t0ElapsedMsOrNull(): Long? = t0ElapsedMs.takeIf { it > 0L }

    /**
     * Mirror a pre-formatted diagnostics line to logcat under this tag. Report-buffer
     * lines can carry embedded newlines (Emby supplies multi-line stream names); a raw
     * newline splits the logcat entry and the continuation loses the tag, which strands
     * fragments like a bare "2160p" outside any TTFF_STAGE filter. Collapse any CR/LF
     * runs to a single space so one mirrored line is always one logcat line.
     */
    fun mirror(line: String) {
        Log.i(TAG, line.replace(NEWLINE_RUNS, " "))
    }

    private val NEWLINE_RUNS = Regex("[\r\n]+")
}
