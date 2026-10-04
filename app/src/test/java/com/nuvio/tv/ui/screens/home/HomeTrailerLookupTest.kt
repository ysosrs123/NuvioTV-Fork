package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.trailer.TrailerLookupResult
import com.nuvio.tv.data.trailer.TrailerPlaybackFailures
import com.nuvio.tv.data.trailer.TrailerPlaybackSource
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class HomeTrailerLookupTest {
    private val created = mutableListOf<HomeViewModel>()
    @Before fun setup() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun cleanup() {
        created.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun preview(id: String, imdbId: String? = null) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2026",
        imdbRating = null,
        genres = emptyList(),
        imdbId = imdbId
    )

    @Test fun `card lookup passes the item's IMDb id like details does`() = runTest {
        val vm = newViewModel()
        coEvery { vm.tmdbService.ensureTmdbId(any(), any(), any()) } returns "550"
        coEvery { vm.trailerService.lookupTrailer(any(), any(), any(), any(), any()) } returns
            TrailerLookupResult(TrailerPlaybackSource("https://example.test/trailer.mp4"))

        vm.requestTrailerPreviewPipeline(preview("kitsu:1", imdbId = "tt0137523"))
        advanceTimeBy(400); runCurrent()

        coVerify { vm.tmdbService.ensureTmdbId("kitsu:1", "movie", "tt0137523") }
        coVerify { vm.trailerService.lookupTrailer(any(), any(), "550", any(), any()) }
        assertEquals("https://example.test/trailer.mp4", vm.trailerPreviewUrlsState["kitsu:1"])
    }

    @Test fun `card resolves again when its stored link has expired`() = runTest {
        val vm = newViewModel()
        coEvery { vm.tmdbService.ensureTmdbId(any(), any(), any()) } returns "550"
        coEvery { vm.trailerService.lookupTrailer(any(), any(), any(), any(), any()) } returns
            TrailerLookupResult(TrailerPlaybackSource("https://example.test/fresh.mp4"))
        vm.trailerPreviewUrlsState["A"] = "https://rr1.googlevideo.com/videoplayback?expire=1000000000"
        vm.trailerPreviewUrlsState["B"] = "https://rr1.googlevideo.com/videoplayback?expire=9000000000"

        vm.requestTrailerPreviewPipeline("A", "A", "2026", "movie")
        advanceTimeBy(400); runCurrent()
        vm.requestTrailerPreviewPipeline("B", "B", "2026", "movie")
        advanceTimeBy(400); runCurrent()

        assertEquals("https://example.test/fresh.mp4", vm.trailerPreviewUrlsState["A"])
        assertEquals("https://rr1.googlevideo.com/videoplayback?expire=9000000000", vm.trailerPreviewUrlsState["B"])
        coVerify(exactly = 1) { vm.trailerService.lookupTrailer(any(), any(), any(), any(), any()) }
    }

    @Test fun `a link that fails to play is looked up once more, then the card gives up`() = runTest {
        val vm = newViewModel()
        vm.observeTrailerPlaybackFailuresPipeline(); runCurrent()
        val item = preview("fail-A")
        vm.seedCatalog(item)
        coEvery { vm.tmdbService.ensureTmdbId(any(), any(), any()) } returns "550"
        coEvery { vm.trailerService.lookupTrailer(any(), any(), any(), any(), any()) } returnsMany listOf(
            TrailerLookupResult(TrailerPlaybackSource("https://example.test/fail-A-1.mp4")),
            TrailerLookupResult(TrailerPlaybackSource("https://example.test/fail-A-2.mp4"))
        )

        vm.requestTrailerPreviewPipeline(item)
        advanceTimeBy(400); runCurrent()
        assertEquals("https://example.test/fail-A-1.mp4", vm.trailerPreviewUrlsState["fail-A"])

        TrailerPlaybackFailures.report("https://example.test/fail-A-1.mp4")
        runCurrent(); advanceTimeBy(400); runCurrent()
        assertEquals("https://example.test/fail-A-2.mp4", vm.trailerPreviewUrlsState["fail-A"])

        TrailerPlaybackFailures.report("https://example.test/fail-A-2.mp4")
        advanceTimeBy(400); runCurrent()
        assertNull(vm.trailerPreviewUrlsState["fail-A"])
        assertTrue(vm.trailerPreviewNegativeCache.contains("fail-A"))
        coVerify(exactly = 2) { vm.trailerService.lookupTrailer(any(), any(), any(), any(), any()) }
    }

    @Test fun `a timeout is remembered for two minutes only`() = runTest {
        val vm = newViewModel()
        coEvery { vm.tmdbService.ensureTmdbId(any(), any(), any()) } returns "550"
        coEvery { vm.trailerService.lookupTrailer(any(), any(), any(), any(), any()) } returnsMany listOf(
            TrailerLookupResult(null, definiteMiss = false),
            TrailerLookupResult(TrailerPlaybackSource("https://example.test/after-timeout.mp4"))
        )

        vm.requestTrailerPreviewPipeline("T", "T", "2026", "movie")
        advanceTimeBy(400); runCurrent()
        vm.requestTrailerPreviewPipeline("U", "U", "2026", "movie")
        vm.requestTrailerPreviewPipeline("T", "T", "2026", "movie")
        advanceTimeBy(400); runCurrent()
        coVerify(exactly = 1) { vm.trailerService.lookupTrailer("T", any(), any(), any(), any()) }

        vm.trailerPreviewNegativeCache["T"] = vm.trailerPreviewNegativeCache.getValue("T") - 2 * 60_000L
        vm.requestTrailerPreviewPipeline("U", "U", "2026", "movie")
        vm.requestTrailerPreviewPipeline("T", "T", "2026", "movie")
        advanceTimeBy(400); runCurrent()

        assertEquals("https://example.test/after-timeout.mp4", vm.trailerPreviewUrlsState["T"])
    }

    @Test fun `a definite miss is remembered for ten minutes only`() = runTest {
        val vm = newViewModel()
        coEvery { vm.tmdbService.ensureTmdbId(any(), any(), any()) } returns "550"
        coEvery { vm.trailerService.lookupTrailer(any(), any(), any(), any(), any()) } returns
            TrailerLookupResult.DEFINITE_MISS

        vm.requestTrailerPreviewPipeline("M", "M", "2026", "movie")
        advanceTimeBy(400); runCurrent()
        assertTrue(vm.trailerPreviewNegativeCache.contains("M"))
        vm.requestTrailerPreviewPipeline("U", "U", "2026", "movie")
        vm.requestTrailerPreviewPipeline("M", "M", "2026", "movie")
        advanceTimeBy(400); runCurrent()
        coVerify(exactly = 1) { vm.trailerService.lookupTrailer("M", any(), any(), any(), any()) }

        vm.trailerPreviewNegativeCache["M"] = System.currentTimeMillis() - 11 * 60_000L
        vm.requestTrailerPreviewPipeline("U", "U", "2026", "movie")
        vm.requestTrailerPreviewPipeline("M", "M", "2026", "movie")
        advanceTimeBy(400); runCurrent()
        coVerify(exactly = 2) { vm.trailerService.lookupTrailer("M", any(), any(), any(), any()) }
    }

    @Test fun `card skips invalid trailer ids and tries the next one`() = runTest {
        val vm = newViewModel()
        val item = preview("yt-A").copy(trailerYtIds = listOf("vi1234567890", "aaaaaaaaaaa", "bbbbbbbbbbb"))
        vm.seedCatalog(item)
        coEvery { vm.tmdbService.ensureTmdbId(any(), any(), any()) } returns null
        coEvery { vm.trailerService.lookupTrailer(any(), any(), any(), any(), any()) } returns
            TrailerLookupResult.DEFINITE_MISS
        coEvery {
            vm.trailerService.getTrailerPlaybackSourceFromYouTubeUrl("https://www.youtube.com/watch?v=aaaaaaaaaaa", any(), any())
        } returns null
        coEvery {
            vm.trailerService.getTrailerPlaybackSourceFromYouTubeUrl("https://www.youtube.com/watch?v=bbbbbbbbbbb", any(), any())
        } returns TrailerPlaybackSource("https://example.test/second-id.mp4")

        vm.requestTrailerPreviewPipeline(item)
        advanceTimeBy(400); runCurrent()

        assertEquals("https://example.test/second-id.mp4", vm.trailerPreviewUrlsState["yt-A"])
        coVerify(exactly = 0) {
            vm.trailerService.getTrailerPlaybackSourceFromYouTubeUrl("https://www.youtube.com/watch?v=vi1234567890", any(), any())
        }
    }

    @Test fun `a card focused during the startup grace gets its trailer when the grace ends`() = runTest {
        val vm = newViewModel()
        vm.startupGracePeriodActive = true
        coEvery { vm.tmdbService.ensureTmdbId(any(), any(), any()) } returns "550"
        coEvery { vm.trailerService.lookupTrailer(any(), any(), any(), any(), any()) } returns
            TrailerLookupResult(TrailerPlaybackSource("https://example.test/after-grace.mp4"))

        vm.requestTrailerPreviewPipeline("early", "early", "2026", "movie")
        vm.requestTrailerPreviewPipeline("first", "first", "2026", "movie")
        advanceTimeBy(400); runCurrent()
        coVerify(exactly = 0) { vm.trailerService.lookupTrailer(any(), any(), any(), any(), any()) }

        advanceTimeBy(HomeViewModel.STARTUP_GRACE_PERIOD_MS + 400); runCurrent()

        assertEquals("https://example.test/after-grace.mp4", vm.trailerPreviewUrlsState["first"])
        assertNull(vm.trailerPreviewUrlsState["early"])
        assertNull(vm.deferredTrailerPreviewRequest)
    }

    @Test fun `a card focused during the startup grace gets its trailer after Home comes back`() = runTest {
        val vm = newViewModel()
        vm.startupGracePeriodActive = true
        val item = preview("first")
        vm.seedCatalog(item)
        coEvery { vm.tmdbService.ensureTmdbId(any(), any(), any()) } returns "550"
        coEvery { vm.trailerService.lookupTrailer(any(), any(), any(), any(), any()) } returns
            TrailerLookupResult(TrailerPlaybackSource("https://example.test/back-home.mp4"))

        vm.requestTrailerPreviewPipeline(item)
        vm.setTrailerPreviewActive(false)
        advanceTimeBy(HomeViewModel.STARTUP_GRACE_PERIOD_MS + 400); runCurrent()
        coVerify(exactly = 0) { vm.trailerService.lookupTrailer(any(), any(), any(), any(), any()) }

        vm.setTrailerPreviewActive(true)
        advanceTimeBy(400); runCurrent()

        assertEquals("https://example.test/back-home.mp4", vm.trailerPreviewUrlsState["first"])
    }

    @Test fun `moving to a server card cancels the lookup for the card before`() = runTest {
        val vm = newViewModel()
        var cancelled = false
        coEvery { vm.tmdbService.ensureTmdbId(any(), any(), any()) } returns "550"
        coEvery { vm.trailerService.lookupTrailer(any(), any(), any(), any(), any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }

        vm.requestTrailerPreviewPipeline("A", "A", "2026", "movie")
        advanceTimeBy(400); runCurrent()
        vm.requestTrailerPreviewPipeline("srv1:1:abc", "S", "2026", "movie")
        runCurrent()

        assertTrue(cancelled)
        assertNull(vm.trailerPreviewUrlsState["A"])
    }

    private fun HomeViewModel.seedCatalog(item: MetaPreview) {
        synchronized(catalogStateLock) {
            catalogsMap["seeded-row"] = CatalogRow(
                addonId = "test.addon",
                addonName = "Test Addon",
                addonBaseUrl = "https://addon.example",
                catalogId = "test.catalog",
                catalogName = "Test Catalog",
                type = ContentType.MOVIE,
                items = listOf(item)
            )
            catalogItemKeyIndex.getOrPut(item.id) { mutableSetOf() }.add("seeded-row")
        }
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
