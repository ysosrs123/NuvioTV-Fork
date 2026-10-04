package com.nuvio.tv.core.player.thumbnail

/** When the generating screen stops waiting and playback starts; the thumbnails carry on in the background. */
internal object PrepareHandover {
    enum class Reason { RATE_LIMITED, STALLED, HOPELESS }

    /** No new thumbnail for this long ... */
    const val STALL_MS = 20_000L
    /** ... or, judged after this long, more than [MAX_LEFT_S] still to go. */
    const val JUDGE_MS = 10_000L
    const val MAX_LEFT_S = 240

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
