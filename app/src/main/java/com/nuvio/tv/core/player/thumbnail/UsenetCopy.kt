package com.nuvio.tv.core.player.thumbnail

/** How long a Usenet title's thumbnails wait for a copy, and the one picture a scrub stop still reads from the stream. */
internal object UsenetCopy {
    /** No copy confirmed this long after the copy pass started: the stream makes the thumbnails. */
    const val TOTAL_MS = 60_000L
    /** No new copy thumbnail for this long while one is being made: the stream makes the thumbnails. */
    const val STALL_MS = 20_000L
    /** The scrub rested this long: that spot is a stop, not a step. */
    const val SCRUB_STOP_MS = 700L

    /**
     * The copy's [order] with the slots the generating screen counts first (copy slots serving an own slot on every
     * [stride]th), the rest after, each part in its own order. [owners]: own slots by copy slot.
     */
    fun screenFirst(order: List<Int>, owners: Map<Int, IntArray>, stride: Int): List<Int> {
        if (stride <= 1) return order
        val (counted, rest) = order.partition { m -> owners[m]?.any { it % stride == 0 } == true }
        return counted + rest
    }

    /** The own slot a copy slot's picture is taken for: of those it [served], the one the screen counts, if any. */
    fun pictureFor(served: IntArray, stride: Int): Int =
        if (stride > 1) served.firstOrNull { it % stride == 0 } ?: served[0] else served[0]

    /**
     * Copy pictures fetched at once, by the same server rules as the title's own ([ThumbFetchLanes.limit]): behind the
     * generating screen as generating would use, otherwise as while playing. [serverCap]: what the ledger leaves now.
     * [headroom]: lanes playback's buffer allows ([PlaybackHeadroom]); none still plans one, the pass waits for it.
     * Never more than [CopyFirst.MAX_LANES]: the film's own requests keep the rest.
     */
    fun fetches(
        screenUp: Boolean, playing: Boolean, serverCap: Int, smallBox: Boolean, rateLimited: Boolean,
        torBox: Boolean = false, refused: Boolean = false, headroom: Int = ThumbFetchLanes.MAX, surplus: Boolean = false,
    ): Int {
        val phase = if (screenUp && !playing) FetchPhase.GENERATING else FetchPhase.PLAYBACK
        val lanes = ThumbFetchLanes.limit(phase, rateLimited, smallBox4K = smallBox, ledgerCap = serverCap,
            torBox = torBox, refused = refused, surplus = surplus)
        val n = if (phase == FetchPhase.PLAYBACK) minOf(lanes, headroom.coerceAtLeast(1)) else lanes
        return minOf(n, CopyFirst.MAX_LANES)
    }

    /** Why the copy is given up, null while it may go on. [sinceProgressMs]: since its last thumbnail, null while none is being made. */
    fun giveUp(confirmed: Boolean, sinceStartMs: Long, sinceProgressMs: Long?): String? = when {
        sinceProgressMs != null && sinceProgressMs >= STALL_MS -> "no copy thumbnail in ${STALL_MS / 1000} s"
        !confirmed && sinceStartMs >= TOTAL_MS -> "no copy confirmed in ${TOTAL_MS / 1000} s"
        else -> null
    }

    /**
     * The slot read from the stream for a scrub stop, -1 for none: [slot] once the scrub has rested since [lastScrubAt],
     * when [needed] (no thumbnail there yet), and once per stop ([servedStopAt] is the stop already read for).
     */
    fun landingSlot(slot: Int, nowMs: Long, lastScrubAt: Long, servedStopAt: Long, needed: Boolean): Int = when {
        slot < 0 || !needed || lastScrubAt <= 0L -> -1
        nowMs - lastScrubAt < SCRUB_STOP_MS -> -1
        servedStopAt == lastScrubAt -> -1
        else -> slot
    }
}
