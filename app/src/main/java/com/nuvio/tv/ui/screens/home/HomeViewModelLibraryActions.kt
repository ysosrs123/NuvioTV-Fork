package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.tv.core.tracking.mergeTrackingMembershipWithTabs
import com.nuvio.tv.core.tracking.toggleTrackingMembershipSelection
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.isSeriesLikeType
import com.nuvio.tv.ui.components.posteroptions.TitleAlias
import com.nuvio.tv.ui.components.posteroptions.isInLibraryOrAlias
import com.nuvio.tv.ui.components.posteroptions.toggleDefaultMerging
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun HomeViewModel.observeLibraryState() {
    viewModelScope.launch {
        libraryRepository.sourceMode
            .distinctUntilChanged()
            .collectLatest { sourceMode ->
                if (sourceMode == LibrarySourceMode.LOCAL) {
                    activePosterListPickerInput = null
                    pendingPosterListPickerChanges = null
                }
                _uiState.update { state ->
                    val resetPickerState = sourceMode == LibrarySourceMode.LOCAL
                    val updatedState = state.copy(
                        librarySourceMode = sourceMode,
                        showPosterListPicker = if (resetPickerState) false else state.showPosterListPicker,
                        posterListPickerPending = if (resetPickerState) false else state.posterListPickerPending,
                        posterListPickerError = if (resetPickerState) null else state.posterListPickerError,
                        posterListPickerTitle = if (resetPickerState) null else state.posterListPickerTitle,
                        posterListPickerContentType = if (resetPickerState) null else state.posterListPickerContentType,
                        posterListPickerRemovalConfirmations = if (resetPickerState) {
                            emptyList()
                        } else {
                            state.posterListPickerRemovalConfirmations
                        },
                        posterListPickerMembership = if (resetPickerState) {
                            emptyMap()
                        } else {
                            state.posterListPickerMembership
                        }
                    )
                    if (updatedState == state) state else updatedState
                }
            }
    }

    viewModelScope.launch {
        libraryRepository.membershipListTabs
            .distinctUntilChanged()
            .collectLatest { tabs ->
                _uiState.update { state ->
                    val filteredMembership = mergeTrackingMembershipWithTabs(
                        tabs = tabs,
                        membership = state.posterListPickerMembership
                    )
                    if (
                        state.libraryListTabs == tabs &&
                        state.posterListPickerMembership == filteredMembership
                    ) {
                        state
                    } else {
                        state.copy(
                            libraryListTabs = tabs,
                            posterListPickerMembership = filteredMembership
                        )
                    }
                }
            }
    }
}

fun HomeViewModel.refreshPosterLibraryStatus(item: MetaPreview, addonBaseUrl: String? = null) {
    val statusKey = homeItemStatusKey(item.id, item.apiType)
    viewModelScope.launch {
        runCatching {
            val saved = posterTitleIdentity.resolve(item, addonBaseUrl)
            val isInLibrary = libraryRepository
                .isInLibraryOrAlias(saved.id, saved.apiType, TitleAlias.between(saved, item))
                .first()
            _uiState.update { state ->
                if (state.posterLibraryMembership[statusKey] == isInLibrary) state
                else state.copy(
                    posterLibraryMembership = state.posterLibraryMembership + (statusKey to isInLibrary)
                )
            }
        }
    }
}

fun HomeViewModel.togglePosterLibrary(item: MetaPreview, addonBaseUrl: String?) {
    val statusKey = homeItemStatusKey(item.id, item.apiType)
    if (statusKey in _uiState.value.posterLibraryPending) return

    _uiState.update { state ->
        state.copy(posterLibraryPending = state.posterLibraryPending + statusKey)
    }

    viewModelScope.launch {
        val saved = runCatching { posterTitleIdentity.resolve(item, addonBaseUrl) }.getOrDefault(item)
        val alias = TitleAlias.between(saved, item)
        runCatching {
            libraryRepository.toggleDefaultMerging(saved.toLibraryEntryInput(addonBaseUrl), alias)
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to toggle poster library for ${saved.id}: ${error.message}")
        }
        runCatching {
            val isNowInLibrary = libraryRepository.isInLibraryOrAlias(saved.id, saved.apiType, alias).first()
            _uiState.update { state ->
                state.copy(
                    posterLibraryMembership = state.posterLibraryMembership + (statusKey to isNowInLibrary)
                )
            }
        }
        _uiState.update { state ->
            state.copy(posterLibraryPending = state.posterLibraryPending - statusKey)
        }
    }
}

fun HomeViewModel.openPosterListPicker(item: MetaPreview, addonBaseUrl: String?) {
    if (_uiState.value.librarySourceMode == LibrarySourceMode.LOCAL) {
        togglePosterLibrary(item, addonBaseUrl)
        return
    }
    activePosterListPickerInput = null
    activePosterListPickerStatusKey = homeItemStatusKey(item.id, item.apiType)

    _uiState.update { state ->
        state.copy(
            showPosterListPicker = true,
            posterListPickerTitle = item.name,
            posterListPickerContentType = item.apiType,
            posterListPickerPending = true,
            posterListPickerError = null,
            posterListPickerRemovalConfirmations = emptyList(),
            posterListPickerMembership = mergeTrackingMembershipWithTabs(
                tabs = state.libraryListTabs,
                membership = emptyMap()
            )
        )
    }

    viewModelScope.launch {
        runCatching {
            val saved = posterTitleIdentity.resolve(item, addonBaseUrl)
            val input = saved.toLibraryEntryInput(addonBaseUrl)
            activePosterListPickerInput = input
            libraryRepository.getMembershipSnapshot(input)
        }.onSuccess { snapshot ->
            _uiState.update { state ->
                state.copy(
                    showPosterListPicker = true,
                    posterListPickerPending = false,
                    posterListPickerError = null,
                    posterListPickerMembership = mergeTrackingMembershipWithTabs(
                        tabs = state.libraryListTabs,
                        membership = snapshot.listMembership
                    )
                )
            }
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to load poster list picker for ${item.id}: ${error.message}")
            _uiState.update { state ->
                state.copy(
                    showPosterListPicker = true,
                    posterListPickerPending = false,
                    posterListPickerError = error.message ?: appContext.getString(com.nuvio.tv.R.string.home_poster_lists_error_load_failed)
                )
            }
        }
    }
}

fun HomeViewModel.togglePosterListPickerMembership(listKey: String) {
    _uiState.update { state ->
        val updatedMembership = toggleTrackingMembershipSelection(
            tabs = state.libraryListTabs,
            membership = state.posterListPickerMembership,
            listKey = listKey,
            contentType = state.posterListPickerContentType
        ) ?: return@update state
        state.copy(
            posterListPickerMembership = updatedMembership,
            posterListPickerError = null
        )
    }
}

fun HomeViewModel.savePosterListPickerMembership() {
    if (_uiState.value.posterListPickerPending) return
    if (_uiState.value.librarySourceMode == LibrarySourceMode.LOCAL) return
    val input = activePosterListPickerInput ?: return
    val changes = ListMembershipChanges(
        desiredMembership = _uiState.value.posterListPickerMembership
    )

    viewModelScope.launch {
        _uiState.update { state ->
            state.copy(
                posterListPickerPending = true,
                posterListPickerError = null
            )
        }

        runCatching {
            libraryRepository.applyMembershipChanges(
                item = input,
                changes = changes
            )
        }.onSuccess { result ->
            if (result.requiresRemovalConfirmation) {
                pendingPosterListPickerChanges = changes
                _uiState.update { state ->
                    state.copy(
                        posterListPickerPending = false,
                        posterListPickerRemovalConfirmations = result.requiredRemovalConfirmations
                    )
                }
            } else {
                closePosterListPickerAfterSave(changes)
            }
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to save poster list picker: ${error.message}")
            _uiState.update { state ->
                state.copy(
                    posterListPickerPending = false,
                    posterListPickerError = error.message ?: appContext.getString(com.nuvio.tv.R.string.home_poster_lists_error_update_failed)
                )
            }
        }
    }
}

fun HomeViewModel.confirmPosterListPickerRemoval() {
    if (_uiState.value.posterListPickerPending) return
    val input = activePosterListPickerInput ?: return
    val changes = pendingPosterListPickerChanges ?: return
    val confirmations = _uiState.value.posterListPickerRemovalConfirmations
    if (confirmations.isEmpty()) return

    viewModelScope.launch {
        _uiState.update { it.copy(posterListPickerPending = true) }
        runCatching {
            libraryRepository.applyMembershipChanges(
                item = input,
                changes = changes,
                confirmedRemovalProviders = confirmations.mapTo(
                    linkedSetOf(),
                    TrackingMembershipRemovalConfirmation::providerId
                )
            )
        }.onSuccess { result ->
            if (result.requiresRemovalConfirmation) {
                _uiState.update {
                    it.copy(
                        posterListPickerPending = false,
                        posterListPickerRemovalConfirmations = result.requiredRemovalConfirmations
                    )
                }
            } else {
                closePosterListPickerAfterSave(changes)
            }
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to confirm poster list removal: ${error.message}")
            _uiState.update {
                it.copy(
                    posterListPickerPending = false,
                    posterListPickerRemovalConfirmations = emptyList(),
                    posterListPickerError = error.message
                        ?: appContext.getString(com.nuvio.tv.R.string.home_poster_lists_error_update_failed)
                )
            }
        }
    }
}

fun HomeViewModel.cancelPosterListPickerRemoval() {
    pendingPosterListPickerChanges = null
    _uiState.update { it.copy(posterListPickerRemovalConfirmations = emptyList()) }
}

fun HomeViewModel.dismissPosterListPicker() {
    activePosterListPickerInput = null
    activePosterListPickerStatusKey = null
    pendingPosterListPickerChanges = null
    _uiState.update { state ->
        state.copy(
            showPosterListPicker = false,
            posterListPickerPending = false,
            posterListPickerError = null,
            posterListPickerTitle = null,
            posterListPickerContentType = null,
            posterListPickerRemovalConfirmations = emptyList()
        )
    }
}

private fun HomeViewModel.closePosterListPickerAfterSave(changes: ListMembershipChanges) {
    val statusKey = activePosterListPickerStatusKey
        ?: activePosterListPickerInput?.let { homeItemStatusKey(it.itemId, it.itemType) }
    val anyListSelected = changes.desiredMembership.values.any { it }
    activePosterListPickerInput = null
    activePosterListPickerStatusKey = null
    pendingPosterListPickerChanges = null
    _uiState.update { state ->
        val updatedMembership = statusKey?.let { key ->
            state.posterLibraryMembership + (key to anyListSelected)
        } ?: state.posterLibraryMembership
        state.copy(
            showPosterListPicker = false,
            posterListPickerPending = false,
            posterListPickerError = null,
            posterListPickerTitle = null,
            posterListPickerContentType = null,
            posterListPickerRemovalConfirmations = emptyList(),
            posterLibraryMembership = updatedMembership
        )
    }
}

fun HomeViewModel.togglePosterMovieWatched(item: MetaPreview) {
    if (isSeriesLikeType(item.apiType)) return
    val statusKey = homeItemStatusKey(item.id, item.apiType)
    if (statusKey in _uiState.value.movieWatchedPending) return

    _uiState.update { state ->
        state.copy(movieWatchedPending = state.movieWatchedPending + statusKey)
    }

    viewModelScope.launch {
        val currentlyWatched = _uiState.value.movieWatchedStatus[statusKey] == true
        runCatching {
            if (currentlyWatched) {
                watchProgressRepository.removeFromHistory(item.id, videoId = item.imdbId)
            } else {
                watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(item))
            }
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to toggle poster watched status for ${item.id}: ${error.message}")
        }
        _uiState.update { state ->
            state.copy(movieWatchedPending = state.movieWatchedPending - statusKey)
        }
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

fun HomeViewModel.togglePosterSeriesWatched(item: MetaPreview, addonBaseUrl: String? = null) {
    val isSeries = item.apiType.equals("series", ignoreCase = true) ||
        item.apiType.equals("tv", ignoreCase = true) ||
        item.apiType.equals("anime", ignoreCase = true)
    if (!isSeries) return
    val statusKey = homeItemStatusKey(item.id, item.apiType)
    if (statusKey in _uiState.value.movieWatchedPending) return

    val currentlyWatched = _uiState.value.movieWatchedStatus[statusKey] == true

    // Optimistically update the UI immediately
    _uiState.update { state ->
        state.copy(
            movieWatchedPending = state.movieWatchedPending + statusKey,
            movieWatchedStatus = state.movieWatchedStatus + (statusKey to !currentlyWatched)
        )
    }

    viewModelScope.launch {
        runCatching {
            if (currentlyWatched) {
                unmarkSeriesWatched(item, addonBaseUrl)
            } else {
                markSeriesWatched(item, addonBaseUrl)
            }
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to toggle series watched for ${item.id}: ${error.message}")
            // Revert optimistic update on failure
            _uiState.update { state ->
                state.copy(
                    movieWatchedStatus = state.movieWatchedStatus + (statusKey to currentlyWatched)
                )
            }
        }
        _uiState.update { state ->
            state.copy(movieWatchedPending = state.movieWatchedPending - statusKey)
        }
    }
}

private suspend fun HomeViewModel.markSeriesWatched(item: MetaPreview, addonBaseUrl: String?) {
    val episodes = fetchSeriesEpisodes(item, addonBaseUrl)
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
    fullyWatchedSeriesIds.updateWithValidation(
        fullyWatchedSeriesIds.fullyWatchedSeriesIds.value + item.id,
        setOf(item.id)
    )
}

private suspend fun HomeViewModel.unmarkSeriesWatched(item: MetaPreview, addonBaseUrl: String?) {
    val episodes = fetchSeriesEpisodes(item, addonBaseUrl)
    if (episodes.isEmpty()) {
        watchProgressRepository.removeFromHistory(item.id, videoId = item.imdbId, wholeSeries = true)
        return
    }

    watchProgressRepository.removeFromHistoryBatch(
        contentId = item.id,
        videoId = item.imdbId,
        episodes = episodes.map { Triple(it.season!!, it.episode!!, it.id) }
    )
    fullyWatchedSeriesIds.updateWithValidation(
        fullyWatchedSeriesIds.fullyWatchedSeriesIds.value - item.id,
        setOf(item.id)
    )
}

private suspend fun HomeViewModel.fetchSeriesEpisodes(
    item: MetaPreview,
    addonBaseUrl: String?
): List<com.nuvio.tv.domain.model.Video> {
    val type = if (item.apiType.equals("tv", ignoreCase = true)) "series" else item.apiType
    return com.nuvio.tv.ui.components.posteroptions.lookUpSeriesEpisodes(
        metaRepository = metaRepository,
        item = item,
        type = type,
        addonBaseUrl = addonBaseUrl,
        resolve = { posterTitleIdentity.resolve(it, addonBaseUrl) },
        pick = { it.watchableEpisodes() }
    )
}

private fun MetaPreview.toLibraryEntryInput(addonBaseUrl: String?): LibraryEntryInput {
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
        simklId = parsedIds.simkl,
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
        addonBaseUrl = addonBaseUrl
    )
}
