package com.nuvio.tv.core.party

import kotlin.math.abs
import kotlin.math.roundToInt

sealed interface PartySyncAction {
    data object Hold : PartySyncAction
    data class Speed(val speed: Float) : PartySyncAction
    data object Seek : PartySyncAction
}

/**
 * How a member closes the gap to the room. A member that must not change speed (bitstream audio, tunnelling,
 * the native video path) only ever seeks, and rarely.
 */
object PartySyncPolicy {
    const val SPEED_DEAD_BAND_MS = 150L
    const val SPEED_RELEASE_MS = 60L
    const val SPEED_SEEK_ABOVE_MS = 2_000L
    const val SPEED_FULL_SCALE_MS = 20_000f
    const val MAX_SPEED_DELTA = 0.05f
    const val MIN_SPEED_DELTA = 0.01f
    const val SPEED_STEP = 0.005f

    const val FIXED_DEAD_BAND_MS = 1_500L
    const val FIXED_URGENT_MS = 5_000L
    const val FIXED_SEEK_INTERVAL_MS = 60_000L
    const val FIXED_URGENT_INTERVAL_MS = 10_000L

    /** [driftMs] is the room position minus ours: positive means we are behind. */
    fun decide(
        driftMs: Long,
        speedAllowed: Boolean,
        nudging: Boolean,
        msSinceLastSeek: Long,
        resyncRequested: Boolean = false,
    ): PartySyncAction {
        val gap = abs(driftMs)
        if (!speedAllowed) {
            return when {
                resyncRequested && gap > SPEED_DEAD_BAND_MS -> PartySyncAction.Seek
                gap > FIXED_URGENT_MS && msSinceLastSeek >= FIXED_URGENT_INTERVAL_MS -> PartySyncAction.Seek
                gap > FIXED_DEAD_BAND_MS && msSinceLastSeek >= FIXED_SEEK_INTERVAL_MS -> PartySyncAction.Seek
                else -> PartySyncAction.Hold
            }
        }
        return when {
            gap > SPEED_SEEK_ABOVE_MS -> PartySyncAction.Seek
            resyncRequested && gap > SPEED_DEAD_BAND_MS -> PartySyncAction.Seek
            gap > SPEED_DEAD_BAND_MS || (nudging && gap > SPEED_RELEASE_MS) -> {
                val delta = (driftMs / SPEED_FULL_SCALE_MS).coerceIn(-MAX_SPEED_DELTA, MAX_SPEED_DELTA)
                val floored = if (abs(delta) < MIN_SPEED_DELTA) MIN_SPEED_DELTA * (if (driftMs < 0) -1f else 1f) else delta
                PartySyncAction.Speed(1f + (floored / SPEED_STEP).roundToInt() * SPEED_STEP)
            }
            else -> PartySyncAction.Speed(1f)
        }
    }
}
