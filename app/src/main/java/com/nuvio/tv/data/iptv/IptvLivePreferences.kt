package com.nuvio.tv.data.iptv

import android.content.Context
import com.nuvio.tv.core.iptv.LivePreferenceKeys
import com.nuvio.tv.core.iptv.MultiviewLayout
import com.nuvio.tv.core.iptv.MultiviewQuality

enum class IptvStartView { LAST, ALL, FAVOURITES, SPORT }

class IptvLivePreferences(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("iptv-live", Context.MODE_PRIVATE)

    fun key(ref: IptvSourceRef, name: String) = LivePreferenceKeys.source(ref.profileId, ref.sourceId, name)

    var defaultFormat: IptvStreamFormat
        get() = enumValue(FORMAT_KEY, IptvStreamFormat.AUTO)
        set(value) = putString(FORMAT_KEY, value.name)
    var timeshift: Boolean
        get() = preferences.getBoolean(TIMESHIFT_KEY, true)
        set(value) = preferences.edit().putBoolean(TIMESHIFT_KEY, value).apply()
    var showStats: Boolean
        get() = preferences.getBoolean(STATS_KEY, false)
        set(value) = preferences.edit().putBoolean(STATS_KEY, value).apply()
    var startView: IptvStartView
        get() = enumValue(START_KEY, IptvStartView.LAST)
        set(value) = putString(START_KEY, value.name)
    var sport: Boolean
        get() = preferences.getBoolean(SPORT_KEY, true)
        set(value) = preferences.edit().putBoolean(SPORT_KEY, value).apply()
    var multiviewLayout: MultiviewLayout
        get() = enumValue(LAYOUT_KEY, MultiviewLayout.GRID)
        set(value) = putString(LAYOUT_KEY, value.name)
    var multiviewQuality: MultiviewQuality
        get() = enumValue(QUALITY_KEY, MultiviewQuality.AUTO)
        set(value) = putString(QUALITY_KEY, value.name)
    var recordEarlyMinutes: Int
        get() = preferences.getInt(EARLY_KEY, 1).coerceIn(0, MAX_EARLY_MINUTES)
        set(value) = preferences.edit().putInt(EARLY_KEY, value.coerceIn(0, MAX_EARLY_MINUTES)).apply()
    var recordLateMinutes: Int
        get() = preferences.getInt(LATE_KEY, 2).coerceIn(0, MAX_LATE_MINUTES)
        set(value) = preferences.edit().putInt(LATE_KEY, value.coerceIn(0, MAX_LATE_MINUTES)).apply()

    fun hiddenCategoryCount(profileId: Int): Int =
        LivePreferenceKeys.hiddenOfProfile(preferences.all.keys, profileId).sumOf { preferences.getStringSet(it, null)?.size ?: 0 }

    fun unhideCategories(profileId: Int) = remove(LivePreferenceKeys.hiddenOfProfile(preferences.all.keys, profileId))

    fun removeSource(ref: IptvSourceRef) = remove(LivePreferenceKeys.ofSource(preferences.all.keys, ref.profileId, ref.sourceId))

    fun removeProfile(profileId: Int) = remove(LivePreferenceKeys.ofProfile(preferences.all.keys, profileId))

    fun clearAllProfiles() = remove(LivePreferenceKeys.ofAllProfiles(preferences.all.keys))

    private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T =
        preferences.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private fun putString(key: String, value: String) = preferences.edit().putString(key, value).apply()

    private fun remove(keys: List<String>) {
        if (keys.isEmpty()) return
        preferences.edit().apply { keys.forEach(::remove) }.commit()
    }

    companion object {
        const val MAX_EARLY_MINUTES = 10
        const val MAX_LATE_MINUTES = 30
        private const val FORMAT_KEY = "settings-format"
        private const val TIMESHIFT_KEY = "settings-timeshift"
        private const val STATS_KEY = "settings-stats"
        private const val START_KEY = "settings-start"
        private const val SPORT_KEY = "settings-sport"
        private const val EARLY_KEY = "settings-record-early"
        private const val LATE_KEY = "settings-record-late"
        private const val LAYOUT_KEY = "multiview-layout"
        private const val QUALITY_KEY = "multiview-quality"
    }
}
