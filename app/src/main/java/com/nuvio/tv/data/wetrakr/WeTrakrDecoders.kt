package com.nuvio.tv.data.wetrakr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal fun weTrakrJson(body: String): JsonElement =
    runCatching { Json.parseToJsonElement(body) }.getOrElse { throw WeTrakrDecodingException() }

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonObject.text(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
    (this[name] as? JsonPrimitive)?.takeIf { it.isString || it.longOrNull != null }?.contentOrNull?.takeIf(String::isNotBlank)
}

internal fun JsonObject.long(vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
    (this[name] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
}

internal fun JsonObject.int(vararg names: String): Int? = long(*names)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

internal fun JsonObject.double(name: String): Double? =
    (this[name] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }?.takeIf { it.isFinite() }

internal fun JsonObject.flag(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

internal fun JsonElement.array(): List<JsonObject> =
    (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: throw WeTrakrDecodingException()

internal fun decodeWeTrakrIds(element: JsonElement?, wetrakrId: Long? = null): WeTrakrIds {
    val ids = element.obj()
    return WeTrakrIds(
        wetrakr = wetrakrId?.takeIf { it > 0L },
        imdb = ids?.text("imdb")?.takeIf { it.matches(IMDB_ID) },
        tmdb = ids?.long("tmdb")?.takeIf { it > 0L },
        tvdb = ids?.long("tvdb")?.takeIf { it > 0L }
    )
}

internal fun decodeWeTrakrType(value: String?): WeTrakrType? = when (value?.lowercase()) {
    "movie" -> WeTrakrType.MOVIE
    "show" -> WeTrakrType.SHOW
    "episode" -> WeTrakrType.EPISODE
    else -> null
}

internal fun decodeWeTrakrTitle(element: JsonObject, fallbackType: WeTrakrType? = null): WeTrakrTitle? {
    val type = decodeWeTrakrType(element.text("type"))?.takeIf { it != WeTrakrType.EPISODE } ?: fallbackType ?: return null
    val ids = decodeWeTrakrIds(element["ids"], element.long("id"))
    if (ids.isEmpty) return null
    val date = element.text("release_date", "first_air_date")
    return WeTrakrTitle(
        type = type,
        ids = ids,
        title = element.text("title", "name"),
        year = date?.take(4)?.toIntOrNull()?.takeIf { it in 1870..2200 },
        poster = weTrakrPosterUrl(element.text("poster_path")),
        backdrop = weTrakrPosterUrl(element.text("backdrop_path"), "w1280"),
        runtimeMinutes = element.int("runtime")?.takeIf { it in 1..1_000 }
    )
}

internal fun decodeWeTrakrUser(body: String): WeTrakrUser {
    val root = weTrakrJson(body).obj() ?: throw WeTrakrDecodingException()
    val user = root.obj("user") ?: root
    val id = user.long("id")?.takeIf { it > 0L } ?: throw WeTrakrDecodingException()
    val info = user.obj("info")
    return WeTrakrUser(
        id = id,
        username = info?.text("username") ?: user.text("username"),
        displayName = info?.text("display_name") ?: user.text("display_name", "name"),
        plan = user.text("plan")
    )
}

internal fun decodeWeTrakrActivities(body: String): WeTrakrActivities {
    val root = weTrakrJson(body).obj() ?: throw WeTrakrDecodingException()
    val stamps = buildMap {
        root.forEach { (section, value) ->
            when (value) {
                is JsonPrimitive -> value.contentOrNull?.let { put(section, it) }
                is JsonObject -> value.forEach { (field, stamp) ->
                    (stamp as? JsonPrimitive)?.contentOrNull?.let { put("$section.$field", it) }
                }
                else -> Unit
            }
        }
    }
    return WeTrakrActivities(stamps)
}

internal fun decodeWeTrakrWatchedMovies(rows: List<JsonObject>): List<WeTrakrWatchedMovie> = rows.mapNotNull { row ->
    val ids = decodeWeTrakrIds(row["ids"], row.long("id")).takeUnless { it.isEmpty } ?: return@mapNotNull null
    WeTrakrWatchedMovie(
        ids = ids,
        lastWatchedAt = row.text("last_watched_at", "watched_at", "tracked_at") ?: return@mapNotNull null,
        plays = row.int("plays", "watched_count")?.coerceAtLeast(1) ?: 1
    )
}

internal fun decodeWeTrakrWatchedEpisodes(rows: List<JsonObject>): List<WeTrakrWatchedEpisode> = rows.mapNotNull { row ->
    val showIds = decodeWeTrakrIds(row["show_ids"], row.long("show_id"))
        .takeUnless { it.isEmpty } ?: row.obj("show")?.let { decodeWeTrakrIds(it["ids"], it.long("id")) }
        ?: return@mapNotNull null
    WeTrakrWatchedEpisode(
        showId = row.long("show_id") ?: row.obj("show")?.long("id"),
        showIds = showIds,
        season = row.int("season_number")?.takeIf { it >= 0 } ?: return@mapNotNull null,
        number = row.int("number")?.takeIf { it >= 1 } ?: return@mapNotNull null,
        episodeId = row.long("id"),
        lastWatchedAt = row.text("last_watched_at", "watched_at", "tracked_at") ?: return@mapNotNull null,
        plays = row.int("plays", "watched_count")?.coerceAtLeast(1) ?: 1
    )
}

internal fun decodeWeTrakrListEntries(rows: List<JsonObject>, fallbackType: WeTrakrType): List<WeTrakrListEntry> =
    rows.mapNotNull { row ->
        val title = decodeWeTrakrTitle(row, fallbackType) ?: return@mapNotNull null
        val tracked = row.obj("interactions")?.obj("user")?.obj("tracking")?.obj("last")
        WeTrakrListEntry(
            title = title,
            listedAt = row.text("tracked_at", "watched_at", "last_watched_at", "added_at")
                ?: tracked?.text("tracked_at", "created_at")
        )
    }

/** Locked lists are left out; the ones a plan no longer covers come back on their own when it does. */
internal fun decodeWeTrakrCustomLists(body: String): List<WeTrakrCustomList> =
    weTrakrJson(body).array().mapNotNull { row ->
        val id = row.long("id") ?: return@mapNotNull null
        val name = row.text("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        if (row["locked"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) return@mapNotNull null
        WeTrakrCustomList(
            id = id,
            name = name,
            description = row.text("description"),
            isPrivate = row.text("privacy") == "private",
            updatedAt = row.text("list_updated_at")
        )
    }

/** A list can also hold seasons, episodes and people; only films and shows can open as a title here. */
internal fun decodeWeTrakrCustomListItems(rows: List<JsonObject>): List<WeTrakrListEntry> =
    rows.mapNotNull { row ->
        val type = decodeWeTrakrType(row.text("type"))
        if (type != WeTrakrType.MOVIE && type != WeTrakrType.SHOW) return@mapNotNull null
        val title = decodeWeTrakrTitle(row, type) ?: return@mapNotNull null
        WeTrakrListEntry(title = title, listedAt = row.text("list_item_created_at"))
    }

internal fun decodeWeTrakrPlayback(rows: List<JsonObject>, type: WeTrakrType): List<WeTrakrPlayback> = rows.mapNotNull { row ->
    val playback = row.obj("playback") ?: return@mapNotNull null
    val progress = playback.double("progress_percent")?.toFloat()?.coerceIn(0f, 100f) ?: return@mapNotNull null
    val updatedAt = playback.text("tracked_at") ?: return@mapNotNull null
    val runtime = playback.long("runtime_seconds")?.takeIf { it > 0L }
    if (type == WeTrakrType.MOVIE) {
        val title = decodeWeTrakrTitle(row, WeTrakrType.MOVIE) ?: return@mapNotNull null
        WeTrakrPlayback(WeTrakrType.MOVIE, title, progress, updatedAt, runtimeSeconds = runtime)
    } else {
        val show = row.obj("show")?.let { decodeWeTrakrTitle(it, WeTrakrType.SHOW) } ?: return@mapNotNull null
        WeTrakrPlayback(
            type = WeTrakrType.EPISODE,
            title = show,
            progress = progress,
            updatedAt = updatedAt,
            season = row.int("season_number")?.takeIf { it >= 0 } ?: return@mapNotNull null,
            number = row.int("number")?.takeIf { it >= 1 } ?: return@mapNotNull null,
            episodeId = row.long("id"),
            episodeTitle = row.text("title"),
            runtimeSeconds = runtime
        )
    }
}

internal fun decodeWeTrakrJournal(body: String): WeTrakrJournalPage {
    val root = weTrakrJson(body).obj() ?: throw WeTrakrDecodingException()
    val rows = root["journal"]?.array() ?: throw WeTrakrDecodingException()
    return WeTrakrJournalPage(
        entries = rows.mapNotNull { row ->
            val type = decodeWeTrakrType(row.text("type"))
            WeTrakrJournalEntry(
                entryId = row.text("entry_id") ?: return@mapNotNull null,
                category = row.text("category")?.lowercase() ?: return@mapNotNull null,
                status = row.text("status")?.lowercase() ?: return@mapNotNull null,
                actionAt = row.text("action_at") ?: return@mapNotNull null,
                type = type,
                id = row.long("id"),
                ids = decodeWeTrakrIds(row["ids"], row.long("id").takeIf { type != WeTrakrType.EPISODE }),
                title = row.text("title", "name"),
                showId = row.long("media_id"),
                season = row.int("season_number"),
                number = row.int("number").takeIf { type == WeTrakrType.EPISODE },
                watchedAt = row.text("watched_at")
            )
        },
        visibleUntil = root.text("visible_until")
    )
}

internal fun decodeWeTrakrSimilar(body: String): List<WeTrakrTitle> =
    weTrakrJson(body).array().mapNotNull { decodeWeTrakrTitle(it) }

private val IMDB_ID = Regex("tt\\d{5,12}")
