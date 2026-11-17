package com.nuvio.tv.ui.screens.home

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ModernLandscapeLogoShadeTest {
    @Test fun shadeStartsWhereTheFullCardGradientStopsBeingTransparent() {
        assertEquals(0.58f, MODERN_LANDSCAPE_LOGO_SHADE_START, 0f)
        assertEquals(290f, modernLandscapeLogoShadeTop(500f), 0.001f)
        assertEquals(0f, modernLandscapeLogoShadeTop(0f), 0f)
    }

    @Test fun shadeEndsOnTheSameColour() {
        assertEquals(Color.Black.copy(alpha = 0.75f), MODERN_LANDSCAPE_LOGO_SHADE_END)
    }
}
