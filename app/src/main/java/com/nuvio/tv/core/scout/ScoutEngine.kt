package com.nuvio.tv.core.scout

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.ArcDispatchers
import com.nuvio.tv.data.local.ScoutStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbRecommendationResult
import com.nuvio.tv.domain.model.SCOUT_MIN_SEEDS
import com.nuvio.tv.domain.model.SCOUT_POOL_VERSION
import com.nuvio.tv.domain.model.ScoutBecause
import com.nuvio.tv.domain.model.ScoutExclusions
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.ScoutPool
import com.nuvio.tv.domain.model.ScoutProfile
import com.nuvio.tv.domain.model.ScoutSeed
import com.nuvio.tv.domain.model.scoutExclusionName
import com.nuvio.tv.domain.model.scoutHistorySignature
import com.nuvio.tv.domain.model.scoutNormalizedName
import com.nuvio.tv.domain.model.scoutRank
import com.nuvio.tv.domain.model.scoutSeeds
import com.nuvio.tv.domain.model.scoutShouldRefresh
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ScoutEngine"
private const val MAX_CONCURRENT_REQUESTS = 2
private const val BECAUSE_ROWS = 2
private const val BECAUSE_ITEMS = 30
private const val GEM_ITEMS = 60
private const val TOP_UP_BELOW = 40
private const val TOP_UP_WEIGHT = 0.02
private const val GEM_MIN_VOTES = 150
private const val GEM_MIN_AVERAGE = 7.2
private const val POPULAR_MIN_VOTES = 500
private const val KIDS_TV_MIN_VOTES = 50
private const val CERTIFICATION_COUNTRY = "US"
private const val TV_LEFT_OUT = "10763,10764,10766,10767"
private const val KIDS_MOVIE_LEFT_OUT = "27,53,80,10752"
private const val KIDS_TV_LEFT_OUT = "$TV_LEFT_OUT,80,10768"
private const val MEMBER_POOL_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
private const val CHECK_EVERY_MS = 60L * 60 * 1000
private const val RATING_SETTLE_MS = 3_000L

/** Builds the picks on this box, about once a day, from what the profile watched and rated. */
@Singleton
class ScoutEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val tmdbSettings: TmdbSettingsDataStore,
    private val watchProgress: dagger.Lazy<WatchProgressRepository>,
    private val watchedItems: WatchedItemsPreferences,
    private val progressOf: dagger.Lazy<com.nuvio.tv.data.local.WatchProgressPreferences>,
    private val store: ScoutStore,
    private val profileManager: ProfileManager,
    private val folderSupport: ScoutFolderSupport,
    private val picksBuilder: ScoutPicksBuilder,
    private val moodCheck: dagger.Lazy<ScoutMoodCheck>
) {
    private val adapter = Moshi.Builder().build().adapter(ScoutPool::class.java)
    private val refreshLock = Mutex()
    private val _pool = MutableStateFlow<ScoutPool?>(null)
    val pool: StateFlow<ScoutPool?> = _pool.asStateFlow()

    fun activeProfile(): Int = profileManager.activeProfileId.value

    @Volatile private var loadedProfile: Int? = null

    private fun file(profileId: Int) = File(context.filesDir, "scout/pool_$profileId.json")

    private fun showSaved(profileId: Int) {
        if (loadedProfile == profileId) return
        _pool.value = runCatching { adapter.fromJson(file(profileId).readText()) }.getOrNull()
        loadedProfile = profileId
    }

    /** Shows the active profile's picks on every switch and keeps them no older than a day while the app stays open. */
    suspend fun followProfiles(checkEveryMs: Long = CHECK_EVERY_MS) {
        profileManager.activeProfileId.collectLatest { profileId ->
            refreshLock.withLock { withContext(Dispatchers.IO) { showSaved(profileId) } }
            coroutineScope {
                launch {
                    store.profile.map { profile -> profile.ratings.values.sumOf { it.ratedAt } }.distinctUntilChanged().drop(1).collectLatest {
                        delay(RATING_SETTLE_MS)
                        refreshIfDue()
                    }
                }
                while (true) {
                    refreshIfDue()
                    moodCheck.get().runIfDue()
                    delay(checkEveryMs)
                }
            }
        }
    }

    /** Drops what is held for a removed profile, or for every profile when [profileId] is null. */
    fun forget(profileId: Int?) {
        if (profileId == null || loadedProfile == profileId) {
            loadedProfile = null
            _pool.value = null
        }
    }

    suspend fun refreshIfDue(now: Long = System.currentTimeMillis()) = refreshLock.withLock {
        withContext(ArcDispatchers.background) {
            val profileId = profileManager.activeProfileId.value
            showSaved(profileId)
            val profile = store.current()
            val watched = watchProgress.get().tasteHistory.first()
            val started = watchProgress.get().allProgress.first()
            val signals = scoutSignals(watched, started, profile.ratings.values, now)
            val watching = scoutWatchingSeeds(started, signals)
            val seeds = scoutSignalSeeds(signals, watching)
            val signature = scoutHistorySignature(seeds, profile.settings.leaveOutGenres)
            if (!scoutShouldRefresh(_pool.value, signature, now)) return@withContext
            try {
                val built = build(seeds, profile, signature, now, signals = signals + watching.filter { it in seeds }, ageTitles = signals + watching, profileId = profileId)
                if (built.picked.isEmpty() || profileManager.activeProfileId.value != profileId) return@withContext
                _pool.value = built
                file(profileId).apply { parentFile?.mkdirs() }.writeText(adapter.toJson(built))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Picks not rebuilt: ${error.javaClass.simpleName}")
            }
        }
    }

    /** Another profile on this box: its saved picks, built once if it has none, and what it has seen. */
    suspend fun memberFor(profileId: Int, now: Long = System.currentTimeMillis()): ScoutGroupMember = withContext(ArcDispatchers.background) {
        val profile = store.profileOf(profileId)
        val watched = watchedItems.getAllItems(profileId)
        val started = runCatching { progressOf.get().observeAllProgress(profileId).first() }.getOrDefault(emptyList())
        val exclusions = exclusionsOf(profile, watched, started)
        val saved = runCatching { adapter.fromJson(file(profileId).readText()) }.getOrNull()
        if (saved != null && saved.version >= SCOUT_POOL_VERSION && now - saved.builtAt < MEMBER_POOL_MAX_AGE_MS) {
            return@withContext ScoutGroupMember(saved, exclusions)
        }
        val finished = scoutSeeds(watched, profile.ratings.values)
        val watching = scoutWatchingSeeds(started, finished)
        val seeds = scoutSignalSeeds(finished, watching)
        if (seeds.isEmpty()) return@withContext ScoutGroupMember(saved, exclusions)
        val built = try {
            build(seeds, profile, scoutHistorySignature(seeds, profile.settings.leaveOutGenres), now, exclusions, ageTitles = finished + watching, profileId = profileId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Member picks not built: ${error.javaClass.simpleName}")
            null
        }
        if (built != null && built.picked.isNotEmpty()) {
            runCatching { file(profileId).apply { parentFile?.mkdirs() }.writeText(adapter.toJson(built)) }
            ScoutGroupMember(built, exclusions)
        } else {
            ScoutGroupMember(saved, exclusions)
        }
    }

    private suspend fun build(
        seeds: List<ScoutSeed>,
        profile: ScoutProfile,
        signature: String,
        now: Long,
        known: ScoutExclusions? = null,
        signals: List<ScoutSeed> = seeds,
        ageTitles: List<ScoutSeed> = signals,
        profileId: Int = profileManager.activeProfileId.value
    ): ScoutPool {
        val language = tmdbSettings.settings.first().language
        val exclusions = known ?: exclusions(profile)
        val gate = Semaphore(MAX_CONCURRENT_REQUESTS)
        val ceiling = try {
            picksBuilder.ageCeiling(ageTitles, now, profileId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "No age read: ${error.javaClass.simpleName}")
            null
        }
        val resolved = coroutineScope {
            seeds.map { seed ->
                async { gate.withPermit { seed to recommendations(seed, language) } }
            }.awaitAll()
        }
        val blockedSeeds = resolved.filter { (seed, _) -> seed.finishedOrder != null || seed.weight < 0 }.mapNotNull { (seed, result) ->
            result?.first?.let { (if (seed.tv) "tv:" else "movie:") + it }
        }
        val full = exclusions.copy(tmdbKeys = exclusions.tmdbKeys + blockedSeeds)
        val results = resolved.map { (seed, result) -> seed to result?.second.orEmpty() }.toMutableList()

        val rated = HashSet<String>()
        var levels = if (ceiling == null) emptyMap() else picksBuilder.knownAgeLevels(now)
        fun fits(pick: ScoutPick) = pick.key in rated || scoutWithinCeiling(pick, levels[pick.key], ceiling)

        val positive = seeds.count { it.weight > 0 }
        var picked = scoutRank(results, full)
        if (positive < SCOUT_MIN_SEEDS || picked.count(::fits) < TOP_UP_BELOW) {
            val popular = if (ceiling == null) {
                discover(false, language, sortBy = "popularity.desc", votes = POPULAR_MIN_VOTES) +
                    discover(true, language, sortBy = "popularity.desc", votes = POPULAR_MIN_VOTES)
            } else {
                val films = discover(false, language, sortBy = "popularity.desc", votes = POPULAR_MIN_VOTES,
                    certification = scoutCertificationAtMost(ceiling), without = KIDS_MOVIE_LEFT_OUT)
                rated += films.map { it.key }
                films + discover(true, language, sortBy = "popularity.desc", votes = KIDS_TV_MIN_VOTES,
                    genres = scoutKidsTvGenres(ceiling), without = KIDS_TV_LEFT_OUT)
            }
            results += ScoutSeed("", false, "", TOP_UP_WEIGHT, null) to popular
            picked = scoutRank(results, full).map { if (it.seedName.isNullOrBlank()) it.copy(seedName = null) else it }
        }

        val tasteGenres = picked.filter(::fits).take(50).flatMap { it.genres }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(3).joinToString("|") { it.key.toString() }
        val gemFilms = discover(false, language, "vote_average.desc", GEM_MIN_VOTES, SCOUT_GEM_MAX_VOTES, GEM_MIN_AVERAGE, tasteGenres,
            certification = ceiling?.let(::scoutCertificationAtMost), without = if (ceiling == null) null else KIDS_MOVIE_LEFT_OUT)
        if (ceiling != null) rated += gemFilms.map { it.key }
        val gemSeries = if (ceiling == null) {
            discover(true, language, "vote_average.desc", GEM_MIN_VOTES / 2, SCOUT_GEM_MAX_VOTES_TV, GEM_MIN_AVERAGE, tasteGenres)
        } else {
            discover(true, language, "vote_average.desc", GEM_MIN_VOTES / 2, SCOUT_GEM_MAX_VOTES_TV, GEM_MIN_AVERAGE, scoutKidsTvGenres(ceiling), without = KIDS_TV_LEFT_OUT)
        }

        val improved = if (known != null) null else try {
            picksBuilder.improve(signals, profile.ratings.values, results, full, now, language, ceiling)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Picks kept simple: ${error.javaClass.simpleName}")
            null
        }
        if (ceiling != null) levels = picksBuilder.knownAgeLevels(now)

        val because = seeds.filter { it.weight > 0 && it.finishedOrder != null }
            .sortedBy { it.finishedOrder }
            .take(BECAUSE_ROWS)
            .mapNotNull { seed ->
                val own = results.firstOrNull { it.first.contentId == seed.contentId }?.second.orEmpty()
                val picks = own.filterNot(full::blocks).filter(::fits).take(BECAUSE_ITEMS)
                if (picks.isEmpty()) null else ScoutBecause(seed.name, picks)
            }
        val gems = (gemFilms + gemSeries).filterNot(full::blocks).filter(::fits).take(GEM_ITEMS)
        val rows = improved?.rows.orEmpty().map { row -> row.copy(picks = row.picks.filter(::fits)) }
            .filter { it.picks.size >= SCOUT_TASTE_ROW_MIN_PICKS }

        return ScoutPool(
            builtAt = now,
            historySignature = signature,
            picked = (improved?.picked?.takeIf { it.isNotEmpty() } ?: picked).filter(::fits),
            because = because,
            gems = gems,
            tasteRows = rows,
            newForYou = improved?.newForYou.orEmpty().filter(::fits),
            trendingForYou = improved?.trendingForYou.orEmpty().filter(::fits),
            version = SCOUT_POOL_VERSION
        )
    }

    /** Forgets the picks; the next refresh builds them again from what is left. */
    suspend fun reset() = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            runCatching { file(profileManager.activeProfileId.value).delete() }
            _pool.value = null
        }
    }

    suspend fun exclusions(profile: ScoutProfile): ScoutExclusions = withContext(Dispatchers.Default) {
        exclusionsOf(profile, watchProgress.get().tasteHistory.first(), watchProgress.get().allProgress.first())
    }

    private suspend fun exclusionsOf(
        profile: ScoutProfile,
        watched: List<com.nuvio.tv.domain.model.WatchedItem>,
        started: List<com.nuvio.tv.domain.model.WatchProgress>
    ): ScoutExclusions {
        val listed = folderSupport.listed()
        val names = watched.map { scoutExclusionName(it.title, it.releaseInfo) } +
            started.map { scoutNormalizedName(it.name) } +
            profile.ratings.values.filter { it.rating == com.nuvio.tv.domain.model.ScoutRating.NOT_FOR_ME }.map { scoutNormalizedName(it.title.name) } +
            listed.names
        val listedKeys = listed.ids.filter { it.startsWith("tmdb:") }.flatMap { id ->
            val tmdb = id.removePrefix("tmdb:")
            listOf("movie:$tmdb", "tv:$tmdb")
        }
        val ids = (watched.map { it.contentId to it.contentType } + started.map { it.contentId to it.contentType } +
            profile.ratings.values.filter { it.rating == com.nuvio.tv.domain.model.ScoutRating.NOT_FOR_ME }.map { it.title.id to it.title.type })
            .mapNotNull { (id, type) ->
                val tmdb = id.removePrefix("tmdb:").takeIf { id.startsWith("tmdb:") } ?: return@mapNotNull null
                val tv = type.trim().lowercase() != "movie"
                (if (tv) "tv:" else "movie:") + tmdb
            }
        return ScoutExclusions(names = names.toSet(), tmdbKeys = ids.toSet() + listedKeys, genres = profile.settings.leaveOutGenres)
    }

    private suspend fun recommendations(seed: ScoutSeed, language: String): Pair<Int, List<ScoutPick>>? {
        val media = if (seed.tv) "tv" else "movie"
        val id = runCatching { tmdbService.ensureTmdbId(seed.contentId, media)?.toIntOrNull() }.getOrNull() ?: return null
        val response = runCatching {
            if (seed.tv) tmdbApi.getTvRecommendations(id, BuildConfig.TMDB_API_KEY, language)
            else tmdbApi.getMovieRecommendations(id, BuildConfig.TMDB_API_KEY, language)
        }.getOrNull()?.body() ?: return id to emptyList()
        return id to response.results.orEmpty().mapNotNull { it.toPick(seed.tv) }
    }

    private suspend fun discover(
        tv: Boolean,
        language: String,
        sortBy: String,
        votes: Int,
        votesMax: Int? = null,
        average: Double? = null,
        genres: String? = null,
        certification: String? = null,
        without: String? = null
    ): List<ScoutPick> {
        val body = runCatching {
            if (tv) tmdbApi.discoverTv(
                apiKey = BuildConfig.TMDB_API_KEY, language = language, sortBy = sortBy, voteCountGte = votes,
                voteCountLte = votesMax, voteAverageGte = average, withGenres = genres?.ifBlank { null },
                withoutGenres = without ?: TV_LEFT_OUT
            ) else tmdbApi.discoverMovies(
                apiKey = BuildConfig.TMDB_API_KEY, language = language, sortBy = sortBy, voteCountGte = votes,
                voteCountLte = votesMax, voteAverageGte = average, withGenres = genres?.ifBlank { null },
                withoutGenres = without, certificationCountry = certification?.let { CERTIFICATION_COUNTRY },
                certificationLte = certification
            )
        }.getOrNull()?.body()
        return body?.results.orEmpty().mapNotNull { it.toPick(tv) }
    }
}

/** The highest US film rating at or under an age ceiling. */
internal fun scoutCertificationAtMost(ceiling: Int): String = if (ceiling <= 0) "G" else "PG"

/** TV discover has no rating filter, so a ceiling picks kids shows, and family shows from PG up. */
internal fun scoutKidsTvGenres(ceiling: Int): String = if (ceiling <= 0) "10762" else "10762|10751"

private fun image(path: String?, size: String) = path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

internal fun TmdbRecommendationResult.toPick(seedTv: Boolean): ScoutPick? {
    val tv = when (mediaType?.trim()?.lowercase()) {
        "tv" -> true
        "movie" -> false
        else -> seedTv
    }
    return ScoutPick(
        tmdbId = id,
        tv = tv,
        name = title?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() } ?: return null,
        poster = image(posterPath, "w342") ?: return null,
        backdrop = image(backdropPath, "w1280"),
        overview = overview?.takeIf { it.isNotBlank() },
        released = (if (tv) firstAirDate else releaseDate)?.takeIf { it.isNotBlank() },
        rating = voteAverage?.toFloat(),
        votes = voteCount,
        genres = genreIds.orEmpty()
    )
}

internal fun TmdbDiscoverResult.toPick(tv: Boolean): ScoutPick? = ScoutPick(
    tmdbId = id,
    tv = tv,
    name = title?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() } ?: return null,
    poster = image(posterPath, "w342") ?: return null,
    backdrop = image(backdropPath, "w1280"),
    overview = overview?.takeIf { it.isNotBlank() },
    released = (if (tv) firstAirDate else releaseDate)?.takeIf { it.isNotBlank() },
    rating = voteAverage?.toFloat(),
    votes = voteCount,
    genres = genreIds.orEmpty()
)
