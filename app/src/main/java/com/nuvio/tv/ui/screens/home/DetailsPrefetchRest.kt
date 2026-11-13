package com.nuvio.tv.ui.screens.home

/**
 * Full details for the details page are fetched once the focus has rested about a second (the settle runs 250 ms after
 * the focus). Stepping on cancels the wait, so a quick pass over a row asks for hero data only.
 */
internal object DetailsPrefetchRest {
    const val AFTER_SETTLE_MS = 750L

    fun remainingMs(settledAtMs: Long, nowMs: Long): Long = (settledAtMs + AFTER_SETTLE_MS - nowMs).coerceAtLeast(0L)
}
