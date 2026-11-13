package com.nuvio.tv.core.scout

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.ArcDispatchers
import com.nuvio.tv.data.local.ScoutStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.SCOUT_IMPORTED_TASTE_TITLES
import com.nuvio.tv.domain.model.limitImported
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.ScoutPool
import com.nuvio.tv.domain.model.ScoutProfile
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutRatingEntry
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.scoutNormalizedName
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

const val SCOUT_TASTE_TITLES = 400
private const val FACTS_VERSION = 2
const val SCOUT_TASTE_MIN_TITLES = 3
const val SCOUT_FAILED_RETRY_MS = 12L * 60 * 60 * 1000
const val SCOUT_FACTS_MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000
private const val TAG = "ScoutTaste"
private const val MAX_REQUESTS = 2
private const val BARS = 3
private const val DECADES = 6
private const val DIRECTORS = 12
private const val ACTORS = 12
private const val MIN_PERSON_TITLES = 2
private const val NOT_FOR_ME_PENALTY = 2.0
private const val TOP_CAST = 3
private const val TOP_KEYWORDS = 30
private const val TOP_WRITERS = 3
private const val TOP_OVERVIEW = 400
private const val SAVE_EVERY = 10
private const val LOVE_WEIGHT = 3.0
private const val LIKE_WEIGHT = 2.0
private const val WATCHED_WEIGHT = 1.0
private const val RECENT_TITLES = 20
private const val MIN_RECENT = 10
private const val LATELY_RISE = 0.15

@JsonClass(generateAdapter = true)
data class ScoutTastePerson(val id: Int, val name: String, val crew: Boolean = false, val photo: String? = null)

/** What one title adds to the page; looked up once and kept on the box. */
@JsonClass(generateAdapter = true)
data class ScoutTasteTitle(
    val contentId: String,
    val genres: List<Int> = emptyList(),
    val decade: Int? = null,
    val countries: List<String> = emptyList(),
    val people: List<ScoutTastePerson> = emptyList(),
    val backdrop: String? = null,
    val keywords: List<ScoutKeyword> = emptyList(),
    val writers: List<ScoutTastePerson> = emptyList(),
    val language: String? = null,
    val runtime: Int? = null,
    val votes: Int? = null,
    val collection: Int? = null,
    val fetchedAt: Long = 0L,
    val name: String? = null,
    val poster: String? = null,
    val overview: String? = null,
    val released: String? = null,
    val rating: Float? = null,
    val ageLevel: Int? = null,
    val ageKnown: Boolean = false,
    val factsVersion: Int = 0,
    val failedAt: Long = 0L
) {
    /** A title TMDB couldn't answer for is left alone for a while. */
    fun restingAfterFailure(now: Long) = now - failedAt in 0 until SCOUT_FAILED_RETRY_MS

    fun fresh(now: Long) = ageKnown && factsVersion >= FACTS_VERSION && now - fetchedAt in 0 until SCOUT_FACTS_MAX_AGE_MS
}

@JsonClass(generateAdapter = true)
data class ScoutKeyword(val id: Int, val name: String)

data class ScoutTasteBar(val key: String, val share: Float)

data class ScoutTasteSummary(
    val genres: List<ScoutTasteBar> = emptyList(),
    val decades: List<ScoutTasteBar> = emptyList(),
    val countries: List<ScoutTasteBar> = emptyList(),
    val directors: List<ScoutTastePerson> = emptyList(),
    val actors: List<ScoutTastePerson> = emptyList(),
    val backdrops: List<String> = emptyList(),
    val latelyGenre: Int? = null,
    val latelyCountry: String? = null
) {
    val isEmpty: Boolean get() = genres.isEmpty() && decades.isEmpty() && countries.isEmpty()

    /** The two decades with the most behind them, oldest first. */
    val topDecades: List<String> get() = decades.sortedByDescending { it.share }.take(2).map { it.key }.sorted()
}

/** Episodes seen of a show, or 1 for a film. */
fun scoutWatchCounts(watched: List<WatchedItem>): Map<String, Int> = watched.filter { it.contentId.isNotBlank() }
    .groupBy { it.contentId }
    .mapValues { (_, items) -> items.distinctBy { it.season to it.episode }.size }

/** More watching counts for more, but less with each episode, so one long sitcom can't fill a row. Love and Like multiply it. */
fun scoutWatchScore(rating: ScoutRating?, watches: Int): Double = scoutTasteWeight(rating) * (1 + ln(watches.coerceAtLeast(1).toDouble()))

fun scoutTasteWeight(rating: ScoutRating?): Double = when (rating) {
    ScoutRating.LOVE -> LOVE_WEIGHT
    ScoutRating.LIKE -> LIKE_WEIGHT
    ScoutRating.NOT_FOR_ME -> 0.0
    null -> WATCHED_WEIGHT
}

private fun bars(weights: Map<String, Double>): List<ScoutTasteBar> {
    val top = weights.values.maxOrNull()?.takeIf { it > 0 } ?: return emptyList()
    return weights.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
        .take(BARS)
        .map { ScoutTasteBar(it.key, (it.value / top).toFloat()) }
}

private fun <K> shareRise(all: List<ScoutTasteTitle>, recent: List<ScoutTasteTitle>, of: (ScoutTasteTitle) -> List<K>): K? {
    if (recent.size < MIN_RECENT || all.size <= recent.size) return null
    fun shares(list: List<ScoutTasteTitle>) = list.flatMap { of(it).distinct() }.groupingBy { it }.eachCount()
        .mapValues { it.value.toDouble() / list.size }
    val before = shares(all)
    return shares(recent).mapValues { (key, share) -> share - (before[key] ?: 0.0) }
        .filterValues { it >= LATELY_RISE }
        .maxByOrNull { it.value }?.key
}

fun scoutTasteSummary(
    titles: List<ScoutTasteTitle>,
    ratings: Map<String, ScoutRatingEntry>,
    recentIds: Set<String> = emptySet(),
    watches: Map<String, Int> = emptyMap()
): ScoutTasteSummary {
    val genres = HashMap<String, Double>()
    val decades = HashMap<Int, Double>()
    val countries = HashMap<String, Double>()
    val personWeight = HashMap<Int, Double>()
    val personTitles = HashMap<Int, Int>()
    val persons = HashMap<Int, ScoutTastePerson>()
    titles.forEach { title ->
        val rating = ratings[title.contentId]?.rating
        if (rating == ScoutRating.NOT_FOR_ME) {
            title.people.distinctBy { it.id }.forEach { personWeight.merge(it.id, -NOT_FOR_ME_PENALTY, Double::plus) }
        }
        val weight = scoutTasteWeight(rating)
        if (weight <= 0) return@forEach
        val personScore = scoutWatchScore(rating, watches[title.contentId] ?: 1)
        title.genres.distinct().forEach { genres.merge(it.toString(), weight, Double::plus) }
        title.decade?.let { decades.merge(it, weight, Double::plus) }
        title.countries.forEach { countries.merge(it, weight, Double::plus) }
        title.people.distinctBy { it.id }.forEach { person ->
            personWeight.merge(person.id, personScore, Double::plus)
            personTitles.merge(person.id, 1, Int::plus)
            persons[person.id] = person
        }
    }
    fun people(crew: Boolean, count: Int) = personWeight.entries
        .filter { it.value > 0 && (personTitles[it.key] ?: 0) >= MIN_PERSON_TITLES && persons.getValue(it.key).crew == crew }
        .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { persons.getValue(it.key).name })
        .take(count)
        .map { persons.getValue(it.key) }
    val topDecade = decades.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val shownDecades = decades.entries.sortedByDescending { it.key }.take(DECADES).sortedBy { it.key }
        .map { ScoutTasteBar(it.key.toString(), (it.value / topDecade).toFloat()) }
    val recent = titles.filter { it.contentId in recentIds }.take(RECENT_TITLES)
    return ScoutTasteSummary(
        genres = bars(genres),
        decades = shownDecades,
        countries = bars(countries),
        directors = people(crew = true, count = DIRECTORS),
        actors = people(crew = false, count = ACTORS),
        backdrops = titles.filter { it.backdrop != null && scoutTasteWeight(ratings[it.contentId]?.rating) > 0 }
            .sortedByDescending { scoutTasteWeight(ratings[it.contentId]?.rating) }
            .take(3).mapNotNull { it.backdrop },
        latelyGenre = shareRise(titles, recent) { it.genres },
        latelyCountry = shareRise(titles, recent) { it.countries }
    )
}

fun scoutCountryWeights(titles: List<ScoutTasteTitle>, ratings: Map<String, ScoutRatingEntry>): Map<String, Double> {
    val countries = HashMap<String, Double>()
    titles.forEach { title ->
        val weight = scoutTasteWeight(ratings[title.contentId]?.rating)
        if (weight > 0) title.countries.distinct().forEach { countries.merge(it, weight, Double::plus) }
    }
    return countries
}

/** The latest finished titles, plus everything rated on top. */
fun scoutTasteTargets(watched: List<WatchedItem>, ratings: Collection<ScoutRatingEntry>): List<Pair<String, Boolean>> {
    val seen = watched.filter { it.contentId.isNotBlank() }.groupBy { it.contentId }
        .map { (_, items) -> items.maxBy { it.watchedAt } }
        .sortedByDescending { it.watchedAt }
        .take(SCOUT_TASTE_TITLES)
        .map { it.contentId to (it.contentType.trim().lowercase() != "movie") }
    val rated = ratings.limitImported(SCOUT_IMPORTED_TASTE_TITLES).sortedByDescending { it.ratedAt }
        .map { it.title.id to (it.title.type.trim().lowercase() != "movie") }
    val ids = HashSet<String>()
    return (seen + rated).filter { ids.add(it.first) }
}

/** A title the profile has probably heard of, never one it has seen or rated. */
fun scoutNudge(pool: ScoutPool?, profile: ScoutProfile, skipped: Set<String>): ScoutPick? {
    val ratedNames = profile.ratings.values.map { scoutNormalizedName(it.title.name) }.toSet()
    return pool?.picked.orEmpty()
        .filter { it.key !in skipped && "tmdb:${it.tmdbId}" !in profile.ratings && scoutNormalizedName(it.name) !in ratedNames }
        .maxByOrNull { it.votes ?: 0 }
}

data class ScoutTasteProgress(val titles: List<ScoutTasteTitle>, val done: Int, val total: Int)

@Singleton
class ScoutTasteRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val watchProgress: WatchProgressRepository,
    private val store: ScoutStore,
    private val profileManager: ProfileManager
) {
    private val adapter = Moshi.Builder().build().adapter<List<ScoutTasteTitle>>(
        Types.newParameterizedType(List::class.java, ScoutTasteTitle::class.java)
    )
    private val lock = Mutex()

    private fun file(profileId: Int) = File(context.filesDir, "scout/taste_$profileId.json")

    /** Only called while the page is open. Titles that fail are simply tried on the next visit. */
    suspend fun load(onProgress: (ScoutTasteProgress) -> Unit) = lock.withLock {
        withContext(Dispatchers.IO) {
            val target = file(profileManager.activeProfileId.value)
            val known = runCatching { adapter.fromJson(target.readText()).orEmpty() }.getOrDefault(emptyList())
                .associateBy { it.contentId }.toMutableMap()
            val wanted = scoutTasteTargets(watchProgress.tasteHistory.first(), store.current().ratings.values)
            val now = System.currentTimeMillis()
            known.keys.retainAll(wanted.map { it.first }.toSet())
            val missing = wanted.filter { known[it.first].needsLookUp(now) }
            var done = wanted.size - missing.size
            fun titles() = wanted.mapNotNull { known[it.first]?.takeIf { title -> title.fetchedAt > 0 } }
            onProgress(ScoutTasteProgress(titles(), done, wanted.size))
            val gate = Semaphore(MAX_REQUESTS)
            val update = Mutex()
            try {
                coroutineScope {
                    missing.map { (id, tv) ->
                        async {
                            val title = gate.withPermit { lookUp(id, tv) }
                            update.withLock {
                                done++
                                known[id] = title ?: failed(known[id], id)
                                onProgress(ScoutTasteProgress(titles(), done, wanted.size))
                                if (done % SAVE_EVERY == 0) save(target, known.values.toList())
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                save(target, known.values.toList())
            }
        }
    }

    /** For the daily rebuild: the given titles, looked up only if they are not known yet. */
    suspend fun fill(wanted: List<Pair<String, Boolean>>, now: Long, profileId: Int = profileManager.activeProfileId.value): Map<String, ScoutTasteTitle> = lock.withLock {
        withContext(ArcDispatchers.background) {
            val target = file(profileId)
            val known = runCatching { adapter.fromJson(target.readText()).orEmpty() }.getOrDefault(emptyList())
                .associateBy { it.contentId }.toMutableMap()
            val missing = wanted.filter { known[it.first].needsLookUp(now) }
            val gate = Semaphore(MAX_REQUESTS)
            coroutineScope {
                missing.map { (id, tv) -> async { id to gate.withPermit { lookUp(id, tv) } } }.awaitAll()
            }.forEach { (id, title) -> known[id] = title ?: failed(known[id], id) }
            if (missing.isNotEmpty()) save(target, known.values.toList())
            wanted.mapNotNull { (id, _) -> known[id]?.takeIf { it.fetchedAt > 0 }?.let { id to it } }.toMap()
        }
    }

    /** How much each country's shows count in what the profile watched, from facts already on the box. No lookups. */
    suspend fun countryWeights(): Map<String, Double> = withContext(Dispatchers.IO) {
        val known = runCatching { adapter.fromJson(file(profileManager.activeProfileId.value).readText()).orEmpty() }
            .getOrDefault(emptyList())
        if (known.isEmpty()) return@withContext emptyMap()
        val ratings = store.current().ratings
        val wanted = scoutTasteTargets(watchProgress.tasteHistory.first(), ratings.values).map { it.first }.toSet()
        scoutCountryWeights(known.filter { it.contentId in wanted }, ratings)
    }

    private fun save(target: File, titles: List<ScoutTasteTitle>) {
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.path + ".tmp")
            temp.writeText(adapter.toJson(titles))
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    private fun failed(old: ScoutTasteTitle?, id: String) =
        (old ?: ScoutTasteTitle(contentId = id)).copy(failedAt = System.currentTimeMillis())

    private suspend fun lookUp(contentId: String, tv: Boolean): ScoutTasteTitle? {
        try {
            val id = tmdbService.ensureTmdbId(contentId, if (tv) "tv" else "movie")?.toIntOrNull() ?: return null
            return scoutLookUpFacts(tmdbApi, id, tv, contentId, System.currentTimeMillis())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "No lookup: ${error.javaClass.simpleName}")
            return null
        }
    }
}

internal fun ScoutTasteTitle?.needsLookUp(now: Long): Boolean =
    this == null || (!fresh(now) && !restingAfterFailure(now))

/** One request for one title, with its keywords and credits. */
suspend fun scoutLookUpFacts(tmdbApi: TmdbApi, id: Int, tv: Boolean, contentId: String, now: Long, language: String? = null): ScoutTasteTitle? {
    val key = BuildConfig.TMDB_API_KEY
    val details = (if (tv) tmdbApi.getTvFacts(id, key, language) else tmdbApi.getMovieFacts(id, key, language)).body() ?: return null
    val date = if (tv) details.firstAirDate else details.releaseDate
    fun photo(path: String?) = path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w185$it" }
    val crew = details.credits?.crew.orEmpty().filter { it.id != null && !it.name.isNullOrBlank() }
    val directors = crew.filter { it.job == "Director" }
        .map { ScoutTastePerson(it.id!!, it.name!!, crew = true, photo = photo(it.profilePath)) }
    val cast = details.credits?.cast.orEmpty().filter { it.id != null && !it.name.isNullOrBlank() }.take(TOP_CAST)
        .map { ScoutTastePerson(it.id!!, it.name!!, photo = photo(it.profilePath)) }
    val creators = details.createdBy.orEmpty().filter { it.id != null && !it.name.isNullOrBlank() }
        .map { ScoutTastePerson(it.id!!, it.name!!, crew = true, photo = photo(it.profilePath)) }
    val writers = crew.filter { it.job == "Writer" || it.job == "Screenplay" }.map { ScoutTastePerson(it.id!!, it.name!!, crew = true) }
        .distinctBy { it.id }.take(TOP_WRITERS)
    return ScoutTasteTitle(
        contentId = contentId,
        genres = details.genres.orEmpty().map { scoutCanonicalGenre(it.id) }.distinct(),
        decade = date?.take(4)?.toIntOrNull()?.let { it / 10 * 10 },
        countries = (details.productionCountries.orEmpty().mapNotNull { it.iso31661 } + details.originCountry.orEmpty())
            .filter { it.isNotBlank() }.distinct().take(2),
        people = directors + creators + cast,
        backdrop = details.backdropPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w780$it" },
        keywords = details.keywords?.let { it.keywords ?: it.results }.orEmpty().take(TOP_KEYWORDS),
        writers = writers,
        language = details.originalLanguage,
        runtime = details.runtime ?: details.episodeRunTime?.firstOrNull(),
        votes = details.voteCount,
        collection = details.collection?.id,
        fetchedAt = now,
        name = (details.title ?: details.name)?.takeIf { it.isNotBlank() },
        poster = details.posterPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w342$it" },
        overview = details.overview?.takeIf { it.isNotBlank() }?.take(TOP_OVERVIEW),
        released = date?.takeIf { it.isNotBlank() },
        rating = details.voteAverage?.toFloat(),
        ageLevel = if (tv) details.contentRatings?.results?.firstOrNull { it.iso31661 == "US" }?.rating?.let { scoutAgeLevel(it, true) }
        else details.releaseDates?.results?.firstOrNull { it.iso31661 == "US" }?.releaseDates?.firstNotNullOfOrNull { scoutAgeLevel(it.certification, false) },
        ageKnown = true,
        factsVersion = FACTS_VERSION
    )
}
