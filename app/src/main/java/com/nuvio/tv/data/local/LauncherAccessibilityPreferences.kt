package com.nuvio.tv.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Device setting: leave the screen description empty while only launchers' accessibility services are on. */
class LauncherAccessibilityPreferences @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val quietForLaunchers: Boolean
        get() = preferences.getBoolean(KEY_QUIET_FOR_LAUNCHERS, true)

    fun setQuietForLaunchers(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_QUIET_FOR_LAUNCHERS, enabled).apply()
    }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.registerOnSharedPreferenceChangeListener(listener)

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        private const val PREFERENCES_NAME = "launcher_accessibility"
        const val KEY_QUIET_FOR_LAUNCHERS = "quiet_for_launchers"
    }
}
