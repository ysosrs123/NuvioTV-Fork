package com.nuvio.tv.core.player.thumbnail

import com.nuvio.tv.ui.screens.player.ParallelRangeRetryAfter
import java.util.concurrent.ConcurrentHashMap

/** Waits after a server said "too many requests" (429/503). */
internal object ThumbRateLimit {
    const val FIRST_BACKOFF_MS = 2_000L
    const val MAX_BACKOFF_MS = 30_000L
    private const val MIN_RETRY_AFTER_MS = 1_000L
    private const val MAX_RETRY_AFTER_MS = 30 * 60_000L

    /** The server's Retry-After (seconds or an HTTP date) in ms, or null when it sent none we can read. */
    fun retryAfterMs(header: String?, nowEpochMs: Long): Long? =
        ParallelRangeRetryAfter.parseHeaderMs(header?.trim(), nowEpochMs)?.coerceIn(MIN_RETRY_AFTER_MS, MAX_RETRY_AFTER_MS)

    /** Pause after [hitsInRow] rate limits without a fetch getting through: 2 s, doubling, at most 30 s. */
    fun backoffMs(hitsInRow: Int): Long {
        if (hitsInRow <= 0) return 0L
        return (FIRST_BACKOFF_MS shl (hitsInRow - 1).coerceAtMost(20)).coerceAtMost(MAX_BACKOFF_MS)
    }
}

/**
 * How many fetches one session runs at once: a few to start, one more after every [SUCCESSES_PER_STEP] fetches that
 * got through, and one at a time for the rest of the session once the server rate limits. The phase ceiling
 * ([ThumbFetchLanes.limit]) always applies on top.
 */
internal class FetchRamp(limitedFromStart: Boolean = false) {
    companion object {
        const val START_LANES = 3
        const val SUCCESSES_PER_STEP = 2
    }

    var lanes = if (limitedFromStart) 1 else START_LANES
        private set
    var limited = limitedFromStart
        private set
    var hitsInRow = 0
        private set

    private var okSinceStep = 0
    private var lastHitAtMs = Long.MIN_VALUE

    fun limit(ceiling: Int): Int = minOf(lanes, ceiling).coerceAtLeast(1)

    /** A fetch started at [startedAtMs] got through. One started before the last rate limit says nothing new. */
    fun succeeded(startedAtMs: Long) {
        if (startedAtMs <= lastHitAtMs) return
        hitsInRow = 0
        if (limited || lanes >= ThumbFetchLanes.MAX) return
        if (++okSinceStep >= SUCCESSES_PER_STEP) {
            lanes++
            okSinceStep = 0
        }
    }

    fun limitToOne() {
        limited = true
        lanes = 1
    }

    /**
     * A fetch started at [startedAtMs] was rate limited: one at a time from now on. Returns the pause before the next
     * fetch: the server's [retryAfterMs], else the back-off. Fetches under way together are refused together and
     * count once; for those null is returned unless the server named a wait.
     */
    fun rateLimited(retryAfterMs: Long?, startedAtMs: Long, nowMs: Long): Long? {
        limitToOne()
        if (startedAtMs <= lastHitAtMs) return retryAfterMs
        lastHitAtMs = nowMs
        hitsInRow++
        return retryAfterMs ?: ThumbRateLimit.backoffMs(hitsInRow)
    }
}

/**
 * Hosts that answered 429 to thumbnail fetches, so the next title from them starts at one fetch. The host is the one
 * that answered (after redirects), and it is forgotten [TTL_MS] after its last 429.
 */
internal class RateLimitedHosts {
    companion object {
        const val TTL_MS = 30 * 60_000L

        val appSession = RateLimitedHosts()

        fun hostOf(url: String): String? {
            val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@').lowercase()
            return host.ifEmpty { null }
        }
    }

    private val lastHitAt = ConcurrentHashMap<String, Long>()

    fun remember(url: String?, nowMs: Long = System.currentTimeMillis()) {
        url?.let { hostOf(it) }?.let { lastHitAt[it] = nowMs }
    }

    /** Only "too many requests" is remembered; a 503 is often a brief fault of one server. */
    fun note(e: RateLimitedException, nowMs: Long = System.currentTimeMillis()) {
        if (e.status == RateLimitedException.TOO_MANY_REQUESTS) remember(e.url, nowMs)
    }

    fun contains(url: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val host = url?.let { hostOf(it) } ?: return false
        val at = lastHitAt[host] ?: return false
        if (nowMs - at in 0 until TTL_MS) return true
        lastHitAt.remove(host, at)
        return false
    }
}
