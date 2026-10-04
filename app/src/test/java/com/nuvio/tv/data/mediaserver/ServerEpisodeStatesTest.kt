package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEpisodeStatesTest {
    private val episodes = (1..4).map { video("tt1:1:$it", 1, it) }

    private fun state(episode: Int, played: Boolean, positionMs: Long = 0L, lastPlayed: Long? = 1_000L, season: Int = 1) =
        ServerUserState(
            videoId = "srv1:c:ep$episode",
            positionMs = positionMs,
            durationMs = 60_000L,
            played = played,
            lastPlayedEpochMs = lastPlayed,
            season = season,
            episode = episode
        )

    private fun local(episode: Int, position: Long, lastWatched: Long) = WatchProgress(
        contentId = "tt1",
        contentType = "series",
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt1:1:$episode",
        season = 1,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = 60_000L,
        lastWatched = lastWatched
    )

    @Test
    fun serverPlayedEpisodesCountWhenNuvioKnowsNothing() {
        val (progress, watched) = mergeServerEpisodeStates(
            listOf(state(1, played = true), state(2, played = false, lastPlayed = null)),
            emptyMap(), emptySet(), "tt1", episodes
        )
        assertEquals(setOf(1 to 1), watched)
        val first = progress.getValue(1 to 1)
        assertTrue(first.isCompleted())
        assertEquals("tt1:1:1", first.videoId)
        assertEquals(1_000L, first.lastWatched)
        assertNull(progress[1 to 2])
    }

    @Test
    fun aNewerLocalEntryWins() {
        val newer = local(2, position = 20_000L, lastWatched = 5_000L)
        val (progress, watched) = mergeServerEpisodeStates(
            listOf(state(2, played = true, lastPlayed = 1_000L)),
            mapOf((1 to 2) to newer), emptySet(), "tt1", episodes
        )
        assertEquals(newer, progress[1 to 2])
        assertFalse((1 to 2) in watched)
    }

    @Test
    fun aNewerServerPositionBecomesAResumePoint() {
        val (progress, _) = mergeServerEpisodeStates(
            listOf(state(3, played = false, positionMs = 30_000L, lastPlayed = 9_000L)),
            mapOf((1 to 3) to local(3, position = 5_000L, lastWatched = 2_000L)), emptySet(), "tt1", episodes
        )
        val resume = progress.getValue(1 to 3)
        assertEquals(30_000L, resume.position)
        assertEquals(60_000L, resume.duration)
        assertEquals(9_000L, resume.lastWatched)
        assertFalse(resume.isCompleted())
    }

    @Test
    fun episodesTheCatalogDoesNotListAreIgnored() {
        val (progress, watched) = mergeServerEpisodeStates(
            listOf(state(7, played = true), state(1, played = true, season = 2)),
            emptyMap(), emptySet(), "tt1", episodes
        )
        assertTrue(progress.isEmpty())
        assertTrue(watched.isEmpty())
    }

    @Test
    fun aServerPlayWithoutADateOnlyMarksTheEpisodeWatched() {
        val (progress, watched) = mergeServerEpisodeStates(
            listOf(state(1, played = true, lastPlayed = null)),
            emptyMap(), emptySet(), "tt1", episodes
        )
        assertEquals(setOf(1 to 1), watched)
        assertTrue(progress.isEmpty())
    }

    private val tmdbService = mockk<TmdbService> {
        coEvery { ensureTmdbId(any(), any(), any()) } returns null
        coEvery { tmdbToImdb(any(), any()) } returns null
    }
    private val metaRepository = mockk<MetaRepository> {
        every { getCachedMeta(any(), any()) } returns null
    }

    private fun loader(provider: FakeServerProvider): Triple<ServerEpisodeStates, ServerRepository, ServerConnection> {
        val (repository, connection) = fakeServerRepository(provider)
        val matcher = ServerMatcher(repository, tmdbService, metaRepository)
        return Triple(ServerEpisodeStates(repository, matcher), repository, connection)
    }

    @Test
    fun loadsStatesForANativeSeries() = runBlocking {
        val provider = FakeServerProvider()
        provider.seriesStates[FakeServerProvider.SHOW_ID] = listOf(state(1, played = true), state(2, played = false, positionMs = 10_000L))
        val (loader, _, connection) = loader(provider)
        val id = ServerItemRef(connection.id, FakeServerProvider.SHOW_ID).encode()
        assertEquals(2, loader.load("series", id).size)
    }

    @Test
    fun findsTheServerSeriesForACatalogTitle() = runBlocking {
        val provider = FakeServerProvider()
        provider.indexedIds[FakeServerProvider.SHOW_ID] = TrackingExternalIds(imdb = "tt0944947")
        provider.seriesStates[FakeServerProvider.SHOW_ID] = listOf(state(1, played = true))
        val (loader, repository, connection) = loader(provider)
        repository.setCatalogMetadata(connection.id, true)
        assertEquals(listOf(1), loader.load("series", "tt0944947").map { it.episode })
        assertTrue(loader.load("series", "tt0000001").isEmpty())
        assertTrue(loader.load("movie", "tt0944947").isEmpty())
    }

    @Test
    fun leavesPositionsOutWhenContinueWatchingImportIsOff() = runBlocking {
        val provider = FakeServerProvider()
        provider.seriesStates[FakeServerProvider.SHOW_ID] = listOf(state(2, played = false, positionMs = 10_000L))
        val (loader, repository, connection) = loader(provider)
        repository.setImportContinueWatching(connection.id, false)
        val id = ServerItemRef(connection.id, FakeServerProvider.SHOW_ID).encode()
        assertEquals(listOf(0L), loader.load("series", id).map { it.positionMs })
    }

    @Test
    fun aFailingServerGivesNothingAndIsNotMarkedUnreachable() = runBlocking {
        val provider = FakeServerProvider().apply { failingSeriesStates = true }
        val (loader, repository, connection) = loader(provider)
        val id = ServerItemRef(connection.id, FakeServerProvider.SHOW_ID).encode()
        assertTrue(loader.load("series", id).isEmpty())
        assertNull(repository.uiState.value.failures[connection.id])
    }

    @Test
    fun catalogTitlesOnlyUseServersThatShareWatchedMarks() = runBlocking {
        val provider = FakeServerProvider()
        provider.indexedIds[FakeServerProvider.SHOW_ID] = TrackingExternalIds(imdb = "tt0944947")
        provider.seriesStates[FakeServerProvider.SHOW_ID] = listOf(state(1, played = true))
        val (loader, repository, connection) = loader(provider)

        assertTrue(loader.load("series", "tt0944947").isEmpty())

        val native = ServerItemRef(connection.id, FakeServerProvider.SHOW_ID).encode()
        assertEquals(listOf(1), loader.load("series", native).map { it.episode })

        repository.setCatalogMetadata(connection.id, true)
        assertEquals(listOf(1), loader.load("series", "tt0944947").map { it.episode })
    }

    @Test
    fun anEpisodeUnmarkedInNuvioStaysUnwatched() {
        val states = listOf(state(1, played = true, lastPlayed = 1_000L), state(2, played = true, lastPlayed = 1_000L))
        val filtered = states.withoutUnmarked(mapOf((1 to 1) to 5_000L))

        val (progress, watched) = mergeServerEpisodeStates(filtered, emptyMap(), emptySet(), "tt1", episodes)

        assertEquals(setOf(1 to 2), watched)
        assertNull(progress[1 to 1])
        assertEquals(listOf(false, true), filtered.map { it.played })
    }

    @Test
    fun aServerPlayAfterTheUnmarkCountsAgain() {
        val states = listOf(state(1, played = true, lastPlayed = 9_000L), state(2, played = false, positionMs = 20_000L, lastPlayed = 2_000L))
        val filtered = states.withoutUnmarked(mapOf((1 to 1) to 5_000L, (1 to 2) to 5_000L))

        assertTrue(filtered[0].played)
        assertEquals(0L, filtered[1].positionMs)
        val (_, watched) = mergeServerEpisodeStates(filtered, emptyMap(), emptySet(), "tt1", episodes)
        assertEquals(setOf(1 to 1), watched)
    }

    @Test
    fun anUndatedServerPlayDoesNotOverrideAnUnmark() {
        val filtered = listOf(state(3, played = true, lastPlayed = null)).withoutUnmarked(mapOf((1 to 3) to 5_000L))
        val (_, watched) = mergeServerEpisodeStates(filtered, emptyMap(), emptySet(), "tt1", episodes)
        assertTrue(watched.isEmpty())
    }
}
