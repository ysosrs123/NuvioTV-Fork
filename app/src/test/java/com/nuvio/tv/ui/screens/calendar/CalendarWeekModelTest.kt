package com.nuvio.tv.ui.screens.calendar

import com.nuvio.tv.core.calendar.CalendarTitleType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarWeekModelTest {
    private val monday = LocalDate.of(2026, 10, 19)

    private fun episode(show: String, s: Int, e: Int, date: LocalDate, time: LocalTime? = LocalTime.of(20, 0)) = CalendarRow(
        key = "series:imdb:$show|S${s}E$e",
        titleKey = "series:imdb:$show",
        name = show,
        poster = null,
        contentId = show,
        type = CalendarTitleType.SERIES,
        kind = CalendarRowKind.EPISODE,
        season = s,
        episode = e,
        date = date,
        time = time
    )

    private fun film(name: String, date: LocalDate) = CalendarRow(
        key = "movie:imdb:$name|MOVIE_DIGITAL",
        titleKey = "movie:imdb:$name",
        name = name,
        poster = null,
        contentId = name,
        type = CalendarTitleType.MOVIE,
        kind = CalendarRowKind.FILM_DIGITAL,
        date = date
    )

    @Test fun theWeekStartsOnTheChosenFirstDay() {
        assertEquals(monday, calendarWeekStart(monday.plusDays(3), DayOfWeek.MONDAY))
        assertEquals(monday.minusDays(1), calendarWeekStart(monday.plusDays(3), DayOfWeek.SUNDAY))
        assertEquals(monday, calendarWeekStart(monday, DayOfWeek.MONDAY))
    }

    @Test fun episodesOfOneShowAtOneTimeShareACard() {
        val rows = listOf(
            episode("tt1", 2, 3, monday),
            episode("tt1", 2, 1, monday),
            episode("tt1", 2, 2, monday),
            episode("tt2", 1, 4, monday),
            episode("tt1", 2, 4, monday, LocalTime.of(22, 0))
        )
        val cards = calendarCards(rows)
        assertEquals(3, cards.size)
        assertEquals(3, cards[0].count)
        assertEquals(1, cards[0].row.episode)
        assertEquals(3, cards[0].lastEpisode)
        assertNull(cards[1].lastEpisode)
        assertEquals(1, cards[2].count)
    }

    @Test fun aRunAcrossSeasonsHasNoRange() {
        val card = calendarCards(listOf(episode("tt1", 1, 10, monday), episode("tt1", 2, 1, monday))).single()
        assertEquals(2, card.count)
        assertNull(card.lastEpisode)
    }

    @Test fun theWeekHasSevenDaysAndTheFilterNarrowsThem() {
        val days = mapOf(
            monday to listOf(episode("tt1", 1, 1, monday), film("f1", monday)),
            monday.plusDays(2) to listOf(film("f2", monday.plusDays(2))),
            monday.plusDays(8) to listOf(episode("tt3", 1, 1, monday.plusDays(8)))
        )
        val all = buildCalendarWeek(days, monday, CalendarFilter.ALL)
        assertEquals(7, all.size)
        assertEquals(listOf(2, 0, 1, 0, 0, 0, 0), all.map { it.cards.size })
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 0), buildCalendarWeek(days, monday, CalendarFilter.SHOWS).map { it.cards.size })
        assertEquals(listOf(1, 0, 1, 0, 0, 0, 0), buildCalendarWeek(days, monday, CalendarFilter.FILMS).map { it.cards.size })
        assertEquals(1, buildCalendarWeek(days, monday.plusWeeks(1), CalendarFilter.ALL)[1].cards.size)
    }

    @Test fun dotsStopAtFour() {
        val rows = (1..6).map { episode("tt$it", 1, 1, monday) }
        assertEquals(4, buildCalendarWeek(mapOf(monday to rows), monday, CalendarFilter.ALL).first().dots)
    }

    @Test fun theWeekOpensOnItsFirstCard() {
        val days = mapOf(
            monday.plusDays(1) to listOf(episode("tt1", 1, 1, monday.plusDays(1)), episode("tt3", 1, 1, monday.plusDays(1))),
            monday.plusDays(3) to listOf(episode("tt2", 1, 1, monday.plusDays(3)))
        )
        assertEquals("series:imdb:tt1|S1E1", calendarWeekOpeningKey(buildCalendarWeek(days, monday, CalendarFilter.ALL)))
        assertNull(calendarWeekOpeningKey(buildCalendarWeek(emptyMap(), monday, CalendarFilter.ALL)))
    }

    @Test fun nextFromListsTheShowsLaterEpisodes() {
        val e8 = episode("tt1", 2, 8, monday)
        val days = mapOf(
            monday to listOf(e8, episode("tt2", 1, 1, monday)),
            monday.plusWeeks(1) to listOf(episode("tt1", 2, 9, monday.plusWeeks(1))),
            monday.plusWeeks(2) to listOf(episode("tt1", 2, 10, monday.plusWeeks(2))),
            monday.plusWeeks(3) to listOf(episode("tt1", 2, 11, monday.plusWeeks(3))),
            monday.minusWeeks(1) to listOf(episode("tt1", 2, 7, monday.minusWeeks(1)))
        )
        val next = calendarNextFrom(calendarRowsByTitle(days), e8)
        assertEquals(listOf(9, 10), next.map { it.episode })
        assertTrue(calendarNextFrom(calendarRowsByTitle(days), episode("tt1", 2, 11, monday.plusWeeks(3))).isEmpty())
    }

    @Test fun theMosaicFollowsHowManyTitlesAreOn() {
        fun mosaic(n: Int) = calendarMosaic((1..n).map { episode("tt$it", 1, 1, monday) })
        assertEquals(CalendarMosaicLayout.NONE, calendarMosaic(null).layout)
        assertEquals(CalendarMosaicLayout.FULL, mosaic(1).layout)
        assertEquals(CalendarMosaicLayout.SPLIT, mosaic(2).layout)
        assertEquals(CalendarMosaicLayout.GRID, mosaic(3).layout)
        assertEquals(4, mosaic(4).tiles.size)
        assertEquals(0, mosaic(4).more)
        assertEquals(3, mosaic(6).tiles.size)
        assertEquals(3, mosaic(6).more)
    }

    @Test fun aShowWithSeveralEpisodesTakesOneTile() {
        val rows = listOf(episode("tt1", 1, 1, monday), episode("tt1", 1, 2, monday), episode("tt2", 1, 1, monday))
        assertEquals(CalendarMosaicLayout.SPLIT, calendarMosaic(rows).layout)
    }

    @Test
    fun `a show that streams first says Streaming where the time would be`() {
        val dexter = episode("tt1", 1, 2, LocalDate.of(2026, 11, 13), time = null)
        assertEquals(com.nuvio.tv.R.string.calendar_time_not_announced, calendarNoTimeLabel(dexter))
        assertEquals(com.nuvio.tv.R.string.calendar_streaming, calendarNoTimeLabel(dexter.copy(streaming = true)))
    }
}
