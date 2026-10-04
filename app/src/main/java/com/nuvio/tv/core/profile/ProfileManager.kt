package com.nuvio.tv.core.profile

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.ContinueWatchingSnapshotStorage
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileManager @Inject constructor(
    private val profileDataStore: ProfileDataStore,
    private val factory: ProfileDataStoreFactory,
    private val credentialStores: Set<@JvmSuppressWildcards ProfileScopedCredentialStore>,
    @ApplicationContext private val context: Context,
    private val snapshotStorage: ContinueWatchingSnapshotStorage = ContinueWatchingSnapshotStorage(context)
) {
    companion object {
        const val MAX_PROFILES = 6
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // An admission stamp also catches rapid selections whose intermediate ID is conflated.
    private val _profileSelectionRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    internal val profileSelectionRevision: StateFlow<Long> get() = _profileSelectionRevision
    internal val profileHistoryGenerationChanges: StateFlow<Long> get() = factory.historyGenerationChanges

    val activeProfileId: StateFlow<Int> = profileDataStore.activeProfileId
        .stateIn(scope, SharingStarted.Eagerly, 1)

    val activeProfileReady: StateFlow<Boolean> = profileDataStore.activeProfileId
        .map { true }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val hasEverSelectedProfile: StateFlow<Boolean> = profileDataStore.hasEverSelectedProfile
        .stateIn(scope, SharingStarted.Eagerly, false)

    val rememberLastProfileEnabled: StateFlow<Boolean> = profileDataStore.rememberLastProfileEnabled
        .stateIn(scope, SharingStarted.Eagerly, false)

    val confirmExitEnabled: StateFlow<Boolean> = profileDataStore.confirmExitEnabled
        .stateIn(scope, SharingStarted.Eagerly, false)

    val startupSplashEnabled: StateFlow<Boolean> = profileDataStore.startupSplashEnabled
        .stateIn(scope, SharingStarted.Eagerly, true)

    val profiles: StateFlow<List<UserProfile>> = profileDataStore.profilesList
        .stateIn(scope, SharingStarted.Eagerly, listOf(
            UserProfile(id = 1, name = context.getString(R.string.profile_default_name, 1), avatarColorHex = "#1E88E5")
        ))

    val activeProfile: UserProfile?
        get() = profiles.value.find { it.id == activeProfileId.value }

    val isPrimaryProfileActive: Boolean
        get() = activeProfileId.value == 1

    val canCreateProfile: Boolean
        get() = profiles.value.size < MAX_PROFILES

    suspend fun setActiveProfile(id: Int) = snapshotStorage.lifecycleMutex.withLock {
        val exists = profileDataStore.profilesList.first().any { it.id == id }
        if (exists) {
            _profileSelectionRevision.update { it + 1 }
            if (snapshotStorage.reopenListedProfile(id)) factory.markProfileCreated(id)
            com.nuvio.tv.core.stream.StreamPrefetchCache.updatePolicy(id, false)
            profileDataStore.setActiveProfile(id)
        }
    }

    suspend fun setRememberLastProfileEnabled(enabled: Boolean) {
        profileDataStore.setRememberLastProfileEnabled(enabled)
    }

    suspend fun setConfirmExitEnabled(enabled: Boolean) {
        profileDataStore.setConfirmExitEnabled(enabled)
    }

    suspend fun setStartupSplashEnabled(enabled: Boolean) {
        profileDataStore.setStartupSplashEnabled(enabled)
    }

    suspend fun createProfile(
        name: String,
        avatarColorHex: String,
        usesPrimaryAddons: Boolean = false,
        usesPrimaryPlugins: Boolean = false,
        avatarId: String? = null
    ): UserProfile? = snapshotStorage.lifecycleMutex.withLock {
        val current = profileDataStore.profilesList.first()
        if (current.size >= MAX_PROFILES) return@withLock null

        val usedIds = current.map { it.id }.toSet()
        val nextId = (2..MAX_PROFILES).firstOrNull { it !in usedIds } ?: return@withLock null

        val profile = UserProfile(
            id = nextId,
            name = name.trim().ifEmpty { context.getString(R.string.profile_default_name, nextId) },
            avatarColorHex = avatarColorHex,
            usesPrimaryAddons = usesPrimaryAddons,
            usesPrimaryPlugins = usesPrimaryPlugins,
            avatarId = avatarId
        )
        // Once creation starts changing stores, complete the metadata/file-owner commit.
        withContext(NonCancellable) {
            factory.markProfileCreated(nextId)
            profileDataStore.upsertProfile(profile)
            snapshotStorage.activateProfile(nextId)
            profiles.first { entries -> entries.any { it.id == nextId } }
            profile
        }
    }

    suspend fun deleteProfile(id: Int): Boolean = snapshotStorage.lifecycleMutex.withLock {
        if (id == 1) return@withLock false
        if (profileDataStore.profilesList.first().none { it.id == id }) return@withLock false
        // A cancelled waiter cannot enter. An admitted retirement must finish its metadata cleanup.
        withContext(NonCancellable) {
            credentialStores.forEach { store -> store.removeProfile(id) }
            snapshotStorage.clearProfile(id, retire = true)
            deleteProfileDataAsync(id)
            profileDataStore.deleteProfile(id)
            profiles.first { entries -> entries.none { it.id == id } }
            true
        }
    }

    suspend fun updateProfile(profile: UserProfile): Boolean = snapshotStorage.lifecycleMutex.withLock {
        if (profileDataStore.profilesList.first().none { it.id == profile.id }) return@withLock false
        profileDataStore.upsertProfile(profile)
        true
    }

    private suspend fun deleteProfileDataAsync(profileId: Int) = withContext(Dispatchers.IO) {
        if (profileId == 1) return@withContext

        factory.clearProfile(profileId)
        com.nuvio.tv.ui.v2.profile.ProfilePosterWall.clear(context, profileId)
        val suffixWithExtension = "_p${profileId}.preferences_pb"
        val dataStoreDir = File(context.filesDir, "datastore")
        if (dataStoreDir.exists()) {
            dataStoreDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(suffixWithExtension)) {
                    file.delete()
                }
            }
        }

        val pluginCodeDir = File(context.filesDir, "plugin_code_p${profileId}")
        if (pluginCodeDir.exists()) {
            pluginCodeDir.deleteRecursively()
        }
    }
}
