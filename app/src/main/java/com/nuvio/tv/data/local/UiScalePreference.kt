package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.uiScaleDataStore by preferencesDataStore(name = "ui_scale_prefs")

/**
 * App-wide UI scale (percent). Standalone store so the value is readable at the
 * composition root before the main settings graph is up. See upstream #2901.
 */
object UiScalePreference {
    private val key = intPreferencesKey("ui_scale_percent")

    /** Null distinguishes a new install from an explicitly saved 100 percent. */
    fun storedPercent(context: Context): Flow<Int?> =
        context.uiScaleDataStore.data.map { it[key] }

    fun flow(context: Context): Flow<Int> =
        context.uiScaleDataStore.data.map { prefs -> (prefs[key] ?: 100).coerceIn(85, 115) }

    suspend fun set(context: Context, percent: Int) {
        context.uiScaleDataStore.edit { prefs -> prefs[key] = percent.coerceIn(85, 115) }
    }
}
