package com.nuvio.tv.ui.screens.calendar

import com.nuvio.tv.core.calendar.CalendarEntry
import com.nuvio.tv.core.calendar.CalendarEntryKind
import com.nuvio.tv.core.calendar.CalendarEpisodeLabel
import com.nuvio.tv.core.calendar.CalendarIds
import com.nuvio.tv.core.calendar.CalendarSectionKind
import com.nuvio.tv.core.calendar.CalendarSettings
import com.nuvio.tv.core.calendar.CalendarShowStatus
import com.nuvio.tv.core.calendar.CalendarSource
import com.nuvio.tv.core.calendar.CalendarTitleRecord
import com.nuvio.tv.core.calendar.CalendarTitleType
import com.nuvio.tv.core.calendar.CalendarWatchedIndex
import com.nuvio.tv.core.calendar.buildCalendarSections
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarUiModelTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    /** Tue 6 Oct 2026, 10:00 in Sydney. */
    private val now = Instant.parse("2026-10-05T23:00:00Z")
    private val today = LocalDate.of(2026, 10, 6)

    private fun show(
        imdb: String,
        vararg entries: CalendarEntry,
        type: CalendarTitleType = CalendarTitleType.SERIES,
        status: CalendarShowStatus = CalendarShowStatus.RUNNING
    ) = CalendarTitleRecord(
        key = "${type.name.lowercase()}:imdb:$imdb", type = type, ids = CalendarIds(imdb = imdb), name = imdb,
        contentId = imdb, status = status, sources = mapOf(CalendarSource.TVMAZE to entries.toList())
    )

    private fun at(instant: String, season: Int = 1, episode: Int = 1, label: CalendarEpisodeLabel = CalendarEpisodeLabel.NONE) =
        CalendarEntry(season = season, episode = episode, airInstantMs = Instant.parse(instant).toEpochMilli(), label = label)

    private fun watched(vararg keys: String) = CalendarWatchedIndex.Builder().apply {
        keys.forEach { add(it.substringBefore('|'), it.substringAfter('|').substringBefore('|').toInt(), it.substringAfterLast('|').toInt()) }
    }.build()

    private fun status(title: CalendarTitleRecord, bells: Set<String> = emptySet(), index: CalendarWatchedIndex? = null) =
        title.entries.first().let { entry ->
            calendarRowStatus(title, entry, now, bells, index)
        }

    @Test fun rowsShowTheNetworkWithoutAClosingFullStop() {
        val title = show("tt1", at("2026-10-06T09:30:00Z")).copy(network = "Life Style.")
        val row = calendarRow(title, title.entries.first(), today, null, CalendarSectionKind.TODAY, now, today, emptySet(), null)
        assertEquals("Life Style", row.network)
    }

    @Test fun upcomingEpisodesShowTheBellOnlyWhenItIsOnForTheShow() {
        val title = show("tt1", at("2026-10-06T09:30:00Z"))
        assertEquals(CalendarRowStatus.BELL_OFF, status(title))
        assertEquals(CalendarRowStatus.BELL_ON, status(title, bells = setOf("series:imdb:tt1")))
        assertEquals(CalendarRowStatus.BELL_ON, status(title.copy(ids = CalendarIds(imdb = "tt1", tmdb = 9)), bells = setOf("series:tmdb:9")))
    }

    @Test fun airedEpisodesShowATickWhenWatchedAndADotWhenNot() {
        val title = show("tt1", at("2026-10-05T10:00:00Z", season = 2, episode = 6))
        assertEquals(CalendarRowStatus.NOT_WATCHED, status(title, bells = setOf("series:imdb:tt1")))
        assertEquals(CalendarRowStatus.WATCHED, status(title, index = watched("tt1|2|6")))
        assertEquals(CalendarRowStatus.NOT_WATCHED, status(title, index = watched("tt1|2|5")))
    }

    @Test fun aPlainDateCountsAsOutOnceItHasBegunEverywhereAndACinemaDateHasNoBell() {
        val todayOnly = show("tt2", CalendarEntry(season = 1, episode = 1, airDate = "2026-10-06"))
        assertEquals(CalendarRowStatus.BELL_OFF, status(todayOnly))
        val yesterdayOnly = show("tt2", CalendarEntry(season = 1, episode = 1, airDate = "2026-10-04"))
        assertEquals(CalendarRowStatus.NOT_WATCHED, status(yesterdayOnly))
        val tomorrowOnly = show("tt2", CalendarEntry(season = 1, episode = 1, airDate = "2026-10-07"))
        assertEquals(CalendarRowStatus.BELL_OFF, status(tomorrowOnly))
        val cinema = show("tt3", CalendarEntry(kind = CalendarEntryKind.MOVIE_THEATRICAL, airDate = "2026-10-20"), type = CalendarTitleType.MOVIE)
        assertEquals(CalendarRowStatus.NONE, status(cinema))
        val digital = show("tt3", CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = "2026-10-20"), type = CalendarTitleType.MOVIE)
        assertEquals(CalendarRowStatus.BELL_OFF, status(digital))
    }

    @Test fun rowsCarryKindLabelAndOnlyWatchedRowsAreDimmed() {
        val titles = listOf(
            show("tt1", at("2026-10-05T10:00:00Z", 2, 6), at("2026-10-06T09:30:00Z", 2, 7, CalendarEpisodeLabel.SEASON_FINALE)),
            show("tt5", CalendarEntry(kind = CalendarEntryKind.MOVIE_THEATRICAL, airDate = "2026-10-08"), type = CalendarTitleType.MOVIE)
        )
        val list = buildCalendarList(buildCalendarSections(titles, now, sydney), now, sydney, emptySet(), watched("tt1|2|6"))
        val rows = list.entries.filterIsInstance<CalendarRowEntry>().map { it.row }
        val aired = rows.single { it.episode == 6 }
        assertEquals(CalendarSectionKind.JUST_AIRED, aired.section)
        assertTrue(aired.dimmed)
        val finale = rows.single { it.episode == 7 }
        assertEquals(CalendarEpisodeLabel.SEASON_FINALE, finale.label)
        assertEquals(CalendarRowKind.EPISODE, finale.kind)
        assertFalse(finale.dimmed)
        val film = rows.single { it.isFilm }
        assertEquals(CalendarRowKind.FILM_CINEMA, film.kind)
        assertNull(film.season)
        assertEquals("movie", film.apiType)
    }

    @Test fun listHasOneHeadingPerSectionUniqueKeysAndOpensOnToday() {
        val titles = listOf(
            show("tt1", at("2026-10-05T10:00:00Z"), at("2026-10-06T09:30:00Z", 1, 2), at("2026-12-20T09:30:00Z", 1, 9)),
            show("tt2", at("2026-10-07T09:30:00Z")),
            show("tt3", status = CalendarShowStatus.IN_PRODUCTION)
        )
        val list = buildCalendarList(buildCalendarSections(titles, now, sydney), now, sydney, emptySet(), null)
        val headings = list.entries.filterIsInstance<CalendarHeaderEntry>().map { it.kind }
        assertEquals(
            listOf(CalendarSectionKind.JUST_AIRED, CalendarSectionKind.TODAY, CalendarSectionKind.TOMORROW, CalendarSectionKind.LATER, CalendarSectionKind.NO_DATE),
            headings
        )
        assertEquals(list.entries.size, list.entries.map { it.key }.distinct().size)
        assertEquals("series:imdb:tt1|S1E2", list.startRowKey)
        val noDate = list.entries.filterIsInstance<CalendarRowEntry>().last().row
        assertEquals(CalendarRowKind.SHOW, noDate.kind)
        assertEquals(CalendarRowStatus.BELL_OFF, noDate.status)
    }

    @Test fun aDayInTheListFindsItsFirstRowOrTheNextOne() {
        val titles = listOf(show("tt1", at("2026-10-06T09:30:00Z")), show("tt2", at("2026-10-09T09:30:00Z")))
        val list = buildCalendarList(buildCalendarSections(titles, now, sydney), now, sydney, emptySet(), null)
        assertEquals("series:imdb:tt2|S1E1", list.rowKeyFor(LocalDate.of(2026, 10, 9)))
        assertEquals("series:imdb:tt2|S1E1", list.rowKeyFor(LocalDate.of(2026, 10, 7)))
        assertEquals("series:imdb:tt1|S1E1", list.rowKeyFor(today))
    }

    @Test fun hidingARowMovesFocusToTheNextTitleOrTheOneBefore() {
        val titles = listOf(
            show("tt1", at("2026-10-06T09:30:00Z"), at("2026-10-07T09:30:00Z", 1, 2)),
            show("tt2", at("2026-10-08T09:30:00Z"))
        )
        val list = buildCalendarList(buildCalendarSections(titles, now, sydney), now, sydney, emptySet(), null)
        val rows = list.entries.filterIsInstance<CalendarRowEntry>().map { it.row }
        assertEquals("series:imdb:tt2|S1E1", list.neighbourOf(rows.first()))
        assertEquals("series:imdb:tt1|S1E2", list.neighbourOf(rows.last()))
    }

    @Test fun monthCellsCountEpisodesAndFilmsAndShowAtMostTwoPosters() {
        val titles = listOf(
            show("tt1", at("2026-10-08T09:30:00Z")),
            show("tt2", at("2026-10-08T01:00:00Z")),
            show("tt3", at("2026-10-08T05:00:00Z")),
            show("tt4", CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = "2026-10-08"), type = CalendarTitleType.MOVIE)
        ).map { it.copy(poster = "p-${it.name}") }
        val days = buildCalendarDays(titles, now, sydney, emptySet(), null)
        val rows = days.getValue(LocalDate.of(2026, 10, 8))
        assertEquals(listOf("tt2", "tt3", "tt1", "tt4"), rows.map { it.name })
        val summary = calendarDaySummary(rows)
        assertEquals(3, summary.episodes)
        assertEquals(1, summary.films)
        assertEquals(listOf("p-tt2", "p-tt3"), summary.posters)
        assertEquals(2, summary.more)
        assertTrue(calendarDaySummary(null).isEmpty)
    }

    @Test fun monthGridHasSixWeeksFromTheLocalesFirstDay() {
        val monday = calendarMonthGrid(YearMonth.of(2026, 10), DayOfWeek.MONDAY)
        assertEquals(42, monday.size)
        assertEquals(LocalDate.of(2026, 9, 28), monday.first())
        assertEquals(LocalDate.of(2026, 11, 8), monday.last())
        assertEquals(LocalDate.of(2026, 9, 27), calendarMonthGrid(YearMonth.of(2026, 10), DayOfWeek.SUNDAY).first())
    }

    @Test fun monthKeysMoveDaysLeaveAtTheEdgesAndTurnTheMonth() {
        val october = YearMonth.of(2026, 10)
        val move = { date: LocalDate, key: CalendarMonthKey -> calendarMonthMove(date, october, DayOfWeek.MONDAY, key) }
        assertEquals(CalendarMonthMove.To(LocalDate.of(2026, 10, 7)), move(today, CalendarMonthKey.RIGHT))
        assertEquals(CalendarMonthMove.To(LocalDate.of(2026, 10, 13)), move(today, CalendarMonthKey.DOWN))
        assertEquals(CalendarMonthMove.Leave(CalendarMonthKey.LEFT), move(LocalDate.of(2026, 10, 5), CalendarMonthKey.LEFT))
        assertEquals(CalendarMonthMove.Leave(CalendarMonthKey.RIGHT), move(LocalDate.of(2026, 10, 11), CalendarMonthKey.RIGHT))
        assertEquals(CalendarMonthMove.Leave(CalendarMonthKey.UP), move(LocalDate.of(2026, 10, 1), CalendarMonthKey.UP))
        assertEquals(CalendarMonthMove.To(LocalDate.of(2026, 9, 30)), move(LocalDate.of(2026, 10, 1), CalendarMonthKey.LEFT))
        assertEquals(CalendarMonthMove.To(LocalDate.of(2026, 11, 12)), move(LocalDate.of(2026, 11, 5), CalendarMonthKey.DOWN))
    }

    @Test fun calendarIsInTheMenuUnlessSwitchedOffAlsoBeforeTheSettingsAreRead() {
        assertTrue(calendarMenuVisible(null))
        assertTrue(calendarMenuVisible(CalendarSettings(profileId = 1)))
        assertFalse(calendarMenuVisible(CalendarSettings(profileId = 1, menuVisible = false)))
    }

    @Test fun theListOpensOnTodayAndTheHeadingNamesWhatIsNew() {
        val titles = listOf(
            show("tt1", at("2026-10-05T10:00:00Z")),
            show("tt2", at("2026-10-06T09:30:00Z")),
            show("tt3", at("2026-10-08T09:30:00Z"))
        )
        val list = buildCalendarList(buildCalendarSections(titles, now, sydney), now, sydney, emptySet(), null)
        assertEquals("series:imdb:tt2|S1E1", calendarOpeningRowKey(list))
        val noToday = buildCalendarList(buildCalendarSections(titles.take(1) + titles.last(), now, sydney), now, sydney, emptySet(), null)
        assertEquals("series:imdb:tt3|S1E1", calendarOpeningRowKey(noToday))

        val episode = com.nuvio.tv.core.calendar.CalendarReminder(titles[0], titles[0].entries.first(), now)
        val film = show("tt9", CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = "2026-10-01"), type = CalendarTitleType.MOVIE)
            .let { com.nuvio.tv.core.calendar.CalendarReminder(it, it.entries.first(), now) }
        assertEquals(com.nuvio.tv.R.plurals.calendar_reminder_title, calendarReminderTitleRes(listOf(episode)))
        assertEquals(com.nuvio.tv.R.plurals.calendar_reminder_title_films, calendarReminderTitleRes(listOf(film)))
        assertEquals(com.nuvio.tv.R.plurals.calendar_reminder_title_releases, calendarReminderTitleRes(listOf(episode, film)))
        assertEquals(com.nuvio.tv.R.string.calendar_reminder_title_one, calendarReminderSingleTitleRes(listOf(episode)))
        assertEquals(com.nuvio.tv.R.string.calendar_reminder_title_one_film, calendarReminderSingleTitleRes(listOf(film)))
        assertEquals(com.nuvio.tv.R.string.calendar_reminder_title_one_release, calendarReminderSingleTitleRes(listOf(episode, film)))
    }

    @Test fun placeholderNamesLeaveJustTheEpisodeNumbers() {
        val title = show("tt1", CalendarEntry(season = 6, episode = 1, title = "TBA", airInstantMs = Instant.parse("2026-10-08T09:30:00Z").toEpochMilli()))
        val row = buildCalendarList(buildCalendarSections(listOf(title), now, sydney), now, sydney, emptySet(), null)
            .entries.filterIsInstance<CalendarRowEntry>().single().row
        assertNull(row.episodeTitle)
    }
}
