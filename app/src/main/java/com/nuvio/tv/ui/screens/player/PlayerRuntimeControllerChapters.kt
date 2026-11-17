package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Tracks
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Settings switch: off hides the button, the timeline gaps and the chapter name; on reads them from the playing file again. */
internal fun PlayerRuntimeController.applyChaptersEnabled(enabled: Boolean) {
    if (enabled == chaptersEnabled) return
    chaptersEnabled = enabled
    refreshChapters()
}

/** The file's own chapters, else the media server's named chapters for the title. */
internal fun PlayerRuntimeController.chaptersFor(tracks: Tracks?): List<PlayerChapter> {
    if (tracks != null && !isUsingMpvEngine()) {
        PlayerChapters.namedFileChapters(tracks)?.let(::updateSkipDetectionChapters)
    }
    if (!chaptersEnabled) return emptyList()
    val (source, chapters) = when {
        isUsingMpvEngine() -> "mpv" to (if (mpvChaptersStreamUrl == currentStreamUrl) mpvChapters else emptyList())
        tracks != null -> PlayerChapters.fileChapters(tracks) ?: ("server" to serverChapters)
        else -> "server" to serverChapters
    }
    if (chapters.isNotEmpty()) {
        val key = "$currentStreamUrl|$source|${chapters.size}"
        if (key != chaptersLogKey) {
            chaptersLogKey = key
            Log.i(PlayerRuntimeController.TAG, "CHAPTERS: source=$source count=${chapters.size}")
        }
    }
    return chapters
}

/** mpv lists the chapters itself; they come with the file, so once read for a stream they are kept. */
internal fun PlayerRuntimeController.refreshMpvChapters(named: List<PlayerChapter>) {
    if (!chaptersEnabled && !skipIntroEnabled) return
    if (mpvChaptersStreamUrl == currentStreamUrl && skipDetectionChapters.isNotEmpty()) {
        if (mpvChapters.isNotEmpty() && _uiState.value.chapters.isEmpty()) refreshChapters()
        return
    }
    if (named.isEmpty()) return
    updateSkipDetectionChapters(named)
    mpvChapters = PlayerChapters.fromMpv(named)
    mpvChaptersStreamUrl = currentStreamUrl
    refreshChapters()
}

internal fun PlayerRuntimeController.refreshChapters() {
    val chapters = chaptersFor(_exoPlayer?.currentTracks)
    _uiState.update { it.copy(chapters = chapters, showChaptersPanel = it.showChaptersPanel && chapters.isNotEmpty()) }
}

internal fun PlayerRuntimeController.fetchServerChapters() {
    if (!chaptersEnabled) return
    val url = reportedServerUrl ?: return
    val session = serverPlayback.session(url) ?: return
    val key = "${session.target.item.encode()}:${session.mediaSourceId}"
    if (serverChaptersFetchedKey == key) return
    serverChaptersFetchedKey = key
    scope.launch {
        val chapters = PlayerChapters.fromServer(serverPlayback.chapters(url))
        if (serverChaptersFetchedKey != key) return@launch
        serverChapters = chapters
        if (chapters.isNotEmpty()) refreshChapters()
    }
}

internal fun PlayerRuntimeController.clearServerChapters() {
    serverChaptersFetchedKey = null
    if (serverChapters.isEmpty()) return
    serverChapters = emptyList()
    refreshChapters()
}

internal fun PlayerRuntimeController.showChaptersPanel() {
    if (_uiState.value.chapters.isEmpty()) return
    _uiState.update {
        it.copy(
            showChaptersPanel = true,
            showControls = true,
            showEpisodesPanel = false,
            showSourcesPanel = false,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false
        )
    }
}

internal fun PlayerRuntimeController.dismissChaptersPanel() {
    _uiState.update { it.copy(showChaptersPanel = false) }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.selectChapter(startMs: Long) {
    _uiState.update { it.copy(showChaptersPanel = false) }
    onEvent(PlayerEvent.OnSeekTo(startMs, exact = true))
}

/** Next or previous chapter from where playback is; a short pill names the chapter. */
internal fun PlayerRuntimeController.jumpChapter(forward: Boolean) {
    val chapters = _uiState.value.chapters
    if (chapters.isEmpty() || _playbackTimeline.value.isLive) return
    val position = _playbackTimeline.value.playbackPosition
    val target = if (forward) PlayerChapters.next(chapters, position) else PlayerChapters.previous(chapters, position)
    if (target == null) return
    onEvent(PlayerEvent.OnSeekTo(target.startMs, exact = true))
    _uiState.update { it.copy(showAspectRatioIndicator = true, aspectRatioIndicatorText = chapterLabel(context, target)) }
    hideAspectRatioIndicatorJob?.cancel()
    hideAspectRatioIndicatorJob = scope.launch {
        delay(2000)
        _uiState.update { it.copy(showAspectRatioIndicator = false) }
    }
}

/**
 * Every named chapter of the file, recaps and openings included (the chapter list drops those),
 * kept apart from the list so "Skip intro" can use them with chapters switched off.
 */
private fun PlayerRuntimeController.updateSkipDetectionChapters(chapters: List<PlayerChapter>) {
    if (chapters.isEmpty() || chapters == skipDetectionChapters) return
    skipDetectionChapters = chapters
    skipChaptersComputedForDuration = 0L
    if (skipIntroEnabled) {
        tryComputeChapterSkipIntervals()
    }
}

/**
 * Compute chapter skip intervals if the duration is known and we haven't already done so
 * for this stream at this duration. Sets a flag to avoid re-running regexes on every tick.
 */
private fun PlayerRuntimeController.tryComputeChapterSkipIntervals(): Boolean {
    val chapters = skipDetectionChapters.takeIf { it.size >= 2 } ?: return false
    val durationMs = _playbackTimeline.value.duration.takeIf { it > 0L } ?: return false
    if (skipChaptersComputedForDuration == durationMs) return false
    skipChaptersComputedForDuration = durationMs
    chapterSkipIntervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
    recomputeMergedSkipIntervals()
    return true
}

/**
 * Called from the playback tick. Retries chapter interval computation once the duration
 * becomes known, then stops. Guarded by skipIntroEnabled.
 */
internal fun PlayerRuntimeController.recomputeChapterSkipIntervalsIfNeeded() {
    if (!skipIntroEnabled) return
    if (skipChaptersComputedForDuration > 0L) return // already computed for this stream
    tryComputeChapterSkipIntervals()
}

internal fun PlayerRuntimeController.clearChapterSkipState() {
    skipDetectionChapters = emptyList()
    chapterSkipIntervals = emptyList()
    skipChaptersComputedForDuration = 0L
}
