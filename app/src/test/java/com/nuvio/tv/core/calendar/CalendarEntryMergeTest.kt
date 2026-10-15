package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEntryMergeTest {
    private val now = Instant.parse("2026-10-05T23:00:00Z")
    private val sixAmUtc = Instant.parse("2026-10-10T06:00:00Z").toEpochMilli()

    @Test fun anExactTimeBeatsAPlainDateFromAHigherSource() {
        val merged = mergeCalendarEntries(
            listOf(
                CalendarEntry(season = 2, episode = 5, airDate = "2026-10-09", source = CalendarSource.TMDB, title = "From TMDB"),
                CalendarEntry(season = 2, episode = 5, airInstantMs = sixAmUtc, source = CalendarSource.TVMAZE)
            )
        ).single()
        assertEquals(sixAmUtc, merged.airInstantMs)
        assertNull(merged.airDate)
        assertEquals(CalendarSource.TVMAZE, merged.source)
        assertEquals("From TMDB", merged.title)
    }

    @Test fun traktsEpisodeTypeBeatsEveryOtherLabelAndTraktsTimeBeatsTvMazes() {
        val trakt = CalendarEntry(
            season = 3, episode = 10, airInstantMs = sixAmUtc + 60_000, label = CalendarEpisodeLabel.SERIES_FINALE,
            source = CalendarSource.TRAKT
        )
        val tvMaze = CalendarEntry(season = 3, episode = 10, airInstantMs = sixAmUtc, source = CalendarSource.TVMAZE, title = "Last")
        val tmdb = CalendarEntry(season = 3, episode = 10, airDate = "2026-10-10", label = CalendarEpisodeLabel.SEASON_FINALE, source = CalendarSource.TMDB)
        val merged = mergeCalendarEntries(listOf(tvMaze, tmdb, trakt)).single()
        assertEquals(CalendarEpisodeLabel.SERIES_FINALE, merged.label)
        assertEquals(sixAmUtc + 60_000, merged.airInstantMs)
        assertEquals("Last", merged.title)
    }

    @Test fun aStandardTraktEpisodeTypeOverridesATmdbFinaleMark() {
        val merged = mergeCalendarEntries(
            listOf(
                CalendarEntry(season = 1, episode = 8, airInstantMs = sixAmUtc, label = CalendarEpisodeLabel.NONE, source = CalendarSource.TRAKT),
                CalendarEntry(season = 1, episode = 8, airDate = "2026-10-10", label = CalendarEpisodeLabel.SEASON_FINALE, source = CalendarSource.TMDB)
            )
        ).single()
        assertEquals(CalendarEpisodeLabel.NONE, merged.label)
    }

    @Test fun tmdbFinaleMarksTvMazeEpisodesWithoutChangingTheirTime() {
        val merged = mergeCalendarEntries(
            listOf(
                CalendarEntry(season = 1, episode = 8, airInstantMs = sixAmUtc, source = CalendarSource.TVMAZE),
                CalendarEntry(season = 1, episode = 8, airDate = "2026-10-10", label = CalendarEpisodeLabel.SEASON_FINALE, source = CalendarSource.TMDB)
            )
        ).single()
        assertEquals(CalendarEpisodeLabel.SEASON_FINALE, merged.label)
        assertEquals(sixAmUtc, merged.airInstantMs)
    }

    @Test fun noSourceWithATimeMeansNoTime() {
        val merged = mergeCalendarEntries(
            listOf(
                CalendarEntry(season = 1, episode = 2, airDate = "2026-10-11", source = CalendarSource.ADDON),
                CalendarEntry(season = 1, episode = 2, airDate = "2026-10-10", source = CalendarSource.TMDB)
            )
        ).single()
        assertNull(merged.airInstantMs)
        assertEquals("2026-10-10", merged.airDate)
        assertEquals(false, merged.timeKnown)
    }

    @Test fun differentReleasesStaySeparateInDateOrder() {
        val merged = mergeCalendarEntries(
            listOf(
                CalendarEntry(season = 1, episode = 3, airDate = "2026-10-20"),
                CalendarEntry(season = 1, episode = 2, airDate = "2026-10-13"),
                CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = "2026-10-15")
            )
        )
        assertEquals(listOf("S1E2", "MOVIE_DIGITAL", "S1E3"), merged.map { it.slot })
    }

    @Test fun labels() {
        assertEquals(CalendarEpisodeLabel.NEW_SHOW, deriveEpisodeLabel(1, 1))
        assertEquals(CalendarEpisodeLabel.SEASON_PREMIERE, deriveEpisodeLabel(4, 1))
        assertEquals(CalendarEpisodeLabel.NONE, deriveEpisodeLabel(4, 2))
        assertEquals(CalendarEpisodeLabel.NONE, deriveEpisodeLabel(0, 1))
        assertEquals(CalendarEpisodeLabel.MID_SEASON_FINALE, traktEpisodeLabel("mid_season_finale", 2, 8))
        assertEquals(CalendarEpisodeLabel.NONE, traktEpisodeLabel("standard", 2, 1))
        assertEquals(CalendarEpisodeLabel.SEASON_PREMIERE, traktEpisodeLabel(null, 2, 1))
        assertEquals(CalendarEpisodeLabel.SEASON_FINALE, tmdbEpisodeLabel("finale"))
        assertEquals(CalendarEpisodeLabel.MID_SEASON_FINALE, tmdbEpisodeLabel("mid_season"))
        assertEquals(CalendarEpisodeLabel.NONE, tmdbEpisodeLabel("standard"))
    }

    @Test fun aTitleKeepsFifteenDaysBackAYearAheadAndFortyReleasesAcrossAllSources() {
        fun weekly(source: CalendarSource, label: CalendarEpisodeLabel = CalendarEpisodeLabel.NONE) = (-30..400).map { day ->
            CalendarEntry(
                season = 1, episode = day + 31, airInstantMs = now.plus(Duration.ofDays(day.toLong())).toEpochMilli(),
                label = label, source = source
            )
        }
        val record = CalendarTitleRecord(
            "series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"),
            sources = mapOf(
                CalendarSource.TRAKT to weekly(CalendarSource.TRAKT),
                CalendarSource.TVMAZE to weekly(CalendarSource.TVMAZE),
                CalendarSource.TMDB to weekly(CalendarSource.TMDB, CalendarEpisodeLabel.SEASON_FINALE).drop(100),
                CalendarSource.ADDON to listOf(CalendarEntry(season = 1, episode = 500, airDate = "2026-11-01", source = CalendarSource.ADDON))
            )
        ).bounded(now)
        assertEquals(CALENDAR_MAX_ENTRIES_PER_TITLE, record.entries.size)
        assertEquals(now.minus(Duration.ofDays(15)).toEpochMilli(), record.entries.first().airInstantMs)
        assertTrue(record.sources.values.all { it.size <= CALENDAR_MAX_ENTRIES_PER_TITLE })
        assertEquals(setOf(CalendarSource.TRAKT, CalendarSource.TVMAZE), record.sources.keys)
        assertEquals(0, keptCalendarEntries(listOf(CalendarEntry(airDate = "2028-01-01")), now).size)
    }

    @Test fun theTimeFetchedLastWinsWhenTwoSourcesGiveOne() {
        val traktOld = CalendarEntry(season = 1, episode = 5, airInstantMs = sixAmUtc, source = CalendarSource.TRAKT, label = CalendarEpisodeLabel.SEASON_FINALE)
        val tvMazeMoved = CalendarEntry(season = 1, episode = 5, airInstantMs = sixAmUtc + 86_400_000L, source = CalendarSource.TVMAZE)
        val merged = mergeCalendarEntries(
            listOf(traktOld, tvMazeMoved),
            mapOf(CalendarSource.TRAKT to 1_000L, CalendarSource.TVMAZE to 2_000L)
        ).single()
        assertEquals(sixAmUtc + 86_400_000L, merged.airInstantMs)
        assertEquals(CalendarEpisodeLabel.SEASON_FINALE, merged.label)
        val traktNewer = mergeCalendarEntries(
            listOf(traktOld, tvMazeMoved),
            mapOf(CalendarSource.TRAKT to 3_000L, CalendarSource.TVMAZE to 2_000L)
        ).single()
        assertEquals(sixAmUtc, traktNewer.airInstantMs)
    }

    @Test fun replacingASourceDropsAnEpisodeThatMoved() {
        val record = CalendarTitleRecord(
            "series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"),
            sources = mapOf(CalendarSource.TVMAZE to listOf(CalendarEntry(season = 1, episode = 2, airInstantMs = sixAmUtc)))
        ).withSource(CalendarSource.TVMAZE, listOf(CalendarEntry(season = 1, episode = 2, airInstantMs = sixAmUtc + 3_600_000L)), now)
        assertEquals(listOf(sixAmUtc + 3_600_000L), record.entries.map { it.airInstantMs })
        assertEquals(now.toEpochMilli(), record.sourceFetchedAtMs[CalendarSource.TVMAZE])
        assertTrue(record.withSource(CalendarSource.TVMAZE, emptyList(), now).sources.isEmpty())
    }

    @Test fun addonDatesGoToTheShowThatSharesAnId() {
        val record = CalendarTitleRecord("series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1", tmdb = 4))
        val updated = applyAddonEntries(
            listOf(record),
            listOf(
                CalendarIds(tmdb = 4) to CalendarEntry(season = 1, episode = 3, airDate = "2026-10-12", source = CalendarSource.ADDON),
                CalendarIds(imdb = "tt2") to CalendarEntry(season = 1, episode = 1, airDate = "2026-10-12", source = CalendarSource.ADDON)
            ),
            now
        ).single()
        assertEquals(listOf("S1E3"), updated.entries.map { it.slot })
    }
}
