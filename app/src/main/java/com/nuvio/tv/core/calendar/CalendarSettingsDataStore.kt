package com.nuvio.tv.core.calendar

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class CalendarSettings(
    val profileId: Int? = null,
    val menuVisible: Boolean = true,
    val includePersonalLists: Boolean = false,
    val windowDays: Int = CALENDAR_DEFAULT_WINDOW_DAYS,
    val zoneOverride: String? = null,
    val cornerMessage: Boolean = true,
    val comingUpRow: Boolean = false,
    val hiddenTitleKeys: Set<String> = emptySet(),
    val bellOn: Set<String> = emptySet(),
    /** Names of hidden titles, so they can be listed after the Calendar has dropped them. */
    val hiddenTitleNames: Map<String, String> = emptyMap()
)

internal object CalendarSettingsKeys {
    val menuVisible = booleanPreferencesKey("calendar_menu_visible")
    val includePersonalLists = booleanPreferencesKey("calendar_include_personal_lists")
    val windowDays = intPreferencesKey("calendar_window_days")
    val zoneOverride = stringPreferencesKey("calendar_zone_override")
    val cornerMessage = booleanPreferencesKey("calendar_corner_message")
    val comingUpRow = booleanPreferencesKey("calendar_coming_up_row")
    val hiddenTitleKeys = stringSetPreferencesKey("calendar_hidden_titles")
    val bellOn = stringSetPreferencesKey("calendar_bell_on")
    val hiddenTitleNames = stringSetPreferencesKey("calendar_hidden_names")
}

private const val NAME_SEPARATOR = '\u001F'

internal fun encodeCalendarHiddenNames(names: Map<String, String>): Set<String> =
    names.mapTo(linkedSetOf()) { (key, name) -> "$key$NAME_SEPARATOR$name" }

internal fun decodeCalendarHiddenNames(entries: Set<String>?): Map<String, String> =
    entries.orEmpty().mapNotNull { entry ->
        val key = entry.substringBefore(NAME_SEPARATOR, "")
        val name = entry.substringAfter(NAME_SEPARATOR, "")
        if (key.isEmpty() || name.isBlank()) null else key to name
    }.toMap()

fun normalizeCalendarWindowDays(days: Int?): Int =
    days?.takeIf { it in CALENDAR_WINDOW_OPTIONS } ?: CALENDAR_DEFAULT_WINDOW_DAYS

fun normalizeCalendarZoneOverride(zone: String?): String? =
    zone?.trim()?.takeIf { it.isNotEmpty() && runCatching { ZoneId.of(it) }.isSuccess }

internal fun Preferences.toCalendarSettings() = CalendarSettings(
    menuVisible = this[CalendarSettingsKeys.menuVisible] ?: true,
    includePersonalLists = this[CalendarSettingsKeys.includePersonalLists] ?: false,
    windowDays = normalizeCalendarWindowDays(this[CalendarSettingsKeys.windowDays]),
    zoneOverride = normalizeCalendarZoneOverride(this[CalendarSettingsKeys.zoneOverride]),
    cornerMessage = this[CalendarSettingsKeys.cornerMessage] ?: true,
    comingUpRow = this[CalendarSettingsKeys.comingUpRow] ?: false,
    hiddenTitleKeys = this[CalendarSettingsKeys.hiddenTitleKeys].orEmpty(),
    bellOn = this[CalendarSettingsKeys.bellOn].orEmpty(),
    hiddenTitleNames = decodeCalendarHiddenNames(this[CalendarSettingsKeys.hiddenTitleNames])
        .filterKeys { it in this[CalendarSettingsKeys.hiddenTitleKeys].orEmpty() }
)

/** Turning a title off also removes keys that name the same title by another of its ids. */
internal fun Set<String>.withTitleKey(titleKey: String, present: Boolean): Set<String> {
    if (present) return this + titleKey
    val (type, ids) = parseCalendarTitleKey(titleKey) ?: return this - titleKey
    return filterNotTo(linkedSetOf()) { it == titleKey || calendarKeyMatches(it, type, ids) }
}

internal fun MutablePreferences.toggleInSet(key: Preferences.Key<Set<String>>, value: String, present: Boolean) {
    this[key] = this[key].orEmpty().withTitleKey(value, present)
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun store(profileId: Int = profileManager.activeProfileId.value) = factory.get(profileId, FEATURE)

    /** Null until the active profile's settings have been read, so hidden titles and the zone are never skipped. */
    val settings: StateFlow<CalendarSettings?> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { it.toCalendarSettings().copy(profileId = profileId) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun current(profileId: Int = profileManager.activeProfileId.value): CalendarSettings =
        store(profileId).data.first().toCalendarSettings().copy(profileId = profileId)

    suspend fun setMenuVisible(visible: Boolean) = store().edit { it[CalendarSettingsKeys.menuVisible] = visible }

    suspend fun setIncludePersonalLists(include: Boolean) =
        store().edit { it[CalendarSettingsKeys.includePersonalLists] = include }

    suspend fun setWindowDays(days: Int) =
        store().edit { it[CalendarSettingsKeys.windowDays] = normalizeCalendarWindowDays(days) }

    suspend fun setZoneOverride(zone: String?) = store().edit { preferences ->
        val normalized = normalizeCalendarZoneOverride(zone)
        if (normalized == null) preferences.remove(CalendarSettingsKeys.zoneOverride)
        else preferences[CalendarSettingsKeys.zoneOverride] = normalized
    }

    suspend fun setCornerMessage(enabled: Boolean) = store().edit { it[CalendarSettingsKeys.cornerMessage] = enabled }

    suspend fun setComingUpRow(enabled: Boolean) = store().edit { it[CalendarSettingsKeys.comingUpRow] = enabled }

    suspend fun setTitleHidden(titleKey: String, hidden: Boolean, name: String? = null) = store().edit { preferences ->
        preferences.toggleInSet(CalendarSettingsKeys.hiddenTitleKeys, titleKey, hidden)
        val keys = preferences[CalendarSettingsKeys.hiddenTitleKeys].orEmpty()
        val names = decodeCalendarHiddenNames(preferences[CalendarSettingsKeys.hiddenTitleNames]).filterKeys { it in keys }
            .let { current -> if (hidden && !name.isNullOrBlank()) current + (titleKey to name.trim()) else current }
        preferences[CalendarSettingsKeys.hiddenTitleNames] = encodeCalendarHiddenNames(names)
    }

    suspend fun setBell(titleKey: String, on: Boolean) =
        store().edit { it.toggleInSet(CalendarSettingsKeys.bellOn, titleKey, on) }

    private companion object {
        const val FEATURE = "calendar_settings"
    }
}
