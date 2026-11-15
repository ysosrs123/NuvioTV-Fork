package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerPanelNotesTest {
    @Test fun `each player panel gets a plain name and none open gives nothing`() {
        assertNull(openPanel(PlayerUiState()))
        assertEquals("stats", openPanel(PlayerUiState(showPlaybackStatsOverlay = true)))
        assertEquals("audio", openPanel(PlayerUiState(showAudioOverlay = true)))
        assertEquals("subtitles", openPanel(PlayerUiState(showSubtitleStylePanel = true)))
        assertEquals("sources", openPanel(PlayerUiState(showSourcesPanel = true)))
    }
}
