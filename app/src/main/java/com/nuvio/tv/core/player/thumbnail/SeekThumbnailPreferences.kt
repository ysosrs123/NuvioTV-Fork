package com.nuvio.tv.core.player.thumbnail

import androidx.datastore.core.DataStore
import android.app.ActivityManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataMigration
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

// Device-wide (the thumbnail store is per device), so not part of the per-profile player settings.
private val Context.seekThumbnailDataStore by preferencesDataStore(
    name = "seek_thumbnails",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = {
        listOf(SeekThumbnailPreferences.beforePlayRemoval)
    }
)

/** When 4K thumbnails are made is not a user choice; the engine decides per device and free memory. */
enum class SeekThumbMode(@StringRes val label: Int, @StringRes val description: Int) {
    OFF(R.string.seek_thumbnails_mode_off, R.string.seek_thumbnails_mode_off_desc),
    /** No longer offered; a stored value reads as [ALL]. */
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

    /**
     * Thumbnails start on only on boxes with 3 GB of RAM or more. Android reports less than the nominal size, 3 GB boxes
     * from about 2.7 GiB, 2 GB boxes below 2 GiB.
     */
    internal const val DEFAULT_ON_MIN_TOTAL_MB = 2_600L

    /** The mode of an install that never chose one. [totalMemBytes] <= 0: not known. */
    internal fun defaultMode(totalMemBytes: Long): SeekThumbMode =
        if (totalMemBytes in 1 until DEFAULT_ON_MIN_TOTAL_MB * 1024 * 1024) SeekThumbMode.OFF else SeekThumbMode.ALL

    @Volatile private var totalMemBytes = -1L

    private fun defaultMode(context: Context): SeekThumbMode {
        if (totalMemBytes < 0) {
            val info = ActivityManager.MemoryInfo()
            val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.getMemoryInfo(info)
            totalMemBytes = info.totalMem
        }
        return defaultMode(totalMemBytes)
    }

    /** A stored choice (or an older key's) always wins; only an install without one gets [default]. */
    internal fun modeOf(prefs: Preferences, default: SeekThumbMode = SeekThumbMode.ALL): SeekThumbMode {
        prefs[modeKey]?.let { v ->
            return when (SeekThumbMode.entries.firstOrNull { it.name == v }) {
                null, SeekThumbMode.OFF -> SeekThumbMode.OFF
                else -> SeekThumbMode.ALL
            }
        }
        return when (prefs[legacyEnabledKey]) {
            null -> default
            false -> SeekThumbMode.OFF
            else -> SeekThumbMode.ALL
        }
    }

    /** A store that cannot be read counts as Off, with no copies. */
    private fun <T> Flow<Preferences>.readOr(fallback: T, read: (Preferences) -> T): Flow<T> =
        map(read).catch { if (it is IOException) emit(fallback) else throw it }

    internal fun modeFlow(data: Flow<Preferences>, default: SeekThumbMode = SeekThumbMode.ALL): Flow<SeekThumbMode> =
        data.readOr(SeekThumbMode.OFF) { modeOf(it, default) }

    fun modeFlow(context: Context): Flow<SeekThumbMode> =
        modeFlow(context.applicationContext.seekThumbnailDataStore.data, defaultMode(context))

    /** True unless the mode is Off (the player starts a session only then). */
    fun enabledFlow(context: Context): Flow<Boolean> = modeFlow(context).map { it != SeekThumbMode.OFF }

    suspend fun mode(context: Context): SeekThumbMode = modeFlow(context).first()

    private val prepareKey = stringPreferencesKey("seek_thumbnails_prepare")

    internal fun prepareModeFlow(data: Flow<Preferences>): Flow<ThumbPrepareMode> =
        data.readOr(ThumbPrepareMode.OFF) { prefs ->
            (prefs[prepareKey]?.let { v -> ThumbPrepareMode.entries.firstOrNull { it.name == v } } ?: ThumbPrepareMode.OFF)
                .let { if (it == ThumbPrepareMode.ASK) ThumbPrepareMode.ALWAYS else it }
        }

    fun prepareModeFlow(context: Context): Flow<ThumbPrepareMode> =
        prepareModeFlow(context.applicationContext.seekThumbnailDataStore.data)

    suspend fun prepareMode(context: Context): ThumbPrepareMode = prepareModeFlow(context).first()

    suspend fun setPrepareMode(context: Context, mode: ThumbPrepareMode) {
        context.applicationContext.seekThumbnailDataStore.edit { prefs -> prefs[prepareKey] = mode.name }
    }

    private val hardwareKey = booleanPreferencesKey("seek_thumbnails_hardware")

    /** Thumbnails from the box's hardware decoder where it can make them; on until switched off. */
    internal fun hardwareFlow(data: Flow<Preferences>): Flow<Boolean> = data.readOr(true) { it[hardwareKey] ?: true }

    fun hardwareFlow(context: Context): Flow<Boolean> = hardwareFlow(context.applicationContext.seekThumbnailDataStore.data)

    suspend fun hardwareEnabled(context: Context): Boolean = hardwareFlow(context).first()

    suspend fun setHardware(context: Context, on: Boolean) {
        context.applicationContext.seekThumbnailDataStore.edit { prefs -> prefs[hardwareKey] = on }
    }

    private val beforePlayKey = stringPreferencesKey("seek_thumbnails_before_play")

    /** The Full / Faster / Fastest choice is gone (everyone gets what Faster did), so a stored one is dropped. */
    internal val beforePlayRemoval = object : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences): Boolean = beforePlayKey in currentData

        override suspend fun migrate(currentData: Preferences): Preferences =
            currentData.toMutablePreferences().apply { remove(beforePlayKey) }.toPreferences()

        override suspend fun cleanUp() = Unit
    }

    suspend fun setMode(context: Context, mode: SeekThumbMode) {
        context.applicationContext.seekThumbnailDataStore.edit { prefs ->
            prefs[modeKey] = mode.name
            prefs.remove(legacyEnabledKey)
            prefs.remove(legacyFourKKey)
        }
    }
}

internal fun Context.backupSeekThumbnailStore(): DataStore<Preferences> = seekThumbnailDataStore
