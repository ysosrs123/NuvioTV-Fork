package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddonSubtitlesFirstTest {
    private fun pick(addons: List<String>, targets: List<String>, position: Int) =
        AddonSubtitlesFirst.pick(addons, targets, position) { it }

    @Test fun anAddOnSubtitleInTheSameLanguageWins() {
        assertEquals("en", pick(listOf("fr", "en"), listOf("en", "fr"), 0))
    }

    @Test fun aHigherRankedLanguageWinsOverTheMatchedOne() {
        assertEquals("en", pick(listOf("fr", "en"), listOf("en", "fr"), 1))
    }

    @Test fun aLowerRankedAddOnLanguageDoesNotReplaceTheFilesOwn() {
        assertNull(pick(listOf("fr"), listOf("en", "fr"), 0))
    }

    @Test fun noAddOnSubtitleKeepsTheFilesOwn() {
        assertNull(pick(emptyList(), listOf("en"), 0))
    }

    @Test fun anUnknownLanguageMatchPicksNothing() {
        assertNull(pick(listOf("en"), listOf("en"), -1))
    }
}
