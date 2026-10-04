package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.*

/** Popup membership uses saved order; preview also exposes hidden/unsupported members. */
internal fun playerControlMoreEntries(layout: PlayerControlLayout, available: Set<PlayerControlAction>, preview: Boolean): List<PlayerControlPlacement> {
    val toggle = layout.entries.single { it.action == PlayerControlAction.MORE }
    if (!toggle.visible || toggle.action !in available) return emptyList()
    return layout.entries.filter { it.group == toggle.group }.dropWhile { it.action != toggle.action }.drop(1)
        .filter { preview || (it.visible && it.action in available) }
}

/** Opening a popup never changes primary geometry or the stable editable ghost positions. */
internal fun playerControlPrimaryDeckPlan(layout: PlayerControlLayout, available: Set<PlayerControlAction>, sizes: Map<PlayerControlAction, PlayerControlDeckSize>, width: Int, gap: Int, preview: Boolean, labelHeight: Int): PlayerControlDeckPlan =
    playerControlDeckPlan(layout, available, sizes, width, gap, preview, labelHeight, moreExpanded = false)

internal data class PlayerControlPopupBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = (right - left).coerceAtLeast(0)
    val height get() = (bottom - top).coerceAtLeast(0)
}
internal data class PlayerControlPopupPosition(val x: Int, val y: Int)

/** Prefer above the More anchor, then below; clamp oversized content to its allowed viewport. */
internal fun playerControlPopupPosition(anchor: PlayerControlPopupBounds, viewport: PlayerControlPopupBounds, width: Int, height: Int, gap: Int): PlayerControlPopupPosition {
    require(width >= 0 && height >= 0 && gap >= 0)
    val maxX = (viewport.right - width).coerceAtLeast(viewport.left)
    val maxY = (viewport.bottom - height).coerceAtLeast(viewport.top)
    val above = anchor.top - gap - height
    val below = anchor.bottom + gap
    val y = when {
        above >= viewport.top -> above
        below + height <= viewport.bottom -> below
        else -> above
    }
    return PlayerControlPopupPosition((anchor.right - width).coerceIn(viewport.left, maxX), y.coerceIn(viewport.top, maxY))
}

internal data class PlayerControlChromePolicy(val showDeckPanel: Boolean, val backgroundAlpha: Float)
/** Same panel/backdrop distinction as the existing V2 renderer; button style is independent. */
internal fun playerControlChromePolicy(v2: Boolean, controlDeck: Boolean, cinematicGlass: Boolean) =
    PlayerControlChromePolicy(v2 && controlDeck, if (!v2) .65f else if (cinematicGlass) .20f else if (controlDeck) .55f else .78f)

/** A modal popup recovers within supported menu entries; null delegates to its Close button. */
internal fun playerControlPopupFocusFallback(layout: PlayerControlLayout, available: Set<PlayerControlAction>, previous: PlayerControlAction?): PlayerControlAction? {
    val actions = playerControlMoreEntries(layout, available, false).map { it.action }
    return previous?.takeIf { it in actions } ?: actions.firstOrNull()
}
