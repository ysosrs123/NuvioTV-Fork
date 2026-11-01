package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.ImdbTapframeApi
import com.nuvio.tv.data.remote.api.SeriesGraphApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImdbEpisodeRatingsRepository @Inject constructor(
    private val imdbTapframeApi: ImdbTapframeApi,
    private val seriesGraphApi: SeriesGraphApi
) {
    private data class CacheEntry(
        val ratings: Map<Pair<Int, Int>, Double>,
        val expiresAtMs: Long
    )

    private val tag = "ImdbEpisodeRatingsRepo"
    private val cacheTtlMs = 30L * 60L * 1000L
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<Map<Pair<Int, Int>, Double>>>()
    private val inFlightMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal var primaryConfigured = BuildConfig.IMDB_TAPFRAME_API_BASE_URL.isNotBlank()
    internal val primaryOutage = HostOutage()

    suspend fun getEpisodeRatings(
        imdbId: String?,
        tmdbId: Int?
    ): Map<Pair<Int, Int>, Double> {
        val normalizedImdbId = imdbId
            ?.trim()
            ?.takeIf { it.startsWith("tt", ignoreCase = true) }
            ?.substringBefore(':')
        val normalizedTmdbId = tmdbId?.takeIf { it > 0 }
        if (normalizedImdbId == null && normalizedTmdbId == null) return emptyMap()

        val cacheKey = when {
            !normalizedImdbId.isNullOrBlank() -> "imdb:$normalizedImdbId"
            else -> "tmdb:$normalizedTmdbId"
        }

        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) return cached.ratings
            cache.remove(cacheKey)
        }

        val deferred = inFlightMutex.withLock {
            inFlight[cacheKey] ?: scope.async {
                try {
                    fetchEpisodeRatings(
                        imdbId = normalizedImdbId,
                        tmdbId = normalizedTmdbId
                    ).also { result ->
                        cache[cacheKey] = CacheEntry(
                            ratings = result,
                            expiresAtMs = System.currentTimeMillis() + cacheTtlMs
                        )
                    }
                } finally {
                    inFlightMutex.withLock {
                        inFlight.remove(cacheKey)
                    }
                }
            }.also { created ->
                inFlight[cacheKey] = created
            }
        }

        return deferred.await()
    }

    private suspend fun fetchEpisodeRatings(
        imdbId: String?,
        tmdbId: Int?
    ): Map<Pair<Int, Int>, Double> {
        var primaryUnreachable = false
        if (!imdbId.isNullOrBlank() && primaryConfigured && !primaryOutage.isDown()) {
            val primary = fetchFromImdbTapframe(imdbId) { primaryUnreachable = true }
            if (primary.isNotEmpty()) return primary
            if (!primaryUnreachable) Log.w(tag, "Primary episode ratings empty for imdbId=$imdbId, trying fallback.")
        }

        if (tmdbId != null && tmdbId > 0) {
            val fallback = fetchFromSeriesGraph(tmdbId)
            if (primaryUnreachable) primaryOutage.markDown(networkUp = fallback.isNotEmpty(), tag = tag)
            return fallback
        }

        if (primaryUnreachable) primaryOutage.markDown(networkUp = false, tag = tag)
        return emptyMap()
    }

    private suspend fun fetchFromImdbTapframe(
        imdbId: String,
        onUnreachable: () -> Unit
    ): Map<Pair<Int, Int>, Double> {
        return try {
            val response = imdbTapframeApi.getSeasonRatings(imdbId)
            if (!response.isSuccessful) {
                Log.w(tag, "Failed primary season ratings for imdbId=$imdbId (${response.code()})")
                return emptyMap()
            }
            toRatingsMap(response.body().orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (HostOutage.isUnreachable(e)) {
                onUnreachable()
            } else {
                Log.w(tag, "Error fetching primary season ratings for imdbId=$imdbId", e)
            }
            emptyMap()
        }
    }

    private suspend fun fetchFromSeriesGraph(tmdbId: Int): Map<Pair<Int, Int>, Double> {
        return try {
            val response = seriesGraphApi.getSeasonRatings(tmdbId)
            if (!response.isSuccessful) {
                Log.w(tag, "Failed fallback season ratings for tmdbId=$tmdbId (${response.code()})")
                return emptyMap()
            }
            toRatingsMap(response.body().orEmpty())
        } catch (e: Exception) {
            Log.w(tag, "Error fetching fallback season ratings for tmdbId=$tmdbId", e)
            emptyMap()
        }
    }

    private fun toRatingsMap(payload: List<com.nuvio.tv.data.remote.api.SeriesGraphSeasonRatingsDto>): Map<Pair<Int, Int>, Double> {
        return buildMap {
            payload.forEach { season ->
                season.episodes.orEmpty().forEach { episode ->
                    val seasonNumber = episode.seasonNumber ?: return@forEach
                    val episodeNumber = episode.episodeNumber ?: return@forEach
                    val voteAverage = episode.voteAverage ?: return@forEach
                    put(seasonNumber to episodeNumber, voteAverage)
                }
            }
        }
    }
}
