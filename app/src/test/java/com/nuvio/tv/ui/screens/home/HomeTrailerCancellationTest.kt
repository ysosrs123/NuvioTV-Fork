package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.data.trailer.TrailerLookupResult
import com.nuvio.tv.data.trailer.TrailerPlaybackSource
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class HomeTrailerCancellationTest {
    private val created = mutableListOf<HomeViewModel>()
    @Before fun setup() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun cleanup() {
        created.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }
    private fun HomeViewModel.request(id: String) = requestTrailerPreviewPipeline(id, id, "2026", "movie")
    private fun HomeViewModel.stubSource() {
        coEvery { tmdbService.ensureTmdbId(any(), any()) } returns "123"
        coEvery { trailerService.lookupTrailer(any(), any(), any(), any(), any()) } returns
            TrailerLookupResult(TrailerPlaybackSource("https://example.test/trailer.mp4"))
    }

    @Test fun `duplicate focus during debounce still resolves the focused trailer`() = runTest {
        val vm = newViewModel(); vm.stubSource()
        vm.request("A")
        advanceTimeBy(100)
        vm.request("A")
        advanceTimeBy(400); runCurrent()
        assertEquals("https://example.test/trailer.mp4", vm.trailerPreviewUrlsState["A"])
        coVerify(exactly = 1) { vm.trailerService.lookupTrailer(any(), any(), any(), any(), any()) }
    }

    @Test fun `moving to a cached title cancels obsolete resolution`() = runTest {
        val vm = newViewModel(); vm.stubSource()
        var cancelled = false
        coEvery { vm.trailerService.lookupTrailer(any(), any(), any(), any(), any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        vm.request("A"); advanceTimeBy(400); runCurrent()
        vm.trailerPreviewUrlsState["B"] = "https://example.test/cached.mp4"
        vm.request("B"); runCurrent()
        assertTrue(cancelled)
        assertFalse(vm.trailerPreviewNegativeCache.contains("A"))
        assertTrue(vm.trailerPreviewLoadingIds.isEmpty())
    }

    @Test fun `leaving Home cancels resolution and prevents background requests`() = runTest {
        val vm = newViewModel(); vm.stubSource()
        vm.request("A"); runCurrent()
        vm.setTrailerPreviewActive(false)
        vm.request("B")
        advanceTimeBy(500); runCurrent()
        coVerify(exactly = 0) { vm.trailerService.lookupTrailer(any(), any(), any(), any(), any()) }
        assertTrue(vm.trailerPreviewLoadingIds.isEmpty())
        vm.setTrailerPreviewActive(true)
        vm.request("B"); advanceTimeBy(400); runCurrent()
        assertNotNull(vm.trailerPreviewUrlsState["B"])
    }

    @Test fun `rapid A B A focus does not let old cleanup erase latest loading state`() = runTest {
        val vm = newViewModel(); vm.stubSource()
        vm.request("A"); runCurrent()
        vm.request("B")
        vm.request("A"); runCurrent()
        assertTrue(vm.trailerPreviewLoadingIds.contains("A"))
        advanceTimeBy(400); runCurrent()
        assertNotNull(vm.trailerPreviewUrlsState["A"])
        assertNull(vm.trailerPreviewUrlsState["B"])
    }

    private fun newViewModel(): HomeViewModel {
        val profileManager = mockk<com.nuvio.tv.core.profile.ProfileManager>(relaxed = true) {
            every { activeProfileReady } returns MutableStateFlow(false)
            every { activeProfileId } returns MutableStateFlow(1)
        }
        val cwEnrichmentCache =
            mockk<com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache>(relaxed = true) {
                every { cacheCleared } returns MutableStateFlow(0)
            }
        val watchProgressRepository =
            mockk<com.nuvio.tv.domain.repository.WatchProgressRepository>(relaxed = true) {
                every { getAllEpisodeProgress(any()) } returns flowOf(emptyMap())
            }
        val viewModel = HomeViewModel(
            appContext = mockk(relaxed = true),
            addonRepository = mockk(relaxed = true),
            startupSyncService = mockk(relaxed = true),
            catalogRepository = mockk(relaxed = true),
            watchProgressRepository = watchProgressRepository,
            libraryRepository = mockk(relaxed = true),
            episodeShuffleStore = mockk(relaxed = true),
            episodeShuffle = com.nuvio.tv.domain.model.EpisodeShuffle(),
            metaRepository = mockk(relaxed = true),
            collectionsDataStore = mockk(relaxed = true),
            layoutPreferenceDataStore = mockk(relaxed = true),
            playerSettingsDataStore = mockk(relaxed = true),
            tmdbSettingsDataStore = mockk(relaxed = true),
            mdbListSettingsDataStore = mockk(relaxed = true),
            traktSettingsDataStore = mockk(relaxed = true),
            authSessionNoticeDataStore = mockk(relaxed = true),
            tmdbService = mockk(relaxed = true),
            tmdbMetadataService = mockk(relaxed = true),
            mdbListRepository = mockk(relaxed = true),
            imdbEpisodeRatingsRepository = mockk(relaxed = true),
            trailerService = mockk(relaxed = true),
            watchedSeriesStateHolder = mockk(relaxed = true),
            cwEnrichmentCache = cwEnrichmentCache,
            profileManager = profileManager,
            tvRecommendationManager = mockk(relaxed = true),
            homeRefreshSignal = mockk(relaxed = true),
            prefetchSelectionSupplier = mockk(relaxed = true),
            streamRepository = mockk(relaxed = true),
            trailerSettingsDataStore = mockk(relaxed = true),
            serverCatalog = mockk { every { addons } returns flowOf(emptyList()) }
        )
        viewModel.startupGracePeriodActive = false
        viewModel.externalMetaPrefetchEnabled = true
        viewModel.currentTmdbSettings = TmdbSettings(enabled = false)
        created += viewModel
        return viewModel
    }
}
