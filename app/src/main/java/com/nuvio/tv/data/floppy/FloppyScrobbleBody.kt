package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal const val FLOPPY_COMPLETED_PERCENT = 80.0
internal const val FLOPPY_MIN_STOP_PERCENT = 1.0

/** Null when Floppy could not match the title: it only matches by id, never by name. */
internal fun floppyScrobbleBody(
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent,
    eventId: () -> String = { UUID.randomUUID().toString() }
): JsonObject? {
    val percent = event.progressPercent.takeIf(Double::isFinite)?.coerceIn(0.0, 100.0) ?: return null
    if (action == TrackingScrobbleAction.STOP && percent < FLOPPY_MIN_STOP_PERCENT) return null
    val duration = event.durationSeconds?.takeIf { it > 0L }
    return buildJsonObject {
        put("action", action.wireValue)
        if (!putMedia(event.media)) return null
        put("progress_percent", (percent * 100.0).roundToLong() / 100.0)
        if (duration != null) {
            put("duration_seconds", duration)
            put("position_seconds", (duration * percent / 100.0).roundToLong())
        }
        if (action == TrackingScrobbleAction.STOP) {
            put("completed", percent >= FLOPPY_COMPLETED_PERCENT)
            put("client_event_id", "arc-stop-${eventId()}")
        }
    }
}

internal fun floppyManualMarkBody(item: TrackingHistoryItem): JsonObject? = buildJsonObject {
    put("action", TrackingScrobbleAction.STOP.wireValue)
    if (!putMedia(item.media)) return null
    put("completed", true)
    put("manual", true)
    val episode = item.media.episode?.let { "s${it.season}e${it.number}" }.orEmpty()
    val watchedAt = item.watchedAtEpochMs
    watchedAt?.let { put("played_at", Instant.ofEpochMilli(it).toString()) }
    put("client_event_id", "arc-mark-${item.media.stableKey}$episode${watchedAt?.let { "-$it" }.orEmpty()}".take(200))
}

/** A new id for each unmark: Floppy answers a repeated id from its first result, so mark, unmark, mark, unmark must not share one. */
internal fun floppyUnwatchBody(
    media: TrackingMediaReference,
    eventId: () -> String = { UUID.randomUUID().toString() }
): JsonObject? = buildJsonObject {
    put("action", "unwatch")
    if (!putMedia(media)) return null
    put("client_event_id", "arc-unmark-${eventId()}")
}

internal fun floppyErrorDetail(body: String): String? {
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
    return (root["detail"] as? JsonPrimitive)?.contentOrNull
}

private fun JsonObjectBuilder.putMedia(media: TrackingMediaReference): Boolean {
    val ids = media.ids
    val imdb = ids.imdb?.takeIf { IMDB_ID.matches(it) }
    val tmdb = ids.tmdb?.takeIf { it > 0L }
    val tvdb = ids.tvdb?.toLongOrNull()?.takeIf { it > 0L }
    if (imdb == null && tmdb == null && tvdb == null) return false
    val isMovie = media.kind == TrackingMediaKind.MOVIE
    val episode = media.episode
    if (!isMovie) {
        if (episode == null || episode.usesTvdbSeasonMapping) return false
        val season = episode.season ?: return false
        if (season < 0 || episode.number < 1) return false
    }
    put("media_type", if (isMovie) "movie" else "episode")
    put("ids", buildJsonObject {
        imdb?.let { put("imdb", it) }
        tmdb?.let { put("tmdb", it.toString()) }
        tvdb?.let { put("tvdb", it.toString()) }
        ids.anidb?.takeIf { it > 0L && !isMovie }?.let { put("anidb", it.toString()) }
    })
    if (isMovie) {
        media.title?.let { put("title", it) }
    } else if (episode != null) {
        media.title?.let { put("series_title", it) }
        episode.title?.let { put("title", it) }
        put("season_number", requireNotNull(episode.season))
        put("episode_number", episode.number)
    }
    return true
}

data class FloppyPlay(val id: Long, val endedAtMs: Long?)

/** Floppy's reply is a plain list or a page with `results`; a row without a usable end time can't be matched. */
internal fun parseFloppyPlays(body: String): List<FloppyPlay> {
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
    val rows = (root as? JsonArray) ?: ((root as? JsonObject)?.get("results") as? JsonArray) ?: return emptyList()
    return rows.mapNotNull { row ->
        val fields = row as? JsonObject ?: return@mapNotNull null
        val id = (fields["consumption_id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        val ended = (fields["end_date"] as? JsonPrimitive)?.contentOrNull
        FloppyPlay(id, ended?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() })
    }
}

/** Floppy rounds a playback stop's end time down to the minute, so a stamp matches within two minutes. */
internal fun floppyPlaysMadeHere(plays: List<FloppyPlay>, stamps: List<Long>): List<FloppyPlay> = plays.filter { play ->
    val ended = play.endedAtMs ?: return@filter false
    stamps.any { abs(it - ended) <= FLOPPY_PLAY_MATCH_MS }
}

private const val FLOPPY_PLAY_MATCH_MS = 120_000L

private val IMDB_ID = Regex("tt[0-9]+")
