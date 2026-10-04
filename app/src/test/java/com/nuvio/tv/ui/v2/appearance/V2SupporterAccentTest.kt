package com.nuvio.tv.ui.v2.appearance

import androidx.compose.ui.graphics.Color
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CosmeticEntitlements
import com.nuvio.tv.domain.model.V2AppearancePreferences
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.domain.model.availableAppThemes
import com.nuvio.tv.ui.theme.ThemeColors
import org.junit.Assert.*
import org.junit.Test

class V2SupporterAccentTest {
    private val supporters = listOf(AppTheme.GOLD, AppTheme.JADE, AppTheme.ROSE_GOLD, AppTheme.ARCTIC_BLUE, AppTheme.GRAPHITE)

    @Test fun `all five supporter colorways reach V2 glass progress and focus in both styles`() {
        for (theme in supporters) for (style in VisualStyle.entries) {
            val original = ThemeColors.getColorPalette(theme)
            val palette = v2Palette(original, V2AppearancePreferences(visualStyle = style))
            assertTrue(original.accentGradient.size > 1)
            assertEquals(original.accentGradient, v2AccentStops(palette, false, null))
            assertEquals(original.focusRingGradient, v2AccentStops(palette, false, null, focus = true))
            assertEquals(original.focusRingGradient, v2AccentStops(palette, true, Color.Red, focus = true, override = palette.secondary))
        }
    }

    @Test fun `artwork mode follows artwork and missing artwork keeps the entire selected colorway`() {
        for (theme in supporters) {
            val palette = ThemeColors.getColorPalette(theme)
            assertEquals(listOf(Color.Cyan), v2AccentStops(palette, true, Color.Cyan))
            assertEquals(palette.accentGradient, v2AccentStops(palette, true, null))
            assertEquals(listOf(Color.Red), v2AccentStops(palette, false, null, override = Color.Red))
        }
    }

    @Test fun `supporter themes remain entitlement gated`() {
        val free = availableAppThemes(CosmeticEntitlements.None)
        val unlocked = availableAppThemes(CosmeticEntitlements.SupporterPreview)
        supporters.forEach {
            assertFalse(it in free)
            assertTrue(it in unlocked)
        }
    }
}
