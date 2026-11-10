package com.nuvio.tv.updater

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import kotlinx.coroutines.flow.catch
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.MutablePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "update_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { ex ->
        android.util.Log.e("UpdatePreferences", "DataStore corrupted: ${ex.message} — resetting to empty")
        emptyPreferences()
    }
)

@Singleton
class UpdatePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.updateDataStore

    private val preferences = dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    private val ignoredTagKey = stringPreferencesKey("ignored_release_tag")
    private val lastCheckAtKey = longPreferencesKey("last_check_at_ms")
    private val updateBannerEnabledKey = booleanPreferencesKey("update_banner_enabled")
    private val autoInstallEnabledKey = booleanPreferencesKey("auto_install_enabled")
    private val downloadedTagKey = stringPreferencesKey("downloaded_release_tag")
    private val downloadedApkPathKey = stringPreferencesKey("downloaded_apk_path")
    private val downloadedCodeKey = longPreferencesKey("downloaded_release_code")
    private val autoInstalledTagKey = stringPreferencesKey("auto_installed_release_tag")
    private val autoInstalledCodeKey = longPreferencesKey("auto_installed_release_code")
    private val autoInstallNoticeEnabledKey = booleanPreferencesKey("auto_install_notice_enabled")

    val ignoredTag: Flow<String?> = preferences.map { prefs ->
        prefs[ignoredTagKey]
    }

    val lastCheckAtMs: Flow<Long> = preferences.map { prefs ->
        prefs[lastCheckAtKey] ?: 0L
    }

    val updateBannerEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[updateBannerEnabledKey] ?: true
    }

    // Old stable/beta values are obsolete. Keep banner and skipped-tag preferences.
    companion object {
        internal fun migrateForkStream(prefs: MutablePreferences) {
            prefs.remove(stringPreferencesKey("update_channel"))
        }
    }

    val autoInstallEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[autoInstallEnabledKey] ?: false
    }

    /** The update already downloaded, if any, so it survives the app being closed. */
    val downloadedUpdate: Flow<DownloadedUpdate?> = preferences.map { prefs ->
        val tag = prefs[downloadedTagKey]
        val path = prefs[downloadedApkPathKey]
        if (tag != null && path != null) DownloadedUpdate(tag, path, prefs[downloadedCodeKey]) else null
    }

    /** Release the idle job last installed without asking, so the next launch can say the app was updated. */
    val autoInstalledTag: Flow<String?> = preferences.map { prefs ->
        prefs[autoInstalledTagKey]
    }

    val autoInstalledCode: Flow<Long?> = preferences.map { prefs ->
        prefs[autoInstalledCodeKey]
    }

    val autoInstallNoticeEnabled: Flow<Boolean> = preferences.map { prefs ->
        prefs[autoInstallNoticeEnabledKey] ?: true
    }

    suspend fun initializeForkStream() {
        dataStore.edit(::migrateForkStream)
    }

    suspend fun setIgnoredTag(tag: String?) {
        dataStore.edit { prefs ->
            if (tag == null) prefs.remove(ignoredTagKey) else prefs[ignoredTagKey] = tag
        }
    }

    suspend fun setLastCheckAtMs(value: Long) {
        dataStore.edit { prefs ->
            prefs[lastCheckAtKey] = value
        }
    }

    suspend fun setUpdateBannerEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[updateBannerEnabledKey] = enabled
        }
    }

    suspend fun setAutoInstallEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[autoInstallEnabledKey] = enabled
        }
    }

    suspend fun setDownloadedUpdate(tag: String?, apkPath: String?, versionCode: Long? = null) {
        dataStore.edit { prefs ->
            prefs.remove(downloadedCodeKey)
            if (tag == null || apkPath == null) {
                prefs.remove(downloadedTagKey)
                prefs.remove(downloadedApkPathKey)
            } else {
                prefs[downloadedTagKey] = tag
                prefs[downloadedApkPathKey] = apkPath
                if (versionCode != null) prefs[downloadedCodeKey] = versionCode
            }
        }
    }

    suspend fun setAutoInstallNoticeEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[autoInstallNoticeEnabledKey] = enabled
        }
    }

    suspend fun setAutoInstalledTag(tag: String?, versionCode: Long? = null) {
        dataStore.edit { prefs ->
            prefs.remove(autoInstalledCodeKey)
            if (tag == null) prefs.remove(autoInstalledTagKey) else prefs[autoInstalledTagKey] = tag
            if (tag != null && versionCode != null) prefs[autoInstalledCodeKey] = versionCode
        }
    }

}
