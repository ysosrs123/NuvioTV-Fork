package com.nuvio.tv.core.formatcheck

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class FormatCheckMode { QUICK, FULL }

/** [only] limits a run to those clips, whose new results then replace their old ones. */
data class FormatCheckStart(val mode: FormatCheckMode, val only: Set<String>? = null)

sealed interface FormatCheckRunState {
    data object Idle : FormatCheckRunState
    data object Preparing : FormatCheckRunState
    data class Playing(val index: Int, val total: Int, val clip: FormatClip, val question: FormatQuestion?) : FormatCheckRunState
    data object Failed : FormatCheckRunState
}

/** Links the player to a running check. Outside a run [active] is false and the player reports to nothing. */
object FormatCheckSession {
    @Volatile
    var active: Boolean = false
        private set

    private val _state = MutableStateFlow<FormatCheckRunState>(FormatCheckRunState.Idle)
    val state: StateFlow<FormatCheckRunState> = _state.asStateFlow()

    private val _observation = MutableStateFlow(ClipObservation())
    val observation: StateFlow<ClipObservation> = _observation.asStateFlow()

    private val _openRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val openRequests: SharedFlow<Unit> = _openRequests

    private val _startRequests = MutableSharedFlow<FormatCheckStart>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val startRequests: SharedFlow<FormatCheckStart> = _startRequests

    private var pendingAnswer: CompletableDeferred<String?>? = null

    fun requestOpen() {
        _openRequests.tryEmit(Unit)
    }

    fun requestStart(mode: FormatCheckMode, only: Set<String>? = null) {
        if (!active) _startRequests.tryEmit(FormatCheckStart(mode, only?.takeIf { it.isNotEmpty() }))
    }

    internal fun setActive(value: Boolean) {
        active = value
    }

    internal fun setState(value: FormatCheckRunState) {
        _state.value = value
    }

    private val _liveResults = MutableStateFlow<Map<String, FormatVerdict>>(emptyMap())
    val liveResults: StateFlow<Map<String, FormatVerdict>> = _liveResults

    private val _runClips = MutableStateFlow(FormatCheckCatalog.clips)
    val runClips: StateFlow<List<FormatClip>> = _runClips

    internal fun setRunClips(clips: List<FormatClip>) {
        _runClips.value = clips
    }

    internal fun clearLiveResults() {
        _liveResults.value = emptyMap()
    }

    internal fun noteResult(clipId: String, verdict: FormatVerdict) {
        _liveResults.update { it + (clipId to verdict) }
    }

    internal fun beginClip() {
        _observation.value = ClipObservation()
    }

    internal fun askQuestion(): CompletableDeferred<String?> =
        CompletableDeferred<String?>().also { pendingAnswer = it }

    fun answer(value: String?) {
        pendingAnswer?.complete(value)
    }

    fun onFirstFrame() {
        if (active) _observation.update { it.copy(firstFrame = true) }
    }

    fun onDecoder(name: String) {
        if (active) _observation.update { it.copy(decoder = name) }
    }

    fun onDroppedFrames(count: Int) {
        if (active) _observation.update { it.copy(droppedFrames = it.droppedFrames + count) }
    }

    fun onDolbyVisionRoute(route: DolbyVisionRoute?) {
        if (active) _observation.update { it.copy(dolbyVisionRoute = route) }
    }

    fun onAudioPath(passthrough: Boolean) {
        if (active) _observation.update { it.copy(passthrough = passthrough) }
    }

    /** The first picture from here on is MPV's, so the earlier error and picture no longer count. */
    fun onMovedToMpv() {
        if (active) _observation.update { it.copy(movedToMpv = true, error = null, firstFrame = false, droppedFrames = 0) }
    }

    fun onError(message: String) {
        if (active) _observation.update { it.copy(error = message) }
    }
}
