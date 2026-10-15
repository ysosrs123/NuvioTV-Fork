package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant

private val FAILURE_BACKOFF = listOf(Duration.ofHours(1), Duration.ofHours(6), Duration.ofHours(24))

/**
 * When a title is asked again: next release within 7 days every 6 hours, within 30 days daily, later or unknown and
 * still running weekly, ended or cancelled monthly. After a failed fetch: 1 hour, then 6 hours, then daily.
 */
fun nextRefreshAt(record: CalendarTitleRecord, now: Instant): Instant {
    if (record.failures > 0) return now.plus(FAILURE_BACKOFF[(record.failures - 1).coerceAtMost(FAILURE_BACKOFF.lastIndex)])
    val next = record.entries.mapNotNull { it.sortInstant() }.filter { !it.isBefore(now) }.minOrNull()
    val wait = when {
        next != null && next.isBefore(now.plus(Duration.ofDays(7))) -> Duration.ofHours(6)
        next != null && next.isBefore(now.plus(Duration.ofDays(30))) -> Duration.ofHours(24)
        next == null && record.status.isOver -> Duration.ofDays(30)
        else -> Duration.ofDays(7)
    }
    return now.plus(wait)
}

fun CalendarTitleRecord.isDue(now: Instant): Boolean = nextRefreshAtMs <= now.toEpochMilli()

val CALENDAR_TRAKT_REFRESH: Duration = Duration.ofHours(6)
