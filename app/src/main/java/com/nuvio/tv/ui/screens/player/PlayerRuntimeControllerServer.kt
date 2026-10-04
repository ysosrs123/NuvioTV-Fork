package com.nuvio.tv.ui.screens.player

import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.media3.common.PlaybackException
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.ServerPlayMethod
import com.nuvio.tv.data.mediaserver.ServerPlaybackSession
import com.nuvio.tv.data.mediaserver.ServerTrack
import com.nuvio.tv.data.mediaserver.labelRes
import com.nuvio.tv.data.mediaserver.mergeServerSkipIntervals
import com.nuvio.tv.data.mediaserver.newerServerResume
import com.nuvio.tv.data.mediaserver.readableTranscodeReason
import com.nuvio.tv.data.mediaserver.serverPlaybackMessageRes
import com.nuvio.tv.data.mediaserver.toSkipIntervals
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.enabledAddons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.reportServerPlayback() {
    val url = currentStreamUrl
    if (url != reportedServerUrl) {
        serverPlayback.stop(reportedServerUrl)
        reportedServerUrl = url.takeIf(serverPlayback::isServerSource)
        refreshServerTracks()
        if (reportedServerUrl == null) clearServerSkipIntervals()
    }
    fetchServerSkipIntervals()
    val state = _uiState.value
    serverPlayback.onPlaybackSnapshot(
        url = reportedServerUrl,
        positionMs = _playbackTimeline.value.playbackPosition,
        isPlaying = state.isPlaying,
        isLoading = state.isBuffering,
        isEnded = state.playbackEnded
    )
}

internal fun PlayerRuntimeController.fetchServerSkipIntervals() {
    if (!skipIntroEnabled) return
    val url = reportedServerUrl ?: return
    val session = serverPlayback.session(url) ?: return
    val key = "${session.target.item.encode()}:${session.mediaSourceId}"
    if (serverSkipFetchedKey == key) return
    serverSkipFetchedKey = key
    val isMovie = contentType.equals("movie", ignoreCase = true)
    scope.launch {
        val intervals = serverPlayback.segments(url).toSkipIntervals(isMovie)
        if (!skipIntroEnabled || serverSkipFetchedKey != key) return@launch
        serverSkipIntervals = intervals
        skipIntervals = mergeServerSkipIntervals(intervals, providerSkipIntervals)
    }
}

internal fun PlayerRuntimeController.clearServerSkipIntervals() {
    serverSkipFetchedKey = null
    if (serverSkipIntervals.isEmpty()) return
    serverSkipIntervals = emptyList()
    skipIntervals = providerSkipIntervals
}

internal fun PlayerRuntimeController.stopServerPlayback() {
    serverPlayback.stop(reportedServerUrl ?: currentStreamUrl)
    reportedServerUrl = null
}

internal val PlayerRuntimeController.isServerStream: Boolean
    get() = serverPlayback.isServerSource(currentStreamUrl)

internal val PlayerRuntimeController.hasBurnedInServerSubtitle: Boolean
    get() = serverPlayback.burnInSubtitles(currentStreamUrl).any { it.selected }

internal fun PlayerRuntimeController.serverPlaybackSummary(): String? {
    val session = serverPlayback.session(currentStreamUrl) ?: return null
    val method = context.getString(session.playMethod.labelRes())
    val reasons = session.transcodeReasons.joinToString(", ", transform = ::readableTranscodeReason)
    return if (reasons.isEmpty()) method else "$method · $reasons"
}

/** Provider row of the stats HUD for a server stream, for example "Jellyfin · Direct play". */
internal fun PlayerRuntimeController.serverProviderLabel(): String? {
    val session = serverPlayback.session(currentStreamUrl) ?: return null
    val name = serverPlayback.providerName(currentStreamUrl) ?: return null
    return "$name · ${context.getString(session.playMethod.labelRes())}"
}

/**
 * Nuvio's saved progress for this start, or the server's own resume point when that one is newer. The server is
 * asked while [saved] reads, and its short timeout keeps a slow server from holding up the start.
 */
internal suspend fun PlayerRuntimeController.savedOrServerProgress(saved: suspend () -> WatchProgress?): WatchProgress? {
    val url = currentStreamUrl
    if (!serverPlayback.isServerSource(url)) return saved()
    return coroutineScope {
        val state = async { serverPlayback.resumeState(url) }
        val local = saved()
        val progress = newerServerResume(local, state.await()) { blankServerProgress() }
        if (progress !== local) {
            Log.i(PlayerRuntimeController.TAG, "Resuming from the server position ${progress?.position}ms")
        }
        progress
    }
}

private fun PlayerRuntimeController.blankServerProgress(): WatchProgress? {
    val parentContentId = contentId?.takeIf { it.isNotEmpty() } ?: return null
    return WatchProgress(
        contentId = parentContentId,
        contentType = contentType?.takeIf { it.isNotEmpty() } ?: "movie",
        name = contentName ?: title,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        videoId = currentVideoId ?: parentContentId,
        season = currentSeason,
        episode = currentEpisode,
        episodeTitle = currentEpisodeTitle,
        position = 0L,
        duration = 0L,
        lastWatched = 0L
    )
}

internal fun PlayerRuntimeController.serverImdbId(contentId: String): String? =
    metaRepository.getCachedMeta(contentType ?: "movie", contentId)?.imdbId?.takeIf { it.startsWith("tt") }

internal suspend fun PlayerRuntimeController.streamAddonsFor(videoId: String): List<Addon> =
    if (serverStreams.isNativeRequest(videoId)) emptyList() else addonRepository.getInstalledAddons().first().enabledAddons()

internal fun PlayerRuntimeController.serverSourceNames(type: String, videoId: String): List<String> =
    serverStreams.sources(type, videoId, season = null, episode = null).map { it.name }

internal suspend fun PlayerRuntimeController.prepareServerStream(stream: Stream, quiet: Boolean = false): Stream? {
    val target = stream.serverTarget ?: return stream
    val session = try {
        serverPlayback.prepare(target)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (!quiet) {
            Toast.makeText(context, context.getString(error.serverPlaybackMessageRes()), Toast.LENGTH_SHORT).show()
        }
        return null
    }
    val hints = stream.behaviorHints ?: StreamBehaviorHints(null, null, null, null)
    return stream.copy(
        url = session.url,
        subtitles = session.subtitles + stream.subtitles,
        behaviorHints = hints.copy(proxyHeaders = session.headers.takeIf { it.isNotEmpty() }?.let { ProxyHeaders(request = it, response = null) })
    )
}

/** A server source the failover moved to could not be opened: skip it, or show the playback error. */
internal fun PlayerRuntimeController.skipUnavailableServerSource(stream: Stream) {
    stream.deadSourceKey()?.let { deadSourceStreamUrls.add(it) }
    deadSourceFailoverCount = (deadSourceFailoverCount - 1).coerceAtLeast(0)
    val message = context.getString(R.string.player_error_play_stream_failed)
    if (advanceToNextLiveSource(message)) return
    _uiState.update {
        it.copy(
            error = message,
            isBuffering = false,
            showLoadingOverlay = false,
            showPauseOverlay = false
        )
    }
}

internal val PlayerRuntimeController.isServerDirectPlay: Boolean
    get() = serverPlayback.session(currentStreamUrl)?.playMethod == ServerPlayMethod.DIRECT_PLAY

/**
 * Asks the server for a transcode of a direct-play stream that has used up its reopen attempts,
 * and only when the server offered one. A refused or unreachable transcode hands the error back
 * to the normal failover.
 */
internal fun PlayerRuntimeController.tryServerFallback(error: PlaybackException): Boolean {
    val failedUrl = currentStreamUrl
    if (!serverPlayback.canFallback(failedUrl)) return false
    val positionMs = _exoPlayer?.currentPosition?.takeIf { it > 0L }
        ?: _playbackTimeline.value.currentPosition.takeIf { it > 0L }
        ?: _uiState.value.pendingSeekPosition
        ?: 0L
    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        showRecoveryOverlay()
        val session = serverPlayback.fallback(failedUrl)
        if (currentStreamUrl != failedUrl) {
            session?.let { serverPlayback.stop(it.url) }
            return@launch
        }
        if (session != null) {
            restartServerSession(session, positionMs)
            return@launch
        }
        _uiState.update { it.copy(isBuffering = false) }
        val listener = currentExoPlayerListener
        if (listener != null) {
            listener.onPlayerError(error)
        } else {
            _uiState.update {
                it.copy(
                    error = error.toDisplayMessage(context),
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
        }
    }
    return true
}

internal fun PlayerRuntimeController.selectServerAudio(index: Int) {
    serverAudioChosenByUser = true
    switchServerAudio(index)
}

internal fun PlayerRuntimeController.selectServerSubtitle(index: Int) {
    if (_uiState.value.serverSubtitleTracks.any { it.isSelected && it.index == index }) return
    disableSubtitles()
    isUserExplicitSubtitleSelection = true
    persistedTrackPreference = persistedTrackPreference?.copy(subtitle = null)
    pendingRestoredAddonSubtitle = null
    restartServerStream(R.string.servers_subtitle_switch_failed) { url -> serverPlayback.switchSubtitle(url, index) }
}

internal fun PlayerRuntimeController.clearServerSubtitle() {
    persistedTrackPreference = rememberedTrackPreference ?: persistedTrackPreference
    restartServerStream(R.string.servers_subtitle_switch_failed) { url -> serverPlayback.switchSubtitle(url, null) }
}

private fun PlayerRuntimeController.switchServerAudio(index: Int) {
    if (_uiState.value.serverAudioTracks.any { it.isSelected && it.index == index }) return
    restartServerStream(R.string.servers_audio_switch_failed) { url -> serverPlayback.switchAudio(url, index) }
}

private fun PlayerRuntimeController.restartServerStream(
    @StringRes failureMessage: Int,
    restart: suspend (String) -> ServerPlaybackSession?
) {
    val url = currentStreamUrl
    val positionMs = _playbackTimeline.value.playbackPosition
    scope.launch {
        val session = restart(url)
        if (session == null) {
            Toast.makeText(context, context.getString(failureMessage), Toast.LENGTH_SHORT).show()
            return@launch
        }
        if (currentStreamUrl != url) {
            serverPlayback.stop(session.url)
            return@launch
        }
        restartServerSession(session, positionMs)
    }
}

private fun PlayerRuntimeController.restartServerSession(session: ServerPlaybackSession, positionMs: Long) {
    currentStreamUrl = session.url
    currentHeaders = session.headers
    currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
        url = session.url,
        filename = null,
        responseHeaders = emptyMap()
    )
    currentStreamResponseHeaders = emptyMap()
    streamSubtitles = session.subtitles + streamSubtitles.filterNot { it.id.startsWith("${session.mediaSourceId}:") }
    hasRetriedCurrentStreamAfter416 = false
    hasRetriedCurrentStreamAfterUnexpectedNpe = false
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = false
    timeoutRecoveryAttempts = 0
    resetErrorRetryState()
    _uiState.update { it.copy(currentStreamUrl = session.url) }
    scheduleDeferredPlayerReinitialize(fromPositionMs = positionMs)
}

private fun PlayerRuntimeController.refreshServerTracks() {
    val audioTracks = serverPlayback.audioTracks(reportedServerUrl).map { it.toTrackInfo() }
    val subtitleTracks = serverPlayback.burnInSubtitles(reportedServerUrl).map { it.toTrackInfo() }
    _uiState.update {
        it.copy(
            serverAudioTracks = audioTracks,
            serverSubtitleTracks = subtitleTracks,
            isServerStream = reportedServerUrl != null
        )
    }
    if (!serverAudioChosenByUser) applyPreferredServerAudio(audioTracks)
}

private fun ServerTrack.toTrackInfo() = TrackInfo(index = index, name = label, language = language, isSelected = selected)

private fun PlayerRuntimeController.applyPreferredServerAudio(tracks: List<TrackInfo>) {
    val preferred = mpvPreferredAudioLanguages.firstNotNullOfOrNull { target ->
        tracks.firstOrNull { PlayerSubtitleUtils.matchesLanguageCode(it.language, target) }
    } ?: return
    if (!preferred.isSelected) switchServerAudio(preferred.index)
}
