package com.nuvio.tv.ui.screens.player

/**
 * Scrub step sizes and cadence for remote D-pad and media keys. A held key repeats about every 50 ms, so a held
 * scrub steps at most once per [STEP_INTERVAL_MS] to keep each seek thumbnail readable. The step grows with the
 * hold time.
 */
object PlayerScrubRates {
    const val STEP_SHORT_MS = 10_000L

    const val STEP_INTERVAL_MS = 200L

    /** (hold duration from, step size), ascending. */
    private val RAMP = listOf(
        0L to STEP_SHORT_MS,
        2_000L to 30_000L,
        5_000L to 60_000L,
        10_000L to 120_000L,
    )

    /** Step size for a key held for [holdDurationMs] (0 = a tap). */
    fun stepMsForHold(holdDurationMs: Long): Long =
        RAMP.lastOrNull { holdDurationMs >= it.first }?.second ?: STEP_SHORT_MS

    fun deltaMsForHold(holdDurationMs: Long, forward: Boolean): Long {
        val step = stepMsForHold(holdDurationMs)
        return if (forward) step else -step
    }

    private var cadenceDownTime = Long.MIN_VALUE
    private var cadenceLastStepAt = 0L

    /** True for the first press of a hold, then at most once per [STEP_INTERVAL_MS]. Main thread only. */
    fun acceptStep(downTime: Long, eventTime: Long): Boolean {
        if (downTime != cadenceDownTime) {
            cadenceDownTime = downTime
            cadenceLastStepAt = eventTime
            return true
        }
        if (eventTime - cadenceLastStepAt < STEP_INTERVAL_MS) return false
        cadenceLastStepAt = eventTime
        return true
    }
}
