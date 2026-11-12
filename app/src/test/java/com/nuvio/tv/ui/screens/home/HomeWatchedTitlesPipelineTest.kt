package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.WatchedTitlesMode
import com.nuvio.tv.domain.model.legacyKey
import com.nuvio.tv.domain.model.stableKey
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeWatchedTitlesPipelineTest {
    private val created = mutableListOf<HomeViewModel>()

    @Before fun setup() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun cleanup() {
        created.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `hide takes watched titles out of the rows and the banner but not out of See all or the tick map`() = runTest {
        val vm = newViewModel()
        val row = row("movies", hasMore = false, items = (1..12).map { movie("m$it") })
        seed(vm, row)
        vm.watchedTitles.filter.value = HomeWatchedFilter(WatchedTitlesMode.HIDE, HomeWatchedIds(movieIds = setOf("m1")))

        vm.updateCatalogRowsPipeline()
        advanceUntilIdle()

        val state = vm._uiState.value
        assertEquals((2..12).map { "m$it" }, state.catalogRows.single().items.map { it.id })
        assertTrue(state.heroItems.none { it.id == "m1" })
        assertTrue(vm.fullCatalogRows.value.single().items.any { it.id == "m1" })
        assertTrue("movie|m1" in vm.lastMovieWatchedItemKeys)
    }

    @Test
    fun `show and dim leave every row as it is`() = runTest {
        for (mode in listOf(WatchedTitlesMode.SHOW, WatchedTitlesMode.DIM)) {
            val vm = newViewModel()
            val row = row("movies", hasMore = false, items = (1..3).map { movie("m$it") })
            seed(vm, row)
            vm.watchedTitles.filter.value = HomeWatchedFilter(mode, HomeWatchedIds(movieIds = setOf("m1")))

            vm.updateCatalogRowsPipeline()

            assertEquals(listOf("m1", "m2", "m3"), vm._uiState.value.catalogRows.single().items.map { it.id })
        }
    }

    @Test
    fun `a row where everything is watched disappears from Home`() = runTest {
        val vm = newViewModel()
        val watched = row("watched", hasMore = false, items = listOf(movie("w1"), movie("w2")))
        val fresh = row("fresh", hasMore = false, items = listOf(movie("f1")))
        seed(vm, watched, fresh)
        vm.watchedTitles.filter.value = HomeWatchedFilter(
            WatchedTitlesMode.HIDE,
            HomeWatchedIds(movieIds = setOf("w1", "w2"))
        )

        vm.updateCatalogRowsPipeline()

        val catalogRows = vm._uiState.value.homeRows.filterIsInstance<HomeRow.Catalog>()
        assertEquals(listOf("fresh"), catalogRows.map { it.row.catalogId })
    }

    @Test
    fun `a title marked watched stays in the focused row until focus moves on`() = runTest {
        val vm = newViewModel()
        val row = row("movies", hasMore = false, items = (1..3).map { movie("m$it") })
        seed(vm, row)
        vm.watchedTitles.filter.value = HomeWatchedFilter(WatchedTitlesMode.HIDE, HomeWatchedIds())
        vm.watchedTitles.homeShown = true
        vm.liveFocusedRowKey = row.stableKey()
        vm.updateCatalogRowsPipeline()

        vm.watchedTitles.filter.value = HomeWatchedFilter(WatchedTitlesMode.HIDE, HomeWatchedIds(movieIds = setOf("m2")))
        vm.updateCatalogRowsPipeline()
        assertEquals(listOf("m1", "m2", "m3"), vm._uiState.value.catalogRows.single().items.map { it.id })
        assertEquals(row.stableKey(), vm.watchedTitles.heldRowKey)

        vm.liveFocusedRowKey = "somewhere_else"
        vm.updateCatalogRowsPipeline()
        assertEquals(listOf("m1", "m3"), vm._uiState.value.catalogRows.single().items.map { it.id })
        assertNull(vm.watchedTitles.heldRowKey)
    }

    @Test
    fun `leaving Home lets the focused row drop its watched titles`() = runTest {
        val vm = newViewModel()
        val row = row("movies", hasMore = false, items = (1..3).map { movie("m$it") })
        seed(vm, row)
        vm.watchedTitles.filter.value = HomeWatchedFilter(WatchedTitlesMode.HIDE, HomeWatchedIds())
        vm.watchedTitles.homeShown = true
        vm.liveFocusedRowKey = row.stableKey()
        vm.updateCatalogRowsPipeline()
        vm.watchedTitles.filter.value = HomeWatchedFilter(WatchedTitlesMode.HIDE, HomeWatchedIds(movieIds = setOf("m2")))
        vm.updateCatalogRowsPipeline()

        vm.watchedTitles.homeShown = false
        vm.updateCatalogRowsPipeline()

        assertEquals(listOf("m1", "m3"), vm._uiState.value.catalogRows.single().items.map { it.id })
    }

    @Test
    fun `a row left short asks for another page, at most two`() = runTest {
        val vm = newViewModel()
        val row = row("movies", hasMore = true, items = (1..12).map { movie("m$it") })
        val key = row.legacyKey()
        seed(vm, row)
        vm.watchedTitles.filter.value = HomeWatchedFilter(
            WatchedTitlesMode.HIDE,
            HomeWatchedIds(movieIds = (1..4).map { "m$it" }.toSet())
        )

        repeat(WATCHED_REFILL_MAX_PAGES) {
            vm.updateCatalogRowsPipeline()
            assertTrue(key in vm.loadingCatalogs.value)
            finishPageLoad(vm, key)
        }
        vm.updateCatalogRowsPipeline()

        assertFalse(key in vm.loadingCatalogs.value)
        assertEquals(WATCHED_REFILL_MAX_PAGES, vm.watchedTitles.refillPages[key])
    }

    @Test
    fun `a refresh that brings back the same first page does not fetch the extra pages again`() = runTest {
        val row = row("movies", hasMore = true, items = (1..12).map { movie("m$it") })
        val vm = refreshingViewModel(row, refreshed = row)

        vm.loadCatalogPipeline(addon(), descriptor("movies"), vm.catalogLoadGeneration, isRefresh = true).join()

        assertEquals(WATCHED_REFILL_MAX_PAGES, vm.watchedTitles.refillPages[row.legacyKey()])
    }

    @Test
    fun `a row that starts again from its first page may fill up again`() = runTest {
        val row = row("movies", hasMore = true, items = (1..12).map { movie("m$it") })
        val restructured = row.copy(items = (20..24).map { movie("m$it") })
        val vm = refreshingViewModel(row, refreshed = restructured)

        vm.loadCatalogPipeline(addon(), descriptor("movies"), vm.catalogLoadGeneration, isRefresh = true).join()

        assertEquals(restructured.items, vm.catalogsMap.getValue(row.legacyKey()).items)
        assertNull(vm.watchedTitles.refillPages[row.legacyKey()])
    }

    @Test
    fun `mark as watched works for an anime-type title and its tick is observed`() = runTest {
        val vm = newViewModel()
        val item = movie("kitsu:1").copy(type = ContentType.UNKNOWN, rawType = "anime")

        vm.togglePosterSeriesWatched(item)
        assertTrue("anime|kitsu:1" in vm._uiState.value.movieWatchedPending)
        assertEquals(true, vm._uiState.value.movieWatchedStatus["anime|kitsu:1"])

        vm.reconcilePosterStatusObserversPipeline(listOf(row("anime", hasMore = false, items = listOf(item))))
        assertNotNull(vm.seriesWatchedObserverJob)
    }

    private fun refreshingViewModel(current: CatalogRow, refreshed: CatalogRow): HomeViewModel {
        val vm = spyk(newViewModel())
        every { vm.scheduleUpdateCatalogRows() } just Runs
        seed(vm, current)
        vm.watchedTitles.refillPages[current.legacyKey()] = WATCHED_REFILL_MAX_PAGES
        vm.pendingCatalogLoads = 1
        every {
            vm.catalogRepository.getCatalog(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns flowOf(NetworkResult.Success(refreshed))
        return vm
    }

    private fun addon() = Addon(
        id = "addon", name = "Addon", version = "1", description = null, logo = null,
        baseUrl = "https://example.test", catalogs = emptyList(), types = emptyList(), resources = emptyList()
    )

    private fun descriptor(catalogId: String) = CatalogDescriptor(
        type = ContentType.MOVIE, id = catalogId, name = catalogId
    )

    private fun finishPageLoad(vm: HomeViewModel, key: String) {
        vm._loadingCatalogs.update { it - key }
        synchronized(vm.catalogStateLock) {
            vm.catalogsMap[key] = vm.catalogsMap.getValue(key).copy(isLoading = false)
        }
    }

    private fun seed(vm: HomeViewModel, vararg rows: CatalogRow) {
        synchronized(vm.catalogStateLock) {
            rows.forEach { row ->
                vm.catalogsMap[row.legacyKey()] = row
                vm.catalogOrder += row.legacyKey()
            }
        }
    }

    private fun row(catalogId: String, hasMore: Boolean, items: List<MetaPreview>) = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://example.test",
        catalogId = catalogId,
        catalogName = catalogId,
        type = ContentType.MOVIE,
        items = items,
        hasMore = hasMore
    )

    private fun movie(id: String) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = "https://example.test/$id.jpg",
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
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
                every { observeWatchedMovieIds() } returns flowOf(emptySet())
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
            serverCatalog = mockk { every { addons } returns flowOf(emptyList()); every { artworkUpdates } returns MutableStateFlow(0L) }
        )
        viewModel.startupGracePeriodActive = false
        viewModel.currentTmdbSettings = TmdbSettings(enabled = false)
        created += viewModel
        return viewModel
    }
}
