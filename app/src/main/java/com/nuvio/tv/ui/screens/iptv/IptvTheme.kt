@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.nuvio.tv.data.iptv.IptvAppearance
import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CosmeticEntitlements
import com.nuvio.tv.domain.model.availableAppThemes
import com.nuvio.tv.ui.theme.LocalAppTheme
import com.nuvio.tv.ui.theme.LocalNuvioColors
import com.nuvio.tv.ui.theme.LocalNuvioExtendedColors
import com.nuvio.tv.ui.theme.LocalNuvioFocusRingStyle
import com.nuvio.tv.ui.theme.LocalThemePalette
import com.nuvio.tv.ui.theme.NuvioColorScheme
import com.nuvio.tv.ui.theme.NuvioExtendedColors
import com.nuvio.tv.ui.theme.ThemeColors
import com.nuvio.tv.ui.theme.createFocusRingStyle
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.v2Palette
import com.nuvio.tv.ui.v2.components.LocalGlassBodyFloor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

internal val LocalIptvAppearance = staticCompositionLocalOf { IptvAppearance() }

private const val GLASS_FLOOR = .9f

@EntryPoint
@InstallIn(SingletonComponent::class)
interface IptvThemeEntryPoint {
    fun memberAccess(): MemberAccessRepository
}

internal fun iptvThemes(entitlements: CosmeticEntitlements): List<AppTheme> = availableAppThemes(entitlements).filter { it != AppTheme.CUSTOM }

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val appearance by remember { IptvLivePreferences.appearance(context) }.collectAsState()
    val access by remember { EntryPointAccessors.fromApplication(context.applicationContext, IptvThemeEntryPoint::class.java).memberAccess().access }.collectAsState()
    val v2 = LocalV2Appearance.current
    val theme = iptvTheme(appearance.theme)?.takeIf { it in iptvThemes(access.entitlements) }
    val floor = if (appearance.solidPanels) 1f else GLASS_FLOOR
    if (theme == null && !appearance.black) {
        CompositionLocalProvider(LocalIptvAppearance provides appearance, LocalGlassBodyFloor provides floor, content = content)
        return
    }
    val inherited = LocalThemePalette.current
    val appTheme = theme ?: LocalAppTheme.current
    val palette = remember(theme, inherited, v2) {
        val base = theme?.let { ThemeColors.getColorPalette(it) } ?: inherited
        if (theme != null && v2 != null) v2Palette(base, v2, theme) else base
    }
    val colors = remember(palette, appearance.black, v2 != null) {
        NuvioColorScheme(palette, amoledMode = appearance.black, amoledSurfacesMode = appearance.black, glassPresentation = v2 != null)
    }
    val extended = remember(colors) {
        NuvioExtendedColors(colors.BackgroundElevated, colors.BackgroundCard, colors.TextSecondary, colors.TextTertiary, colors.FocusRing,
            colors.FocusBackground, colors.Rating)
    }
    val scheme = darkColorScheme(primary = colors.Primary, onPrimary = colors.OnPrimary, secondary = colors.Secondary, onSecondary = colors.OnSecondary,
        background = colors.Background, surface = colors.Surface, surfaceVariant = colors.SurfaceVariant, onBackground = colors.TextPrimary,
        onSurface = colors.TextPrimary, onSurfaceVariant = colors.TextSecondary, error = colors.Error)
    CompositionLocalProvider(LocalIptvAppearance provides appearance, LocalGlassBodyFloor provides floor, LocalAppTheme provides appTheme,
        LocalThemePalette provides palette, LocalNuvioColors provides colors, LocalNuvioExtendedColors provides extended,
        LocalNuvioFocusRingStyle provides remember(palette) { createFocusRingStyle(palette) }) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
    }
}

@Composable
internal fun IptvPanelFloor(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val appearance by remember { IptvLivePreferences.appearance(context) }.collectAsState()
    CompositionLocalProvider(LocalIptvAppearance provides appearance, LocalGlassBodyFloor provides if (appearance.solidPanels) 1f else GLASS_FLOOR, content = content)
}

@Composable
internal fun IptvOpaqueDialogs(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassBodyFloor provides 1f, content = content)
}

internal fun iptvTheme(name: String?): AppTheme? = AppTheme.entries.firstOrNull { it.name == name && it != AppTheme.CUSTOM }
