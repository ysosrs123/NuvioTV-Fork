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
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class PostPlayRecommendationSchedulingTest {
    @Before fun setup() { mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerLifecycleKt") }
    @After fun cleanup() { unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerLifecycleKt") }
    private class Fixture(scope: CoroutineScope, suppliedProfileManager: ProfileManager? = null) {
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
        val profileManager = suppliedProfileManager ?: mockk<ProfileManager>(relaxed=true) {
            every { activeProfileId } returns activeProfileFlow
            every { activeProfileReady } returns MutableStateFlow(false)
            every { profileSelectionRevision } returns selectionChanges
            every { profileHistoryGenerationChanges } returns historyChanges
        }
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
            watchProgressRepository=mockk(relaxed=true) { every { observeWatchedMovieIds() } returns flowOf(emptySet()) },
            watchedSeriesStateHolder=WatchedSeriesStateHolder(mockk(relaxed=true),profileManager),
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

    @Test fun `actual controller resolves only the first candidate before navigation`() = runTest {
        val f=Fixture(backgroundScope);runCurrent()
        assertEquals("a",f.controller.uiState.value.recommendation?.id)
        assertEquals(4,f.controller.uiState.value.recommendationCount)
        assertTrue(f.controller.uiState.value.isVisible)
        assertEquals(listOf("current","a"),f.metaCalls)
    }
    @Test fun `actual controller requests details only for the selected candidate`() = runTest {
        val f=Fixture(backgroundScope);runCurrent()
        assertEquals(listOf("a"),f.ratingCalls)
        assertEquals(listOf("a"),f.trailerCalls)
        coVerify(exactly=0) { f.runtime.releasePlayer() }
    }
    @Test fun `accepted next navigation cancels details for the previous candidate`() = runTest {
        val f=Fixture(backgroundScope);f.suspendRatings();runCurrent()
        f.controller.showNextRecommendation();runCurrent()
        assertEquals("b",f.controller.uiState.value.recommendation?.id)
        assertEquals(listOf("a"),f.cancelledRatings)
        assertEquals(listOf("a","b"),f.ratingCalls)
    }
    @Test fun `same movie stream replacement retires and reloads the recommendation pipeline`() = runTest {
        val f=Fixture(backgroundScope);runCurrent()
        assertEquals(1,f.relatedCalls)
        f.player.value=f.player.value.copy(currentStreamUrl="fixture://two");runCurrent()
        assertEquals(2,f.relatedCalls)
        assertEquals("a",f.controller.uiState.value.recommendation?.id)
    }
    @Test fun `recommendation opt out cancels admitted details and clears the overlay`() = runTest {
        val f=Fixture(backgroundScope);f.suspendRatings();runCurrent()
        f.settings.value=f.settings.value.copy(postPlayRecommendationsEnabled=false);runCurrent()
        assertNull(f.controller.uiState.value.recommendation)
        assertFalse(f.controller.uiState.value.isVisible)
        assertTrue(f.cancelledRatings.contains("a"))
    }
    @Test fun `return to player cancels admitted details and preserves return intent`() = runTest {
        val f=Fixture(backgroundScope);f.suspendRatings();runCurrent()
        f.controller.returnToPlayer();runCurrent()
        assertTrue(f.controller.uiState.value.hasReturnedToPlayer)
        assertFalse(f.controller.uiState.value.isVisible)
        assertTrue(f.cancelledRatings.contains("a"))
        coVerify(exactly=0) { f.runtime.releasePlayer() }
    }
    @Test fun `stop cancels admitted details and clears recommendation state`() = runTest {
        val f=Fixture(backgroundScope);f.suspendRatings();runCurrent()
        f.controller.stop();runCurrent()
        assertEquals(PostPlayRecommendationUiState(),f.controller.uiState.value)
        assertTrue(f.cancelledRatings.contains("a"))
    }
    @Test fun `revisiting cancelled details admits a fresh selected lookup`() = runTest {
        val f=Fixture(backgroundScope);f.suspendRatings();runCurrent()
        f.controller.showNextRecommendation();runCurrent()
        f.controller.showPreviousRecommendation();runCurrent()
        assertEquals("a",f.controller.uiState.value.recommendation?.id)
        assertEquals(listOf("a","b","a"),f.ratingCalls)
        assertEquals(listOf("a","b"),f.cancelledRatings)
    }
    @Test fun `rapid navigation preserves the existing changing guard`() = runTest {
        val f=Fixture(backgroundScope);runCurrent()
        f.controller.showNextRecommendation()
        f.controller.showNextRecommendation()
        runCurrent()
        assertEquals(1,f.controller.uiState.value.recommendationIndex)
        assertEquals(listOf("current","a","b"),f.metaCalls)
    }
    @Test fun `completed lightweight details are reused on return navigation`() = runTest {
        val f=Fixture(backgroundScope);runCurrent()
        f.controller.showNextRecommendation();runCurrent()
        f.controller.showPreviousRecommendation();runCurrent()
        assertEquals("a",f.controller.uiState.value.recommendation?.id)
        assertEquals(listOf("a","b"),f.ratingCalls)
        assertEquals(listOf("current","a","b"),f.metaCalls)
    }
    @Test fun `active profile mismatch cancels work until playback owner returns`() = runTest {
        val f=Fixture(backgroundScope);f.suspendRatings();runCurrent()
        f.activeProfileFlow.value=2;runCurrent()
        assertNull(f.controller.uiState.value.recommendation)
        assertEquals(listOf("a"),f.cancelledRatings)
        assertEquals(1,f.relatedCalls)
        f.activeProfileFlow.value=1;runCurrent()
        assertEquals(2,f.relatedCalls)
        assertEquals("a",f.controller.uiState.value.recommendation?.id)
    }
    @Test fun `same numeric profile history retirement cancels and refreshes the pipeline`() = runTest {
        val f=Fixture(backgroundScope);f.suspendRatings();runCurrent()
        f.historyChanges.value++;runCurrent()
        assertEquals(2,f.relatedCalls)
        assertEquals(listOf("a"),f.cancelledRatings)
        assertEquals(listOf("a","a"),f.ratingCalls)
    }
    @Test fun `profile selection admission retires work even when profile ID is unchanged`() = runTest {
        val f=Fixture(backgroundScope);f.suspendRatings();runCurrent()
        f.selectionChanges.value++;f.selectionChanges.value++;runCurrent()
        assertEquals(2,f.relatedCalls)
        assertEquals(listOf("a"),f.cancelledRatings)
        assertEquals(1,f.activeProfileFlow.value)
    }
    @Test fun `metadata preferences cancel and reload their owned pipeline`() = runTest {
        val f=Fixture(backgroundScope);f.suspendRatings();runCurrent()
        f.tmdbSettings.value=f.tmdbSettings.value.copy(language="fr");runCurrent()
        assertEquals(2,f.relatedCalls)
        assertEquals(listOf("a"),f.cancelledRatings)
        assertEquals(listOf("a","a"),f.ratingCalls)
    }
    @Test fun `rating opt out cancels old lookup and does not admit a replacement rating RPC`() = runTest {
        val f=Fixture(backgroundScope);f.suspendRatings();runCurrent()
        f.ratingSettings.value=f.ratingSettings.value.copy(enabled=false);runCurrent()
        assertEquals(listOf("a"),f.cancelledRatings)
        assertEquals(listOf("a"),f.ratingCalls)
        assertEquals("a",f.controller.uiState.value.recommendation?.id)
    }
    @Test fun `return intent survives preference and history refresh on the same playback`() = runTest {
        val f=Fixture(backgroundScope);runCurrent()
        f.controller.returnToPlayer();runCurrent()
        f.tmdbSettings.value=f.tmdbSettings.value.copy(language="fr")
        f.historyChanges.value++;runCurrent()
        assertTrue(f.controller.uiState.value.hasReturnedToPlayer)
        assertFalse(f.controller.uiState.value.isVisible)
        assertEquals(1,f.relatedCalls)
        coVerify(exactly=0) { f.runtime.releasePlayer() }
    }
    @Test fun `disabled recommendation setting never admits discovery or details`() = runTest {
        val f=Fixture(backgroundScope)
        f.settings.value=f.settings.value.copy(postPlayRecommendationsEnabled=false);runCurrent()
        assertEquals(0,f.relatedCalls)
        assertTrue(f.metaCalls.isEmpty())
        assertTrue(f.ratingCalls.isEmpty())
        assertTrue(f.trailerCalls.isEmpty())
    }

    @Test fun `late old ratings cannot replace fresh state or erase its active detail job`() = runTest {
        val f=Fixture(backgroundScope)
        val finishOld=CompletableDeferred<Unit>()
        var admitted=0
        var newerCancelled=false
        coEvery { f.ratings.getRatingsForMeta(any(),"a",any()) } coAnswers {
            admitted++
            if(admitted==1) {
                withContext(NonCancellable) { finishOld.await() }
                MDBListRatingsResult(MDBListRatings(imdb=1.0),true)
            } else {
                try { awaitCancellation() } finally { newerCancelled=true }
            }
        }
        try {
            runCurrent();assertEquals(1,admitted)
            f.player.value=f.player.value.copy(currentStreamUrl="fixture://new");runCurrent()
            assertEquals(2,admitted)
            finishOld.complete(Unit);runCurrent()
            assertNull(f.controller.uiState.value.recommendation?.mdbListRatings)
            f.controller.showNextRecommendation();runCurrent()
            assertEquals("b",f.controller.uiState.value.recommendation?.id)
            assertTrue(newerCancelled)
        } finally { finishOld.complete(Unit) }
    }
    @Test fun `manual trailer cannot start an obsolete candidate during navigation`() = runTest {
        val f=Fixture(backgroundScope)
        coEvery { f.trailers.getTrailerPlaybackSource(any(),any(),any(),any(),any()) } returns com.nuvio.tv.data.trailer.TrailerPlaybackSource("fixture://trailer")
        every { f.meta.getCachedMeta("movie","b") } returns null
        coEvery { f.meta.getMetaFromAllAddons("movie","b",any()) } returns flow { awaitCancellation() }
        runCurrent();assertTrue(f.controller.uiState.value.recommendation?.hasTrailer==true)
        f.controller.showNextRecommendation();runCurrent()
        assertTrue(f.controller.uiState.value.isChangingRecommendation)
        f.controller.playTrailer();runCurrent()
        coVerify(exactly=0) { f.runtime.releasePlayer() }
        assertFalse(f.controller.uiState.value.isTrailerPlaying)
    }
    @Test fun `manual current trailer retains its existing playback transition`() = runTest {
        val f=Fixture(backgroundScope)
        coEvery { f.trailers.getTrailerPlaybackSource(any(),any(),any(),any(),any()) } returns com.nuvio.tv.data.trailer.TrailerPlaybackSource("fixture://trailer")
        runCurrent();f.controller.playTrailer();runCurrent()
        coVerify(exactly=1) { f.runtime.releasePlayer() }
        verify(exactly=1) { f.trailerPool.reclaim() }
        assertTrue(f.controller.uiState.value.isTrailerPlaying)
    }
    @Test fun `post-end countdown preserves its five-second transition`() = runTest {
        val f=Fixture(backgroundScope)
        f.player.value=f.player.value.copy(playbackEnded=true)
        f.trailerSettings.value=f.trailerSettings.value.copy(enabled=true)
        coEvery { f.trailers.getTrailerPlaybackSource(any(),any(),any(),any(),any()) } returns com.nuvio.tv.data.trailer.TrailerPlaybackSource("fixture://trailer")
        runCurrent();assertEquals(5,f.controller.uiState.value.countdownSeconds)
        advanceTimeBy(4_000);runCurrent()
        coVerify(exactly=0) { f.runtime.releasePlayer() }
        advanceTimeBy(1_000);runCurrent()
        coVerify(exactly=1) { f.runtime.releasePlayer() }
        assertTrue(f.controller.uiState.value.hasAutoPlayedTrailer)
    }
    @Test fun `source replacement retires the old countdown before admitting the new one`() = runTest {
        val f=Fixture(backgroundScope)
        f.player.value=f.player.value.copy(playbackEnded=true)
        f.trailerSettings.value=f.trailerSettings.value.copy(enabled=true)
        coEvery { f.trailers.getTrailerPlaybackSource(any(),any(),any(),any(),any()) } returns com.nuvio.tv.data.trailer.TrailerPlaybackSource("fixture://trailer")
        runCurrent();advanceTimeBy(2_000);runCurrent()
        f.player.value=f.player.value.copy(currentStreamUrl="fixture://new");runCurrent()
        assertEquals(5,f.controller.uiState.value.countdownSeconds)
        advanceTimeBy(3_000);runCurrent()
        coVerify(exactly=0) { f.runtime.releasePlayer() }
        advanceTimeBy(2_000);runCurrent()
        coVerify(exactly=1) { f.runtime.releasePlayer() }
    }
    @Test fun `stopped metadata resolution admits no subsequent ID or enrichment lookup`() = runTest {
        val f=Fixture(backgroundScope);runCurrent()
        every { f.meta.getCachedMeta("movie","b") } answers {
            f.controller.stop()
            Fixture.metadata("b")
        }
        f.controller.showNextRecommendation();runCurrent()
        assertNull(f.controller.uiState.value.recommendation)
        coVerify(exactly=0) { f.tmdb.ensureTmdbId("b",any(),any()) }
    }
    @Test fun `real profile manager admission and factory generation retire actual controller work`() = runTest {
        val generation=MutableStateFlow(0L)
        val factory=mockk<ProfileDataStoreFactory>(relaxed=true) { every { historyGenerationChanges } returns generation }
        val data=mockk<ProfileDataStore>(relaxed=true)
        val finish=CompletableDeferred<Unit>()
        coEvery { data.setActiveProfile(1) } coAnswers { finish.await() }
        every { data.profilesList } returns MutableStateFlow(listOf(
            com.nuvio.tv.domain.model.UserProfile(1,"fixture","#1E88E5")
        ))
        val manager=ProfileManager(data,factory,emptySet(),mockk<android.content.Context>(relaxed=true))
        try {
            val f=Fixture(backgroundScope,manager);f.suspendRatings();runCurrent()
            val selected=backgroundScope.launch { manager.setActiveProfile(1) }
            runCurrent()
            assertFalse(selected.isCompleted)
            assertEquals(2,f.relatedCalls)
            assertEquals(listOf("a"),f.cancelledRatings)
            finish.complete(Unit);runCurrent()
            generation.value++;runCurrent()
            assertEquals(3,f.relatedCalls)
            assertEquals(listOf("a","a"),f.cancelledRatings)
        } finally {
            finish.complete(Unit)
            val field=ProfileManager::class.java.getDeclaredField("scope").apply { isAccessible=true }
            (field.get(manager) as CoroutineScope).cancel()
        }
    }

    @Test fun `recommendation provider switch retires Trakt details and selects TMDB without offscreen resolution`() = runTest {
        val f=Fixture(backgroundScope);f.suspendRatings();runCurrent()
        coEvery { f.tmdb.ensureTmdbId("current","movie",any()) } returns "42"
        coEvery { f.tmdbMetadata.fetchMoreLikeThis(any(),any(),any(),any()) } returns listOf(Fixture.preview("other"),Fixture.preview("offscreen"))
        f.tmdbSettings.value=f.tmdbSettings.value.copy(enabled=true)
        f.relatedSourceChanges.value=MoreLikeThisSourcePreference.TMDB
        runCurrent()
        assertEquals("other",f.controller.uiState.value.recommendation?.id)
        assertEquals(2,f.controller.uiState.value.recommendationCount)
        assertEquals(1,f.relatedCalls)
        assertEquals(listOf("a"),f.cancelledRatings)
        assertEquals(listOf("a","other"),f.ratingCalls)
        assertEquals(listOf("current","a","current","other"),f.metaCalls)
        coVerify(exactly=1) { f.tmdbMetadata.fetchMoreLikeThis("42",ContentType.MOVIE,any(),any()) }
    }

    @Test fun `stop reentered from current metadata does not admit recommendation discovery`() = runTest {
        val f=Fixture(backgroundScope)
        every { f.meta.getCachedMeta("movie","current") } answers { f.controller.stop();Fixture.metadata("current") }
        runCurrent()
        assertEquals(0,f.relatedCalls)
        assertNull(f.controller.uiState.value.recommendation)
    }
    @Test fun `retired current TMDB ID lookup does not admit related lookup`() = runTest {
        val f=Fixture(backgroundScope)
        f.tmdbSettings.value=f.tmdbSettings.value.copy(enabled=true)
        f.relatedSourceChanges.value=MoreLikeThisSourcePreference.TMDB
        coEvery { f.tmdb.ensureTmdbId("current","movie",any()) } coAnswers { f.controller.stop();"42" }
        runCurrent()
        coVerify(exactly=0) { f.tmdbMetadata.fetchMoreLikeThis(any(),any(),any(),any()) }
    }
    @Test fun `retired canonical ID lookup does not admit fallback ID lookup`() = runTest {
        val f=Fixture(backgroundScope)
        f.tmdbSettings.value=f.tmdbSettings.value.copy(enabled=true)
        f.relatedSourceChanges.value=MoreLikeThisSourcePreference.TMDB
        every { f.meta.getCachedMeta("movie","current") } returns Fixture.metadata("canonical")
        coEvery { f.tmdb.ensureTmdbId("canonical","movie",any()) } coAnswers { f.controller.stop();null }
        runCurrent()
        coVerify(exactly=0) { f.tmdb.ensureTmdbId("current","movie",any()) }
        coVerify(exactly=0) { f.tmdbMetadata.fetchMoreLikeThis(any(),any(),any(),any()) }
    }
    @Test fun `ordinary canonical ID miss retains fallback discovery and selected resolution`() = runTest {
        val f=Fixture(backgroundScope)
        f.tmdbSettings.value=f.tmdbSettings.value.copy(enabled=true)
        f.relatedSourceChanges.value=MoreLikeThisSourcePreference.TMDB
        every { f.meta.getCachedMeta("movie","current") } returns Fixture.metadata("canonical")
        coEvery { f.tmdb.ensureTmdbId("canonical","movie",any()) } returns null
        coEvery { f.tmdb.ensureTmdbId("current","movie",any()) } returns "42"
        coEvery { f.tmdbMetadata.fetchMoreLikeThis("42",any(),any(),any()) } returns listOf(Fixture.preview("a"),Fixture.preview("b"))
        runCurrent()
        assertEquals("a",f.controller.uiState.value.recommendation?.id)
        assertEquals(2,f.controller.uiState.value.recommendationCount)
        coVerify(exactly=1) { f.tmdb.ensureTmdbId("canonical","movie",any()) }
        coVerify(exactly=1) { f.tmdb.ensureTmdbId("current","movie",any()) }
        coVerify(exactly=1) { f.tmdbMetadata.fetchMoreLikeThis("42",ContentType.MOVIE,any(),any()) }
        verify(exactly=0) { f.meta.getCachedMeta("movie","b") }
    }
    @Test fun `cancelled discovery does not publish or admit selected details`() = runTest {
        val f=Fixture(backgroundScope)
        coEvery { f.related.getRelated(any(),any(),any(),any()) } throws CancellationException("fixture cancelled")
        runCurrent()
        assertNull(f.controller.uiState.value.recommendation)
        assertTrue(f.ratingCalls.isEmpty())
        assertTrue(f.trailerCalls.isEmpty())
        verify(exactly=0) { f.meta.getCachedMeta("movie","a") }
    }

    @Test fun `retired selected canonical ID lookup does not admit candidate fallback lookup`() = runTest {
        val f=Fixture(backgroundScope)
        every { f.meta.getCachedMeta("movie","a") } returns Fixture.metadata("candidate-canonical")
        coEvery { f.tmdb.ensureTmdbId("candidate-canonical","movie",any()) } coAnswers { f.controller.stop();null }
        runCurrent()
        coVerify(exactly=0) { f.tmdb.ensureTmdbId("a","movie",any()) }
        assertNull(f.controller.uiState.value.recommendation)
    }

}
