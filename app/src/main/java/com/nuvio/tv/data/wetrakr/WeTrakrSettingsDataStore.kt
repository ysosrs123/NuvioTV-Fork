package com.nuvio.tv.data.wetrakr

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class WeTrakrSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    val continueWatchingDays: StateFlow<Int> = profileManager.activeProfileId.flatMapLatest { profileId ->
        runCatching { factory.get(profileId, FEATURE).data }.getOrElse { flowOf() }
            .map { it[DAYS] ?: ALL_HISTORY }
            .catch { emit(ALL_HISTORY) }
    }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, ALL_HISTORY)

    suspend fun setContinueWatchingDays(days: Int) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { it[DAYS] = days.coerceAtLeast(ALL_HISTORY) }
    }

    companion object {
        const val ALL_HISTORY = 0
        private const val FEATURE = "wetrakr_settings"
        private val DAYS = intPreferencesKey("continue_watching_days")
    }
}
