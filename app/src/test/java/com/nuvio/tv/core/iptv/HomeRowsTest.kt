package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class HomeRowsTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun programme(title: String, start: Long, stop: Long?) = GuideProgramme("c", GuideTimestamp(start, 14, start.toString()),
        stop?.let { GuideTimestamp(it, 14, it.toString()) }, listOf(LocalizedGuideText(title, null)), emptyList())

    @Test fun defaultsShowFavouritesSportAndRecordings() {
        val settings = HomeRowSettings()
        assertEquals(setOf(HomeRowKind.FAVOURITES, HomeRowKind.SPORT, HomeRowKind.RECORDINGS), settings.enabled)
        assertEquals(settings, HomeRowSettings.read { null })
        val saved = HomeRowSettings.read { if (it == HomeRowKind.MOVIES) true else if (it == HomeRowKind.SPORT) false else null }
        assertTrue(saved.shows(HomeRowKind.MOVIES))
        assertFalse(saved.shows(HomeRowKind.SPORT))
        assertFalse(saved.with(HomeRowKind.MOVIES, false).shows(HomeRowKind.MOVIES))
        assertTrue(saved.with(HomeRowKind.SERIES, true).shows(HomeRowKind.SERIES))
    }

    @Test fun orderFollowsSettingsAndSportAvailability() {
        val all = HomeRowSettings(HomeRowKind.entries.toSet())
        assertEquals(listOf(HomeRowKind.FAVOURITES, HomeRowKind.SPORT, HomeRowKind.MOVIES, HomeRowKind.SERIES, HomeRowKind.RECORDINGS), HomeRows.order(all, true))
        assertEquals(listOf(HomeRowKind.FAVOURITES, HomeRowKind.RECORDINGS), HomeRows.order(HomeRowSettings(), false))
        assertTrue(HomeRows.order(HomeRowSettings(emptySet()), true).isEmpty())
    }

    @Test fun cacheIsFreshOnlyWithinItsAge() {
        assertFalse(HomeRows.fresh(null, now))
        assertTrue(HomeRows.fresh(now - minute, now))
        assertFalse(HomeRows.fresh(now - HomeRows.CACHE_MILLIS, now))
        assertFalse(HomeRows.fresh(now + minute, now))
    }

    @Test fun mergedKeepsSourceOrderWithoutDuplicatesUpToTheLimit() {
        val merged = HomeRows.merged(listOf(listOf("a", "b"), listOf("b", "c", "d")), { it }, 3)
        assertEquals(listOf("a", "b", "c"), merged)
        assertEquals(listOf("a"), HomeRows.merged(listOf(listOf("a", "a")), { it }, 5))
    }

    @Test fun nowPlayingPicksTheProgrammeOnAirAndProgressFollowsTheClock() {
        val earlier = programme("Earlier", now - 90 * minute, now - 30 * minute)
        val current = programme("Current", now - 15 * minute, now + 45 * minute)
        val later = programme("Later", now + 45 * minute, now + 90 * minute)
        assertEquals(current, HomeRows.nowPlaying(listOf(earlier, later, current), now))
        assertNull(HomeRows.nowPlaying(listOf(earlier, later), now))
        val nested = programme("Inserted", now - 5 * minute, now + 5 * minute)
        assertEquals(nested, HomeRows.nowPlaying(listOf(current, nested), now))
        assertEquals(0.25f, HomeRows.progress(current, now)!!, 0.001f)
        assertNull(HomeRows.progress(programme("Open", now - minute, null), now))
        assertEquals(1f, HomeRows.progress(earlier, now)!!, 0.001f)
    }

    @Test fun openEndedProgrammesCountAsAiringForALimitedTime() {
        assertTrue(HomeRows.airing(programme("Open", now - minute, null), now))
        assertFalse(HomeRows.airing(programme("Old", now - SPORTS_OPEN_ENDED_MILLIS, null), now))
        assertFalse(HomeRows.airing(programme("Ended", now - 30 * minute, now), now))
        assertFalse(HomeRows.airing(programme("Soon", now + minute, now + 60 * minute), now))
    }

    @Test fun sportOnNowKeepsLiveListingsOncePerChannel() {
        val live = programme("Match", now - 20 * minute, now + 70 * minute)
        val started = programme("Race", now - 50 * minute, now + 10 * minute)
        val upcoming = programme("Final", now + 30 * minute, now + 150 * minute)
        val listings = listOf("b" to live, "a" to upcoming, "c" to started, "b" to live)
        val result = HomeRows.sportOnNow(listings, { it }, now, 10)
        assertEquals(listOf("c" to started, "b" to live), result)
        assertEquals(1, HomeRows.sportOnNow(listings, { it }, now, 1).size)
    }

    @Test fun recentTitlesSortByAddedAcrossSources() {
        data class Title(val id: String, val added: Long?)
        val first = listOf(Title("a", 10), Title("b", null), Title("c", 30))
        val second = listOf(Title("d", 20), Title("a", 40))
        val result = HomeRows.recentTitles(listOf(first, second), { it.id }, { it.added }, 3)
        assertEquals(listOf("c", "d", "a"), result.map { it.id })
        assertEquals("b", HomeRows.recentTitles(listOf(first, second), { it.id }, { it.added }, 10).last().id)
    }

    @Test fun recentRecordingsShowFinishedRecordingsNewestFirst() {
        data class Recording(val id: String, val status: RecordingStatus, val end: Long)
        val items = listOf(Recording("old", RecordingStatus.DONE, 1), Recording("failed", RecordingStatus.FAILED, 5),
            Recording("partial", RecordingStatus.PARTIAL, 3), Recording("running", RecordingStatus.RECORDING, 9),
            Recording("new", RecordingStatus.DONE, 7), Recording("planned", RecordingStatus.SCHEDULED, 8))
        val result = HomeRows.recentRecordings(items, { it.status }, { it.end }, 2)
        assertEquals(listOf("new", "partial"), result.map { it.id })
    }

    @Test fun logoAcceptsOnlyWebAddresses() {
        assertEquals("https://example.com/a.png", HomeRows.logo(" https://example.com/a.png "))
        assertNull(HomeRows.logo("file:///sdcard/a.png"))
        assertNull(HomeRows.logo(null))
        assertNull(HomeRows.logo("http://" + "a".repeat(2050)))
    }
}
