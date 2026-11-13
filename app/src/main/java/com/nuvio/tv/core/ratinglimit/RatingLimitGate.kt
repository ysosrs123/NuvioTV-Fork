package com.nuvio.tv.core.ratinglimit

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RatingLimit"
private const val KNOWN_KEEP_MS = 30L * 24 * 60 * 60 * 1000
private const val UNKNOWN_KEEP_MS = 12L * 60 * 60 * 1000
private const val KEEP_TITLES = 3000
private const val LOOKUPS_AT_ONCE = 3

data class TitleRating(val rank: Int?, val certification: String?)

sealed interface RatingCheck {
    data object Allowed : RatingCheck
    data class NeedsPin(val limit: RatingLimit, val certification: String?) : RatingCheck
}

/** Pure part of the gate, so it can be tested without a box. */
fun ratingCheck(limit: RatingLimit?, rating: TitleRating, unlocked: Boolean): RatingCheck = when {
    limit == null || unlocked || limit.allows(rating.rank) -> RatingCheck.Allowed
    else -> RatingCheck.NeedsPin(limit, rating.certification?.takeIf { rating.rank != null })
}

/** Keeps each profile's rating limit on this box and answers whether a title may play without the main profile's PIN. */
@Singleton
class RatingLimitGate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val profileManager: ProfileManager
) {
    private val prefs by lazy { context.getSharedPreferences("arc_rating_limit", Context.MODE_PRIVATE) }
    private val _limits = MutableStateFlow<Map<Int, RatingLimit>>(emptyMap())
    val limits: StateFlow<Map<Int, RatingLimit>> = _limits.asStateFlow()
    @Volatile private var loaded = false

    private val cacheLock = Mutex()
    private var cache: MutableMap<String, JSONObject>? = null
    private val writeLock = Mutex()
    @Volatile private var cacheDirty = false
    private val unlocked = HashSet<String>()
    private var unlockedFor: Int? = null
    private var unlockedAt = -1L

    private fun cacheFile() = File(context.filesDir, "rating_limit/ratings.json")

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _limits.value = prefs.all.mapNotNull { (key, value) ->
                val id = key.removePrefix("p").toIntOrNull() ?: return@mapNotNull null
                RatingLimit.fromCode(value as? String)?.let { id to it }
            }.toMap()
            loaded = true
        }
    }

    fun limitFor(profileId: Int): RatingLimit? {
        ensureLoaded()
        return _limits.value[profileId]
    }

    fun activeLimit(): RatingLimit? = limitFor(profileManager.activeProfileId.value)

    fun setLimit(profileId: Int, limit: RatingLimit?) {
        ensureLoaded()
        prefs.edit().apply { if (limit == null) remove("p$profileId") else putString("p$profileId", limit.code) }.apply()
        _limits.value = if (limit == null) _limits.value - profileId else _limits.value + (profileId to limit)
    }

    /** A removed profile's slot is reused by the next new profile, which must not inherit the limit. */
    fun forgetProfile(profileId: Int) = setLimit(profileId, null)

    fun forgetAll() {
        ensureLoaded()
        prefs.edit().apply { prefs.all.keys.forEach { remove(it) } }.apply()
        _limits.value = emptyMap()
        synchronized(unlocked) { unlocked.clear() }
    }

    /** Unlocked titles last until the next profile pick, even when the same profile is picked again. */
    private fun unlockedTitles(profileId: Int): HashSet<String> {
        val pick = profileManager.profileSelectionRevision.value
        if (unlockedFor != profileId || unlockedAt != pick) {
            unlocked.clear()
            unlockedFor = profileId
            unlockedAt = pick
        }
        return unlocked
    }

    suspend fun check(profileId: Int, contentId: String, contentType: String): RatingCheck {
        val limit = limitFor(profileId) ?: return RatingCheck.Allowed
        val key = ratingTitleKey(contentId, contentType)
        val open = synchronized(unlocked) { key in unlockedTitles(profileId) }
        if (open) return RatingCheck.Allowed
        return ratingCheck(limit, rating(contentId, contentType), false)
    }

    /** Until Arc closes or a profile is picked again. */
    fun unlock(profileId: Int, contentId: String, contentType: String) {
        synchronized(unlocked) { unlockedTitles(profileId) += ratingTitleKey(contentId, contentType) }
    }

    /** The items whose rating fits the limit; ratings are looked up a few at a time and kept. */
    suspend fun <T> keepFitting(limit: RatingLimit, items: List<T>, idOf: (T) -> Pair<String, String>): List<T> {
        val gate = kotlinx.coroutines.sync.Semaphore(LOOKUPS_AT_ONCE)
        val fits = kotlinx.coroutines.coroutineScope {
            items.map { item ->
                async {
                    val (id, type) = idOf(item)
                    gate.withPermit { limit.allows(rating(id, type, save = false).rank) }
                }
            }.awaitAll()
        }
        saveCache()
        return items.filterIndexed { index, _ -> fits[index] }
    }

    /** Whether a title is known to fit, without asking TMDB; for lists that must not wait. */
    suspend fun knownToFit(limit: RatingLimit, contentId: String, contentType: String): Boolean? {
        val entry = cached(ratingTitleKey(contentId, contentType)) ?: return null
        return limit.allows(entry.optInt("rank", -1).takeIf { it >= 0 })
    }

    suspend fun rating(contentId: String, contentType: String, save: Boolean = true): TitleRating {
        val key = ratingTitleKey(contentId, contentType)
        cached(key)?.let { return TitleRating(it.optInt("rank", -1).takeIf { r -> r >= 0 }, it.optString("cert").ifBlank { null }) }
        val tv = key.startsWith("tv|")
        val found = try {
            lookUp(key.substringAfter('|'), tv)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "No rating: ${error.javaClass.simpleName}")
            return TitleRating(null, null)
        }
        remember(key, found)
        if (save) saveCache()
        return found
    }

    private suspend fun lookUp(id: String, tv: Boolean): TitleRating {
        val tmdb = tmdbService.ensureTmdbId(id, if (tv) "tv" else "movie")?.toIntOrNull() ?: return TitleRating(null, null)
        val key = BuildConfig.TMDB_API_KEY
        val certification = if (tv) {
            val response = tmdbApi.getTvContentRatings(tmdb, key)
            if (!response.isSuccessful) throw java.io.IOException("TMDB ${response.code()}")
            response.body()?.results.orEmpty().firstOrNull { it.iso31661 == "US" }?.rating?.takeIf { ratingRank(it) != null }
        } else {
            val response = tmdbApi.getMovieReleaseDates(tmdb, key)
            if (!response.isSuccessful) throw java.io.IOException("TMDB ${response.code()}")
            usFilmCertification(response.body()?.results.orEmpty()
                .map { country -> country.iso31661 to country.releaseDates.orEmpty().map { it.certification } })
        }
        return TitleRating(ratingRank(certification), certification)
    }

    private suspend fun cached(key: String): JSONObject? = cacheLock.withLock {
        val entries = loadCache()
        val entry = entries[key] ?: return@withLock null
        val age = System.currentTimeMillis() - entry.optLong("at")
        val keep = if (entry.optInt("rank", -1) >= 0) KNOWN_KEEP_MS else UNKNOWN_KEEP_MS
        entry.takeIf { age in 0 until keep }
    }

    private suspend fun remember(key: String, rating: TitleRating) = cacheLock.withLock {
        loadCache()[key] = JSONObject().put("rank", rating.rank ?: -1).put("cert", rating.certification ?: "").put("at", System.currentTimeMillis())
        cacheDirty = true
    }

    /** Writes the ratings file once per batch, outside the lock the play check waits on. */
    private suspend fun saveCache() = writeLock.withLock {
        val kept = cacheLock.withLock {
            if (!cacheDirty) return@withLock null
            cacheDirty = false
            val all = loadCache()
            if (all.size > KEEP_TITLES) {
                all.entries.sortedBy { it.value.optLong("at") }.take(all.size - KEEP_TITLES).forEach { all.remove(it.key) }
            }
            JSONObject().apply { all.forEach { (key, value) -> put(key, value) } }.toString()
        } ?: return@withLock
        withContext(Dispatchers.IO) {
            runCatching { cacheFile().apply { parentFile?.mkdirs() }.writeText(kept) }
        }
    }

    private suspend fun loadCache(): MutableMap<String, JSONObject> {
        cache?.let { return it }
        val read = withContext(Dispatchers.IO) {
            runCatching {
                val json = JSONObject(cacheFile().readText())
                json.keys().asSequence().associateWith { json.getJSONObject(it) }.toMutableMap()
            }.getOrDefault(HashMap())
        }
        cache = read
        return read
    }
}
