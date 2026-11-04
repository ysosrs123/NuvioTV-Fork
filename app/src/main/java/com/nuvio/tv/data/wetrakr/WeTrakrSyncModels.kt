package com.nuvio.tv.data.wetrakr

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class WeTrakrIds(
    val wetrakr: Long? = null,
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdb: Long? = null
) {
    val isEmpty: Boolean
        get() = wetrakr == null && imdb == null && tmdb == null && tvdb == null

    val key: String
        get() = imdb ?: tmdb?.let { "tmdb:$it" } ?: tvdb?.let { "tvdb:$it" } ?: wetrakr?.let { "wetrakr:$it" }
            ?: throw WeTrakrDecodingException()

    val contentId: String
        get() = key

    fun aliases(): Set<String> = buildSet {
        imdb?.let { add(it); add("imdb:$it") }
        tmdb?.let { add("tmdb:$it") }
        tvdb?.let { add("tvdb:$it") }
        wetrakr?.let { add("wetrakr:$it") }
    }

    fun matches(other: WeTrakrIds): Boolean =
        (wetrakr != null && wetrakr == other.wetrakr) || (imdb != null && imdb == other.imdb) ||
            (tmdb != null && tmdb == other.tmdb) || (tvdb != null && tvdb == other.tvdb)

    fun merged(other: WeTrakrIds) = WeTrakrIds(
        wetrakr ?: other.wetrakr, imdb ?: other.imdb, tmdb ?: other.tmdb, tvdb ?: other.tvdb
    )
}

@Serializable
enum class WeTrakrType { MOVIE, SHOW, EPISODE }

@Serializable
data class WeTrakrTitle(
    val type: WeTrakrType,
    val ids: WeTrakrIds,
    val title: String? = null,
    val year: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val runtimeMinutes: Int? = null
) {
    fun merged(other: WeTrakrTitle) = copy(
        ids = ids.merged(other.ids),
        title = title ?: other.title,
        year = year ?: other.year,
        poster = poster ?: other.poster,
        backdrop = backdrop ?: other.backdrop,
        runtimeMinutes = runtimeMinutes ?: other.runtimeMinutes
    )
}

@Serializable
data class WeTrakrWatchedMovie(
    val ids: WeTrakrIds,
    val lastWatchedAt: String,
    val plays: Int = 1
)

@Serializable
data class WeTrakrWatchedEpisode(
    val showId: Long? = null,
    val showIds: WeTrakrIds,
    val season: Int,
    val number: Int,
    val episodeId: Long? = null,
    val lastWatchedAt: String,
    val plays: Int = 1
) {
    val key: String
        get() = "${showId?.let { "wetrakr:$it" } ?: showIds.key}:$season:$number"
}

@Serializable
data class WeTrakrPlayback(
    val type: WeTrakrType,
    val title: WeTrakrTitle,
    val progress: Float,
    val updatedAt: String,
    val season: Int? = null,
    val number: Int? = null,
    val episodeId: Long? = null,
    val episodeTitle: String? = null,
    val runtimeSeconds: Long? = null
) {
    val key: String
        get() = "$type:${title.ids.key}:${season ?: -1}:${number ?: -1}"
}

@Serializable
enum class WeTrakrList(val wireValue: String, val journalCategory: String, val targets: List<String>) {
    PLANNING("planning", "planning", listOf("movies", "shows")),
    WATCHING("watching", "watching", listOf("shows")),
    WAITING("waiting", "waiting", listOf("shows")),
    WATCHED("watched", "watched", listOf("movies", "shows")),
    HOLD("hold", "paused", listOf("shows")),
    DROPPED("dropped", "dropped", listOf("movies", "shows"));

    fun stampKeys(): List<String> = targets.map { target ->
        if (this == HOLD) "$target.last_tracking_hold_at" else "$target.last_tracking_${wireValue}_at"
    }
}

@Serializable
data class WeTrakrPendingPlay(
    val type: WeTrakrType,
    val ids: WeTrakrIds,
    val season: Int? = null,
    val number: Int? = null,
    val atEpochMs: Long
)

@Serializable
data class WeTrakrListEntry(
    val title: WeTrakrTitle,
    val listedAt: String? = null
)

data class WeTrakrJournalEntry(
    val entryId: String,
    val category: String,
    val status: String,
    val actionAt: String,
    val type: WeTrakrType?,
    val id: Long?,
    val ids: WeTrakrIds,
    val title: String? = null,
    val showId: Long? = null,
    val season: Int? = null,
    val number: Int? = null,
    val watchedAt: String? = null
)

data class WeTrakrJournalPage(
    val entries: List<WeTrakrJournalEntry>,
    val visibleUntil: String?,
    val expired: Boolean = false
)

data class WeTrakrActivities(val stamps: Map<String, String>) {
    val journalVisibleUntil: String?
        get() = stamps["journal_visible_until"]

    fun changed(previous: Map<String, String>, keys: Collection<String>): Boolean =
        keys.any { stamps[it] != previous[it] }

    companion object {
        val WATCHED_KEYS = listOf(
            "movies.last_tracking_watched_at", "episodes.last_tracking_watched_at",
            "movies.last_tracking_removed_at", "episodes.last_tracking_removed_at"
        )
        val PLAYBACK_KEYS = listOf("movies.last_playback_at", "episodes.last_playback_at")
        val REMOVAL_KEYS = listOf("movies.last_tracking_removed_at", "shows.last_tracking_removed_at")
    }
}

@Serializable
data class WeTrakrSnapshot(
    val accountId: Long,
    val watchedMovies: List<WeTrakrWatchedMovie> = emptyList(),
    val watchedEpisodes: List<WeTrakrWatchedEpisode> = emptyList(),
    val playback: List<WeTrakrPlayback> = emptyList(),
    val lists: Map<WeTrakrList, List<WeTrakrListEntry>> = emptyMap(),
    val showIds: Map<Long, WeTrakrIds> = emptyMap(),
    val activities: Map<String, String> = emptyMap(),
    val journalMark: String? = null,
    val appliedEntryIds: Set<String> = emptySet(),
    val pendingPlays: List<WeTrakrPendingPlay> = emptyList(),
    val checkedAtEpochMs: Long? = null,
    val isInitialized: Boolean = false,
    val invalidated: Boolean = false,
    val storageRevision: String? = null
) {
    fun list(list: WeTrakrList): List<WeTrakrListEntry> = lists[list].orEmpty()
}

class WeTrakrDecodingException : Exception("WeTrakr returned an incomplete response")

internal fun weTrakrTimestamp(value: String?): Long =
    value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

internal fun weTrakrPosterUrl(path: String?, size: String = "w342"): String? = when {
    path.isNullOrBlank() -> null
    path.startsWith("http") -> path
    path.startsWith("/") -> "https://image.tmdb.org/t/p/$size$path"
    else -> null
}

internal fun WeTrakrTitle.pageUrl(): String? = ids.wetrakr?.let { id ->
    "https://wetrakr.com/${if (type == WeTrakrType.MOVIE) "movies" else "shows"}/$id"
}
