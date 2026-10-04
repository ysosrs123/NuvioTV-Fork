package com.nuvio.tv.ui.v2.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance

/** Adds shared V2 decoration to an existing control without replacing its key/click handling. */
@Composable
fun Modifier.nuvioControlSurface(shape: Shape): Modifier {
    if (LocalV2Appearance.current == null) return this
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }
        .nuvioV2Focus(focused, shape, stationary = true)
        .nuvioGlass(GlassRole.CONTROL, focused, shape)
}
