package com.nuvio.tv.core.player.thumbnail

/** When the generating screen stops waiting and playback starts; the thumbnails carry on in the background. */
internal object PrepareHandover {
    enum class Reason { RATE_LIMITED, STALLED, HOPELESS }

    /** No new thumbnail for this long ... */
    const val STALL_MS = 20_000L
    /** ... or, judged after this long, more than [MAX_LEFT_S] still to go. */
    const val JUDGE_MS = 10_000L
    const val MAX_LEFT_S = 240

    /**
     * A start refused before its first frame (Dolby Vision, tunnelling) is asked again once the frame is up: a display
     * rate switch can delay that frame by a few seconds. Past that, or once more than a moment has played, it stays off.
     */
    const val HOLD_RETRY_WAIT_MS = 12_000L
    const val HOLD_RETRY_MAX_PLAYED_MS = 3_000L
    const val HOLD_RETRY_POLL_MS = 200L

    /** A session that finds no player at its start waits this long for a rebuilt one before it ends. */
    const val PLAYER_BACK_WAIT_MS = 15_000L
    const val PLAYER_BACK_POLL_MS = 250L

    fun retriesHold(sinceRefusedMs: Long, playedMs: Long): Boolean =
        sinceRefusedMs < HOLD_RETRY_WAIT_MS && playedMs <= HOLD_RETRY_MAX_PLAYED_MS

    /**
     * Whether thumbnails wait for a buffering player. Behind the generating screen playback is held, so a start or a
     * player rebuild (e.g. after the sound output refused a format) is not a rebuffer anyone sees.
     */
    fun waitsForBuffering(buffering: Boolean, screenUp: Boolean, playWhenReady: Boolean, small4K: Boolean): Boolean =
        buffering && (!screenUp || playWhenReady || small4K)

    /**
     * Small boxes, 4K: before the screen's first decode the player it holds lets go of its decoders and buffer, and
     * loads again when the screen ends. Never a player the user paused, and only once.
     */
    fun letsPlayerGo(smallBox4K: Boolean, screenUp: Boolean, heldByScreen: Boolean, letGo: Boolean): Boolean =
        smallBox4K && screenUp && heldByScreen && !letGo

    /**
     * Thumbnails still to make before every slot on the screen's grid ([stride]) is done, in the pass's own [order]: the
     * pass also makes the finer slots it meets on the way, so those count as work too.
     */
    fun workLeft(order: List<Int>, stride: Int, needs: (Int) -> Boolean): Int {
        val last = order.indexOfLast { it % stride == 0 && needs(it) }
        if (last < 0) return 0
        var left = 0
        for (i in 0..last) if (needs(order[i])) left++
        return left
    }

    /** Every slot the screen makes before its grid ([stride]) is complete: what its count and percentage show. */
    fun screenSet(order: List<Int>, stride: Int): List<Int> {
        val last = order.indexOfLast { it % stride == 0 }
        return if (last < 0) emptyList() else order.subList(0, last + 1)
    }

    /** Null while the screen should keep waiting. A rate-limited source fetches one at a time: it never keeps up. */
    fun reason(
        nowMs: Long,
        startedAtMs: Long,
        lastProgressAtMs: Long,
        leftS: Int,
        done: Int,
        total: Int,
        finished: Boolean,
        rateLimited: Boolean,
    ): Reason? {
        if (done >= total || finished) return null
        return when {
            rateLimited -> Reason.RATE_LIMITED
            nowMs - lastProgressAtMs >= STALL_MS -> Reason.STALLED
            nowMs - startedAtMs >= JUDGE_MS && leftS > MAX_LEFT_S -> Reason.HOPELESS
            else -> null
        }
    }
}
