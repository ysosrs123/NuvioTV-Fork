package com.nuvio.tv.domain.model

import com.nuvio.tv.BuildConfig

object ScoutFeature {
    val available: Boolean get() = BuildConfig.SCOUT_ENABLED
}

enum class ScoutRating { NOT_FOR_ME, LIKE, LOVE }

data class ScoutTitle(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null
)

data class ScoutRatingEntry(
    val title: ScoutTitle,
    val rating: ScoutRating,
    val ratedAt: Long
)

data class ScoutSettings(
    val ratingsEnabled: Boolean = true,
    val askAfterFilm: Boolean = true,
    val pickedRow: Boolean = true,
    val genreRows: Boolean = true,
    val becauseRow: Boolean = true,
    val gemsRow: Boolean = true,
    val tilesRow: Boolean = false,
    val leaveOutGenres: Set<Int> = emptySet(),
    val savedSlices: List<String> = emptyList(),
    val tasteRow: String = "titles"
)

data class ScoutProfile(
    val profileId: Int = 0,
    val settings: ScoutSettings = ScoutSettings(),
    val ratings: Map<String, ScoutRatingEntry> = emptyMap(),
    val asked: Set<String> = emptySet()
) {
    fun rating(contentId: String): ScoutRating? = ratings[contentId]?.rating
}

/** A question about one title, waiting for the player to close. */
data class ScoutAskTarget(
    val title: ScoutTitle,
    val askKey: String
)

/**
 * The film or season just finished is worth asking about only once per title or season, never
 * when already rated, and never with the switches off.
 */
fun scoutShouldAsk(profile: ScoutProfile, target: ScoutAskTarget): Boolean =
    ScoutFeature.available &&
        profile.settings.ratingsEnabled &&
        profile.settings.askAfterFilm &&
        target.title.id.isNotBlank() &&
        profile.rating(target.title.id) == null &&
        target.askKey !in profile.asked

/** Null when the player closing here is not the end of a film or of a season. */
fun scoutAskTargetFor(
    completed: Boolean,
    contentId: String?,
    contentType: String?,
    name: String?,
    poster: String?,
    season: Int?,
    nextEpisodeSeason: Int?
): ScoutAskTarget? {
    if (!completed || contentId.isNullOrBlank() || name.isNullOrBlank()) return null
    val type = contentType?.trim()?.lowercase().orEmpty()
    val title = ScoutTitle(contentId, type, name, poster)
    return when (type) {
        "movie", "film" -> ScoutAskTarget(title, contentId)
        "series", "tv", "show", "tvshow", "anime" ->
            if (season != null && season != nextEpisodeSeason) ScoutAskTarget(title, "$contentId:$season") else null
        else -> null
    }
}

private const val FIELD = '\u001F'

internal fun encodeScoutRating(entry: ScoutRatingEntry): String =
    listOf(entry.rating.name, entry.ratedAt.toString(), entry.title.type, entry.title.name, entry.title.poster.orEmpty())
        .joinToString(FIELD.toString())

internal fun decodeScoutRating(id: String, raw: String): ScoutRatingEntry? {
    val parts = raw.split(FIELD)
    if (parts.size < 5) return null
    val rating = runCatching { ScoutRating.valueOf(parts[0]) }.getOrNull() ?: return null
    return ScoutRatingEntry(
        title = ScoutTitle(id, parts[2], parts[3], parts[4].ifBlank { null }),
        rating = rating,
        ratedAt = parts[1].toLongOrNull() ?: 0L
    )
}
