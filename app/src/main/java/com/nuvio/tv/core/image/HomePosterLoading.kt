package com.nuvio.tv.core.image

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import coil3.Extras
import coil3.imageLoader
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.ImageResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared with the image mapper so disk lookups use the fetched TMDB URL. */
fun sizedHomeArtworkUrl(data: String, width: Int?): String {
    if (!data.startsWith("https://image.tmdb.org/t/p/original/") || width == null || width !in 1..780) return data
    val bucket = when { width <= 342 -> "w342"; width <= 500 -> "w500"; else -> "w780" }
    return data.replace("/t/p/original/", "/t/p/$bucket/")
}

/** A request waiting for a download or decode lane. */
internal interface PosterWaiter {
    fun rank(): Int
    /** Prefetch and idle warming, as opposed to a poster on screen. */
    val background: Boolean
}

/**
 * Serializes identical requests without retaining completed keys or sharing a cancelled result. Whoever goes first
 * queues with the most urgent rank of everyone waiting for the same poster, so a card on screen never waits at the
 * rank of a prefetch that got there first.
 */
internal class PosterRequestLocks {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0) {
        val waiters = mutableListOf<PosterWaiter>()
        val group = object : PosterWaiter {
            override fun rank(): Int = synchronized(waiters) { waiters.minOfOrNull { it.rank() } } ?: Int.MAX_VALUE
            override val background: Boolean get() = synchronized(waiters) { waiters.all { it.background } }
        }
    }
    private val entries = mutableMapOf<String, Entry>()
    suspend fun <T> run(key: String, waiter: PosterWaiter? = null, block: suspend (PosterWaiter) -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        if (waiter != null) synchronized(entry.waiters) { entry.waiters += waiter }
        try { return entry.mutex.withLock { block(entry.group) } }
        finally {
            if (waiter != null) synchronized(entry.waiters) { entry.waiters.remove(waiter) }
            synchronized(entries) { if (--entry.users == 0) entries.remove(key) }
        }
    }
}

/** A viewport, rather than a fixed request rank, lets queued work follow a scrolling user. */
class HomePosterViewport {
    var firstRow by mutableIntStateOf(0)
    var lastRow by mutableIntStateOf(1)
    var scrolling by mutableStateOf(false)
    internal val visibleRequests = java.util.concurrent.atomic.AtomicInteger()
    /** Uptime of the last focus move on Home; 0 before the first. */
    @Volatile var lastFocusMoveAtMs = 0L
    internal val progressiveLock = Mutex()

    /** One disk warmer per Home viewport; three download slots stay available. */
    suspend fun <T> warmWhenIdle(stillIdle: () -> Boolean, block: suspend () -> T): T? =
        progressiveLock.withLock {
            while (visibleRequests.get() > 0 && stillIdle()) kotlinx.coroutines.delay(50)
            if (scrolling || !stillIdle()) return@withLock null
            block()
        }
}

data class HomePosterPriority(val viewport: HomePosterViewport, val row: Int, val prefetch: Boolean = false, val progressive: Boolean = false) : PosterWaiter {
    override val background: Boolean get() = prefetch || progressive

    override fun rank(): Int {
        val first = viewport.firstRow
        val last = viewport.lastRow
        return when {
            progressive -> 100_000 + row
            row in first..last -> (row - first) * 100 + if (prefetch) 5_000 else 0
            row > last -> 10_000 + (row - last) * 100
            else -> 20_000 + (first - row) * 100
        }
    }
}

val LocalHomePosterPriority = staticCompositionLocalOf<HomePosterPriority?> { null }
private val HomePosterPriorityKey = Extras.Key<HomePosterPriority?>(null)

fun ImageRequest.Builder.homePosterPriority(priority: HomePosterPriority?): ImageRequest.Builder = apply {
    extras[HomePosterPriorityKey] = priority
}

fun homePosterCacheKey(url: String, width: Int, height: Int, version: Int = 0) =
    "${url}_${width}x${height}_v$version"

private val HomePosterAttemptKey = Extras.Key(0)

/** A retry must not equal the failed request, or the painter keeps the error. Memory and disk keys are unchanged. */
fun ImageRequest.Builder.homePosterAttempt(attempt: Int): ImageRequest.Builder = apply {
    if (attempt > 0) extras[HomePosterAttemptKey] = attempt
}

const val HOME_POSTER_RETRY_DELAY_MS = 2_000L

/** One retry per appearance of a tile; a second failure shows the no-artwork tile instead of the loading colour. */
fun homePosterRetryAfterError(attempt: Int): Boolean = attempt < 1

const val HOME_POSTER_BITMAP_PARALLELISM = 4

/** Posters on screen download this many at a time; prefetch and idle warming hold at most [HOME_POSTER_BACKGROUND_DOWNLOADS] of them. */
const val HOME_POSTER_DOWNLOADS = 8
const val HOME_POSTER_BACKGROUND_DOWNLOADS = 2
private const val MIB = 1024L * 1024
private const val STRONG_BOX_TOTAL_BYTES = 2_560L * MIB
private const val SMALL_BOX_TOTAL_BYTES = 1_536L * MIB
private const val LARGE_MEMORY_BOX_TOTAL_BYTES = 3_072L * MIB
private const val LARGE_BOX_ARTWORK_CACHE_BYTES = 176L * MIB

/** Disk-hit decode lanes by device class, never more than BitmapFactory's own parallelism. */
fun homePosterDiskDecodeSlots(totalRamBytes: Long, lowRamDevice: Boolean, cores: Int): Int = when {
    lowRamDevice || totalRamBytes < SMALL_BOX_TOTAL_BYTES || cores < 4 -> 2
    totalRamBytes >= STRONG_BOX_TOTAL_BYTES -> HOME_POSTER_BITMAP_PARALLELISM
    else -> 3
}

/** Boxes above 3 GB hold several rows more of decoded posters; smaller boxes keep the heap-based budget. */
fun homeArtworkMemoryCacheBytes(heapBudgetBytes: Long, totalRamBytes: Long): Long =
    if (totalRamBytes > LARGE_MEMORY_BOX_TOTAL_BYTES) {
        maxOf(heapBudgetBytes, minOf(LARGE_BOX_ARTWORK_CACHE_BYTES, totalRamBytes / 16))
    } else heapBudgetBytes

/** Idle memory warming takes at most a third of the poster partition, so it cannot evict what is on screen. */
fun homePosterMemoryWarmBudget(posterPartitionBytes: Long): Long = (posterPartitionBytes / 3).coerceAtLeast(0)

/** Rows decoded into memory at idle: the visible rows, then the next rows in the direction of travel. */
internal fun homePosterMemoryWarmRows(count: Int, first: Int, last: Int, previousFirst: Int, ahead: Int = 3): List<Int> {
    if (count <= 0 || first < 0 || last < first) return emptyList()
    val up = previousFirst >= 0 && first < previousFirst
    val next = (1..ahead).map { if (up) first - it else last + it }
    return ((first..last) + next).filter { it in 0 until count }
}

/** Posters of one row held decoded at idle: half a screen behind its position and one screen beyond. */
internal fun homePosterMemoryWarmColumns(first: Int, visibleCount: Int): IntRange {
    val count = visibleCount.coerceIn(4, 16)
    return maxOf(0, first - count / 2)..(maxOf(0, first) + count * 2 - 1)
}

/** Ahead-row work already started keeps running while it stays near the viewport. */
internal fun homePosterAheadKeepRows(first: Int, last: Int, ahead: Int): IntRange =
    (first - ahead - 1)..(last + ahead + 1)

/** The same three-row budget follows travel direction, including a reversal. */
internal fun homePosterPrefetchRows(first: Int, last: Int, previousFirst: Int, ahead: Int = 2): List<Int> {
    if (first < 0 || last < first) return emptyList()
    val up = previousFirst >= 0 && first < previousFirst
    val forward = if (up) (1..ahead).map { first - it } else (1..ahead).map { last + it }
    return (forward + if (up) last + 1 else first - 1).filter { it >= 0 }
}

/** Background poster lanes left while a Home list is moving. Posters on screen keep all of theirs. */
internal fun homePosterBackgroundLanes(lanes: Int, scrolling: Boolean): Int = if (scrolling) 1 else lanes

const val HOME_SCROLL_LANES_RESTORE_MS = 150L
const val HOME_SCROLL_REPORT_EVERY_MS = 1_000L
private const val HOME_SCROLL_REPORT_STALE_MS = 3_000L

/** Home lists report scrolling here. A report goes stale, so a lost "stopped" cannot keep the lanes cut. */
internal class HomeScrollSignal(private val nowMs: () -> Long) {
    @Volatile private var reported = false
    @Volatile private var reportedAtMs = 0L
    @Volatile var onStopped: (() -> Unit)? = null

    val scrolling: Boolean get() = reported && nowMs() - reportedAtMs < HOME_SCROLL_REPORT_STALE_MS

    fun reportScrolling() {
        reportedAtMs = nowMs()
        reported = true
    }

    fun reportStopped() {
        reported = false
        onStopped?.invoke()
    }
}

internal val HomePosterScroll = HomeScrollSignal { android.os.SystemClock.uptimeMillis() }

/**
 * Posters seen in the disk cache. It only picks the lane: the fetcher opens the file itself, so an entry that is
 * no longer true costs a wait in the wrong lane and nothing else.
 */
internal class KnownDiskKeys(private val limit: Int = 4_000) {
    private val keys = HashSet<String>()

    operator fun contains(key: String): Boolean = synchronized(keys) { key in keys }

    fun add(key: String) {
        synchronized(keys) {
            if (keys.size >= limit) keys.clear()
            keys += key
        }
    }

    fun remove(key: String) {
        synchronized(keys) { keys.remove(key) }
    }

    fun clear() {
        synchronized(keys) { keys.clear() }
    }
}

internal val HomePostersOnDisk = KnownDiskKeys()

/**
 * Bounded priority queue; cancellation releases a slot even when granted just before await. Background work holds at
 * most [backgroundConcurrency] slots, so the rest stay free for what is on screen.
 */
internal class ImagePriorityQueue(
    private val concurrency: Int,
    private val backgroundConcurrency: Int = concurrency,
    private val scrolling: () -> Boolean = { false }
) {
    private class Ticket(val rank: () -> Int, val background: () -> Boolean, val ready: CompletableDeferred<Unit> = CompletableDeferred()) {
        var countedAsBackground = false
    }
    private val waiting = mutableListOf<Ticket>()
    private var active = 0
    private var activeBackground = 0

    init { require(concurrency > 0 && backgroundConcurrency > 0) }

    suspend fun <T> run(rank: () -> Int, background: () -> Boolean = { false }, block: suspend () -> T): T {
        val ticket = Ticket(rank, background)
        synchronized(waiting) {
            waiting += ticket
            drain()
        }
        try {
            ticket.ready.await()
            return block()
        } finally {
            synchronized(waiting) {
                if (!waiting.remove(ticket)) {
                    active--
                    if (ticket.countedAsBackground) activeBackground--
                }
                drain()
            }
        }
    }

    /** Hands out lanes that opened without a request finishing, such as when scrolling stops. */
    fun recheck() {
        synchronized(waiting) { drain() }
    }

    private fun drain() {
        while (active < concurrency) {
            val backgroundRoom = activeBackground < homePosterBackgroundLanes(backgroundConcurrency, scrolling())
            val next = waiting.filter { backgroundRoom || !it.background() }.minByOrNull { it.rank() } ?: return
            waiting.remove(next)
            active++
            if (next.background()) {
                next.countedAsBackground = true
                activeBackground++
            }
            next.ready.complete(Unit)
        }
    }
}

/** Only Home artwork opts in. Cached images never wait behind network/decode work. */
class HomePosterLoadingInterceptor(diskDecodeSlots: Int = 2) : Interceptor {
    private val downloads = ImagePriorityQueue(HOME_POSTER_DOWNLOADS, HOME_POSTER_BACKGROUND_DOWNLOADS) {
        HomePosterScroll.scrolling
    }
    private val cached = diskDecodeSlots.coerceIn(1, HOME_POSTER_BITMAP_PARALLELISM).let { slots ->
        ImagePriorityQueue(slots, (slots - 1).coerceAtLeast(1)) { HomePosterScroll.scrolling }
    }
    private val locks = PosterRequestLocks()

    init {
        HomePosterScroll.onStopped = {
            downloads.recheck()
            cached.recheck()
        }
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val priority = chain.request.extras[HomePosterPriorityKey] ?: return chain.proceed()
        val visible = !priority.prefetch && !priority.progressive
        if (visible) priority.viewport.visibleRequests.incrementAndGet()
        try { return interceptPrioritized(chain, priority) }
        finally { if (visible) priority.viewport.visibleRequests.decrementAndGet() }
    }

    private suspend fun interceptPrioritized(chain: Interceptor.Chain, priority: HomePosterPriority): ImageResult {
        val request = chain.request
        val loader = request.context.imageLoader
        val key = request.memoryCacheKey ?: return downloads.run(priority::rank, { priority.background }) { chain.proceed() }
        return locks.run(key, priority) { shared ->
            // Recheck after a concurrent prefetch/card request has completed.
            val memoryKey = MemoryCache.Key(key, request.memoryCacheKeyExtras)
            val start = android.os.SystemClock.elapsedRealtime()
            val result = if (request.memoryCachePolicy.readEnabled && loader.memoryCache?.get(memoryKey) != null) {
                chain.proceed()
            } else {
                val diskKey = request.diskCacheKey ?: (request.data as? String)?.let {
                    sizedHomeArtworkUrl(it, (chain.size.width as? coil3.size.Dimension.Pixels)?.px)
                }
                // Any saved copy is shown straight from disk; an older one is checked online afterwards.
                val known = diskKey != null && diskKey in HomePostersOnDisk
                val onDisk = request.diskCachePolicy.readEnabled && diskKey != null &&
                    (known || withContext(Dispatchers.IO) {
                        loader.diskCache?.openSnapshot(diskKey)?.use { true } ?: false
                    })
                // Local files get independent slots; absent images cannot hold up their decode.
                val loaded = (if (onDisk) cached else downloads).run(shared::rank, { shared.background }) {
                    chain.proceed()
                }
                if (onDisk && diskKey != null) {
                    val fromDisk = (loaded as? coil3.request.SuccessResult)?.dataSource == coil3.decode.DataSource.DISK
                    if (fromDisk) HomePostersOnDisk.add(diskKey) else if (known) HomePostersOnDisk.remove(diskKey)
                }
                loaded
            }
            if (com.nuvio.tv.BuildConfig.DEBUG || android.util.Log.isLoggable("HomePosterCache", android.util.Log.VERBOSE)) {
                val source = (result as? coil3.request.SuccessResult)?.dataSource?.name ?: "ERROR"
                android.util.Log.d("HomePosterCache", "source=$source elapsedMs=${android.os.SystemClock.elapsedRealtime() - start} prefetch=${priority.prefetch} progressive=${priority.progressive}")
            }
            if (!priority.prefetch && !priority.progressive) recordHomeTile(key, request, result)
            result
        }
    }
}

private fun recordHomeTile(key: String, request: ImageRequest, result: ImageResult) {
    val retry = request.isHomePosterRecovery || (request.extras[HomePosterAttemptKey] ?: 0) > 0
    when (result) {
        is coil3.request.SuccessResult -> HomePosterVisitStats.counters.success(key, result.dataSource, retry)
        // A custom poster that fails is retried with its plain poster right after; count only the final answer.
        is coil3.request.ErrorResult -> if (request.memoryCacheKeyExtras[CustomPosterFallbackInterceptor.FALLBACK_URL_KEY].isNullOrBlank()) {
            HomePosterVisitStats.counters.failure(key, retry)
            HomePosterRecovery.missing.record(key, result.throwable)
        }
    }
}

/** Lazy nearest-first traversal: no request list proportional to catalogue size. */
internal fun progressivePosterIndices(count: Int, first: Int, last: Int): Sequence<Int> = sequence {
    if (count <= 0 || first < 0 || last < first) return@sequence
    var distance = 1
    while (last + distance < count || first - distance >= 0) {
        if (last + distance < count) yield(last + distance)
        if (first - distance >= 0) yield(first - distance)
        distance++
    }
}

/** Keep a bounded decoded window ahead of fast D-pad travel; retain a return flank. */
internal fun homePosterWarmWindow(count: Int, first: Int, last: Int, previousFirst: Int): List<Int> {
    if (count <= 0 || first < 0 || last < first) return emptyList()
    val visible = last - first + 1
    val ahead = (visible * 2).coerceIn(8, 32)
    val behind = visible.coerceIn(5, 16)
    val movingLeft = previousFirst >= 0 && first < previousFirst
    val right = ((last + 1)..minOf(count - 1, last + if (movingLeft) behind else ahead)).toList()
    val left = ((first - 1) downTo maxOf(0, first - if (movingLeft) ahead else behind)).toList()
    return if (movingLeft) left + right else right + left
}

/** Visible rows first, then neighbouring loaded rows; no poster list is allocated. */
internal fun progressivePosterRows(count: Int, first: Int, last: Int): Sequence<Int> = sequence {
    if (count <= 0) return@sequence
    val start = first.coerceIn(0, count - 1)
    val end = last.coerceIn(start, count - 1)
    for (i in start..end) yield(i)
    var distance = 1
    while (end + distance < count || start - distance >= 0) {
        if (end + distance < count) yield(end + distance)
        if (start - distance >= 0) yield(start - distance)
        distance++
    }
}
