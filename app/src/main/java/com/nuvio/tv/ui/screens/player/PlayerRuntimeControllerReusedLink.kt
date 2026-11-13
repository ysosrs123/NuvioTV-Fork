package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackException
import com.nuvio.tv.core.logging.redactLinksForLog
import com.nuvio.tv.data.local.CachedStreamLink
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val FRESH_LINK_LIST_WAIT_MS = 20_000L
private const val FRESH_LINK_LIST_POLL_MS = 250L

/** Finds the entry a saved link came from in a freshly loaded source list. */
internal object ReusedLinkRefresh {

    /** Expired or refused links; a rate limit (429) or a bad range (416) is not the link's fault. */
    fun isLinkError(httpStatus: Int?, deadSource: Boolean): Boolean =
        deadSource || (httpStatus != null && httpStatus >= 400 && httpStatus != 416 && httpStatus != 429)

    /** Refused outright before any picture: the list handed out a link that had already run out. */
    fun isRefusedAtFirstRequest(httpStatus: Int?): Boolean = httpStatus == 400 || httpStatus == 410

    /** The entry playing now, described like a saved link so [sameSource] can find it in a reloaded list. */
    fun playingEntry(
        url: String,
        streamName: String,
        headers: Map<String, String>,
        filename: String?,
        videoSize: Long?,
        bingeGroup: String?
    ): CachedStreamLink = CachedStreamLink(
        url = url, streamName = streamName, headers = headers, cachedAtMs = 0L, filename = filename,
        videoSize = videoSize, bingeGroup = bingeGroup
    )

    /**
     * The same release from the same source: same file name first, then the same entry name, each in the same binge
     * group when both have one; then the same binge group and size. Never the failed link itself.
     */
    fun sameSource(
        streams: List<Stream>,
        saved: CachedStreamLink,
        deadKeys: Set<String>,
        season: Int?,
        episode: Int?
    ): Stream? {
        val playable = streams.filter { stream ->
            SourceFailoverSelection.isAutoFailoverPlayable(stream, season, episode) &&
                SourceFailoverSelection.failoverKey(stream)?.let { it !in deadKeys && it != saved.url } == true
        }
        fun groupOk(stream: Stream): Boolean {
            val group = stream.behaviorHints?.bingeGroup
            return saved.bingeGroup == null || group == null || group == saved.bingeGroup
        }
        saved.filename?.takeIf(String::isNotBlank)?.let { file ->
            playable.firstOrNull { it.behaviorHints?.filename.equals(file, ignoreCase = true) && groupOk(it) }?.let { return it }
        }
        playable.firstOrNull { stream ->
            val name = stream.name?.takeIf(String::isNotBlank) ?: stream.addonName
            name == saved.streamName && groupOk(stream)
        }?.let { return it }
        val group = saved.bingeGroup ?: return null
        val size = saved.videoSize ?: return null
        return playable.firstOrNull { it.behaviorHints?.bingeGroup == group && it.behaviorHints?.videoSize == size }
    }

    /** The reloaded list hands out the refused address again, so waiting for a fresh one is pointless. */
    fun failedAddressBack(streams: List<Stream>, failedUrl: String): Boolean =
        streams.any { SourceFailoverSelection.failoverKey(it) == failedUrl }
}

/** The link playing now is the saved link played again as is. */
private fun PlayerRuntimeController.replayedLinkPlaying(): CachedStreamLink? {
    val key = streamCacheKey ?: return null
    val saved = streamLinkCacheDataStore.replayedLink(key) ?: return null
    val url = saved.url.takeIf(String::isNotBlank) ?: return null
    val normalized = PlayerMediaSourceFactory.normalizePlaybackRequest(url, saved.headers).url
    return saved.takeIf { url == currentStreamUrl || normalized == currentStreamUrl }
}

/**
 * A saved link ("Reuse last link") that fails before the first picture is forgotten, and the same source is asked
 * for a fresh link once. Returns false when this does not apply, so the usual recovery goes on.
 */
internal fun PlayerRuntimeController.tryFreshLinkForReusedLink(error: PlaybackException, detailedError: String): Boolean {
    if (hasRenderedFirstFrame || freshLinkForReusedLinkTried) return false
    val http = error.findInvalidResponseCodeException()?.responseCode
    if (!ReusedLinkRefresh.isLinkError(http, isDeadSourcePlaybackError(error))) return false
    return refreshReusedLink(detailedError)
}

/** The startup recovery ran out on a saved link: ask its source for a fresh one before any other source. */
internal fun PlayerRuntimeController.tryFreshLinkAfterStartupExhausted(detailedError: String): Boolean {
    if (hasRenderedFirstFrame || freshLinkForReusedLinkTried) return false
    return refreshReusedLink(detailedError)
}

/**
 * A link picked from the list (not a saved one) refused with 400 or 410 before the first picture: the source list is
 * loaded again once and the same entry plays with its new link. Only the entry already started is opened again.
 */
internal fun PlayerRuntimeController.tryFreshLinkForRefusedLink(error: PlaybackException, detailedError: String): Boolean {
    if (hasRenderedFirstFrame || freshLinkForReusedLinkTried) return false
    if (!ReusedLinkRefresh.isRefusedAtFirstRequest(error.findInvalidResponseCodeException()?.responseCode)) return false
    if (serverPlayback.session(currentStreamUrl) != null) return false
    val url = currentStreamUrl.takeIf(String::isNotBlank) ?: return false
    val entry = ReusedLinkRefresh.playingEntry(
        url = url,
        streamName = _uiState.value.currentStreamName?.takeIf(String::isNotBlank) ?: title,
        headers = currentHeaders,
        filename = currentFilename,
        videoSize = currentVideoSize,
        bingeGroup = currentStreamBingeGroup
    )
    Log.w(PlayerRuntimeController.TAG, "Link refused at the first request (${detailedError.redactLinksForLog()}); " +
        "reloading the source list for the same entry")
    return refreshLink(entry, savedKey = null, detailedError = detailedError)
}

private fun PlayerRuntimeController.refreshReusedLink(detailedError: String): Boolean {
    val saved = replayedLinkPlaying() ?: return false
    val key = streamCacheKey ?: return false
    Log.w(PlayerRuntimeController.TAG, "Saved link failed (${detailedError.redactLinksForLog()}); asking its source for a fresh link")
    return refreshLink(saved, savedKey = key, detailedError = detailedError)
}

/** [savedKey]: the saved link to forget, when the failed link was one. */
private fun PlayerRuntimeController.refreshLink(saved: CachedStreamLink, savedKey: String?, detailedError: String): Boolean {
    val requestKey = currentSourceRequestKey() ?: return false
    freshLinkForReusedLinkTried = true
    deadSourceStreamUrls.add(currentStreamUrl)
    saved.url.takeIf(String::isNotBlank)?.let(deadSourceStreamUrls::add)
    _uiState.update { it.copy(deadSourceStreamUrls = deadSourceStreamUrls.toSet()) }
    val savedPosition = currentPlaybackPositionMs()?.takeIf { it > 0L } ?: 0L
    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        savedKey?.let { streamLinkCacheDataStore.remove(it) }
        _uiState.update {
            it.copy(
                error = null,
                showPauseOverlay = false,
                showLoadingOverlay = it.loadingOverlayEnabled,
                pendingSeekPosition = if (savedPosition > 0L) savedPosition else it.pendingSeekPosition
            )
        }
        loadSourceStreams(forceRefresh = true)
        val request = currentSourceStreamsRequest()
        val deadline = SystemClock.elapsedRealtime() + FRESH_LINK_LIST_WAIT_MS
        var fresh: Stream? = null
        while (true) {
            if (currentSourceRequestKey() != requestKey) return@launch
            if (sourceStreamsCacheRequestKey == requestKey) {
                fresh = ReusedLinkRefresh.sameSource(
                    _uiState.value.sourceAllStreams, saved, deadSourceStreamUrls, request?.season, request?.episode
                )
                if (fresh != null || sourceStreamsFetchCompleted) break
                if (savedKey == null && ReusedLinkRefresh.failedAddressBack(_uiState.value.sourceAllStreams, saved.url)) break
            }
            if (SystemClock.elapsedRealtime() >= deadline) break
            delay(FRESH_LINK_LIST_POLL_MS)
        }
        errorRetryJob = null
        if (fresh != null) {
            Log.i(PlayerRuntimeController.TAG, "Fresh link from the same source: host=${fresh.getStreamUrl()?.safeHost()}")
            switchToSourceStream(fresh, automatic = true)
        } else if (!advanceToNextLiveSource(detailedError)) {
            surfaceSourceFailoverError(detailedError)
        }
    }
    return true
}
