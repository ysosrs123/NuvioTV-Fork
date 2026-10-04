package com.nuvio.tv.ui.screens.player

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Time layout of the seek strip. Tile i sits at anchorMs + i * spacingMs and shows the stored thumbnail
 * nearest that time.
 */
internal object SeekStripLayout {

    /**
     * Grid multiple of the tile spacing: tiles at least three steps apart, so one step moves the strip by a third
     * of a tile at most. Taken from the smaller of the last two steps, so one long step does not re-space the strip.
     */
    fun multiple(stepMs: Long, previousStepMs: Long, baseSpacingMs: Long): Long {
        if (baseSpacingMs <= 0L) return 1L
        val step = if (previousStepMs > 0L) minOf(stepMs, previousStepMs) else stepMs
        return ((3L * step + baseSpacingMs - 1) / baseSpacingMs).coerceAtLeast(1L)
    }

    /** Anchor for [newSpacingMs] that leaves every tile where it was on screen at [glideMs]. */
    fun respacedAnchor(anchorMs: Double, glideMs: Double, oldSpacingMs: Double, newSpacingMs: Double): Double {
        if (oldSpacingMs <= 0.0 || newSpacingMs <= 0.0) return anchorMs
        val near = anchorMs + Math.round((glideMs - anchorMs) / oldSpacingMs) * oldSpacingMs
        return glideMs + (near - glideMs) * newSpacingMs / oldSpacingMs
    }

    /** Indices of the tiles within [reachMs] of [posMs]. */
    fun tileIndices(anchorMs: Double, posMs: Double, reachMs: Double, spacingMs: Double): LongRange {
        if (spacingMs <= 0.0) return LongRange.EMPTY
        return ceil((posMs - reachMs - anchorMs) / spacingMs).toLong()..floor((posMs + reachMs - anchorMs) / spacingMs).toLong()
    }

    /** Stored thumbnail time for a tile at [tileMs]. */
    fun pictureMs(tileMs: Double, baseSpacingMs: Long): Long =
        if (baseSpacingMs <= 0L) tileMs.roundToLong() else (tileMs / baseSpacingMs).roundToLong() * baseSpacingMs
}
