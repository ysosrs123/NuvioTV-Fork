package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchStateMutationStore
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingHistoryWriterRegistry
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerWatched
import com.nuvio.tv.data.mediaserver.meta
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressServerHistoryTest {
    private val show = ServerItemRef("cfake", "500").encode()
    private val episode = ServerItemRef("cfake", "901").encode()
    private val movie = ServerItemRef("cfake", "42").encode()

    private val added = mutableListOf<TrackingHistoryItem>()
    private val removed = mutableListOf<TrackingMediaReference>()
    private val writer = object : TrackingHistoryWriter {
        override val providerId = TrackingProviderId.TRAKT
        override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
            added += items
            return TrackingMutationResult(items.size)
        }
        override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult {
            removed += items
            return TrackingMutationResult(items.size)
        }
    }
    private val metaRepository = mockk<MetaRepository>(relaxed = true) {
        every { getCachedMeta(any(), any()) } returns null
    }
    private val serverWatched = mockk<ServerWatched>(relaxed = true) {
        coEvery { trackerIds(any()) } returns null
    }
    private val mutationStore = mockk<WatchStateMutationStore>(relaxed = true)

    private val repository: WatchProgressRepositoryImpl = run {
        val profileManager = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        val progressPreferences = mockk<WatchProgressPreferences>(relaxed = true)
        coEvery { progressPreferences.getAllRawEntries(any()) } returns emptyMap()
        val authManager = mockk<AuthManager>(relaxed = true) { every { isAuthenticated } returns false }
        val traktSettings = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { watchProgressSource } returns MutableStateFlow(WatchProgressSource.NUVIO_SYNC)
        }
        val trakt = mockk<TrackingProgressProvider>(relaxed = true) {
            every { providerId } returns TrackingProviderId.TRAKT
            every { isAuthenticated } returns flowOf(true)
        }
        WatchProgressRepositoryImpl(
            watchProgressPreferences = progressPreferences,
            traktSettingsDataStore = traktSettings,
            layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true),
            watchProgressSyncService = mockk<WatchProgressSyncService>(relaxed = true),
            watchedItemsPreferences = mockk<WatchedItemsPreferences>(relaxed = true),
            watchedItemsSyncService = mockk<WatchedItemsSyncService>(relaxed = true),
            authManager = authManager,
            metaRepository = metaRepository,
            tmdbService = mockk<TmdbService>(relaxed = true),
            profileManager = profileManager,
            trackingProgressProviders = TrackingProgressProviderRegistry(setOf(trakt)),
            trackingHistoryWriters = TrackingHistoryWriterRegistry(setOf(writer)),
            mutationStore = mutationStore,
            serverWatched = serverWatched
        )
    }

    @Test
    fun aServerEpisodeMarkedWatchedReachesTrackersUnderItsExternalIds() = runTest {
        coEvery { serverWatched.trackerIds(show) } returns TrackingExternalIds(imdb = "tt0944947", tmdb = 1399L, tvdb = "121361")

        repository.markAsCompleted(episodeProgress(season = 2, episode = 3), broadcastTrackingHistory = true)

        val media = added.single().media
        assertEquals("tt0944947", media.ids.imdb)
        assertEquals(1399L, media.ids.tmdb)
        assertEquals("121361", media.ids.tvdb)
        assertEquals(TrackingMediaKind.SHOW, media.kind)
        assertEquals("tt0944947", media.catalog?.contentId)
        assertNull(media.catalog?.videoId)
        assertEquals(2, media.episode?.season)
        assertEquals(3, media.episode?.number)
    }

    @Test
    fun theCachedImdbIdIsUsedWithoutAskingTheServer() = runTest {
        every { metaRepository.getCachedMeta(any(), movie) } returns
            meta(movie, ServerMediaKind.MOVIE, "Movie", imdbId = "tt0111161")

        repository.markAsCompleted(movieProgress(), broadcastTrackingHistory = true)

        val media = added.single().media
        assertEquals("tt0111161", media.catalog?.contentId)
        assertEquals(TrackingMediaKind.MOVIE, media.kind)
        coVerify(exactly = 0) { serverWatched.trackerIds(any()) }
    }

    @Test
    fun anUnmarkOnAServerTitleRemovesItFromTrackers() = runTest {
        coEvery { serverWatched.trackerIds(show) } returns TrackingExternalIds(tmdb = 1399L)

        repository.removeFromHistoryBatch(show, null, listOf(Triple(1, 1, episode), Triple(1, 2, null)))

        assertEquals(listOf("tmdb:1399", "tmdb:1399"), removed.map { it.catalog?.contentId })
        assertEquals(listOf(1, 2), removed.map { it.episode?.number })
        coVerify(exactly = 1) { serverWatched.trackerIds(show) }
    }

    @Test
    fun aServerTitleWithoutUsableIdsIsLeftOutAndOtherTitlesStillGo() = runTest {
        repository.removeFromHistory(movie, videoId = movie)
        assertTrue(removed.isEmpty())

        repository.removeFromHistory("tt0111161", videoId = "tt0111161")
        assertEquals(listOf("tt0111161"), removed.map { it.catalog?.contentId })
    }

    @Test
    fun watchedStateImportedFromTheServerIsNeverSentToTrackers() = runTest {
        coEvery { serverWatched.trackerIds(any()) } returns TrackingExternalIds(imdb = "tt0944947")

        repository.saveProgressBatch(listOf(episodeProgress(season = 1, episode = 1)), profileId = 1, syncRemote = false)
        repository.saveProgress(movieProgress(), profileId = 1, syncRemote = false)
        repository.markAsCompleted(movieProgress(), profileId = 1, broadcastTrackingHistory = false)

        assertTrue(added.isEmpty())
        assertTrue(removed.isEmpty())
        coVerify(exactly = 0) { serverWatched.trackerIds(any()) }
    }

    private fun episodeProgress(season: Int, episode: Int) = WatchProgress(
        contentId = show,
        contentType = "series",
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = this.episode,
        season = season,
        episode = episode,
        episodeTitle = "Episode $episode",
        position = 60_000L,
        duration = 60_000L,
        lastWatched = 100L
    )

    private fun movieProgress() = WatchProgress(
        contentId = movie,
        contentType = "movie",
        name = "Movie",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = movie,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 60_000L,
        duration = 60_000L,
        lastWatched = 100L
    )
}
