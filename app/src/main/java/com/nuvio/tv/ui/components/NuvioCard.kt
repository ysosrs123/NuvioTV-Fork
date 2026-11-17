package com.nuvio.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Border
import androidx.tv.material3.Glow
import com.nuvio.tv.ui.v2.components.nuvioV2Focus

/**
 * A [LightweightCard] with [focusedBorder] and [focusedGlow], or with [light] a plain focusable column with
 * the same clip, colour and select key click, for looks without the card's border, glow and zoom.
 * [ownFocusEdge] is a V2 edge the card draws on itself; its focus is read here, so a focus change
 * recomposes this card frame and not the content.
 */
@Composable
fun NuvioCard(
    onClick: () -> Unit,
    shape: Shape,
    light: Boolean,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent,
    focusedBorder: Border = Border.None,
    focusedGlow: Glow = Glow.None,
    focusChromeHidden: () -> Boolean = { false },
    ownFocusEdge: OwnFocusEdge? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!light) {
        LightweightCard(
            onClick = onClick,
            shape = shape,
            containerColor = containerColor,
            modifier = modifier,
            focusedBorder = focusedBorder,
            focusedGlow = focusedGlow,
            focusChromeHidden = focusChromeHidden,
            content = content
        )
        return
    }
    val press = remember { SelectKeyPress() }
    val edge = if (ownFocusEdge != null) {
        Modifier.nuvioV2Focus(ownFocusEdge.focused.value, ownFocusEdge.shape, stationary = true)
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .then(edge)
            .semantics { onClick { onClick(); true } }
            .onFocusChanged { if (!it.isFocused) press.keyCode = null }
            .onKeyEvent { press.handle(it.nativeKeyEvent, onClick) }
            .focusable()
            .clip(shape)
            .then(if (containerColor.alpha > 0f) Modifier.background(containerColor) else Modifier),
        content = content
    )
}

/** A V2 focus edge drawn by the card itself, shown while [focused] is true. */
@Stable
class OwnFocusEdge(val shape: Shape, val focused: State<Boolean>)

/** As Modifier.zIndex([zIndex]) while [focused] is true, read only while placing. */
fun Modifier.focusZIndex(focused: State<Boolean>, zIndex: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.place(0, 0, zIndex = if (focused.value) zIndex else 0f)
    }
}

/** Clicks when a select key goes up after going down on this card. */
private class SelectKeyPress {
    var keyCode: Int? = null

    fun handle(event: AndroidKeyEvent, onClick: () -> Unit): Boolean {
        if (!isSelect(event.keyCode)) return false
        return when (event.action) {
            AndroidKeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) keyCode = event.keyCode
                keyCode == event.keyCode
            }
            AndroidKeyEvent.ACTION_UP -> {
                if (keyCode != event.keyCode) return false
                keyCode = null
                onClick()
                true
            }
            else -> false
        }
    }

    private fun isSelect(code: Int) = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        code == AndroidKeyEvent.KEYCODE_ENTER ||
        code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}
