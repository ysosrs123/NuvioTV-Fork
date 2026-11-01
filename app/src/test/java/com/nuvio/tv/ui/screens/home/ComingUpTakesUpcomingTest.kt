package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.calendar.CalendarComingUpWhen
import com.nuvio.tv.core.calendar.CalendarEntry
import com.nuvio.tv.core.calendar.CalendarIds
import com.nuvio.tv.core.calendar.CalendarSource
import com.nuvio.tv.core.calendar.CalendarTitleRecord
import com.nuvio.tv.core.calendar.CalendarTitleType
import com.nuvio.tv.core.calendar.CalendarWatchedIndex
import com.nuvio.tv.core.calendar.buildCalendarComingUp
import com.nuvio.tv.core.calendar.calendarContinueWatchingTitles
import com.nuvio.tv.core.calendar.calendarTitleInContinueWatching
import com.nuvio.tv.ui.screens.calendar.CalendarFormats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ComingUpTakesUpcomingTest {
    private val sydney = ZoneId.of("Australia/Sydney")

    /** Sunday 18 October 2026, 10:00 in Sydney. */
    private val now = Instant.parse("2026-10-17T23:00:00Z")

    private val formats = CalendarFormats.plain(Locale.UK, is24Hour = false)
    private val text = CalendarComingUpText(
        newLabel = "New",
        today = "Today",
        todayAt = "Today %1\$s",
        tonightAt = "Tonight %1\$s",
        tomorrow = "Tomorrow",
        cinema = "In cinemas",
        digital = "Streaming and digital release"
    )

    private fun show(id: String, vararg entries: CalendarEntry, name: String = id, backdrop: String? = null) =
        CalendarTitleRecord(
            key = "series:imdb:$id", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = id), name = name,
            contentId = id, backdrop = backdrop, sources = mapOf(CalendarSource.TVMAZE to entries.toList())
        )

    private fun on(date: String, episode: Int, season: Int = 1, title: String? = null) =
        CalendarEntry(season = season, episode = episode, title = title, airDate = date)

    private fun upcoming(
        contentId: String,
        season: Int,
        episode: Int,
        released: String?,
        name: String = contentId,
        backdrop: String? = null,
        contentType: String = "series"
    ) = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = contentId, contentType = contentType, name = name, poster = "poster-$contentId",
            backdrop = backdrop, logo = null, videoId = "$contentId:$season:$episode", season = season,
            episode = episode, episodeTitle = "Episode $episode", thumbnail = null, released = released,
            hasAired = false, lastWatched = 0L, sortTimestamp = 0L
        )
    )

    private fun row(titles: List<CalendarTitleRecord>, items: List<ContinueWatchingItem>, watched: CalendarWatchedIndex? = CalendarWatchedIndex()) =
        buildCalendarComingUp(calendarTitlesWithUpcoming(titles, items, sydney), watched, now, sydney)

    @Test fun anUpcomingAirTimeFallsOnTheViewersDayAndAPlainDateOnTheNetworksEvening() {
        val losAngeles = ZoneId.of("America/Los_Angeles")
        val timed = upcoming("tt200", 2, 3, "2026-10-20T01:00:00.000Z")
        assertEquals(LocalDate.of(2026, 10, 20), upcomingAirDate(timed, sydney))
        assertEquals(LocalDate.of(2026, 10, 19), upcomingAirDate(timed, losAngeles))
        assertEquals(LocalDate.of(2026, 10, 20), upcomingAirDate(upcoming("tt200", 2, 3, "2026-10-20T00:00:00.000Z"), losAngeles))

        val followed = show("tt200", on("2026-10-13", 2, season = 2)).copy(networkZone = "America/New_York")
        val added = calendarTitlesWithUpcoming(listOf(followed), listOf(upcoming("tt200", 2, 3, "2026-10-20")), sydney).single()
        val entry = added.entries.first { it.episode == 3 }
        assertEquals(LocalDate.of(2026, 10, 21), entry.localDate(sydney))
        assertEquals(LocalDate.of(2026, 10, 20), entry.localDate(losAngeles))
    }

    @Test fun aShowTheCalendarDoesNotFollowGetsItsOwnCard() {
        val items = row(emptyList(), listOf(upcoming("tt100", 1, 3, "2026-10-22", name = "VisionQuest", backdrop = "vq.jpg")))
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals(LocalDate.of(2026, 10, 22), item.date)
        assertEquals(CalendarComingUpWhen.WEEKDAY, item.whenKind)
        val card = calendarComingUpCard(item, text, formats)!!.info
        assertEquals("tt100", card.contentId)
        assertEquals("VisionQuest", card.name)
        assertEquals(3, card.episode)
        assertEquals("Episode 3", card.episodeTitle)
        assertEquals("vq.jpg", card.backdrop)
        assertEquals("Thu", card.badgeLabel)
    }

    @Test fun oneCardPerShowWithTheEarliestEpisode() {
        val calendar = show("tt100", on("2026-10-29", 4), name = "VisionQuest")
        val items = row(listOf(calendar), listOf(upcoming("tt100", 1, 3, "2026-10-22")))
        assertEquals(1, items.size)
        assertEquals(3, items.single().entry.episode)

        val later = row(listOf(show("tt100", on("2026-10-20", 4))), listOf(upcoming("tt100", 1, 5, "2026-10-27")))
        assertEquals(1, later.size)
        assertEquals(4, later.single().entry.episode)
    }

    @Test fun theCalendarsDateWinsForAnEpisodeItLists() {
        val calendar = show("tt100", on("2026-10-21", 3, title = "Reboot"))
        val items = row(listOf(calendar), listOf(upcoming("tt100", 1, 3, "2026-10-23")))
        assertEquals(1, items.size)
        assertEquals(LocalDate.of(2026, 10, 21), items.single().date)
        assertEquals("Reboot", items.single().entry.title)
    }

    @Test fun theCalendarsArtWinsAndTheUpcomingArtFillsAGap() {
        val withArt = calendarTitlesWithUpcoming(
            listOf(show("tt100", on("2026-10-21", 3), backdrop = "calendar.jpg")),
            listOf(upcoming("tt100", 1, 3, "2026-10-21", backdrop = "upcoming.jpg")),
            sydney
        ).single()
        assertEquals("calendar.jpg", withArt.backdrop)
        val without = calendarTitlesWithUpcoming(
            listOf(show("tt100", on("2026-10-21", 3))),
            listOf(upcoming("tt100", 1, 3, "2026-10-21", backdrop = "upcoming.jpg")),
            sydney
        ).single()
        assertEquals("upcoming.jpg", without.backdrop)
    }

    @Test fun aNewEpisodeKeepsTheShowsOnlyCard() {
        val calendar = show("tt100", on("2026-10-16", 2), on("2026-10-29", 4))
        val items = row(listOf(calendar), listOf(upcoming("tt100", 1, 3, "2026-10-22")))
        assertEquals(1, items.size)
        assertEquals(CalendarComingUpWhen.NEW, items.single().whenKind)
        assertEquals(2, items.single().entry.episode)
    }

    @Test fun theRowIsSortedNewFirstThenByAirDate() {
        val calendar = listOf(
            show("tt1", on("2026-10-16", 5, season = 6), name = "Slow Horses"),
            show("tt2", on("2026-10-21", 8, season = 3), name = "Shrinking"),
            show("tt3", on("2026-10-31", 1, season = 4), name = "The Diplomat")
        )
        val items = row(calendar, listOf(upcoming("tt100", 1, 3, "2026-10-22", name = "VisionQuest")))
        assertEquals(listOf("Slow Horses", "Shrinking", "VisionQuest", "The Diplomat"), items.map { it.title.name })
        assertEquals(CalendarComingUpWhen.NEW, items.first().whenKind)
    }

    @Test fun matchesAShowByAnyOfItsIds() {
        val calendar = CalendarTitleRecord(
            key = "series:imdb:tt100", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt100", tmdb = 42L),
            name = "VisionQuest", sources = mapOf(CalendarSource.TVMAZE to listOf(on("2026-10-29", 4)))
        )
        val items = row(listOf(calendar), listOf(upcoming("tmdb:42", 1, 3, "2026-10-22")))
        assertEquals(1, items.size)
        assertEquals(3, items.single().entry.episode)
    }

    @Test fun beforeTheCalendarIsReadyTheUpcomingEpisodesStillShow() {
        val items = buildCalendarComingUp(
            calendarTitlesWithUpcoming(emptyList(), listOf(upcoming("tt100", 1, 3, "2026-10-22")), sydney),
            null, now, sydney
        )
        assertEquals(1, items.size)
    }

    @Test fun theCalendarsOwnDatesWinOverTheUpcomingRowsGuess() {
        val titles = listOf(show("tt1", on("2026-10-17", 7, season = 2), on("2026-10-17", 8, season = 2)))
        val merged = calendarTitlesWithUpcoming(titles, listOf(upcoming("tt1", 2, 2, "2026-10-19")), sydney)
        assertEquals(listOf(7, 8), merged.single().entries.map { it.episode })
        val earlier = calendarTitlesWithUpcoming(titles, listOf(upcoming("tt1", 2, 9, "2026-10-19")), sydney)
        assertEquals(listOf(7, 8, 9), earlier.single().entries.map { it.episode })
    }

    @Test fun aShowWithSomethingToWatchInContinueWatchingIsLeftOut() {
        val watchable = calendarContinueWatchingTitles(listOf("tt1", "tmdb:42"))
        assertTrue(calendarTitleInContinueWatching(watchable, show("tt1", on("2026-10-19", 2))))
        val byTmdb = show("tt9", on("2026-10-19", 2)).copy(contentId = null, ids = CalendarIds(imdb = "tt9", tmdb = 42))
        assertTrue(calendarTitleInContinueWatching(watchable, byTmdb))
        assertTrue(!calendarTitleInContinueWatching(watchable, show("tt2", on("2026-10-19", 2))))
        assertTrue(!calendarTitleInContinueWatching(emptySet(), show("tt1", on("2026-10-19", 2))))
    }

    @Test fun filmsUndatedAndUnnumberedItemsAreLeftOut() {
        val titles = calendarTitlesWithUpcoming(
            emptyList(),
            listOf(
                upcoming("tt1", 0, 0, "2026-10-22", contentType = "movie"),
                upcoming("tt2", 1, 0, "2026-10-22"),
                upcoming("tt3", 1, 2, null)
            ),
            sydney
        )
        assertTrue(titles.isEmpty())
    }

    @Test fun nothingUpcomingLeavesTheCalendarAsItIs() {
        val titles = listOf(show("tt1", on("2026-10-21", 1)))
        assertSame(titles, calendarTitlesWithUpcoming(titles, emptyList(), sydney))
    }

    @Test fun theUpcomingRowHidesOnlyWhileComingUpIsOn() {
        val items = listOf<ContinueWatchingItem>(upcoming("tt100", 1, 3, "2026-10-22"))
        val off = HomeUiState(upcomingItems = items)
        assertSame(items, off.shownUpcomingItems)
        val on = off.copy(upcomingInComingUp = true)
        assertTrue(on.shownUpcomingItems.isEmpty())
        assertSame(items, on.upcomingItems)
    }

    @Test fun anUndatedEpisodeStaysInTheUpcomingRow() {
        val undated = upcoming("tt200", 1, 2, null)
        val state = HomeUiState(upcomingItems = listOf(upcoming("tt100", 1, 3, "2026-10-22"), undated), upcomingInComingUp = true)
        assertEquals(listOf<ContinueWatchingItem>(undated), state.shownUpcomingItems)
    }

    @Test fun aShowHiddenInTheCalendarStaysOut() {
        val titles = calendarTitlesWithUpcoming(
            emptyList(),
            listOf(upcoming("tt100", 1, 3, "2026-10-22"), upcoming("tt200", 2, 1, "2026-10-23")),
            sydney,
            hiddenTitleKeys = setOf("series:imdb:tt100")
        )
        assertEquals(listOf("tt200"), titles.map { it.contentId })
    }

    @Test fun anIdTheCalendarCannotReadMatchesByContentId() {
        val calendar = show("tt1", on("2026-10-29", 4), name = "Anime").copy(
            ids = CalendarIds(), key = "series:kitsu:1", contentId = "kitsu:1"
        )
        val titles = calendarTitlesWithUpcoming(listOf(calendar), listOf(upcoming("kitsu:1", 1, 3, "2026-10-22")), sydney)
        assertEquals(1, titles.size)
        assertEquals(listOf(3, 4), titles.single().entries.map { it.episode })
    }

    @Test fun aTimedReleaseUsesItsDateAsTheCalendarDoes() {
        val item = upcoming("tt100", 1, 3, "2026-10-21T22:00:00.000Z")
        assertEquals(LocalDate.of(2026, 10, 22), upcomingAirDate(item, sydney))
        assertEquals(LocalDate.of(2026, 10, 21), upcomingAirDate(item, ZoneId.of("America/Los_Angeles")))
    }

    @Test fun anEpisodeContinueWatchingShowsIsStillLeftOut() {
        val calendar = show("tt1", on("2026-10-16", 2), on("2026-10-29", 3))
        val items = buildCalendarComingUp(
            calendarTitlesWithUpcoming(listOf(calendar), listOf(upcoming("tt100", 1, 3, "2026-10-22")), sydney),
            CalendarWatchedIndex(), now, sydney,
            { title, entry -> title.contentId == "tt1" && entry.episode == 2 }
        )
        assertEquals(listOf("tt100" to 3, "tt1" to 3), items.map { it.title.contentId to it.entry.episode })
    }
}
