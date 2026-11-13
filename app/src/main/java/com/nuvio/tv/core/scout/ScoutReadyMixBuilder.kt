package com.nuvio.tv.core.scout

import android.content.Context
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.data.local.ScoutMixStore
import com.nuvio.tv.domain.model.ScoutMix
import com.nuvio.tv.domain.model.ScoutMixShow
import com.nuvio.tv.domain.model.ScoutPick
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

internal const val SCOUT_READY_SHOWS = 15
internal const val SCOUT_READY_FEWEST = 8
internal const val SCOUT_READY_TOO_FEW = 4
/** Raise when the way mixes are filled changes, so kept mixes are filled again at once rather than on Monday. */
internal const val SCOUT_READY_RULES = 2
/** An occasion needs this many good episodes before its tile shows. */
internal const val SCOUT_OCCASION_FEWEST = 15
private const val PAGES = 3
private const val PREVIEW_PAGES = 1
private const val BY_VOTES = "vote_count.desc"
private const val ID_TIMEOUT_MS = 6_000L
private const val BUILD_TIMEOUT_MS = 25_000L
private const val OCCASIONS_FILE = "scout_occasions.json"
private const val EPISODES_PER_SHOW = 4
private const val SURPRISE_SKIPS = 10
private const val OCCASION_PREFIX = "o"
private const val NEIGHBOUR_YEARS = 10
private const val LOW_VOTES = 20
/** Requests in flight at once, so a page of tiles never opens a burst of connections on a small box. */
private const val AT_ONCE = 4

/** What Scout had to loosen to find enough shows, said in one line under the posters. */
enum class ScoutWidened { NONE, DECADE, VOTES, MOOD }

data class ScoutMixPreview(val picks: List<ScoutPick>, val widened: ScoutWidened, val tooFew: Boolean)

/** One episode of the bundled occasion lists. */
data class ScoutOccasionEpisode(
    val imdb: String,
    val name: String,
    val season: Int,
    val episode: Int,
    val ratings: Map<String, String>,
    val poster: String?,
    val backdrop: String?
)

/** Ratings that count as fine for the whole family, where a show has one for that country. */
private val FAMILY_RATINGS = mapOf(
    "US" to setOf("TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG"),
    "AU" to setOf("P", "C", "G", "PG"),
    "GB" to setOf("U", "PG"),
    "DE" to setOf("0", "6"),
    "NL" to setOf("AL", "6", "9"),
    "IE" to setOf("G", "PG")
)

internal fun ScoutOccasionEpisode.suitsFamily(): Boolean =
    ratings.isNotEmpty() && ratings.all { (country, rating) -> FAMILY_RATINGS[country]?.contains(rating) ?: true }

/** At most a few episodes of any one show, chosen afresh each week, so no single show takes over. */
internal fun scoutOccasionChoice(episodes: List<ScoutOccasionEpisode>, family: Boolean, seed: Long): Map<String, List<ScoutOccasionEpisode>> {
    val random = Random(seed)
    return episodes.filter { !family || it.suitsFamily() }
        .groupBy { it.imdb }
        .mapValues { (_, list) -> list.distinctBy { it.season to it.episode }.shuffled(random).take(EPISODES_PER_SHOW) }
}

/** Taste first, popular first, or the less obvious ones further down the list. */
internal fun scoutBlendOrder(byVotes: List<ScoutPick>, byTaste: List<ScoutPick>, blend: ScoutMixBlend, seed: Long): List<ScoutPick> = when (blend) {
    ScoutMixBlend.CLOSE -> byTaste
    ScoutMixBlend.BALANCED -> buildList {
        val taste = byTaste.iterator()
        val votes = byVotes.filter { pick -> byTaste.any { it.key == pick.key } }.iterator()
        val seen = HashSet<String>()
        while (taste.hasNext() || votes.hasNext()) {
            if (taste.hasNext()) taste.next().takeIf { seen.add(it.key) }?.let(::add)
            if (votes.hasNext()) votes.next().takeIf { seen.add(it.key) }?.let(::add)
        }
    }
    ScoutMixBlend.SURPRISE -> {
        val kept = byVotes.filter { pick -> byTaste.any { it.key == pick.key } }
        val further = kept.drop(SURPRISE_SKIPS).shuffled(Random(seed))
        further + kept.take(SURPRISE_SKIPS)
    }
}

@Singleton
class ScoutReadyMixBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val finder: ScoutPicksFinder,
    private val tmdbService: TmdbService,
    private val store: ScoutMixStore
) {
    private val previews = HashMap<String, Pair<LocalDate, ScoutMixPreview>>()
    private val previewLock = Mutex()
    private val building = Mutex()
    private val requests = Semaphore(AT_ONCE)
    @Volatile private var occasions: Map<String, List<ScoutOccasionEpisode>>? = null

    private fun country(): String = Locale.getDefault().country

    /** The shows a recipe finds, in the order the blend asks for, loosened step by step when too few are left. */
    suspend fun preview(recipe: ScoutMixRecipe, years: IntRange? = null, pages: Int = PREVIEW_PAGES): ScoutMixPreview {
        val today = LocalDate.now()
        val cacheKey = recipe.encode() + years + pages
        previewLock.withLock { previews[cacheKey]?.takeIf { it.first == today }?.let { return it.second } }
        val steps = listOf(
            ScoutWidened.NONE to recipe,
            ScoutWidened.DECADE to recipe,
            ScoutWidened.VOTES to recipe,
            ScoutWidened.MOOD to recipe.copy(feel = ScoutMixFeel.ANY)
        ).filter { (step, _) ->
            when (step) {
                ScoutWidened.DECADE -> recipe.decade != ScoutMixDecade.ANY
                ScoutWidened.MOOD -> recipe.feel != ScoutMixFeel.ANY
                else -> true
            }
        }
        var best = ScoutMixPreview(emptyList(), ScoutWidened.NONE, true)
        for ((step, choice) in steps) {
            val widen = if (step == ScoutWidened.NONE) 0 else NEIGHBOUR_YEARS
            val votes = if (step == ScoutWidened.VOTES || step == ScoutWidened.MOOD) LOW_VOTES else 50
            val found = find(choice, years, widen, votes, pages, today)
            best = ScoutMixPreview(found, step, found.size < SCOUT_READY_TOO_FEW)
            if (found.size >= SCOUT_READY_FEWEST) break
        }
        previewLock.withLock { previews[cacheKey] = today to best }
        return best
    }

    private suspend fun find(recipe: ScoutMixRecipe, years: IntRange?, widen: Int, votes: Int, pages: Int, today: LocalDate): List<ScoutPick> {
        val found = if (recipe.kind == ScoutMixKind.ANY && recipe.feel == ScoutMixFeel.ANY) {
            classic(recipe, years, widen, votes, today).takeIf { it.size >= SCOUT_CLASSIC_FEWEST }
                ?: search(recipe, years, widen, votes, pages, today, strict = true)
        } else {
            val strict = search(recipe, years, widen, votes, pages, today, strict = true)
            val kind = recipe.kind
            if (strict.size >= SCOUT_READY_FEWEST || (kind.looseKeywords == null && kind.keywords == null)) strict
            else (strict + search(recipe, years, widen, votes, pages, today, strict = false)).distinctBy { it.key }
        }
        val exclusions = runCatching { finder.exclusions() }.getOrNull() ?: return found
        val seed = scoutReadyWeekSeed(today)
        return withContext(Dispatchers.Default) {
            val byTaste = scoutOrderPicks(found, finder.pool(), exclusions, seed)
            scoutBlendOrder(found, byTaste, recipe.blend, seed)
        }
    }

    /** The best known of each kind where any episode works on its own, most votes first. */
    private suspend fun classic(recipe: ScoutMixRecipe, years: IntRange?, widen: Int, votes: Int, today: LocalDate): List<ScoutPick> =
        coroutineScope {
            SCOUT_CLASSIC_KINDS.map { kind -> async { search(recipe.copy(kind = kind), years, widen, votes, 1, today, strict = true) } }
                .awaitAll()
        }.flatten().distinctBy { it.key }.sortedByDescending { it.votes ?: 0 }

    private suspend fun search(
        recipe: ScoutMixRecipe,
        years: IntRange?,
        widen: Int,
        votes: Int,
        pages: Int,
        today: LocalDate,
        strict: Boolean
    ): List<ScoutPick> {
        var filters = recipe.filters(country(), today, widen, votes, strict)
        if (years != null) {
            filters = filters.copy(
                releaseDateGte = "${years.first - widen}-01-01",
                releaseDateLte = "${minOf(years.last + widen, today.year)}-12-31"
            )
        }
        val found = coroutineScope {
            (1..pages).map { page -> async { requests.withPermit { runCatching { finder.discoverSeries(filters, page, BY_VOTES) }.getOrDefault(emptyList()) } } }
                .awaitAll().flatten().distinctBy { it.key }
        }
        return scoutFromRegion(found, recipe.from)
    }

    /** A ready-made mix as it should be this week: the kept one if it is still fresh, otherwise built again and kept. */
    suspend fun ensure(id: String, family: Boolean? = null, name: String? = null): ScoutMix? = building.withLock {
        val profileId = store.profileId()
        val kept = store.readyMix(profileId, id)
        val wantFamily = family ?: kept?.let { readyFamily(it) } ?: false
        if (kept != null && scoutReadyFresh(kept.builtAt) && kept.rules >= SCOUT_READY_RULES && readyFamily(kept) == wantFamily && kept.shows.isNotEmpty()) return@withLock kept
        val ready = ScoutReadyMixes.byId(id)
        val built = withTimeoutOrNull(BUILD_TIMEOUT_MS) {
            when {
                ready?.occasion != null -> occasionMix(id, ready.occasion, wantFamily)
                ready?.recipe != null -> recipeMix(id, local().getString(ready.nameRes), ready.recipe.copy(family = wantFamily), ready.years)
                else -> ScoutMixRecipe.decode(kept?.recipe)?.let { recipeMix(id, name ?: kept?.name.orEmpty(), it.copy(family = wantFamily), null) }
            }
        } ?: return@withLock kept
        val mix = kept?.let { built.copy(unwatchedOnly = it.unwatchedOnly, keepPlaying = it.keepPlaying) } ?: built
        store.putReady(mix)
        mix
    }

    /** A mix the viewer made from their own choices, kept in one slot that each new one replaces. */
    suspend fun own(recipe: ScoutMixRecipe, name: String): ScoutMix? = building.withLock {
        val mix = withTimeoutOrNull(BUILD_TIMEOUT_MS) { recipeMix(OWN_ID, name, recipe, null) } ?: return@withLock null
        store.putReady(mix)
        mix
    }

    private fun local() = context.withAppLocale()

    private fun readyFamily(mix: ScoutMix): Boolean = mix.recipe?.let { raw ->
        ScoutMixRecipe.decode(raw)?.family ?: raw.endsWith(".f")
    } ?: false

    private suspend fun recipeMix(id: String, name: String, recipe: ScoutMixRecipe, years: IntRange?): ScoutMix? {
        val found = preview(recipe, years, PAGES)
        if (found.picks.isEmpty()) return null
        val shows = coroutineScope { found.picks.take(SCOUT_READY_SHOWS).map { async { show(it) } }.awaitAll() }
        return ScoutMix(
            id = id,
            name = name,
            shows = shows,
            autoName = false,
            recipe = recipe.encode(),
            rules = SCOUT_READY_RULES,
            builtAt = System.currentTimeMillis()
        )
    }

    private suspend fun show(pick: ScoutPick): ScoutMixShow {
        val imdb = requests.withPermit { withTimeoutOrNull(ID_TIMEOUT_MS) { runCatching { tmdbService.tmdbToImdb(pick.tmdbId, "tv") }.getOrNull() } }
            ?.takeIf { it.isNotBlank() }
        return ScoutMixShow(
            id = imdb ?: "tmdb:${pick.tmdbId}",
            name = pick.name,
            poster = pick.poster,
            backdrop = pick.backdrop,
            type = "series",
            imdbId = imdb
        )
    }

    private suspend fun occasionMix(id: String, occasion: ScoutOccasion, family: Boolean): ScoutMix? = withContext(Dispatchers.Default) { occasionMixNow(id, occasion, family) }

    private suspend fun occasionMixNow(id: String, occasion: ScoutOccasion, family: Boolean): ScoutMix? {
        val all = occasionEpisodes(occasion).filter { !family || it.suitsFamily() }
        if (all.size < SCOUT_OCCASION_FEWEST) return null
        val chosen = scoutOccasionChoice(all, family, scoutReadyWeekSeed())
        val shows = chosen.values.map { list ->
            val first = list.first()
            ScoutMixShow(id = first.imdb, name = first.name, poster = first.poster, backdrop = first.backdrop, type = "series", imdbId = first.imdb)
        }.shuffled(Random(scoutReadyWeekSeed()))
        return ScoutMix(
            id = id,
            name = local().getString(occasion.nameRes),
            shows = shows,
            unwatchedOnly = false,
            autoName = false,
            recipe = listOf(OCCASION_PREFIX, occasion.code, if (family) "f" else "x").joinToString("."),
            rules = SCOUT_READY_RULES,
            builtAt = System.currentTimeMillis(),
            only = chosen.mapValues { (_, list) -> list.map { it.season to it.episode }.toSet() }
        )
    }

    /** The bundled lists, read once. */
    suspend fun occasionEpisodes(occasion: ScoutOccasion): List<ScoutOccasionEpisode> {
        occasions?.let { return it[occasion.code].orEmpty() }
        val read = withContext(Dispatchers.IO) {
            runCatching { parseScoutOccasions(context.assets.open(OCCASIONS_FILE).bufferedReader().use { it.readText() }) }.getOrDefault(emptyMap())
        }
        occasions = read
        return read[occasion.code].orEmpty()
    }

    companion object {
        const val OWN_ID = "ready_own"
    }
}

internal fun parseScoutOccasions(text: String): Map<String, List<ScoutOccasionEpisode>> {
    val root = JSONObject(text).getJSONObject("occasions")
    return root.keys().asSequence().associateWith { code ->
        val list = root.getJSONObject(code).getJSONArray("episodes")
        (0 until list.length()).mapNotNull { index ->
            val item = list.getJSONObject(index)
            val imdb = item.optString("imdb").takeIf { it.startsWith("tt") } ?: return@mapNotNull null
            val ratings = item.optJSONObject("r")?.let { r -> r.keys().asSequence().associateWith { r.getString(it) } }.orEmpty()
            ScoutOccasionEpisode(
                imdb = imdb,
                name = item.optString("name"),
                season = item.optInt("s", -1).takeIf { it >= 0 } ?: return@mapNotNull null,
                episode = item.optInt("e", -1).takeIf { it > 0 } ?: return@mapNotNull null,
                ratings = ratings,
                poster = item.optString("po").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w342$it" },
                backdrop = item.optString("bd").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w1280$it" }
            )
        }
    }
}
