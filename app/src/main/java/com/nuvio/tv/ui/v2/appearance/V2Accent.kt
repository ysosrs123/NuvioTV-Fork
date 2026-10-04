package com.nuvio.tv.ui.v2.appearance

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.nuvio.tv.domain.model.AccentMode
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.ThemeColorPalette
import com.nuvio.tv.ui.theme.ThemeColors

/** Preserve supporter colorways; artwork mode intentionally follows the focused artwork. */
internal fun v2AccentStops(
    palette: ThemeColorPalette,
    adaptive: Boolean,
    artwork: Color?,
    focus: Boolean = false,
    override: Color? = null
): List<Color> {
    if (override != null && override != palette.secondary) return listOf(override)
    if (override == null && adaptive && artwork != null) return listOf(artwork)
    val gradient = if (focus) palette.focusRingGradient else palette.accentGradient
    return if (gradient.size > 1) gradient else listOf(palette.secondary)
}

@Composable
internal fun v2AccentColors(focus: Boolean = false, override: Color? = null): List<Color> {
    val adaptive = NuvioTheme.currentTheme != com.nuvio.tv.domain.model.AppTheme.GLASS &&
        LocalV2Appearance.current?.accentMode == AccentMode.ADAPTIVE_ARTWORK
    // Fixed-theme surfaces do not use this animated value. Reading it anyway made
    // every surface recompose on each artwork-colour frame, including cached rows.
    val artwork = if (adaptive && override == null) LocalArtworkAccent.current?.color?.value else null
    return v2AccentStops(
        palette = NuvioTheme.palette,
        adaptive = adaptive,
        artwork = artwork,
        focus = focus,
        override = override
    )
}
