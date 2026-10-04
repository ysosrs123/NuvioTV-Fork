package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.PlayerControlAction

/** Runtime support is separate from saved visibility; shared by rendering and focus recovery. */
internal fun playerControlAvailability(
    state: PlayerUiState,
    timeline: PlaybackTimelineState,
    nativeVideoSelected: Boolean,
    appliedTunneling: Boolean = false
): Set<PlayerControlAction> = buildSet {
    addAll(listOf(PlayerControlAction.PLAY_PAUSE, PlayerControlAction.STATS,
        PlayerControlAction.SOURCES, PlayerControlAction.MORE,
        PlayerControlAction.ENGINE, PlayerControlAction.WATCH_PARTY, PlayerControlAction.INFO))
    if (!state.isServerStream) add(PlayerControlAction.EXTERNAL)
    if (!timeline.isLive && timeline.duration > 0L) add(PlayerControlAction.RESTART)
    if (state.currentSeason != null && state.currentEpisode != null) add(PlayerControlAction.EPISODES)
    if (state.nextEpisode?.hasAired == true && (state.postPlayMode as? PostPlayMode.AutoPlay)?.let {
        !it.searching && it.countdownSec == null
    } != false) add(PlayerControlAction.NEXT_EPISODE)
    if (state.audioTracks.isNotEmpty()) add(PlayerControlAction.AUDIO)
    if (state.subtitleTracks.isNotEmpty() || state.addonSubtitles.isNotEmpty()) add(PlayerControlAction.SUBTITLES)
    if (state.internalPlayerEngine == com.nuvio.tv.data.local.InternalPlayerEngine.MVP_PLAYER ||
        (!state.tunnelingEnabled && !appliedTunneling && !nativeVideoSelected)) add(PlayerControlAction.SPEED)
    if (!state.tunnelingEnabled && !appliedTunneling) add(PlayerControlAction.ASPECT)
    if (state.playbackIssueReportsEnabled && state.playbackIssueReportStatus != PlaybackIssueReportStatus.Sending &&
        state.playbackIssueReportStatus != PlaybackIssueReportStatus.Sent) add(PlayerControlAction.REPORT)
}
