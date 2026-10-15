package com.nuvio.tv.core.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSectionsTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    private val newYork = ZoneId.of("America/New_York")
    /** Tue 6 Oct 2026, 10:00 in Sydney (AEDT, two days after the change from AEST). */
    private val now = Instant.parse("2026-10-05T23:00:00Z")

    private fun title(
        key: String,
        vararg entries: CalendarEntry,
        status: CalendarShowStatus = CalendarShowStatus.RUNNING,
        type: CalendarTitleType = CalendarTitleType.SERIES
    ) = CalendarTitleRecord(
        key = key, type = type, ids = CalendarIds(imdb = key), name = key, status = status,
        sources = mapOf(CalendarSource.TVMAZE to entries.toList())
    )

    private fun at(instant: String, season: Int = 1, episode: Int = 1) =
        CalendarEntry(season = season, episode = episode, airInstantMs = Instant.parse(instant).toEpochMilli())

    private fun on(date: String, season: Int = 1, episode: Int = 1) =
        CalendarEntry(season = season, episode = episode, airDate = date, source = CalendarSource.TMDB)

    private fun List<CalendarSection>.dayOf(key: String): LocalDate? =
        flatMap { it.items }.firstOrNull { it.title.key == key }?.date

    @Test fun aUsNinePmEasternShowLandsOnTheNextDayInSydney() {
        // Mon 5 Oct 21:00 EDT = Tue 6 Oct 01:00 UTC = Tue 6 Oct 12:00 AEDT.
        val entry = at("2026-10-06T01:00:00Z")
        val sections = buildCalendarSections(listOf(title("tt1", entry)), now, sydney)
        val today = sections.single()
        assertEquals(CalendarSectionKind.TODAY, today.kind)
        assertEquals(LocalDate.of(2026, 10, 6), today.date)
        assertEquals(LocalTime.of(12, 0), today.items.single().time)

        val inNewYork = buildCalendarSections(listOf(title("tt1", entry)), now, newYork)
        assertEquals(LocalDate.of(2026, 10, 5), inNewYork.dayOf("tt1"))
        assertEquals(LocalTime.of(21, 0), inNewYork.single().items.single().time)
    }

    @Test fun dayBoundariesFollowTheDaylightSavingChangeInSydney() {
        val titles = listOf(
            title("before", at("2026-10-03T13:59:00Z")), // Sat 3 Oct 23:59 AEST
            title("earlySunday", at("2026-10-03T15:30:00Z")), // Sun 4 Oct 01:30 AEST
            title("afterChange", at("2026-10-03T16:30:00Z")), // Sun 4 Oct 03:30 AEDT
            title("lateSunday", at("2026-10-04T12:59:00Z")), // Sun 4 Oct 23:59 AEDT
            title("mondayStart", at("2026-10-04T13:30:00Z")) // Mon 5 Oct 00:30 AEDT, 23:30 Sunday at the old offset
        )
        val sections = buildCalendarSections(titles, now, sydney)
        assertEquals(LocalDate.of(2026, 10, 3), sections.dayOf("before"))
        assertEquals(LocalDate.of(2026, 10, 4), sections.dayOf("earlySunday"))
        assertEquals(LocalDate.of(2026, 10, 4), sections.dayOf("afterChange"))
        assertEquals(LocalDate.of(2026, 10, 4), sections.dayOf("lateSunday"))
        assertEquals(LocalDate.of(2026, 10, 5), sections.dayOf("mondayStart"))
        val times = sections.first { it.kind == CalendarSectionKind.JUST_AIRED }.items.associate { it.title.key to it.time }
        assertEquals(LocalTime.of(1, 30), times["earlySunday"])
        assertEquals(LocalTime.of(3, 30), times["afterChange"])
        assertEquals(LocalTime.of(0, 30), times["mondayStart"])
    }

    @Test fun justAiredHoldsTheSevenDaysBeforeTodayNewestFirst() {
        val titles = listOf(
            title("tooOld", on("2026-09-28")),
            title("sevenDays", on("2026-09-29")),
            title("yesterday", at("2026-10-05T09:00:00Z")),
            title("twoDays", at("2026-10-04T09:00:00Z"))
        )
        val sections = buildCalendarSections(titles, now, sydney)
        assertEquals(listOf(CalendarSectionKind.JUST_AIRED, CalendarSectionKind.NO_DATE), sections.map { it.kind })
        val justAired = sections.first()
        assertEquals(listOf("yesterday", "twoDays", "sevenDays"), justAired.items.map { it.title.key })
    }

    @Test fun aPlainDateStaysOnItsDateAndHasNoTime() {
        val sections = buildCalendarSections(listOf(title("tt1", on("2026-10-07"))), now, sydney)
        val tomorrow = sections.single()
        assertEquals(CalendarSectionKind.TOMORROW, tomorrow.kind)
        assertNull(tomorrow.items.single().time)
        val sameInNewYork = buildCalendarSections(listOf(title("tt1", on("2026-10-07"))), now, newYork)
        assertEquals(LocalDate.of(2026, 10, 7), sameInNewYork.dayOf("tt1"))
    }

    @Test fun withinADayTimesComeFirstInOrderThenReleasesWithoutATime() {
        val titles = listOf(
            title("noTime", on("2026-10-08")),
            title("evening", at("2026-10-08T09:30:00Z")), // 20:30
            title("noon", at("2026-10-08T01:00:00Z")) // 12:00
        )
        val day = buildCalendarSections(titles, now, sydney).single()
        assertEquals(CalendarSectionKind.DAY, day.kind)
        assertEquals(LocalDate.of(2026, 10, 8), day.date)
        assertEquals(listOf("noon", "evening", "noTime"), day.items.map { it.title.key })
    }

    @Test fun laterHoldsEachTitlesNextReleaseAfterTheWindowAndNoDateHoldsRunningShowsWithNothingDated() {
        val titles = listOf(
            title("inWindow", on("2026-10-15"), on("2026-11-20", episode = 2)),
            title("beyond", on("2026-11-01"), on("2026-11-08", episode = 2), on("2026-11-15", episode = 3)),
            title("tooFar", on("2027-12-01")),
            title("returning"),
            title("inProduction", status = CalendarShowStatus.IN_PRODUCTION),
            title("ended", status = CalendarShowStatus.ENDED),
            title("unknown", status = CalendarShowStatus.UNKNOWN),
            title("aired", on("2026-10-02"))
        )
        val sections = buildCalendarSections(titles, now, sydney, windowDays = 14)
        assertEquals(
            listOf(CalendarSectionKind.JUST_AIRED, CalendarSectionKind.DAY, CalendarSectionKind.LATER, CalendarSectionKind.NO_DATE),
            sections.map { it.kind }
        )
        val later = sections.first { it.kind == CalendarSectionKind.LATER }
        assertEquals(listOf("beyond" to 1, "inWindow" to 2), later.items.map { it.title.key to it.entry!!.episode })
        val noDate = sections.first { it.kind == CalendarSectionKind.NO_DATE }
        assertEquals(listOf("aired", "inProduction", "returning"), noDate.items.map { it.title.key })
        assertTrue(noDate.items.all { it.entry == null })
    }

    @Test fun theWindowCountsTodayAsItsFirstDay() {
        val titles = listOf(title("day30", on("2026-11-04")), title("day31", on("2026-11-05")))
        val sections = buildCalendarSections(titles, now, sydney, windowDays = 30)
        assertEquals(CalendarSectionKind.DAY, sections.first { it.items.any { item -> item.title.key == "day30" } }.kind)
        assertEquals(CalendarSectionKind.LATER, sections.first { it.items.any { item -> item.title.key == "day31" } }.kind)
    }

    @Test fun filmsSlotInByDate() {
        val film = CalendarTitleRecord(
            key = "movie:imdb:tt5", type = CalendarTitleType.MOVIE, ids = CalendarIds(imdb = "tt5"), name = "Blue Hour",
            sources = mapOf(
                CalendarSource.TMDB to listOf(
                    CalendarEntry(kind = CalendarEntryKind.MOVIE_THEATRICAL, airDate = "2026-10-08", source = CalendarSource.TMDB),
                    CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = "2026-12-20", source = CalendarSource.TMDB)
                )
            )
        )
        val sections = buildCalendarSections(listOf(film, title("tt1", at("2026-10-08T01:00:00Z"))), now, sydney)
        val thursday = sections.first { it.date == LocalDate.of(2026, 10, 8) }
        assertEquals(listOf("tt1", "movie:imdb:tt5"), thursday.items.map { it.title.key })
        val later = sections.first { it.kind == CalendarSectionKind.LATER }
        assertEquals(CalendarEntryKind.MOVIE_DIGITAL, later.items.single().entry!!.kind)
        assertTrue(sections.none { it.kind == CalendarSectionKind.NO_DATE })
    }

    @Test fun plainDatesAndTimesAcrossTheUsChangeBackInNewYork() {
        // Clocks go back at 02:00 EDT on Sun 1 Nov 2026, so 01:30 happens twice.
        val fridayNoon = Instant.parse("2026-10-30T16:00:00Z")
        val titles = listOf(
            title("saturday", on("2026-10-31")),
            title("sunday", on("2026-11-01")),
            title("monday", on("2026-11-02")),
            title("firstHalfPastOne", at("2026-11-01T05:30:00Z")),
            title("secondHalfPastOne", at("2026-11-01T06:30:00Z")),
            title("lateSunday", at("2026-11-02T04:30:00Z"))
        )
        val sections = buildCalendarSections(titles, fridayNoon, newYork)
        assertEquals(LocalDate.of(2026, 10, 31), sections.dayOf("saturday"))
        assertEquals(LocalDate.of(2026, 11, 1), sections.dayOf("sunday"))
        assertEquals(LocalDate.of(2026, 11, 2), sections.dayOf("monday"))
        assertEquals(LocalDate.of(2026, 11, 1), sections.dayOf("firstHalfPastOne"))
        assertEquals(LocalDate.of(2026, 11, 1), sections.dayOf("secondHalfPastOne"))
        assertEquals(LocalDate.of(2026, 11, 1), sections.dayOf("lateSunday"))
        val sunday = sections.first { it.date == LocalDate.of(2026, 11, 1) }
        assertEquals(listOf("firstHalfPastOne", "secondHalfPastOne", "lateSunday", "sunday"), sunday.items.map { it.title.key })
        assertEquals(LocalTime.of(23, 30), sunday.items[2].time)
        assertEquals(CalendarSectionKind.TOMORROW, sections.first { it.date == LocalDate.of(2026, 10, 31) }.kind)
    }
}
