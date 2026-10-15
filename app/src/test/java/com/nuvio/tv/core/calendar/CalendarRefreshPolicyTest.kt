package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarRefreshPolicyTest {
    private val now = Instant.parse("2026-10-05T23:00:00Z")

    private fun record(
        nextIn: Duration?,
        status: CalendarShowStatus = CalendarShowStatus.RUNNING,
        failures: Int = 0,
        pastIn: Duration? = Duration.ofDays(-2)
    ) = CalendarTitleRecord(
        key = "series:imdb:tt1", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt1"),
        status = status, failures = failures,
        sources = mapOf(
            CalendarSource.TVMAZE to listOfNotNull(
                pastIn?.let { CalendarEntry(season = 1, episode = 1, airInstantMs = now.plus(it).toEpochMilli()) },
                nextIn?.let { CalendarEntry(season = 1, episode = 2, airInstantMs = now.plus(it).toEpochMilli()) }
            )
        )
    )

    private fun wait(record: CalendarTitleRecord) = Duration.between(now, nextRefreshAt(record, now))

    @Test fun nextReleaseWithinAWeekEverySixHours() {
        assertEquals(Duration.ofHours(6), wait(record(Duration.ofDays(6))))
        assertEquals(Duration.ofHours(6), wait(record(Duration.ofHours(1))))
    }

    @Test fun nextReleaseWithinAMonthDaily() {
        assertEquals(Duration.ofHours(24), wait(record(Duration.ofDays(7))))
        assertEquals(Duration.ofHours(24), wait(record(Duration.ofDays(29))))
    }

    @Test fun laterOrUnknownWeeklyWhileRunning() {
        assertEquals(Duration.ofDays(7), wait(record(Duration.ofDays(45))))
        assertEquals(Duration.ofDays(7), wait(record(null)))
        assertEquals(Duration.ofDays(7), wait(record(null, CalendarShowStatus.UNKNOWN)))
    }

    @Test fun endedOrCancelledMonthly() {
        assertEquals(Duration.ofDays(30), wait(record(null, CalendarShowStatus.ENDED)))
        assertEquals(Duration.ofDays(30), wait(record(null, CalendarShowStatus.CANCELLED)))
        assertEquals(Duration.ofHours(6), wait(record(Duration.ofDays(2), CalendarShowStatus.ENDED)))
    }

    @Test fun aPlainDateCountsFromUtcMidnight() {
        val dated = CalendarTitleRecord(
            key = "k", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt1"),
            sources = mapOf(CalendarSource.TMDB to listOf(CalendarEntry(season = 1, episode = 1, airDate = "2026-10-11")))
        )
        assertEquals(Duration.ofHours(6), wait(dated))
    }

    @Test fun failuresBackOffOneSixAndTwentyFourHours() {
        assertEquals(Duration.ofHours(1), wait(record(Duration.ofDays(1), failures = 1)))
        assertEquals(Duration.ofHours(6), wait(record(Duration.ofDays(1), failures = 2)))
        assertEquals(Duration.ofHours(24), wait(record(Duration.ofDays(1), failures = 3)))
        assertEquals(Duration.ofHours(24), wait(record(Duration.ofDays(1), failures = 9)))
    }

    @Test fun dueWhenTheRefreshTimeHasCome() {
        assertTrue(record(null).copy(nextRefreshAtMs = 0L).isDue(now))
        assertTrue(record(null).copy(nextRefreshAtMs = now.toEpochMilli()).isDue(now))
        assertFalse(record(null).copy(nextRefreshAtMs = now.toEpochMilli() + 1).isDue(now))
    }
}
