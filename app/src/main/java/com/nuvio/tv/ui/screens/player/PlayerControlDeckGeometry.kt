package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.*
import kotlin.math.abs

internal data class PlayerControlDeckSize(val width: Int, val height: Int)
/** hidden identifies preview-only placement outside the main deck; brightness uses saved visibility/capability. */
internal data class PlayerControlDeckCell(val action: PlayerControlAction, val x: Int, val y: Int, val width: Int, val height: Int, val hidden: Boolean) {
    val centreX get() = x + width / 2
    val centreY get() = y + height / 2
}
internal enum class PlayerControlDirection { LEFT, RIGHT, UP, DOWN }
internal data class PlayerControlDeckPlan(val cells: List<PlayerControlDeckCell>, val width: Int, val height: Int, val mainHeight: Int, val hiddenLabelY: Int?, val collapsedActions: Set<PlayerControlAction> = emptySet(), val moreLabelY: Int? = null, val moreLabelX: Int = 0, val previewSectionWidth: Int = 0) {
    fun neighbour(action: PlayerControlAction, direction: PlayerControlDirection): PlayerControlAction? {
        val from = cells.firstOrNull { it.action == action } ?: return null
        val horizontal = direction == PlayerControlDirection.LEFT || direction == PlayerControlDirection.RIGHT
        val sign = if (direction == PlayerControlDirection.LEFT || direction == PlayerControlDirection.UP) -1 else 1
        val candidates = cells.filter { it.action != action &&
            (if (horizontal) (it.centreX - from.centreX) * sign > 0 && abs(it.centreY - from.centreY) <= maxOf(it.height, from.height) / 2
             else (it.centreY - from.centreY) * sign > maxOf(it.height, from.height) / 2) }
        return candidates.minWithOrNull(compareBy<PlayerControlDeckCell> {
            if (horizontal) abs(it.centreX - from.centreX) else abs(it.centreY - from.centreY)
        }.thenBy { if (horizontal) abs(it.centreY - from.centreY) else abs(it.centreX - from.centreX) }.thenBy { it.action.ordinal })?.action
    }
}

/** Saved menu membership survives hiding More. Its actions stay hidden until More is shown,
 * while an unavailable but visible toggle retains the direct-action fallback.
 */
internal fun playerControlCollapsedActions(layout: PlayerControlLayout, available: Set<PlayerControlAction>): Set<PlayerControlAction> {
    val toggle = layout.entries.first { it.action == PlayerControlAction.MORE }
    if (toggle.visible && toggle.action !in available) return emptySet()
    return layout.entries.filter { it.group == toggle.group }.dropWhile { it.action != toggle.action }.drop(1)
        .filter { it.visible && it.action in available }.map { it.action }.toSet()
}

/** Preview brightness reflects the action's saved choice and capability, not popup collapse. */
internal fun playerControlPreviewFaded(entry: PlayerControlPlacement, available: Set<PlayerControlAction>): Boolean =
    !entry.visible || entry.action !in available

internal fun playerControlDeckAvailable(layout: PlayerControlLayout, available: Set<PlayerControlAction>, moreExpanded: Boolean): Set<PlayerControlAction> =
    if (moreExpanded && layout.entries.single { it.action == PlayerControlAction.MORE }.visible) available
    else available - playerControlCollapsedActions(layout, available)

/** Runtime and preview share measured packing. More's visual anchor is independent of its saved menu boundary. */
internal fun playerControlDeckPlan(layout: PlayerControlLayout, available: Set<PlayerControlAction>, sizes: Map<PlayerControlAction, PlayerControlDeckSize>, width: Int, requestedGap: Int, showHidden: Boolean, hiddenLabelHeight: Int, moreExpanded: Boolean = true): PlayerControlDeckPlan {
    require(width >= 0 && requestedGap >= 0 && hiddenLabelHeight >= 0)
    require(sizes.values.all { it.width >= 0 && it.height >= 0 })
    val gap = requestedGap.coerceAtMost(width / 2)
    val region = ((width - gap * 2).coerceAtLeast(0) / 3)
    val collapsed = available - playerControlDeckAvailable(layout, available, moreExpanded)
    val visible = layout.visibleGroups(available - collapsed)
    val more = PlayerControlAction.MORE
    val hasMore = visible.values.any { more in it } && sizes.containsKey(more)
    val actions = visible.mapValues { (_, entries) -> entries.filter { it != more } }
    fun natural(entries: List<PlayerControlAction>) = entries.sumOf { sizes[it]?.width ?: 0 } + gap * (entries.size - 1).coerceAtLeast(0)
    var leftWidth = region
    var rightWidth = region
    // An empty centre is usable space. Grant either side its measured need first;
    // if both overflow, share the remaining capacity without overlap.
    val centreEmpty = actions.getValue(PlayerControlGroup.CENTRE).isEmpty()
    if (centreEmpty) {
        val capacity = (width - gap).coerceAtLeast(0)
        val leftNeed = natural(actions.getValue(PlayerControlGroup.LEFT))
        val rightNeed = natural(actions.getValue(PlayerControlGroup.RIGHT)) +
            if (hasMore) (sizes.getValue(more).width + if (actions.getValue(PlayerControlGroup.RIGHT).isEmpty()) 0 else gap) else 0
        val half = capacity / 2
        leftWidth = when {
            leftNeed <= half -> leftNeed
            rightNeed <= half -> (capacity - rightNeed).coerceAtLeast(0)
            else -> half
        }.coerceIn(0, capacity)
        rightWidth = capacity - leftWidth
    }
    val moreWidth = if (hasMore) sizes.getValue(more).width.coerceIn(0, rightWidth) else 0
    val rightActionWidth = (rightWidth - moreWidth - if (hasMore && actions.getValue(PlayerControlGroup.RIGHT).isNotEmpty()) gap else 0).coerceAtLeast(0)
    fun rows(entries: List<PlayerControlAction>, limit: Int, firstLimit: Int = limit): List<List<PlayerControlAction>> {
        val result = mutableListOf<List<PlayerControlAction>>()
        var row = mutableListOf<PlayerControlAction>(); var used = 0
        for (action in entries) {
            val size = sizes[action] ?: continue
            var rowLimit = if (result.isEmpty()) firstLimit else limit
            val next = size.width.coerceIn(0, limit) + if (row.isEmpty()) 0 else gap
            if ((row.isNotEmpty() && used + next > rowLimit) || (result.isEmpty() && row.isEmpty() && size.width.coerceAtMost(limit) > firstLimit)) {
                result += row; row = mutableListOf(); used = 0; rowLimit = limit
            }
            used += size.width.coerceIn(0, rowLimit) + if (row.isEmpty()) 0 else gap; row += action
        }
        if (row.isNotEmpty()) result += row
        return result
    }
    fun rowHeight(row: List<PlayerControlAction>) = row.maxOfOrNull { sizes.getValue(it).height } ?: 0
    fun blockHeight(rows: List<List<PlayerControlAction>>, anchored: Boolean = false) = rows.mapIndexed { i, row ->
        maxOf(rowHeight(row), if (anchored && i == 0) sizes.getValue(more).height else 0)
    }.sum() + gap * (rows.size - 1).coerceAtLeast(0)
    val groupRows = actions.mapValues { (group, entries) -> rows(entries, when (group) {
        PlayerControlGroup.LEFT -> leftWidth; PlayerControlGroup.CENTRE -> region; PlayerControlGroup.RIGHT -> rightWidth
    }, if (group == PlayerControlGroup.RIGHT) rightActionWidth else if (group == PlayerControlGroup.LEFT) leftWidth else region) }
    val mainHeight = maxOf(groupRows.entries.maxOfOrNull { (group, rows) -> blockHeight(rows, hasMore && group == PlayerControlGroup.RIGHT) } ?: 0, if (hasMore) sizes.getValue(more).height else 0)
    val cells = mutableListOf<PlayerControlDeckCell>()
    fun placeRows(rows: List<List<PlayerControlAction>>, left: Int, limit: Int, initialY: Int, alignment: PlayerControlGroup, hidden: Boolean, anchored: Boolean = false) {
        var y = initialY
        for ((index, row) in rows.withIndex()) {
            val rowLimit = if (anchored && index == 0) rightActionWidth else limit
            val used = row.sumOf { sizes.getValue(it).width.coerceIn(0, rowLimit) } + gap * (row.size - 1).coerceAtLeast(0)
            var x = left + when (alignment) { PlayerControlGroup.LEFT -> 0; PlayerControlGroup.CENTRE -> (rowLimit - used) / 2; PlayerControlGroup.RIGHT -> rowLimit - used }
            val height = maxOf(rowHeight(row), if (anchored && index == 0) sizes.getValue(more).height else 0)
            for (action in row) {
                val size = sizes.getValue(action); val cellWidth = size.width.coerceIn(0, rowLimit)
                cells += PlayerControlDeckCell(action, x, y + (height - size.height) / 2, cellWidth, size.height, hidden)
                x += cellWidth + gap
            }
            y += height + gap
        }
    }
    for (group in PlayerControlGroup.entries) {
        val limit = when (group) { PlayerControlGroup.LEFT -> leftWidth; PlayerControlGroup.CENTRE -> region; PlayerControlGroup.RIGHT -> rightWidth }
        val left = when (group) { PlayerControlGroup.LEFT -> 0; PlayerControlGroup.CENTRE -> (width - region) / 2; PlayerControlGroup.RIGHT -> width - rightWidth }
        placeRows(groupRows.getValue(group), left, limit, 0, group, false, hasMore && group == PlayerControlGroup.RIGHT)
    }
    if (hasMore) {
        val size = sizes.getValue(more)
        cells += PlayerControlDeckCell(more, width - moreWidth, 0, moreWidth, size.height, false)
    }
    // Preview-only sections retain one stable keyed widget per action. Explicit visibility
    // and availability take precedence over menu membership; hiding More retains its members.
    val hidden = if (showHidden) layout.entries.filter { !it.visible || it.action !in available }.map { it.action } else emptyList()
    val menu = if (showHidden) layout.entries.filter { it.action in collapsed }.map { it.action } else emptyList()
    val sectionWidth = (width - gap).coerceAtLeast(0) / 2
    val menuX = sectionWidth + gap
    val hiddenRows = rows(hidden, sectionWidth)
    val menuRows = rows(menu, (width - menuX).coerceAtLeast(0))
    val labelY = if (showHidden) mainHeight + if (mainHeight > 0) gap * 2 else 0 else null
    if (labelY != null) {
        placeRows(hiddenRows, 0, sectionWidth, labelY + hiddenLabelHeight, PlayerControlGroup.LEFT, true)
        placeRows(menuRows, menuX, (width - menuX).coerceAtLeast(0), labelY + hiddenLabelHeight, PlayerControlGroup.RIGHT, true)
    }
    val height = if (labelY == null) mainHeight else labelY + hiddenLabelHeight + maxOf(blockHeight(hiddenRows), blockHeight(menuRows))
    return PlayerControlDeckPlan(cells, width, height, mainHeight, labelY, collapsed,
        moreLabelY = labelY, moreLabelX = if (showHidden) menuX else 0, previewSectionWidth = if (showHidden) sectionWidth else 0)
}
