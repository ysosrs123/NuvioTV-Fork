package com.nuvio.tv.data.iptv

import android.content.Context
import com.nuvio.tv.core.iptv.VodArt
import com.nuvio.tv.core.iptv.VodArtCache
import com.nuvio.tv.core.iptv.VodArtCandidate
import com.nuvio.tv.core.iptv.VodArtwork
import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.core.iptv.VodRef
import com.nuvio.tv.core.iptv.VodTitles
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class IptvVodArtworkMode { PROVIDER, NUVIO }

class IptvVodArtworkPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("iptv-vod", Context.MODE_PRIVATE)

    var mode: IptvVodArtworkMode
        get() = IptvVodArtworkMode.entries.firstOrNull { it.name == preferences.getString(KEY_MODE, null) } ?: IptvVodArtworkMode.PROVIDER
        set(value) { preferences.edit().putString(KEY_MODE, value.name).apply() }

    private companion object { const val KEY_MODE = "artwork" }
}

class IptvVodArtRateLimited(val retryAfterMillis: Long) : IOException("Rate limited")

interface IptvVodArtLookup {
    suspend fun details(kind: VodKind, tmdbId: String, language: String): VodArt?
    suspend fun tmdbId(kind: VodKind, imdbId: String): String?
    suspend fun search(kind: VodKind, title: String, year: Int, language: String): List<VodArtCandidate>
    suspend fun addon(kind: VodKind, imdbId: String): VodArt?
}

class IptvVodArtwork(
    private val directory: File,
    private val lookup: IptvVodArtLookup,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 2_000,
    private val parallel: Int = 2,
) {
    private val cache = VodArtCache(maxEntries, POSITIVE, NEGATIVE, now)
    private val loadLock = Mutex()
    private val saveLock = Mutex()
    private val permits = Semaphore(parallel)
    @Volatile private var loaded = false
    @Volatile private var dirty = false
    @Volatile private var pausedUntil = 0L

    fun cached(title: IptvVodTitle, language: String): VodArt? = key(title, language)?.let(cache::get)

    fun known(title: IptvVodTitle, language: String): Boolean = key(title, language)?.let(cache::contains) ?: true

    suspend fun resolve(titles: List<IptvVodTitle>, language: String): Map<VodRef, VodArt> = withContext(Dispatchers.IO) {
        load()
        val wanted = titles.filter { it.ref.kind != VodKind.EPISODE }.distinctBy { it.ref }.take(MAX_BATCH)
        val found = coroutineScope {
            wanted.map { title -> async { title.ref to permits.withPermit { one(title, language) } } }.awaitAll()
        }
        save()
        found.mapNotNull { (ref, art) -> art?.let { ref to it } }.toMap()
    }

    private suspend fun one(title: IptvVodTitle, language: String): VodArt? {
        val key = key(title, language) ?: return null
        if (cache.contains(key)) return cache.get(key)
        if (now() < pausedUntil) return null
        return try {
            val kind = title.ref.kind
            val tmdb = title.tmdbId ?: title.imdbId?.let { lookup.tmdbId(kind, it) }
                ?: if (title.imdbId == null) (title.year ?: VodTitles.parse(title.title).year)?.let { year ->
                    VodArtwork.pick(title.title, year, lookup.search(kind, title.title, year, language))?.tmdbId
                } else null
            val art = tmdb?.let { lookup.details(kind, it, language) } ?: title.imdbId?.let { lookup.addon(kind, it) }
            val result = art?.copy(tmdbId = art.tmdbId ?: tmdb, imdbId = art.imdbId ?: title.imdbId)?.takeIf { it.matched }
            cache.put(key, result)
            dirty = true
            result
        } catch (cancel: CancellationException) { throw cancel }
        catch (limited: IptvVodArtRateLimited) {
            pausedUntil = now() + limited.retryAfterMillis.coerceIn(MIN_PAUSE, MAX_PAUSE)
            IptvLog.info("vod artwork paused ms=${limited.retryAfterMillis.coerceIn(MIN_PAUSE, MAX_PAUSE)}")
            null
        } catch (error: Exception) {
            IptvLog.failure("vod artwork", error)
            null
        }
    }

    private fun key(title: IptvVodTitle, language: String): String? =
        if (title.ref.kind == VodKind.EPISODE) null else VodArtwork.key(language, title.ref.kind, title.tmdbId, title.imdbId, title.title, title.year)

    private suspend fun load() {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            try {
                val file = File(directory, FILE)
                if (file.isFile && file.length() <= MAX_FILE) file.bufferedReader().useLines { cache.load(it) }
            } catch (error: Exception) { IptvLog.failure("vod artwork load", error) }
            loaded = true
        }
    }

    private suspend fun save() {
        if (!dirty) return
        saveLock.withLock {
            if (!dirty) return
            dirty = false
            try {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Artwork cache folder")
                val temp = File(directory, "$FILE.tmp")
                temp.bufferedWriter().use { writer -> cache.lines().forEach { writer.write(it); writer.newLine() } }
                if (!temp.renameTo(File(directory, FILE))) { temp.delete(); throw IOException("Artwork cache save") }
            } catch (error: Exception) { IptvLog.failure("vod artwork save", error) }
        }
    }

    companion object {
        const val MAX_BATCH = 48
        private const val FILE = "artwork.jsonl"
        private const val MAX_FILE = 4L * 1024 * 1024
        private const val POSITIVE = 30L * 24 * 60 * 60 * 1000
        private const val NEGATIVE = 3L * 24 * 60 * 60 * 1000
        private const val MIN_PAUSE = 2_000L
        private const val MAX_PAUSE = 120_000L
    }
}
