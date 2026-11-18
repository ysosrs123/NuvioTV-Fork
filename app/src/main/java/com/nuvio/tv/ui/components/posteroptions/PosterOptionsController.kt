package com.nuvio.tv.ui.components.posteroptions

import android.util.Log
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.tv.core.tracking.mergeTrackingMembershipWithTabs
import com.nuvio.tv.core.tracking.toggleTrackingMembershipSelection
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.isSeriesLikeType
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reusable controller for the long-press / hold-down poster options menu.
 *
 * Inject into a ViewModel via Hilt and call [bind] from the VM's init with
 * `viewModelScope`. The screen renders [PosterOptionsHost] with [state] and
 * wires [show] to each card's `onLongPress`.
 */
class PosterOptionsController @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val libraryRepository: LibraryRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder,
    private val titleIdentity: PosterTitleIdentity
) {
    private val _state = MutableStateFlow(PosterOptionsState())
    val state: StateFlow<PosterOptionsState> = _state.asStateFlow()

    private val targetFlow = MutableStateFlow<Pair<MetaPreview, TitleAlias?>?>(null)

    private var scope: CoroutineScope? = null
    private var bound = false
    private var showJob: kotlinx.coroutines.Job? = null
    private var pendingMembershipChanges: ListMembershipChanges? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun bind(scope: CoroutineScope) {
        if (bound) return
        bound = true
        this.scope = scope

        libraryRepository.sourceMode
            .distinctUntilChanged()
            .onEach { mode ->
                _state.update { current ->
                    val resetPicker = mode == LibrarySourceMode.LOCAL
                    if (resetPicker) {
                        current.copy(
                            librarySourceMode = mode,
                            listPickerActive = false,
                            listPickerPending = false,
                            listPickerError = null,
                            listPickerTitle = null,
                            listPickerContentType = null,
                            listPickerMembership = emptyMap()
                        )
                    } else {
                        current.copy(librarySourceMode = mode)
                    }
                }
            }
            .launchIn(scope)

        libraryRepository.membershipListTabs
            .distinctUntilChanged()
            .onEach { tabs ->
                _state.update { current ->
                    current.copy(
                        libraryListTabs = tabs,
                        listPickerMembership = mergeTrackingMembershipWithTabs(
                            tabs = tabs,
                            membership = current.listPickerMembership
                        )
                    )
                }
            }
            .launchIn(scope)

        targetFlow
            .flatMapLatest { shown ->
                if (shown == null) {
                    flowOf(false to false)
                } else {
                    val (item, alias) = shown
                    val isSeries = item.apiType.equals("series", ignoreCase = true) ||
                        item.apiType.equals("tv", ignoreCase = true) ||
                        item.apiType.equals("anime", ignoreCase = true)
                    if (isSeries) {
                        combine(
                            libraryRepository.isInLibraryOrAlias(item.id, item.apiType, alias),
                            watchedSeriesStateHolder.fullyWatchedSeriesIds
                        ) { lib, watchedIds -> lib to (item.id in watchedIds) }
                    } else {
                        combine(
                            libraryRepository.isInLibraryOrAlias(item.id, item.apiType, alias),
                            watchProgressRepository.isWatched(item.id, videoId = item.imdbId)
                        ) { lib, watched -> lib to watched }
                    }
                }
            }
            .onEach { (isInLibrary, isWatched) ->
                _state.update { current ->
                    current.copy(
                        isInLibrary = isInLibrary,
                        isWatched = isWatched
                    )
                }
            }
            .launchIn(scope)
    }

    fun show(item: MetaPreview, addonBaseUrl: String?) {
        val launchScope = this.scope ?: return
        showJob?.cancel()
        showJob = launchScope.launch {
            publishTarget(item, shown = item, addonBaseUrl = addonBaseUrl)
            val saved = titleIdentity.resolve(item, addonBaseUrl)
            if (saved != item) publishTarget(saved, shown = item, addonBaseUrl = addonBaseUrl)
        }
    }

    private suspend fun publishTarget(target: MetaPreview, shown: MetaPreview, addonBaseUrl: String?) {
        val alias = TitleAlias.between(target, shown)
        val isSeries = target.apiType.equals("series", ignoreCase = true) ||
            target.apiType.equals("tv", ignoreCase = true) ||
            target.apiType.equals("anime", ignoreCase = true)
        val initialIsInLibrary = runCatching {
            libraryRepository.isInLibraryOrAlias(target.id, target.apiType, alias).first()
        }.getOrDefault(false)
        val initialIsWatched = if (isSeries) {
            target.id in watchedSeriesStateHolder.fullyWatchedSeriesIds.value
        } else {
            runCatching {
                watchProgressRepository.isWatched(target.id, videoId = target.imdbId).first()
            }.getOrDefault(false)
        }

        _state.update { current ->
            current.copy(
                target = target,
                originalItemId = shown.id,
                libraryAlias = alias,
                addonBaseUrl = addonBaseUrl.orEmpty(),
                isInLibrary = initialIsInLibrary,
                isWatched = initialIsWatched,
                isLibraryPending = false,
                isWatchedPending = false
            )
        }
        targetFlow.value = target to alias
    }

    suspend fun isInLibrary(item: MetaPreview): Boolean {
        val saved = titleIdentity.resolve(item, null)
        return runCatching {
            libraryRepository.isInLibraryOrAlias(saved.id, saved.apiType, TitleAlias.between(saved, item)).first()
        }.getOrDefault(false)
    }

    /** For a page with its own library button. Returns whether the title is in the library afterwards. */
    suspend fun toggleLibraryOf(item: MetaPreview): Boolean {
        val saved = titleIdentity.resolve(item, null)
        val alias = TitleAlias.between(saved, item)
        runCatching { libraryRepository.toggleDefaultMerging(saved.toLibraryEntryInput(null), alias) }
            .onFailure { Log.w(TAG, "Failed to toggle library for ${saved.id}: ${it.message}") }
        return runCatching {
            libraryRepository.isInLibraryOrAlias(saved.id, saved.apiType, alias).first()
        }.getOrDefault(false)
    }

    private suspend fun ensureCanonical(): MetaPreview? {
        val state = _state.value
        val current = state.target ?: return null
        val canonical = titleIdentity.resolve(current, state.addonBaseUrl.takeIf { it.isNotBlank() })
        if (canonical.id != current.id && _state.value.target?.id == current.id) {
            val alias = TitleAlias.between(canonical, current) ?: state.libraryAlias
            _state.update { it.copy(target = canonical, libraryAlias = alias) }
            targetFlow.value = canonical to alias
        }
        return canonical
    }

    fun dismiss() {
        showJob?.cancel()
        showJob = null
        targetFlow.value = null
        _state.update { it.copy(target = null) }
    }

    fun toggleLibrary() {
        val state = _state.value
        if (state.target == null) return
        if (state.isLibraryPending) return
        val scope = this.scope ?: return

        _state.update { it.copy(isLibraryPending = true) }
        scope.launch {
            val canonical = ensureCanonical() ?: return@launch
            runCatching {
                libraryRepository.toggleDefaultMerging(
                    canonical.toLibraryEntryInput(state.addonBaseUrl.takeIf { it.isNotBlank() }),
                    _state.value.libraryAlias
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to toggle library for ${canonical.id}: ${error.message}")
            }
            _state.update { it.copy(isLibraryPending = false) }
        }
    }

    fun openListPicker() {
        val state = _state.value
        val item = state.target ?: return
        if (state.librarySourceMode == LibrarySourceMode.LOCAL) {
            toggleLibrary()
            dismiss()
            return
        }
        val scope = this.scope ?: return

        _state.update { current ->
            current.copy(
                target = null,
                listPickerActive = true,
                listPickerTitle = item.name,
                listPickerContentType = item.apiType,
                listPickerPending = true,
                listPickerError = null,
                listPickerMembership = mergeTrackingMembershipWithTabs(
                    tabs = current.libraryListTabs,
                    membership = emptyMap()
                )
            )
        }
        targetFlow.value = null

        scope.launch {
            val canonical = titleIdentity.resolve(item, state.addonBaseUrl.takeIf { it.isNotBlank() })
            val input = canonical.toLibraryEntryInput(state.addonBaseUrl.takeIf { it.isNotBlank() })
            activeListPickerInput = input
            runCatching {
                libraryRepository.getMembershipSnapshot(input)
            }.onSuccess { snapshot ->
                _state.update { current ->
                    current.copy(
                        listPickerPending = false,
                        listPickerError = null,
                        listPickerMembership = mergeTrackingMembershipWithTabs(
                            tabs = current.libraryListTabs,
                            membership = snapshot.listMembership
                        )
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to load list picker for ${canonical.id}: ${error.message}")
                _state.update { current ->
                    current.copy(
                        listPickerPending = false,
                        listPickerError = error.message ?: appContext.getString(com.nuvio.tv.R.string.poster_options_error_load_lists_failed)
                    )
                }
            }
        }
    }

    fun toggleListMembership(listKey: String) {
        _state.update { current ->
            val nextMembership = toggleTrackingMembershipSelection(
                tabs = current.libraryListTabs,
                membership = current.listPickerMembership,
                listKey = listKey,
                contentType = current.listPickerContentType
            ) ?: return@update current
            current.copy(
                listPickerMembership = nextMembership,
                listPickerError = null
            )
        }
    }

    fun saveListPicker() {
        val state = _state.value
        if (state.listPickerPending) return
        if (state.librarySourceMode == LibrarySourceMode.LOCAL) return
        val input = activeListPickerInput ?: return
        val scope = this.scope ?: return

        _state.update { it.copy(listPickerPending = true, listPickerError = null) }
        scope.launch {
            runCatching {
                libraryRepository.applyMembershipChanges(
                    item = input,
                    changes = ListMembershipChanges(
                        desiredMembership = _state.value.listPickerMembership
                    )
                )
            }.onSuccess { result ->
                if (result.requiresRemovalConfirmation) {
                    pendingMembershipChanges = ListMembershipChanges(
                        desiredMembership = _state.value.listPickerMembership
                    )
                    _state.update {
                        it.copy(
                            listPickerPending = false,
                            removalConfirmations = result.requiredRemovalConfirmations
                        )
                    }
                } else {
                    closeListPickerAfterSave()
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to save list picker: ${error.message}")
                _state.update {
                    it.copy(
                        listPickerPending = false,
                        listPickerError = error.message ?: appContext.getString(com.nuvio.tv.R.string.poster_options_error_update_lists_failed)
                    )
                }
            }
        }
    }

    fun dismissListPicker() {
        activeListPickerInput = null
        pendingMembershipChanges = null
        _state.update {
            it.copy(
                listPickerActive = false,
                listPickerPending = false,
                listPickerError = null,
                listPickerTitle = null,
                listPickerContentType = null,
                removalConfirmations = emptyList()
            )
        }
    }

    fun confirmDestructiveRemoval() {
        val input = activeListPickerInput ?: return
        val changes = pendingMembershipChanges ?: return
        val confirmations = _state.value.removalConfirmations
        val scope = this.scope ?: return
        _state.update { it.copy(listPickerPending = true) }
        scope.launch {
            runCatching {
                libraryRepository.applyMembershipChanges(
                    item = input,
                    changes = changes,
                    confirmedRemovalProviders = confirmations.mapTo(linkedSetOf(), TrackingMembershipRemovalConfirmation::providerId)
                )
            }.onSuccess { result ->
                if (result.requiresRemovalConfirmation) {
                    _state.update {
                        it.copy(
                            listPickerPending = false,
                            removalConfirmations = result.requiredRemovalConfirmations
                        )
                    }
                } else {
                    closeListPickerAfterSave()
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        listPickerPending = false,
                        removalConfirmations = emptyList(),
                        listPickerError = error.message
                            ?: appContext.getString(com.nuvio.tv.R.string.poster_options_error_update_lists_failed)
                    )
                }
            }
        }
    }

    fun cancelDestructiveRemoval() {
        pendingMembershipChanges = null
        _state.update { it.copy(removalConfirmations = emptyList()) }
    }

    private fun closeListPickerAfterSave() {
        activeListPickerInput = null
        pendingMembershipChanges = null
        _state.update {
            it.copy(
                listPickerActive = false,
                listPickerPending = false,
                listPickerError = null,
                listPickerTitle = null,
                listPickerContentType = null,
                removalConfirmations = emptyList()
            )
        }
    }

    fun toggleMovieWatched() {
        val state = _state.value
        val item = state.target ?: return
        if (isSeriesLikeType(item.apiType)) return
        if (state.isWatchedPending) return
        val scope = this.scope ?: return

        val currentlyWatched = state.isWatched
        // Collect all known ID variants: original UI id (e.g. "tmdb:123"),
        // canonical id (e.g. "tt1979376"), and imdbId field if available.
        val optimisticIds = buildSet {
            add(item.id)
            state.originalItemId?.takeIf { it != item.id }?.let(::add)
            item.imdbId?.takeIf(String::isNotBlank)?.let(::add)
        }

        watchProgressRepository.applyOptimisticWatchedMovie(optimisticIds, add = !currentlyWatched)

        _state.update { it.copy(isWatchedPending = true) }
        scope.launch {
            val canonical = ensureCanonical() ?: return@launch
            runCatching {
                if (currentlyWatched) {
                    watchProgressRepository.removeFromHistory(canonical.id, videoId = canonical.imdbId)
                } else {
                    watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(canonical))
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to toggle watched for ${canonical.id}: ${error.message}")
                // Revert optimistic update on failure
                watchProgressRepository.revertOptimisticWatchedMovie(optimisticIds, add = !currentlyWatched)
            }
            _state.update { it.copy(isWatchedPending = false) }
        }
    }

    fun toggleSeriesWatched() {
        val state = _state.value
        val item = state.target ?: return
        val isSeries = item.apiType.equals("series", ignoreCase = true) ||
            item.apiType.equals("tv", ignoreCase = true) ||
            item.apiType.equals("anime", ignoreCase = true)
        if (!isSeries) return
        if (state.isWatchedPending) return
        val scope = this.scope ?: return

        val currentlyWatched = state.isWatched

        // Optimistic UI update — badge changes immediately
        val currentIds = watchedSeriesStateHolder.fullyWatchedSeriesIds.value
        val optimisticIds = if (currentlyWatched) currentIds - item.id else currentIds + item.id
        watchedSeriesStateHolder.update(optimisticIds)

        _state.update { it.copy(isWatchedPending = true) }
        scope.launch {
            val canonical = ensureCanonical() ?: return@launch
            runCatching {
                if (currentlyWatched) {
                    unmarkSeriesWatched(canonical)
                } else {
                    markSeriesWatched(canonical)
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to toggle series watched for ${canonical.id}: ${error.message}")
                // Revert optimistic update on failure
                watchedSeriesStateHolder.update(currentIds)
            }
            _state.update { it.copy(isWatchedPending = false) }
        }
    }

    private suspend fun markSeriesWatched(item: MetaPreview) {
        val episodes = fetchSeriesEpisodes(item).filter { it.season != null && it.episode != null && it.season != 0 }
        // Without episodes the only mark left is a show-level one, which trackers answer by marking
        // every episode watched. Fail instead so the optimistic badge is reverted.
        check(episodes.isNotEmpty()) { "No episodes found for ${item.id}" }

        val progressList = episodes.map { video ->
            WatchProgress(
                contentId = item.id,
                contentType = item.apiType,
                name = item.name,
                poster = item.poster,
                backdrop = item.backdropUrl,
                logo = item.logo,
                videoId = video.id,
                season = video.season,
                episode = video.episode,
                episodeTitle = video.title,
                position = 1L,
                duration = 1L,
                lastWatched = System.currentTimeMillis(),
                progressPercent = 100f
            )
        }
        watchProgressRepository.markAsCompletedBatch(progressList)
    }

    private suspend fun unmarkSeriesWatched(item: MetaPreview) {
        val episodes = fetchSeriesEpisodes(item).filter { it.season != null && it.episode != null && it.season != 0 }
        if (episodes.isEmpty()) {
            watchProgressRepository.removeFromHistory(item.id, videoId = item.imdbId, wholeSeries = true)
            return
        }

        watchProgressRepository.removeFromHistoryBatch(
            contentId = item.id,
            videoId = item.imdbId,
            episodes = episodes.map { Triple(it.season!!, it.episode!!, it.id) }
        )
    }

    private suspend fun fetchSeriesEpisodes(item: MetaPreview): List<Video> {
        val type = if (item.apiType.equals("tv", ignoreCase = true) ||
            item.apiType.equals("anime", ignoreCase = true)
        ) "series" else item.apiType
        val addonBaseUrl = _state.value.addonBaseUrl.takeIf { it.isNotBlank() }
        return lookUpSeriesEpisodes(
            metaRepository = metaRepository,
            item = item,
            type = type,
            addonBaseUrl = addonBaseUrl,
            resolve = { titleIdentity.resolve(it, addonBaseUrl) },
            pick = { it.videos }
        )
    }

    private var activeListPickerInput: LibraryEntryInput? = null

    companion object {
        private const val TAG = "PosterOptionsCtrl"
    }
}

private fun buildCompletedMovieProgress(item: MetaPreview): WatchProgress {
    return WatchProgress(
        contentId = item.id,
        contentType = item.apiType,
        name = item.name,
        poster = item.poster,
        backdrop = item.backdropUrl,
        logo = item.logo,
        videoId = item.id,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1L,
        duration = 1L,
        lastWatched = System.currentTimeMillis(),
        progressPercent = 100f
    )
}

private fun MetaPreview.toLibraryEntryInput(addonBaseUrl: String?): LibraryEntryInput {
    val year = Regex("(\\d{4})").find(releaseInfo ?: "")
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    val parsedIds = parseContentIds(id)
    // The library renders as portrait. If the source MetaPreview was built for landscape
    // display (e.g. TMDB collection / more-like-this), its `poster` field holds the backdrop.
    // Prefer `rawPosterUrl` (the proper portrait) when available so the saved entry isn't a
    // stretched/cropped landscape inside a portrait card.
    val isLandscapeSource = posterShape == com.nuvio.tv.domain.model.PosterShape.LANDSCAPE
    val portraitPoster = rawPosterUrl?.takeIf { it.isNotBlank() }
    val savedPoster = if (isLandscapeSource && portraitPoster != null) portraitPoster else poster
    val savedShape = if (isLandscapeSource) com.nuvio.tv.domain.model.PosterShape.POSTER else posterShape
    return LibraryEntryInput(
        itemId = id,
        itemType = apiType,
        title = name,
        year = year,
        traktId = parsedIds.trakt,
        simklId = parsedIds.simkl,
        imdbId = parsedIds.imdb,
        tmdbId = parsedIds.tmdb,
        poster = savedPoster,
        posterShape = savedShape,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl
    )
}
