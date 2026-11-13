package com.nuvio.tv.core.backup

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.player.thumbnail.backupSeekThumbnailStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import com.nuvio.tv.core.sync.ProfileSettingsSyncStateStore
import com.nuvio.tv.core.torrent.backupTorrentStore
import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.backupAppearanceStore
import com.nuvio.tv.data.local.backupDeviceUiStore
import com.nuvio.tv.data.local.backupOnboardingStore
import com.nuvio.tv.data.local.backupProfilesStore
import com.nuvio.tv.data.local.backupUiScaleStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Reads every store in [BackupCatalog] into a backup, and writes a backup's chosen parts back. */
@Singleton
class BackupStores @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val authManager: AuthManager,
    private val deviceLocalPlayerPreferences: DeviceLocalPlayerPreferences,
    private val syncStateStore: ProfileSettingsSyncStateStore
) {
    suspend fun collect(): Map<BackupPart, List<BackupEntry>> {
        val profileIds = profileManager.profiles.value.map { it.id }.ifEmpty { listOf(1) }
        val result = linkedMapOf<BackupPart, MutableList<BackupEntry>>()
        fun add(part: BackupPart, entry: BackupEntry) { result.getOrPut(part) { mutableListOf() } += entry }

        BackupCatalog.stores.forEach { spec ->
            when (spec.kind) {
                BackupStoreKind.PROFILE_STORE -> profileIds.forEach { profileId ->
                    if (!dataStoreFileExists(BackupCatalog.profileStoreFileName(spec.name, profileId))) return@forEach
                    val values = factory.get(profileId, spec.name).data.first().toBackupValues()
                    BackupCatalog.split(spec, values).forEach { (part, partValues) ->
                        val kept = if (part == BackupPart.HISTORY) BackupHistory.scrub(partValues) else partValues
                        add(part, BackupEntry(spec.kind, spec.name, profileId, kept))
                    }
                }
                BackupStoreKind.BOX_STORE -> {
                    val store = boxStore(spec.name) ?: return@forEach
                    if (!dataStoreFileExists(spec.name)) return@forEach
                    BackupCatalog.split(spec, store.data.first().toBackupValues()).forEach { (part, partValues) ->
                        add(part, BackupEntry(spec.kind, spec.name, null, partValues))
                    }
                }
                BackupStoreKind.PREFS -> {
                    val values = context.getSharedPreferences(spec.name, Context.MODE_PRIVATE).all
                        .mapNotNull { (key, raw) -> BackupValue.of(raw)?.let { key to it } }.toMap()
                    BackupCatalog.split(spec, values).forEach { (part, partValues) ->
                        add(part, BackupEntry(spec.kind, spec.name, null, partValues))
                    }
                }
                BackupStoreKind.PROTECTED -> {
                    val alias = spec.keyAlias ?: return@forEach
                    val raw = context.getSharedPreferences(spec.name, Context.MODE_PRIVATE)
                    val protected = ProtectedProfilePreferences(context, spec.name, alias)
                    raw.all.keys.mapNotNull { it.removePrefix("profile.").toIntOrNull() }.sorted().forEach { profileId ->
                        val text = runCatching { protected.read(profileId) }.getOrNull() ?: return@forEach
                        add(spec.part, BackupEntry(spec.kind, spec.name, profileId, text = text))
                    }
                }
                BackupStoreKind.FILE -> profileIds.forEach { profileId ->
                    val file = profileFile(spec, profileId) ?: return@forEach
                    if (!file.isFile) return@forEach
                    val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                    add(spec.part, BackupEntry(spec.kind, spec.name, profileId, text = text))
                }
                BackupStoreKind.SECTION -> Unit
            }
        }
        return result
    }

    fun header(
        parts: Set<BackupPart>,
        nowMs: Long = System.currentTimeMillis(),
        sections: Map<String, Boolean> = emptyMap()
    ) = BackupHeader(
        createdAtMs = nowMs,
        appVersion = BuildConfig.VERSION_NAME,
        device = currentDevice(),
        profileNames = profileManager.profiles.value.map { it.name },
        parts = parts,
        sections = sections
    )

    val isSignedIn: Boolean get() = authManager.isAuthenticated

    fun currentDevice() = BackupDevice(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), Build.VERSION.SDK_INT)

    /**
     * Writes [entries] for the chosen [parts]. Keys of a part the backup does not have are removed, so the
     * part ends up exactly as saved; keys of other parts stay as they are.
     */
    suspend fun apply(entries: Map<BackupPart, List<BackupEntry>>, parts: Set<BackupPart>): BackupApplyResult {
        val signedIn = authManager.isAuthenticated
        val restoredProfiles = mutableSetOf<Int>()
        var written = 0
        var failed = 0
        parts.forEach { part ->
            if (part == BackupPart.HISTORY && signedIn) return@forEach
            entries[part].orEmpty().forEach { entry ->
                try {
                    if (write(part, entry, signedIn, restoredProfiles)) written++
                } catch (error: Exception) {
                    failed++
                }
            }
        }
        if (!signedIn && BackupPart.HISTORY in parts) {
            BackupHistory.storesToEmpty(entries[BackupPart.HISTORY].orEmpty()).forEach { (name, profileId) ->
                val spec = BackupCatalog.spec(BackupStoreKind.PROFILE_STORE, name) ?: return@forEach
                if (!dataStoreFileExists(BackupCatalog.profileStoreFileName(name, profileId))) return@forEach
                try {
                    factory.get(profileId, name).edit { prefs ->
                        prefs.replacePart(spec, BackupPart.HISTORY, emptyMap())
                        spec.resetKeys.forEach { prefs.remove(stringPreferencesKey(it)) }
                    }
                } catch (error: Exception) {
                    failed++
                }
            }
        }
        if (signedIn && (BackupPart.SETTINGS in parts || BackupPart.SECRETS in parts)) {
            val userId = authManager.currentUserId
            restoredProfiles.forEach { profileId -> runCatching { syncStateStore.markPending(profileId, userId) } }
        }
        return BackupApplyResult(written, failed)
    }

    private suspend fun write(part: BackupPart, entry: BackupEntry, signedIn: Boolean, restoredProfiles: MutableSet<Int>): Boolean {
        val spec = BackupCatalog.spec(entry.kind, entry.store) ?: return false
        when (entry.kind) {
            BackupStoreKind.PROFILE_STORE -> {
                val profileId = entry.profileId ?: return false
                factory.get(profileId, spec.name).edit { prefs ->
                    prefs.replacePart(spec, part, entry.values)
                    if (part == spec.part) spec.resetKeys.forEach { prefs.remove(stringPreferencesKey(it)) }
                }
                if (part != BackupPart.HISTORY) restoredProfiles += profileId
            }
            BackupStoreKind.BOX_STORE -> {
                val values = if (signedIn && spec.name == "profile_settings") {
                    entry.values - "profiles_json"
                } else entry.values
                if (part == BackupPart.PROFILES && values.isEmpty()) return false
                val store = boxStore(spec.name) ?: return false
                store.edit { prefs -> prefs.replacePart(spec, part, values) }
            }
            BackupStoreKind.PREFS -> {
                val prefs = context.getSharedPreferences(spec.name, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                prefs.all.keys.filter { BackupCatalog.partFor(spec, it) == part && it !in entry.values }
                    .forEach(editor::remove)
                entry.values.forEach { (key, value) ->
                    when (value) {
                        is BackupValue.Text -> editor.putString(key, value.value)
                        is BackupValue.Flag -> editor.putBoolean(key, value.value)
                        is BackupValue.Whole -> editor.putInt(key, value.value)
                        is BackupValue.Big -> editor.putLong(key, value.value)
                        is BackupValue.Real -> editor.putFloat(key, value.value)
                        is BackupValue.Precise -> editor.putFloat(key, value.value.toFloat())
                        is BackupValue.Texts -> editor.putStringSet(key, value.value)
                    }
                }
                if (!editor.commit()) throw IOException("could not save ${spec.name}")
            }
            BackupStoreKind.PROTECTED -> {
                val profileId = entry.profileId ?: return false
                val alias = spec.keyAlias ?: return false
                ProtectedProfilePreferences(context, spec.name, alias).write(profileId, entry.text)
            }
            BackupStoreKind.FILE -> {
                val profileId = entry.profileId ?: return false
                val file = profileFile(spec, profileId) ?: return false
                val text = entry.text ?: return false
                BackupRestoreChecks.writeWhole(file, text)
            }
            BackupStoreKind.SECTION -> return false
        }
        return true
    }

    private fun boxStore(name: String): DataStore<Preferences>? = when (name) {
        "profile_settings" -> context.backupProfilesStore()
        "app_onboarding" -> context.backupOnboardingStore()
        "appearance_v2" -> context.backupAppearanceStore()
        "device_ui_preferences_v2" -> context.backupDeviceUiStore()
        "seek_thumbnails" -> context.backupSeekThumbnailStore()
        "torrent_settings" -> context.backupTorrentStore()
        "ui_scale_prefs" -> context.backupUiScaleStore()
        "device_local_player_prefs" -> deviceLocalPlayerPreferences.backupStore
        else -> null
    }

    private fun dataStoreFileExists(fileName: String): Boolean =
        File(File(context.filesDir, "datastore"), "$fileName.preferences_pb").isFile

    private fun profileFile(spec: BackupStoreSpec, profileId: Int): File? =
        spec.filePrefix?.let { File(context.filesDir, "${it}_$profileId.json") }
}

internal fun Preferences.toBackupValues(): Map<String, BackupValue> =
    asMap().mapNotNull { (key, raw) -> BackupValue.of(raw)?.let { key.name to it } }.toMap()

internal fun MutablePreferences.replacePart(spec: BackupStoreSpec, part: BackupPart, values: Map<String, BackupValue>) {
    asMap().keys.filter { BackupCatalog.partFor(spec, it.name) == part && it.name !in values }.forEach { remove(it) }
    values.forEach { (key, value) ->
        if (BackupCatalog.partFor(spec, key) != part) return@forEach
        when (value) {
            is BackupValue.Text -> this[stringPreferencesKey(key)] = value.value
            is BackupValue.Flag -> this[booleanPreferencesKey(key)] = value.value
            is BackupValue.Whole -> this[intPreferencesKey(key)] = value.value
            is BackupValue.Big -> this[longPreferencesKey(key)] = value.value
            is BackupValue.Real -> this[floatPreferencesKey(key)] = value.value
            is BackupValue.Precise -> this[doublePreferencesKey(key)] = value.value
            is BackupValue.Texts -> this[stringSetPreferencesKey(key)] = value.value
        }
    }
}
