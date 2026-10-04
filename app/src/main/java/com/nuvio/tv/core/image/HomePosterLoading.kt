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

/** Serializes identical requests without retaining completed keys or sharing a cancelled result. */
internal class PosterRequestLocks {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()
    suspend fun <T> run(key: String, block: suspend () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try { return entry.mutex.withLock { block() } }
        finally { synchronized(entries) { if (--entry.users == 0) entries.remove(key) } }
    }
}

/** A viewport, rather than a fixed request rank, lets queued work follow a scrolling user. */
class HomePosterViewport {
    var firstRow by mutableIntStateOf(0)
    var lastRow by mutableIntStateOf(1)
    var scrolling by mutableStateOf(false)
    internal val visibleRequests = java.util.concurrent.atomic.AtomicInteger()
    internal val progressiveLock = Mutex()

    /** One disk warmer per Home viewport; three download slots stay available. */
    suspend fun <T> warmWhenIdle(stillIdle: () -> Boolean, block: suspend () -> T): T? =
        progressiveLock.withLock {
            while (visibleRequests.get() > 0 && stillIdle()) kotlinx.coroutines.delay(50)
            if (scrolling || !stillIdle()) return@withLock null
            block()
        }
}

data class HomePosterPriority(val viewport: HomePosterViewport, val row: Int, val prefetch: Boolean = false, val progressive: Boolean = false) {
    fun rank(): Int {
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

/** Bounded priority queue; cancellation releases a slot even when granted just before await. */
internal class ImagePriorityQueue(private val concurrency: Int) {
    private class Ticket(val rank: () -> Int, val ready: CompletableDeferred<Unit> = CompletableDeferred())
    private val waiting = mutableListOf<Ticket>()
    private var active = 0

    init { require(concurrency > 0) }

    suspend fun <T> run(rank: () -> Int, block: suspend () -> T): T {
        val ticket = Ticket(rank)
        synchronized(waiting) {
            waiting += ticket
            drain()
        }
        try {
            ticket.ready.await()
            return block()
        } finally {
            synchronized(waiting) {
                if (!waiting.remove(ticket)) active--
                drain()
            }
        }
    }

    private fun drain() {
        while (active < concurrency && waiting.isNotEmpty()) {
            val next = waiting.minBy { it.rank() }
            waiting.remove(next)
            active++
            next.ready.complete(Unit)
        }
    }
}

/** Only Home artwork opts in. Cached images never wait behind network/decode work. */
class HomePosterLoadingInterceptor(diskDecodeSlots: Int = 2) : Interceptor {
    private val downloads = ImagePriorityQueue(concurrency = 4)
    private val cached = ImagePriorityQueue(concurrency = diskDecodeSlots.coerceIn(1, HOME_POSTER_BITMAP_PARALLELISM))
    private val locks = PosterRequestLocks()
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
        val key = request.memoryCacheKey ?: return downloads.run(priority::rank) { chain.proceed() }
        return locks.run(key) {
            // Recheck after a concurrent prefetch/card request has completed.
            val memoryKey = MemoryCache.Key(key, request.memoryCacheKeyExtras)
            val start = android.os.SystemClock.elapsedRealtime()
            val result = if (request.memoryCachePolicy.readEnabled && loader.memoryCache?.get(memoryKey) != null) {
                chain.proceed()
            } else {
                val diskKey = request.diskCacheKey ?: (request.data as? String)?.let {
                    sizedHomeArtworkUrl(it, (chain.size.width as? coil3.size.Dimension.Pixels)?.px)
                }
                val onDisk = request.diskCachePolicy.readEnabled && diskKey != null && withContext(Dispatchers.IO) {
                    loader.diskCache?.openSnapshot(diskKey)?.use { true } ?: false
                }
                // Local files get independent slots; absent images cannot hold up their decode.
                (if (onDisk) cached else downloads).run(priority::rank) { chain.proceed() }
            }
            if (com.nuvio.tv.BuildConfig.DEBUG || android.util.Log.isLoggable("HomePosterCache", android.util.Log.VERBOSE)) {
                val source = (result as? coil3.request.SuccessResult)?.dataSource?.name ?: "ERROR"
                android.util.Log.d("HomePosterCache", "source=$source elapsedMs=${android.os.SystemClock.elapsedRealtime() - start} prefetch=${priority.prefetch} progressive=${priority.progressive}")
            }
            result
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
