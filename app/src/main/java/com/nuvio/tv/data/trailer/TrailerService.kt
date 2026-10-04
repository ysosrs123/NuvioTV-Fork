package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbVideoResult
import com.nuvio.tv.data.remote.api.TrailerApi
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.TrailerSource
import java.time.Clock
import java.net.URI
import java.time.Instant
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "TrailerService"
private const val TMDB_TRAILER_FALLBACK_LANGUAGE = "en-US"
private val NEGATIVE_CACHE_TTL: Duration = Duration.ofMinutes(10)
private const val TMDB_TRAILER_MAX_CANDIDATES = 3

@Singleton
class TrailerService(
    private val trailerApi: TrailerApi,
    private val tmdbApi: TmdbApi,
    private val inAppYouTubeExtractor: InAppYouTubeExtractor,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tmdbService: TmdbService,
    private val imdbTrailerResolver: ImdbTrailerResolver,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val clock: Clock
) {
    @Inject
    constructor(
        trailerApi: TrailerApi,
        tmdbApi: TmdbApi,
        inAppYouTubeExtractor: InAppYouTubeExtractor,
        tmdbSettingsDataStore: TmdbSettingsDataStore,
        tmdbService: TmdbService,
        imdbTrailerResolver: ImdbTrailerResolver,
        trailerSettingsDataStore: TrailerSettingsDataStore
    ) : this(
        trailerApi = trailerApi,
        tmdbApi = tmdbApi,
        inAppYouTubeExtractor = inAppYouTubeExtractor,
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        tmdbService = tmdbService,
        imdbTrailerResolver = imdbTrailerResolver,
        trailerSettingsDataStore = trailerSettingsDataStore,
        clock = Clock.systemUTC()
    )

    // Cache: "title|year|tmdbId|type" -> trailer playback source (NEGATIVE_CACHE sentinel for misses)
    private val cache = ConcurrentHashMap<String, CachedTrailerPlaybackSource>()
    private val NEGATIVE_CACHE = TrailerPlaybackSource(videoUrl = "")
    // youtubeVideoId -> resolved playback source (success-only)
    private val youtubeSourceCache = ConcurrentHashMap<String, CachedTrailerPlaybackSource>()

    /**
     * Search for a trailer by title, year, tmdbId, and type.
     * Returns the trailer playback source (video URL + optional separate audio URL) or null.
     */
    suspend fun getTrailerPlaybackSource(
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null,
        ignoreUseTrailersGate: Boolean = false
    ): TrailerPlaybackSource? = lookupTrailer(title, year, tmdbId, type, ignoreUseTrailersGate).source

    /**
     * Same lookup as [getTrailerPlaybackSource], and also says whether a miss is definite
     * (nothing to try) or may have been a timeout, rate limit or failed request.
     */
    suspend fun lookupTrailer(
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null,
        ignoreUseTrailersGate: Boolean = false
    ): TrailerLookupResult = withContext(Dispatchers.IO) {
        // Read both settings once up front. `useTrailers` (the TMDB-enrichment
        // "Disable Trailers" toggle) gates the TMDB->YouTube path only (#1647).
        // The IMDb source is a separate, user-selected opt-in ("Use IMDb trailers")
        // and must run independently of the TMDB-enrichment gate, otherwise an
        // unrelated TMDB setting silently disables IMDb.
        val tmdbSettings = runCatching { tmdbSettingsDataStore.settings.first() }.getOrNull()
        val useTrailers = tmdbSettings?.useTrailers == true

        // Read the trailer-source setting BEFORE building the cache key and fold it in, so
        // toggling YouTube<->IMDb takes effect immediately (each source has its own cache
        // namespace) instead of serving this session's stale other-source entries.
        val trailerSettings = runCatching { trailerSettingsDataStore.settings.first() }.getOrNull()
        val trailerSource = trailerSettings?.source ?: TrailerSource.YOUTUBE

        // Nothing to surface when trailers are disabled AND the user hasn't opted
        // into IMDb: the only remaining source here is the (gated) TMDB->YouTube
        // path, so return no trailer. Preserves #1647 for the YouTube case.
        // Post-play recommendations bypass this gate because they have no
        // meta-addon trailer to fall back on.
        if (!ignoreUseTrailersGate && !useTrailers && trailerSource != TrailerSource.IMDB) {
            Log.d(TAG, "Trailers disabled in TMDB enrichment settings; skipping lookup")
            return@withContext TrailerLookupResult.DEFINITE_MISS
        }

        val cacheKey = "$title|$year|$tmdbId|$type|$trailerSource"

        freshEntry(cache, cacheKey)?.let { cached ->
            val hit = cached !== NEGATIVE_CACHE
            Log.d(TAG, "Cache hit for $cacheKey: $hit")
            return@withContext if (hit) TrailerLookupResult(cached) else TrailerLookupResult.DEFINITE_MISS
        }

        try {
            Log.d(TAG, "Searching trailer: title=$title, year=$year, tmdbId=$tmdbId, type=$type")

            // IMDb-first path (opt-in via Settings > Layout). On success, cache + return;
            // on null (no trailer >=720p -- SD-only or scene-only title) fall through to the
            // existing TMDB->YouTube path below. This is the null-means-fallthrough contract the
            // hero/detail callers already expect (they treat a returned source as cacheable).
            var imdbFailed = false
            if (trailerSource == TrailerSource.IMDB) {
                val imdbId = tmdbId?.toIntOrNull()?.let { runCatching { tmdbService.tmdbToImdb(it, type ?: "movie") }.getOrNull() }
                if (imdbId != null) {
                    val imdbSource = imdbTrailerResolver.resolve(imdbId, type)
                    if (imdbSource != null) {
                        cache[cacheKey] = cachedEntry(imdbSource)
                        return@withContext TrailerLookupResult(imdbSource)
                    }
                    imdbFailed = true
                    Log.d(TAG, "IMDb resolve returned null for $imdbId; falling through to YouTube")
                }
            }

            // TMDB->YouTube path. Gated on `useTrailers` (#1647): when trailers are
            // disabled in TMDB enrichment, skip it entirely. An IMDb-only user with
            // the gate off gets IMDb-or-nothing from this function, and the caller's
            // meta-provided YouTube fallback still applies. Not negative-cached: the
            // "no trailer" result here is gate-conditional, so caching it under the
            // gate-independent key would go stale if the user re-enables trailers.
            if (!useTrailers) {
                return@withContext TrailerLookupResult(null, definiteMiss = !imdbFailed)
            }

            val tmdbLanguage = normalizeTmdbTrailerLanguage(tmdbSettings?.language)
            val tmdbResult = lookupTmdbTrailer(
                tmdbId = tmdbId,
                type = type,
                title = title,
                year = year,
                languageOverride = tmdbLanguage
            )
            tmdbResult.source?.let { tmdbSource ->
                cache[cacheKey] = cachedEntry(tmdbSource)
                return@withContext tmdbResult
            }
            Log.w(TAG, "TMDB path exhausted; no YouTube trailer key resolved for backend /trailer fallback")
            val definiteMiss = tmdbResult.definiteMiss && !imdbFailed
            // Only cache negative result if tmdbId was available — if null, enrichment
            // may not have completed yet and a retry with tmdbId could succeed.
            if (tmdbId != null && definiteMiss) {
                cache[cacheKey] = cachedEntry(NEGATIVE_CACHE)
            }
            TrailerLookupResult(null, definiteMiss)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The detail screen cancels the previous trailer job every time it starts a new
            // one, so this is routine. Swallowing it returned null, which the caller cannot
            // tell apart from "this title has no trailer" -- it wrote that null over a URL a
            // later job had already resolved, and the NEGATIVE_CACHE line above pinned the
            // miss for the rest of the process, so the trailer button stayed gone.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching trailer for $title: ${e.message}", e)
            TrailerLookupResult(null, definiteMiss = false)
        }
    }

    /**
     * Search for a trailer and return its primary video URL for existing call sites.
     */
    suspend fun getTrailerUrl(
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null
    ): String? {
        return getTrailerPlaybackSource(
            title = title,
            year = year,
            tmdbId = tmdbId,
            type = type
        )?.videoUrl
    }

    suspend fun getExternalTrailerUrl(
        tmdbId: String?,
        type: String?
    ): String? = withContext(Dispatchers.IO) {
        // Parse the id first so an invalid/null tmdbId short-circuits without
        // touching the settings DataStore at all.
        val numericTmdbId = tmdbId?.toIntOrNull() ?: return@withContext null
        // Read settings once and use for both the "Disable Trailers" gate and
        // the trailer language. See #1647 for the gate rationale.
        val tmdbSettings = runCatching { tmdbSettingsDataStore.settings.first() }.getOrNull()
        if (tmdbSettings?.useTrailers != true) {
            return@withContext null
        }
        val mediaType = normalizeTmdbMediaType(type)
        val tmdbLanguage = normalizeTmdbTrailerLanguage(tmdbSettings.language)
        val tmdbResults = fetchTmdbVideos(numericTmdbId, mediaType, tmdbLanguage).orEmpty()
        rankTmdbVideoCandidates(tmdbResults, preferredLanguageCode = tmdbLanguage)
            .firstOrNull()
            ?.key
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { "https://www.youtube.com/watch?v=$it" }
    }

    /**
     * TMDB-first resolution using /movie/{id}/videos or /tv/{id}/videos.
     */
    suspend fun getTrailerPlaybackSourceFromTmdbId(
        tmdbId: String?,
        type: String?,
        title: String? = null,
        year: String? = null,
        languageOverride: String? = null
    ): TrailerPlaybackSource? = lookupTmdbTrailer(tmdbId, type, title, year, languageOverride).source

    private suspend fun lookupTmdbTrailer(
        tmdbId: String?,
        type: String?,
        title: String?,
        year: String?,
        languageOverride: String?
    ): TrailerLookupResult = withContext(Dispatchers.IO) {
        val numericTmdbId = tmdbId?.toIntOrNull()
            ?: return@withContext TrailerLookupResult(null, definiteMiss = false)
        val mediaType = normalizeTmdbMediaType(type)
        val tmdbLanguage = languageOverride ?: getPreferredTmdbTrailerLanguage()
        Log.d(
            TAG,
            "TMDB trailer lookup start: tmdbId=$numericTmdbId type=${mediaType ?: "unknown"} language=$tmdbLanguage"
        )

        val tmdbResults = fetchTmdbVideos(numericTmdbId, mediaType, tmdbLanguage)
            ?: return@withContext TrailerLookupResult(null, definiteMiss = false)

        val candidates = rankTmdbVideoCandidates(tmdbResults, preferredLanguageCode = tmdbLanguage)
        Log.d(TAG, "TMDB candidate count: ${candidates.size}")

        for (candidate in candidates.take(TMDB_TRAILER_MAX_CANDIDATES)) {
            val key = candidate.key?.trim().orEmpty()
            if (key.isBlank()) continue
            Log.d(
                TAG,
                "TMDB selected candidate: type=${candidate.type.orEmpty()} " +
                    "official=${candidate.official == true} key=${obfuscateYoutubeKey(key)}"
            )

            val youtubeUrl = "https://www.youtube.com/watch?v=$key"
            val source = getTrailerPlaybackSourceFromYouTubeUrl(
                youtubeUrl = youtubeUrl,
                title = title,
                year = year
            )
            if (source != null) {
                return@withContext TrailerLookupResult(source)
            }

            Log.d(
                TAG,
                "TMDB candidate extraction failed, trying next: key=${obfuscateYoutubeKey(key)}"
            )
            if (inAppYouTubeExtractor.unplayableReason(youtubeUrl) == YouTubeUnplayableReason.SIGN_IN_REQUIRED) break
        }

        TrailerLookupResult(null, definiteMiss = candidates.isEmpty())
    }

    /**
     * Resolve a YouTube trailer URL to a playback source (prefers in-app extraction).
     */
    suspend fun getTrailerPlaybackSourceFromYouTubeUrl(
        youtubeUrl: String,
        title: String? = null,
        year: String? = null
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        try {
            val youtubeKey = youTubeVideoIdOf(youtubeUrl)
            if (!youtubeKey.isNullOrBlank()) {
                freshEntry(youtubeSourceCache, youtubeKey)?.let { cached ->
                    Log.d(TAG, "YouTube cache hit for key=${obfuscateYoutubeKey(youtubeKey)}")
                    return@withContext cached
                }
            }

            Log.d(TAG, "Attempting in-app YouTube extraction for ${summarizeUrl(youtubeUrl)}")
            val localSource = inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl)
            if (localSource != null) {
                if (!youtubeKey.isNullOrBlank()) {
                    youtubeSourceCache[youtubeKey] = cachedEntry(localSource)
                }
                Log.d(
                    TAG,
                    "Using in-app YouTube source for ${summarizeUrl(youtubeUrl)} " +
                        "(audioPresent=${!localSource.audioUrl.isNullOrBlank()})"
                )
                return@withContext localSource
            }

            // Fallback to remote trailer resolver if in-app extraction fails.
            Log.w(TAG, "In-app extraction failed, falling back to backend resolver for ${summarizeUrl(youtubeUrl)}")
            val response = trailerApi.getTrailer(youtubeUrl = youtubeUrl, title = title, year = year)
            if (!response.isSuccessful) {
                Log.w(TAG, "Backend trailer fallback failed (${response.code()}) for ${summarizeUrl(youtubeUrl)}")
                return@withContext null
            }

            val fallbackUrl = response.body()?.url ?: return@withContext null
            if (!isValidUrl(fallbackUrl)) return@withContext null

            if (!youtubeKey.isNullOrBlank()) {
                youtubeSourceCache[youtubeKey] = cachedEntry(TrailerPlaybackSource(videoUrl = fallbackUrl))
            }
            Log.d(TAG, "Using backend fallback source for ${summarizeUrl(youtubeUrl)}")
            TrailerPlaybackSource(videoUrl = fallbackUrl)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error getting trailer from YouTube: ${e.message}", e)
            null
        }
    }

    /** Same as [getTrailerPlaybackSourceFromYouTubeUrl], and on a miss says why when YouTube refused the video. */
    suspend fun lookupYouTubeTrailer(
        youtubeUrl: String,
        title: String? = null,
        year: String? = null
    ): YouTubeTrailerLookup {
        val source = getTrailerPlaybackSourceFromYouTubeUrl(youtubeUrl, title, year)
        return YouTubeTrailerLookup(
            source = source,
            unplayable = if (source == null) inAppYouTubeExtractor.unplayableReason(youtubeUrl) else null
        )
    }

    /**
     * Compatibility method for existing callers expecting a single URL.
     */
    suspend fun getTrailerFromYouTubeUrl(
        youtubeUrl: String,
        title: String? = null,
        year: String? = null
    ): String? {
        return getTrailerPlaybackSourceFromYouTubeUrl(
            youtubeUrl = youtubeUrl,
            title = title,
            year = year
        )?.videoUrl
    }

    /** Null when a request failed, so an empty list always means TMDB has no videos. */
    private suspend fun fetchTmdbVideos(
        tmdbId: Int,
        mediaType: String?,
        language: String
    ): List<TmdbVideoResult>? = when (mediaType) {
        "movie" -> fetchTmdbMovieVideos(tmdbId, language)
        "tv" -> fetchTmdbTvVideos(tmdbId, language)
        else -> {
            val movie = fetchTmdbMovieVideos(tmdbId, language)
            val tv = fetchTmdbTvVideos(tmdbId, language)
            if ((movie == null || tv == null) && movie.isNullOrEmpty() && tv.isNullOrEmpty()) {
                null
            } else {
                movie.orEmpty() + tv.orEmpty()
            }
        }
    }

    private suspend fun fetchTmdbMovieVideos(tmdbId: Int, preferredLanguage: String): List<TmdbVideoResult>? {
        val localized = fetchTmdbMovieVideosOnce(tmdbId, preferredLanguage)
        if (!localized.isNullOrEmpty() || preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)) {
            return localized
        }
        Log.d(TAG, "TMDB movie videos localized miss for $tmdbId ($preferredLanguage), retrying $TMDB_TRAILER_FALLBACK_LANGUAGE")
        return mergeLanguageFallback(localized, fetchTmdbMovieVideosOnce(tmdbId, TMDB_TRAILER_FALLBACK_LANGUAGE))
    }

    private suspend fun fetchTmdbTvVideos(tmdbId: Int, preferredLanguage: String): List<TmdbVideoResult>? {
        val localized = fetchTmdbTvVideosOnce(tmdbId, preferredLanguage)
        if (!localized.isNullOrEmpty() || preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)) {
            return localized
        }
        Log.d(TAG, "TMDB tv videos localized miss for $tmdbId ($preferredLanguage), retrying $TMDB_TRAILER_FALLBACK_LANGUAGE")
        return mergeLanguageFallback(localized, fetchTmdbTvVideosOnce(tmdbId, TMDB_TRAILER_FALLBACK_LANGUAGE))
    }

    private fun mergeLanguageFallback(
        localized: List<TmdbVideoResult>?,
        fallback: List<TmdbVideoResult>?
    ): List<TmdbVideoResult>? = when {
        fallback == null -> null
        fallback.isEmpty() && localized == null -> null
        else -> fallback
    }

    private suspend fun fetchTmdbMovieVideosOnce(tmdbId: Int, language: String): List<TmdbVideoResult>? {
        return try {
            val response = tmdbApi.getMovieVideos(
                movieId = tmdbId,
                apiKey = tmdbService.apiKey(),
                language = language
            )
            if (!response.isSuccessful) {
                Log.w(TAG, "TMDB movie videos request failed ($tmdbId/$language): ${response.code()}")
                null
            } else {
                response.body()?.results.orEmpty()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TMDB movie videos error ($tmdbId/$language): ${e.message}")
            null
        }
    }

    private suspend fun fetchTmdbTvVideosOnce(tmdbId: Int, language: String): List<TmdbVideoResult>? {
        return try {
            val response = tmdbApi.getTvVideos(
                tvId = tmdbId,
                apiKey = tmdbService.apiKey(),
                language = language
            )
            if (!response.isSuccessful) {
                Log.w(TAG, "TMDB tv videos request failed ($tmdbId/$language): ${response.code()}")
                null
            } else {
                response.body()?.results.orEmpty()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TMDB tv videos error ($tmdbId/$language): ${e.message}")
            null
        }
    }

    private suspend fun getPreferredTmdbTrailerLanguage(): String {
        val rawLanguage = runCatching { tmdbSettingsDataStore.settings.first().language }.getOrNull()
        return normalizeTmdbTrailerLanguage(rawLanguage)
    }

    private fun isValidUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.startsWith("http://") || url.startsWith("https://")
    }

    private fun summarizeUrl(url: String): String {
        return runCatching {
            val uri = URI(url)
            val host = uri.host ?: "unknown-host"
            val path = uri.path ?: "/"
            "$host$path"
        }.getOrDefault(url.take(80))
    }

    private fun obfuscateYoutubeKey(key: String): String {
        if (key.length <= 4) return "****"
        return "***${key.takeLast(4)}"
    }

    private fun cachedEntry(source: TrailerPlaybackSource) =
        CachedTrailerPlaybackSource(playbackSource = source, cachedAt = Instant.now(clock))

    private fun freshEntry(
        map: ConcurrentHashMap<String, CachedTrailerPlaybackSource>,
        key: String
    ): TrailerPlaybackSource? {
        val cached = map[key] ?: return null
        val now = Instant.now(clock)
        val stale = if (cached.playbackSource === NEGATIVE_CACHE) {
            Duration.between(cached.cachedAt, now) >= NEGATIVE_CACHE_TTL
        } else {
            isTrailerSourceStale(cached.playbackSource, cached.cachedAt, now) ||
                TrailerPlaybackFailures.hasFailed(cached.playbackSource.videoUrl)
        }
        if (!stale) return cached.playbackSource
        map.remove(key, cached)
        return null
    }

    fun clearCache() {
        cache.clear()
        youtubeSourceCache.clear()
    }

    private data class CachedTrailerPlaybackSource(
        val playbackSource: TrailerPlaybackSource,
        val cachedAt: Instant
    )
}

data class TrailerLookupResult(
    val source: TrailerPlaybackSource?,
    val definiteMiss: Boolean = false
) {
    companion object {
        val DEFINITE_MISS = TrailerLookupResult(null, definiteMiss = true)
    }
}

data class YouTubeTrailerLookup(
    val source: TrailerPlaybackSource?,
    val unplayable: YouTubeUnplayableReason? = null
)

internal fun normalizeTmdbTrailerLanguage(language: String?): String {
    val normalized = language
        ?.trim()
        ?.replace('_', '-')
        ?.takeIf { it.isNotBlank() }
        ?: return TMDB_TRAILER_FALLBACK_LANGUAGE

    val formatted = if (normalized.contains('-')) {
        val parts = normalized.split("-", limit = 2)
        val locale = parts[0].lowercase()
        val region = parts.getOrNull(1)?.uppercase()?.takeIf { it.isNotBlank() }
        if (region != null) "$locale-$region" else locale
    } else {
        normalized.lowercase()
    }

    if (formatted == "en") return TMDB_TRAILER_FALLBACK_LANGUAGE

    // Map codes unsupported by TMDB to their closest equivalent
    return when (formatted) {
        "es-419" -> "es-MX"
        else -> formatted
    }
}

internal fun normalizeTmdbMediaType(type: String?): String? {
    return when (type?.lowercase()) {
        "movie", "film" -> "movie"
        "tv", "series", "show", "tvshow" -> "tv"
        else -> null
    }
}

/**
 * Ranks candidates for the user's chosen TMDB trailer language first (e.g. "en-US",
 * "fr-FR"), falling back to English as a safety net when nothing matches that
 * language, and only then to whatever else is available.
 */
internal fun rankTmdbVideoCandidates(
    results: List<TmdbVideoResult>,
    preferredLanguageCode: String = TMDB_TRAILER_FALLBACK_LANGUAGE
): List<TmdbVideoResult> {
    val preferredLanguage = preferredLanguageCode.substringBefore('-').lowercase()

    fun languageRank(iso6391: String?): Int {
        val lang = iso6391?.trim()?.lowercase()
        return when {
            lang == preferredLanguage -> 0
            lang == "en" -> 1
            else -> 2
        }
    }

    return results
        .asSequence()
        .filter { (it.site ?: "").equals("YouTube", ignoreCase = true) }
        .filter { !it.key.isNullOrBlank() }
        .filter {
            val normalizedType = it.type?.trim()?.lowercase()
            normalizedType == "trailer" || normalizedType == "teaser"
        }
        .distinctBy { it.key }
        .sortedWith(
            compareBy<TmdbVideoResult> { videoTypePriority(it.type) }
                .thenBy { languageRank(it.iso6391) }
                .thenBy { if (it.official == true) 0 else 1 }
                .thenByDescending { it.size ?: 0 }
                .thenByDescending { parsePublishedAtEpoch(it.publishedAt) }
        )
        .toList()
}

private fun videoTypePriority(type: String?): Int {
    return when (type?.trim()?.lowercase()) {
        "trailer" -> 0
        "teaser" -> 1
        else -> 2
    }
}

private fun parsePublishedAtEpoch(value: String?): Long {
    if (value.isNullOrBlank()) return Long.MIN_VALUE
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
}
