package com.nuvio.tv.core.calendar

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarSettingsTest {

    @Test fun defaults() {
        assertEquals(
            CalendarSettings(
                menuVisible = true,
                includePersonalLists = false,
                windowDays = 30,
                zoneOverride = null,
                cornerMessage = true,
                comingUpRow = false,
                hiddenTitleKeys = emptySet(),
                bellOn = emptySet()
            ),
            emptyPreferences().toCalendarSettings()
        )
    }

    @Test fun storedValuesAreReadAndOddOnesFallBack() {
        val preferences = mutablePreferencesOf(
            CalendarSettingsKeys.menuVisible to false,
            CalendarSettingsKeys.windowDays to 45,
            CalendarSettingsKeys.zoneOverride to "Mars/Olympus",
            CalendarSettingsKeys.bellOn to setOf("series:imdb:tt1")
        )
        val settings = preferences.toCalendarSettings()
        assertEquals(false, settings.menuVisible)
        assertEquals(30, settings.windowDays)
        assertNull(settings.zoneOverride)
        assertEquals(setOf("series:imdb:tt1"), settings.bellOn)
        assertEquals(14, normalizeCalendarWindowDays(14))
        assertEquals(60, normalizeCalendarWindowDays(60))
        assertEquals("Australia/Sydney", normalizeCalendarZoneOverride(" Australia/Sydney "))
    }

    @Test fun turningABellOffRemovesItUnderAnyOfTheTitlesIds() {
        val bells = setOf("series:tmdb:5", "series:imdb:tt9", "movie:tmdb:5")
        assertEquals(setOf("series:imdb:tt9", "movie:tmdb:5"), bells.withTitleKey("series:tmdb:5", present = false))
        assertEquals(bells + "series:imdb:tt2", bells.withTitleKey("series:imdb:tt2", present = true))
    }

    @Test fun hiddenTitlesKeepTheirNamesOnlyWhileHidden() {
        val names = mapOf("series:imdb:tt1" to "The Harbour", "movie:tmdb:7" to "Kestrel")
        assertEquals(names, decodeCalendarHiddenNames(encodeCalendarHiddenNames(names)))
        val preferences = mutablePreferencesOf(
            CalendarSettingsKeys.hiddenTitleKeys to setOf("series:imdb:tt1"),
            CalendarSettingsKeys.hiddenTitleNames to encodeCalendarHiddenNames(names) + "broken"
        )
        assertEquals(mapOf("series:imdb:tt1" to "The Harbour"), preferences.toCalendarSettings().hiddenTitleNames)
    }

    @Test fun zoneFollowsTheBoxUnlessOverridden() {
        val box = ZoneId.of("Australia/Sydney")
        assertEquals(box, resolveCalendarZone(null, box))
        assertEquals(box, resolveCalendarZone("nowhere", box))
        assertEquals(ZoneId.of("Europe/Berlin"), resolveCalendarZone("Europe/Berlin", box))
    }

    @Test fun weakBoxesRefreshTwoAtATime() {
        assertEquals(2, calendarRefreshConcurrency(lowRamTier = true))
        assertEquals(4, calendarRefreshConcurrency(lowRamTier = false))
    }
}
