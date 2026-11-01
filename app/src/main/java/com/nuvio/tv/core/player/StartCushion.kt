package com.nuvio.tv.core.player

/**
 * The first start of a heavy film waits for [HEAVY_START_MS] of film, or for a buffer that is full by bytes when
 * that holds less, or for a buffer that has stopped growing (memory lent to downloads or a low-memory cap can stop
 * loading short of the cushion). Starts after a seek or a rebuffer keep the user's own thresholds.
 */
class StartCushion(
    val userStartMs: Int,
    private val maxBufferMs: Int = Int.MAX_VALUE,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    @Volatile
    private var cushionUs = 0L

    @Volatile
    private var pending = false

    @Volatile
    private var lastBufferedUs = -1L

    @Volatile
    private var lastGrowthAtMs = 0L

    /** Set for each stream before it is prepared. Returns the start threshold in ms, 0 when the cushion is off. */
    fun arm(heavy: Boolean): Int {
        val startMs = minOf(HEAVY_START_MS, maxBufferMs).takeIf { heavy && it > userStartMs } ?: 0
        cushionUs = startMs * 1000L
        pending = startMs > 0
        lastBufferedUs = -1L
        return startMs
    }

    val armed: Boolean get() = pending

    /**
     * Gates the load control's own answer [base]. [targetBytes] of 0 or less means the target is not known yet.
     */
    fun shouldStart(
        base: Boolean,
        rebuffering: Boolean,
        bufferedDurationUs: Long,
        allocatedBytes: Long,
        targetBytes: Long,
    ): Boolean {
        if (!base) return false
        if (!pending || rebuffering) return true
        val now = clock()
        if (bufferedDurationUs > lastBufferedUs) {
            lastBufferedUs = bufferedDurationUs
            lastGrowthAtMs = now
        }
        val stalled = now - lastGrowthAtMs >= STALLED_MS
        val full = targetBytes > 0L && allocatedBytes >= targetBytes - FULL_SLACK_BYTES
        val start = bufferedDurationUs >= cushionUs || full || stalled
        if (start) pending = false
        return start
    }

    companion object {
        const val HEAVY_START_MS = 6_000
        /** The buffer has not grown for this long: loading stopped short of the cushion, so the film starts. */
        const val STALLED_MS = 1_500L
        /** The loader stops a piece short of its target; within one piece the buffer counts as full. */
        const val FULL_SLACK_BYTES = 2L * 1024 * 1024
        /** The real bitrate when known, else the guess from the add-on's size or the release name. */
        fun isHeavy(fileBitrateBps: Long, guessedBitrateBps: Long, heavyBps: Long): Boolean =
            (if (fileBitrateBps > 0L) fileBitrateBps else guessedBitrateBps) >= heavyBps
    }
}

/** A load control whose first start can wait for a [StartCushion]. */
interface StartCushionControl {
    val startCushion: StartCushion
}
