package com.nuvio.tv.ui.theme

import com.nuvio.tv.domain.model.DeviceUiPreferences
import com.nuvio.tv.domain.model.InterfaceExperience
import com.nuvio.tv.ui.v2.appearance.LocalDeviceUiPreferences
import com.nuvio.tv.ui.v2.appearance.LocalResolvedAppearance
import com.nuvio.tv.ui.v2.appearance.LocalUiScaleDecision
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.ResolvedAppearance
import com.nuvio.tv.ui.v2.appearance.v2Palette
import com.nuvio.tv.ui.v2.scale.UiScaleDecision
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.SettingsUiStyle

data class NuvioExtendedColors(
    val backgroundElevated: Color,
    val backgroundCard: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val focusRing: Color,
    val focusBackground: Color,
    val rating: Color
)

val LocalNuvioColors = staticCompositionLocalOf {
    NuvioColorScheme(ThemeColors.Ocean)
}

val LocalNuvioExtendedColors = staticCompositionLocalOf {
    NuvioExtendedColors(
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF242424),
        textSecondary = Color(0xFFB3B3B3),
        textTertiary = Color(0xFF808080),
        focusRing = ThemeColors.Ocean.focusRing,
        focusBackground = ThemeColors.Ocean.focusBackground,
        rating = Color(0xFFFFD700)
    )
}

val LocalNuvioTextStyles = staticCompositionLocalOf { NuvioTextStyles }

val LocalAppTheme = staticCompositionLocalOf { AppTheme.WHITE }

val LocalSettingsUiStyle = staticCompositionLocalOf { SettingsUiStyle.CLASSIC }

val LocalNuvioFocusRingStyle = staticCompositionLocalOf {
    createFocusRingStyle(ThemeColors.Ocean)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NuvioTheme(
    appTheme: AppTheme = AppTheme.WHITE,
    appFont: AppFont = AppFont.INTER,
    amoledMode: Boolean = false,
    amoledSurfacesMode: Boolean = false,
    settingsUiStyle: SettingsUiStyle = SettingsUiStyle.CLASSIC,
    uiScalePercent: Int = 100,
    presentation: ResolvedAppearance? = null,
    content: @Composable () -> Unit
) {
    val appearance = presentation?.appearance?.takeIf {
        presentation.device.interfaceExperience == InterfaceExperience.NUVIO_V2
    }
    val originalPalette = ThemeColors.getColorPalette(appTheme)
    val palette = if (appearance == null) originalPalette else v2Palette(originalPalette, appearance)
    val focusRingStyle = createFocusRingStyle(palette)
    val colorScheme = NuvioColorScheme(
        palette = palette,
        amoledMode = appearance == null && amoledMode,
        amoledSurfacesMode = appearance == null && amoledSurfacesMode
    )
    val typography = buildNuvioTypography(getFontFamily(appFont))
    val textStyles = buildNuvioTextStyles(typography)

    val materialColorScheme = darkColorScheme(
        primary = colorScheme.Primary,
        onPrimary = colorScheme.OnPrimary,
        secondary = colorScheme.Secondary,
        onSecondary = colorScheme.OnSecondary,
        background = colorScheme.Background,
        surface = colorScheme.Surface,
        surfaceVariant = colorScheme.SurfaceVariant,
        onBackground = colorScheme.TextPrimary,
        onSurface = colorScheme.TextPrimary,
        onSurfaceVariant = colorScheme.TextSecondary,
        error = colorScheme.Error
    )

    val extendedColors = NuvioExtendedColors(
        backgroundElevated = colorScheme.BackgroundElevated,
        backgroundCard = colorScheme.BackgroundCard,
        textSecondary = colorScheme.TextSecondary,
        textTertiary = colorScheme.TextTertiary,
        focusRing = colorScheme.FocusRing,
        focusBackground = colorScheme.FocusBackground,
        rating = colorScheme.Rating
    )

    CompositionLocalProvider(
        LocalV2Appearance provides appearance,
        LocalDeviceUiPreferences provides (presentation?.device ?: DeviceUiPreferences()),
        LocalResolvedAppearance provides presentation,
        LocalUiScaleDecision provides (presentation?.uiScale ?: UiScaleDecision(uiScalePercent, "Original Nuvio")),
        LocalNuvioColors provides colorScheme,
        LocalNuvioExtendedColors provides extendedColors,
        LocalNuvioTextStyles provides textStyles,
        LocalAppTheme provides appTheme,
        LocalSettingsUiStyle provides settingsUiStyle,
        LocalNuvioFocusRingStyle provides focusRingStyle
    ) {
        val baseDensity = LocalDensity.current
        val scaledDensity = remember(baseDensity, uiScalePercent) {
            Density(
                density = baseDensity.density * (uiScalePercent / 100f),
                fontScale = baseDensity.fontScale
            )
        }
        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            MaterialTheme(
                colorScheme = materialColorScheme,
                typography = typography,
                content = content
            )
        }
    }
}

object NuvioTheme {
    val colors: NuvioColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalNuvioColors.current

    val extendedColors: NuvioExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNuvioExtendedColors.current

    val textStyles: NuvioTextStyleTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalNuvioTextStyles.current

    val spacing: NuvioSpacingTokens
        get() = NuvioSpacing.tokens

    val radii: NuvioRadiusTokens
        get() = NuvioRadii.tokens

    val shapes: NuvioShapeTokens
        get() = NuvioShapes.tokens

    val sizes: NuvioSizeTokens
        get() = NuvioSizes.tokens

    val strokes: NuvioStrokeTokens
        get() = NuvioStrokes.tokens

    val elevations: NuvioElevationTokens
        get() = NuvioElevations.tokens

    val effects: NuvioEffectTokens
        get() = NuvioEffects.tokens

    val motion: NuvioMotionTokens
        get() = NuvioMotion.tokens

    val focus: NuvioFocusTokens
        get() = NuvioFocus.tokens

    val focusRing: NuvioFocusRingStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalNuvioFocusRingStyle.current

    val layout: NuvioLayoutTokens
        get() = NuvioLayout.tokens

    val media: NuvioMediaTokens
        get() = NuvioMedia.tokens

    val components: NuvioComponentTokens
        get() = NuvioComponents.tokens

    val currentTheme: AppTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTheme.current

    val settingsUiStyle: SettingsUiStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalSettingsUiStyle.current
}
