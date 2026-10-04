package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.data.trailer.TrailerPlaybackSource
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class HomeCatalogIncrementalTest {
    private val created = mutableListOf<HomeViewModel>()
    @Before fun setup() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun cleanup() {
        created.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }
    @Test fun `a completed row is published while another addon remains slow`() = runTest {
        checkCompletion(com.nuvio.tv.core.network.NetworkResult.Success(row()))
    }

    @Test fun `a failed row is removed while another addon remains slow`() = runTest {
        checkCompletion(com.nuvio.tv.core.network.NetworkResult.Error("offline"))
    }

    private suspend fun kotlinx.coroutines.test.TestScope.checkCompletion(
        result: com.nuvio.tv.core.network.NetworkResult<com.nuvio.tv.domain.model.CatalogRow>
    ) {
        val vm = spyk(newViewModel())
        // Observe the UI scheduler boundary, while running the real load collector:
        // two pending requests, first render already complete, one response now arrives.
        every { vm.scheduleUpdateCatalogRows() } just Runs
        vm.hasRenderedFirstCatalog = true
        vm.pendingCatalogLoads = 2
        every { vm.catalogRepository.getCatalog(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns flowOf(result)
        val addon = com.nuvio.tv.domain.model.Addon(
            id = "fast", name = "Fast", version = "1", description = null, logo = null,
            baseUrl = "https://example.test", catalogs = emptyList(), types = emptyList(), resources = emptyList()
        )
        val catalog = com.nuvio.tv.domain.model.CatalogDescriptor(
            type = com.nuvio.tv.domain.model.ContentType.MOVIE, id = "catalog", name = "Fast row"
        )
        vm.loadCatalogPipeline(addon, catalog, vm.catalogLoadGeneration).join()
        assertEquals(1, vm.pendingCatalogLoads)
        verify(exactly = 1) { vm.scheduleUpdateCatalogRows() }
        vm.viewModelScope.cancel()
    }

    private fun row() = com.nuvio.tv.domain.model.CatalogRow(
        addonId = "fast", addonName = "Fast", addonBaseUrl = "https://example.test",
        catalogId = "catalog", catalogName = "Fast row", type = com.nuvio.tv.domain.model.ContentType.MOVIE,
        items = emptyList()
    )

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
            metaRepository = mockk(relaxed = true),
            episodeShuffleStore = mockk(relaxed = true),
            episodeShuffle = com.nuvio.tv.domain.model.EpisodeShuffle(),
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
