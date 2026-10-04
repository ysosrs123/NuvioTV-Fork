package com.nuvio.tv.ui.screens.home

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.nuvio.tv.data.repository.MetaRepositoryImpl
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The retry depends on a contract between two classes: MetaRepositoryImpl reports a transport
 * failure as a codeless NetworkResult.Error, and the focus pipeline treats exactly that as
 * retryable. The other tests mock one side or the other, so this one runs the real repository
 * against a mocked AddonApi to keep the two from drifting apart.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeEnrichmentRepositoryBoundaryTest {

    private val itemId = "tt0944947"
    private val otherId = "tt0903747"
    private val catalogSourceUrl = "https://catalog.example"

    private val created = mutableListOf<HomeViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        created.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `a real repository transport failure is retried and then resolves`() = runBlocking {
        val metaCalls = AtomicInteger()
        val reachable = AtomicBoolean(false)
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers {
            metaCalls.incrementAndGet()
            if (!reachable.get()) throw IOException("offline")
            Response.success(
                MetaResponseDto(meta = MetaDto(id = itemId, type = "series", name = "Test Meta"))
            )
        }
        val viewModel = newViewModel(realRepository(api))
        viewModel.seedCatalog(item(itemId))

        viewModel.onItemFocusPipeline(item(itemId))
        awaitAtLeast(metaCalls, 1)

        reachable.set(true)
        focusAndSettle(viewModel, item(otherId))
        focusAndSettle(viewModel, item(itemId))

        // The failed lookup came back as a codeless Error, so the item was never marked prefetched
        // and the focus gate let it through again.
        awaitAtLeast(metaCalls, 2)
        // The API counter advances before the response has reached the enrichment
        // collector. Await that observable outcome instead of racing its dispatcher.
        withTimeout(5_000) {
            while (itemId !in viewModel.prefetchedExternalMetaIds) delay(25)
        }
        assertEquals(
            "the resolved item should now be cached",
            true,
            itemId in viewModel.prefetchedExternalMetaIds
        )
    }


    @Test
    fun `rapid focus on cached titles only warms full metadata for the settled title`() = runBlocking {
        val transientId = "tt9000011"
        val settledId = "tt9000012"
        val requested = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers {
            requested.add(firstArg<String>())
            Response.success(MetaResponseDto(meta = MetaDto(id = settledId, type = "series", name = "Settled")))
        }
        val viewModel = newViewModel(realRepository(api))
        viewModel.externalMetaPrefetchEnabled = false
        viewModel.prefetchedTmdbIds.addAll(listOf(transientId, settledId))
        viewModel.onItemFocusPipeline(item(transientId))
        viewModel.onItemFocusPipeline(item(settledId))
        withTimeout(5_000) { while (requested.isEmpty()) delay(25) }
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS + 100)
        assertEquals(1, requested.size)
        assertEquals(true, requested.single().contains(settledId))
    }

    @Test
    fun `details prefetch prefers catalogue source and shares that result with Details`() = runBlocking {
        val requested = mutableListOf<String>()
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers {
            requested += firstArg<String>()
            Response.success(MetaResponseDto(meta = MetaDto(id = itemId, type = "series", name = "Source")))
        }
        val repository = realRepository(api)
        val vm = newViewModel(repository)
        vm.externalMetaPrefetchEnabled = false
        vm.prefetchDetailsMeta(item(itemId))
        repository.getMeta(catalogSourceUrl, "series", itemId).collect { }
        assertEquals(1, requested.size)
        assertEquals(true, requested.single().startsWith(catalogSourceUrl))
    }

    @Test
    fun `failed full metadata prefetch is not marked complete and can retry`() = runBlocking {
        val reachable = AtomicBoolean(false)
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers {
            if (!reachable.get()) throw IOException("offline")
            Response.success(MetaResponseDto(meta = MetaDto(id = itemId, type = "series", name = "Recovered")))
        }
        val vm = newViewModel(realRepository(api))
        vm.prefetchDetailsMeta(item(itemId))
        assertEquals(false, itemId in vm.backgroundMetaPrefetchedIds)
        reachable.set(true)
        vm.prefetchDetailsMeta(item(itemId))
        assertEquals(true, itemId in vm.backgroundMetaPrefetchedIds)
    }

    private suspend fun focusAndSettle(viewModel: HomeViewModel, item: MetaPreview) {
        viewModel.onItemFocusPipeline(item)
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS + 400)
    }

    private suspend fun awaitAtLeast(counter: AtomicInteger, expected: Int) {
        val reached = runCatching {
            withTimeout(5_000) { while (counter.get() < expected) delay(25) }
        }.isSuccess
        assertEquals("expected at least $expected meta requests", true, reached)
    }

    private fun realRepository(api: AddonApi): MetaRepositoryImpl {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Episode"
            every { getString(any(), *anyVararg()) } returns "No supported addon"
        }
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns flowOf(listOf(metaAddon()))
        }
        return MetaRepositoryImpl(context = context, api = api, addonRepository = addonRepository, healthStore = mockk(relaxed = true), serverCatalog = mockk(relaxed = true))
    }

    /** Deliberately not the catalog source, or the lookup short-circuits as source-sufficient. */
    private fun metaAddon() = Addon(
        id = "meta.addon",
        name = "Meta Addon",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://meta.example",
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        rawTypes = listOf("movie", "series"),
        resources = listOf(
            AddonResource(name = "meta", types = listOf("movie", "series"), idPrefixes = null)
        ),
        idPrefixes = listOf("tt")
    )

    private fun HomeViewModel.seedCatalog(item: MetaPreview) {
        synchronized(catalogStateLock) {
            val rowKey = "seeded-row"
            catalogsMap[rowKey] = com.nuvio.tv.domain.model.CatalogRow(
                addonId = "catalog.addon",
                addonName = "Catalog Addon",
                addonBaseUrl = catalogSourceUrl,
                catalogId = "test.catalog",
                catalogName = "Test Catalog",
                type = ContentType.SERIES,
                items = listOf(item)
            )
            catalogItemKeyIndex.getOrPut(item.id) { mutableSetOf() }.add(rowKey)
        }
    }

    private fun item(id: String) = MetaPreview(
        id = id,
        type = ContentType.SERIES,
        name = "Test Item",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        sourceAddonBaseUrl = catalogSourceUrl
    )

    private fun newViewModel(metaRepository: MetaRepositoryImpl): HomeViewModel {
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
            episodeShuffleStore = mockk(relaxed = true),
            episodeShuffle = com.nuvio.tv.domain.model.EpisodeShuffle(),
            appContext = mockk(relaxed = true),
            addonRepository = mockk(relaxed = true),
            startupSyncService = mockk(relaxed = true),
            catalogRepository = mockk(relaxed = true),
            watchProgressRepository = watchProgressRepository,
            libraryRepository = mockk(relaxed = true),
            metaRepository = metaRepository,
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
            serverCatalog = mockk(relaxed = true)
        )
        viewModel.startupGracePeriodActive = false
        viewModel.externalMetaPrefetchEnabled = true
        viewModel.currentTmdbSettings = TmdbSettings(enabled = false)
        created += viewModel
        return viewModel
    }
}
