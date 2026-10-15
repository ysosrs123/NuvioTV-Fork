package com.nuvio.tv.core.calendar

import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

fun resolveCalendarZone(override: String?, systemDefault: ZoneId): ZoneId =
    normalizeCalendarZoneOverride(override)?.let(ZoneId::of) ?: systemDefault

@Singleton
class CalendarZoneProvider @Inject constructor(
    private val settingsStore: CalendarSettingsDataStore
) {
    fun zone(): ZoneId = resolveCalendarZone(settingsStore.settings.value?.zoneOverride, ZoneId.systemDefault())

    val zones: Flow<ZoneId> = settingsStore.settings
        .map { resolveCalendarZone(it?.zoneOverride, ZoneId.systemDefault()) }
        .distinctUntilChanged()
}
