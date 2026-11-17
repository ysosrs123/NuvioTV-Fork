package com.nuvio.tv.core.image

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.memory.MemoryCache
import coil3.network.HttpException
import coil3.network.httpHeaders
import coil3.request.Options
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.ByteString
import okio.FileSystem
import okio.HashingSource
import okio.blackholeSink
import okio.buffer

/** Failures where the saved copy is still the right picture: no network, a timeout or a busy server. */
internal fun isTransientImageFailure(error: Throwable): Boolean = when (error) {
    is java.io.IOException -> true
    is HttpException -> error.response.code >= 500 || error.response.code == 408 || error.response.code == 429
    else -> false
}

/**
 * Shows a saved picture at once, also one due for an online check, and checks it afterwards ([showSavedFirst]).
 * Coil drops a saved copy whose online check fails; this shows it instead of an empty tile.
 */
class SavedCopyOnErrorFetcher internal constructor(
    private val delegate: Fetcher,
    private val showSavedFirst: (suspend () -> FetchResult?)? = null,
    private val openSavedCopy: () -> FetchResult?,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        showSavedFirst?.invoke()?.let { return it }
        return try {
            delegate.fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isTransientImageFailure(e)) throw e
            currentCoroutineContext().ensureActive()
            val saved = runCatching { openSavedCopy() }.getOrNull() ?: throw e
            savedCopiesServed.incrementAndGet()
            saved
        }
    }

    class Factory internal constructor(
        private val network: Fetcher.Factory<Uri>,
        private val checks: SavedImageChecks,
        private val onChanged: (String, ImageLoader) -> Unit = { url, loader ->
            artworkChanged(url, loader.memoryCache, checks)
        },
        private val isFresh: suspend (DiskCache.Snapshot, FileSystem, String, Options) -> Boolean,
    ) : Fetcher.Factory<Uri> {
        constructor(network: Fetcher.Factory<Uri>) : this(network, SavedImageChecks.shared, isFresh = ::savedImageIsFresh)

        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val fetcher = network.create(data, options, imageLoader) ?: return null
            if (!options.diskCachePolicy.readEnabled) return fetcher
            val url = data.toString()
            val key = options.diskCacheKey ?: url
            val openSaved: () -> FetchResult? = {
                imageLoader.diskCache?.let { cache ->
                    cache.openSnapshot(key)?.let { snapshot ->
                        SourceFetchResult(
                            source = ImageSource(snapshot.data, cache.fileSystem, key, snapshot),
                            mimeType = null,
                            dataSource = DataSource.DISK,
                        )
                    }
                }
            }
            val showSavedFirst: suspend () -> FetchResult? = savedFirst@{
                if (onlineCheckRequested(options.httpHeaders["Cache-Control"])) return@savedFirst null
                val cache = imageLoader.diskCache ?: return@savedFirst null
                val due = withContext(Dispatchers.IO) {
                    cache.openSnapshot(key)?.use { !isFresh(it, cache.fileSystem, url, options) }
                } ?: return@savedFirst null
                if (!due) return@savedFirst null
                val saved = openSaved() ?: return@savedFirst null
                checks.schedule(key, host = imageHost(url)) {
                    val before = savedFingerprint(cache, key)
                    val checked = network.create(data, options, imageLoader)?.fetch()
                    (checked as? SourceFetchResult)?.source?.close()
                    val after = savedFingerprint(cache, key)
                    if (after != null && after != before) onChanged(url, imageLoader)
                }
                savedCopiesServedBeforeCheck.incrementAndGet()
                saved
            }
            return SavedCopyOnErrorFetcher(fetcher, showSavedFirst, openSaved)
        }
    }

    companion object {
        val savedCopiesServed = AtomicInteger()
        val savedCopiesServedBeforeCheck = AtomicInteger()
    }
}

/** A request that asks the server first (no-cache, max-age=0) never gets the saved copy ahead of the check. */
internal fun onlineCheckRequested(cacheControl: String?): Boolean {
    val value = cacheControl?.lowercase() ?: return false
    return "no-cache" in value || "max-age=0" in value
}

/**
 * What the saved picture is, by its bytes: a 304 leaves it alone, and a 200 with the same picture from a site that
 * sends no ETag or Last-Modified gives the same fingerprint although the file was written again.
 */
internal fun savedFingerprint(cache: DiskCache, key: String): ByteString? = runCatching {
    cache.openSnapshot(key)?.use { snapshot ->
        HashingSource.sha256(cache.fileSystem.source(snapshot.data)).use { hashing ->
            hashing.buffer().readAll(blackholeSink())
            hashing.hash
        }
    }
}.getOrNull()

/** Cards ask for the original TMDB size; the request that was checked may be a smaller one. */
internal fun changedArtworkUrls(url: String): List<String> {
    val original = url.replace(Regex("/t/p/w\\d+/"), "/t/p/original/")
    return if (original != url) listOf(url, original) else listOf(url)
}

/** True when a memory cache key is for this picture: the address itself, or the address with a size or version after it. */
internal fun memoryKeyShows(key: String, url: String): Boolean {
    var at = key.indexOf(url)
    while (at >= 0) {
        val end = at + url.length
        if (end == key.length || key[end] in MEMORY_KEY_SEPARATORS) return true
        at = key.indexOf(url, at + 1)
    }
    return false
}

private const val MEMORY_KEY_SEPARATORS = "_:#| "

/** Memory entries of a changed picture under every key: cards off screen, warm-up, hero, details, Continue watching. */
internal fun memoryKeysOf(keys: Set<MemoryCache.Key>, urls: List<String>): List<MemoryCache.Key> =
    keys.filter { key -> urls.any { memoryKeyShows(key.key, it) } }

/**
 * A changed picture: cards on screen redraw with the new one at once (the old one stays as their placeholder for a
 * moment), then every memory copy of the old one is dropped, so a card that comes back later reads the new file.
 */
internal fun artworkChanged(url: String, memory: MemoryCache?, checks: SavedImageChecks) {
    val urls = changedArtworkUrls(url)
    val old = memory?.let { memoryKeysOf(it.keys, urls) }.orEmpty()
    urls.forEach(ImageInvalidationBus::notifyInvalidated)
    if (memory != null && old.isNotEmpty()) checks.later(REDRAW_GRACE_MS) { old.forEach(memory::remove) }
}

private const val REDRAW_GRACE_MS = 3_000L

/** A busy or failing site gets a rest from background checks. */
internal fun siteNeedsRest(error: Throwable): Boolean =
    error is HttpException && (error.response.code == 429 || error.response.code >= 500)

/**
 * Online checks of saved pictures run in the background, two at a time, at most once per picture every 10 minutes.
 * They wait while Home is scrolling or still settling after it opened, and a site that answers 429 or 5xx is left
 * alone for 10 minutes.
 */
internal class SavedImageChecks(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
    parallel: Int = 2,
    private val scrolling: () -> Boolean = { false },
    private val settling: () -> Boolean = { false },
) {
    private val slots = Semaphore(parallel)
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val lastCheck = ConcurrentHashMap<String, Long>()
    private val restingUntil = ConcurrentHashMap<String, Long>()

    fun schedule(key: String, host: String? = null, check: suspend () -> Unit) {
        val last = lastCheck[key]
        if (last != null && now() - last < COOLDOWN_MS) return
        if (resting(host)) return
        if (!running.add(key)) return
        scope.launch {
            try {
                slots.withPermit {
                    while (scrolling() || settling()) delay(SCROLL_WAIT_MS)
                    if (resting(host)) return@withPermit
                    try {
                        check()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (host != null && siteNeedsRest(e)) restingUntil[host] = now() + SITE_REST_MS
                    }
                }
            } finally {
                lastCheck[key] = now()
                running.remove(key)
            }
        }
    }

    private fun resting(host: String?): Boolean = host != null && (restingUntil[host] ?: 0L) > now()

    fun later(delayMs: Long, block: () -> Unit) {
        scope.launch {
            delay(delayMs)
            runCatching(block)
        }
    }

    companion object {
        const val COOLDOWN_MS = 10L * 60 * 1000
        const val SITE_REST_MS = 10L * 60 * 1000
        const val SCROLL_WAIT_MS = 250L
        val shared = SavedImageChecks(
            scrolling = { HomePosterScroll.scrolling },
            settling = { HomeStartCalm.active() },
        )
    }
}
