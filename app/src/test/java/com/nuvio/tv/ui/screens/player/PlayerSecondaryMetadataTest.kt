package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test

class PlayerSecondaryMetadataTest {
    @Test fun `full episode title survives formatting and movie transition drops episode data`() {
        val title = "A very long episode title that must remain fully accessible without a filename fallback"
        val series = PlayerUiState(contentType = "series", currentSeason = 2, currentEpisode = 4, currentEpisodeTitle = title)
        assertEquals("S2E4 • $title", playerSecondaryMetadata(series, "S2E4", title))
        assertEquals("1995", playerSecondaryMetadata(series.copy(contentType = "movie", releaseYear = "1995"), "S2E4", title))
        assertEquals("S2E5", playerSecondaryMetadata(series.copy(currentEpisode = 5, currentEpisodeTitle = null), "S2E5", null))
    }

    @Test fun `missing and invalid years stay hidden`() {
        listOf(null, "", "0", "null", "0000", "unknown", "1995-1999", "19950").forEach {
            assertNull(playerSecondaryMetadata(PlayerUiState(contentType = "movie", releaseYear = it), "", null))
        }
        assertEquals("2026", playerMovieYear("2026-09-20"))
    }

    @Test fun `cloud names never become episode titles and incomplete designation is hidden`() {
        assertNull(playerSecondaryMetadata(PlayerUiState(contentType = "cloud", currentSeason = 1, currentEpisode = 1), "S1E1", "raw.mkv"))
        assertNull(playerSecondaryMetadata(PlayerUiState(contentType = "series", currentSeason = 1), "", "Previous title"))
    }
}
