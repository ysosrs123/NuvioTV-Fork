package com.nuvio.tv.data.wetrakr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTrakrSyncEngineTest {
    private val now = 1_793_750_400_000L
    private val dune = WeTrakrIds(wetrakr = 126, imdb = "tt15239678", tmdb = 693134)
    private val severance = WeTrakrIds(wetrakr = 900, imdb = "tt11280740", tmdb = 95396)

    private class FakeRemote : WeTrakrSyncRemote {
        var activities = WeTrakrActivities(emptyMap())
        var movies = listOf<WeTrakrWatchedMovie>()
        var episodes = listOf<WeTrakrWatchedEpisode>()
        var lists = mapOf<Pair<WeTrakrList, String>, List<WeTrakrListEntry>>()
        var playback = listOf<WeTrakrPlayback>()
        var journal = WeTrakrJournalPage(emptyList(), null)
        val shows = mutableMapOf<Long, WeTrakrIds>()
        val calls = mutableListOf<String>()

        override suspend fun activities() = activities.also { calls += "activities" }
        override suspend fun watchedMovies() = movies.also { calls += "movies" }
        override suspend fun watchedEpisodes() = episodes.also { calls += "episodes" }
        override suspend fun list(list: WeTrakrList, target: String, since: String?) =
            lists[list to target].orEmpty().also { calls += "list:${list.wireValue}:$target:${since != null}" }
        override suspend fun playback() = playback.also { calls += "playback" }
        override suspend fun journal(since: String) = journal.also { calls += "journal:$since" }
        override suspend fun showIds(showId: Long) = shows[showId].also { calls += "show:$showId" }
    }

    private fun stamps(vararg pairs: Pair<String, String>) = WeTrakrActivities(
        mapOf("all" to (pairs.maxOfOrNull { it.second } ?: "2026-11-03T00:00:00.000Z"),
            "journal_visible_until" to "2026-11-03T10:00:00.000Z") + pairs
    )

    @Test
    fun `the first sync reads everything once in compact form`() = runTest {
        val remote = FakeRemote().apply {
            activities = stamps()
            movies = listOf(WeTrakrWatchedMovie(dune, "2026-11-01T20:00:00.000Z", 2))
            episodes = listOf(WeTrakrWatchedEpisode(900, severance, 1, 1, null, "2026-11-02T20:00:00.000Z"))
            lists = mapOf((WeTrakrList.PLANNING to "movies") to listOf(WeTrakrListEntry(WeTrakrTitle(WeTrakrType.MOVIE, dune, "Dune"))))
        }
        val result = WeTrakrSyncEngine(remote) { now }.synchronize(WeTrakrSnapshot(42))
        assertTrue(result.isInitialized)
        assertEquals("2026-11-03T10:00:00.000Z", result.journalMark)
        assertEquals(1, result.watchedMovies.size)
        assertEquals(severance, result.showIds[900])
        assertEquals(1, result.list(WeTrakrList.PLANNING).size)
        assertFalse(remote.calls.any { it.startsWith("journal") })
    }

    @Test
    fun `nothing changed costs a single call`() = runTest {
        val remote = FakeRemote().apply { activities = stamps("movies.last_tracking_watched_at" to "2026-11-01T00:00:00.000Z") }
        val previous = WeTrakrSnapshot(42, isInitialized = true, journalMark = "2026-11-03T09:00:00.000Z", activities = remote.activities.stamps)
        val result = WeTrakrSyncEngine(remote) { now }.synchronize(previous)
        assertEquals(listOf("activities"), remote.calls)
        assertEquals(now, result.checkedAtEpochMs)
    }

    @Test
    fun `the journal adds and removes plays, including from other devices`() = runTest {
        val remote = FakeRemote().apply {
            activities = stamps("movies.last_tracking_watched_at" to "2026-11-03T09:30:00.000Z")
            shows[901] = WeTrakrIds(wetrakr = 901, imdb = "tt0903747")
            journal = WeTrakrJournalPage(
                listOf(
                    entry("e1", "watched", "added", WeTrakrType.MOVIE, ids = WeTrakrIds(wetrakr = 7, imdb = "tt0468569")),
                    entry("e2", "watched", "removed", WeTrakrType.MOVIE, ids = dune),
                    entry("e3", "watched", "added", WeTrakrType.EPISODE, showId = 901, season = 2, number = 3),
                    entry("e4", "watched", "removed", WeTrakrType.EPISODE, showId = 900, season = 1, number = 1)
                ),
                "2026-11-03T10:00:00.000Z"
            )
        }
        val previous = WeTrakrSnapshot(
            42,
            watchedMovies = listOf(WeTrakrWatchedMovie(dune, "2026-11-01T20:00:00.000Z", 1)),
            watchedEpisodes = listOf(WeTrakrWatchedEpisode(900, severance, 1, 1, null, "2026-11-02T20:00:00.000Z")),
            isInitialized = true, journalMark = "2026-11-03T09:00:00.000Z"
        )
        val result = WeTrakrSyncEngine(remote) { now }.synchronize(previous)
        assertEquals(listOf("tt0468569"), result.watchedMovies.map { it.ids.imdb })
        assertEquals(listOf(Triple(901L, 2, 3)), result.watchedEpisodes.map { Triple(it.showId, it.season, it.number) })
        assertEquals("tt0903747", result.watchedEpisodes.single().showIds.imdb)
        assertEquals("journal:2026-11-03T08:59:55Z", remote.calls[1])
        assertEquals(setOf("e1", "e2", "e3", "e4"), result.appliedEntryIds)
        assertEquals("2026-11-03T10:00:00.000Z", result.journalMark)
    }

    @Test
    fun `a play with more than one watch stays watched when one is removed`() = runTest {
        val snapshot = WeTrakrSnapshot(42, watchedMovies = listOf(WeTrakrWatchedMovie(dune, "2026-11-01T20:00:00.000Z", 2)))
        val result = applyWeTrakrJournal(snapshot, listOf(entry("e1", "watched", "removed", WeTrakrType.MOVIE, ids = dune))) { null }
        assertEquals(1, result.watchedMovies.single().plays)
    }

    @Test
    fun `a play the app already applied is not counted twice when the journal reports it`() = runTest {
        val local = WeTrakrSnapshot(42).withLocalPlay(
            WeTrakrTarget(WeTrakrType.MOVIE, kotlinx.serialization.json.buildJsonObject { }, WeTrakrIds(imdb = "tt15239678")), now
        )
        assertEquals(1, local.pendingPlays.size)
        val result = applyWeTrakrJournal(local, listOf(entry("e1", "watched", "added", WeTrakrType.MOVIE, ids = dune))) { null }
        assertEquals(1, result.watchedMovies.single().plays)
        assertEquals(126L, result.watchedMovies.single().ids.wetrakr)
        assertTrue(result.pendingPlays.isEmpty())
    }

    @Test
    fun `entries already applied in the overlap are skipped`() = runTest {
        val remote = FakeRemote().apply {
            activities = stamps("movies.last_tracking_watched_at" to "2026-11-03T09:30:00.000Z")
            journal = WeTrakrJournalPage(listOf(entry("e1", "watched", "added", WeTrakrType.MOVIE, ids = dune)), "2026-11-03T10:00:00.000Z")
        }
        val previous = WeTrakrSnapshot(
            42, watchedMovies = listOf(WeTrakrWatchedMovie(dune, "2026-11-01T20:00:00.000Z", 1)),
            isInitialized = true, journalMark = "2026-11-03T09:00:00.000Z", appliedEntryIds = setOf("e1")
        )
        assertEquals(1, WeTrakrSyncEngine(remote) { now }.synchronize(previous).watchedMovies.single().plays)
    }

    @Test
    fun `a list that moved is read from the mark and removals come from the journal`() = runTest {
        val remote = FakeRemote().apply {
            activities = stamps("movies.last_tracking_planning_at" to "2026-11-03T09:30:00.000Z")
            lists = mapOf((WeTrakrList.PLANNING to "movies") to listOf(WeTrakrListEntry(WeTrakrTitle(WeTrakrType.MOVIE, dune, "Dune"))))
            journal = WeTrakrJournalPage(
                listOf(entry("e1", "planning", "removed", WeTrakrType.SHOW, ids = severance)), "2026-11-03T10:00:00.000Z"
            )
        }
        val previous = WeTrakrSnapshot(
            42, lists = mapOf(WeTrakrList.PLANNING to listOf(WeTrakrListEntry(WeTrakrTitle(WeTrakrType.SHOW, severance)))),
            isInitialized = true, journalMark = "2026-11-03T09:00:00.000Z"
        )
        val result = WeTrakrSyncEngine(remote) { now }.synchronize(previous)
        assertEquals(listOf("Dune"), result.list(WeTrakrList.PLANNING).map { it.title.title })
        assertTrue("list:planning:movies:true" in remote.calls)
        assertTrue("list:planning:shows:true" in remote.calls)
        assertFalse(remote.calls.any { it.startsWith("list:watched") })
        assertFalse("playback" in remote.calls)
    }

    @Test
    fun `an expired journal falls back to a full sync`() = runTest {
        val remote = FakeRemote().apply {
            activities = stamps("movies.last_tracking_watched_at" to "2026-11-03T09:30:00.000Z")
            journal = WeTrakrJournalPage(emptyList(), null, expired = true)
            movies = listOf(WeTrakrWatchedMovie(dune, "2026-11-01T20:00:00.000Z"))
        }
        val previous = WeTrakrSnapshot(42, isInitialized = true, journalMark = "2026-09-01T00:00:00.000Z")
        val result = WeTrakrSyncEngine(remote) { now }.synchronize(previous)
        assertTrue("movies" in remote.calls)
        assertEquals(1, result.watchedMovies.size)
    }

    @Test
    fun `playback is read again only when it moved`() = runTest {
        val remote = FakeRemote().apply {
            activities = stamps("episodes.last_playback_at" to "2026-11-03T09:30:00.000Z")
            journal = WeTrakrJournalPage(emptyList(), "2026-11-03T10:00:00.000Z")
        }
        val previous = WeTrakrSnapshot(42, isInitialized = true, journalMark = "2026-11-03T09:00:00.000Z")
        WeTrakrSyncEngine(remote) { now }.synchronize(previous)
        assertTrue("playback" in remote.calls)
    }

    private fun entry(
        id: String,
        category: String,
        status: String,
        type: WeTrakrType,
        ids: WeTrakrIds = WeTrakrIds(),
        showId: Long? = null,
        season: Int? = null,
        number: Int? = null
    ) = WeTrakrJournalEntry(id, category, status, "2026-11-03T09:${10 + id.drop(1).toInt()}:00.000Z", type, ids.wetrakr, ids,
        showId = showId, season = season, number = number, watchedAt = "2026-11-03T09:00:00.000Z")
}
