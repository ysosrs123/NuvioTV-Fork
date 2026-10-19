package com.nuvio.tv.core.calendar

import com.nuvio.tv.data.remote.dto.TvMazeEpisodeDto
import com.squareup.moshi.JsonReader
import java.time.Duration
import java.time.Instant
import okio.BufferedSource

/** How often TVmaze's full schedule is read again; the file itself is cached for a day. */
val CALENDAR_SCHEDULE_REFRESH: Duration = Duration.ofHours(12)

/** How long a schedule read that failed waits before it is tried again. */
private val SCHEDULE_RETRY: Duration = Duration.ofHours(1)

fun calendarScheduleDue(snapshot: CalendarSnapshot, now: Instant): Boolean {
    val nowMs = now.toEpochMilli()
    return nowMs - snapshot.scheduleFetchedAtMs >= CALENDAR_SCHEDULE_REFRESH.toMillis() &&
        nowMs - snapshot.scheduleTriedAtMs >= SCHEDULE_RETRY.toMillis()
}

/** Episodes TVmaze gave earlier that aired within this long are kept when the schedule no longer lists them. */
private val RECENTLY_AIRED: Duration = Duration.ofDays(30)

/** One followed show found in TVmaze's full schedule, with its dated future episodes. */
data class TvMazeScheduledShow(
    val id: Int,
    val ids: CalendarIds,
    val name: String? = null,
    val status: CalendarShowStatus = CalendarShowStatus.UNKNOWN,
    val network: String? = null,
    val poster: String? = null,
    val entries: List<CalendarEntry> = emptyList()
)

/** The followed shows TVmaze's full schedule lists, found by IMDb or TVDB id. */
class TvMazeSchedule(val shows: List<TvMazeScheduledShow>) {
    private val byAlias: Map<String, TvMazeScheduledShow> = buildMap {
        shows.forEach { show -> calendarTvMazeAliases(show.ids).forEach { putIfAbsent(it, show) } }
    }

    fun find(ids: CalendarIds): TvMazeScheduledShow? = calendarTvMazeAliases(ids).firstNotNullOfOrNull(byAlias::get)
}

/** The ids a show can be matched on in TVmaze. */
fun calendarTvMazeAliases(ids: CalendarIds): List<String> =
    listOfNotNull(ids.imdb?.let { "imdb:$it" }, ids.tvdb?.let { "tvdb:$it" })

/**
 * Applies TVmaze's full schedule to the followed shows. A listed show takes its episodes from the file. A show that
 * is not listed has nothing dated ahead: it is marked as checked and needs no call of its own until the file lists it
 * again; while its TVmaze id is not known yet it is due for the one lookup that tells whether TVmaze has it at all. A
 * show TVmaze is known not to have, or one without an IMDb or TVDB id, is left to the per-title refresh.
 */
fun applyTvMazeSchedule(
    records: List<CalendarTitleRecord>,
    schedule: TvMazeSchedule,
    knownTvMazeIds: Map<String, CalendarTvMazeId>,
    now: Instant
): List<CalendarTitleRecord> = records.map { record ->
    if (record.type != CalendarTitleType.SERIES) return@map record
    val aliases = calendarTvMazeAliases(record.ids)
    if (aliases.isEmpty()) return@map record
    val listed = schedule.find(record.ids)
    if (listed == null) {
        val unknownToTvMaze = record.tvmazeId == null && aliases.all { knownTvMazeIds[it]?.let { known -> known.id == null } == true }
        if (unknownToTvMaze) return@map record
        val mapped = record.tvmazeId != null || aliases.any { knownTvMazeIds[it]?.id != null }
        return@map record.copy(
            scheduleCheckedAtMs = now.toEpochMilli(),
            nextRefreshAtMs = if (mapped) record.nextRefreshAtMs else minOf(record.nextRefreshAtMs, now.toEpochMilli())
        )
            .withSource(CalendarSource.TVMAZE, record.recentlyAired(now), now)
            .withoutSources(CalendarSource.TRAKT_NEXT)
    }
    val entries = (listed.entries + record.recentlyAired(now)).distinctBy(CalendarEntry::slot)
    val updated = record.copy(
        tvmazeId = listed.id,
        ids = record.ids.merged(listed.ids),
        name = record.name ?: listed.name,
        poster = record.poster ?: listed.poster,
        network = listed.network ?: record.network,
        status = listed.status,
        scheduleCheckedAtMs = now.toEpochMilli()
    ).withSource(CalendarSource.TVMAZE, entries, now).withoutSources(CalendarSource.TRAKT_NEXT)
    if (updated.wantsFinales(now)) updated.copy(nextRefreshAtMs = minOf(updated.nextRefreshAtMs, now.toEpochMilli())) else updated
}

/** The aliases TVmaze ids are known by after a schedule pass, so later passes and lookups can use them. */
fun TvMazeSchedule.learnedTvMazeIds(now: Instant): Map<String, CalendarTvMazeId> = buildMap {
    shows.forEach { show -> calendarTvMazeAliases(show.ids).forEach { put(it, CalendarTvMazeId(show.id, now.toEpochMilli())) } }
}

/** A show with a release within the finale look-ahead whose TMDB finale marks were last asked a day or more ago. */
fun CalendarTitleRecord.wantsFinales(now: Instant): Boolean {
    val soon = entries.any { entry ->
        entry.kind == CalendarEntryKind.EPISODE &&
            entry.sortInstant()?.let { !it.isBefore(now) && it.isBefore(now.plus(CALENDAR_FINALE_LOOKAHEAD)) } == true
    }
    return soon && now.toEpochMilli() - finalesCheckedAtMs >= Duration.ofDays(1).toMillis()
}

val CALENDAR_FINALE_LOOKAHEAD: Duration = Duration.ofDays(60)

private fun CalendarTitleRecord.recentlyAired(now: Instant): List<CalendarEntry> =
    sources[CalendarSource.TVMAZE].orEmpty().filter { entry ->
        entry.isOut(now) && entry.sortInstant()?.isAfter(now.minus(RECENTLY_AIRED)) == true
    }

/**
 * Reads `/schedule/full` one episode at a time and keeps only the shows in [wanted] (aliases as
 * [calendarTvMazeAliases] makes them). Nothing else is kept, so the file is never held in memory as a whole.
 */
fun parseTvMazeSchedule(source: BufferedSource, wanted: Set<String>): TvMazeSchedule {
    val shows = LinkedHashMap<Int, TvMazeScheduledShow>()
    val reader = JsonReader.of(source)
    reader.beginArray()
    while (reader.hasNext()) {
        var id: Int? = null
        var name: String? = null
        var season: Int? = null
        var number: Int? = null
        var type: String? = null
        var airdate: String? = null
        var airtime: String? = null
        var airstamp: String? = null
        var show: TvMazeScheduledShow? = null
        reader.forEachField { field ->
            when (field) {
                "id" -> id = intOrNull()
                "name" -> name = stringOrNull()
                "season" -> season = intOrNull()
                "number" -> number = intOrNull()
                "type" -> type = stringOrNull()
                "airdate" -> airdate = stringOrNull()
                "airtime" -> airtime = stringOrNull()
                "airstamp" -> airstamp = stringOrNull()
                "_embedded" -> forEachField { embedded -> if (embedded == "show") show = readShow() else skipValue() }
                else -> skipValue()
            }
        }
        val found = show ?: continue
        if (calendarTvMazeAliases(found.ids).none { it in wanted }) continue
        val episode = TvMazeEpisodeDto(
            id = id, name = name, season = season, number = number, type = type,
            airdate = airdate, airtime = airtime, airstamp = airstamp
        )
        val entry = tvMazeEpisodeEntry(episode) ?: continue
        val known = shows[found.id] ?: found
        shows[found.id] = known.copy(entries = known.entries + entry)
    }
    reader.endArray()
    return TvMazeSchedule(shows.values.map { show -> show.copy(entries = show.entries.distinctBy(CalendarEntry::slot)) })
}

private fun JsonReader.readShow(): TvMazeScheduledShow? {
    var id: Int? = null
    var name: String? = null
    var status: String? = null
    var network: String? = null
    var webChannel: String? = null
    var imdb: String? = null
    var tvdb: Long? = null
    var poster: String? = null
    forEachField { field ->
        when (field) {
            "id" -> id = intOrNull()
            "name" -> name = stringOrNull()
            "status" -> status = stringOrNull()
            "network" -> forEachField { if (it == "name") network = stringOrNull() else skipValue() }
            "webChannel" -> forEachField { if (it == "name") webChannel = stringOrNull() else skipValue() }
            "externals" -> forEachField { external ->
                when (external) {
                    "imdb" -> imdb = stringOrNull()
                    "thetvdb" -> tvdb = longOrNull()
                    else -> skipValue()
                }
            }
            "image" -> forEachField { size ->
                when (size) {
                    "medium" -> stringOrNull()?.let { poster = it }
                    "original" -> stringOrNull()?.let { if (poster == null) poster = it }
                    else -> skipValue()
                }
            }
            else -> skipValue()
        }
    }
    return TvMazeScheduledShow(
        id = id ?: return null,
        ids = CalendarIds(imdb = imdb?.takeIf { it.startsWith("tt") }, tvdb = tvdb),
        name = name,
        status = tvMazeStatus(status),
        network = network ?: webChannel,
        poster = poster?.takeIf(String::isNotBlank)?.replaceFirst("http://", "https://")
    )
}

/** Calls [block] with each field name of the object at the cursor; a null there is skipped. */
private inline fun JsonReader.forEachField(block: JsonReader.(String) -> Unit) {
    if (peek() == JsonReader.Token.NULL) {
        nextNull<Unit>()
        return
    }
    beginObject()
    while (hasNext()) block(nextName())
    endObject()
}

private fun JsonReader.stringOrNull(): String? = if (peek() == JsonReader.Token.NULL) nextNull() else nextString()

private fun JsonReader.intOrNull(): Int? = if (peek() == JsonReader.Token.NULL) nextNull() else nextInt()

private fun JsonReader.longOrNull(): Long? = if (peek() == JsonReader.Token.NULL) nextNull() else nextLong()
