package com.nuvio.tv.core.player.thumbnail

/** Network failures in a row: a growing wait before the next try, and when to stop trying. A success resets it. */
internal class FetchFailureStreak(
    private val giveUpAfter: Int,
    private val firstWaitMs: Long = 2_000L,
    private val maxWaitMs: Long = 60_000L,
) {
    var count = 0
        private set

    private var lastCountedAtMs = Long.MIN_VALUE

    val gaveUp: Boolean get() = count >= giveUpAfter

    /** Counts a failure and returns how long to wait before the next try. */
    fun failed(): Long {
        count++
        return waitMs(count)
    }

    /**
     * A fetch started at [startedAtMs] failed. One that was already under way when the last failure was counted
     * failed for the same cause: not counted again, null is returned.
     */
    fun failedFetch(startedAtMs: Long, nowMs: Long): Long? {
        if (startedAtMs <= lastCountedAtMs) return null
        lastCountedAtMs = nowMs
        return failed()
    }

    fun succeeded() {
        count = 0
    }

    fun waitMs(failures: Int): Long {
        if (failures <= 0) return 0L
        return (firstWaitMs shl (failures - 1).coerceAtMost(20)).coerceAtMost(maxWaitMs)
    }
}
