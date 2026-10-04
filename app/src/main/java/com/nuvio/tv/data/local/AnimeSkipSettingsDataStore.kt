package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AnimeSkipSettingsSnapshot(val profileId: Int, val enabled: Boolean, val clientId: String)

@Singleton
class AnimeSkipSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "animeskip_settings"
    }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private val enabledKey = booleanPreferencesKey("animeskip_enabled")
    private val clientIdKey = stringPreferencesKey("animeskip_client_id")

    val enabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[enabledKey] ?: false }
    }

    val clientId: Flow<String> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[clientIdKey] ?: "" }
    }

    // Capture both values from one profile/store emission, including during profile switches.
    suspend fun snapshot(): AnimeSkipSettingsSnapshot {
        val profileId = profileManager.activeProfileId.value
        val preferences = factory.get(profileId, FEATURE).data.first()
        return AnimeSkipSettingsSnapshot(profileId, preferences[enabledKey] ?: false, preferences[clientIdKey]?.trim().orEmpty())
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setClientId(clientId: String, profileId: Int = profileManager.activeProfileId.value) {
        factory.get(profileId, FEATURE).edit { it[clientIdKey] = clientId.trim() }
    }
}
