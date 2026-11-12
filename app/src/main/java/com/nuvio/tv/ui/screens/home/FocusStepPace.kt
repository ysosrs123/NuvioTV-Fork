package com.nuvio.tv.ui.screens.home

/**
 * Extra wait before a focused card's lookups while the viewer is stepping quickly. A card reports
 * focus after its own settle time, so at a brisk pace every card passed would still start lookups
 * whose result cannot be shown before the focus has moved on.
 */
internal class FocusStepPace(
    private val quickStepMs: Long = QUICK_STEP_MS,
    private val extraWaitMs: Long = EXTRA_WAIT_MS
) {
    private var lastFocusAtMs = Long.MIN_VALUE

    @Volatile
    var extraWaitNowMs: Long = 0L
        private set

    fun onFocus(nowMs: Long): Long {
        val previous = lastFocusAtMs
        lastFocusAtMs = nowMs
        val quick = previous != Long.MIN_VALUE && nowMs - previous in 0 until quickStepMs
        extraWaitNowMs = if (quick) extraWaitMs else 0L
        return extraWaitNowMs
    }

    companion object {
        const val QUICK_STEP_MS = 450L
        const val EXTRA_WAIT_MS = 200L
    }
}
