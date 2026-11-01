package com.nuvio.tv.core.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarAddonDatesTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    private val london = ZoneId.of("Europe/London")
    private val losAngeles = ZoneId.of("America/Los_Angeles")
    private val sunday = LocalDate.of(2026, 11, 1)
    private val monday = LocalDate.of(2026, 11, 2)

    private fun addon(released: String): CalendarEntry {
        val (date, exactMs) = calendarAddonRelease(released)!!
        return CalendarEntry(season = 3, episode = 4, airDate = date, placeAtMs = exactMs, source = CalendarSource.ADDON)
    }

    private fun show(networkZone: String?, vararg sources: Pair<CalendarSource, List<CalendarEntry>>) = CalendarTitleRecord(
        key = "series:imdb:tt1", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt1"),
        networkZone = networkZone, sources = mapOf(*sources)
    )

    private fun days(entry: CalendarEntry) = listOf(sydney, london, losAngeles).map { entry.localDate(it) }

    @Test fun aDateOnlyValueKeepsItsDateAndGivesNoMoment() {
        assertEquals("2026-11-01" to null, calendarAddonRelease("2026-11-01"))
        assertEquals("2026-11-01" to null, calendarAddonRelease("2026-11-01T00:00:00.000Z"))
        assertEquals("2026-11-01" to null, calendarAddonRelease("2026-11-01T00:00:00+11:00"))
        assertNull(calendarAddonRelease("2026-13-45"))
        assertNull(calendarAddonRelease("soon"))
    }

    @Test fun aRealTimeIsTheMomentItAirs() {
        val sundayNinePmEastern = Instant.parse("2026-11-02T02:00:00Z").toEpochMilli()
        assertEquals("2026-11-02" to sundayNinePmEastern, calendarAddonRelease("2026-11-02T02:00:00.000Z"))
        assertEquals("2026-11-01" to sundayNinePmEastern, calendarAddonRelease("2026-11-01T21:00:00-05:00"))
    }

    @Test fun aUsEveningAirTimeFallsOnEachViewersOwnDay() {
        val entry = show(null, CalendarSource.ADDON to listOf(addon("2026-11-02T02:00:00.000Z"))).entries.single()
        assertEquals(listOf(monday, monday, sunday), days(entry))
        assertFalse(entry.timeKnown)
    }

    @Test fun aDateOnlyValueIsAnEveningInTheNetworksZone() {
        val dateOnly = show("America/New_York", CalendarSource.ADDON to listOf(addon("2026-11-01"))).entries.single()
        assertEquals(listOf(monday, monday, sunday), days(dateOnly))
        assertFalse(dateOnly.timeKnown)
        assertEquals("2026-11-01", dateOnly.airDate)

        val utcMidnight = show("America/New_York", CalendarSource.ADDON to listOf(addon("2026-11-01T00:00:00.000Z"))).entries.single()
        assertEquals(listOf(monday, monday, sunday), days(utcMidnight))
    }

    @Test fun anAustralianNetworkKeepsTheSameDayInSydney() {
        val entry = show("Australia/Sydney", CalendarSource.ADDON to listOf(addon("2026-11-01"))).entries.single()
        assertEquals(sunday, entry.localDate(sydney))
    }

    @Test fun withoutTheNetworksZoneTheDateIsNotShifted() {
        val dateOnly = show(null, CalendarSource.ADDON to listOf(addon("2026-11-01"))).entries.single()
        assertEquals(listOf(sunday, sunday, sunday), days(dateOnly))
        val utcMidnight = show(null, CalendarSource.ADDON to listOf(addon("2026-11-01T00:00:00.000Z"))).entries.single()
        assertEquals(listOf(sunday, sunday, sunday), days(utcMidnight))
        assertEquals(listOf(sunday, sunday, sunday), days(show("not/a-zone", CalendarSource.ADDON to listOf(addon("2026-11-01"))).entries.single()))
    }

    @Test fun otherSourcesPlainDatesAreLeftAsGiven() {
        val tvMaze = CalendarEntry(season = 3, episode = 4, airDate = "2026-11-01", source = CalendarSource.TVMAZE)
        assertEquals(listOf(sunday, sunday, sunday), days(show("America/New_York", CalendarSource.TVMAZE to listOf(tvMaze)).entries.single()))
    }

    @Test fun anExactTimeFromAnotherSourceBeatsTheAddonDate() {
        val tvMazeAt = Instant.parse("2026-11-02T02:00:00Z").toEpochMilli()
        val tvMaze = CalendarEntry(season = 3, episode = 4, airInstantMs = tvMazeAt, source = CalendarSource.TVMAZE)
        val merged = show(
            "America/New_York",
            CalendarSource.ADDON to listOf(addon("2026-11-03")),
            CalendarSource.TVMAZE to listOf(tvMaze)
        ).entries.single()
        assertEquals(CalendarSource.TVMAZE, merged.source)
        assertEquals(tvMazeAt, merged.airInstantMs)
        assertEquals(listOf(monday, monday, sunday), days(merged))

        val tvdbAt = Instant.parse("2026-11-02T01:00:00Z").toEpochMilli()
        val tvdb = CalendarEntry(season = 3, episode = 4, airInstantMs = tvdbAt, source = CalendarSource.TVDB)
        val withTvdb = show(
            null,
            CalendarSource.ADDON to listOf(addon("2026-11-02T02:00:00.000Z")),
            CalendarSource.TVDB to listOf(tvdb)
        ).entries.single()
        assertEquals(CalendarSource.TVDB, withTvdb.source)
        assertEquals(tvdbAt, withTvdb.airInstantMs)
    }

    @Test fun aPlainDateFromTvMazeStillBeatsTheAddonDate() {
        val tvMaze = CalendarEntry(season = 3, episode = 4, airDate = "2026-11-01", source = CalendarSource.TVMAZE)
        val merged = show(
            "America/New_York",
            CalendarSource.ADDON to listOf(addon("2026-11-02T02:00:00.000Z")),
            CalendarSource.TVMAZE to listOf(tvMaze)
        ).entries.single()
        assertEquals(CalendarSource.TVMAZE, merged.source)
        assertEquals(sunday, merged.localDate(sydney))
    }
}
