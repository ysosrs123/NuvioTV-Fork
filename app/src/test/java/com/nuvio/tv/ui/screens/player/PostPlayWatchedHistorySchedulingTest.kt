package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.*
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.repository.TraktRelatedService
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.*
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.repository.WatchProgressRepositoryImpl
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class PostPlayWatchedHistorySchedulingTest {
    @Before fun setup() { mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerLifecycleKt") }
    @After fun cleanup() { unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerLifecycleKt") }
    private class Fixture(scope: CoroutineScope, val history: History) {
        val player = MutableStateFlow(PlayerUiState(contentType="movie",currentVideoId="current",currentStreamUrl="fixture://one",isBuffering=false,isPlaying=true))
        val timeline = MutableStateFlow(PlaybackTimelineState(currentPosition=7_150_000L,duration=7_200_000L))
        val settings = MutableStateFlow(PlayerSettings(postPlayRecommendationsEnabled=true,postPlayMovieThresholdPercent=90))
        val tmdbSettings = MutableStateFlow(TmdbSettings(enabled=false))
        val trailerSettings = MutableStateFlow(TrailerSettings(enabled=false))
        val ratingSettings = MutableStateFlow(MDBListSettings(enabled=true,apiKey="fixture"))
        val activeProfileFlow = MutableStateFlow(1)
        val selectionChanges = MutableStateFlow(0L)
        val historyChanges = MutableStateFlow(0L)
        val relatedSourceChanges = MutableStateFlow(MoreLikeThisSourcePreference.TRAKT)
        val authenticatedChanges = MutableStateFlow(true)
        val metaCalls = mutableListOf<String>()
        val ratingCalls = mutableListOf<String>()
        val trailerCalls = mutableListOf<String>()
        val cancelledRatings = mutableListOf<String>()
        var relatedCalls = 0
        val profileManager = history.manager
        val runtime = mockk<PlayerRuntimeController>(relaxed=true) {
            every { uiState } returns player
            every { playbackTimeline } returns timeline
            every { contentId } returns "current"
            every { contentType } returns "movie"
            every { profileId } returns 1
            every { lastKnownDuration } returns 7_200_000L
            every { skipIntervals } returns emptyList()
            every { nextEpisodeThresholdModeSetting } returns NextEpisodeThresholdMode.PERCENTAGE
            every { nextEpisodeThresholdPercentSetting } returns 99f
            every { nextEpisodeThresholdMinutesBeforeEndSetting } returns 2f
        }
        init { every { runtime.releasePlayer() } just Runs }
        val playerStore=mockk<PlayerSettingsDataStore>(relaxed=true).also { store -> every { store.playerSettings } returns settings }
        val tmdbStore=mockk<TmdbSettingsDataStore>(relaxed=true).also { store -> every { store.settings } returns tmdbSettings }
        val ratingStore=mockk<MDBListSettingsDataStore>(relaxed=true).also { store -> every { store.settings } returns ratingSettings }
        val trailerStore=mockk<TrailerSettingsDataStore>(relaxed=true).also { store -> every { store.settings } returns trailerSettings }
        val meta = mockk<MetaRepository>(relaxed=true) {
            coEvery { getCachedMeta(any(),any()) } coAnswers {
                val id=secondArg<String>();metaCalls+=id;metadata(id)
            }
        }
        val ratings = mockk<MDBListRepository>(relaxed=true) {
            coEvery { getRatingsForMeta(any(),any(),any()) } coAnswers { ratingCalls+=secondArg<String>();null }
            every { isAvailable(any()) } answers { firstArg<MDBListSettings>().let { it.enabled && it.apiKey.isNotBlank() } }
        }
        val trailers = mockk<TrailerService>(relaxed=true) {
            coEvery { getTrailerPlaybackSource(any(),any(),any(),any(),any()) } coAnswers { trailerCalls+=firstArg<String>();null }
        }
        val related = mockk<TraktRelatedService>(relaxed=true) {
            coEvery { getRelated(any(),any(),any(),any()) } coAnswers { relatedCalls++;listOf("a","b","c","d").map(::preview) }
        }
        val tmdb=mockk<com.nuvio.tv.core.tmdb.TmdbService>(relaxed=true) { coEvery { ensureTmdbId(any(),any(),any()) } returns null }
        val tmdbMetadata=mockk<com.nuvio.tv.core.tmdb.TmdbMetadataService>(relaxed=true)
        val trailerPool=mockk<com.nuvio.tv.core.player.TrailerPlayerPool>(relaxed=true)
        val controller = PostPlayRecommendationController(
            playbackController=runtime,
            profileManager=profileManager,
            playerSettingsDataStore=playerStore,
            metaRepository=meta,
            tmdbService=tmdb,
            tmdbMetadataService=tmdbMetadata,
            tmdbSettingsDataStore=tmdbStore,
            mdbListRepository=ratings,
            mdbListSettingsDataStore=ratingStore,
            traktRelatedService=related,
            traktAuthDataStore=mockk(relaxed=true) { every { isAuthenticated } returns authenticatedChanges },
            traktSettingsDataStore=mockk(relaxed=true) { every { moreLikeThisSource } returns relatedSourceChanges },
            simklRelatedService=mockk(relaxed=true),
            simklAuthRepository=mockk(relaxed=true),
            layoutPreferenceDataStore=mockk(relaxed=true) {
                every { hideUnreleasedContent } returns flowOf(false)
                every { homeImdbRatingsVisibility } returns flowOf(HomeImdbRatingsVisibility.SHOW_ALL)
            },
            watchProgressRepository=history.repository,
            watchedSeriesStateHolder=history.series,
            trailerService=trailers,
            trailerSettingsDataStore=trailerStore,
            trailerPlayerPool=trailerPool,
            scope=scope
        )
        fun suspendRatings() {
            coEvery { ratings.getRatingsForMeta(any(),any(),any()) } coAnswers {
                val id=secondArg<String>();ratingCalls+=id
                try { awaitCancellation() } finally { cancelledRatings+=id }
            }
        }
        companion object {
            fun metadata(id:String)=Meta(id=id,type=ContentType.MOVIE,name=id,poster=null,posterShape=PosterShape.POSTER,background="fixture://backdrop",logo=null,description=null,releaseInfo=null,imdbRating=null,genres=emptyList(),runtime=null,director=emptyList(),cast=emptyList(),videos=emptyList(),country=null,awards=null,language=null,links=emptyList())
            fun preview(id:String)=MetaPreview(id=id,type=ContentType.MOVIE,name=id,poster=null,posterShape=PosterShape.POSTER,background="fixture://backdrop",logo=null,description=null,releaseInfo=null,imdbRating=null,genres=emptyList())
        }
    }


    /** Isolated actual preferences/owner/lifetime/repository; no user history or provider I/O. */
    private class Store : DataStore<Preferences> {
        private val state=MutableStateFlow(emptyPreferences())
        private val mutex=kotlinx.coroutines.sync.Mutex()
        var updates=0
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences)->Preferences): Preferences=mutex.withLock {
            transform(state.value).also { state.value=it;updates++ }
        }
    }
    private class History {
        val active=MutableStateFlow(1);val selections=MutableStateFlow(0L);val generations=MutableStateFlow(0L)
        val manager=mockk<ProfileManager>(relaxed=true) {
            every { activeProfileId } returns active
            every { activeProfileReady } returns MutableStateFlow(false)
            every { profileSelectionRevision } returns selections
            every { profileHistoryGenerationChanges } returns generations
        }
        val stores=ConcurrentHashMap<Pair<Int,String>,ProfileStoreLifetime>()
        val factory=mockk<ProfileDataStoreFactory>(relaxed=true) {
            every { historyGenerationChanges } returns generations
            every { get(any(),any()) } answers {
                stores.computeIfAbsent(firstArg<Int>() to secondArg<String>()) { ProfileStoreLifetime(Store(),generations) }
            }
            every { isProfileDeleted(any()) } returns false
        }
        val watched=WatchedItemsPreferences(factory,manager)
        val progress=WatchProgressPreferences(factory,manager)
        val series=WatchedSeriesStateHolder(factory,manager)
        val sync=mockk<WatchProgressSyncService>(relaxed=true)
        val watchedSync=mockk<WatchedItemsSyncService>(relaxed=true)
        val mutations=mockk<WatchStateMutationStore>(relaxed=true)
        val source=MutableStateFlow(WatchProgressSource.NUVIO_SYNC)
        val repository=WatchProgressRepositoryImpl(progress,
            mockk<TraktSettingsDataStore>(relaxed=true) { every { watchProgressSource } returns source },
            mockk<LayoutPreferenceDataStore>(relaxed=true),sync,watched,watchedSync,
            mockk<AuthManager>(relaxed=true) { every { isAuthenticated } returns false },
            mockk<MetaRepository>(relaxed=true),mockk<com.nuvio.tv.core.tmdb.TmdbService>(relaxed=true),manager,
            TrackingProgressProviderRegistry(emptySet()),TrackingHistoryWriterRegistry(emptySet()),mutations,mockk(relaxed=true))
        val gates=mutableListOf<CompletableDeferred<Unit>>()
        val scopes=mutableListOf<CoroutineScope>()
        fun gate()=CompletableDeferred<Unit>().also { gates+=it }
        fun fixture(parent: CoroutineScope): Fixture {
            val scope=CoroutineScope(parent.coroutineContext+SupervisorJob(parent.coroutineContext[Job]))
            scopes+=scope
            return Fixture(scope,this)
        }
        suspend fun mark(id: String, title: String=id)=watched.markAsWatched(WatchedItem(id,"movie",title,watchedAt=100),1)
        suspend fun close() {
            gates.forEach { it.complete(Unit) }
            scopes.forEach { it.cancel() }
            for ((instance,fieldName) in listOf(repository to "syncScope",series to "scope")) {
                val field=instance.javaClass.getDeclaredField(fieldName).apply { isAccessible=true }
                (field.get(instance) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
            }
            stores.values.toList().forEach { it.retire() }
        }
    }
    private suspend fun TestScope.history(block: suspend TestScope.(History)->Unit) {
        val h=History()
        try { block(h) } finally { h.close() }
    }
    // Owner/store observation uses the real Default/IO dispatchers. Pump only the
    // controller/test scheduler and wait for controlled facts, never user input.
    private fun TestScope.awaitFact(message: String, fact: ()->Boolean) {
        val deadline=System.nanoTime()+4_000_000_000L
        while(!fact() && System.nanoTime()<deadline) {
            runCurrent();Thread.sleep(5);advanceTimeBy(600);runCurrent()
        }
        assertTrue(message,fact())
    }
    private fun TestScope.pumpObservedWrites() { repeat(8) { runCurrent();Thread.sleep(5);advanceTimeBy(600);runCurrent() } }

    @Test fun `actual history projection excludes a watched movie before first selection`() = runTest { history { h ->
        h.mark("a");val f=h.fixture(backgroundScope)
        awaitFact("watched a must be excluded") { f.controller.uiState.value.recommendation?.id=="b" }
        assertEquals(3,f.controller.uiState.value.recommendationCount)
        assertEquals(1,f.relatedCalls)
        assertEquals(listOf("b"),f.ratingCalls)
        coVerify(exactly=0) { h.sync.pushToRemote(any()) }
        coVerify(exactly=0) { h.watchedSync.pushToRemote(any()) }
    } }
    @Test fun `clearing actual watched history restores previously filtered first candidate`() = runTest { history { h ->
        h.mark("a");val f=h.fixture(backgroundScope)
        awaitFact("initial b") { f.controller.uiState.value.recommendation?.id=="b" }
        h.watched.clearAll(1)
        awaitFact("clear must restore a from current eligibility") { f.controller.uiState.value.recommendation?.id=="a" }
        assertEquals(4,f.controller.uiState.value.recommendationCount)
        assertTrue(h.watched.getAllItems(1).isEmpty())
    } }
    @Test fun `new watched eligibility cancels selected ratings and excludes the candidate`() = runTest { history { h ->
        val f=h.fixture(backgroundScope);f.suspendRatings()
        awaitFact("initial a rating") { f.ratingCalls==listOf("a") }
        h.mark("a")
        awaitFact("watched selected a must retire and choose b") { f.controller.uiState.value.recommendation?.id=="b" }
        assertEquals(listOf("a"),f.cancelledRatings)
        assertEquals(listOf("a","b"),f.ratingCalls)
        assertEquals(3,f.controller.uiState.value.recommendationCount)
        assertEquals(listOf("a"),h.watched.getAllItems(1).map { it.contentId })
    } }
    @Test fun `watching an offscreen candidate refreshes filtering without resolving it`() = runTest { history { h ->
        val f=h.fixture(backgroundScope)
        awaitFact("initial a") { f.controller.uiState.value.recommendation?.id=="a" }
        h.mark("b")
        awaitFact("offscreen b must leave candidate list") { f.controller.uiState.value.recommendationCount==3 }
        assertEquals("a",f.controller.uiState.value.recommendation?.id)
        verify(exactly=0) { f.meta.getCachedMeta("movie","b") }
        assertFalse(f.ratingCalls.contains("b"));assertFalse(f.trailerCalls.contains("b"))
    } }
    @Test fun `ordinary unwatch restores a filtered candidate on the same playback`() = runTest { history { h ->
        h.mark("a");val f=h.fixture(backgroundScope)
        awaitFact("initial b") { f.controller.uiState.value.recommendation?.id=="b" }
        h.watched.unmarkAsWatched("a",profileId=1)
        awaitFact("unwatch must refresh eligibility") { f.controller.uiState.value.recommendation?.id=="a" }
    } }
    @Test fun `actual watched sync metadata writes retain an admitted detail lookup`() = runTest { history { h ->
        val f=h.fixture(backgroundScope);f.suspendRatings()
        awaitFact("initial a rating") { f.ratingCalls==listOf("a") }
        h.watched.advanceLastSuccessfulPushMs(100,1);h.watched.setDeltaState(10,true,1)
        pumpObservedWrites()
        assertEquals(1,f.relatedCalls);assertEquals(listOf("a"),f.ratingCalls);assertTrue(f.cancelledRatings.isEmpty())
        assertEquals(100L,h.watched.getLastSuccessfulPushMs(1));assertEquals(10L,h.watched.getDeltaCursor(1))
    } }
    @Test fun `watched display-only replacement retains the same eligibility owner`() = runTest { history { h ->
        h.mark("d");val f=h.fixture(backgroundScope);f.suspendRatings()
        awaitFact("initial a rating") { f.ratingCalls==listOf("a") }
        h.mark("d","changed fixture title");pumpObservedWrites()
        assertEquals(1,f.relatedCalls);assertEquals(listOf("a"),f.ratingCalls);assertTrue(f.cancelledRatings.isEmpty())
        assertEquals("changed fixture title",h.watched.getAllItems(1).single().title)
    } }
    @Test fun `actual fully watched series projection retires a selected series`() = runTest { history { h ->
        val f=h.fixture(backgroundScope)
        f.player.value=f.player.value.copy(contentType="series",isNextEpisodeMetadataResolved=true)
        coEvery { f.related.getRelated(any(),any(),any(),any()) } coAnswers {
            f.relatedCalls++;listOf("a","b","c").map { Fixture.preview(it).copy(type=ContentType.SERIES) }
        }
        coEvery { f.meta.getCachedMeta("series",any()) } coAnswers {
            val id=secondArg<String>();f.metaCalls+=id;Fixture.metadata(id).copy(type=ContentType.SERIES)
        }
        f.suspendRatings();awaitFact("initial series a") { f.controller.uiState.value.recommendation?.id=="a" }
        h.series.update(setOf("a"))
        awaitFact("fully watched series a must retire") { f.controller.uiState.value.recommendation?.id=="b" }
        assertEquals(2,f.controller.uiState.value.recommendationCount)
        assertTrue(f.cancelledRatings.contains("a"))
    } }
    @Test fun `late noncooperative ratings cannot publish for a newly watched candidate`() = runTest { history { h ->
        val f=h.fixture(backgroundScope);f.suspendRatings();val old=h.gate()
        coEvery { f.ratings.getRatingsForMeta(any(),"a",any()) } coAnswers {
            f.ratingCalls+="a";withContext(NonCancellable) { old.await() }
            MDBListRatingsResult(MDBListRatings(imdb=1.0),true)
        }
        awaitFact("old a admitted") { f.ratingCalls==listOf("a") }
        h.mark("a")
        awaitFact("fresh b must not wait for old a") { f.controller.uiState.value.recommendation?.id=="b" }
        old.complete(Unit);runCurrent();pumpObservedWrites()
        assertEquals("b",f.controller.uiState.value.recommendation?.id)
        assertNull(f.controller.uiState.value.recommendation?.mdbListRatings)
        f.controller.showNextRecommendation();awaitFact("navigate to c") { f.controller.uiState.value.recommendation?.id=="c" }
        assertTrue(f.cancelledRatings.contains("b"))
    } }
    @Test fun `cleared eligibility preserves returned-to-player intent on the same playback`() = runTest { history { h ->
        h.mark("a");val f=h.fixture(backgroundScope);f.suspendRatings()
        awaitFact("initial b") { f.controller.uiState.value.recommendation?.id=="b" }
        f.controller.returnToPlayer();advanceTimeBy(POST_PLAY_RECOMMENDATION_TRANSITION_MS.toLong());runCurrent()
        h.watched.clearAll(1);pumpObservedWrites()
        assertTrue(f.controller.uiState.value.hasReturnedToPlayer);assertFalse(f.controller.uiState.value.isVisible)
        assertEquals(1,f.relatedCalls);assertEquals(listOf("b"),f.ratingCalls)
    } }
    @Test fun `metadata-only history writes preserve the normal five-second countdown`() = runTest { history { h ->
        val f=h.fixture(backgroundScope)
        f.trailerSettings.value=f.trailerSettings.value.copy(enabled=true)
        coEvery { f.trailers.getTrailerPlaybackSource(any(),any(),any(),any(),any()) } returns com.nuvio.tv.data.trailer.TrailerPlaybackSource("fixture://trailer")
        awaitFact("initial trailer ready") { f.controller.uiState.value.recommendation?.hasTrailer==true }
        f.player.value=f.player.value.copy(playbackEnded=true);runCurrent()
        assertEquals(5,f.controller.uiState.value.countdownSeconds)
        val end=testScheduler.currentTime+5000
        advanceTimeBy(2000);runCurrent();h.watched.advanceLastSuccessfulPushMs(100,1)
        repeat(4) { Thread.sleep(5);runCurrent() }
        assertEquals(1,f.relatedCalls)
        advanceTimeBy(end-testScheduler.currentTime-1);runCurrent();coVerify(exactly=0) { f.runtime.releasePlayer() }
        advanceTimeBy(1);runCurrent();coVerify(exactly=1) { f.runtime.releasePlayer() }
    } }
    @Test fun `clearing all excluded candidates admits a fresh recommendation attempt`() = runTest { history { h ->
        for(id in listOf("a","b","c","d")) h.mark(id)
        val f=h.fixture(backgroundScope)
        awaitFact("initial filtering finished") { f.relatedCalls==1 && !f.controller.uiState.value.isLoadingRecommendation }
        assertNull(f.controller.uiState.value.recommendation)
        h.watched.clearAll(1)
        awaitFact("clear must reopen empty candidate result") { f.controller.uiState.value.recommendation?.id=="a" }
    } }
    @Test fun `actual replay progress restores eligibility without altering watched membership`() = runTest { history { h ->
        h.mark("a");val f=h.fixture(backgroundScope)
        awaitFact("initial b") { f.controller.uiState.value.recommendation?.id=="b" }
        h.progress.saveProgress(WatchProgress("a","movie","a",null,null,null,"a",null,null,null,1000,10000,200),1)
        awaitFact("actual replay overrides watched exclusion") { f.controller.uiState.value.recommendation?.id=="a" }
        assertEquals(listOf("a"),h.watched.getAllItems(1).map { it.contentId })
        assertEquals(1000L,h.progress.getAllRawEntries(1).getValue("a").position)
    } }
    @Test fun `actual optimistic watched projection excludes a currently selected candidate`() = runTest { history { h ->
        val f=h.fixture(backgroundScope);f.suspendRatings()
        awaitFact("initial a") { f.controller.uiState.value.recommendation?.id=="a" }
        h.repository.applyOptimisticWatchedMovie(setOf("a"),true)
        awaitFact("optimistic eligibility update must select b") { f.controller.uiState.value.recommendation?.id=="b" }
        assertTrue(h.watched.getAllItems(1).isEmpty())
        coVerify(exactly=0) { h.watchedSync.pushToRemote(any()) }
    } }
    @Test fun `stop remains stopped after an ordinary timeline emission`() = runTest { history { h ->
        val f=h.fixture(backgroundScope);f.suspendRatings()
        awaitFact("initial a") { f.controller.uiState.value.recommendation?.id=="a" }
        f.controller.stop();f.timeline.value=f.timeline.value.copy(currentPosition=7_160_000);pumpObservedWrites()
        assertEquals("stop must not be reversed by its old playback observer",1,f.relatedCalls)
        assertNull(f.controller.uiState.value.recommendation)
    } }
    @Test fun `stopped recommendations do not revive after observed watched eligibility changes`() = runTest { history { h ->
        val f=h.fixture(backgroundScope);f.suspendRatings()
        awaitFact("initial a") { f.controller.uiState.value.recommendation?.id=="a" }
        f.controller.stop();h.mark("a");pumpObservedWrites()
        assertEquals(1,f.relatedCalls);assertNull(f.controller.uiState.value.recommendation)
    } }
    @Test fun `watched clear retires cooperative discovery before fresh candidates`() = runTest { history { h ->
        h.mark("a");val f=h.fixture(backgroundScope);var retired=false
        awaitFact("initial filtered b establishes observed history") { f.controller.uiState.value.recommendation?.id=="b" }
        coEvery { f.related.getRelated(any(),any(),any(),any()) } coAnswers {
            f.relatedCalls++
            if(f.relatedCalls==2) {
                try { awaitCancellation() } finally { retired=true }
            } else listOf("a","b","c","d").map(Fixture::preview)
        }
        f.player.value=f.player.value.copy(currentStreamUrl="fixture://refreshed")
        awaitFact("refresh discovery entered") { f.relatedCalls==2 }
        h.watched.clearAll(1)
        val limit=testScheduler.currentTime+5000
        while(!retired && testScheduler.currentTime<limit) { runCurrent();Thread.sleep(5);advanceTimeBy(500);runCurrent() }
        assertTrue("observed clear must retire discovery before its ordinary ten-second timeout",retired)
        awaitFact("fresh discovery admits current first candidate") { f.controller.uiState.value.recommendation?.id=="a" }
        assertEquals(3,f.relatedCalls);assertEquals(listOf("b","a"),f.ratingCalls)
    } }
    @Test fun `new eligibility discovery does not wait for or publish a noncooperative old reply`() = runTest { history { h ->
        h.mark("a");val f=h.fixture(backgroundScope);val old=h.gate()
        awaitFact("initial filtered b establishes observed history") { f.controller.uiState.value.recommendation?.id=="b" }
        coEvery { f.related.getRelated(any(),any(),any(),any()) } coAnswers {
            f.relatedCalls++
            if(f.relatedCalls==2) {
                withContext(NonCancellable) { old.await() }
                listOf(Fixture.preview("stale"))
            } else listOf("a","b","c","d").map(Fixture::preview)
        }
        f.player.value=f.player.value.copy(currentStreamUrl="fixture://refreshed")
        awaitFact("old refresh discovery entered") { f.relatedCalls==2 }
        h.watched.clearAll(1)
        awaitFact("fresh discovery must not join old reply") { f.controller.uiState.value.recommendation?.id=="a" }
        old.complete(Unit);runCurrent();pumpObservedWrites()
        assertEquals("a",f.controller.uiState.value.recommendation?.id)
        verify(exactly=0) { f.meta.getCachedMeta("movie","stale") }
        assertFalse(f.ratingCalls.contains("stale"));assertFalse(f.trailerCalls.contains("stale"))
    } }

    @Test fun `active end countdown tolerates ordinary timeline emissions`() = runTest { history { h ->
        val f=h.fixture(backgroundScope)
        f.trailerSettings.value=f.trailerSettings.value.copy(enabled=true)
        coEvery { f.trailers.getTrailerPlaybackSource(any(),any(),any(),any(),any()) } returns com.nuvio.tv.data.trailer.TrailerPlaybackSource("fixture://trailer")
        awaitFact("initial trailer ready") { f.controller.uiState.value.recommendation?.hasTrailer==true }
        f.player.value=f.player.value.copy(playbackEnded=true);runCurrent()
        assertEquals(5,f.controller.uiState.value.countdownSeconds)
        advanceTimeBy(2000);runCurrent()
        f.timeline.value=f.timeline.value.copy(currentPosition=7_160_000);runCurrent()
        coVerify(exactly=0) { f.runtime.releasePlayer() }
        advanceTimeBy(3000);runCurrent();coVerify(exactly=1) { f.runtime.releasePlayer() }
    } }
    @Test fun `discovery reentrant history clear preserves fresh first visible filtering`() = runTest { history { h ->
        h.mark("a");val f=h.fixture(backgroundScope);val seen=mutableListOf<String>()
        val reader=backgroundScope.launch { f.controller.uiState.mapNotNull { it.recommendation?.id }.distinctUntilChanged().collect { seen+=it } }
        coEvery { f.related.getRelated(any(),any(),any(),any()) } coAnswers {
            f.relatedCalls++;if(f.relatedCalls==1) h.watched.clearAll(1)
            listOf("a","b","c","d").map(Fixture::preview)
        }
        try {
            awaitFact("current clear must be used before first presentation") { f.controller.uiState.value.recommendation?.id=="a" }
            assertFalse("old cached exclusion must never show b first",seen.contains("b"))
            assertTrue(h.watched.getAllItems(1).isEmpty())
        } finally { reader.cancel() }
    } }
    @Test fun `disabled recommendations do not open the actual watched history projection`() = runTest { history { h ->
        val f=h.fixture(backgroundScope);f.settings.value=f.settings.value.copy(postPlayRecommendationsEnabled=false)
        pumpObservedWrites()
        assertEquals(0,f.relatedCalls);assertTrue(f.ratingCalls.isEmpty());assertTrue(f.trailerCalls.isEmpty())
        assertTrue("disabled recommendations must not subscribe and migrate unused history",h.stores.isEmpty())
    } }

    @Test fun `watching the current movie preserves its admitted end countdown`() = runTest { history { h ->
        val f=h.fixture(backgroundScope)
        f.trailerSettings.value=f.trailerSettings.value.copy(enabled=true)
        coEvery { f.trailers.getTrailerPlaybackSource(any(),any(),any(),any(),any()) } returns com.nuvio.tv.data.trailer.TrailerPlaybackSource("fixture://trailer")
        awaitFact("initial trailer ready") { f.controller.uiState.value.recommendation?.hasTrailer==true }
        f.player.value=f.player.value.copy(playbackEnded=true);runCurrent()
        assertEquals(5,f.controller.uiState.value.countdownSeconds)
        advanceTimeBy(1000);runCurrent();h.mark("current");pumpObservedWrites()
        assertEquals("current movie is already excluded",1,f.relatedCalls)
        coVerify(exactly=1) { f.runtime.releasePlayer() }
    } }
    @Test fun `watching the canonical current movie preserves its admitted end countdown`() = runTest { history { h ->
        val f=h.fixture(backgroundScope)
        coEvery { f.meta.getCachedMeta("movie","current") } returns Fixture.metadata("canonical")
        f.trailerSettings.value=f.trailerSettings.value.copy(enabled=true)
        coEvery { f.trailers.getTrailerPlaybackSource(any(),any(),any(),any(),any()) } returns com.nuvio.tv.data.trailer.TrailerPlaybackSource("fixture://trailer")
        awaitFact("initial trailer ready") { f.controller.uiState.value.recommendation?.hasTrailer==true }
        f.player.value=f.player.value.copy(playbackEnded=true);runCurrent()
        assertEquals(5,f.controller.uiState.value.countdownSeconds)
        advanceTimeBy(1000);runCurrent();h.mark("canonical");pumpObservedWrites()
        assertEquals("current metadata alias is already excluded",1,f.relatedCalls)
        coVerify(exactly=1) { f.runtime.releasePlayer() }
    } }
    @Test fun `watching the current movie preserves an already playing trailer`() = runTest { history { h ->
        val f=h.fixture(backgroundScope)
        f.trailerSettings.value=f.trailerSettings.value.copy(enabled=true)
        coEvery { f.trailers.getTrailerPlaybackSource(any(),any(),any(),any(),any()) } returns com.nuvio.tv.data.trailer.TrailerPlaybackSource("fixture://trailer")
        awaitFact("initial trailer ready") { f.controller.uiState.value.recommendation?.hasTrailer==true }
        f.controller.playTrailer();runCurrent();assertTrue(f.controller.uiState.value.isTrailerPlaying)
        h.mark("current");pumpObservedWrites()
        assertEquals(1,f.relatedCalls);assertTrue(f.controller.uiState.value.isTrailerPlaying)
        verify(exactly=0) { f.trailerPool.stop() }
        coVerify(exactly=1) { f.runtime.releasePlayer() }
    } }
    @Test fun `unrelated watched membership preserves the admitted end countdown`() = runTest { history { h ->
        val f=h.fixture(backgroundScope)
        f.trailerSettings.value=f.trailerSettings.value.copy(enabled=true)
        coEvery { f.trailers.getTrailerPlaybackSource(any(),any(),any(),any(),any()) } returns com.nuvio.tv.data.trailer.TrailerPlaybackSource("fixture://trailer")
        awaitFact("initial trailer ready") { f.controller.uiState.value.recommendation?.hasTrailer==true }
        f.player.value=f.player.value.copy(playbackEnded=true);runCurrent()
        assertEquals(5,f.controller.uiState.value.countdownSeconds)
        advanceTimeBy(1000);runCurrent();h.mark("outside");pumpObservedWrites()
        assertEquals("undiscovered title cannot change these candidates",1,f.relatedCalls)
        coVerify(exactly=1) { f.runtime.releasePlayer() }
    } }
    @Test fun `an additional watched alias preserves an already excluded candidate`() = runTest { history { h ->
        h.mark("a");val f=h.fixture(backgroundScope);f.suspendRatings()
        coEvery { f.related.getRelated(any(),any(),any(),any()) } coAnswers {
            f.relatedCalls++;listOf(Fixture.preview("a").copy(imdbId="alias-a"),Fixture.preview("b"),Fixture.preview("c"))
        }
        awaitFact("initial excluded a selects b") { f.controller.uiState.value.recommendation?.id=="b" }
        h.mark("alias-a");pumpObservedWrites()
        assertEquals("a stays excluded through either watched alias",1,f.relatedCalls)
        assertEquals(listOf("b"),f.ratingCalls);assertTrue(f.cancelledRatings.isEmpty())
    } }
    @Test fun `current watched ID still excludes a discovered candidate carrying that alias`() = runTest { history { h ->
        val f=h.fixture(backgroundScope);f.suspendRatings()
        coEvery { f.related.getRelated(any(),any(),any(),any()) } coAnswers {
            f.relatedCalls++;listOf(Fixture.preview("a").copy(imdbId="current"),Fixture.preview("b"))
        }
        awaitFact("initial a") { f.controller.uiState.value.recommendation?.id=="a" }
        h.mark("current")
        awaitFact("existing candidate watched predicate must still exclude a") { f.controller.uiState.value.recommendation?.id=="b" }
        assertEquals(1,f.controller.uiState.value.recommendationCount)
        assertTrue(f.cancelledRatings.contains("a"))
    } }

}
