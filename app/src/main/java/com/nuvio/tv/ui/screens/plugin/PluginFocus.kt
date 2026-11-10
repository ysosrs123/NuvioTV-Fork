package com.nuvio.tv.ui.screens.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.ui.screens.settings.isV2Settings
import com.nuvio.tv.ui.screens.settings.settingsFocusFillColor
import com.nuvio.tv.ui.screens.settings.settingsItemShape
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.components.nuvioV2Focus

@Composable
internal fun Modifier.pluginFocus(shape: Shape = settingsItemShape()): Modifier {
    var focused by remember { mutableStateOf(false) }
    if (!isV2Settings()) return this
    return nuvioV2Focus(focused, shape, stationary = true)
        .onFocusChanged { focused = it.hasFocus }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun pluginFocusedBorder(shape: Shape): Border =
    if (isV2Settings()) Border.None
    else Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)

@Composable
internal fun pluginFocusedFill(): Color =
    if (isV2Settings()) settingsFocusFillColor() else NuvioTheme.colors.FocusBackground
