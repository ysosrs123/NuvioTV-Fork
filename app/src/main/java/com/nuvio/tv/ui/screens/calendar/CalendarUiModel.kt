package com.nuvio.tv.ui.screens.calendar

import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.calendar.CalendarEntry
import com.nuvio.tv.core.calendar.CalendarEntryKind
import com.nuvio.tv.core.calendar.CalendarEpisodeLabel
import com.nuvio.tv.core.calendar.CalendarSection
import com.nuvio.tv.core.calendar.CalendarSectionKind
import com.nuvio.tv.core.calendar.CalendarSettings
import com.nuvio.tv.core.calendar.CalendarShowStatus
import com.nuvio.tv.core.calendar.CalendarTitleRecord
import com.nuvio.tv.core.calendar.CalendarTitleType
import com.nuvio.tv.core.calendar.CalendarWatchedIndex
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** What sits at the right of a row: the show's bell before release, a tick or a dot after it. */
enum class CalendarRowStatus { BELL_OFF, BELL_ON, WATCHED, NOT_WATCHED, NONE;

    val hasBell: Boolean get() = this == BELL_OFF || this == BELL_ON
}

enum class CalendarRowKind { EPISODE, FILM_CINEMA, FILM_DIGITAL, SHOW }

@Immutable
data class CalendarRow(
    val key: String,
    val titleKey: String,
    val name: String?,
    val poster: String?,
    val contentId: String?,
    val type: CalendarTitleType,
    val kind: CalendarRowKind,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val label: CalendarEpisodeLabel = CalendarEpisodeLabel.NONE,
    val status: CalendarRowStatus = CalendarRowStatus.NONE,
    val showStatus: CalendarShowStatus = CalendarShowStatus.UNKNOWN,
    val network: String? = null,
    val section: CalendarSectionKind = CalendarSectionKind.DAY
) {
    val dimmed: Boolean get() = status == CalendarRowStatus.WATCHED
    val isFilm: Boolean get() = type == CalendarTitleType.MOVIE
    val apiType: String get() = if (isFilm) "movie" else "series"
}

sealed interface CalendarListEntry {
    val key: String
}

@Immutable
data class CalendarHeaderEntry(val kind: CalendarSectionKind, val date: LocalDate?) : CalendarListEntry {
    override val key: String get() = "h:${kind.name}:${date ?: ""}"
}

@Immutable
data class CalendarRowEntry(val row: CalendarRow) : CalendarListEntry {
    override val key: String get() = row.key
}

@Immutable
data class CalendarListModel(
    val entries: List<CalendarListEntry> = emptyList(),
    /** The row the list opens on: the first of today or later, else the first row. */
    val startRowKey: String? = null
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    fun indexOf(key: String): Int = entries.indexOfFirst { it.key == key }

    /** The row to focus when [row]'s title leaves the list: the next row of another title, else the one before. */
    fun neighbourOf(row: CalendarRow): String? {
        val rows = entries.filterIsInstance<CalendarRowEntry>().map { it.row }
        val index = rows.indexOfFirst { it.key == row.key }.takeIf { it >= 0 } ?: return null
        return (rows.drop(index + 1).firstOrNull { it.titleKey != row.titleKey }
            ?: rows.take(index).lastOrNull { it.titleKey != row.titleKey })?.key
    }

    fun firstRowKeyIn(section: CalendarSectionKind): String? =
        entries.firstNotNullOfOrNull { (it as? CalendarRowEntry)?.row?.takeIf { row -> row.section == section }?.key }

    /** The first row on [date] or the first one after it; the last row when the date is past them all. */
    fun rowKeyFor(date: LocalDate): String? {
        val rows = entries.filterIsInstance<CalendarRowEntry>().map { it.row }
        val dated = rows.filter { it.section != CalendarSectionKind.JUST_AIRED && it.date != null }
        return (dated.firstOrNull { it.date!! >= date } ?: rows.firstOrNull { it.date == date } ?: dated.lastOrNull())?.key
    }
}

fun calendarRowStatus(
    title: CalendarTitleRecord,
    entry: CalendarEntry?,
    now: Instant,
    bells: Set<String>,
    watched: CalendarWatchedIndex?
): CalendarRowStatus {
    val bell = bells.any(title::matchesKey)
    if (entry == null) return if (bell) CalendarRowStatus.BELL_ON else CalendarRowStatus.BELL_OFF
    if (entry.isOut(now)) {
        return if (watched?.isWatched(title, entry) == true) CalendarRowStatus.WATCHED else CalendarRowStatus.NOT_WATCHED
    }
    if (entry.kind == CalendarEntryKind.MOVIE_THEATRICAL) return CalendarRowStatus.NONE
    return if (bell) CalendarRowStatus.BELL_ON else CalendarRowStatus.BELL_OFF
}

private fun rowKind(title: CalendarTitleRecord, entry: CalendarEntry?): CalendarRowKind = when {
    entry == null -> CalendarRowKind.SHOW
    entry.kind == CalendarEntryKind.MOVIE_THEATRICAL -> CalendarRowKind.FILM_CINEMA
    entry.kind == CalendarEntryKind.MOVIE_DIGITAL -> CalendarRowKind.FILM_DIGITAL
    title.type == CalendarTitleType.MOVIE -> CalendarRowKind.FILM_DIGITAL
    else -> CalendarRowKind.EPISODE
}

fun calendarRow(
    title: CalendarTitleRecord,
    entry: CalendarEntry?,
    date: LocalDate?,
    time: LocalTime?,
    section: CalendarSectionKind,
    now: Instant,
    today: LocalDate,
    bells: Set<String>,
    watched: CalendarWatchedIndex?
): CalendarRow {
    val kind = rowKind(title, entry)
    return CalendarRow(
        key = "${title.key}|${entry?.slot ?: "next"}",
        titleKey = title.key,
        name = title.name,
        poster = title.poster,
        contentId = title.contentId ?: title.ids.imdb ?: title.ids.tmdb?.let { "tmdb:$it" },
        type = title.type,
        kind = kind,
        season = entry?.season.takeIf { kind == CalendarRowKind.EPISODE },
        episode = entry?.episode.takeIf { kind == CalendarRowKind.EPISODE },
        episodeTitle = entry?.shownTitle?.takeIf { kind == CalendarRowKind.EPISODE },
        date = date,
        time = time,
        label = entry?.label ?: CalendarEpisodeLabel.NONE,
        status = calendarRowStatus(title, entry, now, bells, watched),
        showStatus = title.status,
        network = calendarNetworkName(title.network),
        section = section
    )
}

/** Turns the sections into one flat list of headings and rows with keys that never repeat. */
fun buildCalendarList(
    sections: List<CalendarSection>,
    now: Instant,
    zone: ZoneId,
    bells: Set<String>,
    watched: CalendarWatchedIndex?
): CalendarListModel {
    val today = now.atZone(zone).toLocalDate()
    val entries = ArrayList<CalendarListEntry>()
    val used = HashSet<String>()
    var startRowKey: String? = null
    var firstRowKey: String? = null
    sections.forEach { section ->
        if (section.items.isEmpty()) return@forEach
        entries += CalendarHeaderEntry(section.kind, section.date)
        section.items.forEach { item ->
            val base = calendarRow(item.title, item.entry, item.date, item.time, section.kind, now, today, bells, watched)
            var key = base.key
            var n = 1
            while (!used.add(key)) key = "${base.key}#${n++}"
            val row = if (key == base.key) base else base.copy(key = key)
            entries += CalendarRowEntry(row)
            if (firstRowKey == null) firstRowKey = key
            if (startRowKey == null && section.kind != CalendarSectionKind.JUST_AIRED) startRowKey = key
        }
    }
    return CalendarListModel(entries, startRowKey ?: firstRowKey)
}

/** Every dated release by day, for the month view. A day's rows are in time order. */
fun buildCalendarDays(
    titles: List<CalendarTitleRecord>,
    now: Instant,
    zone: ZoneId,
    bells: Set<String>,
    watched: CalendarWatchedIndex?
): Map<LocalDate, List<CalendarRow>> {
    val today = now.atZone(zone).toLocalDate()
    val byDay = HashMap<LocalDate, MutableList<CalendarRow>>()
    titles.forEach { title ->
        title.entries.forEach { entry ->
            val date = entry.localDate(zone) ?: return@forEach
            val time = entry.airInstantMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
            byDay.getOrPut(date) { mutableListOf() } +=
                calendarRow(title, entry, date, time, CalendarSectionKind.DAY, now, today, bells, watched)
        }
    }
    return byDay.mapValues { (_, rows) ->
        rows.sortedWith(compareBy<CalendarRow> { it.time == null }.thenBy { it.time }.thenBy { it.name.orEmpty().lowercase() })
            .distinctBy { it.key }
    }
}

@Immutable
data class CalendarDaySummary(
    val posters: List<String?> = emptyList(),
    val more: Int = 0,
    val episodes: Int = 0,
    val films: Int = 0
) {
    val isEmpty: Boolean get() = episodes == 0 && films == 0
}

fun calendarDaySummary(rows: List<CalendarRow>?, maxPosters: Int = 2): CalendarDaySummary {
    if (rows.isNullOrEmpty()) return CalendarDaySummary()
    val shown = rows.take(maxPosters)
    return CalendarDaySummary(
        posters = shown.map { it.poster },
        more = rows.size - shown.size,
        episodes = rows.count { !it.isFilm },
        films = rows.count { it.isFilm }
    )
}

/** Six full weeks starting on [firstDay], so the grid never changes height between months. */
fun calendarMonthGrid(month: YearMonth, firstDay: DayOfWeek): List<LocalDate> {
    val start = month.atDay(1).with(TemporalAdjusters.previousOrSame(firstDay))
    return List(42) { start.plusDays(it.toLong()) }
}

enum class CalendarMonthKey { LEFT, RIGHT, UP, DOWN }

sealed interface CalendarMonthMove {
    data class To(val date: LocalDate) : CalendarMonthMove
    /** Focus leaves the grid: left to the menu, up to the header, right into the day's list. */
    data class Leave(val key: CalendarMonthKey) : CalendarMonthMove
}

/**
 * Moves the focused day. Left from the first column and up from the top week leave the grid; down from the last week
 * and any move onto a day of another month turn the month.
 */
fun calendarMonthMove(focused: LocalDate, month: YearMonth, firstDay: DayOfWeek, key: CalendarMonthKey): CalendarMonthMove {
    val grid = calendarMonthGrid(month, firstDay)
    val index = grid.indexOf(focused).takeIf { it >= 0 } ?: return CalendarMonthMove.To(month.atDay(1))
    val column = index % 7
    val row = index / 7
    return when (key) {
        CalendarMonthKey.LEFT -> if (column == 0) CalendarMonthMove.Leave(key) else CalendarMonthMove.To(focused.minusDays(1))
        CalendarMonthKey.RIGHT -> if (column == 6) CalendarMonthMove.Leave(key) else CalendarMonthMove.To(focused.plusDays(1))
        CalendarMonthKey.UP -> if (row == 0) CalendarMonthMove.Leave(key) else CalendarMonthMove.To(focused.minusWeeks(1))
        CalendarMonthKey.DOWN -> CalendarMonthMove.To(focused.plusWeeks(1))
    }
}

/** Calendar sits in the menu unless it is switched off; until the settings are read the default (shown) holds. */
fun calendarMenuVisible(settings: CalendarSettings?): Boolean = settings?.menuVisible ?: true
