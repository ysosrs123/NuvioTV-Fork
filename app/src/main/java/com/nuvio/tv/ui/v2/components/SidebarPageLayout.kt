package com.nuvio.tv.ui.v2.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset

/** Only foreground content moves. Page artwork keeps one screen-sized coordinate space. */
class SidebarPageLayout(private val inset: () -> Dp, val expandedWidth: Dp, val expansion: () -> Float) {
    val startInset: Dp get() = inset()
}

val LocalSidebarPageLayout = staticCompositionLocalOf<SidebarPageLayout?> { null }

/** Home navigation samples artwork plus its page scrim; other controls keep their own source. */
val LocalSidebarBackdropSource = staticCompositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

@Composable
fun Modifier.sidebarPageContent(): Modifier {
    val page = LocalSidebarPageLayout.current ?: return this
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    return layout { measurable, constraints ->
        val inset = page.startInset.roundToPx()
        val placeable = measurable.measure(constraints.offset(horizontal = -inset))
        layout(constraints.constrainWidth(placeable.width + inset), constraints.constrainHeight(placeable.height)) { placeable.placeRelative(inset, 0) }
    }.graphicsLayer {
        translationX = (page.expandedWidth - page.startInset).toPx() * page.expansion() * direction
    }
}
