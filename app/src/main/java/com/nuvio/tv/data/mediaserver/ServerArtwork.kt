package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.Lazy
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Addon artwork for server titles that carry a catalogue id. Only the small fields a row or the
 * hero shows are kept, so a server page does not pin full metas in memory.
 */
@Singleton
class ServerArtwork internal constructor(
    private val metaRepository: Lazy<MetaRepository>,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(metaRepository: Lazy<MetaRepository>) :
        this(metaRepository, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private class Artwork(
        val name: String?,
        val poster: String?,
        val background: String?,
        val logo: String?,
        val landscapePoster: String?,
        val description: String?,
        val imdbRating: Float?,
        val genres: List<String>
    )

    private class Entry(val artwork: Artwork?, val expiresAtMs: Long)

    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > MAX_ENTRIES
    }
    private val inFlight = ConcurrentHashMap<String, Job>()
    private val permits = Semaphore(MAX_CONCURRENT_FETCHES)

    fun cached(items: List<MetaPreview>): List<MetaPreview> {
        var changed = false
        val mapped = items.map { item ->
            val artwork = key(item)?.let { entry(it, item) }?.artwork ?: return@map item
            item.with(artwork).also { if (it != item) changed = true }
        }
        return if (changed) mapped else items
    }

    suspend fun resolve(items: List<MetaPreview>, waitMs: Long): List<MetaPreview> {
        val pending = items.mapNotNull { item ->
            val key = key(item) ?: return@mapNotNull null
            if (entry(key, item) != null) null else fetch(key, item)
        }
        if (pending.isNotEmpty()) withTimeoutOrNull(waitMs) { pending.joinAll() }
        return cached(items)
    }

    suspend fun settle(items: List<MetaPreview>, timeoutMs: Long) {
        val pending = items.mapNotNull { item -> key(item)?.let(inFlight::get) }
        if (pending.isNotEmpty()) withTimeoutOrNull(timeoutMs) { pending.joinAll() }
    }

    private fun key(item: MetaPreview): String? {
        if (ServerItemRef.isServerId(item.id) || ServerMediaKind.fromContentType(item.apiType) == null) return null
        return "${item.apiType}:${item.id}"
    }

    private fun entry(key: String, item: MetaPreview): Entry? {
        synchronized(cache) {
            val entry = cache[key]
            if (entry != null && entry.expiresAtMs > System.currentTimeMillis()) return entry
            if (entry != null) cache.remove(key)
        }
        val meta = metaRepository.get().getCachedMeta(item.apiType, item.id) ?: return null
        return store(key, meta.artwork())
    }

    private fun store(key: String, artwork: Artwork?): Entry {
        val ttl = if (artwork != null) HIT_TTL_MS else MISS_TTL_MS
        val entry = Entry(artwork, System.currentTimeMillis() + ttl)
        synchronized(cache) { cache[key] = entry }
        return entry
    }

    private fun fetch(key: String, item: MetaPreview): Job {
        inFlight[key]?.let { return it }
        val type = item.apiType
        val id = item.id
        val job = scope.launch(start = CoroutineStart.LAZY) {
            store(key, permits.withPermit { load(type, id) })
        }
        inFlight.putIfAbsent(key, job)?.let { existing ->
            job.cancel()
            return existing
        }
        job.invokeOnCompletion { inFlight.remove(key, job) }
        job.start()
        return job
    }

    private suspend fun load(type: String, id: String): Artwork? =
        try {
            val repository = metaRepository.get()
            val meta = repository.getCachedMeta(type, id)
                ?: (repository.getMetaFromAllAddons(type, id).first { it !is NetworkResult.Loading } as? NetworkResult.Success)?.data
            meta?.artwork()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    private fun Meta.artwork(): Artwork? {
        val artwork = Artwork(
            name = name.takeIf { it.isNotBlank() },
            poster = poster?.takeIf { it.isNotBlank() },
            background = background?.takeIf { it.isNotBlank() },
            logo = logo?.takeIf { it.isNotBlank() },
            landscapePoster = landscapePoster?.takeIf { it.isNotBlank() },
            description = description?.takeIf { it.isNotBlank() },
            imdbRating = imdbRating,
            genres = genres
        )
        return artwork.takeIf { it.poster != null || it.background != null || it.logo != null }
    }

    private fun MetaPreview.with(artwork: Artwork): MetaPreview = copy(
        name = artwork.name ?: name,
        poster = artwork.poster ?: poster,
        rawPosterUrl = if (artwork.poster != null) null else rawPosterUrl,
        background = artwork.background ?: background,
        logo = artwork.logo ?: logo,
        landscapePoster = artwork.landscapePoster ?: landscapePoster,
        description = artwork.description ?: description,
        imdbRating = artwork.imdbRating ?: imdbRating,
        genres = artwork.genres.ifEmpty { genres }
    )

    private companion object {
        const val MAX_ENTRIES = 400
        const val MAX_CONCURRENT_FETCHES = 4
        const val HIT_TTL_MS = 45 * 60 * 1000L
        const val MISS_TTL_MS = 10 * 60 * 1000L
    }
}
