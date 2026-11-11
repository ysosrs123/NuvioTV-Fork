package com.nuvio.tv.core.calendar

import com.nuvio.tv.core.util.parseEpisodeReleaseInstant
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs

internal const val CALENDAR_MAX_ENTRIES_PER_TITLE = 40
internal val CALENDAR_KEEP_PAST: Duration = Duration.ofDays(15)
internal val CALENDAR_KEEP_AHEAD: Duration = Duration.ofDays(366)

/**
 * One entry per release. An exact time beats a plain date and is only ever taken from a source that gave one; when
 * two sources give a time, the one fetched last wins, so a show that moved is not held back by an older answer.
 * Trakt's episode type beats every other label.
 */
/**
 * Which sources' dates count for a title. Sources disagree on a few titles, and comparing them showed where each is
 * reliable: TVmaze for anime, and TVmaze's earlier date when a show streams days before it reaches cable.
 */
internal fun calendarEntriesToMerge(record: CalendarTitleRecord): List<CalendarEntry> {
    val tvMaze = record.sources[CalendarSource.TVMAZE].orEmpty()
    if (record.tvmazeAnime && tvMaze.any { it.kind == CalendarEntryKind.EPISODE }) return tvMaze
    val all = record.sources.values.flatten()
    if (tvMaze.isEmpty()) return all
    val tvMazeDays = tvMaze.mapNotNull { entry -> entry.utcDay()?.let { entry.slot to it } }.toMap()
    val others = all.filter { it.source != CalendarSource.TVMAZE }
    val otherSlots = others.mapTo(HashSet()) { it.slot }
    val traktCovers = !record.sources[CalendarSource.TRAKT].isNullOrEmpty()
    return all.filter { entry ->
        if (entry.source == CalendarSource.TVMAZE) {
            // Sources number some shows differently: under the Trakt calendar a TVmaze-only number on a day it already
            // has is that release again, not another one.
            val repeat = traktCovers && entry.slot !in otherSlots && entry.utcDay()?.let { day ->
                others.any { other -> other.utcDay()?.let { abs(ChronoUnit.DAYS.between(day, it)) <= 1L } == true }
            } == true
            // The same number days away from every other source is a different episode under TVmaze's numbering.
            val otherDays = others.filter { it.slot == entry.slot }.mapNotNull { it.utcDay() }
            val elsewhere = entry.utcDay()?.let { day ->
                otherDays.isNotEmpty() && otherDays.none { abs(ChronoUnit.DAYS.between(day, it)) <= SAME_RELEASE_MAX_DAYS }
            } == true
            !repeat && !elsewhere
        } else {
            val gap = tvMazeDays[entry.slot]?.let { day -> entry.utcDay()?.let { ChronoUnit.DAYS.between(day, it) } }
            !(record.tvmazeStreaming && gap != null && gap in STREAMING_FIRST_GAP_DAYS)
        }
    }
}

private fun CalendarEntry.utcDay(): LocalDate? =
    (airInstantMs ?: placeAtMs)?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        ?: airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/**
 * A date the Calendar knows for an episode, when it is up to [EARLIER_RELEASE_MAX_DAYS] days before what the details
 * add-on says. Later dates are never returned: an episode the add-on says is out stays out.
 */
internal fun calendarEarlierRelease(addonReleased: String?, entry: CalendarEntry): String? {
    val addon = parseEpisodeReleaseInstant(addonReleased, ZoneOffset.UTC) ?: return null
    val calendar = entry.airInstantMs?.let(Instant::ofEpochMilli)
        ?: entry.airDate?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull() }
        ?: return null
    if (!calendar.isBefore(addon)) return null
    val days = ChronoUnit.DAYS.between(calendar.atZone(ZoneOffset.UTC).toLocalDate(), addon.atZone(ZoneOffset.UTC).toLocalDate())
    if (days > EARLIER_RELEASE_MAX_DAYS) return null
    return entry.airDate.takeIf { entry.airInstantMs == null } ?: calendar.toString()
}

private val STREAMING_FIRST_GAP_DAYS = 2L..3L
private const val SAME_RELEASE_MAX_DAYS = 3L
private const val EARLIER_RELEASE_MAX_DAYS = 3L

fun mergeCalendarEntries(
    entries: List<CalendarEntry>,
    fetchedAtMs: Map<CalendarSource, Long> = emptyMap()
): List<CalendarEntry> =
    entries.groupBy(CalendarEntry::slot).values
        .map { mergeSameRelease(it, fetchedAtMs) }
        .sortedWith(compareBy<CalendarEntry, Instant?>(nullsLast()) { it.sortInstant() }
            .thenBy { it.season ?: 0 }.thenBy { it.episode ?: 0 })

private fun mergeSameRelease(candidates: List<CalendarEntry>, fetchedAtMs: Map<CalendarSource, Long>): CalendarEntry {
    if (candidates.size == 1) return candidates.single()
    val byPriority = candidates.sortedBy { it.source.priority }
    val timed = byPriority.filter { it.timeKnown }
    // TheTVDB's time is a show's regular slot; Trakt's is for the episode.
    val preferred = if (timed.any { it.source.isTrakt }) timed.filter { it.source != CalendarSource.TVDB } else timed
    val base = preferred.maxWithOrNull(
        compareBy<CalendarEntry> { fetchedAtMs[it.source] ?: 0L }.thenByDescending { it.source.priority }
    ) ?: byPriority.firstOrNull { it.airDate != null } ?: byPriority.first()
    val label = byPriority.firstOrNull { it.source.isTrakt }?.label
        ?: byPriority.firstOrNull { it.label != CalendarEpisodeLabel.NONE }?.label
        ?: CalendarEpisodeLabel.NONE
    val title = byPriority.firstNotNullOfOrNull { it.title?.takeIf(String::isNotBlank) }
    return base.copy(title = title, label = label)
}

fun deriveEpisodeLabel(season: Int?, episode: Int?): CalendarEpisodeLabel = when {
    season == null || season < 1 || episode != 1 -> CalendarEpisodeLabel.NONE
    season == 1 -> CalendarEpisodeLabel.NEW_SHOW
    else -> CalendarEpisodeLabel.SEASON_PREMIERE
}

fun traktEpisodeLabel(episodeType: String?, season: Int?, episode: Int?): CalendarEpisodeLabel =
    when (episodeType?.trim()?.lowercase()) {
        null, "" -> deriveEpisodeLabel(season, episode)
        "series_premiere" -> CalendarEpisodeLabel.NEW_SHOW
        "season_premiere" -> CalendarEpisodeLabel.SEASON_PREMIERE
        "mid_season_premiere" -> CalendarEpisodeLabel.MID_SEASON_PREMIERE
        "mid_season_finale" -> CalendarEpisodeLabel.MID_SEASON_FINALE
        "season_finale" -> CalendarEpisodeLabel.SEASON_FINALE
        "series_finale" -> CalendarEpisodeLabel.SERIES_FINALE
        else -> CalendarEpisodeLabel.NONE
    }

fun tmdbEpisodeLabel(episodeType: String?): CalendarEpisodeLabel = when (episodeType?.trim()?.lowercase()) {
    "finale" -> CalendarEpisodeLabel.SEASON_FINALE
    "mid_season" -> CalendarEpisodeLabel.MID_SEASON_FINALE
    else -> CalendarEpisodeLabel.NONE
}

fun keptCalendarEntries(entries: List<CalendarEntry>, now: Instant): List<CalendarEntry> {
    val from = now.minus(CALENDAR_KEEP_PAST)
    val to = now.plus(CALENDAR_KEEP_AHEAD)
    return entries.filter { entry -> entry.sortInstant()?.let { !it.isBefore(from) && !it.isAfter(to) } == true }
}

/** Releases from 15 days back to a year ahead, at most 40 per title after merging, earliest first. */
fun CalendarTitleRecord.bounded(now: Instant): CalendarTitleRecord {
    val kept = sources.mapValues { (_, entries) -> keptCalendarEntries(entries, now) }.filterValues { it.isNotEmpty() }
    val slots = copy(sources = kept).entries.take(CALENDAR_MAX_ENTRIES_PER_TITLE).mapTo(HashSet()) { it.slot }
    val trimmed = kept.mapValues { (_, entries) -> entries.filter { it.slot in slots } }.filterValues { it.isNotEmpty() }
    return copy(sources = trimmed, sourceFetchedAtMs = sourceFetchedAtMs.filterKeys { it in trimmed })
}

fun CalendarTitleRecord.withSource(source: CalendarSource, entries: List<CalendarEntry>, now: Instant): CalendarTitleRecord =
    copy(
        sources = sources - source + (source to entries),
        sourceFetchedAtMs = sourceFetchedAtMs + (source to now.toEpochMilli())
    ).bounded(now)

fun CalendarTitleRecord.withoutSources(vararg removed: CalendarSource): CalendarTitleRecord =
    if (removed.none { it in sources }) this
    else copy(sources = sources - removed.toSet(), sourceFetchedAtMs = sourceFetchedAtMs - removed.toSet())

fun applyAddonEntries(
    records: List<CalendarTitleRecord>,
    addonEntries: List<Pair<CalendarIds, CalendarEntry>>,
    now: Instant
): List<CalendarTitleRecord> = records.map { record ->
    if (record.type != CalendarTitleType.SERIES) return@map record
    val mine = addonEntries.filter { (ids, _) -> ids.sharesIdWith(record.ids) }.map { it.second }.distinctBy { it.slot }
    record.withSource(CalendarSource.ADDON, mine, now)
}
