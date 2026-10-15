package com.nuvio.tv.core.calendar

import com.nuvio.tv.ui.screens.settings.MemoryBudget
import javax.inject.Inject
import javax.inject.Singleton

/** A small heap counts as weak. */
fun calendarRefreshConcurrency(lowRamTier: Boolean): Int = if (lowRamTier) 2 else 4

@Singleton
class CalendarDeviceTier @Inject constructor() {
    fun refreshConcurrency(): Int = calendarRefreshConcurrency(MemoryBudget.isLowRamTier)
}
