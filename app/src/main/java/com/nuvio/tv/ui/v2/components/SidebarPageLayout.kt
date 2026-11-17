package com.nuvio.tv.ui.v2.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/** Only foreground content moves. Page artwork keeps one screen-sized coordinate space. */
class SidebarPageLayout(val startInset: Dp, val expandedWidth: Dp, val expansion: () -> Float)

val LocalSidebarPageLayout = staticCompositionLocalOf<SidebarPageLayout?> { null }

/** Home navigation samples artwork plus its page scrim; other controls keep their own source. */
val LocalSidebarBackdropSource = staticCompositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

@Composable
fun Modifier.sidebarPageContent(): Modifier =
    sidebarPageContent(LocalSidebarPageLayout.current, LocalLayoutDirection.current)

fun Modifier.sidebarPageContent(layout: SidebarPageLayout?, layoutDirection: LayoutDirection): Modifier {
    if (layout == null) return this
    val direction = if (layoutDirection == LayoutDirection.Rtl) -1 else 1
    return padding(start = layout.startInset).graphicsLayer {
        translationX = (layout.expandedWidth - layout.startInset).toPx() * layout.expansion() * direction
    }
}
