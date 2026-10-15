package com.nuvio.tv.core.calendar

import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CalendarStorageTest {
    @get:Rule val folder = TemporaryFolder()

    private val now = Instant.parse("2026-10-05T23:00:00Z")
    private var clockMs = now.toEpochMilli()
    private val storage by lazy { CalendarStorage({ File(folder.root, "calendar") }, { clockMs }) }

    private fun record(index: Int, entries: Int = 3, title: String = "Episode title") = CalendarTitleRecord(
        key = "series:imdb:tt$index",
        type = CalendarTitleType.SERIES,
        ids = CalendarIds(imdb = "tt$index", tmdb = index.toLong(), trakt = index.toLong() + 100_000),
        name = "Show number $index",
        contentId = "tt$index",
        poster = "https://image.tmdb.org/t/p/w342/abcdefghijklmnopqrstuvwxyz$index.jpg",
        origins = setOf(CalendarOrigin.LIBRARY, CalendarOrigin.TRAKT_WATCHED),
        lastActivityMs = now.toEpochMilli(),
        tvmazeId = 10_000 + index,
        status = CalendarShowStatus.RUNNING,
        network = "Network",
        sources = mapOf(
            CalendarSource.TVMAZE to (1..entries).map { episode ->
                CalendarEntry(
                    season = 3, episode = episode, title = "$title $episode",
                    airInstantMs = now.plus(Duration.ofDays(episode.toLong() * 7 - 7)).toEpochMilli(),
                    source = CalendarSource.TVMAZE
                )
            }
        ),
        fetchedAtMs = now.toEpochMilli(),
        nextRefreshAtMs = now.plus(Duration.ofHours(6)).toEpochMilli()
    )

    @Test fun whatIsSavedComesBackTheSame() = runBlocking {
        val snapshot = CalendarSnapshot(
            titles = listOf(
                record(1),
                record(2).copy(
                    sources = mapOf(
                        CalendarSource.TMDB to listOf(
                            CalendarEntry(season = 1, episode = 1, airDate = "2026-10-09", label = CalendarEpisodeLabel.NEW_SHOW, source = CalendarSource.TMDB)
                        )
                    ),
                    status = CalendarShowStatus.UNKNOWN,
                    failures = 2
                )
            ),
            tvmazeIds = mapOf("imdb:tt1" to CalendarTvMazeId(10_001, now.toEpochMilli()), "imdb:tt7" to CalendarTvMazeId(null, now.toEpochMilli())),
            traktCalendarFetchedAtMs = 99L,
            seenReminders = mapOf("series:imdb:tt1|S3E1" to now.toEpochMilli())
        )
        assertTrue(storage.save(1, snapshot, storage.generation()))
        val loaded = storage.load(1)
        assertEquals(snapshot.copy(updatedAtMs = clockMs), loaded)
        assertEquals(snapshot.titles[1].entries, loaded.titles[1].entries)
        assertTrue(storage.file(1).exists())
        assertEquals(CalendarSnapshot(), storage.load(2))
    }

    @Test fun boundsAreAppliedOnSave() = runBlocking {
        val oldMissing = now.minus(Duration.ofDays(31)).toEpochMilli()
        val snapshot = CalendarSnapshot(
            titles = (1..320).map { record(it, entries = if (it == 1) 60 else 1) },
            tvmazeIds = mapOf("imdb:tt1" to CalendarTvMazeId(5, 1L), "imdb:tt2" to CalendarTvMazeId(null, oldMissing))
        )
        storage.save(1, snapshot, storage.generation(), force = true)
        val loaded = storage.load(1)
        assertEquals(CALENDAR_MAX_TITLES, loaded.titles.size)
        assertEquals(CALENDAR_MAX_ENTRIES_PER_TITLE, loaded.titles.first().entries.size)
        assertEquals(setOf("imdb:tt1"), loaded.tvmazeIds.keys)
    }

    @Test fun writesAreThrottledUnlessForced() = runBlocking {
        val generation = storage.generation()
        assertTrue(storage.save(1, CalendarSnapshot(traktCalendarFetchedAtMs = 1), generation))
        clockMs += 29_000
        assertFalse(storage.save(1, CalendarSnapshot(traktCalendarFetchedAtMs = 2), generation))
        assertEquals(1L, storage.load(1).traktCalendarFetchedAtMs)
        assertTrue(storage.save(1, CalendarSnapshot(traktCalendarFetchedAtMs = 3), generation, force = true))
        clockMs += 30_000
        assertTrue(storage.save(1, CalendarSnapshot(traktCalendarFetchedAtMs = 4), generation))
        assertEquals(4L, storage.load(1).traktCalendarFetchedAtMs)
    }

    @Test fun profileRemovalAndSignOutClearFilesAndDropWritesThatStartedBefore() = runBlocking {
        val before = storage.generation()
        storage.save(1, CalendarSnapshot(titles = listOf(record(1))), before, force = true)
        storage.save(3, CalendarSnapshot(titles = listOf(record(3))), before, force = true)

        storage.removeProfile(3)
        assertFalse(storage.file(3).exists())
        assertTrue(storage.file(1).exists())
        assertFalse(storage.save(3, CalendarSnapshot(titles = listOf(record(3))), before, force = true))
        assertFalse(storage.file(3).exists())

        storage.clearAllProfiles()
        assertFalse(storage.file(1).exists())
        assertEquals(CalendarSnapshot(), storage.load(1))
        assertTrue(storage.save(1, CalendarSnapshot(), storage.generation(), force = true))
    }

    @Test fun anUnreadableFileStartsEmpty() = runBlocking {
        storage.file(1).parentFile!!.mkdirs()
        storage.file(1).writeText("{ not json")
        assertEquals(CalendarSnapshot(), storage.load(1))
    }

    @Test fun sizeOfAFullCache() = runBlocking {
        val typical = CalendarSnapshot(titles = (1..150).map { record(it, entries = 10, title = "A typical episode name") })
        storage.save(1, typical, storage.generation(), force = true)
        val typicalBytes = storage.file(1).length()
        val worst = CalendarSnapshot(titles = (1..300).map { record(it, entries = 40, title = "A rather long episode title, part") })
        storage.save(2, worst, storage.generation(), force = true)
        val worstBytes = storage.file(2).length()
        println("calendar cache: 150 shows x 10 releases = ${typicalBytes / 1024} KB, 300 x 40 = ${worstBytes / 1024} KB")
        assertTrue("typical cache $typicalBytes bytes", typicalBytes < 500 * 1024)
    }

    @Test fun seenKeysAreKeptThirtyDays() {
        val seen = emptyMap<String, Long>().withSeen(listOf("a", "b"), now.minus(Duration.ofDays(31)))
            .withSeen(listOf("c"), now)
        val bounded = boundCalendarSnapshot(CalendarSnapshot(seenReminders = seen), now)
        assertEquals(setOf("c"), bounded.seenReminders.keys)
    }

    private fun allSources(index: Int, bell: Boolean): CalendarTitleRecord {
        val base = record(index, entries = 40, title = "A rather long episode title, part")
        val tvMaze = base.sources.getValue(CalendarSource.TVMAZE)
        fun copyAs(source: CalendarSource) = tvMaze.map { it.copy(source = source) }
        return base.copy(
            origins = if (bell) base.origins + CalendarOrigin.BELL else base.origins,
            lastActivityMs = now.toEpochMilli() - index * 60_000L,
            sources = mapOf(
                CalendarSource.TRAKT to copyAs(CalendarSource.TRAKT),
                CalendarSource.TVMAZE to tvMaze,
                CalendarSource.TMDB to copyAs(CalendarSource.TMDB).map { it.copy(airInstantMs = null, airDate = "2026-10-20") },
                CalendarSource.ADDON to copyAs(CalendarSource.ADDON).map { it.copy(airInstantMs = null, airDate = "2026-10-21") }
            ),
            sourceFetchedAtMs = CalendarSource.entries.associateWith { now.toEpochMilli() }
        )
    }

    @Test fun theFileStaysUnderOneMegabyteDroppingTheLeastActiveTitlesButNeverBellTitles() = runBlocking {
        val bells = setOf(299, 300)
        val worst = CalendarSnapshot(titles = (1..300).map { allSources(it, bell = it in bells) })
        storage.save(1, worst, storage.generation(), force = true)
        val bytes = storage.file(1).length()
        val kept = storage.load(1).titles
        println("calendar cache, every source full: ${bytes / 1024} KB, ${kept.size} of 300 titles kept")
        assertTrue("$bytes bytes", bytes <= 1_000_000L)
        assertTrue(kept.size < 300)
        assertTrue(kept.any { it.key == "series:imdb:tt1" })
        assertTrue(kept.any { it.key == "series:imdb:tt299" } && kept.any { it.key == "series:imdb:tt300" })
        assertTrue(kept.none { it.key == "series:imdb:tt298" })
        assertTrue(kept.all { it.entries.size <= CALENDAR_MAX_ENTRIES_PER_TITLE })
    }

    @Test fun trimmingDropsTheOldestActivityFirst() {
        val snapshot = CalendarSnapshot(titles = (1..5).map { allSources(it, bell = it == 5) })
        assertEquals(listOf(1, 2, 5), trimLeastActiveTitles(snapshot, 2).titles.map { it.ids.tmdb!!.toInt() })
    }
}
