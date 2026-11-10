package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nuvio.tv.domain.model.DiscoverLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

private val retainedStandaloneDataStoreNames = setOf(
    "app_onboarding",
    "appearance_v2",
    "auth_session_notice_store",
    "debug_settings",
    "device_local_player_prefs",
    "device_ui_preferences_v2",
    "format_check_device",
    "profile_lock_state",
    "profile_settings",
    "seek_thumbnails",
    "torrent_settings",
    "ui_scale_prefs"
)

internal fun isProfileScopedDataStoreFile(fileName: String): Boolean {
    if (!fileName.endsWith(".preferences_pb")) return false
    val dataStoreName = fileName.removeSuffix(".preferences_pb")
    return dataStoreName !in retainedStandaloneDataStoreNames
}

private const val SHADOW_PART_SUFFIX = ".part"

private class ScopedDataStore(
    val store: DataStore<Preferences>,
    val scope: CoroutineScope,
    val job: Job
)

internal val discoverLocationMigration = object : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        legacySearchDiscoverEnabledKey in currentData

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mutable = currentData.toMutablePreferences()
        val legacy = mutable[legacySearchDiscoverEnabledKey]
        if (legacy != null && mutable[discoverLocationKey] == null) {
            val rememberedLocation = mutable[lastNonOffDiscoverLocationKey]?.let {
                runCatching { DiscoverLocation.valueOf(it) }.getOrNull()
            }?.takeIf { it != DiscoverLocation.OFF }
            val resolved = if (legacy && rememberedLocation != null) {
                rememberedLocation
            } else {
                DiscoverLocation.fromLegacySearchDiscoverEnabled(legacy)
            }
            mutable[discoverLocationKey] = resolved.name
        }
        mutable.remove(legacySearchDiscoverEnabledKey)
        return mutable.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

@Singleton
class ProfileDataStoreFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cache = ConcurrentHashMap<String, ScopedDataStore>()
    private val deletedProfileIds = ConcurrentHashMap.newKeySet<Int>()
    private val lock = Any()
    private val shadowLock = Any()
    @Volatile
    private var shadowGeneration = 0L
    private val lifecycleMutex = Mutex()
    private val historyGenerations = kotlinx.coroutines.flow.MutableStateFlow(0L)
    internal val historyGenerationChanges: kotlinx.coroutines.flow.StateFlow<Long> get() = historyGenerations
    private val generationOwnedFeatures = setOf("watched_items_preferences", WATCH_PROGRESS_METADATA_FEATURE, WATCH_PROGRESS_RECENT_FEATURE, WATCH_PROGRESS_ARCHIVE_FEATURE)
    private var resetting = false
    private val retiringProfiles = mutableSetOf<Int>()

    /** Set of DataStore file names that were reset due to corruption during this session. */
    val corruptedFileNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun get(profileId: Int, featureName: String): DataStore<Preferences> {
        val fileName = if (profileId == 1) featureName else "${featureName}_p$profileId"
        synchronized(lock) {
            if (profileId in deletedProfileIds || profileId in retiringProfiles) {
                throw CancellationException("Profile store unavailable during retirement")
            }
            cache[fileName]?.let {
                if (resetting && it.store is ProfileStoreLifetime) throw CancellationException("Profile store reset in progress")
                return it.store
            }
            if (resetting) throw CancellationException("Profile store reset in progress")
            return createAndCache(fileName).store
        }
    }

    suspend fun clearProfile(profileId: Int) {
        if (profileId == 1) return
        withContext(Dispatchers.IO + NonCancellable) {
            lifecycleMutex.withLock {
                val stores = synchronized(lock) {
                    deletedProfileIds.add(profileId)
                    retiringProfiles.add(profileId)
                    cache.filterKeys { it.endsWith("_p$profileId") }
                }
                try {
                    retireStores(stores)
                    File(context.filesDir, "datastore").listFiles()?.forEach { file ->
                        if (file.name.removeSuffix(SHADOW_PART_SUFFIX).removeSuffix(".bak").endsWith("_p$profileId.preferences_pb")) file.delete()
                    }
                } finally {
                    synchronized(lock) { retiringProfiles.remove(profileId); historyGenerations.value++ }
                }
            }
        }
    }

    suspend fun clearProfileScopedData() = withContext(Dispatchers.IO + NonCancellable) {
        lifecycleMutex.withLock {
            val stores = synchronized(lock) {
                resetting = true
                cache.toMap()
            }
            try {
                val owned = stores.filterValues { it.store is ProfileStoreLifetime }
                retireStores(owned)
                // Other settings stores intentionally remain live across sign-out.
                stores.filterValues { it.store !is ProfileStoreLifetime }.values.forEach { scoped ->
                    scoped.store.edit { it.clear() }
                }
                val retainedFiles = synchronized(lock) { cache.keys.mapTo(mutableSetOf()) { "$it.preferences_pb" } }
                val dataStoreDir = File(context.filesDir, "datastore")
                synchronized(shadowLock) {
                    shadowGeneration++
                    dataStoreDir.listFiles()?.forEach { file ->
                        val baseName = file.name.removeSuffix(SHADOW_PART_SUFFIX).removeSuffix(".bak")
                        val isShadowCopy = baseName != file.name
                        if (isProfileScopedDataStoreFile(baseName) && (isShadowCopy || baseName !in retainedFiles)) file.delete()
                    }
                }
                deletedProfileIds.clear()
                corruptedFileNames.clear()
            } finally {
                synchronized(lock) { resetting = false; historyGenerations.value++ }
            }
        }
    }

    private suspend fun retireStores(stores: Map<String, ScopedDataStore>) {
        var failure: Exception? = null
        for ((name, scoped) in stores) {
            try {
                val lifetime = scoped.store as? ProfileStoreLifetime
                if (lifetime != null) lifetime.retire() else scoped.store.edit { it.clear() }
            } catch (error: Exception) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            } finally {
                // Keep the entry unavailable until cancellation releases DataStore's
                // active-file registration. Never permit overlapping file owners.
                scoped.job.cancel()
                scoped.job.join()
                synchronized(lock) { cache.remove(name, scoped) }
            }
        }
        failure?.let { throw it }
    }

    fun isProfileDeleted(profileId: Int): Boolean = profileId in deletedProfileIds

    fun markProfileCreated(profileId: Int) = synchronized(lock) {
        check(!resetting && profileId !in retiringProfiles) { "Profile retirement still in progress" }
        deletedProfileIds.remove(profileId)
        historyGenerations.value++
        Unit
    }

    private fun createAndCache(fileName: String): ScopedDataStore {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val migrations = if (fileName == "layout_settings" || fileName.startsWith("layout_settings_p")) {
            listOf(discoverLocationMigration)
        } else {
            emptyList()
        }
        // Ensure the datastore directory exists — prevents ENOENT on first read
        // when a profile-scoped file hasn't been created yet.
        val dataStoreDir = File(context.filesDir, "datastore")
        if (!dataStoreDir.exists()) {
            dataStoreDir.mkdirs()
        }
        // DataStore 1.1.x (okio-based) can throw FileNotFoundException if the file
        // doesn't exist yet and a race condition occurs. Pre-create an empty file
        // to avoid this edge case (DataStore will overwrite on first write).
        val targetFile = File(dataStoreDir, "$fileName.preferences_pb")
        if (!targetFile.exists()) {
            try { targetFile.createNewFile() } catch (_: Exception) { }
        }
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { ex ->
                Log.e("ProfileDataStoreFactory", "DataStore corrupted ($fileName): ${ex.message} — attempting shadow copy recovery")
                val recovered = recoverFromShadowCopy(fileName)
                if (recovered != null) {
                    Log.i("ProfileDataStoreFactory", "DataStore recovered from shadow copy ($fileName)")
                    recovered
                } else {
                    Log.e("ProfileDataStoreFactory", "DataStore shadow copy unavailable ($fileName) — resetting to empty preferences")
                    corruptedFileNames.add(fileName)
                    emptyPreferences()
                }
            },
            scope = scope,
            migrations = migrations,
            produceFile = { context.preferencesDataStoreFile(fileName) }
        )

        // Wrap store to persist a shadow copy after each successful read.
        // The shadow copy is written once on first data emission (app start),
        // ensuring a consistent backup exists before any corruption can occur.
        val wrappedStore = ShadowCopyDataStore(store, fileName, scope, this)

        val owned = generationOwnedFeatures.any { feature -> fileName == feature || fileName.startsWith("${feature}_p") }
        val scoped = ScopedDataStore(if (owned) ProfileStoreLifetime(wrappedStore, historyGenerations) else wrappedStore, scope, job)
        cache[fileName] = scoped
        return scoped
    }

    internal fun currentShadowGeneration(): Long = shadowGeneration

    /** Writes [preferences] to the shadow copy without opening the live store file, which DataStore renames over. */
    internal suspend fun writeShadowCopy(fileName: String, preferences: Preferences, generation: Long) {
        val dataStoreDir = File(context.filesDir, "datastore")
        val backupFile = File(dataStoreDir, "$fileName.preferences_pb.bak")
        val partFile = File(dataStoreDir, "$fileName.preferences_pb.bak$SHADOW_PART_SUFFIX")
        try {
            val bytes = if (preferences.asMap().isEmpty()) {
                null
            } else {
                okio.Buffer().also {
                    androidx.datastore.preferences.core.PreferencesSerializer.writeTo(preferences, it)
                }.readByteArray()
            }
            synchronized(shadowLock) {
                if (generation != shadowGeneration) return
                if (bytes == null) {
                    backupFile.delete()
                    return
                }
                partFile.writeBytes(bytes)
                if (!partFile.renameTo(backupFile)) {
                    backupFile.delete()
                    if (!partFile.renameTo(backupFile)) partFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.w("ProfileDataStoreFactory", "Failed to write shadow copy for $fileName: ${e.message}")
        }
    }

    private fun recoverFromShadowCopy(fileName: String): Preferences? {
        val backupFile = File(File(context.filesDir, "datastore"), "$fileName.preferences_pb.bak")
        if (!backupFile.exists() || backupFile.length() == 0L) return null
        return try {
            val source = backupFile.inputStream().use { input ->
                okio.Buffer().apply { readFrom(input) }
            }
            val preferences = kotlinx.coroutines.runBlocking {
                androidx.datastore.preferences.core.PreferencesSerializer.readFrom(source)
            }
            Log.i("ProfileDataStoreFactory", "Parsed shadow copy for $fileName (${preferences.asMap().size} keys)")
            preferences
        } catch (e: Exception) {
            Log.w("ProfileDataStoreFactory", "Shadow copy recovery failed for $fileName: ${e.message}")
            null
        }
    }
}


/**
 * Thin wrapper around a DataStore that writes a shadow copy of the underlying
 * preferences file after the first successful read and after each edit.
 * This ensures a known-good backup exists for corruption recovery.
 */
private class ShadowCopyDataStore(
    private val delegate: DataStore<Preferences>,
    private val fileName: String,
    private val scope: CoroutineScope,
    private val factory: ProfileDataStoreFactory
) : DataStore<Preferences> {

    @Volatile
    private var shadowWritten = false
    private val shadowMutex = Mutex()

    override val data: kotlinx.coroutines.flow.Flow<Preferences>
        get() = delegate.data.also {
            if (!shadowWritten) launchShadowCopy()
        }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val result = delegate.updateData(transform)
        launchShadowCopy()
        return result
    }

    // Each copy reads the newest data under the mutex, so a late copy never overwrites a newer one.
    private fun launchShadowCopy() {
        scope.launch(Dispatchers.IO) {
            try {
                shadowMutex.withLock {
                    val generation = factory.currentShadowGeneration()
                    factory.writeShadowCopy(fileName, delegate.data.first(), generation)
                    shadowWritten = true
                }
            } catch (_: Exception) { }
        }
    }
}
