package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUserStateProjectionTest {
    private val provider = FakeServerProvider()
    private val server = fakeServerRepository(provider)
    private val repository = server.first
    private val connection = server.second
    private val progressRepository = mockk<WatchProgressRepository>(relaxed = true)
    private val progressPreferences = mockk<WatchProgressPreferences> {
        every { getProgress(any(), any()) } returns flowOf(null)
        every { getEpisodeProgress(any(), any(), any(), any()) } returns flowOf(null)
    }
    private val watchedPreferences = mockk<WatchedItemsPreferences>(relaxed = true)
    private val activeProfileIds = MutableStateFlow(1)
    private val imports = MemoryResumeImports()
    private var now = 0L
    private val projection = ServerUserStateProjection(
        catalog = mockk(relaxed = true),
        repository = repository,
        watchProgressRepository = progressRepository,
        watchProgressPreferences = progressPreferences,
        watchedItemsPreferences = watchedPreferences,
        profileManager = mockk { every { activeProfileId } returns activeProfileIds },
        resumeImports = imports,
        clock = { now }
    )

    private val seriesId = ServerItemRef(connection.id, FakeServerProvider.SHOW_ID).encode()

    private fun state(episode: Int, played: Boolean, positionMs: Long = 0L, lastPlayed: Long? = 1_000L) = ServerUserState(
        videoId = ServerItemRef(connection.id, "ep$episode").encode(),
        positionMs = positionMs,
        durationMs = 60_000L,
        played = played,
        lastPlayedEpochMs = lastPlayed,
        season = 1,
        episode = episode,
        title = "Episode $episode"
    )

    private fun seriesDetails() = ServerItemDetails(
        meta = meta(seriesId, ServerMediaKind.SERIES, "Show"),
        externalIds = TrackingExternalIds(),
        userStates = listOf(
            state(1, played = true),
            state(2, played = true),
            state(3, played = false),
            state(4, played = false, positionMs = 30_000L),
            state(5, played = false, positionMs = 10_000L),
            state(6, played = false, positionMs = 10_000L, lastPlayed = null)
        )
    )

    private fun savedBatches(): List<List<WatchProgress>> {
        val saved = mutableListOf<List<WatchProgress>>()
        coVerify(atLeast = 0) { progressRepository.saveProgressBatch(capture(saved), any<Int>(), any()) }
        return saved.filter { it.isNotEmpty() }
    }

    @Test
    fun mirrorsServerStateIntoLocalHistory() = runBlocking {
        every { watchedPreferences.getWatchedEpisodesForContent(seriesId, 1) } returns flowOf(setOf(1 to 2, 1 to 3))
        every { progressPreferences.getEpisodeProgress(seriesId, 1, 5, 1) } returns flowOf(
            WatchProgress(seriesId, "series", "Show", null, null, null, "v", 1, 5, null, 1L, 2L, lastWatched = 9_000L)
        )

        projection.apply(seriesDetails())

        val marked = slot<List<WatchedItem>>()
        coVerify { watchedPreferences.markAsWatchedBatch(capture(marked), 1) }
        assertEquals(listOf(1 to 1), marked.captured.map { it.season to it.episode })
        coVerify { watchedPreferences.unmarkAsWatchedBatch(seriesId, listOf(1 to 3), 1) }

        val saved = slot<List<WatchProgress>>()
        coVerify { progressRepository.saveProgressBatch(capture(saved), 1, false) }
        assertEquals(listOf(4), saved.captured.map { it.episode })
        assertEquals(30_000L, saved.captured.single().position)
        assertEquals(ServerCatalog.baseUrl(connection.id), saved.captured.single().addonBaseUrl)
        coVerify(exactly = 0) { progressRepository.saveProgressBatch(any(), any<Boolean>()) }
    }

    @Test
    fun importOffKeepsWatchedMarksButNoResumePositions() = runBlocking {
        repository.setImportContinueWatching(connection.id, false)
        every { watchedPreferences.getWatchedEpisodesForContent(seriesId, 1) } returns flowOf(setOf(1 to 2, 1 to 3))

        projection.apply(seriesDetails())

        val marked = slot<List<WatchedItem>>()
        coVerify { watchedPreferences.markAsWatchedBatch(capture(marked), 1) }
        assertEquals(listOf(1 to 1), marked.captured.map { it.season to it.episode })
        coVerify { watchedPreferences.unmarkAsWatchedBatch(seriesId, listOf(1 to 3), 1) }
        assertTrue(savedBatches().isEmpty())
    }

    @Test
    fun ignoresItemsFromUnknownConnections() = runBlocking {
        val details = ServerItemDetails(
            meta = meta(ServerItemRef("missing", "1").encode(), ServerMediaKind.MOVIE, "Film"),
            externalIds = TrackingExternalIds(),
            userStates = listOf(state(1, played = true))
        )

        projection.apply(details)

        coVerify(exactly = 0) { watchedPreferences.markAsWatchedBatch(any(), any()) }
        assertTrue(savedBatches().isEmpty())
    }

    @Test
    fun serverResumeItemsLandInContinueWatching() = runBlocking {
        provider.resumeEntries += provider.resumeMovie(connection, "3", positionMs = 1_200_000L, lastPlayed = 5_000L)
        provider.resumeEntries += provider.resumeEpisode(connection, "ep7", season = 2, episode = 7, positionMs = 600_000L, lastPlayed = 6_000L)

        projection.importResume(connection.id)

        val saved = savedBatches().single()
        val movie = saved.first { it.contentType == "movie" }
        assertEquals(ServerItemRef(connection.id, "3").encode(), movie.contentId)
        assertEquals(movie.contentId, movie.videoId)
        assertEquals(1_200_000L, movie.position)
        assertEquals(5_000L, movie.lastWatched)
        assertEquals(ServerCatalog.baseUrl(connection.id), movie.addonBaseUrl)
        val episode = saved.first { it.contentType == "series" }
        assertEquals(seriesId, episode.contentId)
        assertEquals(ServerItemRef(connection.id, "ep7").encode(), episode.videoId)
        assertEquals(2 to 7, episode.season to episode.episode)
        coVerify(exactly = 0) { progressRepository.saveProgressBatch(any(), any<Boolean>()) }
    }

    @Test
    fun importOffBringsNoResumeItems() = runBlocking {
        repository.setImportContinueWatching(connection.id, false)
        provider.resumeEntries += provider.resumeMovie(connection, "3", positionMs = 1_200_000L, lastPlayed = 5_000L)

        projection.importResume(connection.id, force = true)

        assertEquals(0, provider.resumeRequests)
        assertTrue(savedBatches().isEmpty())
    }

    @Test
    fun disabledServersBringNoResumeItems() = runBlocking {
        repository.setEnabled(connection.id, false)
        provider.resumeEntries += provider.resumeMovie(connection, "3", positionMs = 1_200_000L, lastPlayed = 5_000L)

        projection.importResume(connection.id, force = true)

        assertEquals(0, provider.resumeRequests)
        assertTrue(savedBatches().isEmpty())
    }

    @Test
    fun olderServerPositionsDoNotReplaceLocalProgress() = runBlocking {
        val movieId = ServerItemRef(connection.id, "3").encode()
        every { progressPreferences.getProgress(movieId, 1) } returns flowOf(
            WatchProgress(movieId, "movie", "Item 3", null, null, null, movieId, null, null, null, 1L, 2L, lastWatched = 9_000L)
        )
        provider.resumeEntries += provider.resumeMovie(connection, "3", positionMs = 1_200_000L, lastPlayed = 5_000L)

        projection.importResume(connection.id)

        assertTrue(savedBatches().isEmpty())
    }

    @Test
    fun aRemovedTitleOnlyReturnsAfterItIsPlayedOnTheServerAgain() = runBlocking {
        provider.resumeEntries += provider.resumeMovie(connection, "3", positionMs = 1_200_000L, lastPlayed = 5_000L)

        projection.importResume(connection.id)
        projection.importResume(connection.id, force = true)
        assertEquals(1, savedBatches().size)

        provider.resumeEntries.clear()
        provider.resumeEntries += provider.resumeMovie(connection, "3", positionMs = 1_500_000L, lastPlayed = 8_000L)
        projection.importResume(connection.id, force = true)

        val batches = savedBatches()
        assertEquals(2, batches.size)
        assertEquals(1_500_000L, batches.last().single().position)
    }

    @Test
    fun repeatedRowLoadsAskTheServerAtMostOncePerMinute() = runBlocking {
        projection.importResume(connection.id)
        now += 30_000L
        projection.importResume(connection.id)
        assertEquals(1, provider.resumeRequests)

        now += 31_000L
        projection.importResume(connection.id)
        assertEquals(2, provider.resumeRequests)
    }

    @Test
    fun skipsUnfinishedStatesWithoutUsableNumbers() = runBlocking {
        provider.resumeEntries += provider.resumeEpisode(connection, "special", season = null, episode = null, positionMs = 600_000L, lastPlayed = 6_000L)
        provider.resumeEntries += provider.resumeMovie(connection, "4", positionMs = 0L, lastPlayed = 6_000L)
        provider.resumeEntries += provider.resumeMovie(connection, "5", positionMs = 10_000L, lastPlayed = null)

        projection.importResume(connection.id)

        assertTrue(savedBatches().isEmpty())
    }

    @Test
    fun addonMetadataServersUpdateATitleNuvioAlreadyTracksInsteadOfDuplicatingIt() = runBlocking {
        repository.setCatalogMetadata(connection.id, true)
        provider.indexedIds["3"] = TrackingExternalIds(imdb = "tt0078788")
        provider.indexedIds[FakeServerProvider.SHOW_ID] = TrackingExternalIds(imdb = "tt0903747")
        every { progressPreferences.getProgress("tt0078788", 1) } returns flowOf(
            WatchProgress("tt0078788", "movie", "Film", null, null, null, "tt0078788", null, null, null, 1L, 2L, lastWatched = 1L)
        )
        every { progressPreferences.getProgress("tt0903747", 1) } returns flowOf(
            WatchProgress("tt0903747", "series", "Show", null, null, null, "tt0903747:2:7", 2, 7, null, 1L, 2L, lastWatched = 1L)
        )
        provider.resumeEntries += provider.resumeMovie(connection, "3", positionMs = 1_200_000L, lastPlayed = 5_000L)
        provider.resumeEntries += provider.resumeMovie(connection, "9", positionMs = 900_000L, lastPlayed = 5_500L)
        provider.resumeEntries += provider.resumeEpisode(connection, "ep7", season = 2, episode = 7, positionMs = 600_000L, lastPlayed = 6_000L)

        projection.importResume(connection.id)

        val saved = savedBatches().single()
        assertEquals(
            listOf(ServerItemRef(connection.id, "9").encode(), "tt0078788", "tt0903747"),
            saved.map { it.contentId }
        )
        val movie = saved[1]
        assertEquals(1_200_000L to 5_000L, movie.position to movie.lastWatched)
        assertEquals("tt0078788", movie.videoId)
        val episode = saved[2]
        assertEquals(Triple("tt0903747:2:7", 2, 7), Triple(episode.videoId, episode.season, episode.episode))
        assertEquals(600_000L to 6_000L, episode.position to episode.lastWatched)
    }

    @Test
    fun anOlderServerPositionLeavesATrackedTitleAlone() = runBlocking {
        repository.setCatalogMetadata(connection.id, true)
        provider.indexedIds["3"] = TrackingExternalIds(imdb = "tt0078788")
        every { progressPreferences.getProgress("tt0078788", 1) } returns flowOf(
            WatchProgress("tt0078788", "movie", "Film", null, null, null, "tt0078788", null, null, null, 1L, 2L, lastWatched = 9_000L)
        )
        provider.resumeEntries += provider.resumeMovie(connection, "3", positionMs = 1_200_000L, lastPlayed = 5_000L)

        projection.importResume(connection.id)

        assertTrue(savedBatches().isEmpty())
    }

    @Test
    fun writesToTheProfileThatOwnsTheServers() = runBlocking {
        provider.resumeEntries += provider.resumeMovie(connection, "3", positionMs = 1_200_000L, lastPlayed = 5_000L)
        activeProfileIds.value = 2

        projection.importResume(connection.id)

        assertEquals(0, provider.resumeRequests)
        assertTrue(savedBatches().isEmpty())
    }

    private class MemoryResumeImports : ServerResumeImports {
        private val values = mutableMapOf<String, Map<String, Long>>()
        override fun read(profileId: Int, connectionId: String): Map<String, Long> = values["$profileId:$connectionId"].orEmpty()
        override fun write(profileId: Int, connectionId: String, imported: Map<String, Long>) {
            values["$profileId:$connectionId"] = imported
        }
    }
}
