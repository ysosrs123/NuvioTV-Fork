package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nuvio.tv.BuildConfig
import kotlin.random.Random

/** Shuffling across several shows follows the TV Mix idea from Nuvio-ReSeq (GPL-3.0). */
object ScoutMixes {
    val available: Boolean get() = BuildConfig.SCOUT_ENABLED
    const val NAME_LIMIT = 60
    const val READY_PREFIX = "ready_"

    fun canHold(type: String?): Boolean =
        type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true) ||
            type.equals("anime", ignoreCase = true)
}

@Immutable
data class ScoutMixShow(
    val id: String,
    val name: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val type: String = "series",
    val imdbId: String? = null,
    val addonBaseUrl: String? = null,
    val genres: List<String> = emptyList(),
    val logo: String? = null,
    val logoChecked: Boolean = false
) {
    fun sameShow(other: ScoutMixShow): Boolean = id == other.id ||
        (!imdbId.isNullOrBlank() && (imdbId == other.imdbId || imdbId == other.id)) ||
        (!other.imdbId.isNullOrBlank() && id == other.imdbId)
}

@Immutable
data class ScoutMix(
    val id: String,
    val name: String,
    val shows: List<ScoutMixShow> = emptyList(),
    val unwatchedOnly: Boolean = true,
    val keepPlaying: Boolean = true,
    val autoName: Boolean = true,
    /** What a ready-made mix is made from; it is rebuilt from this each week. Null for a mix the viewer made. */
    val recipe: String? = null,
    val builtAt: Long = 0L,
    /** For a mix of chosen episodes: the only episodes each show (by id) may offer, as season to episode. */
    val only: Map<String, Set<Pair<Int, Int>>> = emptyMap(),
    /** The ready-made mix this one was saved from, so Scout can show it as saved. */
    val savedFrom: String? = null,
    /** Which version of the rules filled this mix; a kept one from older rules is filled again. */
    val rules: Int = 0
) {
    val readyMade: Boolean get() = id.startsWith(ScoutMixes.READY_PREFIX)

    fun holds(show: ScoutMixShow): Boolean = shows.any { it.sameShow(show) }
    fun adding(show: ScoutMixShow): ScoutMix = if (holds(show)) this else copy(shows = shows + show)
    fun removing(show: ScoutMixShow): ScoutMix = copy(shows = shows.filterNot { it.sameShow(show) })
}

/** Which show and episode, across a mix. */
typealias ScoutMixEpisodeKey = Pair<String, String>

/** What a show offers a pick, once its metadata has been looked up. */
class ScoutMixEpisodes(
    val meta: Meta,
    val watched: Set<Pair<Int, Int>> = emptySet(),
    val progress: Map<Pair<Int, Int>, WatchProgress> = emptyMap()
)

/** A show in a mix of chosen episodes offers only those episodes. Null keeps them all. */
internal fun ScoutMixEpisodes.keptTo(allowed: Set<Pair<Int, Int>>?): ScoutMixEpisodes {
    if (allowed == null) return this
    val kept = meta.videos.filter { video ->
        val season = video.season ?: return@filter false
        val episode = video.episode ?: return@filter false
        (season to episode) in allowed
    }
    return ScoutMixEpisodes(meta.copy(videos = kept), watched, progress)
}

sealed interface ScoutMixPick {
    data class Episode(val show: ScoutMixShow, val meta: Meta, val video: Video) : ScoutMixPick {
        val key: ScoutMixEpisodeKey get() = meta.id to video.id
    }
    data object NoShows : ScoutMixPick
    data object AllSeen : ScoutMixPick
    data object Unavailable : ScoutMixPick
}

/**
 * Shows are tried in a shuffled order, so each is as likely as any other whatever its length, and only as many are looked
 * up as the pick needs. The show just played goes last. Episodes already played stay out until nothing else is left.
 * [resume], an episode stopped partway, comes first when the page opens.
 */
internal suspend fun pickScoutMixEpisode(
    shows: List<ScoutMixShow>,
    unwatchedOnly: Boolean,
    played: Set<ScoutMixEpisodeKey> = emptySet(),
    lastPlayed: ScoutMixEpisodeKey? = null,
    current: ScoutMixEpisodeKey? = null,
    lastShowId: String? = null,
    random: Random = Random.Default,
    resume: Pair<ScoutMixShow, WatchProgress>? = null,
    episodes: suspend (ScoutMixShow) -> ScoutMixEpisodes?
): ScoutMixPick {
    if (shows.isEmpty()) return ScoutMixPick.NoShows
    val order = shows.shuffled(random).sortedBy { it.id == lastShowId }
    val loaded = HashMap<String, ScoutMixEpisodes?>()
    if (resume != null && played.isEmpty() && current == null) {
        val (show, partway) = resume
        val offer = episodes(show).also { loaded[show.id] = it }
        val video = offer?.meta?.videos?.firstOrNull { it.season == partway.season && it.episode == partway.episode }
        if (offer != null && video != null) return ScoutMixPick.Episode(show, offer.meta, video)
    }
    val passes = if (unwatchedOnly) {
        listOf(played + setOfNotNull(current), played)
    } else {
        listOf(
            played + setOfNotNull(current),
            setOfNotNull(current, lastPlayed),
            setOfNotNull(lastPlayed),
            emptySet()
        )
    }
    for (excluded in passes.distinct()) {
        for (show in order) {
            val offer = if (show.id in loaded) loaded[show.id] else episodes(show).also { loaded[show.id] = it }
            val meta = offer?.meta ?: continue
            val video = RandomEpisodePicker(
                meta.id,
                meta.videos.filterNot { (meta.id to it.id) in excluded },
                offer.watched,
                offer.progress,
                random
            ).pick(includeWatched = !unwatchedOnly)
            if (video != null) return ScoutMixPick.Episode(show, meta, video)
        }
    }
    return if (unwatchedOnly && loaded.values.any { it != null }) ScoutMixPick.AllSeen else ScoutMixPick.Unavailable
}

/** How a suggested name reads in the viewer's language. */
class ScoutMixNameWords(
    val oneShow: (String) -> String,
    val genre: (String) -> String,
    val twoShows: (String, String) -> String,
    val moreShows: (String, String, Int) -> String
)

/**
 * A genre every show shares reads best, since it still fits as shows are added; otherwise the shows' own names.
 * A name another mix already uses gets a number.
 */
internal fun suggestScoutMixName(
    shows: List<ScoutMixShow>,
    taken: List<String>,
    words: ScoutMixNameWords
): String {
    val sharedGenre = if (shows.size < 2) null else shows.first().genres.firstOrNull { genre ->
        genre.isNotBlank() && shows.all { show -> show.genres.any { it.equals(genre, ignoreCase = true) } }
    }
    val base = when {
        shows.isEmpty() -> ""
        sharedGenre != null -> words.genre(sharedGenre)
        shows.size == 1 -> words.oneShow(shows[0].name)
        shows.size == 2 -> words.twoShows(shows[0].name, shows[1].name)
        else -> words.moreShows(shows[0].name, shows[1].name, shows.size - 2)
    }.take(ScoutMixes.NAME_LIMIT)
    val names = taken.map { it.lowercase() }.toSet()
    if (base.lowercase() !in names) return base
    return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it.lowercase() !in names }
}

/** A show is looked up for its logo once only, whether or not it turned out to have one. */
internal fun ScoutMixShow.needsLogoLookup(): Boolean = !logoChecked

/** [found] maps a show's id to its logo, blank when the show has none. */
internal fun withFoundLogos(shows: List<ScoutMixShow>, found: Map<String, String>): List<ScoutMixShow> = shows.map { show ->
    val logo = found[show.id]
    if (show.needsLogoLookup() && logo != null) show.copy(logo = logo.ifBlank { show.logo }, logoChecked = true) else show
}

/** What a mix shows on Home: the card's words and pictures, and the hero's. */
@Immutable
data class ScoutMixTile(
    val kind: String,
    val shows: String,
    val about: String,
    val genre: String?,
    val logos: List<String>,
    val deck: String?,
    val deckMirrored: String?
)

/** One poster dealt on a mix card, as fractions of the card: where it sits, its size and its tilt. */
data class ScoutMixDeckCard(val left: Float, val top: Float, val width: Float, val height: Float, val degrees: Float)

private const val DECK_ASPECT = 16f / 9f
private const val DECK_MOST = 4

/** Up to four posters fanned at the card's far edge, the first one on top. Listed back to front. */
internal fun scoutMixDeck(count: Int, mirrored: Boolean = false): List<ScoutMixDeckCard> {
    val n = count.coerceAtMost(DECK_MOST)
    if (n <= 0) return emptyList()
    val height = .8f
    val width = height * 2f / 3f / DECK_ASPECT
    val step = if (n > 1) minOf((.47f - width) / (n - 1), width * .62f) else 0f
    val start = 1f - .04f - width - step * (n - 1)
    val tilts = when (n) {
        1 -> listOf(0f)
        2 -> listOf(-4f, 3f)
        3 -> listOf(-6f, -1f, 4f)
        else -> listOf(-7f, -3f, 1f, 5f)
    }
    val cards = (0 until n).map { i ->
        val left = start + step * i
        ScoutMixDeckCard(
            left = if (mirrored) 1f - left - width else left,
            top = .1f + kotlin.math.abs(tilts[i]) * .003f,
            width = width,
            height = height,
            degrees = if (mirrored) -tilts[i] else tilts[i]
        )
    }
    return cards.drop(1).reversed() + cards.first()
}

/** A slanted strip of the hero: x at the top left, top right, bottom right and bottom left, as fractions of the width. */
data class ScoutMixHeroPanel(val topLeft: Float, val topRight: Float, val bottomRight: Float, val bottomLeft: Float)

private const val HERO_MOST = 3
private const val HERO_SLANT = .05f

/** Up to three shows side by side across the whole hero, joined on a slant. */
internal fun scoutMixHeroPanels(count: Int): List<ScoutMixHeroPanel> {
    val n = count.coerceAtMost(HERO_MOST)
    if (n <= 0) return emptyList()
    val slant = if (n > 1) HERO_SLANT else 0f
    return (0 until n).map { i ->
        val a = i.toFloat() / n
        val b = (i + 1).toFloat() / n
        val last = i == n - 1
        ScoutMixHeroPanel(
            topLeft = if (i == 0) 0f else a + slant,
            topRight = if (last) 1f else b + slant,
            bottomRight = if (last) 1f else b - slant,
            bottomLeft = if (i == 0) 0f else a - slant
        )
    }
}

/** Which part of the picture each show's artwork fills, as fractions of the whole: left, top, right, bottom. */
data class ScoutMixArtCell(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal fun scoutMixArtCells(count: Int): List<ScoutMixArtCell> = when {
    count <= 0 -> emptyList()
    count == 1 -> listOf(ScoutMixArtCell(0f, 0f, 1f, 1f))
    count == 2 -> listOf(ScoutMixArtCell(0f, 0f, .5f, 1f), ScoutMixArtCell(.5f, 0f, 1f, 1f))
    count == 3 -> listOf(
        ScoutMixArtCell(0f, 0f, .5f, 1f),
        ScoutMixArtCell(.5f, 0f, 1f, .5f),
        ScoutMixArtCell(.5f, .5f, 1f, 1f)
    )
    else -> listOf(
        ScoutMixArtCell(0f, 0f, .5f, .5f),
        ScoutMixArtCell(.5f, 0f, 1f, .5f),
        ScoutMixArtCell(0f, .5f, .5f, 1f),
        ScoutMixArtCell(.5f, .5f, 1f, 1f)
    )
}
