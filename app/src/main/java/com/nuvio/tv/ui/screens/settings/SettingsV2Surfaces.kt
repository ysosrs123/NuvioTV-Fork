package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.SettingsPresentation
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.V2Atmosphere
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus

@Composable
@ReadOnlyComposable
internal fun isV2Settings() = LocalV2Appearance.current != null

@Composable
@ReadOnlyComposable
internal fun settingsItemShape(originalRadius: Dp = SettingsPillRadius) =
    RoundedCornerShape(if (isV2Settings()) 12.dp else originalRadius)

/** Nested controls share the enclosing frost without creating a blur layer for every row. */
@Composable
@ReadOnlyComposable
internal fun settingsItemColor(original: Color): Color =
    if (com.nuvio.tv.ui.v2.components.usesClearGlassTheme()) Color.Black.copy(alpha = .025f)
    else if (isV2Settings()) NuvioTheme.colors.TextPrimary.copy(alpha = .055f) else original

@Composable
@ReadOnlyComposable
internal fun settingsItemVerticalPadding(): Dp = if (isV2Settings()) 10.dp else NuvioTheme.spacing.md

@Composable
internal fun Modifier.settingsItemFocus(focused: Boolean): Modifier =
    if (isV2Settings()) nuvioV2Focus(focused, settingsItemShape(), stationary = true) else this

/** Standalone settings cards need their own material, unlike rows nested in a group panel. */
@Composable
internal fun Modifier.settingsSubmenuSurface(): Modifier = when {
    !isV2Settings() -> this
    LocalV2Appearance.current?.settingsPresentation == SettingsPresentation.GLASS ->
        nuvioGlass(GlassRole.CONTROL, shape = settingsItemShape())
    else -> background(settingsItemColor(Color.Transparent), settingsItemShape())
}

@Composable
internal fun SettingsPageAtmosphere() {
    LocalV2Appearance.current?.let {
        V2Atmosphere(rich = it.settingsPresentation == SettingsPresentation.GLASS, background = it.settingsBackground)
    }
}
