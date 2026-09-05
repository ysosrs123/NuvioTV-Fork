package com.nuvio.tv.ui.screens.player

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.debrid.DirectDebridPlayableResult
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.AutoPlaySelection
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.data.local.toTrackPreference
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.SourceChipStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Hard ceiling for next-episode stream search to prevent hanging forever. */
private const val NEXT_EPISODE_HARD_TIMEOUT_MS = 120_000L
private const val CLOUD_LIBRARY_AUTO_NEXT_TIMEOUT_MS = 65_000L

/**
 * Schedules incremental badge matching for source streams in the background.
 * Only processes addon groups not yet badged, emits UI update every 5 streams.
 */
internal fun PlayerRuntimeController.scheduleSourceBadgeApplication() {
    val state = _uiState.value
    val newAddons = state.sourceAvailableAddons.filter { it !in sourceBadgedAddonNames }
    if (newAddons.isEmpty()) return

    sourceBadgeJob = scope.launch(kotlinx.coroutines.Dispatchers.Default) {
        val allNewStreams = newAddons.flatMap { addonName ->
            _uiState.value.sourceAllStreams.filter { it.addonName == addonName }
        }
        if (allNewStreams.isEmpty()) {
            sourceBadgedAddonNames = sourceBadgedAddonNames + newAddons.toSet()
            return@launch
        }
        val chunks = allNewStreams.chunked(5)
        for (chunk in chunks) {
            val chunkGroup = com.nuvio.tv.domain.model.AddonStreams(addonName = "", addonLogo = null, streams = chunk)
            val badgedChunk = streamBadgePresentation.apply(listOf(chunkGroup))
                .firstOrNull()?.streams ?: chunk
            val badgedByKey = badgedChunk.associateBy { it.sourceBadgeMergeKey() }
            _uiState.update { current ->
                val updatedAll = current.sourceAllStreams.map { s ->
                    badgedByKey[s.sourceBadgeMergeKey()] ?: s
                }
                val selectedAddon = current.sourceSelectedAddonFilter
                current.copy(
                    sourceAllStreams = updatedAll,
                    sourceFilteredStreams = updatedAll.filterByAddon(selectedAddon)
                )
            }
            val coveredAddons = chunk.map { it.addonName }.toSet()
            sourceBadgedAddonNames = sourceBadgedAddonNames + coveredAddons
        }
    }
}

/**
 * Schedules badge matching for episode streams in the background.
 */
internal fun PlayerRuntimeController.scheduleEpisodeBadgeApplication() {
    episodeBadgeJob?.cancel()
    episodeBadgeJob = scope.launch(kotlinx.coroutines.Dispatchers.Default) {
        val streams = _uiState.value.episodeAllStreams
        if (streams.isEmpty()) return@launch
        val group = com.nuvio.tv.domain.model.AddonStreams(addonName = "", addonLogo = null, streams = streams)
        val badged = streamBadgePresentation.apply(listOf(group))
        val badgedStreams = badged.flatMap { it.streams }
        if (badgedStreams == streams) return@launch
        _uiState.update { current ->
            val selectedAddon = current.episodeSelectedAddonFilter
            current.copy(
                episodeAllStreams = badgedStreams,
                episodeFilteredStreams = if (selectedAddon == null) badgedStreams else badgedStreams.filter { it.addonName == selectedAddon }
            )
        }
    }
}

private fun Stream.sourceBadgeMergeKey(): String {
    infoHash?.lowercase()?.let { return "$addonName|$it:${fileIdx ?: ""}" }
    val playableUrl = url ?: clientResolve?.let { resolve ->
        resolve.stream?.raw?.filename ?: resolve.infoHash
    }
    if (playableUrl != null) return "$addonName|$playableUrl"
    return "$addonName|${name}:${title}:${description?.hashCode() ?: 0}"
}

internal fun PlayerRuntimeController.showEpisodesPanel() {
    _uiState.update {
        it.copy(
            showEpisodesPanel = true,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false
        )
    }

    val desiredSeason = currentSeason ?: _uiState.value.episodesSelectedSeason
    if (_uiState.value.episodesAll.isNotEmpty() && desiredSeason != null) {
        selectEpisodesSeason(desiredSeason)
    } else {
        loadEpisodesIfNeeded()
    }
}

private fun Stream.isReadyForDebridPreparation(): Boolean =
    getStreamUrl().isNullOrBlank() &&
        (isDirectDebrid() || (needsLocalDebridResolve() && debridCacheStatus?.state == StreamDebridCacheState.CACHED))

internal fun PlayerRuntimeController.showSourcesPanel() {
    _uiState.update {
        it.copy(
            showSourcesPanel = true,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            showEpisodesPanel = false,
            showEpisodeStreams = false
        )
    }
    loadSourceStreams(forceRefresh = false)
}

internal fun PlayerRuntimeController.buildSourceRequestKey(type: String, videoId: String, season: Int?, episode: Int?): String {
    return "$type|$videoId|${season ?: -1}|${episode ?: -1}"
}

internal fun PlayerRuntimeController.loadSourceStreams(forceRefresh: Boolean) {
    streamRepository.setLocalPluginSearchPaused(false)
    val type: String
    val vid: String
    val seasonArg: Int?
    val episodeArg: Int?

    if (contentType in listOf("series", "tv") && currentSeason != null && currentEpisode != null) {
        type = contentType ?: return
        vid = currentVideoId ?: contentId ?: return
        seasonArg = currentSeason
        episodeArg = currentEpisode
    } else {
        type = contentType ?: "movie"
        vid = contentId ?: return
        seasonArg = null
        episodeArg = null
    }

    val requestKey = buildSourceRequestKey(type = type, videoId = vid, season = seasonArg, episode = episodeArg)
    val state = _uiState.value
    val hasCachedPayload = state.sourceAllStreams.isNotEmpty() || state.sourceStreamsError != null

    // Fully completed cache hit — nothing to do
    if (!forceRefresh && requestKey == sourceStreamsCacheRequestKey && hasCachedPayload && sourceStreamsFetchCompleted) {
        return
    }
    // Already loading the same request — don't restart
    if (!forceRefresh && state.isLoadingSourceStreams && requestKey == sourceStreamsCacheRequestKey) {
        return
    }

    val targetChanged = requestKey != sourceStreamsCacheRequestKey
    val isResume = !forceRefresh && !targetChanged && requestKey == sourceStreamsCacheRequestKey && hasCachedPayload && !sourceStreamsFetchCompleted
    sourceStreamsScope?.cancel()
    sourceStreamsJob = null
    val newScope = kotlinx.coroutines.CoroutineScope(scope.coroutineContext + kotlinx.coroutines.SupervisorJob())
    sourceStreamsScope = newScope
    sourceChipErrorDismissJob?.cancel()
    sourceStreamsJob = newScope.launch {
        sourceStreamsCacheRequestKey = requestKey
        sourceStreamsFetchCompleted = false
        if (forceRefresh || targetChanged) sourceBadgedAddonNames = emptySet()
        _uiState.update {
            it.copy(
                isLoadingSourceStreams = true,
                sourceStreamsError = null,
                sourceAllStreams = if (forceRefresh || targetChanged) emptyList() else it.sourceAllStreams,
                sourceSelectedAddonFilter = if (forceRefresh || targetChanged) null else it.sourceSelectedAddonFilter,
                sourceFilteredStreams = if (forceRefresh || targetChanged) emptyList() else it.sourceFilteredStreams,
                sourceAvailableAddons = if (forceRefresh || targetChanged) emptyList() else it.sourceAvailableAddons,
                sourceChips = if (forceRefresh || targetChanged) emptyList() else it.sourceChips
            )
        }

        val installedAddons = addonRepository.getInstalledAddons().first().enabledAddons()
        val installedAddonOrder = installedAddons.map { it.displayName }
        val installedAddonNames = installedAddonOrder.toSet()
        var debridPreparationLaunched = false

        // On resume, skip chip reset — keep existing chip statuses
        if (!isResume) {
            updateSourceChipsForFetchStart(type, vid, installedAddons)
        }

        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = vid,
            season = seasonArg,
            episode = episodeArg,
            forceRefresh = forceRefresh
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val addonStreams = StreamAutoPlaySelector.orderAddonStreams(result.data, installedAddonOrder)
                    val allStreams = addonStreams.flatMap { it.streams }
                    val availableAddons = addonStreams.map { it.addonName }
                    _uiState.update {
                        // On resume, merge fresh results with any previously cached streams
                        val mergedAllStreams = if (isResume && it.sourceAllStreams.isNotEmpty()) {
                            mergeSourceStreams(it.sourceAllStreams, allStreams)
                        } else {
                            allStreams
                        }
                        // Preserve badges already computed by prior badge jobs
                        val existingBadged = it.sourceAllStreams
                            .filter { s -> s.badges.isNotEmpty() }
                            .associateBy { s -> s.sourceBadgeMergeKey() }
                        val badgePreserved = if (existingBadged.isEmpty()) {
                            mergedAllStreams
                        } else {
                            mergedAllStreams.map { s ->
                                val existing = existingBadged[s.sourceBadgeMergeKey()]
                                if (existing != null && s.badges.isEmpty()) s.copy(badges = existing.badges) else s
                            }
                        }
                        val mergedAvailableAddons = if (isResume && it.sourceAvailableAddons.isNotEmpty()) {
                            (it.sourceAvailableAddons + availableAddons).distinct()
                        } else {
                            availableAddons
                        }
                        val selectedAddon = it.sourceSelectedAddonFilter
                        val filteredStreams = if (selectedAddon == null) {
                            badgePreserved
                        } else {
                            badgePreserved.filter { stream -> stream.addonName == selectedAddon }
                        }
                        it.copy(
                            isLoadingSourceStreams = false,
                            sourceAllStreams = badgePreserved,
                            sourceFilteredStreams = filteredStreams,
                            sourceAvailableAddons = mergedAvailableAddons,
                            sourceChips = mergeSourceChipStatuses(
                                existing = it.sourceChips,
                                succeededNames = addonStreams.map { group -> group.addonName }
                            ),
                            sourceStreamsError = null
                        )
                    }
                    launchSourceDebridPreparationIfNeeded(
                        launched = debridPreparationLaunched,
                        streams = allStreams,
                        season = seasonArg,
                        episode = episodeArg,
                        installedAddonNames = installedAddonNames,
                    ) { debridPreparationLaunched = true }
                    scheduleSourceBadgeApplication()
                }

                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSourceStreams = false,
                            sourceStreamsError = result.message
                        )
                    }
                }

                NetworkResult.Loading -> {
                    _uiState.update { it.copy(isLoadingSourceStreams = true) }
                }
            }
        }
        sourceStreamsFetchCompleted = true
        markRemainingSourceChipsAsError()
    }
}

/**
 * Merge fresh stream results with previously cached streams.
 * Newer entries for the same stream (matched by addon + url/infoHash) replace older ones.
 */
private fun mergeSourceStreams(cached: List<Stream>, fresh: List<Stream>): List<Stream> {
    val merged = LinkedHashMap<String, Stream>()
    cached.forEach { stream -> merged[stream.mergeKey()] = stream }
    fresh.forEach { stream -> merged[stream.mergeKey()] = stream }
    return merged.values.toList()
}

private fun Stream.mergeKey(): String =
    infoHash?.lowercase()?.let { hash -> "$addonName|$hash:${fileIdx ?: ""}" }
        ?: "$addonName|${getStreamUrl() ?: externalUrl ?: ytId ?: "${name}:${title}"}"

private fun PlayerRuntimeController.launchSourceDebridPreparationIfNeeded(
    launched: Boolean,
    streams: List<Stream>,
    season: Int?,
    episode: Int?,
    installedAddonNames: Set<String>,
    markLaunched: () -> Unit
) {
    if (launched || streams.none { it.isReadyForDebridPreparation() }) {
        return
    }
    markLaunched()
    scope.launch {
        val playerSettings = playerSettingsDataStore.playerSettings.first()
        directDebridStreamPreparer.prepare(
            streams = streams,
            season = season,
            episode = episode,
            playerSettings = playerSettings,
            installedAddonNames = installedAddonNames
        ) { original, prepared ->
            replacePreparedSourceStream(original, prepared)
        }
    }
}

private fun PlayerRuntimeController.replacePreparedSourceStream(
    original: Stream,
    prepared: Stream
) {
    _uiState.update { state ->
        val updatedStreams = replacePreparedFlatStreams(
            streams = state.sourceAllStreams,
            original = original,
            prepared = prepared
        )
        if (updatedStreams == state.sourceAllStreams) {
            state
        } else {
            val selectedAddon = state.sourceSelectedAddonFilter
            state.copy(
                sourceAllStreams = updatedStreams,
                sourceFilteredStreams = updatedStreams.filterByAddon(selectedAddon)
            )
        }
    }
}

internal fun PlayerRuntimeController.dismissSourcesPanel() {
    sourceStreamsScope?.cancel()
    sourceStreamsScope = null
    sourceStreamsJob = null
    sourceChipErrorDismissJob?.cancel()
    streamRepository.setLocalPluginSearchPaused(true)
    _uiState.update {
        it.copy(
            showSourcesPanel = false,
            isLoadingSourceStreams = false
        )
    }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.filterSourceStreamsByAddon(addonName: String?) {
    val allStreams = _uiState.value.sourceAllStreams
    val filteredStreams = if (addonName == null) {
        allStreams
    } else {
        allStreams.filter { it.addonName == addonName }
    }
    _uiState.update {
        it.copy(
            sourceSelectedAddonFilter = addonName,
            sourceFilteredStreams = filteredStreams
        )
    }
}

private suspend fun PlayerRuntimeController.updateSourceChipsForFetchStart(
    type: String,
    videoId: String,
    installedAddons: List<com.nuvio.tv.domain.model.Addon>
) {
    val addonNames = installedAddons
        .filter { it.supportsStreamResourceForChip(type, videoId) }
        .map { it.displayName }

    val pluginNames = try {
        if (pluginManager.pluginsEnabled.first()) {
            val mediaType = when (type.lowercase()) {
                "series", "tv", "show" -> "tv"
                else -> type.lowercase()
            }
            val groupByRepository = pluginManager.groupStreamsByRepository.first()
            val scrapers = pluginManager.enabledScrapers.first()
                .filter { it.supportsType(mediaType) }
            if (groupByRepository) {
                val repositoriesById = pluginManager.repositories.first().associateBy { it.id }
                scrapers
                    .map { scraper ->
                        repositoriesById[scraper.repositoryId]?.name?.takeIf { it.isNotBlank() } ?: scraper.name
                    }
                    .distinct()
            } else {
                scrapers
                    .map { it.name }
                    .distinct()
            }
        } else {
            emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    val ordered = (addonNames + pluginNames).distinct()
    _uiState.update {
        it.copy(
            sourceChips = ordered.map { name -> SourceChipItem(name, SourceChipStatus.LOADING) }
        )
    }
}

private fun PlayerRuntimeController.mergeSourceChipStatuses(
    existing: List<SourceChipItem>,
    succeededNames: List<String>
): List<SourceChipItem> {
    if (succeededNames.isEmpty()) return existing
    if (existing.isEmpty()) {
        return succeededNames.distinct().map { SourceChipItem(it, SourceChipStatus.SUCCESS) }
    }

    val successSet = succeededNames.toSet()
    val updated = existing.map { chip ->
        if (chip.name in successSet) chip.copy(status = SourceChipStatus.SUCCESS) else chip
    }.toMutableList()

    val known = updated.map { it.name }.toSet()
    succeededNames.forEach { name ->
        if (name !in known) updated += SourceChipItem(name, SourceChipStatus.SUCCESS)
    }
    return updated
}

private fun PlayerRuntimeController.markRemainingSourceChipsAsError() {
    var markedAnyError = false
    _uiState.update { state ->
        if (!state.sourceChips.any { it.status == SourceChipStatus.LOADING }) return@update state
        markedAnyError = true
        state.copy(
            sourceChips = state.sourceChips.map { chip ->
                if (chip.status == SourceChipStatus.LOADING) {
                    chip.copy(status = SourceChipStatus.ERROR)
                } else {
                    chip
                }
            }
        )
    }
    if (!markedAnyError) return

    sourceChipErrorDismissJob?.cancel()
    sourceChipErrorDismissJob = scope.launch {
        delay(1600L)
        _uiState.update { state ->
            state.copy(
                sourceChips = state.sourceChips.filterNot { it.status == SourceChipStatus.ERROR }
            )
        }
    }
}

private suspend fun PlayerRuntimeController.updateEpisodeSourceChipsForFetchStart(
    type: String,
    videoId: String,
    installedAddons: List<com.nuvio.tv.domain.model.Addon>
) {
    val addonNames = installedAddons
        .filter { it.supportsStreamResourceForChip(type, videoId) }
        .map { it.displayName }

    val pluginNames = try {
        if (pluginManager.pluginsEnabled.first()) {
            val mediaType = when (type.lowercase()) {
                "series", "tv", "show" -> "tv"
                else -> type.lowercase()
            }
            val groupByRepository = pluginManager.groupStreamsByRepository.first()
            val scrapers = pluginManager.enabledScrapers.first()
                .filter { it.supportsType(mediaType) }
            if (groupByRepository) {
                val repositoriesById = pluginManager.repositories.first().associateBy { it.id }
                scrapers
                    .map { scraper ->
                        repositoriesById[scraper.repositoryId]?.name?.takeIf { it.isNotBlank() } ?: scraper.name
                    }
                    .distinct()
            } else {
                scrapers
                    .map { it.name }
                    .distinct()
            }
        } else {
            emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    val ordered = (addonNames + pluginNames).distinct()
    _uiState.update {
        it.copy(
            episodeSourceChips = ordered.map { name -> SourceChipItem(name, SourceChipStatus.LOADING) }
        )
    }
}

private fun PlayerRuntimeController.markRemainingEpisodeSourceChipsAsError() {
    var markedAnyError = false
    _uiState.update { state ->
        if (!state.episodeSourceChips.any { it.status == SourceChipStatus.LOADING }) return@update state
        markedAnyError = true
        state.copy(
            episodeSourceChips = state.episodeSourceChips.map { chip ->
                if (chip.status == SourceChipStatus.LOADING) {
                    chip.copy(status = SourceChipStatus.ERROR)
                } else {
                    chip
                }
            }
        )
    }
    if (!markedAnyError) return

    scope.launch {
        delay(1600L)
        _uiState.update { state ->
            state.copy(
                episodeSourceChips = state.episodeSourceChips.filterNot { it.status == SourceChipStatus.ERROR }
            )
        }
    }
}

private fun com.nuvio.tv.domain.model.Addon.supportsStreamResourceForChip(type: String, videoId: String): Boolean {
    return resources.any { resource ->
        resource.name == "stream" &&
            (resource.types.isEmpty() || resource.types.any { it.equals(type, ignoreCase = true) }) &&
            run {
                val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
                    ?: idPrefixes.takeIf { it.isNotEmpty() }
                prefixes == null || prefixes.any { prefix -> videoId.startsWith(prefix) }
            }
    }
}

private fun PlayerRuntimeController.applySelectedStreamState(
    stream: Stream,
    url: String,
    headers: Map<String, String>
) {
    val playbackRequest = PlayerMediaSourceFactory.normalizePlaybackRequest(url, headers)
    currentStreamUrl = playbackRequest.url
    currentHeaders = playbackRequest.headers
    currentFilename = stream.behaviorHints?.filename ?: navigationArgs.filename
    currentStreamResponseHeaders = stream.behaviorHints?.proxyHeaders?.response.orEmpty()
    currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
        url = playbackRequest.url,
        filename = currentFilename,
        responseHeaders = currentStreamResponseHeaders
    )
    parsingErrorProbeAttempted = false
    applyStreamMetadata(stream)
}

/**
 * Apply stream metadata that is common to both HTTP and torrent paths.
 * Ensures binge-group, addon info, and video hints are always set regardless
 * of stream type — critical for next-episode binge matching.
 */
private fun PlayerRuntimeController.applyStreamMetadata(stream: Stream) {
    currentStreamBingeGroup = stream.behaviorHints?.bingeGroup
    currentVideoHash = stream.behaviorHints?.videoHash
    currentVideoSize = stream.behaviorHints?.videoSize
    streamSubtitles = stream.subtitles
    currentAddonName = stream.addonName
    currentAddonLogo = stream.addonLogo
    currentStreamDescription = stream.description
    currentVideoCodec = null
    currentVideoWidth = null
    currentVideoHeight = null
    currentVideoBitrate = null

    // Persist binge group per content so subsequent episode plays
    // (from CW, Details, or next-episode) can reuse the same source group.
    val bg = stream.behaviorHints?.bingeGroup
    val cid = contentId
    if (cid != null) {
        scope.launch(kotlinx.coroutines.NonCancellable) {
            bingeGroupCacheDataStore.replace(cid, bg)
        }
    }
}

private fun PlayerRuntimeController.persistSelectedStreamForReuse(
    stream: Stream,
    url: String,
    headers: Map<String, String>
) {
    if (!streamReuseLastLinkEnabled) return

    val key = streamCacheKey ?: return
    val streamName = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName)?.takeIf { it.isNotBlank() }
        ?: title

    scope.launch {
        streamLinkCacheDataStore.save(
            contentKey = key,
            url = url,
            streamName = streamName,
            headers = headers,
            filename = currentFilename,
            videoHash = currentVideoHash,
            videoSize = currentVideoSize,
            bingeGroup = stream.behaviorHints?.bingeGroup,
            contentLanguage = contentLanguage,
            year = year
        )
    }
}

private fun PlayerRuntimeController.persistTorrentStreamForReuse(stream: Stream) {
    if (!streamReuseLastLinkEnabled) return

    val key = streamCacheKey ?: return
    val infoHash = stream.getEffectiveInfoHash() ?: return
    val streamName = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName)?.takeIf { it.isNotBlank() }
        ?: title

    scope.launch {
        streamLinkCacheDataStore.save(
            contentKey = key,
            url = "",
            streamName = streamName,
            headers = emptyMap(),
            filename = stream.behaviorHints?.filename,
            videoHash = stream.behaviorHints?.videoHash,
            videoSize = stream.behaviorHints?.videoSize,
            infoHash = infoHash,
            fileIdx = stream.getEffectiveFileIdx(),
            sources = stream.sources,
            bingeGroup = stream.behaviorHints?.bingeGroup,
            contentLanguage = contentLanguage,
            year = year
        )
    }
}

private fun PlayerRuntimeController.openExternalStreamInBrowser(
    stream: Stream,
    fromEpisodePanel: Boolean
): Boolean {
    if (!stream.isExternal()) return false

    val externalUrl = stream.getStreamUrl()
    if (externalUrl.isNullOrBlank()) {
        _uiState.update {
            if (fromEpisodePanel) {
                it.copy(episodeStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_external_url))
            } else {
                it.copy(sourceStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_external_url))
            }
        }
        return true
    }

    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching {
        context.startActivity(browserIntent)
    }.onSuccess {
        _uiState.update {
            if (fromEpisodePanel) {
                it.copy(
                    showEpisodesPanel = false,
                    showEpisodeStreams = false,
                    isLoadingEpisodeStreams = false,
                    episodeStreamsError = null
                )
            } else {
                it.copy(
                    showSourcesPanel = false,
                    isLoadingSourceStreams = false,
                    sourceStreamsError = null
                )
            }
        }
    }.onFailure { error ->
        _uiState.update {
            if (fromEpisodePanel) {
                it.copy(episodeStreamsError = error.message ?: context.getString(com.nuvio.tv.R.string.player_stream_error_open_external_link_failed))
            } else {
                it.copy(sourceStreamsError = error.message ?: context.getString(com.nuvio.tv.R.string.player_stream_error_open_external_link_failed))
            }
        }
    }

    return true
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.switchToSourceStream(
    stream: Stream
) {
    sourceStreamsScope?.cancel()
    sourceStreamsScope = null
    sourceStreamsJob = null
    streamRepository.setLocalPluginSearchPaused(true)
    if (openExternalStreamInBrowser(stream = stream, fromEpisodePanel = false)) {
        return
    }

    if (stream.isTorrent()) {
        debridResolveJob?.cancel()
        _uiState.update { it.copy(isLoadingSourceStreams = true, sourceStreamsError = null) }
        debridResolveJob = scope.launch {
            val resolved = resolveDirectDebridStreamIfNeeded(stream, currentSeason, currentEpisode)
            debridResolveJob = null
            if (resolved != null && !resolved.getStreamUrl().isNullOrBlank()) {
                switchToSourceStream(resolved)
            } else if (resolved != null) {
                switchToTorrentSourceStream(resolved)
            } else {
                _uiState.update {
                    it.copy(
                        isLoadingSourceStreams = false,
                        sourceStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_url)
                    )
                }
            }
        }
        return
    }

    val url = stream.getStreamUrl()
    if (url.isNullOrBlank()) {
        if (stream.isDirectDebrid()) {
            debridResolveJob?.cancel()
            _uiState.update { it.copy(isLoadingSourceStreams = true, sourceStreamsError = null) }
            debridResolveJob = scope.launch {
                val resolved = resolveDirectDebridStreamIfNeeded(stream, currentSeason, currentEpisode)
                if (resolved != null && !resolved.getStreamUrl().isNullOrBlank()) {
                    debridResolveJob = null
                    switchToSourceStream(resolved)
                } else {
                    debridResolveJob = null
                    _uiState.update {
                        it.copy(
                            isLoadingSourceStreams = false,
                            sourceStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_url)
                        )
                    }
                }
            }
            return
        }
        _uiState.update { it.copy(sourceStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_url)) }
        return
    }

    // Stop any active torrent before switching to HTTP stream
    stopTorrentStream()

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null

    flushPlaybackSnapshotForSwitchOrExit()

    val newHeaders = PlayerMediaSourceFactory.sanitizeHeaders(
        stream.behaviorHints?.proxyHeaders?.request
    )

    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)

    applySelectedStreamState(
        stream = stream,
        url = url,
        headers = newHeaders
    )
    val playbackUrl = currentStreamUrl
    val playbackHeaders = currentHeaders
    persistSelectedStreamForReuse(stream = stream, url = playbackUrl, headers = playbackHeaders)

    // Reset stream-state error flags for the new stream.
    hasRetriedCurrentStreamAfter416 = false
    resetErrorRetryState()
    hasRetriedCurrentStreamAfterUnexpectedNpe = false
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = false
    hasRetriedAfterMimeOverrideClear = false
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    lastSavedPosition = 0L
    _exoPlayer?.stop()
    resetLoadingOverlayForNewStream()

    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = playbackUrl,
            currentStreamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash,
            currentStreamFileIdx = stream.clientResolve?.fileIdx,
            currentStreamAddonName = stream.addonName,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            showSourcesPanel = false,
            isLoadingSourceStreams = false,
            sourceStreamsError = null,
            isTorrentStream = false,
            // AFR review F2/R2: reset detection state on a source switch so the
            // preflight for the new stream isn't a guaranteed no-op. Without
            // this, detectedFrameRateSource stays set (the TRACK path populates
            // it during normal playback) and the skip-guard in the preflight
            // silently kept the previous stream's refresh rate - mixed-frame-
            // rate series (25 fps HDTV next to 23.976 WEB-DL) never re-matched.
            detectedFrameRate = 0f,
            detectedFrameRateRaw = 0f,
            detectedFrameRateSource = null
        )
    }
    // Refresh the filename for the NEW stream before anything derives state
    // from it (the AFR cache key below, createMediaSource's filename, media
    // session metadata). This function never updated it, so a source switch
    // carried the previous stream's filename forward. Same pattern as the
    // initial-play and torrent-switch paths.
    currentFilename = stream.behaviorHints?.filename ?: navigationArgs.filename
    showStreamSourceIndicator(stream)
    resetPostPlayOverlayState(clearEpisode = false)

    _exoPlayer?.let { player ->
        scope.launch {
            try {
                val playerSettings = playerSettingsDataStore.playerSettings.first()
                // nt6 AFR option 1: this branch is ExoPlayer-only (_exoPlayer
                // scope), so use the cache-only preflight; the new stream's
                // track format drives the switch on a cache miss.
                // P-F3: bump the generation so an in-flight track-AFR
                // coroutine from the previous stream stands down.
                afrTrackGeneration++
                trackAfrAttemptedForCurrentStream = false
                afrTrackSwitchInFlight = false
                afrModeAppliedPreStart = false
                afrSeededRateRaw = 0f
                runAfrCachePreflightIfEnabled(
                    url = playbackUrl,
                    headers = playbackHeaders,
                    frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                    resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled
                )
                player.setMediaSource(
                    mediaSourceFactory.createMediaSource(
                        context = context,
                        url = playbackUrl,
                        headers = playbackHeaders,
                        filename = currentFilename,
                        responseHeaders = currentStreamResponseHeaders,
                        mimeTypeOverride = currentStreamMimeType,
                        audioDelayUsProvider = audioDelayUs::get
                    )
                )
                player.playWhenReady = true
                player.prepare()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: context.getString(com.nuvio.tv.R.string.player_error_play_stream_failed)) }
            }
        }
    } ?: run {
        initializePlayer(playbackUrl, playbackHeaders)
    }

    loadSavedProgressFor(currentSeason, currentEpisode)
}

internal fun PlayerRuntimeController.dismissEpisodesPanel() {
    episodeStreamsScope?.cancel()
    episodeStreamsScope = null
    episodeStreamsJob = null
    streamRepository.setLocalPluginSearchPaused(true)
    _uiState.update {
        it.copy(
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false
        )
    }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.selectEpisodesSeason(season: Int) {
    val all = _uiState.value.episodesAll
    if (all.isEmpty()) return

    val seasons = _uiState.value.episodesAvailableSeasons
    if (seasons.isNotEmpty() && season !in seasons) return

    val episodesForSeason = all
        .filter { (it.season ?: -1) == season }
        .sortedWith(compareBy<Video> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })

    _uiState.update {
        it.copy(
            episodesSelectedSeason = season,
            episodes = episodesForSeason
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.switchToTorrentSourceStream(
    stream: Stream
) {
    val infoHash = stream.getEffectiveInfoHash() ?: return
    sourceStreamsScope?.cancel()
    sourceStreamsScope = null
    sourceStreamsJob = null
    stopTorrentStream()
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    flushPlaybackSnapshotForSwitchOrExit()
    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)
    hasRetriedCurrentStreamAfter416 = false
    errorRetryCount = 0
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    lastSavedPosition = 0L
    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = "",
            currentStreamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash,
            currentStreamFileIdx = stream.clientResolve?.fileIdx,
            currentStreamAddonName = stream.addonName,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            showSourcesPanel = false,
            isLoadingSourceStreams = false,
            sourceStreamsError = null,
            isTorrentStream = true
        )
    }
    applyStreamMetadata(stream)
    currentFilename = stream.behaviorHints?.filename ?: navigationArgs.filename
    showStreamSourceIndicator(stream)
    resetPostPlayOverlayState(clearEpisode = false)
    launchTorrentSourceStream(stream, infoHash, loadSavedProgress = true)
    persistTorrentStreamForReuse(stream)
}

private fun PlayerRuntimeController.switchToTorrentEpisodeStream(
    stream: Stream,
    forcedTargetVideo: Video?,
    isAutoPlay: Boolean
) {
    val infoHash = stream.getEffectiveInfoHash() ?: return
    consecutiveAutoPlayCount = nextConsecutiveAutoPlayCount(
        currentCount = consecutiveAutoPlayCount,
        isAutoPlay = isAutoPlay
    )
    stopTorrentStream()
    switchToEpisodeStreamCommon(stream, forcedTargetVideo)
    launchTorrentSourceStream(stream, infoHash, loadSavedProgress = true)
    persistTorrentStreamForReuse(stream)
}

internal fun PlayerRuntimeController.loadEpisodesIfNeeded() {
    val type = contentType
    val id = contentId
    if (type.isNullOrBlank() || id.isNullOrBlank()) return
    if (type !in listOf("series", "tv")) return
    if (_uiState.value.episodesAll.isNotEmpty() || _uiState.value.isLoadingEpisodes) return

    scope.launch {
        _uiState.update { it.copy(isLoadingEpisodes = true, episodesError = null) }

        when (
            val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is NetworkResult.Loading }
        ) {
            is NetworkResult.Success -> {
                val allEpisodes = result.data.videos
                    .sortedWith(
                        compareBy<Video> { it.season ?: Int.MAX_VALUE }
                            .thenBy { it.episode ?: Int.MAX_VALUE }
                            .thenBy { it.title }
                    )

                applyMetaDetails(result.data)

                val seasons = allEpisodes
                    .mapNotNull { it.season }
                    .distinct()
                    .sorted()

                val preferredSeason = when {
                    currentSeason != null && seasons.contains(currentSeason) -> currentSeason
                    initialSeason != null && seasons.contains(initialSeason) -> initialSeason
                    else -> seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1
                }

                val selectedSeason = preferredSeason ?: 1
                val episodesForSeason = allEpisodes
                    .filter { (it.season ?: -1) == selectedSeason }
                    .sortedWith(compareBy<Video> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })

                _uiState.update {
                    it.copy(
                        isLoadingEpisodes = false,
                        episodesAll = allEpisodes,
                        episodesAvailableSeasons = seasons,
                        episodesSelectedSeason = selectedSeason,
                        episodes = episodesForSeason,
                        episodesError = null
                    )
                }
            }

            is NetworkResult.Error -> {
                _uiState.update { it.copy(isLoadingEpisodes = false, episodesError = result.message) }
            }

            NetworkResult.Loading -> {
            }
        }
    }
}

internal fun PlayerRuntimeController.loadStreamsForEpisode(video: Video) {
    loadStreamsForEpisode(video = video, forceRefresh = false)
}

internal fun PlayerRuntimeController.buildEpisodeRequestKey(type: String, video: Video): String {
    return "$type|${video.id}|${video.season ?: -1}|${video.episode ?: -1}"
}

internal fun PlayerRuntimeController.loadStreamsForEpisode(video: Video, forceRefresh: Boolean) {
    streamRepository.setLocalPluginSearchPaused(false)
    val type = contentType
    if (type.isNullOrBlank()) {
        _uiState.update { it.copy(episodeStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_missing_content_type)) }
        return
    }

    val requestKey = buildEpisodeRequestKey(type = type, video = video)
    val state = _uiState.value
    val hasCachedPayload = state.episodeAllStreams.isNotEmpty() || state.episodeStreamsError != null
    if (!forceRefresh && requestKey == episodeStreamsCacheRequestKey && hasCachedPayload) {
        _uiState.update {
            it.copy(
                showEpisodeStreams = true,
                isLoadingEpisodeStreams = false,
                episodeStreamsForVideoId = video.id,
                episodeStreamsSeason = video.season,
                episodeStreamsEpisode = video.episode,
                episodeStreamsTitle = video.title
            )
        }
        return
    }

    val targetChanged = requestKey != episodeStreamsCacheRequestKey
    episodeStreamsScope?.cancel()
    episodeStreamsScope = null
    episodeStreamsJob = null
    val newScope = kotlinx.coroutines.CoroutineScope(scope.coroutineContext + kotlinx.coroutines.SupervisorJob())
    episodeStreamsScope = newScope
    episodeStreamsJob = newScope.launch {
        episodeStreamsCacheRequestKey = requestKey
        val previousAddonFilter = _uiState.value.episodeSelectedAddonFilter
        _uiState.update {
            it.copy(
                showEpisodeStreams = true,
                isLoadingEpisodeStreams = true,
                episodeStreamsError = null,
                episodeAllStreams = if (forceRefresh || targetChanged) emptyList() else it.episodeAllStreams,
                episodeSelectedAddonFilter = if (forceRefresh || targetChanged) null else it.episodeSelectedAddonFilter,
                episodeFilteredStreams = if (forceRefresh || targetChanged) emptyList() else it.episodeFilteredStreams,
                episodeAvailableAddons = if (forceRefresh || targetChanged) emptyList() else it.episodeAvailableAddons,
                episodeSourceChips = if (forceRefresh || targetChanged) emptyList() else it.episodeSourceChips,
                episodeStreamsForVideoId = video.id,
                episodeStreamsSeason = video.season,
                episodeStreamsEpisode = video.episode,
                episodeStreamsTitle = video.title
            )
        }

        val installedAddons = addonRepository.getInstalledAddons().first().enabledAddons()
        val installedAddonOrder = installedAddons.map { it.displayName }
        val installedAddonNames = installedAddonOrder.toSet()
        var debridPreparationLaunched = false

        // Initialize episode source chips with LOADING status
        updateEpisodeSourceChipsForFetchStart(type, video.id, installedAddons)

        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = video.id,
            season = video.season,
            episode = video.episode,
            forceRefresh = forceRefresh
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val addonStreams = StreamAutoPlaySelector.orderAddonStreams(result.data, installedAddonOrder)
                    val allStreams = addonStreams.flatMap { it.streams }
                    val availableAddons = addonStreams.map { it.addonName }
                    val currentFilter = _uiState.value.episodeSelectedAddonFilter
                    val filteredStreams = if (currentFilter == null) {
                        allStreams
                    } else {
                        allStreams.filter { it.addonName == currentFilter }
                    }
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodeStreams = false,
                            episodeAllStreams = allStreams,
                            episodeFilteredStreams = filteredStreams,
                            episodeAvailableAddons = availableAddons,
                            episodeSourceChips = mergeSourceChipStatuses(
                                existing = it.episodeSourceChips,
                                succeededNames = addonStreams.map { group -> group.addonName }
                            ),
                            episodeStreamsError = null
                        )
                    }
                    launchEpisodeDebridPreparationIfNeeded(
                        launched = debridPreparationLaunched,
                        streams = allStreams,
                        season = video.season,
                        episode = video.episode,
                        installedAddonNames = installedAddonNames,
                    ) { debridPreparationLaunched = true }
                    scheduleEpisodeBadgeApplication()
                }

                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodeStreams = false,
                            episodeStreamsError = result.message
                        )
                    }
                }

                NetworkResult.Loading -> {
                    _uiState.update { it.copy(isLoadingEpisodeStreams = true) }
                }
            }
        }
        markRemainingEpisodeSourceChipsAsError()
    }
}

private fun PlayerRuntimeController.launchEpisodeDebridPreparationIfNeeded(
    launched: Boolean,
    streams: List<Stream>,
    season: Int?,
    episode: Int?,
    installedAddonNames: Set<String>,
    markLaunched: () -> Unit
) {
    if (launched || streams.none { it.isReadyForDebridPreparation() }) {
        return
    }
    markLaunched()
    scope.launch {
        val playerSettings = playerSettingsDataStore.playerSettings.first()
        directDebridStreamPreparer.prepare(
            streams = streams,
            season = season,
            episode = episode,
            playerSettings = playerSettings,
            installedAddonNames = installedAddonNames
        ) { original, prepared ->
            replacePreparedEpisodeStream(original, prepared)
        }
    }
}

private fun PlayerRuntimeController.replacePreparedEpisodeStream(
    original: Stream,
    prepared: Stream
) {
    _uiState.update { state ->
        val updatedStreams = replacePreparedFlatStreams(
            streams = state.episodeAllStreams,
            original = original,
            prepared = prepared
        )
        if (updatedStreams == state.episodeAllStreams) {
            state
        } else {
            val selectedAddon = state.episodeSelectedAddonFilter
            state.copy(
                episodeAllStreams = updatedStreams,
                episodeFilteredStreams = updatedStreams.filterByAddon(selectedAddon)
            )
        }
    }
}

private fun PlayerRuntimeController.replacePreparedFlatStreams(
    streams: List<Stream>,
    original: Stream,
    prepared: Stream
): List<Stream> {
    if (streams.isEmpty()) return streams
    return directDebridStreamPreparer.replacePreparedStream(
        groups = listOf(
            AddonStreams(
                addonName = "",
                addonLogo = null,
                streams = streams
            )
        ),
        original = original,
        prepared = prepared
    ).firstOrNull()?.streams ?: streams
}

private fun List<Stream>.filterByAddon(addonName: String?): List<Stream> =
    if (addonName == null) {
        this
    } else {
        filter { it.addonName == addonName }
    }

internal fun PlayerRuntimeController.reloadEpisodeStreams() {
    val state = _uiState.value
    val targetVideoId = state.episodeStreamsForVideoId
    val targetVideo = sequenceOf(
        state.episodes.firstOrNull { it.id == targetVideoId },
        state.episodesAll.firstOrNull { it.id == targetVideoId },
        state.episodes.firstOrNull {
            it.season == state.episodeStreamsSeason && it.episode == state.episodeStreamsEpisode
        },
        state.episodesAll.firstOrNull {
            it.season == state.episodeStreamsSeason && it.episode == state.episodeStreamsEpisode
        }
    ).firstOrNull { it != null }

    if (targetVideo != null) {
        loadStreamsForEpisode(video = targetVideo, forceRefresh = true)
    }
}

internal fun PlayerRuntimeController.switchToEpisodeStream(
    stream: Stream,
    forcedTargetVideo: Video? = null,
    isAutoPlay: Boolean = false
) {
    if (openExternalStreamInBrowser(stream = stream, fromEpisodePanel = true)) {
        return
    }

    if (stream.isTorrent()) {
        val resolveSeason = forcedTargetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
        val resolveEpisode = forcedTargetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
        debridResolveJob?.cancel()
        _uiState.update { it.copy(isLoadingEpisodeStreams = true, episodeStreamsError = null) }
        debridResolveJob = scope.launch {
            val resolved = resolveDirectDebridStreamIfNeeded(stream, resolveSeason, resolveEpisode)
            debridResolveJob = null
            if (resolved != null && !resolved.getStreamUrl().isNullOrBlank()) {
                switchToEpisodeStream(resolved, forcedTargetVideo, isAutoPlay)
            } else if (resolved != null) {
                switchToTorrentEpisodeStream(resolved, forcedTargetVideo, isAutoPlay)
            } else {
                _uiState.update {
                    it.copy(
                        isLoadingEpisodeStreams = false,
                        episodeStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_url)
                    )
                }
            }
        }
        return
    }

    val url = stream.getStreamUrl()
    if (url.isNullOrBlank()) {
        if (stream.isDirectDebrid()) {
            val resolveSeason = forcedTargetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
            val resolveEpisode = forcedTargetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
            debridResolveJob?.cancel()
            _uiState.update { it.copy(isLoadingEpisodeStreams = true, episodeStreamsError = null) }
            debridResolveJob = scope.launch {
                val resolved = resolveDirectDebridStreamIfNeeded(stream, resolveSeason, resolveEpisode)
                if (resolved != null && !resolved.getStreamUrl().isNullOrBlank()) {
                    debridResolveJob = null
                    switchToEpisodeStream(resolved, forcedTargetVideo, isAutoPlay)
                } else {
                    debridResolveJob = null
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodeStreams = false,
                            episodeStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_url)
                        )
                    }
                }
            }
            return
        }
        _uiState.update { it.copy(episodeStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_url)) }
        return
    }

    consecutiveAutoPlayCount = nextConsecutiveAutoPlayCount(
        currentCount = consecutiveAutoPlayCount,
        isAutoPlay = isAutoPlay,
    )

    // Stop any active torrent before switching to HTTP stream
    stopTorrentStream()

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null

    flushPlaybackSnapshotForSwitchOrExit()

    // Pause current playback immediately so the old stream doesn't continue
    // playing audio/video in the background while the new episode is being prepared.
    _exoPlayer?.stop()

    val newHeaders = PlayerMediaSourceFactory.sanitizeHeaders(
        stream.behaviorHints?.proxyHeaders?.request
    )

    // nt13: the URL and headers are final here, the outgoing stream is already
    // stopped, and the player is still ~1.3-3.1 s away from opening the
    // datasource (measured across five transitions, 27 Jul 2026). Start chunk 0
    // into that gap. Fresh presses deliberately do not call this, so every
    // capture carries its own control arm.
    //
    // nt14: the settings push must happen first. initializePlayer does it too,
    // but that runs after this point, so without it the pre-start keys its
    // session on the previous stream's geometry.
    //
    // nt16: from the cached snapshot, synchronously. nt14 read the settings Flow
    // here, and that Flow is cold -- every transition paid a full datastore read.
    // Measured 27 Jul: chunk 0 started 982 and 935 ms after the URL was final,
    // against 79 ms when this hook was synchronous. That was most of the head
    // start the hook exists to create, spent acquiring settings that the previous
    // initializePlayer had already resolved.
    //
    // A null snapshot means no playback has initialised yet, which cannot happen
    // on a transition. Stale settings (changed mid-session, before the next
    // initializePlayer) key the session on the wrong geometry, the player
    // declines to adopt it, and the path falls back to opening as it always did.
    lastAppliedPlayerSettings?.let { cachedSettings ->
        applyMediaSourceFactorySettings(cachedSettings)
        runCatching {
            mediaSourceFactory.prestartChunk0(
                url = url,
                headers = newHeaders,
                filename = stream.behaviorHints?.filename
            )
        }
    }

    val targetVideo = forcedTargetVideo
        ?: _uiState.value.episodes.firstOrNull { it.id == _uiState.value.episodeStreamsForVideoId }

    currentStreamBingeGroup = stream.behaviorHints?.bingeGroup
    currentVideoHash = stream.behaviorHints?.videoHash
    currentVideoSize = stream.behaviorHints?.videoSize
    currentFilename = stream.behaviorHints?.filename
        ?: url.substringBefore('?').substringAfterLast('/', "")
            .takeIf { it.isNotBlank() && it.contains('.') }
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    attachedAddonSubtitleKeys = emptySet()

    applySelectedStreamState(
        stream = stream,
        url = url,
        headers = newHeaders
    )
    val playbackUrl = currentStreamUrl
    val playbackHeaders = currentHeaders
    persistedTrackPreference = null
    losslessAudioDefaultAppliedForStream = false
    persistedAudioPreferenceSeenForStream = false
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    hasRetriedCurrentStreamAfter416 = false
    resetErrorRetryState()
    currentVideoId = targetVideo?.id ?: _uiState.value.episodeStreamsForVideoId ?: currentVideoId
    currentSeason = targetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
    currentEpisode = targetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
    currentEpisodeTitle = targetVideo?.title ?: _uiState.value.episodeStreamsTitle ?: currentEpisodeTitle
    persistSelectedStreamForReuse(stream = stream, url = playbackUrl, headers = playbackHeaders)
    currentTraktEpisodeMapping = null
    currentTraktEpisodeMappingKey = null
    lastSavedPosition = 0L

    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentVideoId = currentVideoId,
            currentEpisodeTitle = currentEpisodeTitle,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = playbackUrl,
            currentStreamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash,
            currentStreamFileIdx = stream.clientResolve?.fileIdx,
            currentStreamAddonName = stream.addonName,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false,
            episodeStreamsError = null,
            isTorrentStream = false,

            parentalWarnings = emptyList(),
            showParentalGuide = false,
            parentalGuideHasShown = false,

            activeSkipInterval = null,
            skipIntervalDismissed = false,
            postPlayMode = null,
            postPlayDismissedForCurrentEpisode = true,
            playbackEnded = false,
            // AFR review F2/R2: next-episode switches never re-evaluated AFR -
            // the previous episode's TRACK detection made the preflight guard a
            // guaranteed no-op. Reset so each episode re-matches.
            detectedFrameRate = 0f,
            detectedFrameRateRaw = 0f,
            detectedFrameRateSource = null,
            isNextEpisodeMetadataResolved = false,
        )
    }
    showStreamSourceIndicator(stream)
    recomputeNextEpisode(resetVisibility = true)

    updateEpisodeDescription()

    playbackStartedForParentalGuide = false
    skipIntervals = emptyList()
    skipIntroFetchedKey = null
    lastActiveSkipType = null
    autoSkippedIntervalKeys.clear()

    fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
    fetchSkipIntervals(contentId, currentSeason, currentEpisode)

    queuePlaybackRawEventLine(
        "LINK_SELECTED: source=in_player_source host=${playbackUrl.safeStreamTraceHost()} " +
            "streamName=${stream.name} addon=${stream.addonName} " +
            "contentId=${contentId ?: "n/a"} videoId=${currentVideoId ?: "n/a"} " +
            "S${currentSeason ?: "-"}E${currentEpisode ?: "-"} torrent=false"
    )
    preparePlaybackBeforeStart(
        url = playbackUrl,
        headers = playbackHeaders,
        loadSavedProgress = true
    )
}

private fun String.safeStreamTraceHost(): String {
    return runCatching {
        Uri.parse(this).host ?: substringBefore("://").takeIf { it.isNotBlank() } ?: "unknown"
    }.getOrDefault("unknown")
}

/**
 * Shared episode stream setup used by both torrent and HTTP episode switching.
 */
private fun PlayerRuntimeController.switchToEpisodeStreamCommon(
    stream: Stream,
    forcedTargetVideo: Video? = null
) {
    episodeStreamsScope?.cancel()
    episodeStreamsScope = null
    episodeStreamsJob = null
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    streamRepository.setLocalPluginSearchPaused(true)
    flushPlaybackSnapshotForSwitchOrExit()

    val targetVideo = forcedTargetVideo
        ?: _uiState.value.episodes.firstOrNull { it.id == _uiState.value.episodeStreamsForVideoId }

    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)

    applyStreamMetadata(stream)
    currentFilename = stream.behaviorHints?.filename ?: navigationArgs.filename

    persistedTrackPreference = null
    losslessAudioDefaultAppliedForStream = false
    persistedAudioPreferenceSeenForStream = false
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    // Reset stream-state error flags for the new stream.
    hasRetriedCurrentStreamAfter416 = false
    hasRetriedCurrentStreamAfterUnexpectedNpe = false
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = false
    hasRetriedAfterMimeOverrideClear = false

    currentVideoId = targetVideo?.id ?: _uiState.value.episodeStreamsForVideoId ?: currentVideoId
    currentSeason = targetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
    currentEpisode = targetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
    currentEpisodeTitle = targetVideo?.title ?: _uiState.value.episodeStreamsTitle ?: currentEpisodeTitle
    refreshScrobbleItem()

    lastSavedPosition = 0L
    _exoPlayer?.stop()
    resetLoadingOverlayForNewStream()

    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentEpisodeTitle = currentEpisodeTitle,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = "",
            currentStreamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash,
            currentStreamFileIdx = stream.clientResolve?.fileIdx,
            currentStreamAddonName = stream.addonName,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false,
            episodeStreamsError = null,
            isTorrentStream = true,

            parentalWarnings = emptyList(),
            showParentalGuide = false,
            parentalGuideHasShown = false,

            activeSkipInterval = null,
            skipIntervalDismissed = false,
            postPlayMode = null,
            postPlayDismissedForCurrentEpisode = true,
            playbackEnded = false,
            isNextEpisodeMetadataResolved = false,
        )
    }
    showStreamSourceIndicator(stream)
    recomputeNextEpisode(resetVisibility = true)
    updateEpisodeDescription()
    refreshSubtitlesForCurrentEpisode()

    playbackStartedForParentalGuide = false
    skipIntervals = emptyList()
    skipIntroFetchedKey = null
    lastActiveSkipType = null
    autoSkippedIntervalKeys.clear()

    fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
    fetchSkipIntervals(contentId, currentSeason, currentEpisode)
}

internal fun PlayerRuntimeController.showEpisodeStreamPicker(video: Video, forceRefresh: Boolean = true) {
    _uiState.update {
        it.copy(
            showEpisodesPanel = true,
            showEpisodeStreams = true,
            showSourcesPanel = false,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            episodesSelectedSeason = video.season ?: it.episodesSelectedSeason
        )
    }
    loadEpisodesIfNeeded()
    loadStreamsForEpisode(video = video, forceRefresh = forceRefresh)
}

internal suspend fun PlayerRuntimeController.resolveDirectDebridStreamIfNeeded(
    stream: Stream,
    season: Int?,
    episode: Int?
): Stream? {
    recordLoadingDiagnosticEvent(
        phase = "resolving_debrid",
        message = context.getString(com.nuvio.tv.R.string.player_loading_preparing),
        detail = stream.addonName
    )
    return when (val result = directDebridResolver.resolveToPlayableStream(stream, season, episode)) {
        is DirectDebridPlayableResult.Success -> {
            recordLoadingDiagnosticEvent(
                phase = "resolving_debrid_done",
                message = context.getString(com.nuvio.tv.R.string.player_loading_preparing),
                detail = stream.addonName
            )
            result.stream
        }
        DirectDebridPlayableResult.MissingApiKey,
        DirectDebridPlayableResult.NotCached,
        DirectDebridPlayableResult.Stale,
        DirectDebridPlayableResult.Error -> {
            recordLoadingDiagnosticEvent(
                phase = "resolving_debrid_failed",
                message = context.getString(com.nuvio.tv.R.string.player_loading_preparing),
                detail = result.javaClass.simpleName
            )
            null
        }
    }
}

internal fun PlayerRuntimeController.playNextEpisode(userInitiated: Boolean = false) {
    val nextVideo = nextEpisodeVideo ?: return
    val type = contentType ?: return

    // Instrument (26 Jul capture gap): everything between the press and
    // resolving_debrid was unmeasured, so the addon scrape and the bounded
    // streamAutoPlayTimeoutSeconds wait were both invisible and the transition
    // budget was inferred rather than read. These two lines close that.
    // Logged under TTFF_STAGE so the existing capture tag filter is unchanged.
    val nextEpisodePressElapsedMs = android.os.SystemClock.elapsedRealtime()
    android.util.Log.i(
        "TTFF_STAGE",
        "NEXT_EPISODE_PRESS userInitiated=$userInitiated " +
            "season=${nextVideo.season} episode=${nextVideo.episode}"
    )

    val state = _uiState.value
    val nextInfo = state.nextEpisode ?: return
    if (!nextInfo.hasAired) {
        return
    }
    val activeAutoPlay = state.postPlayMode as? PostPlayMode.AutoPlay
    if (activeAutoPlay != null &&
        (activeAutoPlay.searching || activeAutoPlay.countdownSec != null)
    ) {
        return
    }

    // Follow the episode. Video.runtime is the per-episode value the next-episode
    // resolver already holds, so no lookup and no added latency on the transition.
    // Placed after the early returns so an aborted transition cannot mutate it.
    // Where the addon supplies no per-episode runtime the previous value is kept:
    // same series, so a closer approximation than dropping to null, and every
    // downstream use treats an over-estimate as fail-safe.
    nextVideo.runtime?.let { expectedRuntimeMinutes = it }

    if (type.equals("cloud", ignoreCase = true)) {
        playNextCloudLibraryFile(nextVideo = nextVideo, userInitiated = userInitiated)
        return
    }

    val episodeForMode = state.nextEpisode ?: nextInfo
    _uiState.update {
        it.copy(
            postPlayMode = PostPlayMode.AutoPlay(
                nextEpisode = episodeForMode,
                searching = true,
            ),
            playbackEnded = false,
        )
    }

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = scope.launch {
        try {
            streamRepository.setLocalPluginSearchPaused(false)
            val playerSettings = playerSettingsDataStore.playerSettings.first()
            val shouldAutoSelectInManualMode =
                playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
                    (
                        playerSettings.streamAutoPlayNextEpisodeEnabled ||
                            playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode
                        )
            val bingeGroupOnlyManualMode =
                shouldAutoSelectInManualMode &&
                    (!playerSettings.streamAutoPlayNextEpisodeEnabled ||
                        !playerSettings.streamAutoPlayNextEpisodeFallbackEnabled) &&
                    playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode
            if (playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL && !shouldAutoSelectInManualMode) {
                _uiState.update {
                    it.copy(
                        postPlayMode = null,
                        postPlayDismissedForCurrentEpisode = true,
                    )
                }
                showEpisodeStreamPicker(video = nextVideo, forceRefresh = true)
                return@launch
            }

            val installedAddons = addonRepository.getInstalledAddons().first().enabledAddons()
            val installedAddonOrder = installedAddons.map { it.displayName }
            val effectiveMode = if (shouldAutoSelectInManualMode) {
                StreamAutoPlayMode.FIRST_STREAM
            } else {
                playerSettings.streamAutoPlayMode
            }
            val effectiveSource = if (shouldAutoSelectInManualMode) {
                StreamAutoPlaySource.ALL_SOURCES
            } else {
                playerSettings.streamAutoPlaySource
            }
            val effectiveSelectedAddons = if (shouldAutoSelectInManualMode) {
                emptySet()
            } else {
                playerSettings.streamAutoPlaySelectedAddons
            }
            val effectiveSelectedPlugins = if (shouldAutoSelectInManualMode) {
                emptySet()
            } else {
                playerSettings.streamAutoPlaySelectedPlugins
            }
            val effectiveRegex = if (shouldAutoSelectInManualMode) {
                ""
            } else {
                playerSettings.streamAutoPlayRegex
            }
            var selectedStream: Stream? = null
            var lastSuccessData: List<AddonStreams>? = null
            var autoSelectTriggered = false
            var timeoutElapsed = false
            var lastError: NetworkResult.Error? = null
            // nt7 (task 3): time spent ranking inside the settle window.
            var selectionRankMs = 0L
            var selectionRankCalls = 0
            // Completed as soon as a stream is selected or the addon search
            // finishes, so the waiting code below resumes without polling.
            val searchSettled = CompletableDeferred<Unit>()

            val debridStreamPreferences =
                debridSettingsDataStore.settings.first().streamPreferences

            fun trySelectStreamInner(data: List<AddonStreams>): Stream? {
                val orderedStreams = StreamAutoPlaySelector.orderAddonStreams(data, installedAddonOrder)
                val allStreams = orderedStreams.flatMap { it.streams }
                // preferBingeGroupInSelection was passed explicitly here as the
                // SETTING, while AutoPlaySelection derives it from
                // preferredBingeGroup != null. Those disagree in exactly one
                // case -- setting on, no binge group known -- and that
                // disagreement is inert: selectAutoPlayStream gates the binge
                // branch on targetBingeGroup.isNotEmpty(), false either way.
                // Asserted in StreamAutoPlaySelectorTest.
                return AutoPlaySelection.select(
                    streams = allStreams,
                    inputs = AutoPlaySelection.Inputs(
                        mode = effectiveMode,
                        regexPattern = effectiveRegex,
                        source = effectiveSource,
                        installedAddonNames = installedAddonOrder.toSet(),
                        selectedAddons = effectiveSelectedAddons,
                        selectedPlugins = effectiveSelectedPlugins,
                        preferredBingeGroup = if (playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode) {
                            currentStreamBingeGroup
                        } else {
                            null
                        }
                    ),
                    debridStreamPreferences = debridStreamPreferences,
                    bingeGroupOnly = bingeGroupOnlyManualMode
                )
            }

            fun tryBingeGroupOnlyInner(data: List<AddonStreams>): Stream? {
                if (currentStreamBingeGroup == null || !playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode) return null
                val orderedStreams = StreamAutoPlaySelector.orderAddonStreams(data, installedAddonOrder)
                val allStreams = orderedStreams.flatMap { it.streams }
                // The guard above returns early when currentStreamBingeGroup is
                // null, so the derived preferBingeGroupInSelection is true here
                // exactly as the explicit argument was.
                return AutoPlaySelection.select(
                    streams = allStreams,
                    inputs = AutoPlaySelection.Inputs(
                        mode = effectiveMode,
                        regexPattern = effectiveRegex,
                        source = effectiveSource,
                        installedAddonNames = installedAddonOrder.toSet(),
                        selectedAddons = effectiveSelectedAddons,
                        selectedPlugins = effectiveSelectedPlugins,
                        preferredBingeGroup = currentStreamBingeGroup
                    ),
                    debridStreamPreferences = debridStreamPreferences,
                    bingeGroupOnly = true
                )
            }

            // nt7 (task 3): the 27 Jul capture prices ranking at ~500 ms
            // on this device (PREFETCH rank_only ms=531/496), which would
            // account for nearly all of nt6's one 647 ms settle against
            // 43/48 ms siblings -- but only a measurement on the settle
            // line itself can adjudicate re-rank vs slow-await. These
            // wrappers keep the original names so all call sites are
            // untouched; both local selectors funnel through them.
            fun trySelectStream(data: List<AddonStreams>): Stream? {
                val rankT0 = android.os.SystemClock.elapsedRealtime()
                val result = trySelectStreamInner(data)
                selectionRankMs += android.os.SystemClock.elapsedRealtime() - rankT0
                selectionRankCalls++
                return result
            }

            fun tryBingeGroupOnly(data: List<AddonStreams>): Stream? {
                val rankT0 = android.os.SystemClock.elapsedRealtime()
                val result = tryBingeGroupOnlyInner(data)
                selectionRankMs += android.os.SystemClock.elapsedRealtime() - rankT0
                selectionRankCalls++
                return result
            }

            fun recordSelection(candidate: Stream) {
                autoSelectTriggered = true
                selectedStream = candidate
                searchSettled.complete(Unit)
            }

            val timeoutSeconds = playerSettings.streamAutoPlayTimeoutSeconds

            val innerJob = launch {
                // S5 binge lookahead (part 2): read THROUGH the prefetch cache.
                //
                // This call site went straight to the repository, so the
                // lookahead above would have filled a cache the binge path never
                // consulted. streamsFor() substitutes the flow rather than
                // bypassing this consumer: a hit emits Loading then one Success
                // then completes, which is indistinguishable from a very fast
                // scrape, so the timeout/auto-select machinery below is
                // untouched. A miss, an expired entry or a join timeout falls
                // through to the live flow, i.e. exactly today's behaviour.
                //
                // The win is not only the scrape: with a hit, searchSettled
                // completes almost immediately and the bounded
                // streamAutoPlayTimeoutSeconds wait (3 s on Paul's device) is
                // skipped rather than served.
                com.nuvio.tv.core.stream.StreamPrefetchCache.streamsFor(
                    repository = streamRepository,
                    type = type,
                    videoId = nextVideo.id,
                    season = nextVideo.season,
                    episode = nextVideo.episode
                ).collect { result ->
                    when (result) {
                        is NetworkResult.Success -> {
                            lastSuccessData = result.data
                            if (!autoSelectTriggered) {
                                val candidate = when {
                                    timeoutElapsed -> trySelectStream(result.data)
                                    playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode ->
                                        tryBingeGroupOnly(result.data)
                                    else -> null
                                }
                                if (candidate != null) recordSelection(candidate)
                            }
                        }
                        is NetworkResult.Error -> lastError = result
                        NetworkResult.Loading -> Unit
                    }
                }
                // Every addon has responded: take whatever matched, then settle so
                // the waiting code below resumes even if nothing was selected.
                if (!autoSelectTriggered) {
                    lastSuccessData?.let { data -> trySelectStream(data)?.let { recordSelection(it) } }
                }
                searchSettled.complete(Unit)
            }

            val timeoutMs = timeoutSeconds * 1_000L
            if (PlayerSettings.isBoundedTimeout(timeoutSeconds)) {
                // Wait for the timeout, resuming as soon as a stream is settled.
                withTimeoutOrNull(timeoutMs) { searchSettled.await() }
                timeoutElapsed = true
                if (!autoSelectTriggered) {
                    val data = lastSuccessData
                    if (data != null) {
                        // Streams arrived: full select once. If nothing matches,
                        // respect the timeout and stop (the caller shows the picker).
                        trySelectStream(data)?.let { recordSelection(it) }
                    } else {
                        // No addon responded yet: keep waiting for the first
                        // usable result up to the hard timeout, matching the
                        // instant and unlimited branches below. searchSettled
                        // completes when the scrape settles, so this resolves
                        // at scrape-end (typically seconds); the cap is only a
                        // hung-addon backstop, not a fixed wait. Without it a
                        // slow in-flight next-episode prefetch that is about
                        // to land was abandoned for the manual picker.
                        withTimeoutOrNull(NEXT_EPISODE_HARD_TIMEOUT_MS) { searchSettled.await() }
                        if (!autoSelectTriggered) {
                            lastSuccessData?.let { trySelectStream(it)?.let { s -> recordSelection(s) } }
                        }
                    }
                }
                innerJob.cancel()
            } else if (timeoutSeconds == 0) {
                timeoutElapsed = true
                withTimeoutOrNull(NEXT_EPISODE_HARD_TIMEOUT_MS) { searchSettled.await() }
                if (!autoSelectTriggered) {
                    lastSuccessData?.let { data -> trySelectStream(data)?.let { recordSelection(it) } }
                }
                innerJob.cancel()
            } else {
                withTimeoutOrNull(NEXT_EPISODE_HARD_TIMEOUT_MS) { searchSettled.await() }
                if (!autoSelectTriggered) {
                    lastSuccessData?.let { data -> trySelectStream(data)?.let { recordSelection(it) } }
                }
                innerJob.cancel()
            }

            // Everything above is scrape + auto-select, including the bounded
            // timeout wait. Subtracting this from the press stamp prices the
            // block S5 part 2 targets; the resolve that follows is already
            // priced by resolving_debrid -> resolving_debrid_done.
            android.util.Log.i(
                "TTFF_STAGE",
                "NEXT_EPISODE_STREAMS_SETTLED " +
                    "ms=${android.os.SystemClock.elapsedRealtime() - nextEpisodePressElapsedMs} " +
                    "selected=${selectedStream != null} timeoutElapsed=$timeoutElapsed " +
                    "selectMs=$selectionRankMs selectCalls=$selectionRankCalls"
            )
            val streamToPlay = selectedStream?.let {
                resolveDirectDebridStreamIfNeeded(it, nextVideo.season, nextVideo.episode)
            }
            if (streamToPlay != null) {
                val sourceName = (streamToPlay.name?.takeIf { it.isNotBlank() } ?: streamToPlay.addonName).trim()
                // The countdown exists so the decision can be cancelled.
                //
                // S5/B3: countdown removed on a deliberate press (26 Jul 2026).
                // nt2 shipped it shortened to 1 s so the chosen source name
                // stayed visible; the capture confirmed the card renders the
                // name correctly, and Paul's call is that the second of latency
                // is not worth the name. The loop body was pure dead time --
                // resolveDirectDebridStreamIfNeeded() has already returned above,
                // so the delay(1000) overlapped no work whatsoever (measured:
                // resolving_debrid_done 16:03:21.383 -> preparing_metadata
                // 16:03:22.447, a 1,064 ms gap).
                //
                // Auto-play is deliberately untouched at three seconds: an
                // unattended transition still needs a cancellable window.
                //
                // Button-suppression note: countdownSec != null is what greys
                // out SkipNext while the card is up. searching = true already
                // covers the whole resolve above, and with the countdown gone
                // the resolve runs straight into switchToEpisodeStream, so the
                // unguarded window is ~0 ms.
                if (!userInitiated) {
                    for (remaining in 3 downTo 1) {
                        _uiState.update { current ->
                            val episodeForMode = current.nextEpisode ?: nextInfo
                            current.copy(
                                postPlayMode = PostPlayMode.AutoPlay(
                                    nextEpisode = episodeForMode,
                                    searching = false,
                                    sourceName = sourceName,
                                    countdownSec = remaining,
                                ),
                            )
                        }
                        delay(1000)
                    }
                }
                _uiState.update {
                    it.copy(
                        postPlayMode = null,
                        postPlayDismissedForCurrentEpisode = true,
                        playbackEnded = false,
                    )
                }
                switchToEpisodeStream(
                    stream = streamToPlay,
                    forcedTargetVideo = nextVideo,
                    isAutoPlay = !userInitiated
                )
            } else {
                _uiState.update {
                    it.copy(
                        postPlayMode = null,
                        postPlayDismissedForCurrentEpisode = true,
                    )
                }
                showEpisodeStreamPicker(
                    video = nextVideo,
                    forceRefresh = lastError != null || selectedStream != null
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    postPlayMode = null,
                    postPlayDismissedForCurrentEpisode = true,
                )
            }
            showEpisodeStreamPicker(video = nextVideo, forceRefresh = false)
        }
    }
}

private fun PlayerRuntimeController.playNextCloudLibraryFile(
    nextVideo: Video,
    userInitiated: Boolean
) {
    val playbackContext = cloudPlaybackContext ?: return
    val nextFile = playbackContext.nextFile ?: return
    val nextInfo = _uiState.value.nextEpisode ?: return
    _uiState.update {
        it.copy(postPlayMode = PostPlayMode.AutoPlay(nextEpisode = nextInfo, searching = true))
    }

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = scope.launch {
        try {
            when (val result = withTimeoutOrNull(CLOUD_LIBRARY_AUTO_NEXT_TIMEOUT_MS) {
                cloudLibraryRepository.resolvePlayback(playbackContext.item, nextFile)
            }) {
                is com.nuvio.tv.core.cloud.CloudLibraryPlaybackResult.Success -> {
                    val filename = result.filename ?: nextFile.name
                    val stream = Stream(
                        name = playbackContext.item.providerName,
                        title = filename,
                        description = playbackContext.item.name,
                        url = result.url,
                        ytId = null,
                        infoHash = null,
                        fileIdx = null,
                        externalUrl = null,
                        behaviorHints = com.nuvio.tv.domain.model.StreamBehaviorHints(
                            notWebReady = null,
                            bingeGroup = null,
                            countryWhitelist = null,
                            proxyHeaders = null,
                            videoSize = result.videoSizeBytes ?: nextFile.sizeBytes,
                            filename = filename
                        ),
                        addonName = playbackContext.item.providerName,
                        addonLogo = null
                    )
                    val advancedContext = playbackContext.advanceTo(nextFile)
                    cloudSessionToken?.let { cloudPlaybackSessionStore.update(it, advancedContext) }
                    cloudPlaybackContext = advancedContext
                    _uiState.update { it.copy(title = filename) }
                    switchToEpisodeStream(
                        stream = stream,
                        forcedTargetVideo = nextVideo,
                        isAutoPlay = !userInitiated
                    )
                }
                else -> {
                    _uiState.update {
                        it.copy(
                            postPlayMode = null,
                            postPlayDismissedForCurrentEpisode = true,
                            error = context.getString(com.nuvio.tv.R.string.cloud_library_play_failed)
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _uiState.update {
                it.copy(
                    postPlayMode = null,
                    postPlayDismissedForCurrentEpisode = true,
                    error = context.getString(com.nuvio.tv.R.string.cloud_library_play_failed)
                )
            }
        }
    }
}
