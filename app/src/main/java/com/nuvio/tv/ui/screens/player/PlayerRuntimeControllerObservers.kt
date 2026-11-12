package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.build.IssueReports
import com.nuvio.tv.core.player.OpenSubtitlesHasher
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.data.local.playbackSettingsChanges
import com.nuvio.tv.data.mediaserver.mergeServerSkipIntervals
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.enabledAddons
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.yield

internal data class SubtitleFetchRequest(
    val type: String,
    val id: String,
    val videoId: String?
)

internal fun PlayerRuntimeController.buildSubtitleFetchRequest(): SubtitleFetchRequest? {
    val id = contentId ?: return null
    val type = contentType ?: return null
    return SubtitleFetchRequest(
        type = type.lowercase(),
        id = id,
        videoId = currentVideoId
    )
}

internal suspend fun PlayerRuntimeController.fetchAddonSubtitlesNow(
    onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)? = null,
    onSubtitlesEmitted: ((List<Subtitle>) -> Unit)? = null
): List<Subtitle> {
    // Addon subtitles are opt-in on this fork; embedded-only otherwise.
    // This var's only writer is the settings collector, so a
    // fetch that runs before its first emission reads a stale false and the whole
    // session silently loses external subtitles with no retry (the startup phase
    // then reports fetchCompleted with an empty list, skipping the fallback).
    // Confirm against the store before denying; zero cost when already enabled.
    if (!addonSubtitlesEnabled) {
        addonSubtitlesEnabled =
            playerSettingsDataStore.playerSettings.firstOrNull()?.addonSubtitlesEnabled == true
        // Stream sidecar subtitles ride on the stream itself,
        // not on an addon fetch, so they are surfaced even when addon subtitles are off.
        if (!addonSubtitlesEnabled) return withStreamSidecarSubtitles(emptyList())
    }
    val request = buildSubtitleFetchRequest() ?: return withStreamSidecarSubtitles(emptyList())
    val installedAddonOrder = addonRepository.getInstalledAddons().firstOrNull()
        ?.enabledAddons()
        ?.map { it.displayName }
        .orEmpty()
    _uiState.update { it.copy(installedSubtitleAddonOrder = installedAddonOrder) }

    // Compute hash lazily for providers that support OpenSubtitles-style matching.
    if (currentVideoHash == null && currentStreamUrl.isNotBlank()) {
        val result = OpenSubtitlesHasher.compute(currentStreamUrl, currentHeaders)
        if (result != null) {
            currentVideoHash = result.hash
            if (currentVideoSize == null) currentVideoSize = result.fileSize
            val key = streamCacheKey
            if (key != null) {
                val state = _uiState.value
                val torrentInfoHash = currentInfoHash
                if (isTorrentStream && torrentInfoHash != null) {
                    streamLinkCacheDataStore.save(
                        contentKey = key,
                        url = "",
                        streamName = state.currentStreamName ?: title,
                        headers = emptyMap(),
                        filename = currentFilename,
                        videoHash = currentVideoHash,
                        videoSize = currentVideoSize,
                        infoHash = torrentInfoHash,
                        fileIdx = currentFileIdx,
                        sources = currentTorrentSources,
                        bingeGroup = currentStreamBingeGroup,
                        contentLanguage = contentLanguage,
                        year = year,
                        addonName = currentAddonName,
                        addonLogo = currentAddonLogo,
                        streamDescription = currentStreamDescription
                    )
                } else if (currentStreamUrl.isNotBlank() && !isServerStream) {
                    streamLinkCacheDataStore.save(
                        contentKey = key,
                        url = currentStreamUrl,
                        streamName = state.currentStreamName ?: title,
                        headers = currentHeaders,
                        filename = currentFilename,
                        videoHash = currentVideoHash,
                        videoSize = currentVideoSize,
                        infoHash = state.currentStreamInfoHash,
                        fileIdx = state.currentStreamFileIdx,
                        bingeGroup = currentStreamBingeGroup,
                        contentLanguage = contentLanguage,
                        year = year,
                        addonName = currentAddonName,
                        addonLogo = currentAddonLogo,
                        streamDescription = currentStreamDescription
                    )
                }
            }
        }
    }

    return withStreamSidecarSubtitles(
        subtitleRepository.getSubtitles(
            type = request.type,
            id = request.id,
            videoId = request.videoId,
            videoHash = currentVideoHash,
            videoSize = currentVideoSize,
            filename = currentFilename,
            onProgress = onProgress,
            onSubtitlesEmitted = { currentList ->
                onSubtitlesEmitted?.invoke(withStreamSidecarSubtitles(currentList))
            }
        )
    )
}

internal fun PlayerRuntimeController.fetchAddonSubtitles() {
    if (buildSubtitleFetchRequest() == null) {
        publishStreamSidecarSubtitlesWithoutAddonFetch()
        return
    }

    scope.launch {
        // The gate check runs inside the coroutine so it can
        // suspend to confirm against the store (see fetchAddonSubtitlesNow).
        if (!addonSubtitlesEnabled &&
            playerSettingsDataStore.playerSettings.firstOrNull()?.addonSubtitlesEnabled != true
        ) {
            publishStreamSidecarSubtitlesWithoutAddonFetch()
            return@launch
        }
        _uiState.update {
            it.copy(
                isLoadingAddonSubtitles = true,
                addonSubtitlesError = null,
                addonSubtitles = if (streamSubtitles.isNotEmpty()) {
                    filterToVisibleAddonSubtitles(streamSubtitles)
                } else {
                    it.addonSubtitles
                }
            )
        }

        try {
            val subtitles = fetchAddonSubtitlesNow(
                onSubtitlesEmitted = { currentList ->
                    _uiState.update { it.copy(addonSubtitles = currentList) }
                }
            )
            val visibleSubtitles = filterToVisibleAddonSubtitles(subtitles)
            Log.d(PlayerRuntimeController.TAG, "fetchAddonSubtitles done: ${subtitles.size} subs, visible=${visibleSubtitles.size}, persistedPref=${persistedTrackPreference?.subtitle?.javaClass?.simpleName}")
            _uiState.update {
                it.copy(
                    addonSubtitles = visibleSubtitles,
                    isLoadingAddonSubtitles = false
                )
            }
            val pendingAddon = pendingRestoredAddonSubtitle
            if (pendingAddon != null) {
                val match = visibleSubtitles.firstOrNull { it.id == pendingAddon.id }
                    ?: visibleSubtitles.firstOrNull { PlayerSubtitleUtils.matchesLanguageCode(it.lang, pendingAddon.lang) }
                if (match != null) {
                    autoSelectAddonSubtitleDeferringReload(match)
                    // After a player rebuild this reapplies the earlier result without analysing again.
                    if (deferredAutoAddonSubtitle == null) runSelectedAutomaticSubtitleSync(match) // AutoSync hook
                    return@launch
                }
            }
            applyPersistedTrackPreference(
                audioTracks = _uiState.value.audioTracks,
                subtitleTracks = _uiState.value.subtitleTracks
            )
            tryAutoSelectPreferredSubtitleFromAvailableTracks()
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoadingAddonSubtitles = false,
                    addonSubtitlesError = e.message,
                    addonSubtitles = if (streamSubtitles.isNotEmpty()) {
                        filterToVisibleAddonSubtitles(streamSubtitles)
                    } else {
                        it.addonSubtitles
                    }
                )
            }
        }
    }
}

// An addon subtitle that can neither be drawn on top of the video nor switched to
// in place needs a full setMediaSource+prepare at the current position (see
// selectAddonSubtitle), which latches bad frame pacing on some vendor HALs (the
// Prism+ class). Auto-restore never triggers that while the user is watching:
// such a pick waits for the next pause. Explicit user picks are untouched.
internal fun addonSubtitleAttachIsSeamless(
    usingMpv: Boolean,
    preAttached: Boolean,
    drawnOnTop: Boolean,
    playing: Boolean
): Boolean = usingMpv || preAttached || drawnOnTop || !playing

internal fun PlayerRuntimeController.autoSelectAddonSubtitleDeferringReload(subtitle: Subtitle) {
    val seamless = addonSubtitleAttachIsSeamless(
        usingMpv = isUsingMpvEngine(),
        preAttached = attachedAddonSubtitleKeys.contains(addonSubtitleKey(subtitle)),
        drawnOnTop = canAttachAddonSubtitleViaSidecar(subtitle),
        playing = isPlaybackCurrentlyPlaying()
    )
    if (seamless) {
        autoSubtitleSelected = true
        selectAddonSubtitle(subtitle)
        _uiState.update { it.copy(selectedAddonSubtitle = subtitle, selectedSubtitleTrackIndex = -1) }
        return
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "deferred addon subtitle attach (would reload mid-play): id=${subtitle.id} lang=${subtitle.lang}"
    )
    deferredAutoAddonSubtitle = subtitle
}

internal fun PlayerRuntimeController.maybeAttachDeferredAddonSubtitle() {
    val subtitle = deferredAutoAddonSubtitle ?: return
    deferredAutoAddonSubtitle = null
    Log.i(
        PlayerRuntimeController.TAG,
        "attaching deferred addon subtitle at pause: id=${subtitle.id} lang=${subtitle.lang}"
    )
    autoSubtitleSelected = true
    selectAddonSubtitle(subtitle)
    _uiState.update { it.copy(selectedAddonSubtitle = subtitle, selectedSubtitleTrackIndex = -1) }
    runSelectedAutomaticSubtitleSync(subtitle)
}

private fun PlayerRuntimeController.publishStreamSidecarSubtitlesWithoutAddonFetch() {
    if (streamSubtitles.isEmpty()) return
    _uiState.update {
        it.copy(
            addonSubtitles = filterToVisibleAddonSubtitles(streamSubtitles),
            isLoadingAddonSubtitles = false,
            addonSubtitlesError = null
        )
    }
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
}

internal fun PlayerRuntimeController.refreshSubtitlesForCurrentEpisode() {
    val keepDisabled = subtitleDisabledByPersistedPreference ||
        (rememberedTrackPreference?.subtitle == PlayerRuntimeController.RememberedSubtitleSelection.Disabled)
    if (!isUserExplicitSubtitleSelection && !keepDisabled) {
        rememberedTrackPreference = rememberedTrackPreference?.copy(subtitle = null)
    }
    autoSubtitleSelected = keepDisabled
    isUserExplicitSubtitleSelection = false
    subtitleDisabledByPersistedPreference = keepDisabled
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    deferredAutoAddonSubtitle = null
    hasScannedTextTracksOnce = false
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    resetSubtitleAutoSyncState()
    attachedAddonSubtitleKeys = emptySet()
    stopSidecarAddonSubtitle(clearView = true)
    _uiState.update {
        it.copy(
            addonSubtitles = emptyList(),
            selectedAddonSubtitle = null,
            selectedSubtitleTrackIndex = if (keepDisabled) -1 else -1,
            isLoadingAddonSubtitles = true,
            addonSubtitlesError = null
        )
    }
    fetchAddonSubtitles()
}

internal fun PlayerRuntimeController.withStreamSidecarSubtitles(addonSubtitles: List<Subtitle>): List<Subtitle> {
    if (streamSubtitles.isEmpty()) return filterToVisibleAddonSubtitles(addonSubtitles)
    return filterToVisibleAddonSubtitles(
        (streamSubtitles + addonSubtitles).distinctBy { addonSubtitleKey(it) }
    )
}

internal fun PlayerRuntimeController.filterToVisibleAddonSubtitles(
    subtitles: List<Subtitle>
): List<Subtitle> {
    val style = _uiState.value.subtitleStyle
    if (!style.showOnlyPreferredLanguages) return subtitles

    val preferredTargets = when (PlayerSubtitleUtils.normalizeLanguageCode(style.preferredLanguage)) {
        "none" -> listOfNotNull(
            style.preferredLanguageBeforeOff?.takeIf { it.isNotBlank() },
            style.secondaryPreferredLanguage?.takeIf { it.isNotBlank() },
            style.tertiaryPreferredLanguage?.takeIf { it.isNotBlank() },
            if (style.useForcedSubtitles) {
                selectedAudioTrackForSubtitleMatching(_uiState.value)
                    ?.takeIf { selectedAudioMatchesResolvedPreferredAudio(it) }
                    ?.let { selectedAudioLanguageTarget(it) }
            } else {
                null
            }
        )
        else -> listOfNotNull(
            style.preferredLanguage,
            style.secondaryPreferredLanguage?.takeIf { it.isNotBlank() },
            style.tertiaryPreferredLanguage?.takeIf { it.isNotBlank() }
        )
    }.map { PlayerSubtitleUtils.normalizeLanguageCode(it) }
        .distinct()

    if (preferredTargets.isEmpty()) {
        return if (
            style.useForcedSubtitles &&
            PlayerSubtitleUtils.normalizeLanguageCode(style.preferredLanguage) == "none" &&
            selectedAudioTrackForSubtitleMatching(_uiState.value) == null
        ) {
            subtitles
        } else {
            emptyList()
        }
    }

    return subtitles.filter { subtitle ->
        preferredTargets.any { target ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target)
        }
    }
}

internal fun PlayerRuntimeController.observeBlurUnwatchedEpisodes() {
    scope.launch {
        layoutPreferenceDataStore.blurUnwatchedEpisodes.collectLatest { enabled ->
            _uiState.update { it.copy(blurUnwatchedEpisodes = enabled) }
        }
    }
}

internal fun PlayerRuntimeController.observeEpisodeOptionsOverlayStyle() {
    scope.launch {
        layoutPreferenceDataStore.episodeOptionsOverlayStyle.collectLatest { style ->
            _uiState.update { it.copy(episodeOptionsOverlayStyle = style) }
        }
    }
}

internal fun PlayerRuntimeController.observeEpisodeWatchProgress() {
    val id = contentId ?: return
    val type = contentType ?: return
    if (!type.equals("series", true) && !type.equals("tv", true)) return
    scope.launch {
        episodeShufflePlayback.observe(profileId, id, type).collectLatest { state ->
            playbackShuffleState = state
            _uiState.update { it.copy(episodeWatchProgressMap = state.progress, watchedEpisodeKeys = state.watched) }
            recomputeNextEpisode(resetVisibility = false)
        }
    }
}

internal fun PlayerRuntimeController.observeSubtitleSettings() {
    scope.launch {
        playerSettingsDataStore.runtimePlayerSettings
            .onEach { snapshot ->
                currentPlayerSettingsForReport = snapshot.settings
                hasObservedPlayerSettings = true
                _uiState.update { it.copy(controlLayout = snapshot.settings.controlLayout) }
            }
            .playbackSettingsChanges()
            .collect { snapshot ->
            val settings = snapshot.settings
            val currentState = _uiState.value
            val showOnlyPreferredLanguagesChanged =
                currentState.subtitleStyle.showOnlyPreferredLanguages != settings.subtitleStyle.showOnlyPreferredLanguages
            val wasRememberingAudioDelayPerDevice = rememberAudioDelayPerDeviceEnabled
            rememberAudioDelayPerDeviceEnabled = settings.rememberAudioDelayPerDevice
            val resolvedInternalPlayerEngine =
                runtimeInternalPlayerEngineOverride ?: resolvedAutoPlayerEngine ?: settings.internalPlayerEngine
            val resolvedAudioAmplificationDb = when {
                !hasInitializedAudioAmplificationForSession -> {
                    hasInitializedAudioAmplificationForSession = true
                    if (settings.persistAudioAmplification) {
                        settings.audioAmplificationDb
                    } else {
                        AUDIO_AMPLIFICATION_MIN_DB
                    }
                }
                settings.persistAudioAmplification -> settings.audioAmplificationDb
                else -> currentState.audioAmplificationDb
            }
            val resolvedCenterMixLevelDb = when {
                !hasInitializedCenterMixForSession -> {
                    hasInitializedCenterMixForSession = true
                    if (settings.persistAudioAmplification) {
                        settings.centerMixLevelDb
                    } else {
                        0
                    }
                }
                settings.persistAudioAmplification -> settings.centerMixLevelDb
                else -> currentState.centerMixLevelDb
            }

            _uiState.update { state ->
                val shouldShowOverlay = when {
                    !settings.loadingOverlayEnabled -> false
                    !hasRenderedFirstFrame && state.isBuffering -> true
                    else -> state.showLoadingOverlay
                }

                state.copy(
                    subtitleStyle = settings.subtitleStyle,
                    dimHdrOverlays = settings.dimHdrOverlays,
                    loadingOverlayEnabled = settings.loadingOverlayEnabled,
                    showPlayerLoadingStatus = settings.showPlayerLoadingStatus,
                    showPlayerLoadingSource = settings.showPlayerLoadingSource,
                    playbackIssueReportsEnabled = IssueReports.available && settings.playbackIssueReportsEnabled,
                    showLoadingOverlay = shouldShowOverlay,
                    loadingIssueReportVisible = if (IssueReports.available && settings.playbackIssueReportsEnabled) {
                        state.loadingIssueReportVisible
                    } else {
                        false
                    },
                    pauseOverlayEnabled = settings.pauseOverlayEnabled,
                    osdClockEnabled = settings.osdClockEnabled,
                    nightModeLastOn = settings.nightModeLastOn,
                    internalPlayerEngine = resolvedInternalPlayerEngine,
                    frameRateMatchingMode = settings.frameRateMatchingMode,
                    tunnelingEnabled = settings.effectiveTunnelingEnabled &&
                            !tunnelingBlockedByUsbAudio &&
                            resolvedInternalPlayerEngine != InternalPlayerEngine.MVP_PLAYER,
                    persistAudioAmplification = settings.persistAudioAmplification,
                    audioAmplificationDb = resolvedAudioAmplificationDb,
                    centerMixLevelDb = resolvedCenterMixLevelDb
                )
            }

            if (resolvedAudioAmplificationDb != currentState.audioAmplificationDb) {
                applyAudioAmplification(resolvedAudioAmplificationDb)
            }
            if (settings.nightMode != currentState.nightMode) {
                applyNightMode(settings.nightMode)
            }
            if (resolvedCenterMixLevelDb != currentState.centerMixLevelDb) {
                applyCenterMixLevel(resolvedCenterMixLevelDb)
            }

            if (settings.rememberAudioDelayPerDevice && !wasRememberingAudioDelayPerDevice) {
                applyStoredAudioDelayForCurrentRouteIfEnabled()
            }

            bufferLogsEnabled = settings.enableBufferLogs
            if (settings.frameRateMatchingMode == FrameRateMatchingMode.OFF) {
                frameRateProbeJob?.cancel()
                _uiState.update {
                    it.clearFrameRateDetection().copy(afrProbeRunning = false)
                }
            }

            if (!settings.pauseOverlayEnabled) {
                if (!pauseDetails.holdsOverlay(_uiState.value.showPauseOverlay)) cancelPauseOverlay()
            } else if (!_uiState.value.isPlaying &&
                !_uiState.value.showPauseOverlay && pauseOverlayJob == null &&
                userPausedManually && hasRenderedFirstFrame
            ) {
                schedulePauseOverlay()
            }
            streamReuseLastLinkEnabled = settings.streamReuseLastLinkEnabled
            autoSwitchInternalPlayerOnErrorEnabled = settings.autoSwitchInternalPlayerOnError
            addonSubtitlesEnabled = settings.addonSubtitlesEnabled
            currentInternalPlayerEngine = resolvedInternalPlayerEngine
            streamAutoPlayModeSetting = settings.streamAutoPlayMode
            streamAutoPlayNextEpisodeEnabledSetting = settings.streamAutoPlayNextEpisodeEnabled
            streamAutoPlayTimeoutSecondsSetting = settings.streamAutoPlayTimeoutSeconds
            preloadNextEpisodeSourcesSetting = false // next-episode sources come from the binge lookahead
            _uiState.update {
                it.copy(
                    streamAutoPlayMode = settings.streamAutoPlayMode,
                    streamAutoPlayNextEpisodeEnabled = settings.streamAutoPlayNextEpisodeEnabled,
                    streamAutoPlayPreferBingeGroupForNextEpisode = settings.streamAutoPlayPreferBingeGroupForNextEpisode
                )
            }
            streamAutoPlayPreferBingeGroupForNextEpisodeSetting =
                settings.streamAutoPlayPreferBingeGroupForNextEpisode
            nextEpisodeThresholdModeSetting = settings.nextEpisodeThresholdMode
            nextEpisodeThresholdPercentSetting = settings.nextEpisodeThresholdPercent
            nextEpisodeThresholdMinutesBeforeEndSetting = settings.nextEpisodeThresholdMinutesBeforeEnd
            stillWatchingEnabledSetting = settings.stillWatchingEnabled
            stillWatchingEpisodeThresholdSetting = settings.stillWatchingEpisodeThreshold

            // VOD cache config is gated by the "Custom Playback Buffers" master.
            // When the master is off the cache is disabled at player build time, so
            // don't push live size updates to it here either (keeps the factory from
            // carrying cache config the master has turned off).
            if (settings.bufferEngineEnabled) {
                mediaSourceFactory.vodCacheSizeMode = settings.vodCacheSizeMode
                mediaSourceFactory.vodCacheSizeMb = settings.vodCacheSizeMb
            }

            val previousMpvHardwareDecodeMode = mpvHardwareDecodeModeSetting
            mpvHardwareDecodeModeSetting = settings.mpvHardwareDecodeMode
            if (isUsingMpvEngine() && previousMpvHardwareDecodeMode != mpvHardwareDecodeModeSetting) {
                mpvView?.applyHardwareDecodeMode(mpvHardwareDecodeModeSetting)
            }

            val resolvedAudioLanguages = resolvePreferredAudioLanguages(
                preferredAudioLanguage = settings.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = settings.secondaryPreferredAudioLanguage,
                deviceLanguages = resolveDeviceAudioLanguages(),
                contentOriginalLanguage = contentLanguage
            )
            if (resolvedAudioLanguages != mpvPreferredAudioLanguages) {
                mpvPreferredAudioLanguages = resolvedAudioLanguages
                if (isUsingMpvEngine()) {
                    mpvView?.applyAudioLanguagePreferences(resolvedAudioLanguages)
                    updateMpvAvailableTracks()
                }
            }

            applySubtitlePreferences(
                settings.subtitleStyle.preferredLanguage,
                settings.subtitleStyle.secondaryPreferredLanguage,
                settings.subtitleStyle.tertiaryPreferredLanguage
            )
            val subtitlePreferenceChanged =
                lastSubtitlePreferredLanguage != settings.subtitleStyle.preferredLanguage ||
                    lastSubtitleSecondaryLanguage != settings.subtitleStyle.secondaryPreferredLanguage ||
                    lastSubtitleTertiaryLanguage != settings.subtitleStyle.tertiaryPreferredLanguage ||
                    lastUseForcedSubtitles != settings.subtitleStyle.useForcedSubtitles
            if (subtitlePreferenceChanged) {
                if (!subtitleDisabledByPersistedPreference && !subtitleAddonRestoredByPersistedPreference) autoSubtitleSelected = false
                lastSubtitlePreferredLanguage = settings.subtitleStyle.preferredLanguage
                lastSubtitleSecondaryLanguage = settings.subtitleStyle.secondaryPreferredLanguage
                lastSubtitleTertiaryLanguage = settings.subtitleStyle.tertiaryPreferredLanguage
                lastUseForcedSubtitles = settings.subtitleStyle.useForcedSubtitles
                tryAutoSelectPreferredSubtitleFromAvailableTracks()
            }

            if (showOnlyPreferredLanguagesChanged) {
                if (settings.subtitleStyle.showOnlyPreferredLanguages) {
                    _uiState.update { state ->
                        val visibleSubtitles = filterToVisibleAddonSubtitles(state.addonSubtitles)
                        state.copy(
                            addonSubtitles = visibleSubtitles,
                            selectedAddonSubtitle = state.selectedAddonSubtitle?.takeIf { selected ->
                                visibleSubtitles.any { it.id == selected.id }
                            }
                        )
                    }
                } else if (_uiState.value.addonSubtitles.isNotEmpty() || _uiState.value.selectedAddonSubtitle != null) {
                    fetchAddonSubtitles()
                }
            }

            val wasEnabled = skipIntroEnabled
            skipIntroEnabled = settings.skipIntroEnabled
            applyChaptersEnabled(settings.chaptersEnabled)
            seekingMode = settings.seekingMode
            parentalGuideEnabled = settings.parentalGuideEnabled
            autoSkipSegmentTypes = settings.autoSkipSegmentTypes
            playerSettingsInitialized = true

            // Fetch parental guide on first settings emission (after we know
            // whether the feature is enabled). Subsequent emissions skip this.
            if (settings.parentalGuideEnabled && _uiState.value.parentalWarnings.isEmpty()) {
                fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
            }

            if (!skipIntroEnabled) {
                if (skipIntervals.isNotEmpty() || _uiState.value.activeSkipInterval != null) {
                    skipIntervals = emptyList()
                    chapterSkipIntervals = emptyList()
                    skipChaptersComputedForDuration = 0L
                    skipIntroFetchedKey = null
                    serverSkipIntervals = emptyList()
                    providerSkipIntervals = emptyList()
                    serverSkipFetchedKey = null
                    autoSkippedIntervalKeys.clear()
                    _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = true) }
                }
            } else {
                if (!wasEnabled || skipIntroFetchedKey == null) {
                    _uiState.update { it.copy(skipIntervalDismissed = false) }
                    fetchSkipIntervals(contentId, currentSeason, currentEpisode)
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.loadSavedProgressFor(season: Int?, episode: Int?) {
    val isCloudLibraryPlayback = contentType.equals("cloud", ignoreCase = true)
    val progressContentId = contentId
    if (!isCloudLibraryPlayback && progressContentId == null) return

    scope.launch {
        pendingResumeProgress = null
        val progress = if (isCloudLibraryPlayback) {
            loadCloudLibraryResumeProgress()
        } else if (season != null && episode != null) {
            watchProgressRepository.getEpisodeProgress(
                progressContentId!!,
                season,
                episode,
                profileId
            ).firstOrNull()
        } else {
            watchProgressRepository.getProgress(progressContentId!!, profileId).firstOrNull()
        }

        progress?.let { saved ->

            if (saved.isInProgress()) {
                pendingResumeProgress = saved
                if (isUsingMpvEngine()) {
                    _uiState.update { it.copy(pendingSeekPosition = null) }
                    mpvView?.let { view ->
                        applyPendingMpvSeekIfNeeded(view)
                    }
                } else {
                    _exoPlayer?.let { player ->
                        if (player.playbackState == Player.STATE_READY) {
                            tryApplyPendingResumeProgress(player)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Suspend variant of [loadSavedProgressFor] that completes the DB read inline
 * instead of launching a fire-and-forget coroutine.
 *
 * This MUST be called **before** [initializePlayer] inside [preparePlaybackBeforeStart]
 * so that [pendingResumeProgress] is guaranteed to be set by the time ExoPlayer's
 * `STATE_READY` callback fires.  The fire-and-forget version races against the
 * player lifecycle and can lose the resume position entirely.
 */
internal suspend fun PlayerRuntimeController.loadSavedProgressSuspend(season: Int?, episode: Int?) {
    val isCloudLibraryPlayback = contentType.equals("cloud", ignoreCase = true)
    val progressContentId = contentId
    if (!isCloudLibraryPlayback && progressContentId == null) return

    pendingResumeProgress = null
    val progress = if (isCloudLibraryPlayback) {
        loadCloudLibraryResumeProgress()
    } else savedOrServerProgress {
        if (season != null && episode != null) {
            watchProgressRepository.getEpisodeProgress(
                progressContentId!!,
                season,
                episode,
                profileId
            ).firstOrNull()
        } else {
            watchProgressRepository.getProgress(progressContentId!!, profileId).firstOrNull()
        }
    }

    progress?.let { saved ->
        if (saved.isInProgress()) {
            pendingResumeProgress = saved
            Log.d(
                PlayerRuntimeController.TAG,
                "loadSavedProgressSuspend: set pendingResumeProgress " +
                    "position=${saved.position} duration=${saved.duration} " +
                    "percent=${saved.progressPercent} S${season}E${episode}"
            )
        }
    }
}

/**
 * Joins the saved-progress read launched at
 * preparePlaybackBeforeStart. Called on both engine branches of
 * initializePlayer immediately before the resume position is read. The
 * runway to that point is the whole player build, so the residual block
 * here should be ~0; it is logged per play.
 *
 * A failed read is swallowed: losing the resume position must not kill
 * the prep coroutine and stop playback from starting.
 * Cancellation of the AWAITING coroutine is rethrown so cancellation
 * stays cooperative; only cancellation of the deferred itself (a newer
 * press superseding this one) is swallowed.
 */
internal suspend fun PlayerRuntimeController.awaitSavedProgressLoad() {
    val deferred = savedProgressDeferred ?: return
    val awaitT0 = android.os.SystemClock.elapsedRealtime()
    try {
        deferred.await()
    } catch (ce: kotlinx.coroutines.CancellationException) {
        if (!deferred.isCancelled) throw ce
    } catch (e: Exception) {
        Log.d(
            PlayerRuntimeController.TAG,
            "awaitSavedProgressLoad: read failed, starting without resume: ${e.message}"
        )
    }
    savedProgressDeferred = null
    android.util.Log.i(
        "TTFF_STAGE",
        "SAVED_PROGRESS_AWAIT ms=${android.os.SystemClock.elapsedRealtime() - awaitT0}"
    )
}

private fun PlayerRuntimeController.loadCloudLibraryResumeProgress(): WatchProgress? {
    val playbackContext = cloudPlaybackContext ?: return null
    val file = playbackContext.fileForVideoId(currentVideoId) ?: return null
    val saved = cloudPlaybackProgressStore.load(playbackContext.item, file) ?: return null
    if (!saved.isInProgress) return null

    return WatchProgress(
        contentId = playbackContext.item.stableKey,
        contentType = "cloud",
        name = playbackContext.item.name,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = playbackContext.videoId(file),
        season = 1,
        episode = playbackContext.episodeNumber(file),
        episodeTitle = file.name,
        position = saved.positionMs,
        duration = saved.durationMs,
        lastWatched = saved.updatedAtMs,
        progressPercent = if (saved.durationMs <= 0L) 5f else null
    )
}

internal fun PlayerRuntimeController.fetchSkipIntervals(id: String?, season: Int?, episode: Int?) {
    if (!skipIntroEnabled) return
    if (id.isNullOrBlank()) return

    // Prefer videoId over contentId — videoId carries the season/episode-specific ID
    val requestedVideoId = currentVideoId
    val effectiveId = currentVideoId?.takeIf { it.isNotBlank() } ?: id

    if (contentType.equals("movie", ignoreCase = true)) {
        val key = "movie:$id:$effectiveId"
        if (skipIntroFetchedKey == key) return
        skipIntroFetchedKey = key
        scope.launch {
            val fetchedIntervals = withTimeoutOrNull(15_000L) {
                skipIntroRepository.getMovieSkipIntervals(id, effectiveId)
            } ?: emptyList()
            if (skipIntroEnabled && skipIntroFetchedKey == key && currentVideoId == requestedVideoId && contentId == id) {
                providerSkipIntervals = fetchedIntervals
                recomputeMergedSkipIntervals()
            }
        }
        return
    }

    val metaImdbId = contentType?.let { type ->
        metaRepository.getCachedMeta(type, id)?.imdbId
            ?: metaRepository.getCachedMeta(type, effectiveId.substringBefore(':'))?.imdbId
    }?.takeIf { it.startsWith("tt") }

    // MAL ID format: "mal:57658:1" (malId:episode)
    if (effectiveId.startsWith("mal:")) {
        val parts = effectiveId.split(":")
        val malId = parts.getOrNull(1) ?: return
        val malEpisode = parts.getOrNull(2)?.toIntOrNull() ?: episode ?: return
        val key = "mal:$malId:$malEpisode"
        if (skipIntroFetchedKey == key) return
        skipIntroFetchedKey = key
        val imdbId = id?.takeIf { it.startsWith("tt") } ?: metaImdbId
        scope.launch {
            val fetchedIntervals = withTimeoutOrNull(15_000L) {
                skipIntroRepository.getSkipIntervalsForMal(malId, malEpisode, imdbId = imdbId, imdbSeason = season, imdbEpisode = episode)
            } ?: emptyList()
            if (skipIntroEnabled && skipIntroFetchedKey == key && currentVideoId == requestedVideoId) {
                providerSkipIntervals = fetchedIntervals
                recomputeMergedSkipIntervals()
            }
        }
        return
    }

    // Kitsu ID format: "kitsu:12345:1" (kitsuId:episode)
    if (effectiveId.startsWith("kitsu:")) {
        val parts = effectiveId.split(":")
        val kitsuId = parts.getOrNull(1) ?: return
        val kitsuEpisode = parts.getOrNull(2)?.toIntOrNull() ?: episode ?: return
        val key = "kitsu:$kitsuId:$kitsuEpisode"
        if (skipIntroFetchedKey == key) return
        skipIntroFetchedKey = key
        val imdbId = id?.takeIf { it.startsWith("tt") } ?: metaImdbId
        scope.launch {
            val fetchedIntervals = withTimeoutOrNull(15_000L) {
                skipIntroRepository.getSkipIntervalsForKitsu(kitsuId, kitsuEpisode, imdbId = imdbId, imdbSeason = season, imdbEpisode = episode)
            } ?: emptyList()
            if (skipIntroEnabled && skipIntroFetchedKey == key && currentVideoId == requestedVideoId) {
                providerSkipIntervals = fetchedIntervals
                recomputeMergedSkipIntervals()
            }
        }
        return
    }

    val imdbId = effectiveId.split(":").firstOrNull()?.takeIf { it.startsWith("tt") } ?: return
    if (season == null || episode == null) return

    val key = "$imdbId:$season:$episode"
    if (skipIntroFetchedKey == key) return
    skipIntroFetchedKey = key

    scope.launch {
        val fetchT0 = android.os.SystemClock.elapsedRealtime()
        val fetchedIntervals = withTimeoutOrNull(15_000L) {
            skipIntroRepository.getSkipIntervals(imdbId, season, episode)
        } ?: emptyList()
        if (!skipIntroEnabled || skipIntroFetchedKey != key || currentVideoId != requestedVideoId) return@launch
        providerSkipIntervals = fetchedIntervals
        recomputeMergedSkipIntervals()
        // SkipIntroRepository logs only its no-data path, and at DEBUG, so
        // without this line a late next-episode card cannot be told apart:
        // intervals returned but no outro among them, no data at all, or the
        // 15 s timeout elapsing. Logged at INFO under TTFF_STAGE.
        //
        // PlayerNextEpisodeRules is deliberately NOT instrumented: it is a
        // pure object under unit test, and android.util.Log there would throw
        // in the JVM test source set. The interval list is the input that
        // decides its branch, so logging it here is sufficient.
        val intervalTypes = if (skipIntervals.isEmpty()) {
            "-"
        } else {
            skipIntervals.joinToString(",") { it.type }
        }
        android.util.Log.i(
            "TTFF_STAGE",
            "SKIP_INTERVALS n=${skipIntervals.size} types=$intervalTypes " +
                "ms=${android.os.SystemClock.elapsedRealtime() - fetchT0} key=$key"
        )
    }
}

/** Server markers first, then chapter titles and the skip providers per segment. */
internal fun PlayerRuntimeController.recomputeMergedSkipIntervals() {
    val isMovie = contentType.equals("movie", ignoreCase = true)
    val others = mergeSkipIntervals(providerSkipIntervals, chapterSkipIntervals, isMovie = isMovie)
    skipIntervals = mergeServerSkipIntervals(serverSkipIntervals, others)
}

internal fun PlayerRuntimeController.tryApplyPendingResumeProgress(player: Player) {
    val saved = pendingResumeProgress ?: return
    if (!player.isCurrentMediaItemSeekable) {
        pendingResumeProgress = null
        _uiState.update { it.copy(pendingSeekPosition = null) }
        return
    }
    val duration = player.duration
    val target = when {
        duration > 0L -> saved.resolveResumePosition(duration)
        saved.position > 0L -> saved.position
        else -> 0L
    }

    if (target > 0L) {
        player.seekTo(target)
    }
    _uiState.update { it.copy(pendingSeekPosition = null) }
    pendingResumeProgress = null
}

internal fun PlayerRuntimeController.resolvePendingInitialResumePosition(): Long {
    val saved = pendingResumeProgress ?: return 0L
    val target = when {
        saved.duration > 0L -> saved.resolveResumePosition(saved.duration)
        saved.position > 0L -> saved.position
        else -> 0L
    }
    if (target <= 0L && saved.progressPercent == null) {
        clearPendingInitialResumePosition()
    }
    return target.coerceAtLeast(0L)
}

internal fun PlayerRuntimeController.clearPendingInitialResumePosition() {
    pendingResumeProgress = null
    _uiState.update { it.copy(pendingSeekPosition = null) }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.retryCurrentStreamFromStartAfter416() {
    if (hasRetriedCurrentStreamAfter416) return
    hasRetriedCurrentStreamAfter416 = true
    pendingResumeProgress = null
    scheduleDeferredPlayerReinitialize(fromPositionMs = 0L, clearResumeProgress = true)
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.retryCurrentStreamAfterTimeout(fromPositionMs: Long) {
    if (timeoutRecoveryAttempts >= PlayerRuntimeController.MAX_TIMEOUT_RECOVERY_ATTEMPTS) return
    timeoutRecoveryAttempts += 1
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.retryCurrentStreamAfterUnexpectedNpe(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamAfterMediaPeriodHolderCrash(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithSafeAudioFallback(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithAudioDisabled(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithDolbyVisionFallback(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs, clearResumeProgress = true)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithDv7Mode1Fallback(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs, clearResumeProgress = true)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithVc1TrackSelectionBypass(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithoutTunneling(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamKeepingTunneling(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.cancelFirstFrameWatchdog() {
    firstFrameWatchdogJob?.cancel()
    firstFrameWatchdogJob = null
}

internal fun PlayerRuntimeController.cancelTunnelAvSyncWatchdog() {
    tunnelAvSyncWatchdogJob?.cancel()
    tunnelAvSyncWatchdogJob = null
}

internal fun PlayerRuntimeController.cancelStallWatchdog() {
    stallWatchdogJob?.cancel()
    stallWatchdogJob = null
}

internal fun PlayerRuntimeController.cancelStartupWatchdog() {
    startupWatchdogJob?.cancel()
    startupWatchdogJob = null
}

/**
 * Startup watchdog: armed when the stream starts loading, disarmed at first
 * frame (real onRenderedFirstFrame or the tunneled synthetic first frame at READY)
 * and on stream reset/reinit. Fires once if no frame has rendered inside
 * [PlayerRuntimeController.STARTUP_WATCHDOG_TIMEOUT_MS] and surfaces an actionable
 * error instead of an infinite spinner. Deliberately does NOT auto-retry: the known
 * trigger is the vendor Codec2 service wedging during decoder allocation, where a
 * reinit just hangs again; the honest remedy is telling the user (a device restart
 * clears the wedge). Exo engine path only, matching the arm site.
 */
internal fun PlayerRuntimeController.scheduleStartupWatchdog() {
    cancelStartupWatchdog()
    startupWatchdogJob = scope.launch {
        // Extend-with-ceiling on buffered-AHEAD growth.
        // Absolute bufferedPosition against a 0 baseline would misfire on every
        // resume (position opens at the resume offset) and measures the
        // timeline, not data flow. totalBufferedDuration is the
        // buffered-ahead amount in ms -- a delta, resume-safe by construction
        // (the analytics layer already treats it as buffered-ahead). If it
        // grew since the last check and another full interval fits inside the
        // ceiling, re-arm instead of firing.
        var armedAtMs = System.currentTimeMillis()
        var lastBufferedAheadMs = 0L
        var lastPositionMs = _exoPlayer?.currentPosition ?: 0L
        var videoRestarted = false
        var slowStart = SlowStartServer.matches(currentStreamUrl)
        while (isActive) {
            delay(PlayerRuntimeController.STARTUP_WATCHDOG_TIMEOUT_MS)
            if (hasRenderedFirstFrame) return@launch
            // The film waits on purpose while thumbnails are made before play; time it from when that screen closes.
            // A film let go for the screen opens again from scratch, so it gets the slow-server allowance.
            if (SeekThumbnails.prepareUi.value !is SeekThumbnails.PrepareUi.Hidden) {
                Log.i(PlayerRuntimeController.TAG, "STARTUP_WATCHDOG: waiting for the thumbnails screen")
                while (isActive && SeekThumbnails.prepareUi.value !is SeekThumbnails.PrepareUi.Hidden) delay(500)
                slowStart = true
                armedAtMs = System.currentTimeMillis()
                lastBufferedAheadMs = 0L
                lastPositionMs = _exoPlayer?.currentPosition ?: 0L
                continue
            }
            val livePlayer = _exoPlayer ?: return@launch
            val elapsedMs = System.currentTimeMillis() - armedAtMs
            val bufferedAheadMs = livePlayer.totalBufferedDuration.coerceAtLeast(0L)
            val positionMs = livePlayer.currentPosition
            val ceilingMs =
                if (slowStart) SlowStartServer.STARTUP_CEILING_MS else PlayerRuntimeController.STARTUP_WATCHDOG_CEILING_MS
            val nothingBuffered = bufferedAheadMs == 0L && livePlayer.playbackState != Player.STATE_READY
            val playbackThreadAnswers = !nothingBuffered || playbackThreadAnswers(livePlayer.playbackLooper)
            val step = StartupWatchdogDecision.next(
                elapsedMs = elapsedMs,
                intervalMs = PlayerRuntimeController.STARTUP_WATCHDOG_TIMEOUT_MS,
                ceilingMs = ceilingMs,
                slowStart = slowStart,
                bufferedAheadMs = bufferedAheadMs,
                lastBufferedAheadMs = lastBufferedAheadMs,
                ready = livePlayer.playbackState == Player.STATE_READY,
                playing = livePlayer.isPlaying,
                positionMs = positionMs,
                lastPositionMs = lastPositionMs,
                videoRestarted = videoRestarted,
                tracksRead = hasScannedTextTracksOnce,
                hasVideoTrack = currentStreamHasVideoTrack,
                videoSelected = currentVideoTrackSelected,
                playbackThreadAnswers = playbackThreadAnswers,
            )
            lastPositionMs = positionMs
            when (step) {
                StartupWatchdogDecision.Step.WaitForData -> {
                    // A home server or a Usenet stream may still be fetching what playback asked for (start or resume point).
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "STARTUP_WATCHDOG: no data ${elapsedMs}ms after starting_stream from a server that may still " +
                            "be preparing it; waiting (ceiling=${SlowStartServer.STARTUP_CEILING_MS}ms)"
                    )
                    continue
                }
                StartupWatchdogDecision.Step.Extend -> {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "STARTUP_WATCHDOG: no first frame ${elapsedMs}ms after starting_stream " +
                            "but buffered-ahead growing (${lastBufferedAheadMs}ms -> ${bufferedAheadMs}ms); " +
                            "extending (ceiling=${ceilingMs}ms)"
                    )
                    com.nuvio.tv.core.util.TtffTrace.mark("startup_watchdog_extended")
                    lastBufferedAheadMs = bufferedAheadMs
                    continue
                }
                StartupWatchdogDecision.Step.RestartVideo -> {
                    videoRestarted = true
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "STARTUP_WATCHDOG: playing without a picture ${elapsedMs}ms after starting_stream " +
                            "(pos=$positionMs bufferedAheadMs=$bufferedAheadMs); seeking in place to restart the video"
                    )
                    com.nuvio.tv.core.util.TtffTrace.mark("startup_watchdog_video_restart")
                    livePlayer.seekTo(positionMs)
                    continue
                }
                is StartupWatchdogDecision.Step.Fire -> Unit
            }
            val stateName = when (livePlayer.playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> livePlayer.playbackState.toString()
            }
            val fireReason = (step as StartupWatchdogDecision.Step.Fire).reason
            val fireMessage = when (fireReason) {
                "video_track_unsupported" ->
                    context.getString(com.nuvio.tv.R.string.player_error_startup_video_track_unsupported)
                "tracks_not_read", "no_video_track", "no_data_buffered" ->
                    startupNoDataMessage(context, playbackAnalyticsDiagnostics.lastLoadErrorHttpStatus)
                else ->
                    context.getString(com.nuvio.tv.R.string.player_error_startup_timeout)
            }
            Log.w(
                PlayerRuntimeController.TAG,
                "STARTUP_WATCHDOG: no first frame ${elapsedMs}ms " +
                    "after starting_stream (state=$stateName bufferedAheadMs=${bufferedAheadMs} " +
                    "pos=${livePlayer.currentPosition} reason=$fireReason " +
                    "scannedTracks=$hasScannedTextTracksOnce hasVideoTrack=$currentStreamHasVideoTrack " +
                    "videoSelected=$currentVideoTrackSelected playbackThreadAnswers=$playbackThreadAnswers " +
                    "loadHttpStatus=${playbackAnalyticsDiagnostics.lastLoadErrorHttpStatus}); " +
                    "surfacing error"
            )
            com.nuvio.tv.core.util.TtffTrace.mark("startup_watchdog_fired")
            _uiState.update {
                if (it.error == null) {
                    it.copy(
                        error = fireMessage,
                        showLoadingOverlay = false
                    )
                } else {
                    it
                }
            }
            return@launch
        }
    }
}

/** False when the player's own thread is stuck, as it is while a decoder never finishes opening. */
private suspend fun playbackThreadAnswers(looper: android.os.Looper): Boolean {
    val answered = kotlinx.coroutines.CompletableDeferred<Unit>()
    android.os.Handler(looper).post { answered.complete(Unit) }
    return withTimeoutOrNull(PLAYBACK_THREAD_ANSWER_MS) { answered.await() } != null
}

private const val PLAYBACK_THREAD_ANSWER_MS = 3_000L

/** The server's own answer when it refused the stream, the general text when it stayed silent. */
internal fun startupNoDataMessage(context: android.content.Context, httpStatus: Int?): String {
    val hint = httpStatus?.let { httpStatusExplanation(context, it) }.orEmpty()
    return if (httpStatus != null && hint.isNotEmpty()) {
        "HTTP $httpStatus$hint"
    } else {
        context.getString(com.nuvio.tv.R.string.player_error_startup_no_stream_data)
    }
}

/**
 * The startup watchdog deliberately does not stop the player, so a
 * slow-but-healthy start can render its first frame after the watchdog has
 * already surfaced the startup-timeout error, leaving a stale error screen
 * over running playback. The rendered frame is
 * ground truth: retract exactly that error. Any other error (a real decode
 * failure racing the frame) is left untouched.
 */
internal fun PlayerRuntimeController.retractStartupTimeoutErrorAfterFirstFrame() {
    // The watchdog can surface three different messages; a late
    // first frame disproves all of them equally, so retract whichever fired.
    val startupWatchdogMessages = setOf(
        context.getString(com.nuvio.tv.R.string.player_error_startup_timeout),
        context.getString(com.nuvio.tv.R.string.player_error_startup_video_track_unsupported),
        context.getString(com.nuvio.tv.R.string.player_error_startup_no_stream_data),
    )
    var retracted = false
    _uiState.update {
        if (it.error != null && it.error in startupWatchdogMessages) {
            retracted = true
            it.copy(error = null)
        } else {
            it
        }
    }
    if (retracted) {
        Log.w(
            PlayerRuntimeController.TAG,
            "STARTUP_WATCHDOG: first frame rendered after fire; retracting startup-timeout error"
        )
        com.nuvio.tv.core.util.TtffTrace.mark("startup_watchdog_retracted")
    }
}

/** Tiny skip past the buffered edge to force Media3 to cancel the in-flight Range request. */
private val STALL_WATCHDOG_SKIP_PAST_BUFFERED_MS = PlayerStallWatchdogPolicy.SKIP_PAST_BUFFERED_MS

/** Re-seeks past the buffered edge when bufferedPosition stops advancing during buffering. */
internal fun PlayerRuntimeController.maybeScheduleStallWatchdog() {
    if (stallWatchdogJob?.isActive == true) return
    val player = _exoPlayer ?: return
    if (player.playbackState != Player.STATE_BUFFERING) return

    stallWatchdogJob = scope.launch {
        var lastBufferedPosition = player.bufferedPosition
        var lastAdvanceAtMs = System.currentTimeMillis()

        while (isActive) {
            delay(PlayerRuntimeController.STALL_WATCHDOG_POLL_INTERVAL_MS)
            val livePlayer = _exoPlayer ?: return@launch
            if (livePlayer.playbackState != Player.STATE_BUFFERING) {
                // Buffering resolved on its own.
                return@launch
            }

            val nowMs = System.currentTimeMillis()
            val bufferedNow = livePlayer.bufferedPosition
            if (bufferedNow > lastBufferedPosition) {
                // Real progress — reset the stall timer.
                lastBufferedPosition = bufferedNow
                lastAdvanceAtMs = nowMs
                continue
            }

            val stalledForMs = nowMs - lastAdvanceAtMs
            when (
                val decision = PlayerStallWatchdogPolicy.evaluate(
                    PlayerStallWatchdogPolicy.Input(
                        bufferedPositionMs = bufferedNow,
                        playheadMs = livePlayer.currentPosition,
                        durationMs = livePlayer.duration,
                        stalledForMs = stalledForMs,
                    )
                )
            ) {
                PlayerStallWatchdogPolicy.Decision.KeepWaiting -> Unit
                PlayerStallWatchdogPolicy.Decision.SkipUnknownDuration -> {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "STALL_WATCHDOG: bufferedPosition stuck at $bufferedNow for ${stalledForMs}ms " +
                            "during STATE_BUFFERING (playhead=${livePlayer.currentPosition.coerceAtLeast(0L)}); " +
                            "skipping self-seek because duration is unknown"
                    )
                    return@launch
                }
                PlayerStallWatchdogPolicy.Decision.SkipBufferedNotAhead -> {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "STALL_WATCHDOG: bufferedPosition stuck at $bufferedNow for ${stalledForMs}ms " +
                            "during STATE_BUFFERING (playhead=${livePlayer.currentPosition.coerceAtLeast(0L)}); " +
                            "skipping self-seek because buffered position is not ahead"
                    )
                    return@launch
                }
                PlayerStallWatchdogPolicy.Decision.SkipTargetNotForward -> {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "STALL_WATCHDOG: bufferedPosition stuck at $bufferedNow for ${stalledForMs}ms " +
                            "during STATE_BUFFERING (playhead=${livePlayer.currentPosition.coerceAtLeast(0L)}); " +
                            "skipping self-seek because target is not forward"
                    )
                    return@launch
                }
                is PlayerStallWatchdogPolicy.Decision.SeekPastBufferedEdge -> {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "STALL_WATCHDOG: bufferedPosition stuck at $bufferedNow for ${stalledForMs}ms " +
                            "during STATE_BUFFERING (playhead=${livePlayer.currentPosition.coerceAtLeast(0L)}); " +
                            "seeking past buffered edge to ${decision.targetMs} to break stuck request"
                    )
                    livePlayer.seekTo(decision.targetMs)
                    return@launch
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.maybeScheduleFirstFrameWatchdog() {
    if (hasRenderedFirstFrame || !currentStreamHasVideoTrack) return
    val player = _exoPlayer ?: return
    if (player.playbackState != Player.STATE_READY) return
    if (firstFrameWatchdogJob?.isActive == true) return

    firstFrameWatchdogJob = scope.launch {
        delay(PlayerRuntimeController.FIRST_FRAME_TIMEOUT_MS)

        val livePlayer = _exoPlayer ?: return@launch
        if (hasRenderedFirstFrame) return@launch
        if (livePlayer.playbackState != Player.STATE_READY) return@launch

        if (PlayerFirstFrameWatchdogPolicy.evaluate(
                PlayerFirstFrameWatchdogPolicy.Input(
                    hasRenderedFirstFrame = hasRenderedFirstFrame,
                    currentStreamHasVideoTrack = currentStreamHasVideoTrack,
                    playbackState = livePlayer.playbackState,
                    playWhenReady = livePlayer.playWhenReady,
                    userPausedManually = userPausedManually,
                )
            ) == PlayerFirstFrameWatchdogPolicy.RecoveryAction.ForcePlayWhenReady
        ) {
            livePlayer.playWhenReady = true
            livePlayer.play()
            return@launch
        }
        if (!livePlayer.playWhenReady) return@launch

        val currentPosition = livePlayer.currentPosition
        when (
            PlayerFirstFrameCodecRecoveryPolicy.evaluateAfterWatchdogTimeout(
                PlayerFirstFrameCodecRecoveryPolicy.Input(
                    playWhenReady = livePlayer.playWhenReady,
                    isManualDv81Mode2Active = isManualDv81Mode2ActiveForCurrentPlayback,
                    dv7Mode1AlreadyForced = dv7Mode1ForcedStreamUrls.contains(currentStreamUrl),
                    currentVideoTrackIsLikelyVc1 = currentVideoTrackIsLikelyVc1,
                )
            )
        ) {
            PlayerFirstFrameCodecRecoveryPolicy.RecoveryAction.RetryDv7Mode1 -> {
                dv7Mode1ForcedStreamUrls.add(currentStreamUrl)
                retryCurrentStreamWithDv7Mode1Fallback(currentPosition)
            }
            PlayerFirstFrameCodecRecoveryPolicy.RecoveryAction.FailVc1Unsupported -> {
                val exoError = livePlayer.playerError ?: return@launch
                handleVc1PlaybackFailure(errorMessage = exoError.toDisplayMessage(context))
            }
            PlayerFirstFrameCodecRecoveryPolicy.RecoveryAction.None -> Unit
        }
    }
}

internal fun PlayerRuntimeController.noteOutputChangeForTunnelWatch() {
    lastOutputChangeForTunnelMs = android.os.SystemClock.elapsedRealtime()
}

internal fun PlayerRuntimeController.maybeScheduleTunnelAvSyncWatchdog() {
    if (!isTunnelingActiveForCurrentPlayback) return
    if (_exoPlayer?.isTunnelingEnabled != true) return
    if (nativeVideoSelection.isSelected) return
    if (!currentStreamHasVideoTrack) return
    if (tunnelingDisabledStreamUrls.contains(currentStreamUrl)) return
    if (tunnelAvSyncWatchdogJob?.isActive == true) return

    tunnelAvSyncWatchdogJob = scope.launch {
        var lastPositionMs: Long? = null
        var stalledMs = 0L
        var readyMs = 0L
        var firstReadyPositionMs: Long? =
            PlayerTunnelAvSyncPolicy.strikeForStream(currentStreamUrl, tunnelStallStrike)?.firstReadyPositionMs
        var pendingDeadClockMemo: Pair<String, String>? = null
        while (isActive) {
            delay(PlayerRuntimeController.TUNNEL_AV_SYNC_CHECK_MS)
            val livePlayer = _exoPlayer ?: return@launch
            val positionMs = livePlayer.currentPosition
            if (firstReadyPositionMs == null && livePlayer.playbackState == Player.STATE_READY) {
                firstReadyPositionMs = positionMs
            }
            val renderedFrames = livePlayer.videoDecoderCounters
                ?.also { it.ensureUpdated() }
                ?.renderedOutputBufferCount
            val frameReportKey = PlayerTunnelAvSyncPolicy.frameReportKey(
                android.os.Build.FINGERPRINT,
                playbackAnalyticsDiagnostics.hudSample().videoDecoderName
            )
            if (frameReportKey != null && renderedFrames != null && renderedFrames > 0 &&
                livePlayer.isTunnelingEnabled && PlayerTunnelAvSyncPolicy.frameReportDecoders.add(frameReportKey)
            ) {
                Log.i(PlayerRuntimeController.TAG, "TUNNEL_AV_SYNC: tunnelled frames reported by $frameReportKey")
                scope.launch { runCatching { deviceLocalPlayerPreferences.addTunnelFrameReportDecoder(frameReportKey) } }
            }
            val result = PlayerTunnelAvSyncPolicy.evaluate(
                PlayerTunnelAvSyncPolicy.Input(
                    isTunnelingActive = isTunnelingActiveForCurrentPlayback && !nativeVideoSelection.isSelected,
                    isTunnelingApplied = livePlayer.isTunnelingEnabled,
                    hasVideoTrack = currentStreamHasVideoTrack,
                    isReady = livePlayer.playbackState == Player.STATE_READY,
                    playWhenReady = livePlayer.playWhenReady,
                    userPausedManually = userPausedManually,
                    positionMs = positionMs,
                    bufferedPositionMs = livePlayer.bufferedPosition,
                    lastPositionMs = lastPositionMs,
                    stalledMs = stalledMs,
                    intervalMs = PlayerRuntimeController.TUNNEL_AV_SYNC_CHECK_MS,
                    stallThresholdMs = PlayerRuntimeController.TUNNEL_AV_SYNC_STALL_MS,
                    renderedOutputBufferCount = renderedFrames,
                    frameReportsTrusted = frameReportKey != null &&
                        frameReportKey in PlayerTunnelAvSyncPolicy.frameReportDecoders,
                    readyMs = readyMs,
                    noFrameThresholdMs = PlayerRuntimeController.TUNNEL_AV_SYNC_NO_FRAME_MS,
                    tunnelingAlreadyDisarmed = tunnelingDisabledStreamUrls.contains(currentStreamUrl),
                )
            )
            lastPositionMs = positionMs
            stalledMs = result.stalledMs
            readyMs = result.readyMs
            when (result.decision) {
                PlayerTunnelAvSyncPolicy.Decision.Stop -> return@launch
                PlayerTunnelAvSyncPolicy.Decision.None -> Unit
                PlayerTunnelAvSyncPolicy.Decision.DisableTunnelingAndRebuild -> {
                    val audioClass = playbackSpeedAwareAudioSink?.currentTunnelAudioClass
                    val audioLabel = audioClass ?: "unknown"
                    val response = PlayerTunnelAvSyncPolicy.stallResponse(
                        result.reason, currentStreamUrl, tunnelStallStrike
                    )
                    if (response == PlayerTunnelAvSyncPolicy.StallResponse.ReloadKeepingTunnel) {
                        tunnelStallStrike = PlayerTunnelAvSyncPolicy.StallStrike(currentStreamUrl, firstReadyPositionMs)
                        Log.w(
                            PlayerRuntimeController.TAG,
                            "TUNNEL_AV_SYNC: position frozen at ${positionMs}ms for ${stalledMs}ms under " +
                                "tunnelling (audio=$audioLabel); first stall in this play; reloading with tunnelling kept"
                        )
                        queuePlaybackRawEventLine(
                            "tunnel_av_sync_reload_keep_tunneling stalledMs=$stalledMs positionMs=$positionMs " +
                                "audioClass=$audioLabel reason=position-frozen"
                        )
                        retryCurrentStreamKeepingTunneling(positionMs)
                        return@launch
                    }
                    val remembered = PlayerTunnelAvSyncPolicy.remembersStall(
                        android.os.SystemClock.elapsedRealtime(),
                        lastOutputChangeForTunnelMs
                    )
                    if (result.reason == PlayerTunnelAvSyncPolicy.Reason.PositionFrozen) {
                        if (!remembered) {
                            Log.i(
                                PlayerRuntimeController.TAG,
                                "TUNNEL_AV_SYNC: freeze right after a display or audio output change; not remembered"
                            )
                        }
                        if (audioClass != null && remembered) {
                            PlayerTunnelAvSyncPolicy.deadAudioClasses.add(audioClass)
                            val signature = tunnelDeadClockSignature
                            if (signature != null && positionMs == firstReadyPositionMs) {
                                pendingDeadClockMemo = audioClass to signature
                            }
                        }
                        Log.w(
                            PlayerRuntimeController.TAG,
                            "TUNNEL_AV_SYNC: position frozen at ${positionMs}ms for ${stalledMs}ms under " +
                                "tunnelling (audio=$audioLabel); disabling tunnelling for this stream"
                        )
                        queuePlaybackRawEventLine(
                            "tunnel_av_sync_disable_tunneling stalledMs=$stalledMs positionMs=$positionMs " +
                                "audioClass=$audioLabel reason=position-frozen"
                        )
                    } else {
                        Log.w(
                            PlayerRuntimeController.TAG,
                            "TUNNEL_AV_SYNC: no tunnelled video frame rendered after ${readyMs}ms at " +
                                "position ${positionMs}ms (audio=$audioLabel); disabling tunnelling for this stream"
                        )
                        queuePlaybackRawEventLine(
                            "tunnel_av_sync_disable_tunneling readyMs=$readyMs positionMs=$positionMs " +
                                "audioClass=$audioLabel reason=no-rendered-frames"
                        )
                    }
                    tunnelingDisabledStreamUrls.add(currentStreamUrl)
                    val rebuiltFrom = livePlayer
                    retryCurrentStreamWithoutTunneling(positionMs)
                    pendingDeadClockMemo?.let { (memoClass, signature) ->
                        persistDeadClockMemoWhenRebuildPlays(memoClass, signature, currentStreamUrl, rebuiltFrom)
                    }
                    return@launch
                }
            }
        }
    }
}

private fun PlayerRuntimeController.persistDeadClockMemoWhenRebuildPlays(
    audioClass: String,
    signature: String,
    streamUrl: String,
    rebuiltFrom: Player
) {
    scope.launch {
        var lastPositionMs: Long? = null
        var playedMs = 0L
        repeat(PlayerTunnelAvSyncPolicy.MEMO_CONFIRM_SAMPLES) {
            delay(PlayerRuntimeController.TUNNEL_AV_SYNC_CHECK_MS)
            if (isReleasingPlayer || currentStreamUrl != streamUrl) return@launch
            val player = _exoPlayer ?: return@repeat
            if (player === rebuiltFrom || player.playbackState != Player.STATE_READY) return@repeat
            val positionMs = player.currentPosition
            lastPositionMs?.let { last ->
                playedMs += PlayerTunnelAvSyncPolicy.memoAdvanceMs(
                    last, positionMs, PlayerRuntimeController.TUNNEL_AV_SYNC_CHECK_MS
                )
            }
            lastPositionMs = positionMs
            if (PlayerTunnelAvSyncPolicy.memoConfirmed(playedMs)) {
                playerSettingsDataStore.recordTunnelDeadAudioClass(audioClass, signature)
                Log.i(
                    PlayerRuntimeController.TAG,
                    "TUNNEL_AV_SYNC: dead-clock memo persisted class=$audioClass (untunnelled rebuild playing)"
                )
                return@launch
            }
        }
        Log.i(
            PlayerRuntimeController.TAG,
            "TUNNEL_AV_SYNC: dead-clock memo not persisted class=$audioClass (untunnelled rebuild did not play long enough)"
        )
    }
}

internal fun PlayerRuntimeController.handleVc1PlaybackFailure(errorMessage: String? = null) {
    val displayMessage = errorMessage?.takeIf { it.isNotBlank() }
        ?: _exoPlayer?.playerError?.toDisplayMessage(context)
        ?: return
    cancelFirstFrameWatchdog()
    cancelTunnelAvSyncWatchdog()
    cancelStallWatchdog()
    cancelStableProgressReset()
    errorRetryJob?.cancel()
    errorRetryJob = null
    releasePlayer(flushPlaybackState = false)
    cancelNextEpisodeAutoPlayOnFatalError()
    _uiState.update {
        it.copy(
            error = displayMessage,
            showSwitchToMpvErrorAction = true,
            isPlaying = false,
            showControls = false,
            isBuffering = false,
            showLoadingOverlay = false,
            showPauseOverlay = false,
            loadingIssueReportVisible = false,
            loadingIssueElapsedMs = 0L,
            playbackEnded = false,
            postPlayMode = null
        )
    }
}

internal fun PlayerRuntimeController.scheduleDeferredPlayerReinitialize(
    fromPositionMs: Long,
    clearResumeProgress: Boolean = false
) {
    cancelFirstFrameWatchdog()
    cancelTunnelAvSyncWatchdog()
    cancelStallWatchdog()
    cancelStartupWatchdog()
    if (clearResumeProgress) {
        pendingResumeProgress = null
    }
    _uiState.update {
        it.copy(
            pendingSeekPosition = if (fromPositionMs > 0L) fromPositionMs else null,
            error = null,
            showLoadingOverlay = it.loadingOverlayEnabled
        )
    }
    scope.launch {
        yield()
        runCatching {
            val paused = pausedByUser
            releasePlayer(flushPlaybackState = true, keepTorrentStream = true)
            reinitializeCurrentStream(startPaused = paused)
        }.onFailure { e ->
            _uiState.update {
                it.copy(
                    error = e.toDisplayMessage(context),
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
        }
    }
}

internal fun PlayerRuntimeController.observePlayerStatsHud() {
    scope.launch {
        combine(
            deviceLocalPlayerPreferences.playerStatsHudButtonEnabled,
            deviceLocalPlayerPreferences.playerStatsHudActive
        ) { buttonAvailable, active ->
            val isHudEnabled = buttonAvailable && active
            buttonAvailable to isHudEnabled
        }.distinctUntilChanged()
            .collect { (buttonAvailable, isHudEnabled) ->
                _uiState.update {
                    it.copy(
                        playerStatsHudButtonAvailable = buttonAvailable,
                        playerStatsHudEnabled = isHudEnabled
                    )
                }
            }
    }
}

internal fun PlayerRuntimeController.observeDeviceLocalAspectMode() {
    scope.launch {
        deviceLocalPlayerPreferences.aspectMode
            .distinctUntilChanged()
            .collect { mode ->
                val currentState = _uiState.value
                if (currentState.aspectMode != mode) {
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "Aspect mode restored from device-local prefs: ${currentState.aspectMode} -> $mode"
                    )
                    _uiState.update { it.copy(aspectMode = mode) }
                }
            }
    }
}

internal fun PlayerRuntimeController.observeDeviceLocalTransparentLetterbox() {
    scope.launch {
        deviceLocalPlayerPreferences.transparentLetterbox
            .distinctUntilChanged()
            .collect { enabled ->
                _uiState.update { it.copy(transparentLetterbox = enabled) }
            }
    }
}

internal fun PlayerRuntimeController.observeDeviceLocalTunneledSurfaceFill() {
    scope.launch {
        deviceLocalPlayerPreferences.tunneledSurfaceFill
            .distinctUntilChanged()
            .collect { fill ->
                if (_uiState.value.tunneledSurfaceFill != fill) {
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "Tunneled surface fill restored from device-local prefs: $fill"
                    )
                    _uiState.update { it.copy(tunneledSurfaceFill = fill) }
                }
            }
    }
}

internal fun PlayerRuntimeController.observeDeviceLocalReleaseDecoderOnBackground() {
    scope.launch {
        deviceLocalPlayerPreferences.releaseDecoderOnBackground
            .distinctUntilChanged()
            .collect { enabled -> releaseDecoderOnBackground = enabled }
    }
}
