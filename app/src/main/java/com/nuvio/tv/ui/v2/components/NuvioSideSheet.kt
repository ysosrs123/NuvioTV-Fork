package com.nuvio.tv.ui.v2.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Activity-local modal focus boundary: opening a player sheet never creates a display-mode window. */
@Composable
fun NuvioSideSheet(
    onDismiss: () -> Unit,
    width: Dp = 460.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        focus.requestFocus()
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 48.dp, vertical = 28.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        NuvioGlassSurface(GlassRole.PANEL, Modifier.width(width).fillMaxHeight()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp).focusRequester(focus)
                    .focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
                content = content
            )
        }
    }
}
