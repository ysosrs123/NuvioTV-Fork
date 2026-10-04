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

import java.util.concurrent.atomic.AtomicBoolean
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.ContinueWatchingSortMode

@OptIn(ExperimentalCoroutinesApi::class)
class HomeContinueWatchingOwnershipTest {
    private val created=mutableListOf<HomeViewModel>()
    private val activeProfileFlow=MutableStateFlow(1)
    private val progress=MutableStateFlow<List<WatchProgress>>(emptyList())
    private val subscriptions=mutableListOf<Int>()
    private val stopped=mutableListOf<Int>()
    private val progressSourceFlow=MutableStateFlow(com.nuvio.tv.data.local.WatchProgressSource.TRAKT)
    @Before fun setup() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun cleanup() {
        created.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }
    private suspend fun TestScope.waitFor(condition: () -> Boolean) {
        repeat(200) {
            runCurrent()
            if(condition()) return
            withContext(Dispatchers.Default) { delay(10) }
        }
        assertTrue("Timed out waiting for actual IO child",condition())
    }
    @Test fun `inactive Home does not subscribe to Continue Watching sources`() = runTest {
        val vm=newViewModel()
        vm.setTrailerPreviewActive(false)
        vm.loadContinueWatchingPipeline()
        runCurrent()
        assertTrue(subscriptions.isEmpty())
    }
    @Test fun `leaving Home cancels its actual progress collection and return resubscribes`() = runTest {
        val vm=newViewModel()
        vm.loadContinueWatchingPipeline();runCurrent()
        assertEquals(listOf(1),subscriptions)
        val visible=WatchProgress("done","movie","done",null,null,null,"done",null,null,null,10,100,1)
        vm._uiState.update { it.copy(continueWatchingItems=listOf(ContinueWatchingItem.InProgress(visible))) }
        vm.setTrailerPreviewActive(false);runCurrent()
        assertEquals(listOf(1),stopped)
        progress.value=listOf(WatchProgress("done","movie","done",null,null,null,"done",null,null,null,90,100,1))
        advanceTimeBy(600);runCurrent()
        assertEquals(listOf(1),subscriptions)
        assertEquals(visible,(vm.uiState.value.continueWatchingItems.single() as ContinueWatchingItem.InProgress).progress)
        vm.setTrailerPreviewActive(true);runCurrent()
        assertEquals(listOf(1,1),subscriptions)
        advanceTimeBy(600)
        waitFor { vm._initialCwResolved.value }
        assertTrue(vm.uiState.value.continueWatchingItems.isEmpty())
        assertEquals(90L,progress.value.single().position)
        coVerify(exactly=0) { vm.watchProgressRepository.saveProgressBatch(any(),syncRemote=any()) }
    }
    @Test fun `rapid leave and return still retires the previous collection`() = runTest {
        val vm=newViewModel()
        vm.loadContinueWatchingPipeline();runCurrent()
        vm.setTrailerPreviewActive(false)
        vm.setTrailerPreviewActive(true)
        runCurrent()
        assertEquals(listOf(1),stopped)
        waitFor { subscriptions.size==2 }
        assertEquals(listOf(1,1),subscriptions)
    }
    @Test fun `profile switch during debounce cancels the old source before starting the next`() = runTest {
        val vm=newViewModel()
        vm.loadContinueWatchingPipeline();runCurrent()
        advanceTimeBy(100)
        activeProfileFlow.value=2;runCurrent()
        assertEquals(listOf(1),stopped)
        assertEquals(listOf(1,2),subscriptions)
        coVerify(exactly=0) { vm.cwEnrichmentCache.getNextUpSnapshot() }
    }
    @Test fun `leaving Home cancels already admitted badge work`() = runTest {
        val vm=newViewModel()
        val started=AtomicBoolean(false);val cancelled=AtomicBoolean(false)
        coEvery { vm.watchProgressRepository.getWatchedShowEpisodes() } coAnswers {
            started.set(true)
            try { awaitCancellation() } finally { cancelled.set(true) }
        }
        vm.loadContinueWatchingPipeline();runCurrent();advanceTimeBy(600)
        waitFor { started.get() }
        vm.setTrailerPreviewActive(false);runCurrent()
        waitFor { cancelled.get() }
        assertEquals(listOf(1),stopped)
    }

    @Test fun `profile change rejects a delayed old-profile snapshot restore`() = runTest {
        val vm=newViewModel()
        val admitted=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val newRead=AtomicBoolean(false)
        coEvery { vm.cwEnrichmentCache.getInProgressSnapshot(2) } coAnswers { newRead.set(true);emptyList() }
        val old=com.nuvio.tv.data.local.CachedInProgressItem("old","movie","old",null,null,null,"old",null,null,null,10,100,1,10f)
        coEvery { vm.cwEnrichmentCache.getInProgressSnapshot(1) } coAnswers {
            admitted.complete(Unit)
            withContext(NonCancellable) { finish.await() }
            listOf(old)
        }
        vm.loadContinueWatchingPipeline();runCurrent()
        waitFor { admitted.isCompleted }
        val current=WatchProgress("current","movie","current",null,null,null,"current",null,null,null,10,100,1)
        progress.value=listOf(current)
        activeProfileFlow.value=2;runCurrent()
        vm._uiState.update { it.copy(continueWatchingItems=listOf(ContinueWatchingItem.InProgress(current))) }
        finish.complete(Unit)
        waitFor { subscriptions.contains(2) && newRead.get() }
        assertEquals(current,(vm.uiState.value.continueWatchingItems.single() as ContinueWatchingItem.InProgress).progress)
        coVerify(atLeast=1) { vm.cwEnrichmentCache.getInProgressSnapshot(2) }
    }

    @Test fun `leaving Home cancels already admitted rich metadata lookup`() = runTest {
        val vm=newViewModel()
        vm.currentTmdbSettings=TmdbSettings(enabled=true,enrichContinueWatching=true)
        coEvery { vm.tmdbService.ensureTmdbId(any(),any(),any()) } returns null
        progress.value=listOf(WatchProgress("movie","movie","movie",null,null,null,"movie",null,null,null,10,100,1))
        val started=AtomicBoolean(false);val cancelled=AtomicBoolean(false)
        coEvery { vm.metaRepository.getMetaFromAllAddons(any(),any(),any()) } returns flow {
            started.set(true)
            try { awaitCancellation() } finally { cancelled.set(true) }
        }
        vm.loadContinueWatchingPipeline();runCurrent();advanceTimeBy(600)
        waitFor { vm._initialCwResolved.value }
        assertTrue("Progress row must render before rich lookup",vm.uiState.value.continueWatchingItems.isNotEmpty())
        waitFor { started.get() }
        vm.setTrailerPreviewActive(false);runCurrent()
        waitFor { cancelled.get() }
        assertEquals(listOf(1),stopped)
        assertTrue(vm.uiState.value.continueWatchingItems.isNotEmpty())
    }
    @Test fun `return consumes provider completion at its existing SIMKL threshold`() = runTest {
        val vm=newViewModel()
        coEvery { vm.watchProgressRepository.hasActiveTrackingProgressProvider() } returns true
        vm.setTrailerPreviewActive(false)
        vm.loadContinueWatchingPipeline();runCurrent()
        progress.value=listOf(WatchProgress("done","movie","done",null,null,null,"done",null,null,null,1,100,1,progressPercent=80f,source=WatchProgress.SOURCE_SIMKL_PLAYBACK))
        assertTrue(subscriptions.isEmpty())
        vm.setTrailerPreviewActive(true);runCurrent();advanceTimeBy(600)
        waitFor { vm._initialCwResolved.value }
        assertTrue(vm.uiState.value.continueWatchingItems.isEmpty())
        assertEquals(80f,progress.value.single().progressPercent)
        coVerify(exactly=0) { vm.watchProgressRepository.saveProgressBatch(any(),syncRemote=any()) }
        coVerify(exactly=0) { vm.watchProgressRepository.saveProgressBatch(any(),any(),any()) }
    }
    private suspend fun TestScope.withActualCache(block: suspend (com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache) -> Unit) {
        val dir=java.nio.file.Files.createTempDirectory("cw-source-boundary-").toFile()
        assertEquals(java.io.File(System.getProperty("java.io.tmpdir")).canonicalFile,dir.parentFile.canonicalFile)
        val context=mockk<android.content.Context> { every { filesDir } returns dir }
        val manager=mockk<com.nuvio.tv.core.profile.ProfileManager> { every { activeProfileId } returns activeProfileFlow }
        try { block(com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache(context,manager)) }
        finally { created.forEach { it.viewModelScope.cancel() };dir.deleteRecursively() }
    }
    private fun observeActualSource(vm: HomeViewModel) {
        HomeViewModel::class.java.getDeclaredMethod("observeProgressSourceChanges").apply { isAccessible=true }.invoke(vm)
    }
    @Test fun `actual source transition rejects snapshots produced under the previous source epoch`() = runTest {
        withActualCache { cache ->
            val old=com.nuvio.tv.data.local.CachedInProgressItem("old","movie","old",null,null,null,"old",null,null,null,10,100,1,10f)
            val next=com.nuvio.tv.data.local.CachedNextUpItem("old","series","old",null,null,null,"old:1:2",1,2,null,thumbnail=null,lastWatched=1,sortTimestamp=1)
            cache.saveInProgressSnapshot(listOf(old),force=true,profileId=1)
            cache.saveNextUpSnapshot(listOf(next),force=true,profileId=1)
            val oldClear=cache.cacheCleared.value;val written=cache.snapshotVersion.value
            val vm=newViewModel(cache);vm.setTrailerPreviewActive(false)
            observeActualSource(vm);runCurrent()
            progressSourceFlow.value=com.nuvio.tv.data.local.WatchProgressSource.NUVIO_SYNC;runCurrent()
            waitFor { cache.cacheCleared.value!=oldClear || cache.snapshotVersion.value>=written+2 }
            cache.saveInProgressSnapshot(listOf(old),force=true,profileId=1,expectedClearVersion=oldClear)
            cache.saveNextUpSnapshot(listOf(next),force=true,profileId=1,expectedClearVersion=oldClear)
            assertTrue("Old source must not repopulate the cleared progress snapshot",cache.getInProgressSnapshot(1).isEmpty())
            assertTrue("Old source must not repopulate the cleared next-up snapshot",cache.getNextUpSnapshot(1).isEmpty())
            assertTrue(cache.cacheCleared.value>oldClear)
        }
    }
    @Test fun `profile switch with a different source preserves both profiles snapshots`() = runTest {
        withActualCache { cache ->
            val one=com.nuvio.tv.data.local.CachedInProgressItem("one","movie","one",null,null,null,"one",null,null,null,10,100,1,10f)
            val two=one.copy(contentId="two",name="two",videoId="two")
            cache.saveInProgressSnapshot(listOf(one),force=true,profileId=1)
            cache.saveInProgressSnapshot(listOf(two),force=true,profileId=2)
            val vm=newViewModel(cache);vm.setTrailerPreviewActive(false)
            observeActualSource(vm);runCurrent()
            activeProfileFlow.value=2
            progressSourceFlow.value=com.nuvio.tv.data.local.WatchProgressSource.NUVIO_SYNC;runCurrent()
            assertEquals(listOf(one),cache.getInProgressSnapshot(1))
            assertEquals(listOf(two),cache.getInProgressSnapshot(2))
            assertEquals(0,cache.cacheCleared.value)
        }
    }

    private fun newViewModel(cacheOverride: com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache? = null): HomeViewModel {
        val profileManager = mockk<com.nuvio.tv.core.profile.ProfileManager>(relaxed = true) {
            every { activeProfileReady } returns MutableStateFlow(false)
            every { activeProfileId } returns activeProfileFlow
        }
        val cwEnrichmentCache = cacheOverride ?: 
            mockk<com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache>(relaxed = true) {
                every { cacheCleared } returns MutableStateFlow(0)
            }
        val watchProgressRepository =
            mockk<com.nuvio.tv.domain.repository.WatchProgressRepository>(relaxed = true) {
                every { getAllEpisodeProgress(any()) } returns flowOf(emptyMap())
                every { allProgress } returns flow {
                    val id=activeProfileFlow.value
                    subscriptions += id
                    try { emitAll(progress) } finally { stopped += id }
                }
                every { observeNextUpSeeds() } returns flowOf(emptyList())
                every { observeRemoteProgressLoaded() } returns flowOf(true)
                every { watchedItems } returns flowOf(emptyList())
                every { hasActiveTrackingProgressProvider() } returns false
                every { activeProviderContinueWatchingCutoffEpochMs(any(),any()) } returns null
            }
        val viewModel = HomeViewModel(
            appContext = mockk(relaxed = true),
            addonRepository = mockk(relaxed = true),
            startupSyncService = mockk(relaxed = true),
            catalogRepository = mockk(relaxed = true),
            watchProgressRepository = watchProgressRepository,
            libraryRepository = mockk(relaxed = true),
            episodeShuffleStore = mockk(relaxed = true) {
                every { profiles } returns flowOf(com.nuvio.tv.data.local.EpisodeShuffleProfile(1))
            },
            episodeShuffle = com.nuvio.tv.domain.model.EpisodeShuffle(),
            metaRepository = mockk(relaxed = true) {
                every { getMetaFromAllAddons(any(),any(),any()) } returns flowOf(com.nuvio.tv.core.network.NetworkResult.Error("fixture",404))
            },
            collectionsDataStore = mockk(relaxed = true),
            layoutPreferenceDataStore = mockk(relaxed = true) {
                every { showUnairedNextUp } returns MutableStateFlow(false)
                every { nextUpFromFurthestEpisode } returns MutableStateFlow(false)
                every { continueWatchingSortMode } returns flowOf(ContinueWatchingSortMode.DEFAULT)
            },
            playerSettingsDataStore = mockk(relaxed = true) {
                every { playerSettings } returns flowOf(com.nuvio.tv.data.local.PlayerSettings())
            },
            tmdbSettingsDataStore = mockk(relaxed = true),
            mdbListSettingsDataStore = mockk(relaxed = true),
            traktSettingsDataStore = mockk(relaxed = true) {
                every { watchProgressSource } returns progressSourceFlow
                every { continueWatchingDaysCap } returns flowOf(0)
                every { dismissedNextUpKeys } returns flowOf(emptySet())
            },
            authSessionNoticeDataStore = mockk(relaxed = true),
            tmdbService = mockk(relaxed = true),
            tmdbMetadataService = mockk(relaxed = true),
            mdbListRepository = mockk(relaxed = true),
            imdbEpisodeRatingsRepository = mockk(relaxed = true),
            trailerService = mockk(relaxed = true),
            watchedSeriesStateHolder = com.nuvio.tv.data.local.WatchedSeriesStateHolder(mockk(relaxed=true),profileManager),
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
