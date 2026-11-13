package com.nuvio.tv.core.scout

import com.nuvio.tv.domain.model.SCOUT_IMPORTED_SEEDS
import com.nuvio.tv.domain.model.SCOUT_MAX_NEGATIVE_SEEDS
import com.nuvio.tv.domain.model.SCOUT_MIN_SEEDS
import com.nuvio.tv.domain.model.importWeight
import com.nuvio.tv.domain.model.limitImported
import com.nuvio.tv.domain.model.ScoutExclusions
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutRatingEntry
import com.nuvio.tv.domain.model.ScoutSeed
import com.nuvio.tv.domain.model.ScoutTasteRow
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private const val LOVE = 6.0
private const val LIKE = 3.0
private const val REWATCHED = 4.0
private const val FINISHED = 1.0
private const val STOPPED = -1.0
private const val WATCHING = 0.5
private const val NOT_FOR_ME = -3.0
private const val DECAY = 0.97
private const val STOPPED_AFTER_MS = 14L * 24 * 60 * 60 * 1000
private const val STOPPED_FILM_SHARE = 0.2f
private const val STOPPED_EPISODES = 2
private const val RANK_DEPTH = 40.0
private const val MIN_VOTES = 50
private const val MIN_RATING = 6f
const val SCOUT_PICKS_SEEDS = 20
private const val KEYWORD_SCALE = 200.0
private const val MAX_DOT_PENALTY = -0.2

private const val WEIGHT_RANK = 1.0
private const val WEIGHT_RATING = 0.15
private const val WEIGHT_KEYWORDS = 3.0
private const val WEIGHT_PEOPLE = 0.5
private const val WEIGHT_LANGUAGE = 0.15
private const val WEIGHT_COUNTRY = 0.075
private const val WEIGHT_APPETITE = 0.12
private const val WEIGHT_AGE = 0.6
private const val AGE_MIN_TITLES = 5
private const val AGE_SHARE_BELOW = 0.2
private const val AGE_HIGHEST_FLOOR = 2
private const val CEILING_MIN_TITLES = 3
private const val CEILING_HIGHEST = 1
private const val CEILING_ABOVE_SHARE = 0.05
private const val CEILING_COVER = 0.95
private val KIDS_GENRES = setOf(10751, 10762)
private val NOT_FOR_KIDS_GENRES = setOf(27, 53, 80, 10752, 10768)

private const val MAX_SAME_SERIES = 1
private const val MAX_SAME_PERSON = 3
private const val SAME_KEYWORDS = 0.45
private const val SAME_CHECK_LAST = 30

const val SCOUT_STRETCH_EVERY = 5
private const val STRETCH_START = 20
private const val STRETCH_WINDOW = 120
private const val STRETCH_RATING = 7f
private const val STRETCH_VOTES = 100
private const val AVOID_LANGUAGE_MIN_TITLES = 2

const val SCOUT_ROWS_MAX = 3
private const val FRESH_MIN_VOTES = 20
private const val FRESH_MATCH_WORDS = 2
private const val FRESH_MIN_MATCHES = 8
private const val FRESH_ROW_ITEMS = 30
private const val ROW_MIN_TITLES = 3
const val SCOUT_TASTE_ROW_MIN_PICKS = 8
private const val JOIN_SCORE = 1.2
private const val SHARED_GENRE = 1.5
private const val SHARED_LANGUAGE = 1.0

private val SKIP_KEYWORDS = setOf(
    "based on novel or book", "woman director", "aftercreditsstinger", "duringcreditsstinger", "sequel", "duplicate"
)

/** Every title the profile has a signal for, newest first. Love > rewatched > Like > finished; stopped early is a mild minus. */
fun scoutSignals(
    watched: List<WatchedItem>,
    started: List<WatchProgress>,
    ratings: Collection<ScoutRatingEntry>,
    now: Long
): List<ScoutSeed> {
    val byId = ratings.associateBy { it.title.id }
    val startedById = started.filter { it.contentId.isNotBlank() }.groupBy { it.contentId }
    val latest = watched.filter { it.contentId.isNotBlank() }.groupBy { it.contentId }.map { (_, items) ->
        val newest = items.maxBy { it.watchedAt }
        val episodes = items.filter { it.episode != null }.distinctBy { it.season to it.episode }.size
        Triple(newest, episodes, newest.watchedAt)
    }.sortedByDescending { it.third }
    val fromHistory = latest.mapIndexed { index, (item, episodes, at) ->
        val tv = item.contentType.isTv()
        val rated = byId[item.contentId]?.rating
        val again = !tv && startedById[item.contentId].orEmpty().any { it.lastWatched > at }
        val weight = when {
            rated != null -> rated.weight()
            again -> REWATCHED
            tv && episodes in 1..STOPPED_EPISODES && now - at > STOPPED_AFTER_MS -> STOPPED
            else -> FINISHED
        }
        ScoutSeed(item.contentId, tv, item.title, weight * DECAY.pow(index), index)
    }
    val known = fromHistory.map { it.contentId }.toSet()
    val ratedOnly = ratings.limitImported(SCOUT_IMPORTED_SEEDS).filter { it.title.id !in known }.sortedByDescending { it.ratedAt }
        .map { ScoutSeed(it.title.id, it.title.type.isTv(), it.title.name, it.rating.weight() * it.importWeight(), null) }
    val rated = known + ratedOnly.map { it.contentId }
    val stoppedFilms = startedById.filterKeys { it !in rated }.mapNotNull { (id, rows) ->
        val row = rows.maxBy { it.lastWatched }
        val share = when {
            row.duration > 0 -> row.position.toFloat() / row.duration
            row.progressPercent != null -> row.progressPercent / 100f
            else -> return@mapNotNull null
        }
        val tv = row.contentType.isTv()
        val quit = now - row.lastWatched > STOPPED_AFTER_MS &&
            (if (tv) rows.distinctBy { it.season to it.episode }.size <= STOPPED_EPISODES else share < STOPPED_FILM_SHARE)
        if (quit) ScoutSeed(id, tv, row.name, STOPPED, null) else null
    }
    return fromHistory + ratedOnly + stoppedFilms
}

/** Titles being watched that have no other signal yet, newest first, each lighter than a finished one. */
fun scoutWatchingSeeds(started: List<WatchProgress>, signals: List<ScoutSeed>): List<ScoutSeed> {
    val known = signals.map { it.contentId }.toSet()
    return started.filter { it.contentId.isNotBlank() && it.contentId !in known }
        .groupBy { it.contentId }
        .map { (_, rows) -> rows.maxBy { it.lastWatched } }
        .sortedByDescending { it.lastWatched }
        .mapIndexed { index, row -> ScoutSeed(row.contentId, row.contentType.isTv(), row.name, WATCHING * DECAY.pow(index), null) }
}

/** With too few finished or rated titles, what the profile is watching fills in. */
fun scoutSignalSeeds(signals: List<ScoutSeed>, watching: List<ScoutSeed> = emptyList()): List<ScoutSeed> {
    val positive = signals.filter { it.weight > 0 }.sortedByDescending { it.weight }.take(SCOUT_PICKS_SEEDS)
    val lighter = if (positive.size < SCOUT_MIN_SEEDS) watching.filter { it.weight > 0 }.take(SCOUT_PICKS_SEEDS - positive.size) else emptyList()
    val negative = signals.filter { it.weight < 0 }.sortedBy { it.weight }.take(SCOUT_MAX_NEGATIVE_SEEDS)
    return positive + lighter + negative
}

private fun ScoutRating.weight() = when (this) {
    ScoutRating.LOVE -> LOVE
    ScoutRating.LIKE -> LIKE
    ScoutRating.NOT_FOR_ME -> NOT_FOR_ME
}

private fun String.isTv() = trim().lowercase() in setOf("series", "tv", "show", "tvshow", "anime")

/** What the profile's titles have in common, weighted by how much each one counts. */
class ScoutTasteVector(
    val keywords: Map<String, Double>,
    val keywordIds: Map<String, Int>,
    val people: Map<Int, Double>,
    val personNames: Map<Int, String>,
    val lovedPeople: Set<Int>,
    val languages: Map<String, Double>,
    val countries: Map<String, Double>,
    val total: Double,
    val logVotesMean: Double,
    val logVotesSd: Double,
    val ageFloor: Int? = null,
    val avoidedLanguages: Set<String> = emptySet()
) {
    fun topKeywords(frequency: Map<String, Int>, count: Int): List<String> = keywords.entries.filter { it.value > 0 }
        .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value * rarity(it.key, frequency) }.thenBy { it.key })
        .take(count).map { it.key }

    fun topPeople(count: Int): List<Int> = people.entries.filter { it.value > 0 }
        .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key }).take(count).map { it.key }
}

private fun rarity(keyword: String, frequency: Map<String, Int>) = ln(1 + KEYWORD_SCALE / (1 + (frequency[keyword] ?: 0)))

fun ScoutTasteTitle.usefulKeywords(): List<ScoutKeyword> = keywords.filter { it.name !in SKIP_KEYWORDS }

fun ScoutTasteTitle.allCrew(): List<ScoutTastePerson> = people.filter { it.crew } + writers

/** 0 is for everyone, 1 PG, 2 teens (M in Australia), 3 adults. Anything else counts as unknown. */
fun scoutAgeLevel(certification: String?, tv: Boolean): Int? = if (tv) when (certification) {
    "TV-Y", "TV-Y7", "TV-G" -> 0
    "TV-PG" -> 1
    "TV-14" -> 2
    "TV-MA" -> 3
    else -> null
} else when (certification) {
    "G" -> 0
    "PG" -> 1
    "PG-13" -> 2
    "R", "NC-17" -> 3
    else -> null
}

/** The lowest level this profile really watches. Below it a pick is pushed down, never removed. */
fun scoutAgeFloor(ages: List<Pair<Int, Double>>): Int? {
    if (ages.size < AGE_MIN_TITLES) return null
    val total = ages.sumOf { it.second }
    var seen = 0.0
    for (level in 0 until AGE_HIGHEST_FLOOR) {
        seen += ages.filter { it.first == level }.sumOf { it.second }
        if (seen > AGE_SHARE_BELOW * total) return level
    }
    return AGE_HIGHEST_FLOOR
}

/**
 * The highest level a profile that watches only young titles really watches, or null when teen or adult
 * titles carry more than a trace of its weight. Above it a pick is removed.
 */
fun scoutAgeCeiling(ages: List<Pair<Int, Double>>): Int? {
    val counted = ages.filter { it.second > 0 }
    if (counted.size < CEILING_MIN_TITLES) return null
    val total = counted.sumOf { it.second }
    if (counted.filter { it.first > CEILING_HIGHEST }.sumOf { it.second } > CEILING_ABOVE_SHARE * total) return null
    var seen = 0.0
    for (level in 0 until CEILING_HIGHEST) {
        seen += counted.filter { it.first == level }.sumOf { it.second }
        if (seen >= CEILING_COVER * total) return level
    }
    return CEILING_HIGHEST
}

/** Under a ceiling a title of unknown level stays only when it is a family or kids title. */
fun scoutWithinCeiling(pick: ScoutPick, level: Int?, ceiling: Int?): Boolean = when {
    ceiling == null -> true
    level != null -> level <= ceiling
    else -> pick.genres.any { it in KIDS_GENRES } && pick.genres.none { it in NOT_FOR_KIDS_GENRES }
}

fun scoutTasteVector(items: List<Pair<ScoutTasteTitle, Double>>, ratedLove: Set<String> = emptySet()): ScoutTasteVector {
    val keywords = HashMap<String, Double>()
    val ids = HashMap<String, Int>()
    val people = HashMap<Int, Double>()
    val names = HashMap<Int, String>()
    val loved = HashSet<Int>()
    val languages = HashMap<String, Double>()
    val countries = HashMap<String, Double>()
    val notForMe = HashMap<String, Double>()
    val notForMeCount = HashMap<String, Int>()
    val logs = ArrayList<Double>()
    var total = 0.0
    items.forEach { (title, weight) ->
        if (weight > 0) {
            total += weight
            title.usefulKeywords().forEach { keywords.merge(it.name, weight, Double::plus); ids[it.name] = it.id }
            title.allCrew().distinctBy { it.id }.forEach {
                people.merge(it.id, weight, Double::plus)
                names[it.id] = it.name
                if (title.contentId in ratedLove) loved += it.id
            }
            title.language?.let { languages.merge(it, weight, Double::plus) }
            title.countries.forEach { countries.merge(it, weight, Double::plus) }
            title.votes?.let { logs += log10(it + 10.0) }
        } else if (weight < 0) {
            title.usefulKeywords().forEach { keywords.merge(it.name, weight * 0.5, Double::plus) }
            title.language?.let {
                notForMe.merge(it, -weight, Double::plus)
                notForMeCount.merge(it, 1, Int::plus)
            }
        }
    }
    val mean = if (logs.isEmpty()) 3.0 else logs.average()
    val sd = if (logs.size > 1) max(0.4, sqrt(logs.sumOf { (it - mean) * (it - mean) } / logs.size)) else 0.8
    val ages = items.filter { it.second > 0 }.mapNotNull { (title, weight) -> title.ageLevel?.let { it to weight } }
    val avoided = notForMeCount.filter { (language, count) ->
        count >= AVOID_LANGUAGE_MIN_TITLES && (languages[language] ?: 0.0) < notForMe.getValue(language)
    }.keys
    return ScoutTasteVector(keywords, ids, people, names, loved, languages, countries, total.coerceAtLeast(1.0), mean, sd, scoutAgeFloor(ages), avoided)
}

/** A title Trakt suggested, once its TMDB facts are known. No poster, no pick. */
fun ScoutTasteTitle.toScoutPick(tmdbId: Int, tv: Boolean): ScoutPick? = ScoutPick(
    tmdbId = tmdbId,
    tv = tv,
    name = name ?: return null,
    poster = poster ?: return null,
    backdrop = backdrop,
    overview = overview,
    released = released,
    rating = rating,
    votes = votes,
    genres = genres,
    language = language
)

/** Adds a pick's share to its score for each seed that suggested it; extra sources are seeds with no name. */
fun scoutTally(results: List<Pair<ScoutSeed, List<ScoutPick>>>, exclusions: ScoutExclusions): Map<String, Pair<ScoutPick, Double>> {
    val scores = LinkedHashMap<String, Double>()
    val picks = HashMap<String, ScoutPick>()
    val best = HashMap<String, Double>()
    results.forEach { (seed, list) ->
        list.forEachIndexed { rank, pick ->
            val share = seed.weight * max(0.05, 1.0 - rank / RANK_DEPTH)
            scores.merge(pick.key, share, Double::plus)
            picks.putIfAbsent(pick.key, pick)
            if (seed.weight > 0 && seed.name.isNotBlank() && share > (best[pick.key] ?: 0.0)) {
                best[pick.key] = share
                picks[pick.key] = pick.copy(seedName = seed.name)
            }
        }
    }
    return scores.filterValues { it > 0 }.mapNotNull { (key, score) ->
        val pick = picks.getValue(key)
        if (exclusions.blocks(pick) || (pick.votes ?: 0) < MIN_VOTES || (pick.rating ?: MIN_RATING) < MIN_RATING) null else key to (pick to score)
    }.toMap()
}

fun scoutScore(pick: ScoutPick, rank: Double, facts: ScoutTasteTitle?, taste: ScoutTasteVector, frequency: Map<String, Int>, keywordNorm: Double, appetite: Boolean = true): Double {
    var score = WEIGHT_RANK * sqrt(rank) + WEIGHT_RATING * (pick.rating ?: 0f) / 10.0
    if (facts != null) {
        val own = facts.usefulKeywords().map { it.name }
        if (own.isNotEmpty()) {
            val dot = own.filter { it in taste.keywords }.sumOf { taste.keywords.getValue(it) * rarity(it, frequency).pow(2) }
            val size = sqrt(own.sumOf { rarity(it, frequency).pow(2) }).coerceAtLeast(1.0)
            score += WEIGHT_KEYWORDS * max(MAX_DOT_PENALTY, dot / (keywordNorm * size))
        }
        val crew = facts.allCrew().distinctBy { it.id }
        if (crew.isNotEmpty() && taste.people.isNotEmpty()) {
            val top = taste.people.values.max().coerceAtLeast(1e-6)
            score += WEIGHT_PEOPLE * min(1.0, crew.sumOf { max(0.0, taste.people[it.id] ?: 0.0) } / top)
        }
        facts.language?.let { score += WEIGHT_LANGUAGE * (taste.languages[it] ?: 0.0) / taste.total }
        score += WEIGHT_COUNTRY * (facts.countries.maxOfOrNull { taste.countries[it] ?: 0.0 } ?: 0.0) / taste.total
        val level = facts.ageLevel
        val floor = taste.ageFloor
        if (level != null && floor != null && level < floor) score -= WEIGHT_AGE * (floor - level)
    }
    if (!appetite) return score
    val votes = facts?.votes ?: pick.votes ?: 0
    val z = (log10(votes + 10.0) - taste.logVotesMean) / taste.logVotesSd
    return score - WEIGHT_APPETITE * z * z
}

fun scoutKeywordNorm(taste: ScoutTasteVector, frequency: Map<String, Int>): Double =
    sqrt(taste.keywords.entries.filter { it.value > 0 }.sumOf { (it.value * rarity(it.key, frequency)).pow(2) }).coerceAtLeast(1.0)

fun scoutReRank(
    tally: Map<String, Pair<ScoutPick, Double>>,
    facts: Map<String, ScoutTasteTitle>,
    taste: ScoutTasteVector,
    lookups: Int
): List<ScoutPick> {
    val ranked = tally.values.sortedByDescending { it.second + 0.3 * (it.first.rating ?: 0f) / 10.0 }
    val head = ranked.take(lookups)
    val frequency = HashMap<String, Int>()
    facts.values.forEach { title -> title.usefulKeywords().forEach { frequency.merge(it.name, 1, Int::plus) } }
    val norm = scoutKeywordNorm(taste, frequency)
    val top = head.maxOfOrNull { it.second }?.coerceAtLeast(1e-6) ?: 1.0
    val scored = head.map { (pick, score) ->
        pick to scoutScore(pick, score / top, facts[pick.key], taste, frequency, norm)
    }.sortedByDescending { it.second }.map { it.first }
    return scored + ranked.drop(lookups).map { it.first }
}

/** New or trending titles in the order they came, re-sorted by taste. A row with too little in common with the profile stays hidden. */
fun scoutFreshRow(
    titles: List<ScoutPick>,
    facts: Map<String, ScoutTasteTitle>,
    taste: ScoutTasteVector,
    frequency: Map<String, Int>,
    exclusions: ScoutExclusions,
    skip: Set<String>
): List<ScoutPick> {
    val usable = titles.distinctBy { it.key }.filter { it.key !in skip && !exclusions.blocks(it) && (it.votes ?: 0) >= FRESH_MIN_VOTES }
    val matches = usable.count { pick ->
        val fact = facts[pick.key] ?: return@count false
        fact.usefulKeywords().count { (taste.keywords[it.name] ?: 0.0) > 0 } >= FRESH_MATCH_WORDS ||
            fact.allCrew().any { (taste.people[it.id] ?: 0.0) > 0 }
    }
    if (matches < FRESH_MIN_MATCHES) return emptyList()
    val norm = scoutKeywordNorm(taste, frequency)
    val sorted = usable.mapIndexed { index, pick ->
        pick to scoutScore(pick, 1.0 / (1 + index), facts[pick.key], taste, frequency, norm, appetite = false)
    }.sortedByDescending { it.second }.map { it.first }
    return scoutLimitDuplicates(sorted, facts).take(FRESH_ROW_ITEMS).map { scoutWithReason(it, facts[it.key], taste, frequency) }
}

/** At most one of a film series, three by one person, and nothing that shares nearly all its keywords with a pick above it. */
fun scoutLimitDuplicates(picks: List<ScoutPick>, facts: Map<String, ScoutTasteTitle>): List<ScoutPick> {
    val kept = ArrayList<ScoutPick>()
    val later = ArrayList<ScoutPick>()
    val series = HashMap<Int, Int>()
    val person = HashMap<Int, Int>()
    val keptWords = ArrayList<Set<String>>()
    picks.forEach { pick ->
        val fact = facts[pick.key]
        val words = fact?.usefulKeywords()?.map { it.name }?.toSet().orEmpty()
        val crew = fact?.allCrew()?.distinctBy { it.id }.orEmpty()
        val repeat = fact?.collection?.let { (series[it] ?: 0) >= MAX_SAME_SERIES } == true ||
            crew.any { (person[it.id] ?: 0) >= MAX_SAME_PERSON } ||
            (words.isNotEmpty() && keptWords.takeLast(SAME_CHECK_LAST).any { other ->
                val union = (words + other).size
                union > 0 && (words intersect other).size.toDouble() / union >= SAME_KEYWORDS
            })
        if (repeat) {
            later += pick
        } else {
            kept += pick
            keptWords += words
            fact?.collection?.let { series.merge(it, 1, Int::plus) }
            crew.forEach { person.merge(it.id, 1, Int::plus) }
        }
    }
    return kept + later
}

/**
 * Every fifth place goes to a well rated pick from outside the profile's main language or main genres,
 * never in a language the profile keeps marking Not for me.
 */
fun scoutAddStretch(picks: List<ScoutPick>, facts: Map<String, ScoutTasteTitle>, taste: ScoutTasteVector): List<ScoutPick> {
    val mainLanguage = taste.languages.maxByOrNull { it.value }?.key
    val common = picks.take(STRETCH_START + 10).flatMap { it.genres }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.take(2).map { it.key }.toSet()
    val stretches = picks.take(STRETCH_WINDOW).drop(STRETCH_START).filter { pick ->
        val language = facts[pick.key]?.language ?: pick.language
        (pick.rating ?: 0f) >= STRETCH_RATING && (pick.votes ?: 0) >= STRETCH_VOTES && language !in taste.avoidedLanguages &&
            (language != mainLanguage || pick.genres.none { it in common })
    }.iterator()
    val used = HashSet<String>()
    val out = ArrayList<ScoutPick>(picks.size)
    picks.forEach { pick ->
        if (pick.key in used) return@forEach
        if ((out.size + 1) % SCOUT_STRETCH_EVERY == 0 && stretches.hasNext()) {
            val extra = stretches.next()
            if (used.add(extra.key)) out += extra.copy(stretch = true)
        }
        if (used.add(pick.key)) out += pick
    }
    return out
}

/** The plainest true reason: a loved or often watched person, then a keyword the profile keeps coming back to. */
fun scoutWithReason(pick: ScoutPick, fact: ScoutTasteTitle?, taste: ScoutTasteVector, frequency: Map<String, Int>): ScoutPick {
    if (fact == null) return pick
    val person = fact.allCrew().distinctBy { it.id }.filter { (taste.people[it.id] ?: 0.0) > 0 }
        .maxByOrNull { taste.people.getValue(it.id) }
    val keyword = fact.usefulKeywords().map { it.name }.filter { (taste.keywords[it] ?: 0.0) > 0 && (frequency[it] ?: 0) >= 2 }
        .maxByOrNull { taste.keywords.getValue(it) * rarity(it, frequency) }
    return pick.copy(
        whyPerson = person?.name,
        whyLoved = person != null && person.id in taste.lovedPeople,
        whyKeyword = keyword
    )
}

/** A row named after a genre holds only that genre. Series ids count as their film equivalent. */
fun scoutInRowGenre(picks: List<ScoutPick>, genre: Int?): List<ScoutPick> =
    if (genre == null) picks else picks.filter { pick -> pick.genres.any { scoutCanonicalGenre(it) == genre } }

/** A short genre row is filled from the profile's other picks in that genre, best first. */
fun scoutTopUpRow(row: List<ScoutPick>, picks: List<ScoutPick>, genre: Int?, most: Int): List<ScoutPick> {
    if (row.size >= most) return row
    val have = row.mapTo(HashSet()) { it.key }
    return row + scoutInRowGenre(picks, genre).filter { have.add(it.key) }.take(most - row.size)
}

/** The first keyword most of the row's titles really carry, or none, so the row is just its genre. */
fun scoutRowKeyword(keywords: List<String>, picks: List<ScoutPick>, facts: Map<String, ScoutTasteTitle>): String? =
    keywords.firstOrNull { word ->
        picks.count { pick -> facts[pick.key]?.usefulKeywords()?.any { it.name == word } == true } * 2 > picks.size
    }

/** A taste keeps its Home position and on or off state as long as it stays the same genre and language. */
fun scoutWithRowIds(rows: List<ScoutTasteRow>): List<ScoutTasteRow> {
    val used = HashMap<String, Int>()
    return rows.map { row ->
        val base = "${row.genre ?: 0}_${row.language ?: "x"}"
        val count = used.merge(base, 1, Int::plus) ?: 1
        row.copy(id = if (count == 1) base else "${base}_$count")
    }
}

class ScoutTasteGroup(val titles: List<Pair<ScoutTasteTitle, Double>>) {
    val genre: Int? = titles.flatMap { (title, weight) -> title.genres.map { it to weight } }
        .groupBy({ it.first }, { it.second }).maxByOrNull { it.value.sum() }?.key
    val language: String? = titles.mapNotNull { (title, weight) -> title.language?.let { it to weight } }
        .groupBy({ it.first }, { it.second }).maxByOrNull { it.value.sum() }?.key
}

/** Starts from the strongest title; each next one joins the group it shares the most with, or starts a new one. */
fun scoutTasteGroups(items: List<Pair<ScoutTasteTitle, Double>>, count: Int = SCOUT_ROWS_MAX): List<ScoutTasteGroup> {
    class Group(first: ScoutTasteTitle, weight: Double) {
        val titles = arrayListOf(first to weight)
        val words = first.usefulKeywords().map { it.name }.toMutableSet()
        val genres = first.genres.toSet()
        val language = first.language
    }
    val positive = items.filter { it.second > 0 }
    val seen = HashMap<String, Int>()
    positive.forEach { (title, _) -> title.usefulKeywords().forEach { seen.merge(it.name, 1, Int::plus) } }
    val groups = ArrayList<Group>()
    positive.sortedByDescending { it.second }.forEach { (title, weight) ->
        val words = title.usefulKeywords().map { it.name }
        var best: Group? = null
        var bestScore = 0.0
        groups.forEach { group ->
            val score = words.filter { it in group.words }.sumOf { 1.0 / (seen[it] ?: 1) } +
                (if (title.genres.any { it in group.genres }) SHARED_GENRE else 0.0) +
                (if (title.language == group.language && title.language != "en") SHARED_LANGUAGE else 0.0)
            if (score > bestScore) {
                best = group
                bestScore = score
            }
        }
        val join = best?.takeIf { bestScore >= JOIN_SCORE || groups.size >= count } ?: groups.firstOrNull()?.takeIf { groups.size >= count }
        if (join != null) {
            join.titles += title to weight
            join.words += words
        } else {
            groups += Group(title, weight)
        }
    }
    return groups.filter { it.titles.size >= ROW_MIN_TITLES }.map { ScoutTasteGroup(it.titles) }
}

/** Shown on enough different days over at least two weeks and never opened: rests for three months. */
@com.squareup.moshi.JsonClass(generateAdapter = true)
data class ScoutShown(val days: Int = 1, val first: Long = 0L, val last: Long = 0L)

private const val SHOWN_DAYS = 5
private const val SHOWN_SPAN_MS = 14L * 24 * 60 * 60 * 1000
private const val RESTS_MS = 90L * 24 * 60 * 60 * 1000
private const val DAY_MS = 24L * 60 * 60 * 1000

fun scoutShownToday(old: ScoutShown?, now: Long): ScoutShown = when {
    old == null -> ScoutShown(1, now, now)
    now / DAY_MS == old.last / DAY_MS -> old
    else -> old.copy(days = old.days + 1, last = now)
}

fun scoutResting(shown: Map<String, ScoutShown>, opened: Set<String>, now: Long): Set<String> =
    shown.filter { (key, entry) ->
        key !in opened && entry.days >= SHOWN_DAYS && entry.last - entry.first >= SHOWN_SPAN_MS && now - entry.last < RESTS_MS
    }.keys
