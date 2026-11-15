package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.ArcBreadcrumbs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The player panel on screen, for crash and freeze reports: a stuck frame often follows opening one. */
internal fun PlayerRuntimeController.notePlayerPanels() {
    scope.launch {
        _uiState.map { openPanel(it) }.distinctUntilChanged().collect { panel ->
            if (panel != null) ArcBreadcrumbs.note("panel $panel")
        }
    }
}

internal fun openPanel(s: PlayerUiState): String? = when {
    s.showPlaybackStatsOverlay -> "stats"
    s.showAudioOverlay -> "audio"
    s.showSubtitleOverlay || s.showSubtitleStylePanel || s.showSubtitleTimingDialog || s.showSubtitleDelayOverlay -> "subtitles"
    s.showSourcesPanel -> "sources"
    s.showStreamInfoOverlay -> "stream info"
    s.showEpisodesPanel -> "episodes"
    s.showChaptersPanel -> "chapters"
    s.showPartyPanel -> "watch party"
    s.showSpeedDialog || s.showMoreDialog -> "more"
    else -> null
}
