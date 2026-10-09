package com.nuvio.tv.data.iptv

import android.content.Context
import com.nuvio.tv.core.iptv.CategoryOrder
import com.nuvio.tv.core.iptv.GuideDensity
import com.nuvio.tv.core.iptv.LivePreferenceKeys
import com.nuvio.tv.core.iptv.LiveUserAgent
import com.nuvio.tv.core.iptv.MultiviewLayout
import com.nuvio.tv.core.iptv.MultiviewQuality
import com.nuvio.tv.core.iptv.RecordingLocations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class IptvStartView { LAST, ALL, FAVOURITES, SPORT }

data class IptvAppearance(val theme: String? = null, val black: Boolean = false, val solidPanels: Boolean = false, val plainBackground: Boolean = true)

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

    var recordLocation: String
        get() = RecordingLocations.choice(preferences.getString(LOCATION_KEY, null))
        set(value) = putString(LOCATION_KEY, value)
    var shareFreeBytes: Long?
        get() = preferences.getLong(SHARE_FREE_KEY, -1).takeIf { it >= 0 }
        set(value) = preferences.edit().putLong(SHARE_FREE_KEY, value ?: -1).apply()

    var guideDensity: GuideDensity
        get() = enumValue(DENSITY_KEY, GuideDensity.COMPACT)
        set(value) = putString(DENSITY_KEY, value.name)

    fun categoryOrder(ref: IptvSourceRef): List<String> = preferences.getString(key(ref, CATEGORY_ORDER), null)?.let { saved ->
        runCatching { org.json.JSONArray(saved).let { array -> (0 until minOf(array.length(), CategoryOrder.MAX_SAVED)).map(array::getString) } }.getOrNull()
    }.orEmpty()

    fun setCategoryOrder(ref: IptvSourceRef, names: List<String>) = putString(key(ref, CATEGORY_ORDER), org.json.JSONArray(names.take(CategoryOrder.MAX_SAVED)).toString())

    var autoPreview: Boolean
        get() = preferences.getBoolean(PREVIEW_KEY, true)
        set(value) = preferences.edit().putBoolean(PREVIEW_KEY, value).apply()

    val currentAppearance: IptvAppearance
        get() = IptvAppearance(preferences.getString(THEME_KEY, null)?.takeIf { it.length <= 40 },
            preferences.getBoolean(BLACK_KEY, false), preferences.getBoolean(SOLID_KEY, false), preferences.getBoolean(PLAIN_KEY, true))

    fun updateAppearance(change: (IptvAppearance) -> IptvAppearance) {
        val next = change(currentAppearance)
        preferences.edit().putString(THEME_KEY, next.theme).putBoolean(BLACK_KEY, next.black).putBoolean(SOLID_KEY, next.solidPanels)
            .putBoolean(PLAIN_KEY, next.plainBackground).apply()
        state(this).value = next
    }

    fun boost(ref: IptvSourceRef, channelId: String): Int = preferences.getInt(key(ref, BOOST_PREFIX + channelId), 0).coerceIn(0, MAX_BOOST_DB)

    fun setBoost(ref: IptvSourceRef, channelId: String, db: Int) = preferences.edit().apply {
        if (db <= 0) remove(key(ref, BOOST_PREFIX + channelId)) else putInt(key(ref, BOOST_PREFIX + channelId), db.coerceAtMost(MAX_BOOST_DB))
    }.apply()

    fun connectionsManual(ref: IptvSourceRef): Boolean = preferences.getBoolean(key(ref, MANUAL_CONNECTIONS), false)

    fun setConnectionsManual(ref: IptvSourceRef, manual: Boolean) = preferences.edit().putBoolean(key(ref, MANUAL_CONNECTIONS), manual).apply()

    fun providerConnections(ref: IptvSourceRef): Int? = key(ref, PROVIDER_CONNECTIONS).let { if (preferences.contains(it)) preferences.getInt(it, 0).coerceAtLeast(0) else null }

    fun setProviderConnections(ref: IptvSourceRef, reported: Int) = preferences.edit().putInt(key(ref, PROVIDER_CONNECTIONS), reported.coerceAtLeast(0)).apply()

    fun userAgentChoice(ref: IptvSourceRef): String? = preferences.getString(key(ref, USER_AGENT), null)

    fun userAgent(ref: IptvSourceRef): String? = LiveUserAgent.resolve(userAgentChoice(ref))

    fun setUserAgent(ref: IptvSourceRef, choice: String?) = preferences.edit().apply {
        if (choice == null || LiveUserAgent.kind(choice) == LiveUserAgent.DEFAULT) remove(key(ref, USER_AGENT)) else putString(key(ref, USER_AGENT), choice)
    }.apply()

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
        private var appearanceState: MutableStateFlow<IptvAppearance>? = null

        @Synchronized private fun state(preferences: IptvLivePreferences): MutableStateFlow<IptvAppearance> =
            appearanceState ?: MutableStateFlow(preferences.currentAppearance).also { appearanceState = it }

        fun appearance(context: Context): StateFlow<IptvAppearance> = state(IptvLivePreferences(context))

        const val MAX_BOOST_DB = 12
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
        private const val MANUAL_CONNECTIONS = "connections-manual"
        private const val PROVIDER_CONNECTIONS = "provider-connections"
        private const val PREVIEW_KEY = "settings-preview"
        private const val LOCATION_KEY = "settings-record-location"
        private const val SHARE_FREE_KEY = "settings-record-share-free"
        private const val THEME_KEY = "settings-theme"
        private const val BLACK_KEY = "settings-black"
        private const val SOLID_KEY = "settings-solid"
        private const val PLAIN_KEY = "settings-plain"
        private const val BOOST_PREFIX = "boost-"
        private const val DENSITY_KEY = "settings-density"
        private const val CATEGORY_ORDER = "category-order"
        private const val USER_AGENT = "user-agent"
    }
}
