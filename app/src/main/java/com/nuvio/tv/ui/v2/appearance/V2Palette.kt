package com.nuvio.tv.ui.v2.appearance

import androidx.compose.ui.graphics.Color
import com.nuvio.tv.domain.model.V2AppearancePreferences
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.theme.ThemeColorPalette

/** Keep the existing accent (including supporter palettes), while style owns all dark surfaces. */
fun v2Palette(accent: ThemeColorPalette, appearance: V2AppearancePreferences): ThemeColorPalette {
    val cinematic = appearance.visualStyle == VisualStyle.CINEMATIC_GLASS
    return accent.copy(
        background = if (cinematic) Color(0xFF090A0D) else Color(0xFF050506),
        backgroundElevated = Color(0xFF13151A),
        backgroundCard = Color(0xFF191B20),
        surface = Color(0xFF14161B),
        surfaceVariant = Color(0xFF23252B),
        panel = Color(0xFF111318),
        overlay = Color(0xE6000000),
        field = Color(0xFF1B1D23),
        menu = Color(0xFF14161B),
        modal = Color(0xFF17191E),
        playerOverlay = Color(0xE6000000),
        focusBackground = accent.secondary.copy(alpha = 0.12f)
    )
}
