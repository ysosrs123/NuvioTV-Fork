package com.nuvio.tv.ui.v2.appearance

import androidx.compose.ui.graphics.Color
import com.nuvio.tv.domain.model.V2AppearancePreferences
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.theme.ThemeColorPalette

/** Keep the existing accent (including supporter palettes), while style owns all dark surfaces. */
fun v2Palette(accent: ThemeColorPalette, appearance: V2AppearancePreferences, theme: com.nuvio.tv.domain.model.AppTheme? = null): ThemeColorPalette {
    val cinematic = appearance.visualStyle == VisualStyle.CINEMATIC_GLASS
    val palette = accent.copy(
        background = if (cinematic) Color(0xFF0B1119) else Color(0xFF03080D),
        backgroundElevated = if (cinematic) Color(0xFF1B2733) else Color(0xFF0A141E),
        backgroundCard = if (cinematic) Color(0xFF22303C) else Color(0xFF0C1822),
        surface = if (cinematic) Color(0xFF1A2834) else Color(0xFF08121B),
        surfaceVariant = if (cinematic) Color(0xFF304351) else Color(0xFF142430),
        panel = if (cinematic) Color(0xFF162532) else Color(0xFF07111A),
        overlay = Color(0xE6000000),
        field = if (cinematic) Color(0xFF263643) else Color(0xFF10212D),
        menu = if (cinematic) Color(0xFF1B2A38) else Color(0xFF091520),
        modal = if (cinematic) Color(0xFF1D2C3B) else Color(0xFF0B1925),
        playerOverlay = Color(0xE6000000),
        focusBackground = accent.secondary.copy(alpha = 0.12f)
    )
    // Clear Glass keeps its backing neutrals achromatic; no blue/grey focus fill.
    return if (theme == com.nuvio.tv.domain.model.AppTheme.GLASS && cinematic) palette.copy(
        background = Color(0xFF090909), backgroundElevated = Color(0xFF141414),
        backgroundCard = Color(0xFF191919), surface = Color(0xFF141414),
        surfaceVariant = Color(0xFF242424), panel = Color(0xFF141414),
        field = Color(0xFF202020), menu = Color(0xFF181818), modal = Color(0xFF1B1B1B),
        focusBackground = Color.Black.copy(alpha = .04f)
    ) else palette
}
