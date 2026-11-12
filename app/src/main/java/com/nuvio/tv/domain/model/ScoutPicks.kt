package com.nuvio.tv.domain.model

import com.squareup.moshi.JsonClass
import java.text.Normalizer
import kotlin.math.max

const val SCOUT_POOL_SIZE = 200
const val SCOUT_MAX_SEEDS = 12
const val SCOUT_MAX_NEGATIVE_SEEDS = 4
const val SCOUT_MIN_SEEDS = 3
const val SCOUT_REFRESH_MS = 24L * 60 * 60 * 1000
const val SCOUT_MIN_REBUILD_GAP_MS = 60L * 60 * 1000
const val SCOUT_POOL_VERSION = 1
private const val RECENCY_DECAY = 0.97
private const val FINISHED_WEIGHT = 1.0
private const val LIKE_WEIGHT = 3.0
private const val LOVE_WEIGHT = 6.0
private const val NOT_FOR_ME_WEIGHT = -3.0
private const val RANK_DEPTH = 40.0
private const val QUALITY_WEIGHT = 0.3
private const val MIN_VOTES = 50

@JsonClass(generateAdapter = true)
data class ScoutPick(
    val tmdbId: Int,
    val tv: Boolean,
    val name: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val overview: String? = null,
    val released: String? = null,
    val rating: Float? = null,
    val votes: Int? = null,
    val genres: List<Int> = emptyList(),
    val seedName: String? = null,
    val whyPerson: String? = null,
    val whyLoved: Boolean = false,
    val whyKeyword: String? = null,
    val stretch: Boolean = false,
    val aiReason: String? = null
) {
    val key: String get() = (if (tv) "tv:" else "movie:") + tmdbId
}

@JsonClass(generateAdapter = true)
data class ScoutBecause(val seedName: String, val picks: List<ScoutPick>)

@JsonClass(generateAdapter = true)
data class ScoutPool(
    val builtAt: Long = 0L,
    val historySignature: String = "",
    val picked: List<ScoutPick> = emptyList(),
    val because: List<ScoutBecause> = emptyList(),
    val gems: List<ScoutPick> = emptyList(),
    val tasteRows: List<ScoutTasteRow> = emptyList(),
    val newForYou: List<ScoutPick> = emptyList(),
    val trendingForYou: List<ScoutPick> = emptyList(),
    val version: Int = 0
)

@JsonClass(generateAdapter = true)
data class ScoutTasteRow(
    val id: String = "",
    val genre: Int? = null,
    val language: String? = null,
    val keyword: String? = null,
    val picks: List<ScoutPick> = emptyList()
)

data class ScoutSeed(
    val contentId: String,
    val tv: Boolean,
    val name: String,
    val weight: Double,
    val finishedOrder: Int?
)

/** Rated titles first, loved before liked, then what was finished most recently. */
fun scoutSeeds(watched: List<WatchedItem>, ratings: kotlin.collections.Collection<ScoutRatingEntry>): List<ScoutSeed> {
    val latest = watched.filter { it.contentId.isNotBlank() }
        .groupBy { it.contentId }
        .map { (_, items) -> items.maxBy { it.watchedAt } }
        .sortedByDescending { it.watchedAt }
    val byId = ratings.associateBy { it.title.id }
    val finished = latest.mapIndexed { index, item ->
        val rated = byId[item.contentId]?.rating
        ScoutSeed(
            contentId = item.contentId,
            tv = item.contentType.scoutIsTv(),
            name = item.title,
            weight = (rated?.seedWeight() ?: FINISHED_WEIGHT) * Math.pow(RECENCY_DECAY, index.toDouble()),
            finishedOrder = index
        )
    }
    val finishedIds = finished.map { it.contentId }.toSet()
    val ratedOnly = ratings.filter { it.title.id !in finishedIds }
        .sortedByDescending { it.ratedAt }
        .map { ScoutSeed(it.title.id, it.title.type.scoutIsTv(), it.title.name, it.rating.seedWeight(), null) }
    val all = finished + ratedOnly
    val negative = all.filter { it.weight < 0 }.take(SCOUT_MAX_NEGATIVE_SEEDS)
    val positive = all.filter { it.weight > 0 }.sortedByDescending { it.weight }.take(SCOUT_MAX_SEEDS)
    return positive + negative
}

private fun ScoutRating.seedWeight(): Double = when (this) {
    ScoutRating.LOVE -> LOVE_WEIGHT
    ScoutRating.LIKE -> LIKE_WEIGHT
    ScoutRating.NOT_FOR_ME -> NOT_FOR_ME_WEIGHT
}

private fun String.scoutIsTv(): Boolean = trim().lowercase() in setOf("series", "tv", "show", "tvshow", "anime")

private val SCOUT_MARKS = Regex("\\p{M}+")
private val SCOUT_NOT_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{N}]+")
private val SCOUT_YEAR = Regex("\\d{4}")

fun scoutNormalizedName(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFD)
        .replace(SCOUT_MARKS, "")
        .lowercase()
        .replace(SCOUT_NOT_LETTER_OR_DIGIT, " ")
        .trim()

/** What must never be offered: anything watched, started or rated, and any left-out genre. */
data class ScoutExclusions(
    val names: Set<String> = emptySet(),
    val tmdbKeys: Set<String> = emptySet(),
    val genres: Set<Int> = emptySet()
) {
    fun blocks(pick: ScoutPick): Boolean {
        if (pick.key in tmdbKeys || pick.genres.any { it in genres }) return true
        val name = scoutNormalizedName(pick.name)
        if (name in names) return true
        val year = pick.released?.take(4)?.toIntOrNull() ?: return false
        return (year - 1..year + 1).any { "$name|$it" in names }
    }
}

/** With a year, a remake under the same name is not mistaken for the title already seen. */
fun scoutExclusionName(name: String, releaseInfo: String?): String {
    val year = releaseInfo?.let { SCOUT_YEAR.find(it)?.value }
    return scoutNormalizedName(name) + (year?.let { "|$it" } ?: "")
}

fun scoutRank(
    resultsBySeed: List<Pair<ScoutSeed, List<ScoutPick>>>,
    exclusions: ScoutExclusions,
    limit: Int = SCOUT_POOL_SIZE
): List<ScoutPick> {
    class Tally(var pick: ScoutPick, var score: Double, var bestShare: Double)

    val tally = LinkedHashMap<String, Tally>()
    resultsBySeed.forEach { (seed, picks) ->
        picks.forEachIndexed { rank, pick ->
            val share = seed.weight * max(0.05, 1.0 - rank / RANK_DEPTH)
            val entry = tally.getOrPut(pick.key) { Tally(pick, 0.0, Double.NEGATIVE_INFINITY) }
            entry.score += share
            if (share > entry.bestShare) {
                entry.bestShare = share
                if (seed.weight > 0) entry.pick = pick.copy(seedName = seed.name)
            }
        }
    }
    return tally.values
        .filter { it.score > 0 && !exclusions.blocks(it.pick) && (it.pick.votes ?: 0) >= MIN_VOTES }
        .sortedByDescending { it.score + QUALITY_WEIGHT * (it.pick.rating ?: 0f) / 10.0 }
        .take(limit)
        .map { it.pick }
}

/** The same view for a few hours, then a different slice of the pool, weighted to the best picks. */
fun scoutRotate(picks: List<ScoutPick>, count: Int, seed: Long): List<ScoutPick> {
    if (picks.size <= count) return picks
    val random = java.util.Random(seed)
    return picks.mapIndexed { index, pick ->
        pick to random.nextDouble() * (picks.size - index)
    }.sortedByDescending { it.second }.take(count).map { it.first }
}

fun scoutShouldRefresh(pool: ScoutPool?, signature: String, now: Long): Boolean {
    if (pool == null || pool.builtAt <= 0L || pool.version < SCOUT_POOL_VERSION) return true
    val age = now - pool.builtAt
    if (age >= SCOUT_REFRESH_MS || age < 0) return true
    return signature != pool.historySignature && age >= SCOUT_MIN_REBUILD_GAP_MS
}

/** Rebuilding is only worth a request when something to base picks on has changed. */
fun scoutHistorySignature(seeds: List<ScoutSeed>, leftOut: Set<Int>): String =
    (seeds.map { it.contentId + ":" + it.weight.toInt() } + leftOut.sorted().map(Int::toString)).joinToString(",")
