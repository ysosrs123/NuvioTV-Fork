package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.mutableStateOf
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Preserve row identity when a focused card has transformed visual bounds. */
internal fun adjacentHomeRowIndex(current: Int, count: Int, down: Boolean): Int? {
    if (current !in 0 until count) return null
    return (current + if (down) 1 else -1).takeIf { it in 0 until count }
}

/** A card's place in its row, in the row's item coordinates (the same for every Home row). */
internal data class RowCardSpan(val index: Int, val offset: Int, val size: Int)

/**
 * The card at the same spot on screen as the focused one, so Up and Down keep the column. A row that ends
 * earlier gives its last card; a card cut off at the edge is still taken, and the row brings it in.
 */
internal fun sameColumnCardIndex(centerPx: Int, cards: List<RowCardSpan>): Int? =
    cards.minByOrNull { abs(it.offset + it.size / 2 - centerPx) }?.index

/**
 * The middle of the focused card's column: where the card comes to rest once its row stops sliding, at the
 * width of the cards next to it, so a grown poster keeps the column of the poster it was. [keepInView] rows
 * only scroll to keep the card on screen; the others bring it to the start. [roomAheadPx] and [roomBackPx] are
 * how far the row can still scroll each way.
 */
internal fun focusedColumnCenter(
    index: Int,
    cards: List<RowCardSpan>,
    visibleEndPx: Int,
    keepInView: Boolean,
    roomAheadPx: Int,
    roomBackPx: Int
): Int? {
    val card = cards.firstOrNull { it.index == index } ?: return null
    val wanted = when {
        !keepInView -> card.offset
        card.offset < 0 -> card.offset
        card.offset + card.size > visibleEndPx -> card.offset + card.size - visibleEndPx
        else -> 0
    }
    val rest = card.offset - wanted.coerceIn(-roomBackPx, roomAheadPx)
    val width = cards.filter { it.index != index }.minByOrNull { abs(it.index - index) }
        ?.let { minOf(card.size, it.size) } ?: card.size
    return rest + width / 2
}

/** The card in the spot when a row has not been laid out yet, from its scroll position and the width of a card. */
internal fun estimatedColumnIndex(centerPx: Int, firstIndex: Int, firstOffsetPx: Int, spanPx: Int): Int =
    firstIndex + Math.floorDiv(centerPx + firstOffsetPx, spanPx)

/** The row an Up or Down starts from: the one the last move is still heading to, for a short while, else focus's row. */
internal fun verticalMoveSource(activeRowKey: String?, headingRowKey: String?, headingAgeMs: Long): String? =
    headingRowKey?.takeIf { it != activeRowKey && headingAgeMs < VERTICAL_HEADING_MS } ?: activeRowKey

internal const val VERTICAL_HEADING_MS = 600L

/** The card focus enters a row on: the one the row is pointed at, kept inside a row that has since shrunk. */
internal fun rowEntryCardIndex(pointedAt: Int?, itemCount: Int): Int? =
    if (itemCount <= 0) null else (pointedAt ?: 0).coerceIn(0, itemCount - 1)

/** Where focus enters a row: the card it was sent to, else the one it is pointed at. */
internal data class RowEntry(val index: Int, val waitForCard: Boolean)

/**
 * A row with a card waiting for focus enters on that card. When the card cannot take focus yet (it was just removed and
 * the next one has not moved into its place), [RowEntry.waitForCard] says to wait for it rather than fall back to the
 * first card. Only rows whose cards are always focusable wait.
 */
internal fun rowEntry(pendingIndex: Int?, pointedAt: Int?, itemCount: Int, cardsAlwaysFocusable: Boolean): RowEntry? {
    val index = rowEntryCardIndex(pendingIndex ?: pointedAt, itemCount) ?: return null
    return RowEntry(index, waitForCard = pendingIndex != null && cardsAlwaysFocusable)
}

/** The card focus moves to when the card at [index] leaves a row of [rowSize]: the one taking its place, else the new last. */
internal fun removedCardFocusTarget(index: Int, rowSize: Int): Int? =
    if (rowSize <= 1) null else index.coerceIn(0, rowSize - 2)

/**
 * Whether a card pointed at by its row takes focus when it is built. A card someone asked focus to go to always may;
 * a card that is only the row's remembered one waits while another card in the row has focus, so it cannot pull focus
 * back from the card the user moved on to.
 */
internal fun shouldClaimFocus(isTarget: Boolean, cardFocused: Boolean, rowHasFocus: Boolean, isPendingTarget: Boolean): Boolean =
    isTarget && !cardFocused && (isPendingTarget || !rowHasFocus)

/**
 * Whether a card's focus report still makes its row the active one. The report runs a frame after focus arrived; if a
 * card in another row took focus meanwhile, making this row active again would let that row's card claim focus back.
 */
internal fun focusReportApplies(cardStillFocused: Boolean): Boolean = cardStillFocused

/** Returns once the Home rows are not scrolling, at once when they already stand still. */
internal suspend fun awaitRowsStill(scrolling: Flow<Boolean>) {
    scrolling.first { !it }
}

/**
 * Waits for the rows to stand still, then says whether the card still has focus. Focus can move on in the frame the
 * rows stop, before the card's job is cancelled.
 */
internal suspend fun stillFocusedOnceRowsStill(scrolling: Flow<Boolean>, cardStillFocused: () -> Boolean): Boolean {
    awaitRowsStill(scrolling)
    return cardStillFocused()
}

/**
 * An Up or Down press counts as moving the rows from the key until its focus job ends. The rows can stand still for a
 * few frames in between, and the card being left must not take that for the end of a move.
 */
internal class VerticalMoveInFlight {
    private var latest = 0
    val active = mutableStateOf(false)

    fun begin(): Int {
        latest += 1
        active.value = true
        return latest
    }

    /** Only the latest press ends it: a job cancelled by the next press finishes after that press began. */
    fun end(token: Int) {
        if (token == latest) active.value = false
    }

    fun clear() {
        latest += 1
        active.value = false
    }
}

/** The rows count as moving while they scroll or while an Up or Down press is still on its way. */
internal fun homeRowsMoving(listScrolling: Boolean, move: VerticalMoveInFlight): Boolean =
    listScrolling || move.active.value

/**
 * How far the rows scroll to put the focused card's top [insetPx] below the top of the rows. Within [tolerancePx] the
 * card counts as in place, and the first row is never pulled down past its start.
 */
internal fun verticalRowScrollDistance(offset: Float, insetPx: Float, canScrollBackward: Boolean, tolerancePx: Float = 1f): Float {
    val distance = offset - insetPx
    if (abs(distance) < tolerancePx) return 0f
    if (distance < 0f && !canScrollBackward) return 0f
    return distance
}

/** The card focus was sent to in a row, once a card of [focusedRowKey] has taken focus: done with as soon as any card in its row has it. */
internal fun pendingAfterFocus(pendingRowKey: String?, pendingIndex: Int?, focusedRowKey: String): Int? =
    if (pendingRowKey == focusedRowKey) null else pendingIndex
