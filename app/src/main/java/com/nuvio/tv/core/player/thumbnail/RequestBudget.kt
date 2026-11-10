package com.nuvio.tv.core.player.thumbnail

import java.util.concurrent.ConcurrentHashMap

/** Requests per domain over the last [WINDOW_MS]: a limit may count over minutes and across a CDN's servers. */
internal class RequestTally {
    private val stamps = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun add(domain: String, nowMs: Long) {
        if (domain !in stamps && stamps.size >= MAX_DOMAINS) stamps.clear()
        val q = stamps.getOrPut(domain) { ArrayDeque() }
        q.addLast(nowMs)
        while (q.size > MAX_STAMPS || (q.isNotEmpty() && nowMs - q.first() >= WINDOW_MS)) q.removeFirst()
    }

    @Synchronized
    fun count(domain: String, nowMs: Long, withinMs: Long): Int =
        stamps[domain]?.count { nowMs - it < withinMs } ?: 0

    companion object {
        const val WINDOW_MS = 300_000L
        const val MAX_STAMPS = 5_000
        const val MAX_DOMAINS = 64
    }
}

/**
 * TorBox's store servers refuse a domain's requests for 300 s once about 740 to 880 were sent in about five minutes,
 * film pieces and thumbnails alike, however small. Thumbnails stop at [THUMBNAILS_HOLD_AT] and leave the rest to the film.
 * A refusal naming a long wait holds thumbnails on that domain until it ends.
 */
internal class RequestBudget(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val tally = RequestTally()
    private val blockedUntilMs = ConcurrentHashMap<String, Long>()

    fun note(host: String) {
        val now = nowMs()
        tally.add(domainOf(host), now)
        storeKey(host)?.let { tally.add(it, now) }
    }

    fun requests(domain: String, withinMs: Long): Int = tally.count(domain, nowMs(), withinMs)

    fun noteRefused(host: String, retryAfterMs: Long?) {
        if (retryAfterMs == null || retryAfterMs < LONG_WAIT_MS) return
        blockedUntilMs[blockKey(host)] = nowMs() + retryAfterMs
    }

    fun holdsThumbnails(url: String?): Boolean {
        val host = url?.let { RateLimitedHosts.hostOf(it) } ?: return false
        blockedUntilMs[blockKey(host)]?.let { if (nowMs() < it) return true }
        val store = storeKey(host) ?: return false
        return tally.count(store, nowMs(), RequestTally.WINDOW_MS) >= THUMBNAILS_HOLD_AT
    }

    companion object {
        val app = RequestBudget()
        const val THUMBNAILS_HOLD_AT = 550
        const val LONG_WAIT_MS = 60_000L
        const val STORE_PIECE_BYTES = 16L * 1024 * 1024
        private val STORE_HOST = Regex("""^store(-\d+)?\.[^.]+\.tb-cdn\.[^.]+$""")

        fun isStoreHost(host: String): Boolean = STORE_HOST.matches(host.lowercase())

        fun isStoreUrl(url: String?): Boolean = url?.let { RateLimitedHosts.hostOf(it) }?.let(::isStoreHost) == true

        fun domainOf(host: String): String {
            val isAddress = host.all { it.isDigit() || it == '.' } || ':' in host
            return if (isAddress) host else host.split('.').takeLast(2).joinToString(".")
        }

        private fun storeKey(host: String): String? = if (isStoreHost(host)) "store." + domainOf(host) else null

        private fun blockKey(host: String): String = storeKey(host) ?: domainOf(host)
    }
}
