package com.nuvio.tv.ui.screens.player

import android.content.SharedPreferences
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * What Automatic speed-up learnt per server: the rate one request carried, the downloads a film kept, the wait for
 * the first byte, the piece size the rate was carried on and the piece size its next film starts on at least. The next
 * film from the same server starts from it instead of measuring again. Kept [KEEP_MS]. Entries saved before version 2
 * shared the rate by downloads while the read-ahead's pieces ran too, so read up to four times too high, and are not
 * used; version 2 entries start on the piece their rate was carried on.
 */
internal object HostSpeeds {
    data class Entry(
        val perDownloadBps: Long,
        val downloads: Int,
        val firstByteMs: Long,
        val savedAtMs: Long,
        val pieceBytes: Long = 0L,
        val startPieceBytes: Long = 0L
    )

    const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    private const val PREFIX = "host:"

    @Volatile
    private var prefs: SharedPreferences? = null
    private val cache = ConcurrentHashMap<String, Entry>()

    fun attach(store: SharedPreferences) {
        if (prefs == null) prefs = store
    }

    /**
     * The server a link belongs to. The numbers in the first label name one machine (nexus-115, syd3-4, store-059,
     * 1-cdn2-ovh-bea), so they are left out and the place stays: nexus, syd, store, cdn-ovh-bea.
     */
    fun keyOf(url: String?): String? {
        val host = runCatching { URI(url ?: return null).host }.getOrNull()?.lowercase() ?: return null
        val labels = host.split('.')
        if (labels.size <= 2 || ':' in host || labels.last().all(Char::isDigit)) return host
        val first = labels[0].split('-').map { it.filterNot(Char::isDigit) }.filter { it.isNotEmpty() }.joinToString("-")
        if (first.isEmpty()) return host
        return (listOf(first) + labels.drop(1)).joinToString(".")
    }

    fun get(key: String, nowMs: Long = System.currentTimeMillis()): Entry? {
        val e = cache[key] ?: prefs?.getString(PREFIX + key, null)?.let(::decode)?.also { cache[key] = it } ?: return null
        return e.takeIf { nowMs - it.savedAtMs in 0 until KEEP_MS }
    }

    fun put(key: String, entry: Entry) {
        cache[key] = entry
        prefs?.edit()?.putString(PREFIX + key, encode(entry))?.apply()
    }

    /** Adds one film to what the server delivered before, so a single good or bad film does not decide the next. */
    fun record(key: String, film: Entry) = put(key, merge(get(key, film.savedAtMs), film))

    fun merge(old: Entry?, film: Entry): Entry {
        if (old == null) return film
        return Entry(
            perDownloadBps = ((old.perDownloadBps * OLD_SHARE + film.perDownloadBps * (100 - OLD_SHARE)) / 100)
                .coerceAtLeast(1L),
            downloads = ((old.downloads * OLD_SHARE + film.downloads * (100 - OLD_SHARE) + 50) / 100).coerceAtLeast(1),
            firstByteMs = if (film.firstByteMs >= 0L) film.firstByteMs else old.firstByteMs,
            savedAtMs = film.savedAtMs,
            pieceBytes = mergePiece(old.pieceBytes, film.pieceBytes),
            startPieceBytes = stepPiece(old.startPieceBytes, film.startPieceBytes)
        )
    }

    /** Averaged like the rate, then set to the nearest piece size Automatic uses. */
    fun mergePiece(old: Long, film: Long): Long {
        if (old <= 0L || film <= 0L) return if (film > 0L) film else old
        val mean = (old * OLD_SHARE + film * (100 - OLD_SHARE)) / 100
        return AutoSpeedupPolicy.PIECE_STEPS.minBy { kotlin.math.abs(it - mean) }
    }

    /** One piece size step from [old] toward [film], so one film moves the start piece but never all the way. */
    fun stepPiece(old: Long, film: Long): Long {
        if (old <= 0L || film <= 0L) return if (film > 0L) film else old
        val steps = AutoSpeedupPolicy.PIECE_STEPS
        val from = steps.indices.minBy { kotlin.math.abs(steps[it] - old) }
        val to = steps.indices.minBy { kotlin.math.abs(steps[it] - film) }
        return steps[from + (to - from).coerceIn(-1, 1)]
    }

    const val OLD_SHARE = 60

    const val VERSION = 3

    fun encode(e: Entry): String =
        "${e.perDownloadBps},${e.downloads},${e.firstByteMs},${e.savedAtMs},${e.pieceBytes},${e.startPieceBytes},v$VERSION"

    fun decode(text: String): Entry? {
        val p = text.split(',')
        val v2 = p.size == 6 && p[5] == "v2"
        if (!v2 && (p.size != 7 || p[6] != "v$VERSION")) return null
        val bps = p[0].toLongOrNull() ?: return null
        val n = p[1].toIntOrNull() ?: return null
        val fb = p[2].toLongOrNull() ?: return null
        val at = p[3].toLongOrNull() ?: return null
        val piece = p[4].toLongOrNull()?.coerceAtLeast(0L) ?: return null
        val start = if (v2) piece else p[5].toLongOrNull()?.coerceAtLeast(0L) ?: return null
        return if (bps > 0L && n > 0) Entry(bps, n, fb, at, piece, start) else null
    }

    /**
     * The requests a server took before it answered "too many requests": [limit] at [atMs]. One more is allowed for
     * each [LIMIT_EASE_MS] without a refusal since, until [AutoSpeedup.MAX_IN_FLIGHT] lifts it. A server that [blocks]
     * parallel downloads stays held for [blockHoldMs] of its [strikes] before it starts to ease; [blockAtMs] is when
     * the last block was learnt.
     */
    data class Limit(
        val limit: Int,
        val atMs: Long,
        val blocks: Boolean = false,
        val strikes: Int = 0,
        val blockAtMs: Long = 0L
    )

    const val LIMIT_EASE_MS = 30L * 60 * 1000
    private const val DAY_MS = 24L * 60 * 60 * 1000
    const val BLOCK_HOLD_MAX_MS = 7L * DAY_MS

    /** Blocks stop counting after this long without one once the last hold ended. */
    const val BLOCK_CLEAN_MS = 14L * DAY_MS
    private const val LIMIT_PREFIX = "limit:"
    private val limits = ConcurrentHashMap<String, Limit>()

    /** One day at the first block, doubled at each block after a hold ran out, a week at most. */
    fun blockHoldMs(strikes: Int): Long =
        if (strikes <= 0) 0L else minOf(DAY_MS shl (strikes - 1).coerceAtMost(3), BLOCK_HOLD_MAX_MS)

    private fun stored(key: String): Limit? =
        limits[key] ?: prefs?.getString(LIMIT_PREFIX + key, null)?.let(::decodeLimit)?.also { limits[key] = it }

    /** The requests [key]'s server takes at once now, null when it never refused or has eased back. */
    fun limitOf(key: String, nowMs: Long = System.currentTimeMillis()): Int? {
        val l = stored(key) ?: return null
        return easedLimit(l, nowMs)
    }

    /** The held block verdict of [key]'s server, null when none holds now. */
    fun heldBlock(key: String, nowMs: Long = System.currentTimeMillis()): Limit? =
        stored(key)?.takeIf { blockHeld(it, nowMs) }

    fun blockHeld(l: Limit, nowMs: Long): Boolean = l.blocks && nowMs - l.atMs in 0 until blockHoldMs(l.strikes)

    fun easedLimit(l: Limit, nowMs: Long): Int? {
        val since = if (l.blocks) (nowMs - l.atMs - blockHoldMs(l.strikes)) else (nowMs - l.atMs)
        val eased = l.limit + (since.coerceAtLeast(0L) / LIMIT_EASE_MS).toInt()
        return eased.takeIf { it < AutoSpeedup.MAX_IN_FLIGHT }
    }

    /**
     * A refusal: the server is held to [limit] (or less, if it was held lower still) from [nowMs]. A softer refusal
     * leaves a held [blocks] verdict as it is and keeps the count of blocks. Returns what is kept now.
     */
    fun noteLimit(key: String, limit: Int, nowMs: Long = System.currentTimeMillis(), blocks: Boolean = false): Limit? {
        val old = stored(key)
        val l = merged(old, limit, nowMs, blocks) ?: return old
        limits[key] = l
        prefs?.edit()?.putString(LIMIT_PREFIX + key, encodeLimit(l))?.apply()
        return l
    }

    /** The block count a block learnt at [nowMs] starts: one more than [old]'s unless its last hold ended clean. */
    fun strikesAfter(old: Limit?, nowMs: Long): Int {
        if (old == null || old.strikes <= 0) return 1
        if (old.blocks && blockHeld(old, nowMs)) return old.strikes
        val holdEndMs = old.blockAtMs + blockHoldMs(old.strikes)
        return if (nowMs - holdEndMs < BLOCK_CLEAN_MS) old.strikes + 1 else 1
    }

    /** What [noteLimit] keeps: null when [old] stays as it is. */
    fun merged(old: Limit?, limit: Int, nowMs: Long, blocks: Boolean): Limit? {
        if (old != null && !blocks && blockHeld(old, nowMs) && old.limit <= limit) return null
        val held = old?.let { easedLimit(it, nowMs) }
        val n = if (held != null) minOf(held, limit) else limit
        if (blocks) return Limit(n, nowMs, blocks = true, strikes = strikesAfter(old, nowMs), blockAtMs = nowMs)
        return Limit(n, nowMs, strikes = old?.strikes ?: 0, blockAtMs = old?.blockAtMs ?: 0L)
    }

    fun encodeLimit(l: Limit): String =
        "${l.limit},${l.atMs}" + if (l.strikes > 0) ",${if (l.blocks) "b" else "s"},${l.strikes},${l.blockAtMs}" else ""

    fun decodeLimit(text: String): Limit? {
        val p = text.split(',')
        val n = p.getOrNull(0)?.toIntOrNull() ?: return null
        val at = p.getOrNull(1)?.toLongOrNull() ?: return null
        if (n <= 0) return null
        val strikes = p.getOrNull(3)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val blockAt = p.getOrNull(4)?.toLongOrNull() ?: 0L
        if (strikes == 0) return Limit(n, at)
        return Limit(n, at, blocks = p[2] == "b", strikes = strikes, blockAtMs = blockAt)
    }

    internal fun clearForTest() {
        cache.clear()
        limits.clear()
        prefs = null
    }
}
