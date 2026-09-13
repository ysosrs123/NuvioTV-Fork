package com.nuvio.tv.ui.v2.appearance

import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.V2AppearancePreferences
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.theme.ThemeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class V2PaletteTest {
    @Test
    fun `every existing theme remains an accent while style owns neutral surfaces`() {
        for (style in VisualStyle.entries) {
            val appearance = V2AppearancePreferences(visualStyle = style)
            val reference = v2Palette(ThemeColors.getColorPalette(AppTheme.WHITE), appearance)
            for (theme in AppTheme.entries) {
                val original = ThemeColors.getColorPalette(theme)
                val result = v2Palette(original, appearance)
                assertEquals(original.secondary, result.secondary)
                assertEquals(original.focusRing, result.focusRing)
                assertEquals(original.focusRingGradient, result.focusRingGradient)
                assertEquals(reference.background, result.background)
                assertEquals(reference.surface, result.surface)
                assertEquals(reference.modal, result.modal)
                assertEquals(original, ThemeColors.getColorPalette(theme))
            }
        }
    }

    @Test
    fun `style selection changes the background without replacing the selected accent`() {
        val accent = ThemeColors.getColorPalette(AppTheme.WHITE)
        val cinema = v2Palette(accent, V2AppearancePreferences())
        val dark = v2Palette(accent, V2AppearancePreferences(visualStyle = VisualStyle.PURE_LIQUID_DARK))
        assertNotEquals(cinema.background, dark.background)
        assertEquals(cinema.secondary, dark.secondary)
    }
}
