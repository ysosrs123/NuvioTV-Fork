package com.nuvio.tv.ui.screens.player

/**
 * Tells a slow connection, which a fresh one replaces, from a slow source, which no restart speeds up.
 * Once a restarted piece arrives no faster than the one it replaced, pieces of this session are only
 * restarted when they fall well below the rate the source keeps.
 */
internal class SteadySlowSource {
    @Volatile
    var rateBps: Long = 0L
        private set

    val known: Boolean get() = rateBps > 0L

    fun stallRateBps(normalBps: Long): Long = if (known) minOf(normalBps, rateBps / STALL_DIVISOR) else normalBps

    /** [rateBps] is the first slow window of a piece restarted after a window at [previousRateBps]. */
    fun noteAfterRestart(previousRateBps: Long, rateBps: Long): Boolean {
        if (previousRateBps < MOVING_BPS || rateBps < MOVING_BPS) return false
        this.rateBps = minOf(previousRateBps, rateBps)
        return true
    }

    companion object {
        /** Below this a connection counts as hung, not slow. */
        const val MOVING_BPS = 16L * 1024L
        const val STALL_DIVISOR = 4L
        /** A reader keeps waiting on a slow piece while its bytes arrive at least this often. */
        const val QUIET_MS = 5_000L
    }
}
