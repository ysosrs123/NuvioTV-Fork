package com.nuvio.tv.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTitleOverlayTest {
    @Test fun `backdrop card keeps its logo until the trailer shows`() {
        assertTrue(cardTitleOverlayVisible(artworkCarriesTitle = false, trailerShowing = false, logoOverTrailer = true))
        assertTrue(cardTitleOverlayVisible(artworkCarriesTitle = false, trailerShowing = false, logoOverTrailer = false))
    }

    @Test fun `logo over the trailer follows the switch`() {
        assertTrue(cardTitleOverlayVisible(artworkCarriesTitle = false, trailerShowing = true, logoOverTrailer = true))
        assertFalse(cardTitleOverlayVisible(artworkCarriesTitle = false, trailerShowing = true, logoOverTrailer = false))
    }

    @Test fun `artwork with its own title never gets a second one`() {
        for (trailerShowing in listOf(false, true)) {
            for (logoOverTrailer in listOf(false, true)) {
                assertFalse(cardTitleOverlayVisible(artworkCarriesTitle = true, trailerShowing, logoOverTrailer))
            }
        }
    }
}
