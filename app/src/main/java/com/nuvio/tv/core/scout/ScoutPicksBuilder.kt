package com.nuvio.tv.core.scout

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.ScoutExclusions
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutRatingEntry
import com.nuvio.tv.domain.model.ScoutSeed
import com.nuvio.tv.domain.model.ScoutTasteRow
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ScoutPicks"
private const val MAX_REQUESTS = 2
private const val TASTE_TITLES = 80
private const val TASTE_NEGATIVE = 10
private const val MIN_TASTE_TITLES = 3
private const val AGE_TITLES = 40
private const val KEYWORD_SOURCES = 5
private const val PEOPLE_SOURCES = 4
private const val SOURCE_WEIGHT = 2.0
private const val KEYWORD_MIN_VOTES = 100
private const val PERSON_MIN_VOTES = 50
private const val PERSON_ITEMS = 20
private const val LOOKUPS = 100
private const val POOL = 200
private const val ROW_ITEMS = 30
private const val ROW_KEYWORD_CHOICES = 3
private const val FACTS_KEEP = 1500
private const val SHOWN_KEEP = 2000
private const val RELATED_ITEMS = 10
private const val FRESH_PAGES = 3
private const val FRESH_VOTES = 20
private const val FRESH_LOOKUPS = 150
private const val FRESH_SKIP_TOP = 20
private const val FRESH_WINDOW_MS = 270L * 24 * 60 * 60 * 1000
private const val RELATED_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class ScoutRelatedIds(val ids: List<Int>, val fetchedAt: Long)

data class ScoutImproved(
    val picked: List<ScoutPick>,
    val rows: List<ScoutTasteRow>,
    val newForYou: List<ScoutPick> = emptyList(),
    val trendingForYou: List<ScoutPick> = emptyList()
)

/** Builds on the seeds' recommendations: taste, wider sources, re-rank, limits and reasons. Null means too little to go on. */
@Singleton
class ScoutPicksBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdbApi: TmdbApi,
    private val taste: dagger.Lazy<ScoutTasteRepository>,
    private val ai: dagger.Lazy<ScoutPicksAi>,
    private val profileManager: ProfileManager,
    private val tmdbService: com.nuvio.tv.core.tmdb.TmdbService,
    private val traktApi: dagger.Lazy<com.nuvio.tv.data.remote.api.TraktApi>,
    private val traktAuth: dagger.Lazy<com.nuvio.tv.data.repository.TraktAuthService>,
    private val traktStore: dagger.Lazy<com.nuvio.tv.data.local.TraktAuthDataStore>
) {
    private val factsAdapter = Moshi.Builder().build().adapter<List<ScoutTasteTitle>>(
        Types.newParameterizedType(List::class.java, ScoutTasteTitle::class.java)
    )
    private val relatedAdapter = Moshi.Builder().build().adapter<Map<String, ScoutRelatedIds>>(
        Types.newParameterizedType(Map::class.java, String::class.java, ScoutRelatedIds::class.java)
    )
    private val shownAdapter = Moshi.Builder().build().adapter<Map<String, ScoutShown>>(
        Types.newParameterizedType(Map::class.java, String::class.java, ScoutShown::class.java)
    )

    private fun factsFile() = File(context.filesDir, "scout/facts.json")
    private fun shownFile(profileId: Int) = File(context.filesDir, "scout/shown_$profileId.json")

    /** Called with the titles a row is showing; counts once a day per title. */
    @Synchronized
    fun noteShown(keys: List<String>, now: Long = System.currentTimeMillis()) {
        val file = shownFile(profileManager.activeProfileId.value)
        val old = readShown(file)
        val updated = old.toMutableMap()
        keys.forEach { updated[it] = scoutShownToday(old[it], now) }
        if (updated == old) return
        val kept = updated.entries.sortedByDescending { it.value.last }.take(SHOWN_KEEP).associate { it.key to it.value }
        runCatching { file.apply { parentFile?.mkdirs() }.writeText(shownAdapter.toJson(kept)) }
    }

    private fun readShown(file: File): Map<String, ScoutShown> =
        runCatching { shownAdapter.fromJson(file.readText()).orEmpty() }.getOrDefault(emptyMap())

    private fun resting(now: Long): Set<String> =
        scoutResting(readShown(shownFile(profileManager.activeProfileId.value)), ScoutOpenedLog.read(context), now)

    /** The age ceiling read from the profile's own titles; their facts are kept, so the rebuild needs no second look-up. */
    suspend fun ageCeiling(titles: List<ScoutSeed>, now: Long, profileId: Int = profileManager.activeProfileId.value): Int? {
        val positive = titles.filter { it.weight > 0 }.sortedByDescending { it.weight }.distinctBy { it.contentId }.take(AGE_TITLES)
        if (positive.isEmpty()) return null
        val facts = taste.get().fill(positive.map { it.contentId to it.tv }, now, profileId)
        return scoutAgeCeiling(positive.mapNotNull { seed -> facts[seed.contentId]?.ageLevel?.let { it to seed.weight } })
    }

    /** Age levels of titles already looked up on this box, by pick key; null where TMDB has no US rating. */
    fun knownAgeLevels(now: Long): Map<String, Int?> = readFacts(now).mapValues { it.value.ageLevel }

    suspend fun improve(
        signals: List<ScoutSeed>,
        ratings: Collection<ScoutRatingEntry>,
        results: List<Pair<ScoutSeed, List<ScoutPick>>>,
        exclusions: ScoutExclusions,
        now: Long,
        language: String? = null,
        ageCeiling: Int? = null
    ): ScoutImproved? {
        val positive = signals.filter { it.weight > 0 }.sortedByDescending { it.weight }.take(TASTE_TITLES)
        val negative = signals.filter { it.weight < 0 }.take(TASTE_NEGATIVE)
        val titles = taste.get().fill((positive + negative).map { it.contentId to it.tv }, now)
        val items = (positive + negative).mapNotNull { seed -> titles[seed.contentId]?.let { it to seed.weight } }
        if (items.count { it.second > 0 } < MIN_TASTE_TITLES) return null
        val loved = ratings.filter { it.rating == ScoutRating.LOVE }.map { it.title.id }.toSet()
        val vector = scoutTasteVector(items, loved)

        val own = HashMap<String, Int>()
        items.forEach { (title, _) -> title.usefulKeywords().forEach { own.merge(it.name, 1, Int::plus) } }
        val gate = Semaphore(MAX_REQUESTS)
        val extra = coroutineScope {
            val words = vector.topKeywords(own, KEYWORD_SOURCES).mapNotNull { vector.keywordIds[it] }.map { id ->
                async { gate.withPermit { keywordPicks(id) } }
            }
            val people = vector.topPeople(PEOPLE_SOURCES).map { id -> async { gate.withPermit { personPicks(id) } } }
            (words + people).awaitAll()
        }.filter { it.isNotEmpty() }.map { ScoutSeed("", false, "", SOURCE_WEIGHT, null) to it }

        val known = readFacts(now)
        val related = relatedResults(results.map { it.first }.filter { it.weight > 0 && it.name.isNotBlank() }, known, now, language)
        val found = scoutTally(results + extra + related, exclusions.copy(tmdbKeys = exclusions.tmdbKeys + resting(now)))
        val candidates = lookUpCandidates(found, known, now)
        val tally = found.filterValues { (pick, _) -> scoutWithinCeiling(pick, known[pick.key]?.ageLevel, ageCeiling) }
        val all = candidates + items.associate { it.first.contentId to it.first }
        val frequency = HashMap<String, Int>()
        all.values.forEach { title -> title.usefulKeywords().forEach { frequency.merge(it.name, 1, Int::plus) } }

        val ranked = scoutReRank(tally, all, vector, LOOKUPS)
        val limited = scoutLimitDuplicates(ranked, all)
        val ordered = scoutAddStretch(limited, all, vector).take(POOL).map { scoutWithReason(it, all[it.key], vector, frequency) }
        val picked = ai.get().sort(ordered, now)

        val groups = scoutTasteGroups(items)
        val rows = if (groups.size < 2) emptyList() else groups.map { group ->
            val sub = scoutTasteVector(group.titles + items.filter { it.second < 0 }, loved)
            val list = scoutInRowGenre(scoutLimitDuplicates(scoutReRank(tally, all, sub, LOOKUPS), all), group.genre).take(ROW_ITEMS)
                .map { scoutWithReason(it, all[it.key], sub, frequency) }
            ScoutTasteRow("", group.genre, group.language, scoutRowKeyword(sub.topKeywords(own, ROW_KEYWORD_CHOICES), list, all), list)
        }.filter { ageCeiling != null || it.picks.size >= SCOUT_TASTE_ROW_MIN_PICKS }.let(::scoutWithRowIds)

        val newTitles = freshTitles(language, now)
        val trending = trendingTitles(language)
        val allFresh = lookUpFresh(newTitles + trending, known, now, language)
        val freshFacts = all + allFresh
        fun suitable(list: List<ScoutPick>) = list.filter { scoutWithinCeiling(it, freshFacts[it.key]?.ageLevel, ageCeiling) }
        val freshFrequency = HashMap<String, Int>()
        freshFacts.values.forEach { title -> title.usefulKeywords().forEach { freshFrequency.merge(it.name, 1, Int::plus) } }
        val shownFirst = picked.take(FRESH_SKIP_TOP).map { it.key }.toSet()
        val blocked = exclusions.copy(tmdbKeys = exclusions.tmdbKeys + resting(now))
        val newRow = scoutFreshRow(suitable(newTitles), freshFacts, vector, freshFrequency, blocked, shownFirst)
        val trendingRow = scoutFreshRow(suitable(trending), freshFacts, vector, freshFrequency, blocked, shownFirst + newRow.take(FRESH_SKIP_TOP).map { it.key })
        saveFacts(known)
        return ScoutImproved(picked, rows, newRow, trendingRow)
    }

    private suspend fun keywordPicks(keyword: Int): List<ScoutPick> {
        val films = runCatching {
            tmdbApi.discoverMovies(
                apiKey = BuildConfig.TMDB_API_KEY, sortBy = "vote_average.desc", voteCountGte = KEYWORD_MIN_VOTES,
                withKeywords = keyword.toString()
            )
        }.getOrNull()?.body()?.results.orEmpty().mapNotNull { it.toPick(false) }
        val series = runCatching {
            tmdbApi.discoverTv(
                apiKey = BuildConfig.TMDB_API_KEY, sortBy = "vote_average.desc", voteCountGte = KEYWORD_MIN_VOTES,
                withKeywords = keyword.toString()
            )
        }.getOrNull()?.body()?.results.orEmpty().mapNotNull { it.toPick(true) }
        return (films + series).sortedByDescending { it.rating ?: 0f }
    }

    private suspend fun personPicks(person: Int): List<ScoutPick> {
        val crew = runCatching { tmdbApi.getPersonCombinedCredits(person, BuildConfig.TMDB_API_KEY) }
            .getOrNull()?.body()?.crew.orEmpty()
        return crew.filter { it.job in setOf("Director", "Writer", "Screenplay", "Creator") && (it.voteCount ?: 0) >= PERSON_MIN_VOTES }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .distinctBy { it.mediaType to it.id }
            .take(PERSON_ITEMS)
            .mapNotNull { credit ->
                val tv = credit.mediaType == "tv"
                ScoutPick(
                    tmdbId = credit.id,
                    tv = tv,
                    name = credit.title?.takeIf { it.isNotBlank() } ?: credit.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    poster = credit.posterPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w342$it" } ?: return@mapNotNull null,
                    backdrop = credit.backdropPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w1280$it" },
                    overview = credit.overview?.takeIf { it.isNotBlank() },
                    released = (if (tv) credit.firstAirDate else credit.releaseDate)?.takeIf { it.isNotBlank() },
                    rating = credit.voteAverage?.toFloat(),
                    votes = credit.voteCount,
                    genres = credit.genreIds.orEmpty(),
                    language = credit.originalLanguage
                )
            }
    }

    /** Films must be out to rent or stream; cinema-only titles rarely have sources yet. */
    private suspend fun freshTitles(language: String?, now: Long): List<ScoutPick> {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val to = day.format(java.util.Date(now))
        val from = day.format(java.util.Date(now - FRESH_WINDOW_MS))
        val key = BuildConfig.TMDB_API_KEY
        val gate = Semaphore(MAX_REQUESTS)
        return coroutineScope {
            (1..FRESH_PAGES).flatMap { page ->
                listOf(
                    async { gate.withPermit { runCatching { tmdbApi.discoverPlayableMovies(key, language, page, from, to, FRESH_VOTES) }.getOrNull()?.body()?.results.orEmpty().mapNotNull { it.toPick(false) } } },
                    async { gate.withPermit { runCatching { tmdbApi.discoverAiringTv(key, language, page, from, to, FRESH_VOTES) }.getOrNull()?.body()?.results.orEmpty().mapNotNull { it.toPick(true) } } }
                )
            }.awaitAll().flatten()
        }
    }

    private suspend fun trendingTitles(language: String?): List<ScoutPick> {
        val gate = Semaphore(MAX_REQUESTS)
        return coroutineScope {
            (1..FRESH_PAGES).map { page ->
                async {
                    gate.withPermit {
                        runCatching { tmdbApi.getTrendingWeek(BuildConfig.TMDB_API_KEY, language, page) }.getOrNull()?.body()?.results.orEmpty()
                            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
                            .mapNotNull { it.toPick(false) }
                    }
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun lookUpFresh(
        titles: List<ScoutPick>,
        known: MutableMap<String, ScoutTasteTitle>,
        now: Long,
        language: String?
    ): Map<String, ScoutTasteTitle> {
        val distinct = titles.distinctBy { it.key }.take(FRESH_LOOKUPS)
        val gate = Semaphore(MAX_REQUESTS)
        coroutineScope {
            distinct.filter { it.key !in known }.map { pick ->
                async {
                    gate.withPermit {
                        try {
                            scoutLookUpFacts(tmdbApi, pick.tmdbId, pick.tv, pick.key, now, language)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.w(TAG, "No facts: ${error.javaClass.simpleName}")
                            null
                        }
                    }?.let { synchronized(known) { known[pick.key] = it } }
                }
            }.awaitAll()
        }
        return distinct.mapNotNull { pick -> known[pick.key]?.let { pick.key to it } }.toMap()
    }

    private fun saveFacts(known: Map<String, ScoutTasteTitle>) {
        val kept = known.values.sortedByDescending { it.fetchedAt }.take(FACTS_KEEP)
        runCatching { factsFile().apply { parentFile?.mkdirs() }.writeText(factsAdapter.toJson(kept)) }
    }

    private fun readFacts(now: Long): MutableMap<String, ScoutTasteTitle> =
        runCatching { factsAdapter.fromJson(factsFile().readText()).orEmpty() }.getOrDefault(emptyList())
            .filter { it.fresh(now) }.associateBy { it.contentId }.toMutableMap()

    /** Trakt's related lists, built from what people watched. Only for a profile signed in to Trakt. */
    private suspend fun relatedResults(
        seeds: List<ScoutSeed>,
        known: MutableMap<String, ScoutTasteTitle>,
        now: Long,
        language: String?
    ): List<Pair<ScoutSeed, List<ScoutPick>>> {
        if (!traktStore.get().isEffectivelyAuthenticated.first()) return emptyList()
        val file = File(context.filesDir, "scout/related.json")
        val cache = runCatching { relatedAdapter.fromJson(file.readText()).orEmpty() }.getOrDefault(emptyMap())
            .filterValues { now - it.fetchedAt in 0 until RELATED_MAX_AGE_MS }.toMutableMap()
        val gate = Semaphore(MAX_REQUESTS)
        val out = coroutineScope {
            seeds.map { seed ->
                async {
                    val media = if (seed.tv) "tv" else "movie"
                    val id = runCatching { tmdbService.ensureTmdbId(seed.contentId, media)?.toIntOrNull() }.getOrNull()
                        ?: return@async null
                    val key = "$media:$id"
                    val ids = synchronized(cache) { cache[key] }?.ids ?: gate.withPermit { fetchRelated(id, seed.tv) }?.also { found ->
                        synchronized(cache) { cache[key] = ScoutRelatedIds(found, now) }
                    } ?: return@async null
                    seed to ids.mapNotNull { other ->
                        val otherKey = "$media:$other"
                        val facts = synchronized(known) { known[otherKey] } ?: gate.withPermit {
                            runCatching { scoutLookUpFacts(tmdbApi, other, seed.tv, otherKey, now, language) }.getOrNull()
                        }?.also { synchronized(known) { known[otherKey] = it } }
                        facts?.toScoutPick(other, seed.tv)
                    }
                }
            }.awaitAll().filterNotNull()
        }
        runCatching { file.apply { parentFile?.mkdirs() }.writeText(relatedAdapter.toJson(cache)) }
        return out
    }

    private suspend fun fetchRelated(tmdbId: Int, tv: Boolean): List<Int>? {
        val type = if (tv) "show" else "movie"
        val api = traktApi.get()
        return try {
            val search = traktAuth.get().executeAuthorizedRequest { header -> api.searchById(header, "tmdb", tmdbId.toString(), type) }
            val hit = search?.takeIf { it.isSuccessful }?.body().orEmpty().firstOrNull { it.type.equals(type, ignoreCase = true) }
            val slug = (if (tv) hit?.show?.ids else hit?.movie?.ids)?.let { it.slug ?: it.trakt?.toString() } ?: return null
            if (tv) {
                traktAuth.get().executeAuthorizedRequest { header -> api.getShowRelated(header, slug, "min", 1, RELATED_ITEMS) }
                    ?.takeIf { it.isSuccessful }?.body()?.mapNotNull { it.ids?.tmdb }
            } else {
                traktAuth.get().executeAuthorizedRequest { header -> api.getMovieRelated(header, slug, "min", 1, RELATED_ITEMS) }
                    ?.takeIf { it.isSuccessful }?.body()?.mapNotNull { it.ids?.tmdb }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "No related: ${error.javaClass.simpleName}")
            null
        }
    }

    private suspend fun lookUpCandidates(
        tally: Map<String, Pair<ScoutPick, Double>>,
        known: MutableMap<String, ScoutTasteTitle>,
        now: Long
    ): Map<String, ScoutTasteTitle> {
        val wanted = tally.values.sortedByDescending { it.second + 0.3 * (it.first.rating ?: 0f) / 10.0 }.take(LOOKUPS).map { it.first }
        val missing = wanted.filter { it.key !in known }
        val gate = Semaphore(MAX_REQUESTS)
        try {
            coroutineScope {
                missing.map { pick ->
                    async {
                        gate.withPermit {
                            try {
                                scoutLookUpFacts(tmdbApi, pick.tmdbId, pick.tv, pick.key, now)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Log.w(TAG, "No facts: ${error.javaClass.simpleName}")
                                null
                            }
                        }?.let { synchronized(known) { known[pick.key] = it } }
                    }
                }.awaitAll()
            }
        } finally {
            run {
                val kept = known.values.sortedByDescending { it.fetchedAt }.take(FACTS_KEEP)
                runCatching { factsFile().apply { parentFile?.mkdirs() }.writeText(factsAdapter.toJson(kept)) }
            }
        }
        return wanted.mapNotNull { pick -> known[pick.key]?.let { pick.key to it } }.toMap()
    }
}

/** Titles opened from a Scout row. A plain file so a title page needs nothing injected. */
object ScoutOpenedLog {
    private const val KEEP_BYTES = 64_000L
    private fun file(context: Context) = File(context.filesDir, "scout/opened.txt")

    fun mark(context: Context, itemId: String, itemType: String) {
        if (!itemId.startsWith("tmdb:")) return
        val key = (if (itemType.trim().lowercase() == "movie") "movie:" else "tv:") + itemId.removePrefix("tmdb:")
        kotlin.concurrent.thread(name = "scout-opened") {
            runCatching {
                val target = file(context).apply { parentFile?.mkdirs() }
                if (target.length() > KEEP_BYTES) target.writeText(target.readLines().takeLast(1000).joinToString("\n", postfix = "\n"))
                target.appendText(key + "\n")
            }
        }
    }

    fun read(context: Context): Set<String> =
        runCatching { file(context).readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
}
