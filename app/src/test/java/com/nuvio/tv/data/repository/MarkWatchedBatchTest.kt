package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingHistoryWriterRegistry
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkWatchedBatchTest {

    @Test
    fun `a long show marked watched reaches Trakt's optimistic state in one go`() {
        val service = spyk(traktService(), recordPrivateCalls = true)
        val episodes = episodes(5_000)

        service.applyOptimisticProgressBatch(episodes, requestSync = true)

        val snapshot = service.optimisticProgressSnapshot()
        assertEquals(5_000, snapshot.size)
        assertTrue(snapshot.keys.containsAll(episodes.map { "${SHOW}_s${it.season}e${it.episode}" }))
        assertTrue(snapshot.values.all { it.source == WatchProgress.SOURCE_TRAKT_PLAYBACK && it.progressPercent == 100f })
        verify(exactly = 1) { service["requestFastSync"]() }
        verify(exactly = 0) { service.applyOptimisticProgress(any()) }
    }

    @Test
    fun `a quiet batch does not ask for a sync`() {
        val service = spyk(traktService(), recordPrivateCalls = true)

        service.applyOptimisticProgressBatch(episodes(3), requestSync = false)

        assertEquals(3, service.optimisticProgressSnapshot().size)
        verify(exactly = 0) { service["requestFastSync"]() }
    }

    @Test
    fun `unmarking many episodes clears them from Trakt's optimistic state in one go`() {
        val service = spyk(traktService(), recordPrivateCalls = true)
        service.applyOptimisticProgressBatch(episodes(10), requestSync = false)

        service.applyOptimisticRemovalBatch(SHOW, (1..8).map { 1 to it })

        assertEquals(setOf("${SHOW}_s1e9", "${SHOW}_s1e10"), service.optimisticProgressSnapshot().keys)
        verify(exactly = 1) { service["requestFastSync"]() }
        verify(exactly = 0) { service.applyOptimisticRemoval(any(), any(), any()) }
    }

    @Test
    fun `the Trakt provider hands the whole batch to the service once`() {
        val service = mockk<TraktProgressService>(relaxed = true)
        val provider = TraktTrackingProgressProvider(service, mockk(relaxed = true), mockk(relaxed = true))
        val list = episodes(5_000)

        provider.applyOptimisticProgressBatch(list, quiet = false)
        provider.applyOptimisticRemovalBatch(SHOW, listOf(1 to 1, 1 to 2))

        verify(exactly = 1) { service.applyOptimisticProgressBatch(list, requestSync = true) }
        verify(exactly = 0) { service.applyOptimisticProgress(any()) }
        verify(exactly = 1) { service.applyOptimisticRemovalBatch(SHOW, listOf(1 to 1, 1 to 2)) }
        verify(exactly = 0) { service.applyOptimisticRemoval(any(), any(), any()) }
    }

    @Test
    fun `marking a long show watched gives the provider one batch`() = runBlocking {
        val provider = trackingProvider()
        val repository = repository(provider)
        assertTrue(
            "the provider never became the source",
            withTimeoutOrNull(5_000) { repository.observeWatchedMovieIds().first { SENTINEL in it } } != null
        )
        val batch = slot<List<WatchProgress>>()
        every { provider.applyOptimisticProgressBatch(capture(batch), false) } returns Unit

        repository.markAsCompletedBatch(episodes(5_000).map { it.copy(position = 10L, progressPercent = null) })

        verify(exactly = 1) { provider.applyOptimisticProgressBatch(any(), false) }
        verify(exactly = 0) { provider.applyOptimisticProgress(any(), any()) }
        assertEquals(5_000, batch.captured.size)
        assertTrue(batch.captured.all { it.progressPercent == 100f && it.position == it.duration })
    }

    @Test
    fun `unmarking many episodes gives each provider one batch`() = runBlocking {
        val provider = trackingProvider()
        val repository = repository(provider)

        repository.removeFromHistoryBatch(SHOW, null, (1..500).map { Triple(1, it, null) })

        verify(exactly = 1) { provider.applyOptimisticRemovalBatch(SHOW, (1..500).map { 1 to it }) }
        verify(exactly = 0) { provider.applyOptimisticRemoval(any(), any(), any()) }
    }

    private fun trackingProvider() = mockk<TrackingProgressProvider>(relaxed = true) {
        every { providerId } returns TrackingProviderId.TRAKT
        every { isAuthenticated } returns MutableStateFlow(true)
        every { watchedMovieIds } returns MutableStateFlow(setOf(SENTINEL))
        every { reportedWatchedMovieIds } returns MutableStateFlow(setOf(SENTINEL))
    }

    private fun repository(provider: TrackingProgressProvider): WatchProgressRepositoryImpl {
        val profileManager = mockk<ProfileManager>(relaxed = true) {
            every { activeProfileId } returns MutableStateFlow(1)
        }
        val progressPreferences = mockk<WatchProgressPreferences>(relaxed = true) {
            coEvery { getAllRawEntries(any()) } returns emptyMap()
            every { allProgress } returns flowOf(emptyList())
        }
        val traktSettings = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { watchProgressSource } returns MutableStateFlow(WatchProgressSource.TRAKT)
        }
        return WatchProgressRepositoryImpl(
            watchProgressPreferences = progressPreferences,
            traktSettingsDataStore = traktSettings,
            layoutPreferenceDataStore = mockk(relaxed = true),
            watchProgressSyncService = mockk(relaxed = true),
            watchedItemsPreferences = mockk<WatchedItemsPreferences>(relaxed = true) {
                every { pendingProviderMarks } returns flowOf(emptySet())
            },
            watchedItemsSyncService = mockk(relaxed = true),
            authManager = mockk<AuthManager>(relaxed = true) { every { isAuthenticated } returns false },
            metaRepository = mockk(relaxed = true),
            tmdbService = mockk(relaxed = true),
            profileManager = profileManager,
            trackingProgressProviders = TrackingProgressProviderRegistry(setOf(provider)),
            trackingHistoryWriters = TrackingHistoryWriterRegistry(emptySet()),
            mutationStore = mockk(relaxed = true),
            serverWatched = mockk(relaxed = true),
            addonEventDispatcher = mockk(relaxed = true)
        )
    }

    private fun traktService(): TraktProgressService {
        val profile = MutableStateFlow(1)
        val auth = MutableStateFlow(TraktAuthState(accessToken = "a", refreshToken = "r", userSlug = "sam"))
        val authService = mockk<TraktAuthService>(relaxed = true) {
            every { isCircuitClosed() } returns false
            coEvery { executeAuthorizedRequest<Any>(any()) } returns null
        }
        val profileManager = mockk<ProfileManager>(relaxed = true) {
            every { activeProfileId } returns profile
            every { activeProfileReady } returns MutableStateFlow(true)
        }
        val authStore = mockk<TraktAuthDataStore>(relaxed = true) {
            every { state } returns auth
            coEvery { getCurrentState(any<Int>()) } returns auth.value
        }
        return TraktProgressService(
            appContext = mockk(relaxed = true),
            traktApi = mockk(relaxed = true),
            traktAuthService = authService,
            metaRepository = mockk(relaxed = true),
            tmdbService = mockk(relaxed = true),
            traktSettingsDataStore = mockk(relaxed = true),
            layoutPreferenceDataStore = mockk(relaxed = true),
            traktEpisodeMappingService = mockk(relaxed = true),
            profileManager = profileManager,
            watchedSeriesStateHolder = mockk(relaxed = true),
            traktAuthDataStore = authStore,
            savedWatchedMovies = TraktSavedWatchedMovies(FakeTraktWatchedMoviesStorage()),
            appVisibility = com.nuvio.tv.core.calendar.CalendarAppVisibility(),
            screensaverController = mockk(relaxed = true) {
                every { playbackActive } returns MutableStateFlow(false)
            }
        )
    }

    private fun episodes(count: Int): List<WatchProgress> = (1..count).map { index ->
        val season = (index - 1) / 250 + 1
        val episode = (index - 1) % 250 + 1
        WatchProgress(
            contentId = SHOW,
            contentType = "series",
            name = "Long show",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$SHOW:$season:$episode",
            season = season,
            episode = episode,
            episodeTitle = null,
            position = 1_000L,
            duration = 1_000L,
            lastWatched = 100L,
            progressPercent = 100f
        )
    }

    private companion object {
        const val SHOW = "tt0055665"
        const val SENTINEL = "tt9999999"
    }
}
