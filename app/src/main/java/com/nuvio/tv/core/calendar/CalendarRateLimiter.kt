package com.nuvio.tv.core.calendar

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Token bucket that keeps a box under [limit] calls in any [windowMs]: [burst] calls at once, the rest refilled evenly
 * over the window. Boxes in one home share an address, so the default stays well under TVmaze's 20 per 10 s.
 */
class CalendarRateLimiter(
    private val limit: Int = 15,
    private val burst: Int = 5,
    private val windowMs: Long = 10_000L,
    private val nowMs: () -> Long,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    private val mutex = Mutex()
    private val lock = Any()
    private val capacity = burst.coerceIn(1, limit).toDouble()
    private val refillPerMs = (limit - capacity).coerceAtLeast(1.0) / windowMs
    private var tokens = capacity
    private var refilledAtMs = Long.MIN_VALUE
    private var blockedUntilMs = 0L

    suspend fun acquire(permits: Int = 1) {
        val needed = permits.toDouble().coerceAtMost(capacity)
        mutex.withLock {
            while (true) {
                val wait = synchronized(lock) { takeOrWait(needed, nowMs()) }
                if (wait <= 0L) return
                sleep(wait)
            }
        }
    }

    /** After a 429: nobody calls again for [ms], and the bucket starts empty. */
    fun pauseFor(ms: Long) = synchronized(lock) {
        val until = nowMs() + ms.coerceAtLeast(0L)
        if (until > blockedUntilMs) blockedUntilMs = until
        tokens = 0.0
        refilledAtMs = maxOf(refilledAtMs, until)
    }

    private fun takeOrWait(needed: Double, now: Long): Long {
        if (blockedUntilMs > now) return blockedUntilMs - now
        refill(now)
        if (tokens >= needed) {
            tokens -= needed
            return 0L
        }
        return ((needed - tokens) / refillPerMs).toLong().coerceAtLeast(1L)
    }

    private fun refill(now: Long) {
        if (refilledAtMs == Long.MIN_VALUE) {
            refilledAtMs = now
            return
        }
        if (now > refilledAtMs) {
            tokens = (tokens + (now - refilledAtMs) * refillPerMs).coerceAtMost(capacity)
            refilledAtMs = now
        }
    }
}

/** Retry-After in seconds as ms, within 1 s and 2 min; null when absent or not a number. */
fun parseRetryAfterMs(header: String?): Long? =
    header?.trim()?.toLongOrNull()?.times(1_000L)?.coerceIn(1_000L, 120_000L)
