package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.media3.exoplayer.SeekParameters
import com.nuvio.tv.R
import com.nuvio.tv.core.party.PartyFingerprint
import com.nuvio.tv.core.party.PartyLinkPolicy
import com.nuvio.tv.core.party.PartyMedia
import com.nuvio.tv.core.party.PartyPlayer
import com.nuvio.tv.core.party.PartySession
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.ui.screens.party.rememberPartyRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The open player as a watch party sees it. Lives as long as the player screen. */
internal class PartyPlayerBridge(
    private val controller: PlayerRuntimeController,
    private val session: PartySession,
) : PartyPlayer {

    /** True while the party, not the person at this device, has playback paused. */
    var partyPaused: Boolean = false
        private set

    override val positionMs: Long
        get() = (controller.currentPlaybackPositionMs() ?: 0L).coerceAtLeast(0L)

    override val durationMs: Long
        get() = controller.currentPlaybackDurationMs().coerceAtLeast(0L)

    override val wantsToPlay: Boolean
        get() = controller.hasActivePlayIntent()

    override val isBuffering: Boolean
        get() {
            val state = controller._uiState.value
            if (state.showLoadingOverlay) return true
            // A paused MPV player stops its progress loop, so the shared flag goes stale; ask the player itself.
            if (controller.isUsingMpvEngine() && !controller.hasActivePlayIntent()) {
                val view = controller.mpvView ?: return false
                return view.isSeekingNow() || view.isPausedForCacheNow()
            }
            return state.isBuffering
        }

    override val isAway: Boolean
        get() {
            if (controller.hasActivePlayIntent()) controller.pausedFromOutside = false
            return controller.isInBackground || controller.pausedFromOutside
        }

    override val speedAllowed: Boolean
        get() = controller.canChangePlaybackSpeed() && controller.partyAudioIsDecoded()

    override fun play() {
        partyPaused = false
        controller.scrubHoldPaused = false
        controller.userPausedManually = false
        controller.cancelPauseOverlay()
        if (controller.isUsingMpvEngine()) {
            controller.setPlaybackPaused(false)
            controller.startProgressUpdates()
            controller.startWatchProgressSaving()
            controller.scheduleHideControls()
            controller.emitScrobbleStart()
        } else {
            controller._exoPlayer?.play()
        }
    }

    override fun pause() {
        partyPaused = true
        controller.userPausedManually = true
        if (controller.isUsingMpvEngine()) {
            controller.setPlaybackPaused(true)
            controller.stopProgressUpdates()
            controller.stopWatchProgressSaving()
            controller.emitPauseScrobbleForCurrentProgress()
        } else {
            controller._exoPlayer?.pause()
        }
        controller.cancelPauseOverlay()
    }

    override fun seekTo(positionMs: Long) {
        controller.seekPlaybackTo(positionMs, SeekParameters.EXACT)
        controller.scheduleProgressSyncAfterSeek()
    }

    override fun setSpeed(speed: Float) {
        controller.setPlaybackSpeedInternal(speed)
    }

    /** True while this player is in a party: the room decides when it plays. */
    val inParty: Boolean get() = session.state.value.isActive

    /** Called before the player handles an event. True means the party has dealt with it. */
    fun onPlayerEvent(event: PlayerEvent): Boolean {
        if (event == PlayerEvent.OnPlayPause) {
            controller.pausedFromOutside = false
            partyPaused = false
        }
        if (!session.state.value.isActive) return false
        when (event) {
            PlayerEvent.OnPlayPause -> partyPaused = false
            PlayerEvent.OnSeekForward,
            PlayerEvent.OnSeekBackward,
            is PlayerEvent.OnSeekBy,
            PlayerEvent.OnCommitPreviewSeek,
            is PlayerEvent.OnSeekTo,
            PlayerEvent.OnSkipIntro -> session.noteLocalSeek()
            is PlayerEvent.OnSetPlaybackSpeed -> if (event.speed != 1f) {
                controller.showPartySpeedNotice()
                return true
            }
            else -> Unit
        }
        return false
    }

    /** A party runs at normal speed: a speed chosen earlier for this title is set aside, not forgotten. */
    fun useNormalSpeed() {
        if (controller._uiState.value.playbackSpeed == 1f) return
        controller._uiState.update { it.copy(playbackSpeed = 1f) }
        controller.setPlaybackSpeedInternal(1f)
    }
}

/** False while audio leaves the box as a bitstream, or before the audio path is known. */
private fun PlayerRuntimeController.partyAudioIsDecoded(): Boolean {
    if (isUsingMpvEngine()) return true
    val sink = playbackSpeedAwareAudioSink ?: return false
    val format = sink.activeInputFormat ?: return false
    return format.sampleMimeType == MimeTypes.AUDIO_RAW && !isAudioOutputBypassing &&
        !sink.isDirectPlaybackActive() && !sink.isIecHbrActive()
}

private fun PlayerRuntimeController.showPartySpeedNotice() {
    _uiState.update {
        it.copy(
            showSpeedDialog = false,
            showAspectRatioIndicator = true,
            aspectRatioIndicatorText = context.getString(R.string.party_speed_fixed),
        )
    }
    hideAspectRatioIndicatorJob?.cancel()
    hideAspectRatioIndicatorJob = scope.launch {
        delay(2_000)
        _uiState.update { it.copy(showAspectRatioIndicator = false) }
    }
}

/** What this player is showing, in the terms a party shares. Null when the title has no catalogue identity or comes from a media server. */
internal fun PlayerRuntimeController.partyMedia(): PartyMedia? {
    val id = contentId?.takeIf { it.isNotBlank() } ?: return null
    val type = contentType?.takeIf { it.isNotBlank() } ?: return null
    if (ServerItemRef.isServerId(id) || ServerItemRef.isServerId(currentVideoId)) return null
    val state = _uiState.value
    val url = currentStreamUrl
    return PartyMedia(
        contentId = id,
        contentType = type,
        videoId = currentVideoId,
        season = currentSeason,
        episode = currentEpisode,
        title = contentName ?: title,
        episodeTitle = currentEpisodeTitle,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        fingerprint = PartyFingerprint(
            infoHash = (state.currentStreamInfoHash ?: currentInfoHash)?.lowercase(),
            fileIdx = state.currentStreamFileIdx ?: currentFileIdx,
            filename = currentFilename,
            sizeBytes = currentVideoSize,
            videoHash = currentVideoHash?.lowercase(),
            durationMs = currentPlaybackDurationMs().takeIf { it > 0 },
        ),
        streamName = state.currentStreamName ?: streamName,
        addonName = state.currentStreamAddonName ?: currentAddonName,
        sharedUrl = url.takeIf { !isTorrentStream && PartyLinkPolicy.isShareable(it) },
    )
}

/** Connects the open player to the party for as long as the player screen is shown. */
@Composable
internal fun PartyPlayerBinding(viewModel: PlayerViewModel) {
    val runtime = rememberPartyRuntime()
    val controller = viewModel.controller
    val bridge = remember(controller) { PartyPlayerBridge(controller, runtime.session) }
    val uiState by viewModel.uiState.collectAsState()
    val timeline by viewModel.playbackTimeline.collectAsState()
    val partyState by runtime.state.collectAsState()

    DisposableEffect(bridge) {
        controller.partyBridge = bridge
        onDispose {
            runtime.session.detachPlayer(bridge)
            controller.partyBridge = null
        }
    }

    val ready = !uiState.showLoadingOverlay && uiState.error == null && timeline.duration > 0
    val playing = if (ready) {
        listOf(uiState.currentVideoId, uiState.currentStreamUrl, uiState.currentStreamInfoHash, uiState.currentStreamFileIdx)
    } else {
        null
    }
    LaunchedEffect(playing, partyState.isActive) {
        val media = if (playing != null) controller.partyMedia() else null
        if (media == null) {
            runtime.session.detachPlayer(bridge)
        } else {
            if (partyState.isActive) bridge.useNormalSpeed()
            runtime.session.attachPlayer(bridge, media)
        }
    }
}
