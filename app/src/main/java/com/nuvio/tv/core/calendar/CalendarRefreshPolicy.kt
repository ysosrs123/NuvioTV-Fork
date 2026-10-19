package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private val FAILURE_BACKOFF = listOf(Duration.ofHours(1), Duration.ofHours(6), Duration.ofHours(24))

/**
 * When a title is asked again: next release within 7 days every 6 hours, within 30 days daily, later or unknown and
 * still running weekly, ended or cancelled monthly. After a failed fetch: 1 hour, then 6 hours, then daily.
 * A show TVmaze's full schedule covers gets its dates from that file; it is only asked again for TMDB's finale marks,
 * daily while a release is near and monthly otherwise. A film that came out two or more years ago is asked once a
 * year; one whose last release date has passed every 90 days.
 */
fun nextRefreshAt(record: CalendarTitleRecord, now: Instant): Instant {
    if (record.failures > 0) return now.plus(FAILURE_BACKOFF[(record.failures - 1).coerceAtMost(FAILURE_BACKOFF.lastIndex)])
    val next = record.entries.mapNotNull { it.sortInstant() }.filter { !it.isBefore(now) }.minOrNull()
    if (record.type == CalendarTitleType.SERIES && record.scheduleCheckedAtMs > 0L) {
        val near = next != null && next.isBefore(now.plus(CALENDAR_FINALE_LOOKAHEAD))
        return now.plus(if (near) Duration.ofDays(1) else Duration.ofDays(30))
    }
    if (record.type == CalendarTitleType.MOVIE) {
        if (record.releasedYearsAgo(now)) return now.plus(Duration.ofDays(365))
        if (next == null && record.entries.isNotEmpty()) return now.plus(Duration.ofDays(90))
    }
    val wait = when {
        next != null && next.isBefore(now.plus(Duration.ofDays(7))) -> Duration.ofHours(6)
        next != null && next.isBefore(now.plus(Duration.ofDays(30))) -> Duration.ofHours(24)
        next == null && record.status.isOver -> Duration.ofDays(30)
        else -> Duration.ofDays(7)
    }
    return now.plus(wait)
}

fun CalendarTitleRecord.isDue(now: Instant): Boolean = nextRefreshAtMs <= now.toEpochMilli()

/** A film the library dates two or more calendar years back: its cinema and home releases are long past. */
fun CalendarTitleRecord.releasedYearsAgo(now: Instant): Boolean =
    type == CalendarTitleType.MOVIE && releaseYear != null && releaseYear <= now.atZone(ZoneOffset.UTC).year - 2

val CALENDAR_TRAKT_REFRESH: Duration = Duration.ofHours(6)
