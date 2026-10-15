package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarRemindersTest {
    private val now = Instant.parse("2026-10-05T23:00:00Z")

    private fun show(key: String, vararg entries: CalendarEntry) = CalendarTitleRecord(
        key = key, type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = key.substringAfterLast(':')), name = key,
        sources = mapOf(CalendarSource.TVMAZE to entries.toList())
    )

    private fun airedAgo(ago: Duration, episode: Int = 1) =
        CalendarEntry(season = 1, episode = episode, airInstantMs = now.minus(ago).toEpochMilli())

    private val noneWatched: (CalendarTitleRecord, CalendarEntry) -> Boolean = { _, _ -> false }

    private fun due(
        titles: List<CalendarTitleRecord>,
        bells: Set<String> = titles.map { it.key }.toSet(),
        seen: Set<String> = emptySet(),
        watched: (CalendarTitleRecord, CalendarEntry) -> Boolean = noneWatched,
        at: Instant = now
    ) = dueReminders(titles, bells, seen, watched, at)

    @Test fun anEpisodeIsDueOneHourAfterItAirs() {
        val title = show("series:imdb:tt1", airedAgo(Duration.ofMinutes(59), 1), airedAgo(Duration.ofMinutes(60), 2))
        assertEquals(listOf(2), due(listOf(title)).items.map { it.entry.episode })
    }

    @Test fun aPlainDateIsDueAtOneInTheMorningUtcTheNextDay() {
        val title = show("series:imdb:tt1", CalendarEntry(season = 1, episode = 1, airDate = "2026-10-05", source = CalendarSource.TMDB))
        assertEquals(Instant.parse("2026-10-06T01:00:00Z"), calendarReminderDueAt(title.entries.single()))
        assertTrue(due(listOf(title)).items.isEmpty())
        assertEquals(1, due(listOf(title), at = Instant.parse("2026-10-06T01:00:00Z")).count)
    }

    @Test fun onlyTheLastFourteenDays() {
        val title = show(
            "series:imdb:tt1",
            airedAgo(Duration.ofDays(14).plusHours(2), 1),
            airedAgo(Duration.ofDays(14), 2),
            airedAgo(Duration.ofDays(3), 3)
        )
        assertEquals(listOf(3, 2), due(listOf(title)).items.map { it.entry.episode })
    }

    @Test fun onlyTitlesWithTheBellOnAlsoByAnotherId() {
        val belled = show("series:imdb:tt1", airedAgo(Duration.ofHours(2)))
            .copy(ids = CalendarIds(imdb = "tt1", tmdb = 10))
        val quiet = show("series:imdb:tt2", airedAgo(Duration.ofHours(2)))
        val result = due(listOf(belled, quiet), bells = setOf("series:tmdb:10"))
        assertEquals(listOf("series:imdb:tt1"), result.items.map { it.title.key })
    }

    @Test fun watchedAndSeenEpisodesAreLeftOut() {
        val title = show("series:imdb:tt1", airedAgo(Duration.ofHours(2), 1), airedAgo(Duration.ofHours(3), 2), airedAgo(Duration.ofHours(4), 3))
        val result = due(
            listOf(title),
            seen = setOf("series:imdb:tt1|S1E2"),
            watched = { _, entry -> entry.episode == 3 }
        )
        assertEquals(listOf("series:imdb:tt1|S1E1"), result.items.map { it.episodeKey })
    }

    @Test fun newestFirstWithACountForAndNMore() {
        val titles = (1..5).map { show("series:imdb:tt$it", airedAgo(Duration.ofHours(it.toLong() + 1))) }
        val result = due(titles)
        assertEquals((1..5).map { "series:imdb:tt$it" }, result.items.map { it.title.key })
        assertEquals(5, result.count)
        assertEquals(2, result.moreThan(3))
        assertEquals(0, result.moreThan(9))
    }

    @Test fun cinemaDatesGiveNoReminderButDigitalReleasesDo() {
        val film = CalendarTitleRecord(
            key = "movie:imdb:tt9", type = CalendarTitleType.MOVIE, ids = CalendarIds(imdb = "tt9"),
            sources = mapOf(
                CalendarSource.TMDB to listOf(
                    CalendarEntry(kind = CalendarEntryKind.MOVIE_THEATRICAL, airDate = "2026-10-01", source = CalendarSource.TMDB),
                    CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = "2026-10-02", source = CalendarSource.TMDB)
                )
            )
        )
        assertEquals(listOf(CalendarEntryKind.MOVIE_DIGITAL), due(listOf(film)).items.map { it.entry.kind })
    }

    @Test fun everythingDueSinceTheLastCheckIsOneBatchAndIsAnnouncedOnce() {
        val batcher = CalendarReminderBatcher()
        val first = show("series:imdb:tt1", airedAgo(Duration.ofHours(2)))
        val second = show("series:imdb:tt2", airedAgo(Duration.ofHours(3)))
        assertEquals(2, batcher.nextBatch(due(listOf(first, second))).size)
        assertTrue(batcher.nextBatch(due(listOf(first, second))).isEmpty())

        val later = now.plus(Duration.ofHours(5))
        val third = show("series:imdb:tt3", CalendarEntry(season = 1, episode = 1, airInstantMs = now.plus(Duration.ofHours(2)).toEpochMilli()))
        val batch = batcher.nextBatch(due(listOf(first, second, third), at = later))
        assertEquals(listOf("series:imdb:tt3"), batch.map { it.title.key })
    }
}
