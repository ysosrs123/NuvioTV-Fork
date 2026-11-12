package com.nuvio.tv.core.tmdb

import android.content.ComponentCallbacks2
import android.content.Context
import com.nuvio.tv.core.player.MemoryPressure
import com.nuvio.tv.core.util.ArcDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val FILE_NAME = "tmdb-imdb-ids.tsv"
private const val NO_ID = "-"
private const val NO_ID_KEPT_MS = 7L * 24 * 60 * 60 * 1000
private const val MAX_LINES = 20_000
private const val KEPT_LINES = 10_000
private const val FLUSH_AFTER_MS = 2_000L
internal const val FLUSH_AT_LINES = 200

/**
 * TMDB to IMDb ids kept across restarts, one line per title: `tmdbId:type`, the IMDb id or `-`
 * when TMDB has none, and for `-` the time it was learned. Read once; new ids are kept in memory at once and
 * appended in batches by one writer, so lookups never wait on the disk.
 */
@Singleton
class TmdbIdStore(
    private val file: File?,
    writer: CoroutineDispatcher = ArcDispatchers.background.limitedParallelism(1),
    private val flushAfterMs: Long = FLUSH_AFTER_MS,
    private val now: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, FILE_NAME)) {
        MemoryPressure.addListener(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) { flush() }
    }

    private val ids = ConcurrentHashMap<String, String>()
    private val noIdSince = ConcurrentHashMap<String, Long>()
    private val lock = Any()
    @Volatile private var loaded = false
    private val scope = CoroutineScope(SupervisorJob() + writer)
    private val pending = ConcurrentLinkedQueue<String>()
    private val pendingCount = AtomicInteger()
    private val flushQueued = AtomicBoolean(false)

    /** Every known pair, as (`tmdbId:type`, IMDb id). Blocking: call off the main thread. */
    fun load(): Map<String, String> {
        ensureLoaded()
        return ids
    }

    /** True while TMDB is known to have no IMDb id for this title. */
    fun hasNoImdbId(key: String): Boolean {
        ensureLoaded()
        val since = noIdSince[key] ?: return false
        if (now() - since < NO_ID_KEPT_MS) return true
        noIdSince.remove(key)
        return false
    }

    fun put(key: String, imdbId: String) {
        ensureLoaded()
        noIdSince.remove(key)
        if (ids.put(key, imdbId) != imdbId) append("$key\t$imdbId")
    }

    fun putNoImdbId(key: String) {
        ensureLoaded()
        if (ids.containsKey(key)) return
        val at = now()
        noIdSince[key] = at
        append("$key\t$NO_ID\t$at")
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val lines = runCatching { file?.takeIf(File::exists)?.readLines() }.getOrNull().orEmpty()
            for (line in lines) {
                val parts = line.split('\t')
                val key = parts.getOrNull(0)?.takeIf(String::isNotBlank) ?: continue
                val value = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: continue
                if (value == NO_ID) {
                    val at = parts.getOrNull(2)?.toLongOrNull() ?: continue
                    if (now() - at < NO_ID_KEPT_MS && !ids.containsKey(key)) noIdSince[key] = at
                } else {
                    ids[key] = value
                    noIdSince.remove(key)
                }
            }
            if (lines.size > MAX_LINES) {
                runCatching { file?.writeText(lines.takeLast(KEPT_LINES).joinToString("\n", postfix = "\n")) }
            }
            loaded = true
        }
    }

    private fun append(line: String) {
        if (file == null) return
        pending.add(line)
        if (pendingCount.incrementAndGet() == FLUSH_AT_LINES) {
            scope.launch { flush() }
        } else if (flushQueued.compareAndSet(false, true)) {
            scope.launch {
                delay(flushAfterMs)
                flushQueued.set(false)
                flush()
            }
        }
    }

    /** Writes every id learned since the last flush. */
    fun flush() {
        val target = file ?: return
        synchronized(lock) {
            val batch = StringBuilder()
            while (true) {
                val line = pending.poll() ?: break
                pendingCount.decrementAndGet()
                batch.append(line).append('\n')
            }
            if (batch.isNotEmpty()) runCatching { target.appendText(batch.toString()) }
        }
    }
}
