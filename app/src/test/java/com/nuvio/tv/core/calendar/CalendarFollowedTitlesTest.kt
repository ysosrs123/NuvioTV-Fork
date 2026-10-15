package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarFollowedTitlesTest {
    private val now = Instant.parse("2026-10-05T23:00:00Z")
    private fun daysAgo(days: Long) = now.minus(Duration.ofDays(days))

    private fun follow(candidates: List<CalendarTitleCandidate>, rules: CalendarFollowRules, now: Instant) =
        followCalendarTitles(candidates, rules, now).titles

    private fun show(origin: CalendarOrigin, ids: CalendarIds, at: Instant? = null, name: String? = null) =
        CalendarTitleCandidate(ids, CalendarTitleType.SERIES, origin, at, name)

    private fun film(origin: CalendarOrigin, ids: CalendarIds, at: Instant? = null) =
        CalendarTitleCandidate(ids, CalendarTitleType.MOVIE, origin, at, "Film")

    @Test fun sourcesSharingAnyIdBecomeOneTitleWithAllIdsAndOrigins() {
        val titles = follow(
            listOf(
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt1"), daysAgo(40), "Night Shift"),
                show(CalendarOrigin.TRAKT_WATCHED, CalendarIds(imdb = "tt1", trakt = 7), daysAgo(3)),
                show(CalendarOrigin.SIMKL_WATCHING, CalendarIds(trakt = 7, simkl = 99, tmdb = 500), daysAgo(10)),
                show(CalendarOrigin.MDBLIST_WATCHLIST, CalendarIds(tmdb = 500, tvdb = 42))
            ),
            CalendarFollowRules(),
            now
        )
        val title = titles.single()
        assertEquals(CalendarIds(imdb = "tt1", tmdb = 500, tvdb = 42, trakt = 7, simkl = 99), title.ids)
        assertEquals(
            setOf(CalendarOrigin.LIBRARY, CalendarOrigin.TRAKT_WATCHED, CalendarOrigin.SIMKL_WATCHING, CalendarOrigin.MDBLIST_WATCHLIST),
            title.origins
        )
        assertEquals(daysAgo(3), title.lastActivity)
        assertEquals("Night Shift", title.name)
        assertEquals("series:imdb:tt1", title.key)
        assertEquals("tt1", title.contentId)
    }

    @Test fun aFilmAndAShowWithTheSameTmdbNumberStayApart() {
        val titles = follow(
            listOf(show(CalendarOrigin.LIBRARY, CalendarIds(tmdb = 1)), film(CalendarOrigin.LIBRARY, CalendarIds(tmdb = 1))),
            CalendarFollowRules(),
            now
        )
        assertEquals(2, titles.size)
    }

    @Test fun startedShowsUntouchedForAYearAreLeftOutUnlessTheBellIsOn() {
        val old = show(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt1"), daysAgo(400))
        val oldWithBell = show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt2"), daysAgo(400))
        val recent = show(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt3"), daysAgo(300))
        val oldButSavedLately = listOf(
            show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt4"), daysAgo(500)),
            show(CalendarOrigin.TRAKT_WATCHLIST, CalendarIds(imdb = "tt4"), daysAgo(20))
        )
        val savedLongAgo = show(CalendarOrigin.TRAKT_WATCHLIST, CalendarIds(imdb = "tt5"), daysAgo(900))

        val keys = follow(
            listOf(old, oldWithBell, recent, savedLongAgo) + oldButSavedLately,
            CalendarFollowRules(bellOn = setOf("series:imdb:tt2")),
            now
        ).map { it.key }.toSet()

        assertEquals(setOf("series:imdb:tt2", "series:imdb:tt3", "series:imdb:tt4", "series:imdb:tt5"), keys)
    }

    @Test fun droppedRemovedFromContinueWatchingAndHiddenTitlesAreLeftOut() {
        val candidates = listOf(
            show(CalendarOrigin.SIMKL_WATCHING, CalendarIds(simkl = 1, imdb = "tt1")),
            show(CalendarOrigin.DROPPED, CalendarIds(simkl = 1)),
            show(CalendarOrigin.PROGRESS, CalendarIds(tmdb = 2)),
            show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt3")),
            show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt4"))
        )
        val titles = follow(
            candidates,
            CalendarFollowRules(
                removedFromContinueWatching = setOf("tmdb:2"),
                hiddenTitleKeys = setOf("series:imdb:tt3")
            ),
            now
        )
        assertEquals(listOf("series:imdb:tt4"), titles.map { it.key })
    }

    @Test fun aHiddenTitleStaysHiddenWhenItsKeyUsesAnotherOfItsIds() {
        val titles = follow(
            listOf(show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt9", tmdb = 9))),
            CalendarFollowRules(hiddenTitleKeys = setOf("series:tmdb:9")),
            now
        )
        assertTrue(titles.isEmpty())
    }

    @Test fun personalListsCountOnlyWhenTheSettingIncludesThem() {
        val candidates = listOf(
            show(CalendarOrigin.TRAKT_LIST, CalendarIds(imdb = "tt1")),
            show(CalendarOrigin.MDBLIST_LIST, CalendarIds(imdb = "tt2")),
            show(CalendarOrigin.TRAKT_LIST, CalendarIds(imdb = "tt3")),
            show(CalendarOrigin.TRAKT_WATCHLIST, CalendarIds(imdb = "tt3"))
        )
        assertEquals(
            listOf("series:imdb:tt3"),
            follow(candidates, CalendarFollowRules(), now).map { it.key }
        )
        assertEquals(3, follow(candidates, CalendarFollowRules(includePersonalLists = true), now).size)
    }

    @Test fun filmsCountWhenSavedNotWhenOnlyWatched() {
        val titles = follow(
            listOf(film(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt1")), film(CalendarOrigin.SIMKL_PLAN_TO_WATCH, CalendarIds(imdb = "tt2"))),
            CalendarFollowRules(),
            now
        )
        assertEquals(listOf("movie:imdb:tt2"), titles.map { it.key })
    }

    @Test fun aBellOnATitleNoSourceHasStillFollowsIt() {
        val titles = follow(emptyList(), CalendarFollowRules(bellOn = setOf("series:tmdb:77")), now)
        val title = titles.single()
        assertTrue(title.bellOn)
        assertEquals(CalendarIds(tmdb = 77), title.ids)
        assertEquals(setOf(CalendarOrigin.BELL), title.origins)
    }

    @Test fun capKeepsBellTitlesAndTheMostRecentlyActive() {
        val candidates = (1..10).map { show(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt$it"), daysAgo(it.toLong())) } +
            show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt99"), daysAgo(200))
        val titles = follow(candidates, CalendarFollowRules(bellOn = setOf("series:imdb:tt99"), cap = 4), now)
        assertEquals(listOf("series:imdb:tt99", "series:imdb:tt1", "series:imdb:tt2", "series:imdb:tt3"), titles.map { it.key })
    }

    @Test fun capOfThreeHundredByDefault() {
        val candidates = (1..450).map { show(CalendarOrigin.LIBRARY, CalendarIds(tmdb = it.toLong()), daysAgo(it.toLong())) }
        val titles = follow(candidates, CalendarFollowRules(), now)
        assertEquals(CALENDAR_MAX_TITLES, titles.size)
        assertEquals("series:tmdb:1", titles.first().key)
    }

    @Test fun reconcileKeepsTheStoredKeyAndDatesAndDropsTitlesNoLongerFollowed() {
        val stored = listOf(
            CalendarTitleRecord(
                key = "series:tmdb:5", type = CalendarTitleType.SERIES, ids = CalendarIds(tmdb = 5),
                tvmazeId = 12, nextRefreshAtMs = 1234L, status = CalendarShowStatus.RUNNING
            ),
            CalendarTitleRecord(key = "series:imdb:tt8", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt8"))
        )
        val followed = follow(
            listOf(show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt5", tmdb = 5), name = "Five"), show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt6"))),
            CalendarFollowRules(),
            now
        )
        val records = reconcileCalendarRecords(followed, stored)
        val five = records.first { it.ids.tmdb == 5L }
        assertEquals("series:tmdb:5", five.key)
        assertEquals(12, five.tvmazeId)
        assertEquals(1234L, five.nextRefreshAtMs)
        assertEquals("tt5", five.ids.imdb)
        assertEquals("Five", five.name)
        val six = records.first { it.ids.imdb == "tt6" }
        assertEquals(0L, six.nextRefreshAtMs)
        assertFalse(records.any { it.key == "series:imdb:tt8" })
    }

    @Test fun titleKeysParseBackAndMatchByAnySharedId() {
        assertEquals(CalendarTitleType.SERIES to CalendarIds(imdb = "tt1"), parseCalendarTitleKey("series:imdb:tt1"))
        assertEquals(CalendarTitleType.MOVIE to CalendarIds(tmdb = 3), parseCalendarTitleKey("movie:tmdb:3"))
        assertNull(parseCalendarTitleKey("anime:tmdb:3"))
        assertNull(parseCalendarTitleKey("series:title:x"))
        assertTrue(calendarKeyMatches("series:tmdb:3", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1", tmdb = 3)))
        assertFalse(calendarKeyMatches("movie:tmdb:3", CalendarTitleType.SERIES, CalendarIds(tmdb = 3)))
    }

    @Test fun everyTitleLeftOutIsReportedWithItsReason() {
        val result = followCalendarTitles(
            listOf(
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt1"), daysAgo(1), "Hidden One"),
                show(CalendarOrigin.TRAKT_WATCHED, CalendarIds(imdb = "tt2"), daysAgo(2), "Dropped One"),
                show(CalendarOrigin.DROPPED, CalendarIds(imdb = "tt2")),
                show(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt3"), daysAgo(3), "Removed One"),
                show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt4"), daysAgo(400), "Idle One"),
                film(CalendarOrigin.SIMKL_COMPLETED, CalendarIds(imdb = "tt5"), daysAgo(5)),
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt6"), daysAgo(6), "Kept"),
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt7"), daysAgo(7), "Past The Cap")
            ),
            CalendarFollowRules(
                hiddenTitleKeys = setOf("series:imdb:tt1"),
                removedFromContinueWatching = setOf("tt3"),
                cap = 1
            ),
            now
        )
        assertEquals(listOf("series:imdb:tt6"), result.titles.map { it.key })
        assertEquals(
            mapOf(
                "series:imdb:tt1" to CalendarLeftOutReason.HIDDEN,
                "series:imdb:tt2" to CalendarLeftOutReason.DROPPED,
                "series:imdb:tt3" to CalendarLeftOutReason.REMOVED_FROM_CONTINUE_WATCHING,
                "series:imdb:tt4" to CalendarLeftOutReason.IDLE,
                "movie:imdb:tt5" to CalendarLeftOutReason.FILM_NOT_SAVED,
                "series:imdb:tt7" to CalendarLeftOutReason.OVER_CAP
            ),
            result.leftOut.associate { it.key to it.reason }
        )
        assertEquals("Dropped One", result.leftOut.first { it.key == "series:imdb:tt2" }.name)

        val lines = calendarLeftOutSummary(result.leftOut)
        assertEquals("left out 6: hidden=1 dropped=1 removed-from-cw=1 idle=1 film-not-saved=1 over-cap=1", lines.first())
        assertTrue("left out hidden: Hidden One" in lines)
        assertTrue("left out over-cap: Past The Cap" in lines)
        assertEquals(7, lines.size)
    }

    @Test fun theLeftOutSummaryStaysShort() {
        val many = (1..25).map { n ->
            CalendarLeftOut("series:imdb:tt$n", "Show\n  $n", CalendarLeftOutReason.OVER_CAP, daysAgo(n.toLong()))
        }
        val lines = calendarLeftOutSummary(many)
        assertEquals(
            listOf("left out 25: over-cap=25", "left out over-cap: Show 1, Show 2, Show 3, +22 more"),
            lines
        )
        assertTrue(calendarLeftOutSummary(emptyList()).isEmpty())
        assertEquals(
            "left out idle: series:tmdb:9",
            calendarLeftOutSummary(listOf(CalendarLeftOut("series:tmdb:9", null, CalendarLeftOutReason.IDLE, null)))[1]
        )
    }

    @Test fun titlesFromASourceThatWasNotReadComeBackAsCandidates() {
        val stored = listOf(
            CalendarTitleRecord(
                "series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1", trakt = 4), name = "Tulsa",
                origins = setOf(CalendarOrigin.TRAKT_WATCHED, CalendarOrigin.LIBRARY), lastActivityMs = daysAgo(30).toEpochMilli()
            ),
            CalendarTitleRecord(
                "series:imdb:tt2", CalendarTitleType.SERIES, CalendarIds(imdb = "tt2"), origins = setOf(CalendarOrigin.LIBRARY)
            )
        )
        assertTrue(carriedCalendarCandidates(stored, emptySet()).isEmpty())
        val carried = carriedCalendarCandidates(stored, setOf(CalendarOrigin.TRAKT_WATCHED, CalendarOrigin.TRAKT_CALENDAR))
        assertEquals(1, carried.size)
        assertEquals(CalendarOrigin.TRAKT_WATCHED, carried.single().origin)
        assertEquals(daysAgo(30), carried.single().lastActivity)
        assertEquals("Tulsa", carried.single().name)

        val hidden = followCalendarTitles(carried, CalendarFollowRules(hiddenTitleKeys = setOf("series:imdb:tt1")), now)
        assertTrue(hidden.titles.isEmpty())
        assertEquals(CalendarLeftOutReason.HIDDEN, hidden.leftOut.single().reason)
    }
}
