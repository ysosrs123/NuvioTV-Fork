package com.nuvio.tv.ui.screens.detail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.ui.theme.ThemeColors
import com.nuvio.tv.ui.v2.appearance.v2AccentStops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayButtonColorTest {
    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun playTextStaysReadableOnEveryThemeColour() {
        AppTheme.entries.forEach { theme ->
            val palette = ThemeColors.getColorPalette(theme)
            val container = v2PlayButtonColor(v2AccentStops(palette, adaptive = false, artwork = null), palette.secondary)
            val text = playButtonContentColor(container)
            assertTrue("$theme: ${contrast(container, text)}", contrast(container, text) >= 4.3f)
        }
    }

    @Test
    fun lightColoursGetDarkTextAndDeepColoursWhiteText() {
        assertEquals(Color.White, playButtonContentColor(Color(0xFF1565C0)))
        assertEquals(Color.White, playButtonContentColor(Color(0xFF6A1B9A)))
        assertTrue(playButtonContentColor(Color(0xFFD5DDE3)) != Color.White)
        assertTrue(playButtonContentColor(Color(0xFFF2F6FF)) != Color.White)
    }

    @Test
    fun gradientThemesUseTheirMainColourAndArtworkWins() {
        val arctic = ThemeColors.getColorPalette(AppTheme.ARCTIC_BLUE)
        assertEquals(arctic.secondary, v2PlayButtonColor(v2AccentStops(arctic, adaptive = false, artwork = null), arctic.secondary))
        val artwork = Color(0xFF7FB3FF)
        assertEquals(artwork, v2PlayButtonColor(v2AccentStops(arctic, adaptive = true, artwork = artwork), arctic.secondary))
    }

    @Test
    fun artworkColourIsReadableToo() {
        listOf(Color(0xFFE8C547), Color(0xFF2E7D32), Color(0xFF8E8E8E), Color(0xFFB0BEC5), Color(0xFF7FB3FF)).forEach { artwork ->
            val text = playButtonContentColor(artwork)
            assertTrue("$artwork", contrast(artwork, text) >= 4.3f)
        }
    }
}
