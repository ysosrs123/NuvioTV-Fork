package com.nuvio.tv.ui.screens.home

import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.calendar.CalendarComingUpItem
import com.nuvio.tv.core.calendar.CalendarComingUpWhen
import com.nuvio.tv.core.calendar.CalendarEntry
import com.nuvio.tv.core.calendar.CalendarEntryKind
import com.nuvio.tv.core.calendar.CalendarIds
import com.nuvio.tv.core.calendar.CalendarSettings
import com.nuvio.tv.core.calendar.CalendarTitleRecord
import com.nuvio.tv.core.calendar.CalendarTitleType
import com.nuvio.tv.core.calendar.toCalendarSettings
import com.nuvio.tv.ui.screens.calendar.CalendarFormats
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarComingUpCardTest {
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
    private val thursday = LocalDate.of(2026, 10, 8)

    private val harbour = CalendarTitleRecord(
        key = "series:imdb:tt1", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt1"), name = "The Harbour",
        poster = "poster.jpg"
    )

    private fun item(whenKind: CalendarComingUpWhen, date: LocalDate = thursday, time: LocalTime? = LocalTime.of(20, 30)) =
        CalendarComingUpItem(harbour, CalendarEntry(season = 2, episode = 6, title = "Graveyard"), whenKind, date, time)

    @Test fun labels() {
        assertEquals("New", calendarComingUpLabel(item(CalendarComingUpWhen.NEW), text, formats))
        assertEquals("Tonight ${formats.time(LocalTime.of(20, 30))}", calendarComingUpLabel(item(CalendarComingUpWhen.TONIGHT), text, formats))
        assertEquals(
            "Today ${formats.time(LocalTime.of(11, 0))}",
            calendarComingUpLabel(item(CalendarComingUpWhen.TODAY, time = LocalTime.of(11, 0)), text, formats)
        )
        assertEquals("Today", calendarComingUpLabel(item(CalendarComingUpWhen.TODAY, time = null), text, formats))
        assertEquals("Tomorrow", calendarComingUpLabel(item(CalendarComingUpWhen.TOMORROW), text, formats))
        assertEquals("Thu", calendarComingUpLabel(item(CalendarComingUpWhen.WEEKDAY), text, formats))
        assertEquals("Thu 5 Nov", calendarComingUpLabel(item(CalendarComingUpWhen.DATE, LocalDate.of(2026, 11, 5)), text, formats))
    }

    @Test fun anEpisodeCard() {
        val card = calendarComingUpCard(item(CalendarComingUpWhen.TONIGHT), text, formats)!!
        val info = card.info
        assertEquals("tt1", info.contentId)
        assertEquals("series", info.contentType)
        assertEquals(2, info.season)
        assertEquals(6, info.episode)
        assertEquals("Graveyard", info.episodeTitle)
        assertEquals("Tonight ${formats.time(LocalTime.of(20, 30))}", info.badgeLabel)
        assertFalse(info.hasAired)
        assertTrue(card.isComingUp)
        assertTrue(calendarComingUpCard(item(CalendarComingUpWhen.NEW), text, formats)!!.info.hasAired)
    }

    @Test fun aFilmCardOpensTheFilm() {
        val film = CalendarTitleRecord(key = "movie:tmdb:9", type = CalendarTitleType.MOVIE, ids = CalendarIds(tmdb = 9), name = "Kestrel")
        val entry = CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = "2026-11-05")
        val card = calendarComingUpCard(
            CalendarComingUpItem(film, entry, CalendarComingUpWhen.DATE, LocalDate.of(2026, 11, 5), null), text, formats,
            art = { type, ids -> "art-$type-${ids.first()}" to null }
        )!!
        assertEquals("tmdb:9", card.info.contentId)
        assertEquals("movie", card.info.contentType)
        assertEquals(0, card.info.season)
        assertEquals("Streaming and digital release", card.info.episodeTitle)
        assertEquals("art-movie-tmdb:9", card.info.backdrop)
    }

    @Test fun aTitleWithoutIdsHasNoCard() {
        val unknown = harbour.copy(ids = CalendarIds(), contentId = null)
        val entry = CalendarEntry(season = 1, episode = 1)
        assertNull(calendarComingUpCard(CalendarComingUpItem(unknown, entry, CalendarComingUpWhen.NEW, thursday, null), text, formats))
    }

    @Test fun theRowIsOffUntilSwitchedOn() {
        assertFalse(CalendarSettings().comingUpRow)
        assertFalse(emptyPreferences().toCalendarSettings().comingUpRow)
    }

    @Test fun continueWatchingCardsAreNotComingUp() {
        val nextUp = ContinueWatchingItem.NextUp(
            NextUpInfo(
                contentId = "tt1", contentType = "series", name = "The Harbour", poster = null, backdrop = null, logo = null,
                videoId = "tt1:2:6", season = 2, episode = 6, episodeTitle = null, thumbnail = null, lastWatched = 0L,
                sortTimestamp = 0L
            )
        )
        assertFalse(nextUp.isComingUp)
    }

    @Test fun episodeNumbersOnlyWhenKnown() {
        val noNumbers = CalendarComingUpItem(harbour, CalendarEntry(airDate = "2026-10-08"), CalendarComingUpWhen.WEEKDAY, thursday, null)
        assertFalse(calendarComingUpCard(noNumbers, text, formats)!!.info.showsEpisodeCode)
        assertTrue(calendarComingUpCard(item(CalendarComingUpWhen.WEEKDAY), text, formats)!!.info.showsEpisodeCode)
        val special = NextUpInfo(
            contentId = "tt1", contentType = "series", name = "The Harbour", poster = null, backdrop = null, logo = null,
            videoId = "tt1:0:0", season = 0, episode = 0, episodeTitle = null, thumbnail = null, lastWatched = 0L, sortTimestamp = 0L
        )
        assertTrue(special.showsEpisodeCode)
        assertFalse(special.copy(contentType = "movie").showsEpisodeCode)
    }

    @Test fun placeholderNamesAreLeftOff() {
        val tba = CalendarComingUpItem(harbour, CalendarEntry(season = 6, episode = 1, title = "TBA"), CalendarComingUpWhen.WEEKDAY, thursday, null)
        assertNull(calendarComingUpCard(tba, text, formats)!!.info.episodeTitle)
    }
}
