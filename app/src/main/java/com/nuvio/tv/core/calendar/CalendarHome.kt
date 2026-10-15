package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

val CALENDAR_SETTLE_DELAY: Duration = Duration.ofSeconds(20)
val CALENDAR_FOREGROUND_REFRESH: Duration = Duration.ofHours(6)
private val REMINDER_CHECK_MIN: Duration = Duration.ofMinutes(1)
private val REMINDER_CHECK_MAX: Duration = Duration.ofHours(6)

/** Once per app start, and again after the app has been away and 6 hours have passed since the last one. */
fun calendarSettledRefreshDue(lastRequestAtMs: Long?, backgroundedSince: Boolean, nowMs: Long): Boolean =
    lastRequestAtMs == null || (backgroundedSince && nowMs - lastRequestAtMs >= CALENDAR_FOREGROUND_REFRESH.toMillis())

/** The next moment a bell title's release becomes due, or null when nothing is waiting. */
fun calendarNextReminderAt(
    titles: List<CalendarTitleRecord>,
    bells: Set<String>,
    seenEpisodeKeys: Set<String>,
    now: Instant
): Instant? = titles
    .filter { title -> bells.any(title::matchesKey) }
    .flatMap { title ->
        title.entries.mapNotNull { entry ->
            if (entry.kind == CalendarEntryKind.MOVIE_THEATRICAL) return@mapNotNull null
            val dueAt = calendarReminderDueAt(entry) ?: return@mapNotNull null
            dueAt.takeIf { it.isAfter(now) && title.episodeKey(entry) !in seenEpisodeKeys }
        }
    }
    .minOrNull()

/** Never sooner than a minute, never later than 6 hours. */
fun calendarReminderCheckDelay(next: Instant?, now: Instant): Duration {
    val wait = next?.let { Duration.between(now, it) } ?: return REMINDER_CHECK_MAX
    return when {
        wait < REMINDER_CHECK_MIN -> REMINDER_CHECK_MIN
        wait > REMINDER_CHECK_MAX -> REMINDER_CHECK_MAX
        else -> wait
    }
}

/** One card for everything due: up to [shown] rows, the rest counted. Every key is marked seen. */
data class CalendarReminderCard(
    val items: List<CalendarReminder>,
    val more: Int,
    val episodeKeys: List<String>,
    val zone: ZoneId,
    val now: Instant
) {
    val single: Boolean get() = items.size == 1 && more == 0
    val total: Int get() = items.size + more
}

fun calendarReminderCard(due: CalendarDueReminders, zone: ZoneId, now: Instant, shown: Int = 3): CalendarReminderCard? {
    if (due.count == 0) return null
    return CalendarReminderCard(
        items = due.items.take(shown),
        more = due.moreThan(shown),
        episodeKeys = due.items.map { it.episodeKey },
        zone = zone,
        now = now
    )
}

/** Everything that became due since the last check, shown together in the corner. */
data class CalendarCornerMessage(val id: Long, val items: List<CalendarReminder>) {
    val count: Int get() = items.size
    val names: List<String> get() = items.mapNotNull { it.title.name?.takeIf(String::isNotBlank) }.distinct()
}

enum class CalendarComingUpWhen { NEW, TODAY, TONIGHT, TOMORROW, WEEKDAY, DATE }

data class CalendarComingUpItem(
    val title: CalendarTitleRecord,
    val entry: CalendarEntry,
    val whenKind: CalendarComingUpWhen,
    val date: LocalDate,
    val time: LocalTime?
)

const val CALENDAR_COMING_UP_MAX = 15
private const val NEW_DAYS = 7L
private const val WEEKDAY_DAYS = 6L
private val TONIGHT_FROM: LocalTime = LocalTime.of(17, 0)

/**
 * The Home row: one card per title. Anything out in the last 7 days and not watched comes first, newest first; then
 * the soonest release of every other title. A release Continue Watching already shows is left out, and a cinema date
 * is only listed before it opens.
 */
fun buildCalendarComingUp(
    titles: List<CalendarTitleRecord>,
    watched: CalendarWatchedIndex?,
    now: Instant,
    zone: ZoneId,
    shownInContinueWatching: (CalendarTitleRecord, CalendarEntry) -> Boolean = { _, _ -> false },
    max: Int = CALENDAR_COMING_UP_MAX
): List<CalendarComingUpItem> {
    val today = now.atZone(zone).toLocalDate()
    val oldest = today.minusDays(NEW_DAYS)
    val fresh = mutableListOf<CalendarComingUpItem>()
    val upcoming = mutableListOf<CalendarComingUpItem>()
    titles.forEach { title ->
        var newestOut: CalendarComingUpItem? = null
        var soonest: CalendarComingUpItem? = null
        title.entries.forEach { entry ->
            val date = entry.localDate(zone) ?: return@forEach
            val time = entry.airInstantMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
            if (shownInContinueWatching(title, entry)) return@forEach
            if (entry.isOut(now)) {
                if (date < oldest || entry.kind == CalendarEntryKind.MOVIE_THEATRICAL) return@forEach
                if (watched?.isWatched(title, entry) == true) return@forEach
                val item = CalendarComingUpItem(title, entry, CalendarComingUpWhen.NEW, date, time)
                if (newestOut == null || comingUpOrder.compare(item, newestOut) > 0) newestOut = item
            } else {
                val shownDate = maxOf(date, today)
                val item = CalendarComingUpItem(title, entry, comingUpWhen(shownDate, time, today), shownDate, time)
                if (soonest == null || comingUpOrder.compare(item, soonest) < 0) soonest = item
            }
        }
        newestOut?.let(fresh::add) ?: soonest?.let(upcoming::add)
    }
    return (fresh.sortedWith(comingUpOrder.reversed()) + upcoming.sortedWith(comingUpOrder)).take(max)
}

private fun comingUpWhen(date: LocalDate, time: LocalTime?, today: LocalDate): CalendarComingUpWhen = when {
    date == today -> if (time != null && time >= TONIGHT_FROM) CalendarComingUpWhen.TONIGHT else CalendarComingUpWhen.TODAY
    date == today.plusDays(1) -> CalendarComingUpWhen.TOMORROW
    date <= today.plusDays(WEEKDAY_DAYS) -> CalendarComingUpWhen.WEEKDAY
    else -> CalendarComingUpWhen.DATE
}

private val comingUpOrder: Comparator<CalendarComingUpItem> =
    compareBy<CalendarComingUpItem> { it.date }
        .thenBy { it.time == null }
        .thenBy { it.time }
        .thenBy { it.title.name.orEmpty().lowercase() }
        .thenBy { it.entry.season ?: 0 }
        .thenBy { it.entry.episode ?: 0 }

/** Keys for episodes Continue Watching shows as next up or new, by every id of the show. */
fun calendarContinueWatchingKeys(episodes: List<Triple<String, Int, Int>>): Set<String> =
    episodes.flatMapTo(hashSetOf()) { (contentId, season, episode) ->
        CalendarIds.fromContentId(contentId).aliases().map { "$it|$season|$episode" }
    }

fun calendarEntryInKeys(keys: Set<String>, title: CalendarTitleRecord, entry: CalendarEntry): Boolean {
    if (keys.isEmpty() || entry.kind != CalendarEntryKind.EPISODE) return false
    val season = entry.season ?: return false
    val episode = entry.episode ?: return false
    return title.ids.aliases().any { "$it|$season|$episode" in keys }
}

/** Ids to open a title by, best first. */
fun calendarContentIds(title: CalendarTitleRecord): List<String> =
    listOfNotNull(title.contentId, title.ids.imdb, title.ids.tmdb?.let { "tmdb:$it" }).distinct()
