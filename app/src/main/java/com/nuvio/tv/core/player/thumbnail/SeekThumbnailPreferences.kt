package com.nuvio.tv.core.player.thumbnail

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Device-wide (the thumbnail store is per device), so not part of the per-profile player settings.
private val Context.seekThumbnailDataStore by preferencesDataStore(name = "seek_thumbnails")

/** When 4K thumbnails are made is not a user choice; the engine decides per device and free memory. */
enum class SeekThumbMode(@StringRes val label: Int, @StringRes val description: Int) {
    OFF(R.string.seek_thumbnails_mode_off, R.string.seek_thumbnails_mode_off_desc),
    /** Every resolution up to 1080p. */
    HD(R.string.seek_thumbnails_mode_hd, R.string.seek_thumbnails_mode_hd_desc),
    ALL(R.string.seek_thumbnails_mode_all, R.string.seek_thumbnails_mode_all_desc),
}

enum class ThumbPrepareMode(@StringRes val label: Int, @StringRes val description: Int) {
    OFF(R.string.seek_thumbnails_prepare_off, R.string.seek_thumbnails_prepare_off_desc),
    /** No longer offered; a stored value reads as [ALWAYS]. */
    ASK(R.string.seek_thumbnails_prepare_on, R.string.seek_thumbnails_prepare_on_desc),
    ALWAYS(R.string.seek_thumbnails_prepare_on, R.string.seek_thumbnails_prepare_on_desc),
}

object SeekThumbnailPreferences {
    private val modeKey = stringPreferencesKey("seek_thumbnails_mode")
    // Older keys, read once to migrate.
    private val legacyEnabledKey = booleanPreferencesKey("seek_thumbnails_enabled")
    private val legacyFourKKey = stringPreferencesKey("seek_thumbnails_4k_mode")

    private fun modeOf(prefs: Preferences): SeekThumbMode {
        prefs[modeKey]?.let { v -> SeekThumbMode.entries.firstOrNull { it.name == v } }?.let { return it }
        return when {
            prefs[legacyEnabledKey] != true -> SeekThumbMode.OFF
            prefs[legacyFourKKey] == "OFF" -> SeekThumbMode.HD
            else -> SeekThumbMode.ALL
        }
    }

    fun modeFlow(context: Context): Flow<SeekThumbMode> =
        context.applicationContext.seekThumbnailDataStore.data.map(::modeOf)

    /** True unless the mode is Off (the player starts a session only then). */
    fun enabledFlow(context: Context): Flow<Boolean> = modeFlow(context).map { it != SeekThumbMode.OFF }

    suspend fun mode(context: Context): SeekThumbMode = modeFlow(context).first()

    private val prepareKey = stringPreferencesKey("seek_thumbnails_prepare")

    fun prepareModeFlow(context: Context): Flow<ThumbPrepareMode> =
        context.applicationContext.seekThumbnailDataStore.data.map { prefs ->
            (prefs[prepareKey]?.let { v -> ThumbPrepareMode.entries.firstOrNull { it.name == v } } ?: ThumbPrepareMode.OFF)
                .let { if (it == ThumbPrepareMode.ASK) ThumbPrepareMode.ALWAYS else it }
        }

    suspend fun prepareMode(context: Context): ThumbPrepareMode = prepareModeFlow(context).first()

    suspend fun setPrepareMode(context: Context, mode: ThumbPrepareMode) {
        context.applicationContext.seekThumbnailDataStore.edit { prefs -> prefs[prepareKey] = mode.name }
    }

    suspend fun setMode(context: Context, mode: SeekThumbMode) {
        context.applicationContext.seekThumbnailDataStore.edit { prefs ->
            prefs[modeKey] = mode.name
            prefs.remove(legacyEnabledKey)
            prefs.remove(legacyFourKKey)
        }
    }
}
