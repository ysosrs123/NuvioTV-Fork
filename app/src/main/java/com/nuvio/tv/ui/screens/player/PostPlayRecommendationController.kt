package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.TrailerPlayerPool
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.isUnreleased
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.MoreLikeThisSourcePreference
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.repository.TraktRelatedService
import com.nuvio.tv.data.simkl.SimklAuthRepository
import com.nuvio.tv.data.simkl.SimklRelatedService
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility
import com.nuvio.tv.data.local.TrailerSettings
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalCoroutinesApi::class)
internal class PostPlayRecommendationController(
    private val playbackController: PlayerRuntimeController,
    private val profileManager: ProfileManager,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val metaRepository: MetaRepository,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val mdbListRepository: MDBListRepository,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val traktRelatedService: TraktRelatedService,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val simklRelatedService: SimklRelatedService,
    private val simklAuthRepository: SimklAuthRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder,
    private val trailerService: TrailerService,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val trailerPlayerPool: TrailerPlayerPool,
    private val scope: CoroutineScope
) {
    private data class RecommendationPreferences(
        val tmdb: TmdbSettings,
        val mdbList: MDBListSettings,
        val trailers: TrailerSettings,
        val relatedSource: MoreLikeThisSourcePreference,
        val traktAuthenticated: Boolean,
        val hideUnreleased: Boolean = false,
        val ratingVisibility: HomeImdbRatingsVisibility = HomeImdbRatingsVisibility.SHOW_ALL
    )

    private data class OwnerInputs(
        val preferences: RecommendationPreferences,
        val profileSelectionRevision: Long,
        val historyGeneration: Long,
        val watchedEligibilityRevision: Long
    )

    private data class PlaybackIdentity(
        val contentType: String?,
        val contentId: String?,
        val videoId: String?,
        val season: Int?,
        val episode: Int?,
        val streamUrl: String?,
        val streamInfoHash: String?,
        val streamFileIndex: Int?,
        val streamAddon: String?,
        val activeProfileId: Int,
        val inputs: OwnerInputs
    ) {
        fun samePlaybackAs(other: PlaybackIdentity): Boolean =
            contentType == other.contentType && contentId == other.contentId &&
                videoId == other.videoId && season == other.season && episode == other.episode &&
                streamUrl == other.streamUrl && streamInfoHash == other.streamInfoHash &&
                streamFileIndex == other.streamFileIndex && streamAddon == other.streamAddon
    }

    // Retain only IDs for discovery eligibility, including watched exclusions.
    // Do not retain offscreen metadata/details just to compare a history update.
    private data class WatchedCandidateKey(val type: ContentType, val id: String, val imdbId: String?) {
        fun isWatched(movies: Set<String>, series: Set<String>): Boolean {
            val ids = if (type == ContentType.MOVIE) movies else series
            return id in ids || imdbId?.let(ids::contains) == true
        }
    }

    private data class PipelineToken(val revision: Long, val identity: PlaybackIdentity)

    private data class PlaybackSnapshot(
        val identity: PlaybackIdentity,
        val contentType: String?,
        val postPlayRecommendationsEnabled: Boolean,
        val postPlayMovieThresholdPercent: Int,
        val isNextEpisodeMetadataResolved: Boolean,
        val nextEpisodeHasAired: Boolean?,
        val nextEpisodeAvailable: Boolean?,
        val nextEpisodeReleased: String?,
        val hasError: Boolean,
        val hasBlockingInteraction: Boolean,
        val playbackEnded: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val hasActiveAutoPlay: Boolean
    )

    private data class ResolvedCandidate(
        val recommendation: PostPlayRecommendation,
        val meta: Meta?
    )

    private data class RatingPreferences(
        val isMdbListActive: Boolean,
        val showStandardRatings: Boolean
    )

    private val _uiState = MutableStateFlow(PostPlayRecommendationUiState())
    val uiState: StateFlow<PostPlayRecommendationUiState> = _uiState.asStateFlow()

    private var observationJob: Job? = null
    private val watchedEligibilityRevision = AtomicLong()
    private var watchedCandidateKeys: Set<WatchedCandidateKey>? = null
    private var currentContentIds = emptySet<String>()
    private var recommendationJob: Job? = null
    private var recommendationSelectionJob: Job? = null
    private var postEndCountdownJob: Job? = null
    private var returnToPlayerAnimationJob: Job? = null
    private var recommendationCandidates = emptyList<MetaPreview>()
    private var ratingPreferences: RatingPreferences? = null
    private val candidateResolutionJobs = mutableMapOf<Int, Deferred<ResolvedCandidate?>>()
    private val recommendationDetailJobs = mutableMapOf<Int, Job>()
    private val recommendationCache = mutableMapOf<Int, PostPlayRecommendation>()
    private val completedRecommendationDetails = mutableSetOf<Int>()
    private var pipelineRevision = 0L
    private var detailRevision = 0L
    private var selectedCandidateIndex = 0
    private var recommendationLoadAttempted = false
    private val postPlayTrailerPlaybackEnabled = AppFeaturePolicy.inAppTrailerPlaybackEnabled
    private var autoPlayTrailerEnabled = postPlayTrailerPlaybackEnabled
    private var lastSnapshot: PlaybackSnapshot? = null
    private var lastPlaybackIdentity: PlaybackIdentity? = null

    init {
        val observation = scope.launch(start = CoroutineStart.LAZY) {
            val preferences = combine(
                tmdbSettingsDataStore.settings,
                mdbListSettingsDataStore.settings,
                trailerSettingsDataStore.settings,
                traktSettingsDataStore.moreLikeThisSource,
                traktAuthDataStore.isAuthenticated
            ) { tmdb, ratings, trailers, relatedSource, authenticated ->
                RecommendationPreferences(tmdb, ratings, trailers, relatedSource, authenticated)
            }
            val displayPreferences = combine(
                preferences,
                layoutPreferenceDataStore.hideUnreleasedContent,
                layoutPreferenceDataStore.homeImdbRatingsVisibility
            ) { base, hideUnreleased, visibility ->
                base.copy(hideUnreleased = hideUnreleased, ratingVisibility = visibility)
            }
            // The first projection is a baseline, not a synthetic empty history.
            // Preserve the fresh filtering read after discovery. Later semantic
            // membership changes retire work; metadata-only writes keep its owner.
            var previousWatched: Pair<Set<String>, Set<String>>? = null
            val watchedProjection = combine(
                watchProgressRepository.observeWatchedMovieIds(),
                watchedSeriesStateHolder.fullyWatchedSeriesIds
            ) { movies, series -> movies.toSet() to series.toSet() }
                .map { current ->
                    val previous = previousWatched
                    previousWatched = current
                    if (previous != null && watchedEligibilityChanged(previous, current)) watchedEligibilityRevision.incrementAndGet()
                    else watchedEligibilityRevision.get()
                }
                .onStart { emit(watchedEligibilityRevision.get()) }
                .distinctUntilChanged()
            val watchedChanges = playerSettingsDataStore.playerSettings
                .map { it.postPlayRecommendationsEnabled }
                .distinctUntilChanged()
                .flatMapLatest { enabled ->
                    if (enabled) watchedProjection else {
                        previousWatched = null
                        flowOf(watchedEligibilityRevision.incrementAndGet())
                    }
                }
            val ownerInputs = combine(
                displayPreferences,
                profileManager.profileSelectionRevision,
                profileManager.profileHistoryGenerationChanges,
                watchedChanges
            ) { settings, selectionRevision, generation, watchedRevision ->
                OwnerInputs(settings, selectionRevision, generation, watchedRevision)
            }
            combine(
                playbackController.uiState,
                playbackController.playbackTimeline,
                playerSettingsDataStore.playerSettings,
                ownerInputs,
                profileManager.activeProfileId
            ) { playerState, timeline, playerSettings, inputs, profileId ->
                PlaybackSnapshot(
                    identity = PlaybackIdentity(
                        contentType = playerState.contentType?.trim()?.lowercase(),
                        contentId = playbackController.contentId,
                        videoId = playerState.currentVideoId,
                        season = playerState.currentSeason,
                        episode = playerState.currentEpisode,
                        streamUrl = playerState.currentStreamUrl,
                        streamInfoHash = playerState.currentStreamInfoHash,
                        streamFileIndex = playerState.currentStreamFileIdx,
                        streamAddon = playerState.currentStreamAddonName,
                        activeProfileId = profileId,
                        inputs = inputs
                    ),
                    contentType = playerState.contentType,
                    postPlayRecommendationsEnabled = playerSettings.postPlayRecommendationsEnabled,
                    postPlayMovieThresholdPercent = playerSettings.postPlayMovieThresholdPercent,
                    isNextEpisodeMetadataResolved = playerState.isNextEpisodeMetadataResolved,
                    nextEpisodeHasAired = playerState.nextEpisode?.hasAired,
                    nextEpisodeAvailable = playerState.nextEpisode?.available,
                    nextEpisodeReleased = playerState.nextEpisode?.released,
                    hasError = !playerState.error.isNullOrBlank(),
                    hasBlockingInteraction = playerState.blocksPostPlayRecommendation(),
                    playbackEnded = playerState.playbackEnded,
                    positionMs = timeline.currentPosition,
                    durationMs = timeline.duration,
                    hasActiveAutoPlay = playerState.postPlayMode is PostPlayMode.AutoPlay &&
                        playerState.nextEpisode?.hasAired == true
                )
            }.distinctUntilChanged().collect { snapshot ->
                val previous = lastPlaybackIdentity
                if (previous != null && previous != snapshot.identity) {
                    val retainReturn = _uiState.value.hasReturnedToPlayer && previous.samePlaybackAs(snapshot.identity)
                    clearRecommendationState()
                    if (retainReturn) _uiState.update { it.copy(hasReturnedToPlayer = true) }
                }
                lastPlaybackIdentity = snapshot.identity
                lastSnapshot = snapshot
                evaluate(snapshot)
            }
        }
        observationJob = observation
        observation.start()
    }

    private fun watchedEligibilityChanged(
        previous: Pair<Set<String>, Set<String>>,
        current: Pair<Set<String>, Set<String>>
    ): Boolean {
        val keys = watchedCandidateKeys
        if (keys != null) return keys.any { key ->
            key.isWatched(previous.first, previous.second) != key.isWatched(current.first, current.second)
        }
        // Until discovery finishes, any changed non-current membership may affect
        // its result. Compare both raw projections using the current aliases so
        // learning an alias or candidate pool cannot itself create a change.
        val ignored = currentContentIds + setOfNotNull(playbackController.contentId?.normalizedId())
        fun relevant(ids: Set<String>) = ids.filterNot { it.normalizedId() in ignored }.toSet()
        return relevant(previous.first) != relevant(current.first) ||
            relevant(previous.second) != relevant(current.second)
    }

    private fun currentToken(): PipelineToken? = lastPlaybackIdentity?.let { PipelineToken(pipelineRevision, it) }

    private fun isCurrent(token: PipelineToken): Boolean =
        observationJob?.isActive == true &&
            token.revision == pipelineRevision && token.identity == lastPlaybackIdentity &&
            token.identity.activeProfileId == playbackController.profileId &&
            profileManager.activeProfileId.value == playbackController.profileId &&
            token.identity.inputs.profileSelectionRevision == profileManager.profileSelectionRevision.value &&
            token.identity.inputs.historyGeneration == profileManager.profileHistoryGenerationChanges.value &&
            token.identity.inputs.watchedEligibilityRevision == watchedEligibilityRevision.get()

    private suspend fun ensureCurrent(token: PipelineToken) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(token)) throw CancellationException("Recommendation owner changed")
    }

    private fun isSelected(token: PipelineToken, index: Int, revision: Long): Boolean =
        isCurrent(token) && selectedCandidateIndex == index && detailRevision == revision

    private suspend fun ensureSelected(token: PipelineToken, index: Int, revision: Long) {
        ensureCurrent(token)
        if (!isSelected(token, index, revision)) throw CancellationException("Recommendation selection changed")
    }

    fun playTrailer() {
        startTrailer()
    }

    fun onTrailerEnded() {
        trailerPlayerPool.stop()
        _uiState.update {
            it.copy(
                countdownSeconds = null,
                isTrailerPlaying = false,
                hasAutoPlayedTrailer = true
            )
        }
    }

    fun showPreviousRecommendation() {
        selectRecommendation(-1)
    }

    fun showNextRecommendation() {
        selectRecommendation(1)
    }

    fun returnToPlayer() {
        val state = _uiState.value
        val returnedState = state.returnToPlayer()
        if (returnedState == state) return
        recommendationJob?.cancel()
        recommendationJob = null
        clearRecommendationPipeline()
        postEndCountdownJob?.cancel()
        postEndCountdownJob = null
        returnToPlayerAnimationJob?.cancel()
        _uiState.value = returnedState
        val revision = pipelineRevision
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(POST_PLAY_RECOMMENDATION_TRANSITION_MS.toLong())
                if (pipelineRevision == revision) _uiState.update {
                    it.copy(isVisible = false, hasReturnedToPlayer = true, countdownSeconds = null, isTrailerPlaying = false)
                }
            } finally {
                if (returnToPlayerAnimationJob === currentCoroutineContext()[Job]) returnToPlayerAnimationJob = null
            }
        }
        returnToPlayerAnimationJob = job
        job.start()
    }

    fun stop() {
        observationJob?.cancel()
        observationJob = null
        clearRecommendationState()
        lastSnapshot = null
        lastPlaybackIdentity = null
    }

    private fun clearRecommendationState() {
        recommendationJob?.cancel()
        recommendationJob = null
        clearRecommendationPipeline()
        postEndCountdownJob?.cancel()
        postEndCountdownJob = null
        returnToPlayerAnimationJob?.cancel()
        returnToPlayerAnimationJob = null
        recommendationLoadAttempted = false
        autoPlayTrailerEnabled = postPlayTrailerPlaybackEnabled
        if (_uiState.value.isTrailerPlaying) {
            trailerPlayerPool.stop()
        }
        _uiState.value = PostPlayRecommendationUiState()
    }

    private fun clearRecommendationPipeline() {
        pipelineRevision++
        detailRevision++
        selectedCandidateIndex = 0
        completedRecommendationDetails.clear()
        recommendationSelectionJob?.cancel()
        recommendationSelectionJob = null
        candidateResolutionJobs.values.forEach { it.cancel() }
        candidateResolutionJobs.clear()
        recommendationDetailJobs.values.forEach { it.cancel() }
        recommendationDetailJobs.clear()
        recommendationCandidates = emptyList()
        watchedCandidateKeys = null
        currentContentIds = emptySet()
        recommendationCache.clear()
        ratingPreferences = null
    }

    private fun evaluate(snapshot: PlaybackSnapshot) {
        // Preferences/history may only be used by the profile owning this playback session.
        if (snapshot.identity.activeProfileId != playbackController.profileId ||
            profileManager.activeProfileId.value != playbackController.profileId) return
        // If the player already has an active auto-play (next episode found and queued),
        // recommendations must not appear — clear any in-flight state and bail out.
        if (snapshot.hasActiveAutoPlay) {
            if (_uiState.value.recommendation != null ||
                _uiState.value.isVisible ||
                _uiState.value.isLoadingRecommendation ||
                recommendationJob != null
            ) {
                clearRecommendationState()
            }
            return
        }

        val shouldUseRecommendation = shouldUsePostPlayRecommendation(
            contentType = snapshot.contentType,
            isNextEpisodeMetadataResolved = snapshot.isNextEpisodeMetadataResolved,
            nextEpisodeHasAired = snapshot.nextEpisodeHasAired,
            nextEpisodeAvailable = snapshot.nextEpisodeAvailable,
            nextEpisodeReleased = snapshot.nextEpisodeReleased,
            enabled = snapshot.postPlayRecommendationsEnabled
        )
        if (!shouldUseRecommendation || snapshot.hasError) {
            if (_uiState.value.recommendation != null ||
                _uiState.value.isVisible ||
                _uiState.value.isLoadingRecommendation ||
                recommendationJob != null
            ) {
                clearRecommendationState()
            }
            return
        }

        if (_uiState.value.hasReturnedToPlayer) return

        val effectiveDuration = snapshot.durationMs
            .takeIf { it > 0L }
            ?: playbackController.lastKnownDuration
        if (isShortPlaceholderDuration(effectiveDuration)) return

        if (!recommendationLoadAttempted &&
            shouldPrefetchPostPlayRecommendation(
                positionMs = snapshot.positionMs,
                durationMs = effectiveDuration,
                progressThreshold = postPlayRecommendationPrefetchProgress(
                    contentType = snapshot.contentType,
                    movieThresholdPercent = snapshot.postPlayMovieThresholdPercent,
                    durationMs = effectiveDuration,
                    skipIntervals = playbackController.skipIntervals,
                    episodeThresholdMode = playbackController.nextEpisodeThresholdModeSetting,
                    episodeThresholdPercent = playbackController.nextEpisodeThresholdPercentSetting,
                    episodeThresholdMinutesBeforeEnd = playbackController.nextEpisodeThresholdMinutesBeforeEndSetting
                )
            )
        ) {
            loadRecommendation()
        }

        var state = _uiState.value
        val recommendation = state.recommendation ?: return
        val shouldShow = shouldShowPostPlayRecommendation(
            contentType = snapshot.contentType,
            positionMs = snapshot.positionMs,
            durationMs = effectiveDuration,
            skipIntervals = playbackController.skipIntervals,
            movieThresholdPercent = snapshot.postPlayMovieThresholdPercent,
            episodeThresholdMode = playbackController.nextEpisodeThresholdModeSetting,
            episodeThresholdPercent = playbackController.nextEpisodeThresholdPercentSetting,
            episodeThresholdMinutesBeforeEnd = playbackController.nextEpisodeThresholdMinutesBeforeEndSetting
        ) || snapshot.playbackEnded

        if (!shouldShow) return
        if (!state.isVisible && snapshot.hasBlockingInteraction) return

        if (!state.isVisible) {
            val needsPostEndCountdown = snapshot.playbackEnded &&
                recommendation.hasTrailer &&
                autoPlayTrailerEnabled
            _uiState.update {
                it.copy(
                    isVisible = true,
                    countdownSeconds = if (needsPostEndCountdown) {
                        POST_PLAY_RECOMMENDATION_TRAILER_COUNTDOWN_SECONDS
                    } else {
                        postPlayRecommendationCountdownSeconds(snapshot.positionMs, effectiveDuration)
                    }
                )
            }
            state = _uiState.value
            if (needsPostEndCountdown) {
                startPostEndCountdown()
                return
            }
        }

        if (state.isTrailerPlaying || state.hasAutoPlayedTrailer || !recommendation.hasTrailer) return
        if (!autoPlayTrailerEnabled) {
            if (state.countdownSeconds != null) {
                _uiState.update { it.copy(countdownSeconds = null) }
            }
            return
        }

        if (snapshot.playbackEnded) {
            // Timeline updates must leave the admitted five-second job in control.
            if (postEndCountdownJob?.isActive == true) return
            if (state.countdownSeconds != null) {
                startTrailer()
            } else {
                startPostEndCountdown()
            }
            return
        }

        val countdown = postPlayRecommendationCountdownSeconds(snapshot.positionMs, effectiveDuration)
        if (countdown != state.countdownSeconds) {
            _uiState.update { it.copy(countdownSeconds = countdown) }
        }
    }

    private fun loadRecommendation() {
        val token = currentToken() ?: return
        if (!isCurrent(token)) return
        recommendationLoadAttempted = true
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                ensureCurrent(token)
                _uiState.update { it.copy(isLoadingRecommendation = true) }
                val candidates = try {
                    val currentMeta = loadCurrentMeta(token)
                    ensureCurrent(token)
                    currentMeta?.let { loadCandidates(it, token) }.orEmpty()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                ensureCurrent(token)
                if (candidates.isEmpty()) {
                    _uiState.update { it.copy(isLoadingRecommendation = false) }
                    return@launch
                }
                recommendationCandidates = candidates.toList()
                val settings = token.identity.inputs.preferences
                val isMdbListActive = mdbListRepository.isAvailable(settings.mdbList)
                val preferences = RatingPreferences(
                    isMdbListActive = isMdbListActive,
                    showStandardRatings = settings.ratingVisibility.showStandardDetailRatings(isMdbListActive)
                )
                ratingPreferences = preferences
                _uiState.update { it.copy(mdbListRatingOrder = settings.mdbList.enabledRatingOrder()) }
                autoPlayTrailerEnabled = postPlayTrailerPlaybackEnabled && settings.trailers.enabled
                selectedCandidateIndex = 0
                val selectionRevision = ++detailRevision
                val resolved = awaitCandidateResolution(0, token)
                ensureSelected(token, 0, selectionRevision)
                if (resolved == null) {
                    clearRecommendationPipeline()
                    _uiState.update { it.copy(isLoadingRecommendation = false) }
                    return@launch
                }
                val recommendation = cacheRecommendation(0, resolved, preferences)
                _uiState.update {
                    if (isSelected(token, 0, selectionRevision)) it.copy(
                        recommendation = recommendation,
                        recommendationIndex = 0,
                        recommendationCount = candidates.size,
                        isLoadingRecommendation = false,
                        isLoadingTrailer = postPlayTrailerPlaybackEnabled
                    ) else it
                }
                lastSnapshot?.let(::evaluate)
                loadRecommendationDetails(0, resolved, preferences, token, selectionRevision)
            } finally {
                if (recommendationJob === currentCoroutineContext()[Job]) recommendationJob = null
            }
        }
        recommendationJob = job
        job.start()
    }

    private suspend fun awaitCandidateResolution(index: Int, token: PipelineToken): ResolvedCandidate? {
        ensureCurrent(token)
        if (index !in recommendationCandidates.indices) return null
        val candidate = recommendationCandidates[index]
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                ensureCurrent(token)
                resolveCandidate(candidate, token.identity.inputs.preferences.tmdb, token).also { ensureCurrent(token) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        candidateResolutionJobs[index] = job
        job.start()
        return try {
            job.await().also { ensureCurrent(token) }
        } finally {
            if (candidateResolutionJobs[index] === job) candidateResolutionJobs.remove(index)
            if (!job.isCompleted) job.cancel()
        }
    }

    private fun cacheRecommendation(index: Int, resolved: ResolvedCandidate, preferences: RatingPreferences): PostPlayRecommendation =
        recommendationCache.getOrPut(index) { resolved.recommendation.copy(showStandardRatings = preferences.showStandardRatings) }

    private fun retireOffscreenWork(index: Int) {
        candidateResolutionJobs.keys.filter { it != index }.forEach { candidateResolutionJobs.remove(it)?.cancel() }
        recommendationDetailJobs.keys.filter { it != index }.forEach { recommendationDetailJobs.remove(it)?.cancel() }
    }

    private fun selectRecommendation(offset: Int) {
        val state = _uiState.value
        if (!state.isVisible || state.isChangingRecommendation) return
        val targetIndex = state.recommendationIndex + offset
        if (targetIndex !in recommendationCandidates.indices) return
        val token = currentToken() ?: return
        if (!isCurrent(token)) return
        val selectionRevision = ++detailRevision
        selectedCandidateIndex = targetIndex
        retireOffscreenWork(targetIndex)
        postEndCountdownJob?.cancel()
        postEndCountdownJob = null
        if (state.isTrailerPlaying) trailerPlayerPool.stop()
        autoPlayTrailerEnabled = false
        _uiState.update { it.copy(isChangingRecommendation = true, countdownSeconds = null, isTrailerPlaying = false, isLoadingTrailer = false) }
        recommendationSelectionJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                ensureSelected(token, targetIndex, selectionRevision)
                val preferences = ratingPreferences ?: return@launch
                val cached = recommendationCache[targetIndex]
                val detailsComplete = targetIndex in completedRecommendationDetails && cached != null
                val resolved = if (detailsComplete) null else awaitCandidateResolution(targetIndex, token)
                ensureSelected(token, targetIndex, selectionRevision)
                val recommendation = if (detailsComplete) cached else resolved?.let { cacheRecommendation(targetIndex, it, preferences) }
                if (recommendation == null) {
                    _uiState.update { if (isSelected(token, targetIndex, selectionRevision)) it.copy(isChangingRecommendation = false) else it }
                    return@launch
                }
                _uiState.update {
                    if (isSelected(token, targetIndex, selectionRevision)) it.copy(
                        recommendation = recommendation,
                        recommendationIndex = targetIndex,
                        isChangingRecommendation = false,
                        isLoadingTrailer = postPlayTrailerPlaybackEnabled && !detailsComplete
                    ) else it
                }
                if (resolved != null) loadRecommendationDetails(targetIndex, resolved, preferences, token, selectionRevision)
            } finally {
                if (recommendationSelectionJob === currentCoroutineContext()[Job]) recommendationSelectionJob = null
            }
        }
        recommendationSelectionJob = job
        job.start()
    }

    private fun loadRecommendationDetails(
        index: Int,
        resolved: ResolvedCandidate,
        preferences: RatingPreferences,
        token: PipelineToken,
        selectionRevision: Long
    ) {
        if (!isSelected(token, index, selectionRevision) || index !in recommendationCandidates.indices ||
            index in completedRecommendationDetails || recommendationDetailJobs[index]?.isActive == true) return
        val candidate = recommendationCandidates[index]
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                ensureSelected(token, index, selectionRevision)
                val ratingsJob = launch {
                    val ratings = loadRatings(candidate, resolved.meta, preferences.isMdbListActive)
                    ensureSelected(token, index, selectionRevision)
                    updateCachedRecommendation(index, token, selectionRevision) { it.copy(mdbListRatings = ratings) }
                }
                val trailerJob = launch {
                    if (!postPlayTrailerPlaybackEnabled) return@launch
                    val recommendation = recommendationCache[index] ?: return@launch
                    val trailerSource = try {
                        withTimeoutOrNull(15_000L) {
                            trailerService.getTrailerPlaybackSource(
                                title = recommendation.title, year = recommendation.releaseInfo,
                                tmdbId = recommendation.tmdbId, type = recommendation.contentType,
                                ignoreUseTrailersGate = true
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    ensureSelected(token, index, selectionRevision)
                    updateCachedRecommendation(index, token, selectionRevision) {
                        it.copy(trailerVideoUrl = trailerSource?.videoUrl, trailerAudioUrl = trailerSource?.audioUrl)
                    }
                    _uiState.update { if (isSelected(token, index, selectionRevision)) it.copy(isLoadingTrailer = false) else it }
                    if (isSelected(token, index, selectionRevision)) lastSnapshot?.let(::evaluate)
                }
                ratingsJob.join()
                trailerJob.join()
                ensureSelected(token, index, selectionRevision)
                completedRecommendationDetails.add(index)
                _uiState.update { if (isSelected(token, index, selectionRevision)) it.copy(isLoadingTrailer = false) else it }
            } finally {
                if (recommendationDetailJobs[index] === currentCoroutineContext()[Job]) recommendationDetailJobs.remove(index)
            }
        }
        recommendationDetailJobs[index] = job
        job.start()
    }

    private fun updateCachedRecommendation(
        index: Int, token: PipelineToken, selectionRevision: Long,
        transform: (PostPlayRecommendation) -> PostPlayRecommendation
    ) {
        if (!isSelected(token, index, selectionRevision)) return
        val recommendation = recommendationCache[index]?.let(transform) ?: return
        recommendationCache[index] = recommendation
        _uiState.update { if (isSelected(token, index, selectionRevision)) it.copy(recommendation = recommendation) else it }
    }

    private suspend fun loadRatings(
        candidate: MetaPreview,
        meta: Meta?,
        enabled: Boolean
    ) = if (!enabled || meta == null) {
        null
    } else {
        try {
            mdbListRepository.getRatingsForMeta(
                meta = meta,
                fallbackItemId = candidate.id,
                fallbackItemType = candidate.apiType
            )?.ratings
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadCurrentMeta(token: PipelineToken): Meta? {
        ensureCurrent(token)
        val id = token.identity.contentId ?: return null
        val type = token.identity.contentType ?: return null
        metaRepository.getCachedMeta(type, id)?.let { return it }
        return withTimeoutOrNull(8_000L) {
            when (
                val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
                    .first { it !is NetworkResult.Loading }
            ) {
                is NetworkResult.Success -> result.data
                else -> null
            }
        }
    }

    private suspend fun loadCandidates(meta: Meta, token: PipelineToken): List<MetaPreview> {
        ensureCurrent(token)
        val owner = token.identity
        val preferences = owner.inputs.preferences
        val currentIds = setOfNotNull(meta.id.normalizedId(), owner.contentId?.normalizedId())
        currentContentIds = currentIds
        val tmdbContentType = resolvePostPlayContentType(
            apiType = owner.contentType,
            fallback = meta.type
        ) ?: return emptyList()
        val candidates = withTimeoutOrNull(10_000L) {
            val sourcePreference = preferences.relatedSource
            val traktAuthenticated = preferences.traktAuthenticated
            if (sourcePreference == MoreLikeThisSourcePreference.SIMKL && simklAuthRepository.state.value.isAuthenticated) {
                try {
                    simklRelatedService.getRelated(
                        meta = meta,
                        fallbackItemId = owner.contentId,
                        fallbackItemType = owner.contentType
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            } else if (sourcePreference == MoreLikeThisSourcePreference.TRAKT && traktAuthenticated) {
                try {
                    traktRelatedService.getRelated(
                        meta = meta,
                        fallbackItemId = owner.contentId,
                        fallbackItemType = owner.contentType
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                val settings = preferences.tmdb
                if (!settings.enabled || !settings.useMoreLikeThis) return@withTimeoutOrNull emptyList()
                val lookupType = tmdbContentType.toApiString(owner.contentType)
                var tmdbId = tmdbService.ensureTmdbId(meta.id, lookupType, fallbackImdbId = meta.imdbId)
                ensureCurrent(token)
                if (tmdbId == null) {
                    tmdbId = owner.contentId?.let {
                        tmdbService.ensureTmdbId(it, lookupType, fallbackImdbId = meta.imdbId)
                    }
                    ensureCurrent(token)
                }
                if (tmdbId == null) return@withTimeoutOrNull emptyList()
                try {
                    tmdbMetadataService.fetchMoreLikeThis(
                        tmdbId = tmdbId,
                        contentType = tmdbContentType,
                        language = settings.language
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.orEmpty()

        ensureCurrent(token)
        val hideUnreleased = preferences.hideUnreleased
        watchedCandidateKeys = candidates.asSequence()
            .filterNot { it.id.normalizedId() in currentIds }
            .filterNot { hideUnreleased && it.isUnreleased(LocalDate.now()) }
            .mapNotNull { candidate ->
                resolvePostPlayContentType(candidate.apiType, candidate.type)?.let { type ->
                    WatchedCandidateKey(type, candidate.id, candidate.imdbId)
                }
            }.toSet()
        val watchedIds = combine(
            watchProgressRepository.observeWatchedMovieIds(),
            watchedSeriesStateHolder.fullyWatchedSeriesIds
        ) { movieIds, seriesIds -> movieIds to seriesIds }.first()
        ensureCurrent(token)
        val filtered = candidates
            .asSequence()
            .filterNot { it.id.normalizedId() in currentIds }
            .filterNot { candidate ->
                isPostPlayCandidateWatched(
                    candidate = candidate,
                    watchedMovieIds = watchedIds.first,
                    watchedSeriesIds = watchedIds.second
                )
            }
            .filterNot { hideUnreleased && it.isUnreleased(LocalDate.now()) }
            .distinctBy { it.apiType.normalizedId() to it.id.normalizedId() }
            .toList()
        val first = filtered.firstOrNull { !it.backdropUrl.isNullOrBlank() }
            ?: filtered.firstOrNull()
            ?: return emptyList()
        return buildList {
            add(first)
            filtered.asSequence()
                .filterNot { it === first }
                .take(MAX_POST_PLAY_RECOMMENDATIONS - 1)
                .forEach(::add)
        }
    }

    private suspend fun resolveCandidate(candidate: MetaPreview, settings: TmdbSettings, token: PipelineToken): ResolvedCandidate {
        ensureCurrent(token)
        val meta = try {
            loadCandidateMeta(candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        ensureCurrent(token)
        val candidateContentType = resolvePostPlayContentType(
            apiType = meta?.apiType ?: candidate.apiType,
            fallback = meta?.type ?: candidate.type
        )
        val candidateImdbId = meta?.imdbId ?: candidate.imdbId
        val tmdbId = try {
            val primaryId = tmdbService.ensureTmdbId(
                videoId = meta?.id ?: candidate.id,
                mediaType = meta?.apiType ?: candidate.apiType,
                fallbackImdbId = candidateImdbId
            )
            ensureCurrent(token)
            if (primaryId == null && meta?.id != candidate.id) {
                tmdbService.ensureTmdbId(candidate.id, candidate.apiType, fallbackImdbId = candidateImdbId)
                    .also { ensureCurrent(token) }
            } else {
                primaryId
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        ensureCurrent(token)
        val enrichment = if (settings.enabled && tmdbId != null && candidateContentType != null) {
            try {
                withTimeoutOrNull(12_000L) {
                    tmdbMetadataService.fetchEnrichment(
                        tmdbId = tmdbId,
                        contentType = candidateContentType,
                        language = settings.language
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        ensureCurrent(token)
        return ResolvedCandidate(
            recommendation = resolvePostPlayRecommendation(
                candidate = candidate,
                meta = meta,
                enrichment = enrichment,
                settings = settings,
                tmdbId = tmdbId
            ),
            meta = meta
        )
    }

    private suspend fun loadCandidateMeta(candidate: MetaPreview): Meta? {
        metaRepository.getCachedMeta(candidate.apiType, candidate.id)?.let { return it }
        return withTimeoutOrNull(8_000L) {
            when (
                val result = metaRepository.getMetaFromAllAddons(
                    type = candidate.apiType,
                    id = candidate.id,
                    sourceAddonBaseUrl = candidate.sourceAddonBaseUrl
                ).first { it !is NetworkResult.Loading }
            ) {
                is NetworkResult.Success -> result.data
                else -> null
            }
        }
    }

    private fun startPostEndCountdown() {
        val state = _uiState.value
        if (!postPlayTrailerPlaybackEnabled ||
            postEndCountdownJob?.isActive == true ||
            state.isTrailerPlaying ||
            state.hasAutoPlayedTrailer ||
            state.recommendation?.hasTrailer != true
        ) {
            return
        }
        val token = currentToken() ?: return
        val index = selectedCandidateIndex
        val selectionRevision = detailRevision
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                for (seconds in POST_PLAY_RECOMMENDATION_TRAILER_COUNTDOWN_SECONDS downTo 1) {
                    ensureSelected(token, index, selectionRevision)
                    _uiState.update { if (isSelected(token, index, selectionRevision)) it.copy(countdownSeconds = seconds) else it }
                    delay(1_000L)
                }
                ensureSelected(token, index, selectionRevision)
                startTrailer()
            } finally {
                if (postEndCountdownJob === currentCoroutineContext()[Job]) postEndCountdownJob = null
            }
        }
        postEndCountdownJob = job
        job.start()
    }

    private fun startTrailer() {
        val token = currentToken() ?: return
        if (!isCurrent(token)) return
        val state = _uiState.value
        if (state.isChangingRecommendation || state.recommendationIndex != selectedCandidateIndex) return
        if (!postPlayTrailerPlaybackEnabled ||
            state.isTrailerPlaying ||
            state.recommendation?.hasTrailer != true
        ) {
            return
        }
        postEndCountdownJob?.cancel()
        postEndCountdownJob = null
        playbackController.releasePlayer()
        trailerPlayerPool.reclaim()
        _uiState.update {
            it.copy(
                isVisible = true,
                countdownSeconds = null,
                isTrailerPlaying = true,
                hasAutoPlayedTrailer = true
            )
        }
    }
}

private const val MAX_POST_PLAY_RECOMMENDATIONS = 4

private fun String.normalizedId(): String = trim().lowercase()
