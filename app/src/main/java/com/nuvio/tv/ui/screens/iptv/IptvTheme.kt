package com.nuvio.tv.ui.screens.iptv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.IptvAppearance
import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.ui.theme.LocalNuvioColors
import com.nuvio.tv.ui.theme.LocalNuvioFocusRingStyle
import com.nuvio.tv.ui.theme.LocalThemePalette
import com.nuvio.tv.ui.theme.NuvioColorScheme
import com.nuvio.tv.ui.theme.ThemeColors
import com.nuvio.tv.ui.theme.createFocusRingStyle
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.v2Palette

internal val LocalIptvAppearance = staticCompositionLocalOf { IptvAppearance() }

internal val IPTV_THEMES = listOf(AppTheme.WHITE, AppTheme.GLASS, AppTheme.OCEAN, AppTheme.VIOLET, AppTheme.EMERALD, AppTheme.AMBER, AppTheme.ROSE, AppTheme.CRIMSON)

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val appearance by remember { IptvLivePreferences.appearance(context) }.collectAsState()
    val v2 = LocalV2Appearance.current
    val theme = iptvTheme(appearance.theme)
    if (theme == null && !appearance.black) {
        CompositionLocalProvider(LocalIptvAppearance provides appearance, content = content)
        return
    }
    val inherited = LocalThemePalette.current
    val palette = remember(theme, inherited, v2) {
        val base = theme?.let { ThemeColors.getColorPalette(it) } ?: inherited
        if (theme != null && v2 != null) v2Palette(base, v2, theme) else base
    }
    val colors = remember(palette, appearance.black, v2 != null) {
        NuvioColorScheme(palette, amoledMode = appearance.black, amoledSurfacesMode = appearance.black, glassPresentation = v2 != null)
    }
    CompositionLocalProvider(LocalIptvAppearance provides appearance, LocalThemePalette provides palette, LocalNuvioColors provides colors,
        LocalNuvioFocusRingStyle provides remember(palette) { createFocusRingStyle(palette) }, content = content)
}

internal fun iptvTheme(name: String?): AppTheme? = IPTV_THEMES.firstOrNull { it.name == name }

internal fun iptvThemeLabel(theme: AppTheme?): Int = when (theme) {
    null -> R.string.iptv_settings_theme_nuvio
    AppTheme.WHITE -> R.string.theme_color_white
    AppTheme.GLASS -> R.string.theme_color_glass
    AppTheme.OCEAN -> R.string.theme_color_ocean
    AppTheme.VIOLET -> R.string.theme_color_violet
    AppTheme.EMERALD -> R.string.theme_color_emerald
    AppTheme.AMBER -> R.string.theme_color_amber
    AppTheme.ROSE -> R.string.theme_color_rose
    AppTheme.CRIMSON -> R.string.theme_color_crimson
    else -> R.string.iptv_settings_theme_nuvio
}
