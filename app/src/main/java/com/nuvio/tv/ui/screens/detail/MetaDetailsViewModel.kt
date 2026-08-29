package com.nuvio.tv.ui.screens.detail

import kotlinx.coroutines.flow.stateIn
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.health.AddonHealthStore
import com.nuvio.tv.core.health.HealthOutcome
import com.nuvio.tv.core.player.StreamAutoPlayPolicy
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbMovieCollection
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.repository.ImdbEpisodeRatingsRepository
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.repository.TraktCommentsService
import com.nuvio.tv.data.repository.TraktRelatedService
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.tv.core.tracking.toggleTrackingMembershipSelection
import com.nuvio.tv.core.tracking.TrackingProgressRefreshCoordinator
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaTrailer
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.TraktCommentReview
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.core.util.isUnreleased
import com.nuvio.tv.core.util.selectEpisodeReleaseValue
import java.time.LocalDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject

private const val TAG = "MetaDetailsViewModel"
private const val STREAM_PREFETCH_DEBOUNCE_MS = 300L

// S4a-2: episode focus moves on every D-pad press while scrolling a season,
// which is far faster than opening a details page, so this is deliberately
// longer than STREAM_PREFETCH_DEBOUNCE_MS. collectLatest cancels a pending
// delay when focus moves again, so scrolling past an episode starts no work
// at all and only a deliberate pause triggers a scrape. [inferred] -- chosen
// as 2x the hero debounce, retunable in one line if it fires too eagerly.
private const val EPISODE_FOCUS_PREFETCH_DEBOUNCE_MS = 600L

@HiltViewModel
class MetaDetailsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metaRepository: MetaRepository,
    // S4a: used only to pre-scrape the play target while this page is open.
    private val streamRepository: com.nuvio.tv.domain.repository.StreamRepository,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val imdbEpisodeRatingsRepository: ImdbEpisodeRatingsRepository,
    private val mdbListRepository: MDBListRepository,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val libraryRepository: LibraryRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val trailerService: TrailerService,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktCommentsService: TraktCommentsService,
    private val traktRelatedService: TraktRelatedService,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    private val prefetchSelectionSupplier: com.nuvio.tv.core.stream.PrefetchSelectionSupplier,
    savedStateHandle: SavedStateHandle,
    private val healthStore: AddonHealthStore,
    private val trackingProgressRefreshCoordinator: TrackingProgressRefreshCoordinator
) : ViewModel() {
    private val itemId: String = savedStateHandle["itemId"] ?: ""
    private val itemType: String = savedStateHandle["itemType"] ?: ""
    private val preferredAddonBaseUrl: String? = savedStateHandle["addonBaseUrl"]

    private val _uiState = MutableStateFlow(MetaDetailsUiState())
    val uiState: StateFlow<MetaDetailsUiState> = _uiState.asStateFlow()

    // S4a: the details page resolves its play target (NextToWatch -> the
    // "Next S1 E2" / "Resume ..." button) well before the press, but the addon
    // scrape cannot start until StreamScreenViewModel exists, which is after it.
    // Measured 24 Jul 2026 (Xiaomi, auto-play Best quality): that scrape is
    // 1,949-2,896 ms and is paid on every play. Kick it off here instead.
    //
    // Debounced so an accidental open that is immediately backed out of does not
    // scrape. Every millisecond of debounce is a millisecond of head start given
    // up, so it is deliberately short. StreamPrefetchCache keeps at most one
    // prefetch in flight and drops the result if nothing consumes it.
    private data class StreamPrefetchTarget(
        val type: String,
        val videoId: String,
        val season: Int?,
        val episode: Int?,
        /** The meta id, for the binge-group cache the stream screen reads. */
        val contentId: String?
    )

    private var lastStreamPrefetchKey: String? = null
    private var streamPrefetchJob: Job? = null

    /** Fork: details-page source line. Key of the hero-target prefetch; SEARCHING until the supplier signals. */
    private val heroSourceKey = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val heroSourceSignal: kotlinx.coroutines.flow.StateFlow<com.nuvio.tv.core.stream.SourcePrefetchSignal?> =
        kotlinx.coroutines.flow.combine(
            heroSourceKey,
            prefetchSelectionSupplier.uiSignals,
            playerSettingsDataStore.playerSettings
        ) { key, sig, settings ->
            when {
                key == null -> null
                settings.streamAutoPlayMode == com.nuvio.tv.data.local.StreamAutoPlayMode.MANUAL -> null
                sig?.uiKey != key -> com.nuvio.tv.core.stream.SourcePrefetchSignal(
                    key, com.nuvio.tv.core.stream.SourcePrefetchPhase.SEARCHING, null
                )
                sig.phase == com.nuvio.tv.core.stream.SourcePrefetchPhase.EMPTY -> null
                else -> sig
            }
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            null
        )

    /**
     * nt4: kick the stream prefetch as early as the play target is knowable -
     * at raw-meta time, before enrichment - so the prewarm has the maximum head
     * start to finish before the user presses. Mirrors the post-applyMeta
     * observer's target selection exactly (movie -> the meta id; series -> the
     * nextToWatch episode, or nothing when there is no next), so the key matches
     * and the observer's later fire is a dedup no-op rather than a re-scrape.
     */
    private fun launchEarlyStreamPrefetch(rawMeta: Meta) {
        viewModelScope.launch {
            val isSeries = rawMeta.videos.any { it.season != null }
            val target: StreamPrefetchTarget? = if (isSeries) {
                val progressMap = watchProgressRepository
                    .getAllEpisodeProgress(_effectiveContentId.value)
                    .first()
                val watchedEpisodes = watchedItemsPreferences
                    .getWatchedEpisodesForContent(_effectiveContentId.value)
                    .first()
                val ntw = computeNextToWatch(rawMeta, progressMap, watchedEpisodes)
                // Fork (caught-up fix, A2): caught-up suppresses the hero
                // source line entirely - no early scrape, no hero key. The
                // Play button still targets the last episode; a press pays a
                // normal scrape under the user's auto/manual selection mode.
                // Without this gate the early path fires for the (non-null)
                // replay target and the line flashes before the observer
                // hides it.
                if (ntw.isCaughtUp) {
                    null
                } else ntw.nextVideoId?.let {
                    StreamPrefetchTarget(rawMeta.apiType, it, ntw.nextSeason, ntw.nextEpisode, rawMeta.id)
                }
            } else {
                StreamPrefetchTarget(rawMeta.apiType, rawMeta.id, null, null, rawMeta.id)
            }
            target?.let {
                onStreamPrefetchTarget(it.type, it.videoId, it.season, it.episode, it.contentId, "details_hero_early")
            }
        }
    }

    private fun onStreamPrefetchTarget(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        contentId: String?,
        source: String
    ) {
        if (type.isBlank() || videoId.isBlank()) return
        val key = com.nuvio.tv.core.stream.StreamPrefetchCache.keyOf(type, videoId, season, episode)
        if (key == lastStreamPrefetchKey) return
        lastStreamPrefetchKey = key
        if (source != "episode_focus") heroSourceKey.value = key
        streamPrefetchJob?.cancel()
        streamPrefetchJob = viewModelScope.launch {
            delay(STREAM_PREFETCH_DEBOUNCE_MS)
            // P2: cap the prefetch's wait at the auto-play timeout when eager
            // ready is on, so a slow source (a bridge measured at 6-9s for 2-3
            // streams) no longer holds up ranking, pre-resolve and the hero
            // source line. Null = pre-P2 behaviour (wait for full completion),
            // used when the toggle is off or the timeout is instant/unlimited.
            val capMs = playerSettingsDataStore.playerSettings.first().eagerReadyCapMs()
            // S4a covers BOTH shapes: a movie's own id, and a series' hero
            // target (resume/next episode). Both now rank and pre-resolve
            // through the same supplier the Continue Watching path uses.
            com.nuvio.tv.core.stream.StreamPrefetchCache.prefetch(
                repository = streamRepository,
                type = type,
                videoId = videoId,
                season = season,
                episode = episode,
                source = source,
                capMs = capMs,
                republishOnDedup = source != "episode_focus",
                rank = { groups ->
                    prefetchSelectionSupplier.rankAndPreResolve(
                        groups = groups,
                        contentId = contentId,
                        season = season,
                        episode = episode,
                        uiKey = if (source != "episode_focus") key else null
                    )
                }
            )
        }
    }


    /**
     * S4a-2: the episode list is the last uncovered play path.
     *
     * S4a prefetches the hero button's target -- a movie, or a series' resume/
     * next episode -- and S4a-3 the top Continue Watching entry. Picking any
     * OTHER episode from the list has always started from cold: full scrape,
     * full rank, full debrid resolve, all after the press.
     *
     * The recorded blocker ("fires in a sub-composable with neither the
     * ViewModel nor onEvent in scope") was half right. MetaDetailsContent
     * genuinely does not receive the ViewModel -- but lastFocusedEpisodeIdBySeason
     * is a ViewModel-owned map already being written from that callback, so the
     * data flow existed and only the hook was missing. One threaded callback,
     * not a new MetaDetailsEvent.
     *
     * Focus is not intent, so this is debounced harder than the hero path. The
     * cost of over-firing is bounded by StreamPrefetchCache being single-flight
     * -- a new target cancels the previous scrape -- and by the pre-resolve
     * running only after that scrape completes, so a scroll-through resolves
     * nothing.
     */
    fun onEpisodeFocusedForPrefetch(episode: Video) {
        val meta = _uiState.value.meta ?: return
        if (episode.id.isBlank()) return
        episodeFocusPrefetchRequests.tryEmit(
            StreamPrefetchTarget(
                type = meta.apiType,
                videoId = episode.id,
                season = episode.season,
                episode = episode.episode,
                contentId = meta.id
            )
        )
    }

    private val episodeFocusPrefetchRequests =
        MutableSharedFlow<StreamPrefetchTarget>(extraBufferCapacity = 16)

    private val episodeFocusPrefetchObserver: Job = viewModelScope.launch {
        episodeFocusPrefetchRequests
            .distinctUntilChanged()
            .collectLatest { target ->
                delay(EPISODE_FOCUS_PREFETCH_DEBOUNCE_MS)
                onStreamPrefetchTarget(
                    target.type,
                    target.videoId,
                    target.season,
                    target.episode,
                    target.contentId,
                    "episode_focus"
                )
            }
    }

    private val streamPrefetchObserver: Job = viewModelScope.launch {
        _uiState
            .map { state ->
                val meta = state.meta
                val ntw = state.nextToWatch
                val nextId = ntw?.nextVideoId
                when {
                    meta == null -> null
                    // Fork (caught-up fix, A2): checked BEFORE nextId - the
                    // caught-up target is the non-null LAST episode, which
                    // would otherwise match the branch below and key the hero
                    // line to a replay the user did not ask to preview.
                    ntw?.isCaughtUp == true -> null
                    // Series: the hero button plays this exact video id.
                    nextId != null -> StreamPrefetchTarget(meta.apiType, nextId, ntw?.nextSeason, ntw?.nextEpisode, meta.id)
                    // Movie: the hero button plays the meta id itself.
                    state.seasons.isEmpty() -> StreamPrefetchTarget(meta.apiType, meta.id, null, null, meta.id)
                    else -> null
                }
            }
            .distinctUntilChanged()
            .collectLatest { target ->
                if (target != null) {
                    onStreamPrefetchTarget(
                        target.type,
                        target.videoId,
                        target.season,
                        target.episode,
                        target.contentId,
                        "details_hero"
                    )
                } else {
                    // Fork (B1): a null target must CLEAR the hero-key state.
                    // Otherwise the source line stays keyed to the previous
                    // target with no prefetch behind it and the combine
                    // synthesises SEARCHING forever (the caught-up spin, and
                    // any future null-target transition). lastStreamPrefetchKey
                    // is cleared with it so a later return to the SAME target
                    // re-enters onStreamPrefetchTarget - re-keying the line via
                    // a cheap cache-dedup republish - instead of dedup-skipping
                    // with the line still hidden.
                    heroSourceKey.value = null
                    lastStreamPrefetchKey = null
                }
            }
    }
    private val _posterCardCornerRadiusDp = MutableStateFlow(12)
    val posterCardCornerRadiusDp: StateFlow<Int> = _posterCardCornerRadiusDp.asStateFlow()

    private val localizedContext: Context
        get() {
            val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET && it.isNotEmpty() }
                ?: return context
            val locale = Locale.forLanguageTag(tag)
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            return context.createConfigurationContext(config)
        }
    val effectiveAutoplayEnabled = playerSettingsDataStore.playerSettings
        .map(StreamAutoPlayPolicy::isEffectivelyEnabled)
        .distinctUntilChanged()

    private var idleTimerJob: Job? = null
    private var trailerFetchJob: Job? = null
    private var moreLikeThisJob: Job? = null
    private var collectionJob: Job? = null

    val lastFocusedEpisodeIdBySeason = androidx.compose.runtime.mutableStateMapOf<Int, String>()
    private var episodeRatingsJob: Job? = null
    private var nextToWatchJob: Job? = null
    private var commentsJob: Job? = null
    private var commentsLoadMoreJob: Job? = null
    private var pendingDefaultLibraryToggle: LibraryEntryInput? = null

    private var trailerDelayMs = 7000L
    private var trailerAutoplayEnabled = false
    private var trailerHasPlayed = false
    private var suppressSeasonAutoSwitch = false

    private var isPlayButtonFocused = false
    private var hideUnreleasedContent = false
    private var traktCommentsEnabled = false
    private var traktAuthenticated = false
    private var moreLikeThisSourcePreference = com.nuvio.tv.data.local.MoreLikeThisSourcePreference.TRAKT

    /** Content ID used for watch-progress and watched-items lookups.
     *  Starts as the navigation [itemId] (which may be "tmdb:123") and is
     *  updated to [Meta.id] once meta loads (typically an IMDB ID like "tt0396375").
     *  This ensures progress is read from the same key it was written under. */
    private val _effectiveContentId = MutableStateFlow(itemId)
    private val _optimisticMarks = mutableSetOf<Pair<Int, Int>>()
    private val _optimisticUnmarks = mutableSetOf<Pair<Int, Int>>()

    init {
        posterOptions.bind(viewModelScope)
        observeMetaViewSettings()
        observeTrailerAutoplaySettings()
        observeTraktCommentsAvailability()
        observeLibraryState()
        observeWatchProgress()
        observeWatchedEpisodes()
        observeMovieWatched()
        observeRelatedWatchedStatus()
        observeBlurUnwatchedEpisodes()
        observeEpisodeOptionsOverlayStyle()
        observeOverallRatingsVisibility()
        observeDetailImdbRatingsVisibility()
        viewModelScope.launch {
            layoutPreferenceDataStore.posterCardCornerRadiusDp
                .collect { _posterCardCornerRadiusDp.value = it }
        }
        observeShowFullReleaseDate()
        observeHideUnreleasedContent()
        loadMeta()
    }

    private fun observeHideUnreleasedContent() {
        viewModelScope.launch {
            layoutPreferenceDataStore.hideUnreleasedContent
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    hideUnreleasedContent = enabled
                }
        }
    }

    private fun observeMetaViewSettings() {
        viewModelScope.launch {
            layoutPreferenceDataStore.detailPageTrailerButtonEnabled
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    _uiState.update { state ->
                        if (state.trailerButtonEnabled == enabled) {
                            state
                        } else {
                            state.copy(trailerButtonEnabled = enabled)
                        }
                    }
                }
        }
    }

    private fun observeTraktCommentsAvailability() {
        viewModelScope.launch {
            traktSettingsDataStore.moreLikeThisSource.collectLatest { source ->
                moreLikeThisSourcePreference = source
            }
        }
        viewModelScope.launch {
            combine(
                traktSettingsDataStore.showMetaComments,
                traktAuthDataStore.isAuthenticated
            ) { enabled, authenticated ->
                enabled to authenticated
            }
                .distinctUntilChanged()
                .collectLatest { (enabled, authenticated) ->
                    traktCommentsEnabled = enabled
                    traktAuthenticated = authenticated

                    val meta = _uiState.value.meta
                    val shouldShow = enabled && authenticated && supportsComments(meta)
                    if (!shouldShow) {
                        cancelCommentsRequests()
                    }

                    _uiState.update { state ->
                        if (shouldShow) {
                            if (state.shouldShowCommentsSection) state else state.copy(
                                shouldShowCommentsSection = true
                            )
                        } else {
                            state.copy(
                                comments = emptyList(),
                                commentsCurrentPage = 0,
                                commentsPageCount = 0,
                                isCommentsLoading = false,
                                isCommentsLoadingMore = false,
                                commentsError = null,
                                shouldShowCommentsSection = false,
                                commentsMode = CommentsMode.TITLE,
                                commentsEpisodeTarget = null,
                                selectedComment = null
                            )
                        }
                    }

                    if (meta != null) {
                        loadMoreLikeThisAsync(meta)
                    }

                    if (shouldShow && meta != null) {
                        loadComments(meta)
                    }
                }
        }
    }

    private fun setTrailerPlaybackState(
        isPlaying: Boolean,
        showControls: Boolean,
        hideLogo: Boolean
    ) {
        _uiState.update { state ->
            if (state.isTrailerPlaying == isPlaying &&
                state.showTrailerControls == showControls &&
                state.hideLogoDuringTrailer == hideLogo
            ) {
                state
            } else {
                state.copy(
                    isTrailerPlaying = isPlaying,
                    showTrailerControls = showControls,
                    hideLogoDuringTrailer = hideLogo
                )
            }
        }
    }

    private fun updateNextToWatch(nextToWatch: NextToWatch) {
        _uiState.update { state ->
            if (state.nextToWatch == nextToWatch) return@update state
            val nextSeason = nextToWatch.nextSeason
            val meta = state.meta
            val shouldSwitchSeason = !suppressSeasonAutoSwitch &&
                nextSeason != null &&
                nextSeason != state.selectedSeason &&
                meta != null &&
                state.seasons.contains(nextSeason)
            if (shouldSwitchSeason) {
                state.copy(
                    nextToWatch = nextToWatch,
                    selectedSeason = nextSeason,
                    episodesForSeason = getEpisodesForSeason(meta.videos, nextSeason)
                )
            } else {
                state.copy(nextToWatch = nextToWatch)
            }
        }
    }

    private fun observeTrailerAutoplaySettings() {
        viewModelScope.launch {
            trailerSettingsDataStore.settings.collectLatest { settings ->
                trailerAutoplayEnabled = settings.enabled
                trailerDelayMs = settings.delaySeconds * 1000L
                if (!settings.enabled) {
                    idleTimerJob?.cancel()
                }
            }
        }
    }

    fun onEvent(event: MetaDetailsEvent) {
        when (event) {
            is MetaDetailsEvent.OnSeasonSelected -> selectSeason(event.season)
            is MetaDetailsEvent.OnEpisodeClick -> { /* Navigate to stream */ }
            is MetaDetailsEvent.OnCommentsModeSelected -> selectCommentsMode(event.mode)
            is MetaDetailsEvent.OnCommentsEpisodeSelected -> selectCommentsEpisode(event.video)
            MetaDetailsEvent.OnPlayClick -> { /* Start playback */ }
            MetaDetailsEvent.OnToggleLibrary -> toggleLibrary()
            MetaDetailsEvent.OnRetry -> loadMeta()
            MetaDetailsEvent.OnRetryComments -> _uiState.value.meta?.let { loadComments(it, forceRefresh = true) }
            MetaDetailsEvent.OnLoadMoreComments -> loadMoreComments()
            is MetaDetailsEvent.OnCommentSelected -> openCommentOverlay(event.review)
            is MetaDetailsEvent.OnAdvanceCommentOverlay -> advanceCommentOverlay(event.direction)
            MetaDetailsEvent.OnDismissCommentOverlay -> dismissCommentOverlay()
            MetaDetailsEvent.OnBackPress -> { /* Handle in screen */ }
            MetaDetailsEvent.OnUserInteraction -> handleUserInteraction()
            MetaDetailsEvent.OnPlayButtonFocused -> handlePlayButtonFocused()
            MetaDetailsEvent.OnTrailerButtonClick -> handleTrailerButtonClick()
            MetaDetailsEvent.OnTrailerEnded -> handleTrailerEnded()
            is MetaDetailsEvent.OnSharedTrailerSelected -> handleSharedTrailerSelected(event.trailer)
            MetaDetailsEvent.OnDismissSharedTrailer -> dismissSharedTrailerOverlay()
            MetaDetailsEvent.OnRetrySharedTrailer -> retrySharedTrailer()
            MetaDetailsEvent.OnToggleMovieWatched -> toggleMovieWatched()
            is MetaDetailsEvent.OnToggleEpisodeWatched -> toggleEpisodeWatched(event.video)
            is MetaDetailsEvent.OnMarkSeasonWatched -> markSeasonWatched(event.season)
            is MetaDetailsEvent.OnMarkSeasonUnwatched -> markSeasonUnwatched(event.season)
            is MetaDetailsEvent.OnMarkPreviousEpisodesWatched -> markPreviousEpisodesWatched(event.video)
            is MetaDetailsEvent.OnMarkPreviousSeasonsWatched -> markPreviousSeasonsWatched(event.season)
            MetaDetailsEvent.OnLibraryLongPress -> openListPicker()
            is MetaDetailsEvent.OnPickerMembershipToggled -> togglePickerMembership(event.listKey)
            MetaDetailsEvent.OnPickerSave -> savePickerMembership()
            MetaDetailsEvent.OnPickerDismiss -> dismissListPicker()
            MetaDetailsEvent.OnRemovalConfirmed -> confirmPickerRemoval()
            MetaDetailsEvent.OnRemovalCancelled -> cancelPickerRemoval()
            MetaDetailsEvent.OnClearMessage -> clearMessage()
            MetaDetailsEvent.OnLifecyclePause -> handleLifecyclePause()
            MetaDetailsEvent.OnLifecycleResume -> handleLifecycleResume()
        }
    }

    private fun observeLibraryState() {
        viewModelScope.launch {
            libraryRepository.sourceMode
                .distinctUntilChanged()
                .collectLatest { sourceMode ->
                    _uiState.update { state ->
                        if (state.librarySourceMode == sourceMode) {
                            state
                        } else {
                            state.copy(librarySourceMode = sourceMode)
                        }
                    }
                }
        }

        viewModelScope.launch {
            libraryRepository.membershipListTabs
                .distinctUntilChanged()
                .collectLatest { tabs ->
                _uiState.update { state ->
                    val selectedMembership = state.pickerMembership
                    val filteredMembership = if (selectedMembership.isEmpty()) {
                        selectedMembership
                    } else {
                        tabs.associate { tab -> tab.key to (selectedMembership[tab.key] == true) }
                    }
                    if (state.libraryListTabs == tabs &&
                        state.pickerMembership == filteredMembership
                    ) {
                        state
                    } else {
                        state.copy(
                            libraryListTabs = tabs,
                            pickerMembership = filteredMembership
                        )
                    }
                }
            }
        }

        // Observe library/watchlist on the *same* (id, type) pair that
        // `toggleLibrary` writes via `meta.toLibraryEntryInput()`. Falling back
        // to navigation (itemId, itemType) until meta loads keeps the button
        // responsive but pre-meta (when toggle is unavailable anyway).
        val canonicalKey = _uiState
            .map { state ->
                val id = state.meta?.id?.takeIf { it.isNotBlank() } ?: itemId
                val type = state.meta?.apiType?.takeIf { it.isNotBlank() } ?: itemType
                id to type
            }
            .distinctUntilChanged()

        viewModelScope.launch {
            canonicalKey
                .flatMapLatest { (id, type) -> libraryRepository.isInLibrary(itemId = id, itemType = type) }
                .distinctUntilChanged()
                .collectLatest { inLibrary ->
                    _uiState.update { state ->
                        if (state.isInLibrary == inLibrary) state else state.copy(isInLibrary = inLibrary)
                    }
                }
        }

        viewModelScope.launch {
            canonicalKey
                .flatMapLatest { (id, type) -> libraryRepository.isInWatchlist(itemId = id, itemType = type) }
                .distinctUntilChanged()
                .collectLatest { inWatchlist ->
                    _uiState.update { state ->
                        if (state.isInWatchlist == inWatchlist) state else state.copy(isInWatchlist = inWatchlist)
                    }
                }
        }
    }

    private fun observeWatchProgress() {
        if (itemType.lowercase() == "movie") return
        viewModelScope.launch {
            _effectiveContentId.flatMapLatest { cid ->
                if (itemType.equals("other", ignoreCase = true)) {
                    // For "other" type, videos lack season/episode.
                    // Build progress map by matching video IDs to their
                    // position in the meta video list.
                    watchProgressRepository.allProgress.map { allProgress ->
                        val meta = _uiState.value.meta
                        val videos = meta?.videos ?: emptyList()
                        val progressByVideoId = allProgress
                            .filter { it.contentId == cid }
                            .associateBy { it.videoId }
                        val result = mutableMapOf<Pair<Int, Int>, WatchProgress>()
                        videos.forEachIndexed { index, video ->
                            val progress = progressByVideoId[video.id]
                            if (progress != null) {
                                // Use synthetic season=1, episode=index+1 as key
                                result[1 to (index + 1)] = progress
                            }
                        }
                        result as Map<Pair<Int, Int>, WatchProgress>
                    }
                } else {
                    watchProgressRepository.getAllEpisodeProgress(cid)
                }
            }
                .distinctUntilChanged()
                .collectLatest { progressMap ->
                _uiState.update { state ->
                    if (state.episodeProgressMap == progressMap) {
                        state
                    } else {
                        state.copy(episodeProgressMap = progressMap)
                    }
                }
                revalidateLocalWatchedEpisodesAgainstActiveProvider(progressMap)
                // Recalculate next to watch when progress changes
                reevaluateSeriesWatchedBadge()
                calculateNextToWatch()
            }
        }
    }

    private fun revalidateLocalWatchedEpisodesAgainstActiveProvider(
        providerProgressMap: Map<Pair<Int, Int>, WatchProgress>
    ) {
        if (itemType.equals("other", ignoreCase = true)) return
        if (itemType.equals("movie", ignoreCase = true)) return
        if (providerProgressMap.isEmpty()) return
        val hasCompletedEntries = providerProgressMap.values.any { it.isCompleted() }
        if (!hasCompletedEntries) return

        viewModelScope.launch(Dispatchers.IO) {
            if (!watchProgressRepository.activeProviderOwnsCompletedHistoryProjection()) return@launch

            val contentId = _effectiveContentId.value
            val localWatched = watchedItemsPreferences
                .getWatchedEpisodesForContent(contentId)
                .first()
            if (localWatched.isEmpty()) return@launch

            val staleEpisodes = localWatched.filter { (season, episode) ->
                val providerEntry = providerProgressMap[season to episode]
                providerEntry == null || !providerEntry.isCompleted()
            }

            if (staleEpisodes.isNotEmpty()) {
                Log.d(TAG, "revalidateWatchedEpisodes: pruning ${staleEpisodes.size} stale entries for $contentId")
                watchedItemsPreferences.unmarkAsWatchedBatch(
                    contentId = contentId,
                    episodes = staleEpisodes.toList()
                )
            }
        }
    }

    private fun observeWatchedEpisodes() {
        if (itemType.lowercase() == "movie") return
        viewModelScope.launch {
            _effectiveContentId.flatMapLatest { cid ->
                combine(
                    watchedItemsPreferences.getWatchedEpisodesForContent(cid),
                    watchProgressRepository.getAllEpisodeProgress(cid),
                    _uiState.map { it.meta?.videos }.distinctUntilChanged(),
                ) { localWatched, progressMap, videos ->
                    val fromProgress = progressMap.filterValues { it.isCompleted() }.keys
                    val merged = (localWatched + fromProgress).toMutableSet()
                    // Remove optimistic unmarks — episodes the user just batch-unmarked
                    // that may still linger in localWatched/fromProgress briefly.
                    merged -= _optimisticUnmarks
                    if (videos.isNullOrEmpty()) return@combine merged
                    for (video in videos) {
                        val s = video.season ?: continue
                        val e = video.episode ?: continue
                        val key = s to e
                        val watchedByVideoId = watchProgressRepository.isWatchedByVideoId(video.id, e)
                        val isWatched = resolveEpisodeWatchedState(
                            currentlyWatched = key in merged,
                            completedByProgress = key in fromProgress,
                            optimisticallyMarked = key in _optimisticMarks,
                            optimisticallyUnmarked = key in _optimisticUnmarks,
                            watchedByVideoId = watchedByVideoId
                        )
                        if (isWatched) merged += key else merged -= key
                    }
                    merged as Set<Pair<Int, Int>>
                }
            }
                .distinctUntilChanged()
                .collectLatest { watchedSet ->
                _uiState.update { state ->
                    if (state.watchedEpisodes == watchedSet) {
                        state
                    } else {
                        state.copy(watchedEpisodes = watchedSet)
                    }
                }
                reevaluateSeriesWatchedBadge()
                calculateNextToWatch()
            }
        }
        // Re-calculate next-to-watch when "furthest episode" preference changes
        viewModelScope.launch {
            layoutPreferenceDataStore.nextUpFromFurthestEpisode
                .collectLatest {
                    calculateNextToWatch()
                }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeMovieWatched() {
        if (itemType.lowercase() != "movie") return
        viewModelScope.launch {
            _effectiveContentId.flatMapLatest { cid ->
                _uiState.map { it.meta?.imdbId?.takeIf { id -> id != cid && id.isNotBlank() } }
                    .distinctUntilChanged()
                    .flatMapLatest { videoId ->
                        watchProgressRepository.isWatched(cid, videoId = videoId)
                    }
            }
                .distinctUntilChanged()
                .collectLatest { watched ->
                _uiState.update { state ->
                    if (state.isMovieWatched == watched) state else state.copy(isMovieWatched = watched)
                }
            }
        }
    }

    private fun observeBlurUnwatchedEpisodes() {
        viewModelScope.launch {
            layoutPreferenceDataStore.blurUnwatchedEpisodes
                .distinctUntilChanged()
                .collectLatest { enabled ->
                _uiState.update { state ->
                    if (state.blurUnwatchedEpisodes == enabled) state else state.copy(blurUnwatchedEpisodes = enabled)
                }
                }
        }
    }

    private fun observeEpisodeOptionsOverlayStyle() {
        viewModelScope.launch {
            layoutPreferenceDataStore.episodeOptionsOverlayStyle
                .distinctUntilChanged()
                .collectLatest { style ->
                    _uiState.update { state ->
                        if (state.episodeOptionsOverlayStyle == style) {
                            state
                        } else {
                            state.copy(episodeOptionsOverlayStyle = style)
                        }
                    }
                }
        }
    }

    private fun observeDetailImdbRatingsVisibility() {
        viewModelScope.launch {
            layoutPreferenceDataStore.detailImdbRatingsVisibility
                .distinctUntilChanged()
                .collectLatest { visibility ->
                    _uiState.update { state ->
                        if (state.detailImdbRatingsVisibility == visibility) {
                            state
                        } else {
                            state.copy(detailImdbRatingsVisibility = visibility)
                        }
                    }
                }
        }
    }

    private fun observeOverallRatingsVisibility() {
        viewModelScope.launch {
            layoutPreferenceDataStore.homeImdbRatingsVisibility
                .distinctUntilChanged()
                .collectLatest { visibility ->
                    _uiState.update { state ->
                        if (state.overallRatingsVisibility == visibility) {
                            state
                        } else {
                            state.copy(overallRatingsVisibility = visibility)
                        }
                    }
                }
        }
    }

    private fun observeRelatedWatchedStatus() {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                watchProgressRepository.observeWatchedMovieIds(),
                watchedSeriesStateHolder.fullyWatchedSeriesIds
            ) { movieIds, seriesIds ->
                buildMap {
                    movieIds.forEach { id -> put("${id}|movie", true) }
                    seriesIds.forEach { id -> put("${id}|series", true) }
                }
            }.distinctUntilChanged().collect { status ->
                _uiState.update { state ->
                    if (state.relatedWatchedStatus == status) state else state.copy(relatedWatchedStatus = status)
                }
            }
        }
    }

    private fun observeShowFullReleaseDate() {
        viewModelScope.launch {
            layoutPreferenceDataStore.showFullReleaseDate
                .distinctUntilChanged()
                .collectLatest { enabled ->
                _uiState.update { state ->
                    if (state.showFullReleaseDate == enabled) state else state.copy(showFullReleaseDate = enabled)
                }
            }
        }
    }

    private fun loadMeta() {
        viewModelScope.launch {
            // Metadata timing instrument. Grep anchor: MetaTiming. metaLoadStartMs
            // is read in applyMetaWithEnrichment (always the next step on the
            // meta) to measure addon-meta-ready, enrich and total text-ready
            // latency. Logging only.
            metaLoadStartMs = android.os.SystemClock.elapsedRealtime()
            cancelCommentsRequests()
            val mdbListSettings = mdbListSettingsDataStore.settings.first()
            val isMdbListActive = mdbListSettings.enabled && mdbListSettings.apiKey.isNotBlank()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    episodeImdbRatings = emptyMap(),
                    isEpisodeRatingsLoading = false,
                    episodeRatingsError = null,
                    mdbListRatings = null,
                    isMdbListRatingsActive = isMdbListActive,
                    tmdbRating = null,
                    moreLikeThis = emptyList(),
                    moreLikeThisSource = null,
                    collection = emptyList(),
                    collectionName = null,
                    comments = emptyList(),
                    commentsCurrentPage = 0,
                    commentsPageCount = 0,
                    isCommentsLoading = false,
                    isCommentsLoadingMore = false,
                    commentsError = null,
                    shouldShowCommentsSection = false,
                    commentsMode = CommentsMode.TITLE,
                    commentsEpisodeTarget = null,
                    selectedComment = null,
                    isSharedTrailerOverlayVisible = false,
                    isSharedTrailerLoading = false,
                    sharedTrailerUrl = null,
                    sharedTrailerAudioUrl = null,
                    sharedTrailerErrorMessage = null,
                    selectedSharedTrailer = null
                )
            }

            val metaLookupId = resolveMetaLookupId(itemId = itemId, itemType = itemType)
            // Update effective content ID as early as possible so watch-progress
            // observers use the canonical (usually IMDB) ID, not the navigation ID.
            if (metaLookupId != itemId) {
                _effectiveContentId.value = metaLookupId
            }
            val preferExternal = layoutPreferenceDataStore.preferExternalMetaAddonDetail.first()

            if (preferExternal) {
                // 1) Try meta addons first
                metaRepository.getMetaFromAllAddons(type = itemType, id = metaLookupId).collect { result ->
                    when (result) {
                        is NetworkResult.Success -> {
                            applyMetaWithEnrichment(result.data)
                        }
                        is NetworkResult.Error -> {
                            // 2) Fallback: try originating addon if meta addons failed
                            val preferred = preferredAddonBaseUrl?.takeIf { it.isNotBlank() }
                            val preferredMeta: Meta? = preferred?.let { baseUrl ->
                                when (val fallbackResult = metaRepository.getMeta(addonBaseUrl = baseUrl, type = itemType, id = metaLookupId)
                                    .first { it !is NetworkResult.Loading }) {
                                    is NetworkResult.Success -> fallbackResult.data
                                    else -> null
                                }
                            }

                            if (preferredMeta != null) {
                                applyMetaWithEnrichment(preferredMeta)
                            } else if (tryApplyTmdbFallbackMeta()) {
                                Unit
                            } else {
                                val errorMsg = buildMetaLoadErrorMessage(result.message, metaLookupId)
                                _uiState.update { it.copy(isLoading = false, error = errorMsg) }
                            }
                        }
                        NetworkResult.Loading -> {
                            _uiState.update { it.copy(isLoading = true) }
                        }
                    }
                }
            } else {
                // Original: prefer catalog addon
                val preferred = preferredAddonBaseUrl?.takeIf { it.isNotBlank() }
                val preferredMeta: Meta? = preferred?.let { baseUrl ->
                    when (val result = metaRepository.getMeta(addonBaseUrl = baseUrl, type = itemType, id = metaLookupId)
                        .first { it !is NetworkResult.Loading }) {
                        is NetworkResult.Success -> result.data
                        else -> null
                    }
                }

                if (preferredMeta != null) {
                    applyMetaWithEnrichment(preferredMeta)
                } else {
                    metaRepository.getMetaFromAllAddons(type = itemType, id = metaLookupId).collect { result ->
                        when (result) {
                            is NetworkResult.Success -> applyMetaWithEnrichment(result.data)
                            is NetworkResult.Error -> {
                                if (!tryApplyTmdbFallbackMeta()) {
                                    val errorMsg = buildMetaLoadErrorMessage(result.message, metaLookupId)
                                    _uiState.update { it.copy(isLoading = false, error = errorMsg) }
                                }
                            }
                            NetworkResult.Loading -> {
                                _uiState.update { it.copy(isLoading = true) }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun tryApplyTmdbFallbackMeta(): Boolean {
        val tmdbId = itemId
            .takeIf { it.startsWith("tmdb:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.substringBefore(':')
            ?.toIntOrNull()
            ?: return false
        val type = ContentType.fromString(itemType)
        val settings = tmdbSettingsDataStore.settings.first()
        val enrichment = tmdbMetadataService.fetchEnrichment(
            tmdbId = tmdbId.toString(),
            contentType = type,
            language = settings.language
        ) ?: return false
        val meta = Meta(
            id = itemId,
            type = type,
            rawType = itemType,
            name = enrichment.localizedTitle ?: enrichment.originalTitle
                ?: context.getString(R.string.detail_tmdb_fallback_title, tmdbId),
            poster = enrichment.poster,
            posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
            background = enrichment.backdrop,
            logo = enrichment.logo,
            description = enrichment.description,
            releaseInfo = enrichment.releaseInfo,
            status = enrichment.status,
            imdbRating = enrichment.rating?.toFloat(),
            genres = enrichment.genres,
            runtime = enrichment.runtimeMinutes?.toString(),
            director = enrichment.director,
            writer = enrichment.writer,
            cast = enrichment.castMembers.map { it.name },
            castMembers = enrichment.castMembers,
            videos = emptyList(),
            productionCompanies = enrichment.productionCompanies,
            networks = enrichment.networks,
            ageRating = enrichment.ageRating,
            country = enrichment.countries?.joinToString(", "),
            awards = null,
            language = enrichment.language,
            links = emptyList(),
            // Honor the "Disable Trailers in TMDB Enrichment" toggle even on
            // this synthetic fallback meta (issue #1647). The main enrichment
            // merge at the bottom of applyMetaWithEnrichment already gates on
            // settings.useTrailers; without the same gate here, the fallback
            // path would smuggle TMDB trailers in unconditionally.
            trailers = if (settings.useTrailers) enrichment.trailers else emptyList()
        )
        applyMetaWithEnrichment(meta)
        return true
    }

    private suspend fun resolveMetaLookupId(itemId: String, itemType: String): String {
        val raw = itemId.trim()
        if (!raw.startsWith("tmdb:", ignoreCase = true)) return raw

        val tmdbNumericId = raw
            .substringAfter(':', missingDelimiterValue = "")
            .substringBefore(':')
            .toIntOrNull()
            ?: return raw

        // Use a short timeout so a blocked TMDB API doesn't stall the detail screen.
        return kotlinx.coroutines.withTimeoutOrNull(5_000L) {
            tmdbService.tmdbToImdb(tmdbNumericId, itemType)
        }
            ?.takeIf { it.isNotBlank() }
            ?: raw
    }

    private fun buildMetaLoadErrorMessage(originalMessage: String?, lookupId: String): String {
        val base = originalMessage ?: "Failed to load metadata"
        return "$base\n\nID: $lookupId"
    }

    /**
     * Render-raw-first (0.8.4-nt1): publishes meta to the UI state only — the
     * part that makes the details screen appear. Split out of applyMeta so the
     * raw addon meta can be painted immediately, before the ~900ms TMDB
     * enrichment, then re-published (with backdrop/logo/etc.) on the enriched
     * pass. Side effects run once, on the enriched applyMeta below.
     */
    private fun publishMetaToUi(meta: Meta) {
        // Update the effective content ID so watch-progress observers pick up
        // the canonical ID (e.g. IMDB "tt0396375") instead of the navigation ID
        // (which may be "tmdb:13836").  Don't downgrade from an IMDB ID to a
        // less canonical one (e.g. tmdb:) — Trakt stores progress under IMDB.
        if (meta.id.isNotBlank() && meta.id != itemId) {
            val currentIsImdb = _effectiveContentId.value.startsWith("tt")
            val newIsImdb = meta.id.startsWith("tt")
            if (!currentIsImdb || newIsImdb) {
                _effectiveContentId.value = meta.id
            }
        }

        val seasons = meta.videos
            .mapNotNull { it.season }
            .distinct()
            .sorted()
            .ifEmpty {
                // For "other" type content videos lack season/episode numbers.  
                // Treat them as a single virtual season so the episodes UI can display them.
                if (meta.videos.isNotEmpty()) listOf(1) else emptyList()
            }

        val defaultEpisodeSeason = findPreferredDefaultEpisode(meta)?.season
        // Prefer addon-specified default episode season, otherwise first regular season (> 0), fallback to season 0 (specials)
        val selectedSeason = defaultEpisodeSeason
            ?.takeIf { it in seasons }
            ?: seasons.firstOrNull { it > 0 }
            ?: seasons.firstOrNull()
            ?: 1
        val episodesForSeason = getEpisodesForSeason(meta.videos, selectedSeason)

        _uiState.update {
            // If nextToWatch already set a season (from pre-computed remap), prefer it
            // over the default season selection.
            val effectiveSeason = it.nextToWatch?.nextSeason
                ?.takeIf { s -> s in seasons }
                ?: selectedSeason
            val effectiveEpisodes = if (effectiveSeason != selectedSeason) {
                getEpisodesForSeason(meta.videos, effectiveSeason)
            } else {
                episodesForSeason
            }
            it.copy(
                isLoading = false,
                meta = meta,
                seasons = seasons,
                selectedSeason = effectiveSeason,
                episodesForSeason = effectiveEpisodes,
                error = null,
                commentsEpisodeTarget = null,
                shouldShowCommentsSection = traktCommentsEnabled && traktAuthenticated && supportsComments(meta)
            )
        }

    }

    private fun applyMeta(meta: Meta) {
        publishMetaToUi(meta)
        // Calculate next to watch after meta is loaded
        reevaluateSeriesWatchedBadge()
        calculateNextToWatch()

        // Start fetching trailer after meta is loaded
        fetchTrailerUrl()

        if (traktCommentsEnabled && traktAuthenticated && supportsComments(meta)) {
            loadComments(meta)
        }
    }

    /** Metadata timing instrument (grep anchor: MetaTiming). Set in loadMeta. */
    @Volatile
    private var metaLoadStartMs: Long = 0L

    private suspend fun applyMetaWithEnrichment(meta: Meta) {
        // MetaTiming: addon meta is in hand here (cache hit or network). The
        // synopsis/cast the addon already returned are not shown until enrichMeta
        // below returns, so this line and META_APPLY bracket that gate.
        android.util.Log.i(
            "MetaTiming",
            "META_FETCH ms=${android.os.SystemClock.elapsedRealtime() - metaLoadStartMs} id=${meta.id}"
        )
        // nt4: fire the stream prefetch off the RAW meta NOW, before the ~1.7s
        // enrichMeta await below. The play target is already determined here (a
        // movie's id, or the series nextToWatch - air-date-independent, computed
        // from the addon episode list that enrichment does not change), so this
        // is the same target the post-applyMeta observer would compute, ~1.7s
        // sooner - giving the prewarm that much more head start to complete
        // before the press. The observer's later fire dedups on
        // lastStreamPrefetchKey, so this does not double-scrape.
        launchEarlyStreamPrefetch(meta)
        // Fire all independent async jobs immediately — they run in parallel.
        loadMoreLikeThisAsync(meta)
        // Render-raw-first (0.8.4-nt1): paint the already-warmed base meta NOW so
        // the details screen appears immediately, instead of blanking until the
        // enrichMeta await below (~900ms cold). The enriched applyMeta re-publishes
        // with TMDB backdrop/logo/etc. and runs the side effects once.
        publishMetaToUi(meta)
        val enrichT0 = android.os.SystemClock.elapsedRealtime()
        val enriched = enrichMeta(meta)
        android.util.Log.i(
            "MetaTiming",
            "META_ENRICH ms=${android.os.SystemClock.elapsedRealtime() - enrichT0} " +
                "thread=${Thread.currentThread().name}"
        )

        // Pre-compute nextToWatch before applyMeta so the PlayButton text is stable
        // from the first composition — prevents focus invalidation from late recomposition.
        val progressMap = watchProgressRepository
            .getAllEpisodeProgress(_effectiveContentId.value)
            .first()
        val watchedEpisodes = watchedItemsPreferences
            .getWatchedEpisodesForContent(_effectiveContentId.value)
            .first()
        val precomputedNextToWatch = computeNextToWatch(enriched, progressMap, watchedEpisodes)
        updateNextToWatch(precomputedNextToWatch)

        android.util.Log.i(
            "MetaTiming",
            "META_APPLY total_ms=${android.os.SystemClock.elapsedRealtime() - metaLoadStartMs}"
        )
        viewModelScope.launch {
            healthStore.record(
                AddonHealthStore.METADATA_KEY,
                HealthOutcome.SUCCESS,
                android.os.SystemClock.elapsedRealtime() - metaLoadStartMs
            )
        }
        applyMeta(enriched)
        // 0.8.4 merge (#3 remote-progress correctness): the local read above can
        // land before the remote provider (Simkl/Trakt) has finished its initial
        // sync, seeding an empty progress map and a wrong "Play S1E1" instead of
        // "Resume". First paint stays fast (above); once remote signals loaded we
        // re-read, bounded, and refine. updateNextToWatch no-ops when the value is
        // unchanged, so this is free on the common local-only path.
        viewModelScope.launch {
            val cid = _effectiveContentId.value
            watchProgressRepository.observeRemoteProgressLoaded().first { it }
            val refreshedProgress = withTimeoutOrNull(150L) {
                watchProgressRepository.getAllEpisodeProgress(cid).first { it.isNotEmpty() }
            } ?: watchProgressRepository.getAllEpisodeProgress(cid).first()
            val refreshedWatched = watchedItemsPreferences
                .getWatchedEpisodesForContent(cid)
                .first()
            val refined = computeNextToWatch(enriched, refreshedProgress, refreshedWatched)
            updateNextToWatch(refined)
        }
        // Episode ratings and MDBList are independent — launch both without waiting.
        loadEpisodeRatingsAsync(enriched)
        viewModelScope.launch { loadMDBListRatings(enriched) }
    }

    private fun loadComments(meta: Meta, forceRefresh: Boolean = false) {
        if (!traktCommentsEnabled || !traktAuthenticated || !supportsComments(meta)) {
            cancelCommentsRequests()
            _uiState.update { state ->
                state.copy(
                    comments = emptyList(),
                    commentsCurrentPage = 0,
                    commentsPageCount = 0,
                    isCommentsLoading = false,
                    isCommentsLoadingMore = false,
                    commentsError = null,
                    shouldShowCommentsSection = false,
                    commentsMode = CommentsMode.TITLE,
                    commentsEpisodeTarget = null,
                    selectedComment = null
                )
            }
            return
        }

        commentsJob?.cancel()
        commentsLoadMoreJob?.cancel()
        commentsJob = viewModelScope.launch {
            _uiState.update { state ->
                if (state.meta == null || state.meta.id != meta.id) {
                    state
                } else {
                    state.copy(
                        comments = emptyList(),
                        commentsCurrentPage = 0,
                        commentsPageCount = 0,
                        isCommentsLoading = true,
                        isCommentsLoadingMore = false,
                        commentsError = null,
                        shouldShowCommentsSection = true,
                        selectedComment = if (forceRefresh) null else state.selectedComment
                    )
                }
            }

            try {
                val page = traktCommentsService.getCommentsPage(
                    meta = meta,
                    fallbackItemId = itemId,
                    fallbackItemType = itemType,
                    targetEpisode = currentCommentsEpisodeTarget(meta),
                    page = 1,
                    forceRefresh = forceRefresh
                )

                _uiState.update { state ->
                    if (state.meta == null || state.meta.id != meta.id) {
                        state
                    } else {
                        state.copy(
                            comments = page.items,
                            commentsCurrentPage = page.currentPage,
                            commentsPageCount = page.pageCount,
                            isCommentsLoading = false,
                            isCommentsLoadingMore = false,
                            commentsError = null,
                            shouldShowCommentsSection = true,
                            selectedComment = state.selectedComment?.let { selected ->
                                page.items.firstOrNull { it.id == selected.id }
                            }
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Failed to load Trakt comments for ${meta.id}: ${error.message}")
                _uiState.update { state ->
                    if (state.meta == null || state.meta.id != meta.id) {
                        state
                    } else {
                        state.copy(
                            comments = emptyList(),
                            commentsCurrentPage = 0,
                            commentsPageCount = 0,
                            isCommentsLoading = false,
                            isCommentsLoadingMore = false,
                            commentsError = localizedContext.getString(R.string.detail_comments_error),
                            shouldShowCommentsSection = true
                        )
                    }
                }
            }
        }
    }

    private fun supportsComments(meta: Meta?): Boolean {
        if (meta == null) return false
        return when (meta.type) {
            ContentType.MOVIE -> true
            ContentType.SERIES, ContentType.TV -> true
            else -> meta.apiType in listOf("movie", "series", "tv", "show")
        }
    }

    private fun loadMoreComments(selectNextAfterLoad: Boolean = false) {
        val state = _uiState.value
        val meta = state.meta ?: return
        if (!traktCommentsEnabled || !traktAuthenticated || !supportsComments(meta)) return
        if (state.isCommentsLoading || state.isCommentsLoadingMore || state.commentsCurrentPage == 0) return
        if (state.commentsPageCount > 0 && state.commentsCurrentPage >= state.commentsPageCount) return

        val nextPage = state.commentsCurrentPage + 1
        val currentLastCommentId = state.comments.lastOrNull()?.id
        val selectedCommentId = state.selectedComment?.id

        commentsLoadMoreJob?.cancel()
        commentsLoadMoreJob = viewModelScope.launch {
            _uiState.update { current ->
                if (current.meta?.id != meta.id) current else current.copy(isCommentsLoadingMore = true)
            }

            try {
                val page = traktCommentsService.getCommentsPage(
                    meta = meta,
                    fallbackItemId = itemId,
                    fallbackItemType = itemType,
                    targetEpisode = currentCommentsEpisodeTarget(meta),
                    page = nextPage
                )

                _uiState.update { current ->
                    if (current.meta?.id != meta.id) {
                        current
                    } else {
                        val appended = page.items.filterNot { fetched ->
                            current.comments.any { existing -> existing.id == fetched.id }
                        }
                        val updatedComments = current.comments + appended
                        val shouldAdvanceSelection =
                            selectNextAfterLoad &&
                                current.selectedComment?.id == selectedCommentId &&
                                current.selectedComment?.id == currentLastCommentId &&
                                appended.isNotEmpty()

                        current.copy(
                            comments = updatedComments,
                            commentsCurrentPage = maxOf(current.commentsCurrentPage, page.currentPage),
                            commentsPageCount = maxOf(current.commentsPageCount, page.pageCount),
                            isCommentsLoadingMore = false,
                            commentsError = null,
                            selectedComment = if (shouldAdvanceSelection) appended.first() else current.selectedComment
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Failed to load more Trakt comments for ${meta.id}: ${error.message}")
                _uiState.update { current ->
                    if (current.meta?.id != meta.id) current else current.copy(isCommentsLoadingMore = false)
                }
            }
        }
    }

    private fun openCommentOverlay(review: TraktCommentReview) {
        _uiState.update { state ->
            state.copy(selectedComment = review)
        }
    }

    private fun advanceCommentOverlay(direction: Int) {
        if (direction == 0) return
        val state = _uiState.value
        val selected = state.selectedComment ?: return
        val selectedIndex = state.comments.indexOfFirst { it.id == selected.id }
        if (selectedIndex < 0) return

        val targetIndex = selectedIndex + direction
        if (targetIndex in state.comments.indices) {
            _uiState.update { current ->
                if (current.selectedComment?.id != selected.id) {
                    current
                } else {
                    current.copy(selectedComment = current.comments.getOrNull(targetIndex) ?: current.selectedComment)
                }
            }
            return
        }

        if (direction > 0) {
            loadMoreComments(selectNextAfterLoad = true)
        }
    }

    private fun dismissCommentOverlay() {
        _uiState.update { state ->
            state.copy(selectedComment = null)
        }
    }

    private fun cancelCommentsRequests() {
        commentsJob?.cancel()
        commentsLoadMoreJob?.cancel()
    }

    private fun loadMoreLikeThisAsync(meta: Meta) {
        moreLikeThisJob?.cancel()
        moreLikeThisJob = viewModelScope.launch {
            val source = if (shouldLoadTraktMoreLikeThis(meta)) {
                MoreLikeThisSource.TRAKT
            } else {
                val settings = tmdbSettingsDataStore.settings.first()
                if (!shouldLoadMoreLikeThis(settings)) {
                    _uiState.update { it.copy(moreLikeThis = emptyList(), moreLikeThisSource = null) }
                    return@launch
                }
                MoreLikeThisSource.TMDB
            }

            val rawRecommendations = when (source) {
                MoreLikeThisSource.TRAKT -> {
                    runCatching {
                        traktRelatedService.getRelated(
                            meta = meta,
                            fallbackItemId = itemId,
                            fallbackItemType = itemType
                        )
                    }.getOrElse {
                        Log.w(TAG, "Failed to load Trakt related titles for ${meta.id}: ${it.message}")
                        emptyList()
                    }
                }

                MoreLikeThisSource.TMDB -> {
                    val settings = tmdbSettingsDataStore.settings.first()
                    val tmdbContentType = resolveTmdbContentType(meta)
                    val tmdbLookupType = tmdbContentType.toApiString()
                    val tmdbId = tmdbService.ensureTmdbId(meta.id, tmdbLookupType)
                        ?: tmdbService.ensureTmdbId(itemId, itemType)
                    if (tmdbId.isNullOrBlank()) {
                        _uiState.update { it.copy(moreLikeThis = emptyList(), moreLikeThisSource = null) }
                        return@launch
                    }

                    runCatching {
                        tmdbMetadataService.fetchMoreLikeThis(
                            tmdbId = tmdbId,
                            contentType = tmdbContentType,
                            language = settings.language
                        )
                    }.getOrElse {
                        Log.w(TAG, "Failed to load More like this for ${meta.id}: ${it.message}")
                        emptyList()
                    }
                }
            }

            val recommendations = if (hideUnreleasedContent) {
                val today = LocalDate.now()
                rawRecommendations.filterNot { it.isUnreleased(today) }
            } else {
                rawRecommendations
            }

            _uiState.update { state ->
                if (state.meta == null || state.meta.id == meta.id) {
                    state.copy(
                        moreLikeThis = recommendations,
                        moreLikeThisSource = source.takeIf { recommendations.isNotEmpty() }
                    )
                } else {
                    state
                }
            }
        }
    }

    private fun shouldLoadMoreLikeThis(settings: TmdbSettings): Boolean {
        return settings.enabled && settings.useMoreLikeThis
    }

    private fun shouldLoadTraktMoreLikeThis(meta: Meta): Boolean {
        if (!traktAuthenticated) return false
        if (moreLikeThisSourcePreference == com.nuvio.tv.data.local.MoreLikeThisSourcePreference.TMDB) return false
        return when (meta.type) {
            ContentType.MOVIE -> true
            ContentType.SERIES, ContentType.TV -> true
            else -> meta.apiType in listOf("movie", "series", "tv", "show")
        }
    }

    private fun loadCollectionAsync(collectionId: Int, collectionName: String?, settings: TmdbSettings) {
        collectionJob?.cancel()
        collectionJob = viewModelScope.launch {
            if (!settings.enabled || !settings.useCollections) {
                _uiState.update { it.copy(collection = emptyList(), collectionName = null) }
                return@launch
            }

            val collection = runCatching {
                tmdbMetadataService.fetchMovieCollection(
                    collectionId = collectionId,
                    language = settings.language
                )
            }.getOrElse {
                Log.w(TAG, "Failed to load collection $collectionId: ${it.message}")
                TmdbMovieCollection(name = null, items = emptyList())
            }

            val filteredItems = if (hideUnreleasedContent) {
                val today = LocalDate.now()
                collection.items.filterNot { it.isUnreleased(today) }
            } else {
                collection.items
            }

            _uiState.update { state ->
                state.copy(
                    collection = filteredItems,
                    collectionName = collection.name ?: collectionName
                )
            }
        }
    }

    private suspend fun loadMDBListRatings(meta: Meta) {
        val settings = mdbListSettingsDataStore.settings.first()
        val isMdbListActive = settings.enabled && settings.apiKey.isNotBlank()
        val ratingsResult = runCatching {
            mdbListRepository.getRatingsForMeta(
                meta = meta,
                fallbackItemId = itemId,
                fallbackItemType = itemType
            )
        }.getOrNull()

        _uiState.update { state ->
            state.copy(
                mdbListRatings = ratingsResult?.ratings,
                isMdbListRatingsActive = isMdbListActive
            )
        }
    }

    private fun loadEpisodeRatingsAsync(meta: Meta) {
        episodeRatingsJob?.cancel()

        val isSeries = meta.type == ContentType.SERIES || meta.type == ContentType.TV || meta.apiType in listOf("series", "tv")
        if (!isSeries) {
            _uiState.update {
                it.copy(
                    episodeImdbRatings = emptyMap(),
                    isEpisodeRatingsLoading = false,
                    episodeRatingsError = null
                )
            }
            return
        }

        episodeRatingsJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    episodeImdbRatings = emptyMap(),
                    isEpisodeRatingsLoading = true,
                    episodeRatingsError = null
                )
            }

            // Ratings the addon supplied on meta.videos[].rating. The repository below
            // still wins wherever it has an entry.
            val addonRatings: Map<Pair<Int, Int>, Double> = meta.videos.mapNotNull { video ->
                val season = video.season ?: return@mapNotNull null
                val episode = video.episode ?: return@mapNotNull null
                val rating = video.rating ?: return@mapNotNull null
                (season to episode) to rating
            }.toMap()

            try {
                val tmdbContentType = resolveTmdbContentType(meta)
                if (tmdbContentType !in listOf(ContentType.SERIES, ContentType.TV)) {
                    _uiState.update {
                        it.copy(
                            episodeImdbRatings = addonRatings,
                            isEpisodeRatingsLoading = false,
                            episodeRatingsError = null
                        )
                    }
                    return@launch
                }

                val tmdbLookupType = tmdbContentType.toApiString()
                val tmdbIdString = tmdbService.ensureTmdbId(meta.id, tmdbLookupType)
                    ?: tmdbService.ensureTmdbId(itemId, itemType)
                val tmdbId = tmdbIdString?.toIntOrNull()
                val imdbId = extractImdbId(meta.id) ?: extractImdbId(itemId)

                if (tmdbId == null && imdbId == null) {
                    _uiState.update { state ->
                        if (state.meta == null || state.meta.id != meta.id) {
                            state
                        } else {
                            state.copy(
                                episodeImdbRatings = addonRatings,
                                isEpisodeRatingsLoading = false,
                                episodeRatingsError = if (addonRatings.isEmpty()) {
                                    localizedContext.getString(R.string.ratings_unavailable)
                                } else {
                                    null
                                }
                            )
                        }
                    }
                    return@launch
                }

                val ratings = imdbEpisodeRatingsRepository.getEpisodeRatings(
                    imdbId = imdbId,
                    tmdbId = tmdbId
                )

                _uiState.update { state ->
                    if (state.meta == null || state.meta.id != meta.id) {
                        state
                    } else {
                        state.copy(
                            episodeImdbRatings = addonRatings + ratings,
                            isEpisodeRatingsLoading = false,
                            episodeRatingsError = null
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Failed to load episode ratings for ${meta.id}: ${error.message}")
                _uiState.update { state ->
                    if (state.meta == null || state.meta.id != meta.id) {
                        state
                    } else {
                        state.copy(
                            episodeImdbRatings = addonRatings,
                            isEpisodeRatingsLoading = false,
                            episodeRatingsError = if (addonRatings.isEmpty()) {
                                localizedContext.getString(R.string.ratings_load_error)
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }

    private suspend fun enrichMeta(meta: Meta): Meta {
        val settings = tmdbSettingsDataStore.settings.first()
        if (!settings.enabled) return meta

        val tmdbContentType = resolveTmdbContentType(meta)
        val tmdbLookupType = tmdbContentType.toApiString()
        val tmdbId = tmdbService.ensureTmdbId(meta.id, tmdbLookupType)
            ?: tmdbService.ensureTmdbId(itemId, itemType)
            ?: return meta

        val isSeries = meta.apiType in listOf("series", "tv")
        val needsEpisodes = (settings.useEpisodes || settings.useReleaseDates) && isSeries

        // Fetch the main TMDB enrichment on the render path; episode enrichment
        // is deferred to the background (see below).
        val tmdbT0 = android.os.SystemClock.elapsedRealtime()
        val enrichment = withContext(Dispatchers.IO) {
            tmdbMetadataService.fetchEnrichment(
                tmdbId = tmdbId,
                contentType = tmdbContentType,
                language = settings.language
            )
        }
        // M1: only the main enrichment (synopsis/cast/backdrop/logo/genres/...)
        // gates the render now. Episode enrichment - the slow, series-only part,
        // measured up to ~7s in the field - is fetched in the background and
        // merged into the already-published meta, so the details text no longer
        // waits on it. The merge changes only per-episode fields on the SAME
        // video list, and nextToWatch does not depend on it, so the hero button
        // and focus are unaffected.
        android.util.Log.i(
            "MetaTiming",
            "META_TMDB main_ms=${android.os.SystemClock.elapsedRealtime() - tmdbT0} " +
                "episodes=$needsEpisodes"
        )
        if (needsEpisodes) {
            launchEpisodeEnrichmentMerge(tmdbId, settings, meta.videos)
        }

        var updated = meta

        if (enrichment != null && settings.useArtwork) {
            updated = updated.copy(
                background = enrichment.backdrop ?: updated.background,
                logo = enrichment.logo ?: updated.logo
            )
        }

        if (enrichment != null && settings.useBasicInfo) {
            updated = updated.copy(
                name = enrichment.localizedTitle ?: updated.name,
                description = enrichment.description ?: updated.description
            )
            if (enrichment.genres.isNotEmpty()) {
                updated = updated.copy(genres = enrichment.genres)
            }
        }

        // Store TMDB rating separately so it can be shown with its own icon on the details screen.
        if (enrichment?.rating != null && settings.useBasicInfo) {
            _uiState.update { it.copy(tmdbRating = enrichment.rating.toFloat()) }
        }

        if (enrichment != null && settings.useDetails) {
            updated = updated.copy(
                runtime = enrichment.runtimeMinutes?.toString() ?: updated.runtime,
                status = enrichment.status ?: updated.status,
                ageRating = enrichment.ageRating ?: updated.ageRating,
                country = enrichment.countries?.joinToString(", ") ?: updated.country,
                language = enrichment.language ?: updated.language
            )
        }

        if (enrichment != null && settings.useReleaseDates) {
            updated = updated.copy(
                releaseInfo = enrichment.releaseInfo ?: updated.releaseInfo
            )
        }

        if (enrichment != null && settings.useCredits) {
            val peopleCredits = buildList {
                addAll(enrichment.directorMembers)
                addAll(enrichment.writerMembers)
                addAll(enrichment.castMembers)
            }
                .filter { it.name.isNotBlank() }
                .distinctBy { it.tmdbId ?: (it.name.lowercase() + "|" + (it.character ?: "")) }

            if (peopleCredits.isNotEmpty()) {
                updated = updated.copy(
                    castMembers = peopleCredits,
                    cast = enrichment.castMembers.takeIf { it.isNotEmpty() }?.map { it.name } ?: updated.cast
                )
            }
            updated = updated.copy(
                director = if (enrichment.director.isNotEmpty()) enrichment.director else updated.director,
                writer = if (enrichment.writer.isNotEmpty()) enrichment.writer else updated.writer
            )
        }

        if (enrichment != null && settings.useProductions && enrichment.productionCompanies.isNotEmpty()) {
            updated = updated.copy(productionCompanies = enrichment.productionCompanies)
        }

        if (enrichment != null && settings.useNetworks && enrichment.networks.isNotEmpty()) {
            updated = updated.copy(networks = enrichment.networks)
        }

        if (enrichment != null && settings.useTrailers && enrichment.trailers.isNotEmpty()) {
            val mergedTrailers = mergeTrailers(
                existing = updated.trailers,
                incoming = enrichment.trailers
            )
            if (mergedTrailers.isNotEmpty()) {
                updated = updated.copy(
                    trailers = mergedTrailers,
                    trailerYtIds = mergedTrailers.mapNotNull { it.ytId }.distinct()
                )
            }
        }

        if (enrichment?.collectionId != null) {
            loadCollectionAsync(enrichment.collectionId, enrichment.collectionName, settings)
        }

        return updated
    }

    /**
     * M1: episode enrichment (TMDB per-episode titles/overviews/air-dates/
     * stills/runtimes) is the slow, series-only part of TMDB enrichment - up to
     * ~7s in the field (10 Aug 2026 capture: META_TMDB total_ms=8833 vs
     * main_ms=1745). It does not change the episode SET, and neither the
     * synopsis, cast, nor nextToWatch depend on it, so it is fetched off the
     * initial render path and merged into the already-published meta when it
     * lands. Details text and the hero button now render at main-enrichment
     * speed; per-episode titles and stills fill in shortly after.
     */
    private fun launchEpisodeEnrichmentMerge(
        tmdbId: String,
        settings: TmdbSettings,
        baseVideos: List<Video>
    ) {
        viewModelScope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val episodeMap = try {
                withContext(Dispatchers.IO) {
                    tmdbMetadataService.fetchEpisodeEnrichment(
                        tmdbId = tmdbId,
                        seasonNumbers = baseVideos.mapNotNull { it.season }.distinct(),
                        language = settings.language
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
            android.util.Log.i(
                "MetaTiming",
                "META_EPISODES ms=${android.os.SystemClock.elapsedRealtime() - t0} count=${episodeMap.size}"
            )
            if (episodeMap.isEmpty()) return@launch
            _uiState.update { state ->
                // applyMeta publishes meta microseconds after enrichMeta returns,
                // seconds before this fetch completes, so meta is set here. If it
                // somehow is not, skip rather than resurrect a stale meta.
                val current = state.meta ?: return@update state
                val enrichedVideos = enrichVideosWithEpisodes(current.videos, episodeMap, settings)
                state.copy(
                    meta = current.copy(videos = enrichedVideos),
                    episodesForSeason = getEpisodesForSeason(enrichedVideos, state.selectedSeason)
                )
            }
        }
    }

    private fun enrichVideosWithEpisodes(
        videos: List<Video>,
        episodeMap: Map<Pair<Int, Int>, com.nuvio.tv.core.tmdb.TmdbEpisodeEnrichment>,
        settings: TmdbSettings
    ): List<Video> = videos.map { video ->
        val key = if (video.season != null && video.episode != null) video.season to video.episode else null
        val ep = key?.let { episodeMap[it] }
        video.copy(
            title = if (settings.useEpisodes) ep?.title ?: video.title else video.title,
            overview = if (settings.useEpisodes) ep?.overview ?: video.overview else video.overview,
            released = selectEpisodeReleaseValue(
                addonReleased = video.released,
                tmdbAirDate = ep?.airDate,
                useTmdbReleaseDates = settings.useReleaseDates
            ),
            thumbnail = if (settings.useEpisodes) ep?.thumbnail ?: video.thumbnail else video.thumbnail,
            runtime = if (settings.useEpisodes) ep?.runtimeMinutes ?: video.runtime else video.runtime
        )
    }

    private fun resolveTmdbContentType(meta: Meta): ContentType {
        val fromRoute = parseApiTypeToContentType(itemType)
        if (fromRoute != null) return fromRoute

        val fromMetaApi = parseApiTypeToContentType(meta.apiType)
        if (fromMetaApi != null) return fromMetaApi

        return when (meta.type) {
            ContentType.SERIES, ContentType.TV -> ContentType.SERIES
            ContentType.MOVIE -> ContentType.MOVIE
            else -> ContentType.MOVIE
        }
    }

    private fun parseApiTypeToContentType(apiType: String?): ContentType? {
        val normalized = apiType?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            "movie", "film" -> ContentType.MOVIE
            "series", "tv", "show", "tvshow" -> ContentType.SERIES
            else -> null
        }
    }

    private fun selectSeason(season: Int) {
        val meta = _uiState.value.meta ?: return
        val episodes = getEpisodesForSeason(meta.videos, season)
        _uiState.update {
            it.copy(
                selectedSeason = season,
                episodesForSeason = episodes
            )
        }
    }

    private fun getEpisodesForSeason(videos: List<Video>, season: Int): List<Video> {
        val filtered = videos.filter { it.season == season }
        if (filtered.isNotEmpty()) return filtered.sortedBy { it.episode }
        // Fallback: if no videos match the season (e.g. "other" type with
        // null seasons), return all videos with synthetic season/episode
        // numbers so the episode UI can track watched state.
        if (videos.isNotEmpty() && videos.all { it.season == null }) {
            return videos.mapIndexed { index, video ->
                video.copy(season = 1, episode = index + 1)
            }
        }
        return emptyList()
    }

    private fun selectCommentsMode(mode: CommentsMode) {
        val meta = _uiState.value.meta ?: return
        if (_uiState.value.commentsMode == mode) return
        val nextTarget = resolveCommentsEpisodeTarget(
            meta = meta,
            preferredSeason = _uiState.value.selectedSeason,
            existingTarget = _uiState.value.commentsEpisodeTarget
        )
        _uiState.update {
            it.copy(
                commentsMode = mode,
                commentsEpisodeTarget = nextTarget,
                selectedComment = null
            )
        }
        if (traktCommentsEnabled && traktAuthenticated && supportsComments(meta)) {
            loadComments(meta, forceRefresh = true)
        }
    }

    private fun selectCommentsEpisode(video: Video) {
        val meta = _uiState.value.meta ?: return
        if (_uiState.value.commentsEpisodeTarget?.id == video.id && _uiState.value.commentsMode == CommentsMode.EPISODE) {
            return
        }
        _uiState.update {
            it.copy(
                commentsMode = CommentsMode.EPISODE,
                commentsEpisodeTarget = video,
                selectedComment = null
            )
        }
        if (traktCommentsEnabled && traktAuthenticated && supportsComments(meta)) {
            loadComments(meta, forceRefresh = true)
        }
    }

    private fun currentCommentsEpisodeTarget(meta: Meta): Video? {
        val state = _uiState.value
        if (state.commentsMode != CommentsMode.EPISODE) return null
        return resolveCommentsEpisodeTarget(
            meta = meta,
            preferredSeason = state.selectedSeason,
            existingTarget = state.commentsEpisodeTarget
        )
    }

    private fun resolveCommentsEpisodeTarget(
        meta: Meta,
        preferredSeason: Int,
        existingTarget: Video?
    ): Video? {
        val allEpisodes = meta.videos.filter { it.season != null && it.episode != null }
        if (allEpisodes.isEmpty()) return null

        existingTarget
            ?.takeIf { target ->
                allEpisodes.any { episode ->
                    episode.season == target.season && episode.episode == target.episode
                }
            }
            ?.let { return it }

        val nextToWatch = _uiState.value.nextToWatch
        nextToWatch?.nextVideoId
            ?.let { nextVideoId ->
                allEpisodes.firstOrNull { episode -> episode.id == nextVideoId }
            }
            ?.let { return it }

        nextToWatch
            ?.takeIf { it.nextSeason != null && it.nextEpisode != null }
            ?.let { target ->
                allEpisodes.firstOrNull { episode ->
                    episode.season == target.nextSeason && episode.episode == target.nextEpisode
                }
            }
            ?.let { return it }

        return allEpisodes.firstOrNull { it.season == preferredSeason }
            ?: allEpisodes.firstOrNull { (it.season ?: 0) > 0 }
            ?: allEpisodes.first()
    }

    private fun reevaluateSeriesWatchedBadge() {
        val contentId = _effectiveContentId.value
        val meta = _uiState.value.meta ?: return
        val isSeries = meta.apiType.equals("series", ignoreCase = true) ||
            meta.apiType.equals("tv", ignoreCase = true)
        if (!isSeries) return

        val episodes = meta.watchableEpisodes()
        if (episodes.isEmpty()) return

        val watchedEpisodes = _uiState.value.watchedEpisodes
        val progressMap = _uiState.value.episodeProgressMap

        val allWatched = episodes.all { video ->
            val key = video.season!! to video.episode!!
            key in watchedEpisodes || progressMap[key]?.isCompleted() == true
        }

        val current = watchedSeriesStateHolder.fullyWatchedSeriesIds.value
        val allIds = buildSet {
            add(contentId)
            meta.id.takeIf { it.isNotBlank() && it != contentId }?.let { add(it) }
            itemId.takeIf { it.isNotBlank() && it != contentId }?.let { add(it) }
        }
        val updated = if (allWatched) current + allIds else current - allIds
        if (updated != current) {
            watchedSeriesStateHolder.updateWithValidation(updated, allIds)
        }
    }

    private fun calculateNextToWatch() {
        val meta = _uiState.value.meta ?: return
        val progressMap = _uiState.value.episodeProgressMap
        val watchedEpisodes = _uiState.value.watchedEpisodes
        // 0.8.4 merge (#3): don't let an observer-driven recompute from empty progress
        // clobber a good nextToWatch that the applyMetaWithEnrichment refine already set.
        if (progressMap.isEmpty() && watchedEpisodes.isEmpty() && _uiState.value.nextToWatch != null) {
            return
        }
        nextToWatchJob?.cancel()

        nextToWatchJob = viewModelScope.launch {
            val nextToWatch = computeNextToWatch(meta, progressMap, watchedEpisodes)
            updateNextToWatch(nextToWatch)
        }
    }

    private suspend fun computeNextToWatch(
        meta: Meta,
        progressMap: Map<Pair<Int, Int>, WatchProgress> = emptyMap(),
        watchedEpisodes: Set<Pair<Int, Int>> = emptySet()
    ): NextToWatch {
        val isSeries = meta.apiType in listOf("series", "tv")

        if (!isSeries) {
            val progress = watchProgressRepository.getProgress(_effectiveContentId.value).first()
            return if (progress != null && shouldResumeProgress(progress)) {
                NextToWatch(
                    watchProgress = progress,
                    isResume = true,
                    nextVideoId = meta.id,
                    nextSeason = null,
                    nextEpisode = null,
                    displayText = localizedContext.getString(R.string.detail_btn_resume)
                )
            } else {
                NextToWatch(
                    watchProgress = null,
                    isResume = false,
                    nextVideoId = meta.id,
                    nextSeason = null,
                    nextEpisode = null,
                    displayText = localizedContext.getString(R.string.detail_btn_play)
                )
            }
        }

        val allEpisodes = meta.videos
            .filter { it.season != null && it.episode != null }
            .filter { it.available != false }
            .sortedWith(compareBy({ it.season }, { it.episode }))

        if (allEpisodes.isEmpty()) {
            return NextToWatch(
                watchProgress = null,
                isResume = false,
                nextVideoId = meta.id,
                nextSeason = null,
                nextEpisode = null,
                displayText = localizedContext.getString(R.string.detail_btn_play)
            )
        }

        val nonSpecialEpisodes = allEpisodes.filter { (it.season ?: 0) > 0 }
        val episodePool = if (nonSpecialEpisodes.isNotEmpty()) nonSpecialEpisodes else allEpisodes
        val useFurthestEpisode = layoutPreferenceDataStore.nextUpFromFurthestEpisode.first()
        val latestSeriesProgress = if (useFurthestEpisode) {
            // When using furthest episode mode, consider both progressMap entries
            // AND watchedEpisodes (batch marks) to find the furthest watched episode.
            val furthestFromProgress = progressMap.values
                .filter { it.isCompleted() || shouldResumeProgress(it) }
                .maxWithOrNull(
                    compareBy<WatchProgress> { it.season ?: 0 }
                        .thenBy { it.episode ?: 0 }
                )
            val furthestFromWatched = watchedEpisodes
                .maxWithOrNull(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
                ?.let { (s, e) ->
                    // Only use watchedEpisodes entry if it's further than progressMap
                    val progressFurthest = furthestFromProgress?.let { (it.season ?: 0) to (it.episode ?: 0) }
                    if (progressFurthest == null || s > progressFurthest.first || (s == progressFurthest.first && e > progressFurthest.second)) {
                        episodePool.firstOrNull { it.season == s && it.episode == e }?.let { video ->
                            WatchProgress(
                                contentId = _effectiveContentId.value,
                                contentType = "series",
                                name = "",
                                poster = null, backdrop = null, logo = null,
                                videoId = video.id,
                                season = video.season,
                                episode = video.episode,
                                episodeTitle = video.title,
                                position = 1L, duration = 1L,
                                lastWatched = System.currentTimeMillis(),
                                progressPercent = 100f
                            )
                        }
                    } else null
                }
            furthestFromWatched ?: furthestFromProgress
        } else {
            progressMap.values
                .sortedWith(
                    compareByDescending<WatchProgress> { it.lastWatched }
                        .thenByDescending { it.season ?: 0 }
                        .thenByDescending { it.episode ?: 0 }
                )
                .firstOrNull()
        }
        val effectiveLatestProgress = latestSeriesProgress ?: run {
            if (watchedEpisodes.isEmpty()) null
            else {
                val watchedWithTimestamps = watchedItemsPreferences
                    .getWatchedEpisodesWithTimestamps(_effectiveContentId.value)
                    .first()
                val highest = watchedWithTimestamps.entries
                    .maxWithOrNull(compareBy<Map.Entry<Pair<Int, Int>, Long>> { it.value }
                        .thenBy { it.key.first }
                        .thenBy { it.key.second })
                highest?.let { (key, watchedAt) ->
                    val (s, e) = key
                    episodePool.firstOrNull { it.season == s && it.episode == e }?.let { video ->
                        WatchProgress(
                            contentId = _effectiveContentId.value,
                            contentType = "series",
                            name = "",
                            poster = null, backdrop = null, logo = null,
                            videoId = video.id,
                            season = video.season,
                            episode = video.episode,
                            episodeTitle = video.title,
                            position = 1L, duration = 1L,
                            lastWatched = watchedAt,
                            progressPercent = 100f
                        )
                    }
                }
            }
        }
        val defaultEpisode = findPreferredDefaultEpisode(meta)?.takeIf { preferred ->
            episodePool.any { it.id == preferred.id }
        }

        return buildNextToWatchFromLatestProgress(
            latestProgress = effectiveLatestProgress,
            episodes = episodePool,
            fallbackProgressMap = progressMap,
            watchedEpisodes = watchedEpisodes,
            metaId = meta.id,
            defaultEpisode = defaultEpisode,
            isRewatchMode = !useFurthestEpisode
        )
    }

    private fun buildNextToWatchFromLatestProgress(
        latestProgress: WatchProgress?,
        episodes: List<Video>,
        fallbackProgressMap: Map<Pair<Int, Int>, WatchProgress>,
        watchedEpisodes: Set<Pair<Int, Int>> = emptySet(),
        metaId: String,
        defaultEpisode: Video? = null,
        isRewatchMode: Boolean = false
    ): NextToWatch {
        if (episodes.isEmpty()) {
            return NextToWatch(
                watchProgress = null,
                isResume = false,
                nextVideoId = metaId,
                nextSeason = null,
                nextEpisode = null,
                displayText = localizedContext.getString(R.string.detail_btn_play)
            )
        }

        if (latestProgress?.season != null && latestProgress.episode != null) {
            val season = latestProgress.season
            val episode = latestProgress.episode
            val matchedIndex = episodes.indexOfFirst { it.season == season && it.episode == episode }

            if (shouldResumeProgress(latestProgress)) {
                val matchedEpisode = if (matchedIndex >= 0) episodes[matchedIndex] else null
                return NextToWatch(
                    watchProgress = latestProgress,
                    isResume = true,
                    nextVideoId = matchedEpisode?.id ?: latestProgress.videoId,
                    nextSeason = season,
                    nextEpisode = episode,
                    displayText = localizedContext.getString(R.string.detail_btn_resume_episode, season, episode)
                )
            }

            if (latestProgress.isCompleted() && matchedIndex >= 0) {
                if (isRewatchMode) {
                    // In rewatch mode, simply take the next episode regardless of watched state
                    val next = episodes.getOrNull(matchedIndex + 1)
                    if (next != null) {
                        return NextToWatch(
                            watchProgress = null,
                            isResume = false,
                            nextVideoId = next.id,
                            nextSeason = next.season,
                            nextEpisode = next.episode,
                            displayText = localizedContext.getString(R.string.detail_btn_next_episode, next.season, next.episode)
                        )
                    }
                } else {
                    // Normal mode: skip already watched episodes
                    val nextUnwatched = episodes.subList(matchedIndex + 1, episodes.size)
                        .firstOrNull { candidate ->
                            val s = candidate.season ?: return@firstOrNull true
                            val e = candidate.episode ?: return@firstOrNull true
                            val progress = fallbackProgressMap[s to e]
                            val isWatched = progress?.isCompleted() == true || (s to e) in watchedEpisodes
                            !isWatched
                        }
                    if (nextUnwatched != null) {
                        return NextToWatch(
                            watchProgress = null,
                            isResume = false,
                            nextVideoId = nextUnwatched.id,
                            nextSeason = nextUnwatched.season,
                            nextEpisode = nextUnwatched.episode,
                            displayText = localizedContext.getString(R.string.detail_btn_next_episode, nextUnwatched.season, nextUnwatched.episode)
                        )
                    }
                }
            }
        }

        var resumeEpisode: Video? = null
        var resumeProgress: WatchProgress? = null
        var nextUnwatchedEpisode: Video? = null

        for (episode in episodes) {
            val season = episode.season ?: continue
            val ep = episode.episode ?: continue
            val progress = fallbackProgressMap[season to ep]

            if (progress != null) {
                if (shouldResumeProgress(progress)) {
                    resumeEpisode = episode
                    resumeProgress = progress
                    break
                } else if (progress.isCompleted()) {
                    continue
                }
            }
            // Check watchedEpisodes — covers both batch marks and episodes
            // that haven't propagated to episodeProgressMap yet.
            if (progress == null && (season to ep) in watchedEpisodes) {
                continue
            }
            if (progress == null) {
                if (nextUnwatchedEpisode == null) {
                    nextUnwatchedEpisode = episode
                }
                if (resumeEpisode == null) {
                    break
                }
            }
        }

        return when {
            resumeEpisode != null && resumeProgress != null -> {
                NextToWatch(
                    watchProgress = resumeProgress,
                    isResume = true,
                    nextVideoId = resumeEpisode.id,
                    nextSeason = resumeEpisode.season,
                    nextEpisode = resumeEpisode.episode,
                    displayText = localizedContext.getString(R.string.detail_btn_resume_episode, resumeEpisode.season, resumeEpisode.episode)
                )
            }
            nextUnwatchedEpisode != null -> {
                val hasWatchedSomething = fallbackProgressMap.isNotEmpty()
                val preferredEpisode = if (hasWatchedSomething) nextUnwatchedEpisode else (defaultEpisode ?: nextUnwatchedEpisode)
                val s = preferredEpisode.season
                val e = preferredEpisode.episode
                NextToWatch(
                    watchProgress = null,
                    isResume = false,
                    nextVideoId = preferredEpisode.id,
                    nextSeason = s,
                    nextEpisode = e,
                    displayText = if (hasWatchedSomething) {
                        localizedContext.getString(R.string.detail_btn_next_episode, s, e)
                    } else {
                        localizedContext.getString(R.string.detail_btn_play_episode, s, e)
                    }
                )
            }
            else -> {
                // Fork (caught-up fix): this branch is reached only when every
                // available episode is watched and none is resumable - e.g.
                // the newest aired episode of an ongoing series was just
                // finished and the next is unreleased. It previously targeted
                // episodes.firstOrNull(), resetting the Play button to S1E1.
                // Target the LAST episode instead (an honest replay) and flag
                // the state so the hero source line suppresses itself (A2).
                val lastEpisode = episodes.lastOrNull()
                NextToWatch(
                    watchProgress = null,
                    isResume = false,
                    nextVideoId = lastEpisode?.id ?: metaId,
                    nextSeason = lastEpisode?.season,
                    nextEpisode = lastEpisode?.episode,
                    displayText = if (lastEpisode != null) {
                        localizedContext.getString(R.string.detail_btn_play_episode, lastEpisode.season, lastEpisode.episode)
                    } else {
                        localizedContext.getString(R.string.detail_btn_play)
                    },
                    isCaughtUp = true
                )
            }
        }
    }

    private fun findPreferredDefaultEpisode(meta: Meta): Video? {
        val defaultVideoId = meta.behaviorHints?.defaultVideoId ?: return null
        return meta.videos.firstOrNull { it.id == defaultVideoId && it.available != false }
    }

    private fun shouldResumeProgress(progress: WatchProgress): Boolean {
        if (progress.isCompleted()) return false
        if (progress.progressPercentage >= 0.02f) return true

        val hasStartedPlayback = progress.position > 0L ||
            progress.progressPercent?.let { it > 0f } == true
        return hasStartedPlayback &&
            progress.source != WatchProgress.SOURCE_TRAKT_HISTORY &&
            progress.source != WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
    }

    private fun toggleLibrary() {
        if (
            _uiState.value.defaultLibraryTogglePending ||
            _uiState.value.removalConfirmations.isNotEmpty()
        ) {
            return
        }
        val meta = _uiState.value.meta ?: return
        viewModelScope.launch {
            val input = meta.toLibraryEntryInput()
            val wasInWatchlist = _uiState.value.isInWatchlist
            val wasInLibrary = _uiState.value.isInLibrary
            _uiState.update { it.copy(defaultLibraryTogglePending = true) }
            runCatching {
                libraryRepository.toggleDefault(input)
            }.onSuccess { result ->
                if (result.requiresRemovalConfirmation) {
                    pendingDefaultLibraryToggle = input
                    _uiState.update {
                        it.copy(
                            defaultLibraryTogglePending = false,
                            removalConfirmations = result.requiredRemovalConfirmations
                        )
                    }
                    return@onSuccess
                }
                pendingDefaultLibraryToggle = null
                _uiState.update { it.copy(defaultLibraryTogglePending = false) }
                val message = if (wasInLibrary || wasInWatchlist) {
                    localizedContext.getString(R.string.detail_removed_from_library)
                } else {
                    localizedContext.getString(R.string.detail_added_to_library)
                }
                showMessage(message)
            }.onFailure { error ->
                pendingDefaultLibraryToggle = null
                _uiState.update { it.copy(defaultLibraryTogglePending = false) }
                showMessage(
                    message = error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_update_library_failed),
                    isError = true
                )
            }
        }
    }

    private fun openListPicker() {
        val meta = _uiState.value.meta ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pickerPending = true, pickerError = null) }
            runCatching {
                val snapshot = libraryRepository.getMembershipSnapshot(meta.toLibraryEntryInput())
                _uiState.update {
                    it.copy(
                        showListPicker = true,
                        pickerMembership = snapshot.listMembership,
                        pickerPending = false,
                        pickerError = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        pickerPending = false,
                        pickerError = error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_load_lists_failed),
                        showListPicker = false
                    )
                }
                showMessage(error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_load_lists_failed), isError = true)
            }
        }
    }

    private fun togglePickerMembership(listKey: String) {
        _uiState.update { current ->
            val updatedMembership = toggleTrackingMembershipSelection(
                tabs = current.libraryListTabs,
                membership = current.pickerMembership,
                listKey = listKey,
                contentType = current.meta?.apiType
            ) ?: return@update current
            current.copy(
                pickerMembership = updatedMembership,
                pickerError = null
            )
        }
    }

    private fun savePickerMembership() {
        if (_uiState.value.pickerPending) return
        val meta = _uiState.value.meta ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(pickerPending = true, pickerError = null) }
            runCatching {
                libraryRepository.applyMembershipChanges(
                    item = meta.toLibraryEntryInput(),
                    changes = ListMembershipChanges(
                        desiredMembership = _uiState.value.pickerMembership
                    )
                )
            }.onSuccess { result ->
                if (result.requiresRemovalConfirmation) {
                    pendingDefaultLibraryToggle = null
                    _uiState.update {
                        it.copy(
                            pickerPending = false,
                            removalConfirmations = result.requiredRemovalConfirmations
                        )
                    }
                } else {
                    completePickerSave()
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        pickerPending = false,
                        pickerError = error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_update_lists_failed)
                    )
                }
                showMessage(error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_update_lists_failed), isError = true)
            }
        }
    }

    private fun dismissListPicker() {
        pendingDefaultLibraryToggle = null
        _uiState.update {
            it.copy(
                showListPicker = false,
                pickerPending = false,
                pickerError = null,
                defaultLibraryTogglePending = false,
                removalConfirmations = emptyList()
            )
        }
    }

    private fun confirmPickerRemoval() {
        if (_uiState.value.pickerPending || _uiState.value.defaultLibraryTogglePending) return
        val meta = _uiState.value.meta ?: return
        val confirmations = _uiState.value.removalConfirmations
        if (confirmations.isEmpty()) return
        val defaultToggle = pendingDefaultLibraryToggle
        if (defaultToggle != null) {
            confirmDefaultLibraryRemoval(defaultToggle, confirmations)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(pickerPending = true) }
            runCatching {
                libraryRepository.applyMembershipChanges(
                    item = meta.toLibraryEntryInput(),
                    changes = ListMembershipChanges(_uiState.value.pickerMembership),
                    confirmedRemovalProviders = confirmations.mapTo(linkedSetOf(), TrackingMembershipRemovalConfirmation::providerId)
                )
            }.onSuccess { result ->
                if (result.requiresRemovalConfirmation) {
                    _uiState.update {
                        it.copy(
                            pickerPending = false,
                            removalConfirmations = result.requiredRemovalConfirmations
                        )
                    }
                } else {
                    completePickerSave()
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        pickerPending = false,
                        removalConfirmations = emptyList(),
                        pickerError = error.message
                            ?: context.getString(com.nuvio.tv.R.string.detail_error_update_lists_failed)
                    )
                }
            }
        }
    }

    private fun confirmDefaultLibraryRemoval(
        input: LibraryEntryInput,
        confirmations: List<TrackingMembershipRemovalConfirmation>
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(defaultLibraryTogglePending = true) }
            runCatching {
                libraryRepository.toggleDefault(
                    item = input,
                    confirmedRemovalProviders = confirmations.mapTo(
                        linkedSetOf(),
                        TrackingMembershipRemovalConfirmation::providerId
                    )
                )
            }.onSuccess { result ->
                if (result.requiresRemovalConfirmation) {
                    _uiState.update {
                        it.copy(
                            defaultLibraryTogglePending = false,
                            removalConfirmations = result.requiredRemovalConfirmations
                        )
                    }
                } else {
                    pendingDefaultLibraryToggle = null
                    _uiState.update {
                        it.copy(
                            defaultLibraryTogglePending = false,
                            removalConfirmations = emptyList()
                        )
                    }
                    showMessage(localizedContext.getString(R.string.detail_removed_from_library))
                }
            }.onFailure { error ->
                pendingDefaultLibraryToggle = null
                _uiState.update {
                    it.copy(
                        defaultLibraryTogglePending = false,
                        removalConfirmations = emptyList()
                    )
                }
                showMessage(
                    message = error.message
                        ?: context.getString(com.nuvio.tv.R.string.detail_error_update_library_failed),
                    isError = true
                )
            }
        }
    }

    private fun cancelPickerRemoval() {
        pendingDefaultLibraryToggle = null
        _uiState.update {
            it.copy(
                defaultLibraryTogglePending = false,
                removalConfirmations = emptyList()
            )
        }
    }

    private fun completePickerSave() {
        pendingDefaultLibraryToggle = null
        _uiState.update {
            it.copy(
                pickerPending = false,
                showListPicker = false,
                pickerError = null,
                defaultLibraryTogglePending = false,
                removalConfirmations = emptyList()
            )
        }
        showMessage(localizedContext.getString(R.string.detail_lists_updated))
    }

    private fun toggleMovieWatched() {
        val meta = _uiState.value.meta ?: return
        if (meta.apiType != "movie") return
        if (_uiState.value.isMovieWatchedPending) return

        viewModelScope.launch {
            _uiState.update { it.copy(isMovieWatchedPending = true) }
            runCatching {
                if (_uiState.value.isMovieWatched) {
                    watchProgressRepository.removeFromHistory(_effectiveContentId.value, videoId = resolveFallbackVideoId())
                    showMessage(localizedContext.getString(R.string.detail_movie_marked_unwatched))
                } else {
                    watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(meta))
                    showMessage(localizedContext.getString(R.string.detail_movie_marked_watched))
                }
            }.onFailure { error ->
                showMessage(
                    message = error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_update_watched_failed),
                    isError = true
                )
            }
            _uiState.update { it.copy(isMovieWatchedPending = false) }
        }
    }

    private fun toggleEpisodeWatched(video: Video) {
        val meta = _uiState.value.meta ?: return
        val season = video.season ?: return
        val episode = video.episode ?: return
        val pendingKey = episodePendingKey(video)
        if (_uiState.value.episodeWatchedPendingKeys.contains(pendingKey)) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKey)
            }

            val isWatched = _uiState.value.episodeProgressMap[season to episode]?.isCompleted() == true
                || _uiState.value.watchedEpisodes.contains(season to episode)
            runCatching {
                if (isWatched) {
                    _optimisticMarks -= season to episode
                    _optimisticUnmarks += season to episode
                    watchProgressRepository.removeFromHistory(_effectiveContentId.value, videoId = video.id, season = season, episode = episode)
                    showMessage(localizedContext.getString(R.string.detail_episode_marked_unwatched))
                } else {
                    _optimisticUnmarks -= season to episode
                    _optimisticMarks += season to episode
                    watchProgressRepository.markAsCompleted(buildCompletedEpisodeProgress(meta, video))
                    showMessage(localizedContext.getString(R.string.detail_episode_marked_watched))
                }
            }.onFailure { error ->
                showMessage(
                    message = error.message ?: context.getString(com.nuvio.tv.R.string.detail_error_update_episode_watched_failed),
                    isError = true
                )
            }

            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - pendingKey)
            }
        }
    }

    fun isSeasonFullyWatched(season: Int): Boolean {
        val state = _uiState.value
        val meta = state.meta ?: return false
        // For "other" type, use episodesForSeason which has synthetic season/episode.
        // For regular series, use meta.videos to support checking any season.
        val episodes = if (meta.apiType.equals("other", ignoreCase = true)) {
            state.episodesForSeason.filter { it.season == season && it.episode != null }
        } else {
            meta.videos.filter { it.season == season && it.episode != null }
        }
        if (episodes.isEmpty()) return false
        return episodes.all { video ->
            val s = video.season ?: return@all false
            val e = video.episode ?: return@all false
            state.episodeProgressMap[s to e]?.isCompleted() == true
                || state.watchedEpisodes.contains(s to e)
        }
    }

    private fun markSeasonWatched(season: Int) {
        val meta = _uiState.value.meta ?: return
        suppressSeasonAutoSwitch = true
        viewModelScope.launch {
            val episodes = if (meta.apiType.equals("other", ignoreCase = true)) {
                _uiState.value.episodesForSeason.filter { it.season == season && it.episode != null }
            } else {
                meta.videos.filter { it.season == season && it.episode != null }
            }
            val unwatched = episodes.filter { video ->
                val s = video.season!!
                val e = video.episode!!
                val isWatched = _uiState.value.episodeProgressMap[s to e]?.isCompleted() == true
                    || _uiState.value.watchedEpisodes.contains(s to e)
                !isWatched
            }
            if (unwatched.isEmpty()) {
                showMessage(localizedContext.getString(R.string.detail_all_episodes_watched))
                return@launch
            }

            val optimisticKeys = unwatched.map { it.season!! to it.episode!! }.toSet()
            _optimisticUnmarks -= optimisticKeys
            _optimisticMarks += optimisticKeys

            val pendingKeys = unwatched.map { episodePendingKey(it) }.toSet()
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKeys)
            }

            runCatching {
                val progressList = unwatched.map { buildCompletedEpisodeProgress(meta, it) }
                watchProgressRepository.markAsCompletedBatch(progressList)
            }.onFailure { error ->
                Log.w(TAG, "Failed to batch mark season $season as watched: ${error.message}")
            }

            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - pendingKeys)
            }
            reevaluateSeriesWatchedBadge()
            showMessage(localizedContext.getString(R.string.detail_marked_episodes_watched, unwatched.size))
        }
    }

    private fun markSeasonUnwatched(season: Int) {
        val meta = _uiState.value.meta ?: return
        suppressSeasonAutoSwitch = true
        viewModelScope.launch {
            val episodes = if (meta.apiType.equals("other", ignoreCase = true)) {
                _uiState.value.episodesForSeason.filter { it.season == season && it.episode != null }
            } else {
                meta.videos.filter { it.season == season && it.episode != null }
            }
            val watched = episodes.filter { video ->
                val s = video.season!!
                val e = video.episode!!
                _uiState.value.episodeProgressMap[s to e]?.isCompleted() == true
                    || _uiState.value.watchedEpisodes.contains(s to e)
            }
            if (watched.isEmpty()) {
                showMessage(localizedContext.getString(R.string.detail_no_watched_episodes))
                return@launch
            }

            val optimisticKeys = watched.map { it.season!! to it.episode!! }.toSet()
            _optimisticMarks -= optimisticKeys
            _optimisticUnmarks += optimisticKeys

            val pendingKeys = watched.map { episodePendingKey(it) }.toSet()
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKeys)
            }

            runCatching {
                val episodeTriples = watched.map { Triple(it.season!!, it.episode!!, it.id) }
                watchProgressRepository.removeFromHistoryBatch(
                    contentId = _effectiveContentId.value,
                    videoId = resolveFallbackVideoId(),
                    episodes = episodeTriples
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to batch unmark season $season: ${error.message}")
            }

            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - pendingKeys)
            }
            reevaluateSeriesWatchedBadge()
            showMessage(localizedContext.getString(R.string.detail_marked_episodes_unwatched, watched.size))
        }
    }

    private fun markPreviousEpisodesWatched(video: Video) {
        val meta = _uiState.value.meta ?: return
        val targetSeason = video.season ?: return
        val targetEpisode = video.episode ?: return

        viewModelScope.launch {
            val previous = if (meta.apiType.equals("other", ignoreCase = true)) {
                _uiState.value.episodesForSeason.filter { v ->
                    v.season == targetSeason && v.episode != null && v.episode < targetEpisode
                }
            } else {
                meta.videos.filter { v ->
                    v.season == targetSeason && v.episode != null && v.episode < targetEpisode
                }
            }
            val unwatched = previous.filter { v ->
                val s = v.season!!
                val e = v.episode!!
                val isWatched = _uiState.value.episodeProgressMap[s to e]?.isCompleted() == true
                    || _uiState.value.watchedEpisodes.contains(s to e)
                !isWatched
            }
            if (unwatched.isEmpty()) {
                showMessage(localizedContext.getString(R.string.detail_all_previous_watched))
                return@launch
            }

            val optimisticKeys = unwatched.map { it.season!! to it.episode!! }.toSet()
            _optimisticUnmarks -= optimisticKeys
            _optimisticMarks += optimisticKeys

            val pendingKeys = unwatched.map { episodePendingKey(it) }.toSet()
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKeys)
            }

            runCatching {
                val progressList = unwatched.map { buildCompletedEpisodeProgress(meta, it) }
                watchProgressRepository.markAsCompletedBatch(progressList)
            }.onFailure { error ->
                Log.w(TAG, "Failed to batch mark previous episodes as watched: ${error.message}")
            }

            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - pendingKeys)
            }
            reevaluateSeriesWatchedBadge()
            showMessage(localizedContext.getString(R.string.detail_marked_previous_watched, unwatched.size))
        }
    }

    private fun markPreviousSeasonsWatched(targetSeason: Int) {
        val meta = _uiState.value.meta ?: return
        suppressSeasonAutoSwitch = true
        viewModelScope.launch {
            val episodes = if (meta.apiType.equals("other", ignoreCase = true)) {
                _uiState.value.episodesForSeason.filter { it.season != null && it.season < targetSeason && it.season > 0 && it.episode != null }
            } else {
                meta.videos.filter { it.season != null && it.season < targetSeason && it.season > 0 && it.episode != null }
            }
            val unwatched = episodes.filter { video ->
                val s = video.season!!
                val e = video.episode!!
                val isWatched = _uiState.value.episodeProgressMap[s to e]?.isCompleted() == true
                    || _uiState.value.watchedEpisodes.contains(s to e)
                !isWatched
            }
            if (unwatched.isEmpty()) {
                showMessage(localizedContext.getString(R.string.detail_all_previous_seasons_watched))
                return@launch
            }

            val optimisticKeys = unwatched.map { it.season!! to it.episode!! }.toSet()
            _optimisticUnmarks -= optimisticKeys
            _optimisticMarks += optimisticKeys

            val pendingKeys = unwatched.map { episodePendingKey(it) }.toSet()
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKeys)
            }

            runCatching {
                val progressList = unwatched.map { buildCompletedEpisodeProgress(meta, it) }
                watchProgressRepository.markAsCompletedBatch(progressList)
            }.onFailure { error ->
                Log.w(TAG, "Failed to batch mark previous seasons as watched: ${error.message}")
            }

            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - pendingKeys)
            }
            reevaluateSeriesWatchedBadge()
            showMessage(localizedContext.getString(R.string.detail_marked_episodes_watched, unwatched.size))
        }
    }

    private fun resolveFallbackVideoId(): String? {
        val meta = _uiState.value.meta ?: return null
        return meta.imdbId?.takeIf { it != itemId && it.isNotBlank() }
    }

    private fun buildCompletedMovieProgress(meta: Meta): WatchProgress {
        return WatchProgress(
            contentId = _effectiveContentId.value,
            contentType = meta.apiType,
            name = meta.name,
            poster = meta.poster,
            backdrop = meta.backdropUrl,
            logo = meta.logo,
            videoId = meta.id,
            season = null,
            episode = null,
            episodeTitle = null,
            position = 1L,
            duration = 1L,
            lastWatched = System.currentTimeMillis(),
            progressPercent = 100f
        )
    }

    private fun buildCompletedEpisodeProgress(meta: Meta, video: Video): WatchProgress {
        val runtimeMs = video.runtime?.toLong()?.times(60_000L) ?: 1L
        return WatchProgress(
            contentId = _effectiveContentId.value,
            contentType = meta.apiType,
            name = meta.name,
            poster = meta.poster,
            backdrop = video.thumbnail ?: meta.backdropUrl,
            logo = meta.logo,
            videoId = video.id,
            season = video.season,
            episode = video.episode,
            episodeTitle = video.title,
            position = runtimeMs,
            duration = runtimeMs,
            lastWatched = System.currentTimeMillis(),
            progressPercent = 100f
        )
    }

    private fun episodePendingKey(video: Video): String {
        return "${video.id}:${video.season ?: -1}:${video.episode ?: -1}"
    }

    private fun showMessage(message: String, isError: Boolean = false) {
        _uiState.update { state ->
            if (state.userMessage == message && state.userMessageIsError == isError) {
                state
            } else {
                state.copy(
                    userMessage = message,
                    userMessageIsError = isError
                )
            }
        }
    }

    private fun clearMessage() {
        _uiState.update { state ->
            if (state.userMessage == null && !state.userMessageIsError) {
                state
            } else {
                state.copy(userMessage = null, userMessageIsError = false)
            }
        }
    }

    private fun extractImdbId(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return null
        val normalized = rawId.trim()
        return if (normalized.startsWith("tt", ignoreCase = true)) {
            normalized.substringBefore(':')
        } else {
            null
        }
    }

    private fun Meta.toLibraryEntryInput(): LibraryEntryInput {
        val year = Regex("(\\d{4})").find(releaseInfo ?: "")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val parsedIds = parseContentIds(id)
        return LibraryEntryInput(
            itemId = id,
            itemType = apiType,
            title = name,
            year = year,
            traktId = parsedIds.trakt,
            imdbId = parsedIds.imdb,
            tmdbId = parsedIds.tmdb,
            poster = poster,
            posterShape = posterShape,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            addonBaseUrl = preferredAddonBaseUrl
        )
    }

    fun getNextEpisodeInfo(): String? {
        val nextToWatch = _uiState.value.nextToWatch
        return nextToWatch?.displayText
    }

    // --- Trailer ---

    private fun fetchTrailerUrl() {
        val meta = _uiState.value.meta ?: return

        trailerFetchJob?.cancel()
        trailerFetchJob = viewModelScope.launch {
            _uiState.update { state ->
                if (state.isTrailerLoading) state else state.copy(isTrailerLoading = true)
            }

            val year = meta.releaseInfo?.let { info ->
                if (info.isBlank()) null
                else Regex("""\b(19|20)\d{2}\b""").find(info)?.value
            }

            val tmdbId = try {
                tmdbService.ensureTmdbId(meta.id, meta.apiType)
            } catch (_: Exception) {
                null
            }

            val source = if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
                trailerService.getTrailerPlaybackSource(
                    title = meta.name,
                    year = year,
                    tmdbId = tmdbId,
                    type = meta.apiType
                ) ?: meta.trailerYtIds.firstOrNull()?.let { ytId ->
                    trailerService.getTrailerPlaybackSourceFromYouTubeUrl(
                        youtubeUrl = "https://www.youtube.com/watch?v=$ytId",
                        title = meta.name,
                        year = year
                    )
                }
            } else {
                val externalUrl = if (AppFeaturePolicy.externalTrailerPlaybackEnabled) {
                    trailerService.getExternalTrailerUrl(
                        tmdbId = tmdbId,
                        type = meta.apiType
                    ) ?: meta.trailerYtIds.firstOrNull()?.let { ytId ->
                        "https://www.youtube.com/watch?v=$ytId"
                    }
                } else {
                    null
                }
                externalUrl?.let { com.nuvio.tv.data.trailer.TrailerPlaybackSource(videoUrl = it) }
            }
            val url = source?.videoUrl
            val audioUrl = source?.audioUrl

            _uiState.update { state ->
                if (state.trailerUrl == url &&
                    state.trailerAudioUrl == audioUrl &&
                    !state.isTrailerLoading
                ) {
                    state
                } else {
                    state.copy(
                        trailerUrl = url,
                        trailerAudioUrl = audioUrl,
                        isTrailerLoading = false
                    )
                }
            }

            if (url != null && isPlayButtonFocused && AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
                startIdleTimer()
            }
        }
    }

    private fun startIdleTimer() {
        idleTimerJob?.cancel()
        if (!AppFeaturePolicy.inAppTrailerPlaybackEnabled) return

        val state = _uiState.value
        if (state.trailerUrl == null || state.isTrailerPlaying) return
        if (!trailerAutoplayEnabled) return
        if (trailerHasPlayed) return
        if (!isPlayButtonFocused) return

        idleTimerJob = viewModelScope.launch {
            delay(trailerDelayMs)
            setTrailerPlaybackState(
                isPlaying = true,
                showControls = false,
                hideLogo = false
            )
        }
    }

    private fun handlePlayButtonFocused() {
        if (isPlayButtonFocused) return
        isPlayButtonFocused = true
        startIdleTimer()
    }

    private fun handleUserInteraction() {
        val state = _uiState.value
        val shouldStopAutoTrailer = state.isTrailerPlaying && !state.showTrailerControls
        val hasActiveIdleTimer = idleTimerJob?.isActive == true
        if (!isPlayButtonFocused && !hasActiveIdleTimer && !shouldStopAutoTrailer) {
            return
        }

        idleTimerJob?.cancel()
        isPlayButtonFocused = false

        if (shouldStopAutoTrailer) {
            trailerHasPlayed = true
            setTrailerPlaybackState(
                isPlaying = false,
                showControls = false,
                hideLogo = false
            )
        }
    }

    private fun handleLifecyclePause() {
        idleTimerJob?.cancel()
        isPlayButtonFocused = false
        dismissSharedTrailerOverlay()
        val state = _uiState.value
        if (state.isTrailerPlaying && !state.showTrailerControls) {
            trailerHasPlayed = true
            setTrailerPlaybackState(isPlaying = false, showControls = false, hideLogo = false)
        }
    }

    /**
     * Returning to this page from the player is an in-app navigation, so the
     * app never went through MainActivity.onResume and the connected tracking
     * providers were never asked to re-pull. Under a remote Watch Progress
     * source getAllEpisodeProgress is provider-projected, so an episode just
     * completed in the player does not surface until the next provider refresh
     * - leaving nextToWatch (and the hero source-line prefetch keyed off it)
     * stale on the episode just watched. Mirror MainActivity.onResume's refresh
     * here so the completion is pulled and the existing
     * observeWatchProgress -> calculateNextToWatch -> stream-prefetch cascade
     * re-runs for the new target. A no-op under a local source (no
     * authenticated providers to refresh).
     */
    private fun handleLifecycleResume() {
        viewModelScope.launch {
            trackingProgressRefreshCoordinator.refreshConnected(TrackingRefreshIntent.INVALIDATED)
        }
    }

    private fun handleTrailerButtonClick() {
        val state = _uiState.value
        if (state.trailerUrl.isNullOrBlank()) return
        if (!AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
            openExternalTrailer(state.trailerUrl)
            return
        }
        idleTimerJob?.cancel()
        isPlayButtonFocused = false
        setTrailerPlaybackState(
            isPlaying = true,
            showControls = true,
            hideLogo = true
        )
    }

    private fun handleTrailerEnded() {
        trailerHasPlayed = true
        isPlayButtonFocused = false
        setTrailerPlaybackState(
            isPlaying = false,
            showControls = false,
            hideLogo = false
        )
    }

    private fun handleSharedTrailerSelected(trailer: MetaTrailer) {
        val ytId = trailer.ytId?.trim().orEmpty()
        if (ytId.isBlank()) {
            _uiState.update { state ->
                state.copy(
                    isSharedTrailerOverlayVisible = true,
                    isSharedTrailerLoading = false,
                    sharedTrailerUrl = null,
                    sharedTrailerAudioUrl = null,
                    sharedTrailerErrorMessage = localizedContext.getString(R.string.detail_trailer_error),
                    selectedSharedTrailer = trailer
                )
            }
            return
        }

        if (!AppFeaturePolicy.inAppTrailerPlaybackEnabled && AppFeaturePolicy.externalTrailerPlaybackEnabled) {
            openExternalTrailer("https://www.youtube.com/watch?v=$ytId")
            return
        }

        idleTimerJob?.cancel()
        isPlayButtonFocused = false
        if (_uiState.value.isTrailerPlaying) {
            setTrailerPlaybackState(
                isPlaying = false,
                showControls = false,
                hideLogo = false
            )
        }

        _uiState.update { state ->
            state.copy(
                isSharedTrailerOverlayVisible = true,
                isSharedTrailerLoading = true,
                sharedTrailerUrl = null,
                sharedTrailerAudioUrl = null,
                sharedTrailerErrorMessage = null,
                selectedSharedTrailer = trailer
            )
        }

        viewModelScope.launch {
            val meta = _uiState.value.meta
            val year = meta?.releaseInfo?.let { info ->
                if (info.isBlank()) null else Regex("""\b(19|20)\d{2}\b""").find(info)?.value
            }
            val source = trailerService.getTrailerPlaybackSourceFromYouTubeUrl(
                youtubeUrl = "https://www.youtube.com/watch?v=$ytId",
                title = meta?.name,
                year = year
            )

            _uiState.update { state ->
                if (state.selectedSharedTrailer?.ytId != trailer.ytId) {
                    state
                } else {
                    state.copy(
                        isSharedTrailerOverlayVisible = true,
                        isSharedTrailerLoading = false,
                        sharedTrailerUrl = source?.videoUrl,
                        sharedTrailerAudioUrl = source?.audioUrl,
                        sharedTrailerErrorMessage = if (source == null) {
                            localizedContext.getString(R.string.detail_trailer_error)
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    private fun openExternalTrailer(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun retrySharedTrailer() {
        _uiState.value.selectedSharedTrailer?.let(::handleSharedTrailerSelected)
    }

    private fun dismissSharedTrailerOverlay() {
        _uiState.update { state ->
            state.copy(
                isSharedTrailerOverlayVisible = false,
                isSharedTrailerLoading = false,
                sharedTrailerUrl = null,
                sharedTrailerAudioUrl = null,
                sharedTrailerErrorMessage = null
            )
        }
    }

    private fun mergeTrailers(existing: List<MetaTrailer>, incoming: List<MetaTrailer>): List<MetaTrailer> {
        if (existing.isEmpty()) return incoming.distinctBy { it.ytId ?: it.name ?: it.type ?: "" }
        if (incoming.isEmpty()) return existing

        val merged = LinkedHashMap<String, MetaTrailer>()

        fun keyOf(trailer: MetaTrailer): String {
            val yt = trailer.ytId?.trim().orEmpty()
            if (yt.isNotBlank()) return "yt:$yt"
            val fallback = listOf(trailer.name, trailer.type, trailer.lang)
                .joinToString("|") { it?.trim()?.lowercase().orEmpty() }
            return "meta:$fallback"
        }

        existing.forEach { trailer -> merged.putIfAbsent(keyOf(trailer), trailer) }
        incoming.forEach { trailer -> merged.putIfAbsent(keyOf(trailer), trailer) }
        return merged.values.toList()
    }

    override fun onCleared() {
        super.onCleared()
        idleTimerJob?.cancel()
        trailerFetchJob?.cancel()
        nextToWatchJob?.cancel()
    }
}
