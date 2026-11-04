package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class WeTrakrRelatedService @Inject constructor(
    private val api: WeTrakrApiClient,
    private val auth: WeTrakrAuthStore
) {
    private class Cached(val items: List<MetaPreview>, val atMs: Long)

    private val cache = WeTrakrLruCache<String, Cached>(CACHE_SIZE)
    private val ids = WeTrakrLruCache<String, Long>(CACHE_SIZE)

    val isAvailable: Boolean
        get() = auth.state.value.isAuthenticated

    suspend fun getRelated(meta: Meta, fallbackItemId: String? = null): List<MetaPreview> = withContext(Dispatchers.IO) {
        val show = meta.type != ContentType.MOVIE && meta.apiType.lowercase() !in setOf("movie", "film")
        val external = listOfNotNull(meta.imdbId, meta.id, fallbackItemId)
            .map(::parseTrackingExternalIds).map { it.toWeTrakrIds() }
            .fold(WeTrakrIds()) { acc, next -> acc.merged(next) }
        val lookup = external.imdb?.let { "imdb" to it } ?: external.tmdb?.let { "tmdb" to it.toString() }
            ?: external.tvdb?.let { "tvdb" to it.toString() } ?: return@withContext emptyList()
        val type = if (show) "show" else "movie"
        val key = "$type|${lookup.first}|${lookup.second}"
        cache[key]?.takeIf { System.currentTimeMillis() - it.atMs <= CACHE_MS }?.let { return@withContext it.items }
        if (!api.hasQuotaToSpare(auth.scope())) return@withContext emptyList()
        val id = ids[key] ?: resolve(lookup.first, lookup.second, type)?.also { ids[key] = it } ?: return@withContext emptyList()
        val response = api.get(
            "/${if (show) "shows" else "movies"}/$id/similar",
            mapOf("limit" to LIMIT.toString()),
            acceptedStatuses = setOf(404)
        )
        val items = if (response.status == 404) emptyList() else decodeWeTrakrSimilar(response.body).mapNotNull(::toPreview)
        cache[key] = Cached(items, System.currentTimeMillis())
        items
    }

    private suspend fun resolve(source: String, value: String, type: String): Long? {
        val response = api.get("/media/external/$source/$value", mapOf("type" to type), acceptedStatuses = setOf(404, 409))
        if (response.status != 200) return null
        return weTrakrJson(response.body).obj()?.long("id")?.takeIf { it > 0L }
    }

    private fun toPreview(title: WeTrakrTitle): MetaPreview? {
        val name = title.title ?: return null
        val contentId = title.ids.imdb ?: title.ids.tmdb?.let { "tmdb:$it" } ?: return null
        val movie = title.type == WeTrakrType.MOVIE
        return MetaPreview(
            id = contentId,
            type = if (movie) ContentType.MOVIE else ContentType.SERIES,
            rawType = if (movie) "movie" else "series",
            name = name,
            poster = title.poster,
            posterShape = PosterShape.POSTER,
            background = title.backdrop,
            logo = null,
            description = null,
            releaseInfo = title.year?.toString(),
            imdbRating = null,
            genres = emptyList(),
            imdbId = title.ids.imdb,
            rawPosterUrl = title.poster
        )
    }

    private companion object {
        const val LIMIT = 20
        const val CACHE_MS = 10L * 60 * 1_000
        const val CACHE_SIZE = 100
    }
}

internal class WeTrakrLruCache<K, V>(private val maxSize: Int) {
    private val entries = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }

    operator fun get(key: K): V? = synchronized(entries) { entries[key] }

    operator fun set(key: K, value: V) {
        synchronized(entries) { entries[key] = value }
    }

    val size: Int
        get() = synchronized(entries) { entries.size }
}
