package com.nuvio.tv.ui.v2.components

import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.GlassPreset
import com.nuvio.tv.domain.model.V2AppearancePreferences
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.theme.ThemeColors
import com.nuvio.tv.ui.v2.appearance.v2Palette
import org.junit.Assert.*
import org.junit.Test

class ClearGlassThemeTest {
    @Test fun `material is limited to Glass colour in Cinematic V2`() {
        AppTheme.entries.forEach { theme ->
            (VisualStyle.entries + listOf(null)).forEach { style ->
                assertEquals(theme == AppTheme.GLASS && style == VisualStyle.CINEMATIC_GLASS,
                    isClearGlassTheme(theme, style))
            }
        }
    }

    @Test fun `Glass backing and focus fill are achromatic without modifying other palettes`() {
        AppTheme.entries.forEach { theme ->
            VisualStyle.entries.forEach { style ->
                val original = ThemeColors.getColorPalette(theme)
                val appearance = V2AppearancePreferences(visualStyle = style)
                val result = v2Palette(original, appearance, theme)
                if (!isClearGlassTheme(theme, style)) {
                    assertEquals(v2Palette(original, appearance), result)
                } else {
                    listOf(result.background, result.surface, result.panel, result.field,
                        result.modal, result.focusBackground).forEach { color ->
                        assertEquals(color.red, color.green, .0001f)
                        assertEquals(color.green, color.blue, .0001f)
                    }
                    assertTrue(result.focusBackground.alpha < .05f)
                }
                assertEquals(original.secondary, result.secondary)
                assertEquals(original.focusRingGradient, result.focusRingGradient)
                assertEquals(original, ThemeColors.getColorPalette(theme))
            }
        }
    }

    @Test fun `clear Glass presets retain independent transparency and dense panel contrast`() {
        GlassRole.entries.forEach { role ->
            val values = GlassPreset.entries.map { clearGlassBodyAlpha(role, it.transparencyPercent) }
            assertTrue(values.zipWithNext().all { (clearer, frostier) -> clearer <= frostier })
            assertTrue(values.first() < values.last())
            val tuning = (0..100).map { clearGlassBodyAlpha(role, it) }
            assertTrue(tuning.zipWithNext().all { (a, b) -> a >= b })
            assertEquals(tuning.first(), clearGlassBodyAlpha(role, -50), .0001f)
            assertEquals(tuning.last(), clearGlassBodyAlpha(role, 500), .0001f)
        }
        assertTrue(clearGlassBodyAlpha(GlassRole.PANEL, 60) <= .10f)
        assertTrue(clearGlassBodyAlpha(GlassRole.CONTROL, 60) <= .08f)
        assertTrue(clearGlassBodyAlpha(GlassRole.MODAL, 100) >= .16f)
        assertTrue(clearGlassBodyAlpha(GlassRole.HUD, 100) >= .16f)
        // The standard white/coloured theme body remains the approved Balanced material.
        assertEquals(.50f, glassBodyAlpha(GlassRole.PANEL, false, .94f, 60), .0001f)
        assertEquals(.18f, glassBodyAlpha(GlassRole.CONTROL, true, .18f, 60), .0001f)
    }
}
