package com.nuvio.tv.core.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * A show whose short segments TVmaze lists as episodes of their own, so its numbers run ahead of Trakt's and TheTVDB's
 * from the sixth episode on. Dates as the three sources gave them for season 4, two or more episodes a night.
 */
class CalendarTvMazeNumberingTest {

    private fun tvMaze(episode: Int, stamp: String, title: String) = CalendarEntry(
        season = 4, episode = episode, title = title, airInstantMs = Instant.parse(stamp).toEpochMilli(),
        source = CalendarSource.TVMAZE
    )

    private fun trakt(episode: Int, stamp: String, title: String) = CalendarEntry(
        season = 4, episode = episode, title = title, airInstantMs = Instant.parse(stamp).toEpochMilli(),
        source = CalendarSource.TRAKT
    )

    private fun tvdb(episode: Int, date: String, title: String) = CalendarEntry(
        season = 4, episode = episode, title = title, airDate = date, source = CalendarSource.TVDB
    )

    private val tvMazeSeason = listOf(
        tvMaze(5, "2026-10-22T02:30:00Z", "Beavis, Take the Wheel"),
        tvMaze(6, "2026-10-22T02:47:00Z", "Humor in Uniform with Tom Anderson"),
        tvMaze(7, "2026-10-22T02:47:00Z", "Pleasure Washer"),
        tvMaze(8, "2026-10-29T02:30:00Z", "Schlongmerica"),
        tvMaze(9, "2026-10-29T02:45:00Z", "Hot Car"),
        tvMaze(10, "2026-11-05T03:30:00Z", "Power Grip"),
        tvMaze(11, "2026-11-05T03:42:00Z", "Swipe Right with David Van Driessen: Picnic"),
        tvMaze(12, "2026-11-05T03:44:00Z", "Double Vision"),
        tvMaze(13, "2026-11-12T03:30:00Z", "Fender Benders"),
        tvMaze(14, "2026-11-12T03:45:00Z", "Chickens"),
        tvMaze(15, "2026-11-19T03:30:00Z", "Burglar Alarm"),
        tvMaze(16, "2026-11-19T03:45:00Z", "TSA-holes 2: Protecting America")
    )

    private val traktSeason = listOf(
        trakt(5, "2026-10-22T02:30:00Z", "Beavis, Take the Wheel"),
        trakt(6, "2026-10-22T02:30:00Z", "Pleasure Washer"),
        trakt(7, "2026-10-29T02:30:00Z", "Schlongmerica"),
        trakt(8, "2026-10-29T02:30:00Z", "Hot Car"),
        trakt(9, "2026-11-05T03:30:00Z", "Power Grip"),
        trakt(10, "2026-11-05T03:30:00Z", "Double Vision"),
        trakt(11, "2026-11-12T03:30:00Z", "Fender Benders"),
        trakt(12, "2026-11-12T03:30:00Z", "Chickens"),
        trakt(13, "2026-11-19T03:30:00Z", "Burglar Alarm"),
        trakt(14, "2026-11-19T03:30:00Z", "TSA-holes 2: Protecting America")
    )

    private val tvdbSeason = listOf(
        tvdb(5, "2026-10-21", "Beavis, Take the Wheel"),
        tvdb(6, "2026-10-21", "Pleasure Washer"),
        tvdb(7, "2026-10-28", "Schlongmerica"),
        tvdb(8, "2026-10-28", "Hot Car"),
        tvdb(9, "2026-11-04", "Power Grip"),
        tvdb(10, "2026-11-04", "Double Vision"),
        tvdb(11, "2026-11-11", "Fender Benders"),
        tvdb(12, "2026-11-11", "Chickens"),
        tvdb(13, "2026-11-18", "Burglar Alarm"),
        tvdb(14, "2026-11-18", "TSA-holes 2: Protecting America"),
        tvdb(15, "2026-11-25", "Bottle of Beer"),
        tvdb(16, "2026-11-25", "Put Down")
    )

    private fun record(vararg sources: Pair<CalendarSource, List<CalendarEntry>>) = CalendarTitleRecord(
        key = "k",
        type = CalendarTitleType.SERIES,
        ids = CalendarIds(imdb = "tt20859904"),
        sources = mapOf(*sources),
        sourceFetchedAtMs = sources.mapIndexed { index, (source, _) -> source to index.toLong() }.toMap()
    )

    private fun CalendarTitleRecord.days(): Map<Int, String> = entries.associate { entry ->
        val day = entry.airInstantMs?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
            ?: entry.airDate.orEmpty()
        (entry.episode ?: 0) to "$day ${entry.title}"
    }

    @Test fun `TVmaze read last keeps Trakt's dates and names for every episode`() {
        val days = record(
            CalendarSource.TVDB to tvdbSeason,
            CalendarSource.TRAKT to traktSeason,
            CalendarSource.TVMAZE to tvMazeSeason
        ).days()
        assertEquals("2026-11-12 Fender Benders", days[11])
        assertEquals("2026-11-12 Chickens", days[12])
        assertEquals("2026-11-19 Burglar Alarm", days[13])
        assertEquals("2026-11-19 TSA-holes 2: Protecting America", days[14])
        assertEquals("2026-11-25 Bottle of Beer", days[15])
        assertEquals("2026-11-25 Put Down", days[16])
        assertEquals("2026-10-29 Schlongmerica", days[7])
        assertEquals("2026-11-05 Power Grip", days[9])
    }

    @Test fun `without Trakt TheTVDB's dates still hold against TVmaze's numbering`() {
        val days = record(CalendarSource.TVDB to tvdbSeason, CalendarSource.TVMAZE to tvMazeSeason).days()
        assertEquals("2026-11-18 Burglar Alarm", days[13])
        assertEquals("2026-11-18 TSA-holes 2: Protecting America", days[14])
        assertEquals("2026-11-11 Fender Benders", days[11])
        assertEquals("2026-11-25 Bottle of Beer", days[15])
    }
}
