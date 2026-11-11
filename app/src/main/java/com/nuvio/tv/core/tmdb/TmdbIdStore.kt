package com.nuvio.tv.core.tmdb

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val FILE_NAME = "tmdb-imdb-ids.tsv"
private const val NO_ID = "-"
private const val NO_ID_KEPT_MS = 7L * 24 * 60 * 60 * 1000
private const val MAX_LINES = 20_000
private const val KEPT_LINES = 10_000

/**
 * TMDB to IMDb ids kept across restarts, one line per title: `tmdbId:type`, the IMDb id or `-`
 * when TMDB has none, and for `-` the time it was learned. Read once, appended to afterwards.
 */
@Singleton
class TmdbIdStore(
    private val file: File?,
    private val now: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, FILE_NAME))

    private val ids = ConcurrentHashMap<String, String>()
    private val noIdSince = ConcurrentHashMap<String, Long>()
    private val lock = Any()
    @Volatile private var loaded = false

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
        val target = file ?: return
        synchronized(lock) { runCatching { target.appendText(line + "\n") } }
    }
}
