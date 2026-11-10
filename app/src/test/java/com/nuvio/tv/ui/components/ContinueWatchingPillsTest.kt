package com.nuvio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingPillsTest {

    @Test
    fun `a poster card shows the count alone and other cards keep the words`() {
        assertEquals("8", cwEpisodesLeftPillText(8, posterCard = true, fullText = "8 left"))
        assertEquals("8 left", cwEpisodesLeftPillText(8, posterCard = false, fullText = "8 left"))
    }

    @Test
    fun `new season shortens only on a poster card that also shows the count`() {
        assertEquals("New", cwNewSeasonLabel(posterCard = true, showsEpisodesLeft = true, newSeason = "New season", shortNew = "New"))
        assertEquals("New season", cwNewSeasonLabel(posterCard = true, showsEpisodesLeft = false, newSeason = "New season", shortNew = "New"))
        assertEquals("New season", cwNewSeasonLabel(posterCard = false, showsEpisodesLeft = true, newSeason = "New season", shortNew = "New"))
    }
}
