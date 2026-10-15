package com.nuvio.tv.core.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class CalendarSectionKind { JUST_AIRED, TODAY, TOMORROW, DAY, LATER, NO_DATE }

data class CalendarItem(
    val title: CalendarTitleRecord,
    val entry: CalendarEntry?,
    val date: LocalDate?,
    val time: LocalTime?
) {
    val episodeKey: String? get() = entry?.let(title::episodeKey)
}

data class CalendarSection(
    val kind: CalendarSectionKind,
    val date: LocalDate?,
    val items: List<CalendarItem>
)

const val CALENDAR_DEFAULT_WINDOW_DAYS = 30
val CALENDAR_WINDOW_OPTIONS = listOf(14, 30, 60)
private const val JUST_AIRED_DAYS = 7L
private const val LATER_MONTHS = 12L

/**
 * Days are counted in [zone] and the window counts today as its first day. A plain date stays on the date as given.
 * Later holds each title's next release after the window; No date yet holds running shows with nothing dated.
 */
fun buildCalendarSections(
    titles: List<CalendarTitleRecord>,
    now: Instant,
    zone: ZoneId,
    windowDays: Int = CALENDAR_DEFAULT_WINDOW_DAYS
): List<CalendarSection> {
    val today = now.atZone(zone).toLocalDate()
    val firstDay = today.minusDays(JUST_AIRED_DAYS)
    val windowEnd = today.plusDays(windowDays.coerceAtLeast(1).toLong())
    val laterEnd = today.plusMonths(LATER_MONTHS)

    val dated = mutableListOf<CalendarItem>()
    val later = mutableListOf<CalendarItem>()
    val undated = mutableListOf<CalendarItem>()
    titles.forEach { title ->
        val items = title.entries.mapNotNull { entry ->
            val given = entry.localDate(zone) ?: return@mapNotNull null
            val date = if (given < today && !entry.isOut(now)) today else given
            val time = entry.airInstantMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
            CalendarItem(title, entry, date, time)
        }
        items.filterTo(dated) { it.date!! >= firstDay && it.date < windowEnd }
        items.filter { it.date!! >= windowEnd && it.date <= laterEnd }.minWithOrNull(itemOrder)?.let(later::add)
        if (title.type == CalendarTitleType.SERIES && title.status.mayReturn && items.none { it.date!! >= today }) {
            undated += CalendarItem(title, null, null, null)
        }
    }

    val sections = mutableListOf<CalendarSection>()
    val byDay = dated.groupBy { it.date!! }
    byDay.filterKeys { it < today }.toSortedMap(reverseOrder()).values
        .flatMap { day -> day.sortedWith(itemOrder.reversed()) }
        .takeIf { it.isNotEmpty() }
        ?.let { sections += CalendarSection(CalendarSectionKind.JUST_AIRED, null, it) }
    byDay.filterKeys { it >= today }.toSortedMap().forEach { (day, items) ->
        val kind = when (day) {
            today -> CalendarSectionKind.TODAY
            today.plusDays(1) -> CalendarSectionKind.TOMORROW
            else -> CalendarSectionKind.DAY
        }
        sections += CalendarSection(kind, day, items.sortedWith(itemOrder))
    }
    if (later.isNotEmpty()) sections += CalendarSection(CalendarSectionKind.LATER, null, later.sortedWith(itemOrder))
    if (undated.isNotEmpty()) {
        sections += CalendarSection(CalendarSectionKind.NO_DATE, null, undated.sortedBy { it.title.name.orEmpty().lowercase() })
    }
    return sections
}

private val itemOrder: Comparator<CalendarItem> =
    compareBy<CalendarItem> { it.date }
        .thenBy { it.time == null }
        .thenBy { it.entry?.airInstantMs ?: 0L }
        .thenBy { it.title.name.orEmpty().lowercase() }
        .thenBy { it.entry?.season ?: 0 }
        .thenBy { it.entry?.episode ?: 0 }
