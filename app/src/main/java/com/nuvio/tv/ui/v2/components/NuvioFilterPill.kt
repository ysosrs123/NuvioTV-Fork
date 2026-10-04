package com.nuvio.tv.ui.v2.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp

/** Shared filter surface; selection and focus keep separate visual indicators. */
@Composable
fun NuvioFilterPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = remember { RoundedCornerShape(12.dp) }
    Row(
        modifier.onFocusChanged { focused = it.isFocused }
            .nuvioV2Focus(focused, shape, stationary = true)
            .nuvioGlass(GlassRole.CONTROL, selected, shape)
            .nuvioRemoteClick(onClick)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

/** TV remotes send DPAD_CENTER; keep one activation on release, including repeat holds. */
@Composable
fun Modifier.nuvioRemoteClick(onClick: () -> Unit): Modifier {
    var armed by remember { mutableStateOf(false) }
    return onFocusChanged { if (!it.isFocused) armed = false }.onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
            native.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
            native.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
            when (native.action) {
                android.view.KeyEvent.ACTION_DOWN -> if (native.repeatCount == 0) armed = true
                android.view.KeyEvent.ACTION_UP -> {
                    val activate = armed && !native.isCanceled
                    armed = false
                    if (activate) onClick()
                }
            }
            true
        } else false
    }
}
