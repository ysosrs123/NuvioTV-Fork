package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarHomeTest {
    private val sydney = ZoneId.of("Australia/Sydney")

    /** Tuesday 6 October 2026, 10:00 in Sydney. */
    private val now = Instant.parse("2026-10-05T23:00:00Z")

    private fun show(id: String, vararg entries: CalendarEntry, name: String = id) = CalendarTitleRecord(
        key = "series:imdb:$id", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = id), name = name,
        sources = mapOf(CalendarSource.TVMAZE to entries.toList())
    )

    private fun film(id: String, vararg entries: CalendarEntry) = CalendarTitleRecord(
        key = "movie:imdb:$id", type = CalendarTitleType.MOVIE, ids = CalendarIds(imdb = id), name = id,
        sources = mapOf(CalendarSource.TMDB to entries.toList())
    )

    private fun at(local: String, episode: Int = 1, season: Int = 1) = CalendarEntry(
        season = season, episode = episode,
        airInstantMs = java.time.LocalDateTime.parse(local).atZone(sydney).toInstant().toEpochMilli()
    )

    private fun ep(airedAgo: Duration, episode: Int = 1) =
        CalendarEntry(season = 1, episode = episode, airInstantMs = now.minus(airedAgo).toEpochMilli())

    @Test fun settledRefreshOncePerStartThenAfterSixHoursAway() {
        val sixHours = Duration.ofHours(6).toMillis()
        assertTrue(calendarSettledRefreshDue(null, backgroundedSince = false, nowMs = 0))
        assertFalse(calendarSettledRefreshDue(0, backgroundedSince = false, nowMs = sixHours * 2))
        assertFalse(calendarSettledRefreshDue(0, backgroundedSince = true, nowMs = sixHours - 1))
        assertTrue(calendarSettledRefreshDue(0, backgroundedSince = true, nowMs = sixHours))
    }

    @Test fun nextReminderIsTheEarliestFutureDueOfABellTitle() {
        val belled = show("tt1", ep(Duration.ofHours(2), 1), ep(Duration.ofMinutes(-30), 2), ep(Duration.ofMinutes(-10), 3))
        val quiet = show("tt2", ep(Duration.ofMinutes(-1), 1))
        val bells = setOf(belled.key)
        assertEquals(now.plus(Duration.ofMinutes(70)), calendarNextReminderAt(listOf(belled, quiet), bells, emptySet(), now))
        val seen = setOf(belled.episodeKey(belled.entries.first { it.episode == 3 }))
        assertEquals(now.plus(Duration.ofMinutes(90)), calendarNextReminderAt(listOf(belled, quiet), bells, seen, now))
        assertNull(calendarNextReminderAt(listOf(quiet), bells, emptySet(), now))
    }

    @Test fun aCinemaDateNeverSetsAReminderCheck() {
        val cinema = film("tt9", CalendarEntry(kind = CalendarEntryKind.MOVIE_THEATRICAL, airDate = "2026-10-08"))
        assertNull(calendarNextReminderAt(listOf(cinema), setOf(cinema.key), emptySet(), now))
    }

    @Test fun checksAreAtLeastAMinuteApartAndAtMostSixHours() {
        assertEquals(Duration.ofMinutes(1), calendarReminderCheckDelay(now.plusSeconds(10), now))
        assertEquals(Duration.ofMinutes(1), calendarReminderCheckDelay(now.minusSeconds(10), now))
        assertEquals(Duration.ofMinutes(30), calendarReminderCheckDelay(now.plus(Duration.ofMinutes(30)), now))
        assertEquals(Duration.ofHours(6), calendarReminderCheckDelay(now.plus(Duration.ofHours(10)), now))
        assertEquals(Duration.ofHours(6), calendarReminderCheckDelay(null, now))
    }

    @Test fun theCardListsThreeNewestAndCountsTheRest() {
        val title = show("tt1", *(1..5).map { ep(Duration.ofHours(2L + it), it) }.toTypedArray())
        val due = dueReminders(listOf(title), setOf(title.key), emptySet(), { _, _ -> false }, now)
        val card = calendarReminderCard(due, sydney, now)!!
        assertEquals(listOf(1, 2, 3), card.items.map { it.entry.episode })
        assertEquals(2, card.more)
        assertEquals(5, card.total)
        assertEquals(5, card.episodeKeys.size)
        assertFalse(card.single)
    }

    @Test fun oneEpisodeMakesTheSingleCardAndNothingMakesNone() {
        val title = show("tt1", ep(Duration.ofHours(2)))
        val due = dueReminders(listOf(title), setOf(title.key), emptySet(), { _, _ -> false }, now)
        assertTrue(calendarReminderCard(due, sydney, now)!!.single)
        assertNull(calendarReminderCard(CalendarDueReminders(), sydney, now))
    }

    @Test fun theCornerMessageGroupsShowNames() {
        val a = show("tt1", ep(Duration.ofHours(2), 1), ep(Duration.ofHours(2), 2), name = "The Harbour")
        val b = show("tt2", ep(Duration.ofHours(2)), name = "Low Tide")
        val due = dueReminders(listOf(a, b), setOf(a.key, b.key), emptySet(), { _, _ -> false }, now)
        val message = CalendarCornerMessage(1, due.items)
        assertEquals(3, message.count)
        assertEquals(setOf("The Harbour", "Low Tide"), message.names.toSet())
        assertEquals(2, message.names.size)
    }

    @Test fun comingUpPutsNewFirstNewestFirstThenTheSoonest() {
        val titles = listOf(
            show("tt1", at("2026-10-04T20:00"), name = "Older out"),
            show("tt2", at("2026-10-06T08:00"), name = "Newer out"),
            show("tt3", at("2026-10-09T20:00"), name = "Friday"),
            show("tt4", at("2026-10-06T20:30"), name = "Tonight"),
            show("tt5", at("2026-10-07T12:00"), name = "Tomorrow")
        )
        val items = buildCalendarComingUp(titles, null, now, sydney)
        assertEquals(listOf("Newer out", "Older out", "Tonight", "Tomorrow", "Friday"), items.map { it.title.name })
        assertEquals(
            listOf(
                CalendarComingUpWhen.NEW, CalendarComingUpWhen.NEW, CalendarComingUpWhen.TONIGHT,
                CalendarComingUpWhen.TOMORROW, CalendarComingUpWhen.WEEKDAY
            ),
            items.map { it.whenKind }
        )
    }

    @Test fun comingUpLabelKinds() {
        val titles = listOf(
            show("tt1", at("2026-10-06T11:00"), name = "a"),
            show("tt2", at("2026-10-06T17:00"), name = "b"),
            show("tt3", at("2026-10-12T09:00"), name = "c"),
            show("tt4", at("2026-10-13T09:00"), name = "d"),
            show("tt5", CalendarEntry(season = 1, episode = 1, airDate = "2026-10-07"), name = "e")
        )
        val kinds = buildCalendarComingUp(titles, null, now, sydney).associate { it.title.name to it.whenKind }
        assertEquals(CalendarComingUpWhen.TODAY, kinds["a"])
        assertEquals(CalendarComingUpWhen.TONIGHT, kinds["b"])
        assertEquals(CalendarComingUpWhen.WEEKDAY, kinds["c"])
        assertEquals(CalendarComingUpWhen.DATE, kinds["d"])
        assertEquals(CalendarComingUpWhen.TOMORROW, kinds["e"])
    }

    @Test fun newIsTheLastSevenDaysAndNotWatched() {
        val old = show("tt1", at("2026-09-28T20:00"))
        val watchedShow = show("tt2", at("2026-10-05T20:00"))
        val dateOnly = show("tt3", CalendarEntry(season = 2, episode = 1, airDate = "2026-10-04"))
        val watched = CalendarWatchedIndex.Builder().add("tt2", 1, 1).build()
        val items = buildCalendarComingUp(listOf(old, watchedShow, dateOnly), watched, now, sydney)
        assertEquals(listOf("tt3"), items.map { it.title.name })
        assertEquals(CalendarComingUpWhen.NEW, items.single().whenKind)
    }

    @Test fun oneCardPerTitleAndNewWins() {
        val title = show("tt1", at("2026-10-05T20:00", 1), at("2026-10-06T08:00", 2), at("2026-10-12T20:00", 3))
        val item = buildCalendarComingUp(listOf(title), null, now, sydney).single()
        assertEquals(2, item.entry.episode)
        assertEquals(CalendarComingUpWhen.NEW, item.whenKind)
    }

    @Test fun anEpisodeContinueWatchingShowsIsLeftOut() {
        val title = show("tt1", at("2026-10-06T08:00", 4), at("2026-10-13T08:00", 5)).copy(ids = CalendarIds(imdb = "tt1", tmdb = 77))
        val keys = calendarContinueWatchingKeys(listOf(Triple("tmdb:77", 1, 4)))
        val item = buildCalendarComingUp(listOf(title), null, now, sydney, { t, e -> calendarEntryInKeys(keys, t, e) }).single()
        assertEquals(5, item.entry.episode)
        assertEquals(CalendarComingUpWhen.DATE, item.whenKind)
    }

    @Test fun cinemaDatesOnlyBeforeTheyOpenAndAtMostFifteenCards() {
        val opened = film("tt8", CalendarEntry(kind = CalendarEntryKind.MOVIE_THEATRICAL, airDate = "2026-10-02"))
        val coming = film("tt9", CalendarEntry(kind = CalendarEntryKind.MOVIE_THEATRICAL, airDate = "2026-10-15"))
        assertEquals(listOf("tt9"), buildCalendarComingUp(listOf(opened, coming), null, now, sydney).map { it.title.name })

        val many = (1..20).map { show("tt$it", at("2026-10-${(10 + it % 9).toString().padStart(2, '0')}T20:00")) }
        val row = buildCalendarComingUp(many, null, now, sydney)
        assertEquals(15, row.size)
        assertEquals(row.sortedBy { it.date }, row)
        assertTrue(row.all { it.date >= LocalDate.of(2026, 10, 10) })
    }

    @Test fun contentIdsPreferTheAppsOwnId() {
        val title = show("tt1").copy(contentId = "tmdb:5", ids = CalendarIds(imdb = "tt1", tmdb = 5))
        assertEquals(listOf("tmdb:5", "tt1"), calendarContentIds(title))
    }

    @Test fun aPlainDateIsOutOnceThatDateHasBegunEverywhere() {
        val entry = CalendarEntry(season = 1, episode = 1, airDate = "2026-10-05")
        assertFalse(entry.isOut(Instant.parse("2026-10-05T23:59:59Z")))
        assertTrue(entry.isOut(Instant.parse("2026-10-06T00:00:00Z")))
        assertTrue(CalendarEntry(airInstantMs = now.toEpochMilli()).isOut(now))
        assertFalse(CalendarEntry(airInstantMs = now.plusSeconds(1).toEpochMilli()).isOut(now))
    }

    @Test fun aPlainDateNotOutYetIsTodayNotNew() {
        // 5 Oct as a plain date is still 5 Oct in the US at 10:00 on 6 Oct in Sydney.
        val title = show("tt1", CalendarEntry(season = 6, episode = 1, airDate = "2026-10-05"))
        val item = buildCalendarComingUp(listOf(title), null, now, sydney).single()
        assertEquals(CalendarComingUpWhen.TODAY, item.whenKind)
        assertEquals(LocalDate.of(2026, 10, 6), item.date)

        val sections = buildCalendarSections(listOf(title), now, sydney)
        assertEquals(listOf(CalendarSectionKind.TODAY), sections.map { it.kind })
        val later = buildCalendarSections(listOf(title), Instant.parse("2026-10-06T01:00:00Z"), sydney)
        assertEquals(CalendarSectionKind.JUST_AIRED, later.first().kind)
    }

    @Test fun placeholderEpisodeNamesAreNotShown() {
        assertNull(CalendarEntry(title = "TBA").shownTitle)
        assertNull(CalendarEntry(title = " tbd ").shownTitle)
        assertNull(CalendarEntry(title = "").shownTitle)
        assertEquals("Graveyard", CalendarEntry(title = "Graveyard").shownTitle)
    }
}
