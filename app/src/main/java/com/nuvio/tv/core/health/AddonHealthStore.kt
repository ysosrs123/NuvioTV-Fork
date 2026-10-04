package com.nuvio.tv.core.health

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted, profile-scoped passive health for add-ons and resolvers. Keys are
 * opaque: [ADDON_PREFIX] + canonical base URL, or [RESOLVER_PREFIX] + provider.
 * Gson + per-profile DataStore, same pattern as AddonPreferences. No polling.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class AddonHealthStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) {
    companion object {
        private const val FEATURE = "addon_health"
        const val ADDON_PREFIX = "addon:"
        const val RESOLVER_PREFIX = "resolver:"
        const val METADATA_PREFIX = "metadata:"
        const val METADATA_KEY = "metadata:pipeline"
        // Redundant fast successes for the same key within this window are
        // dropped (collapses e.g. one addon serving many catalog rows on
        // startup). Failures, empties and slow successes are never coalesced.
        private const val COALESCE_WINDOW_MS = 30_000L

        fun addonKey(canonicalBaseUrl: String): String = ADDON_PREFIX + canonicalBaseUrl
        fun resolverKey(provider: String): String = RESOLVER_PREFIX + provider
    }

    private val gson = Gson()
    private val lastFastSuccessAtMs = ConcurrentHashMap<String, Long>()
    private val recordsKey = stringPreferencesKey("health_records")
    private val recordMapType = object : TypeToken<Map<String, HealthRecord>>() {}.type

    private fun effectiveProfileId(): Int {
        val active = profileManager.activeProfile
        return if (active != null && active.usesPrimaryAddons) 1 else profileManager.activeProfileId.value
    }

    private fun store(profileId: Int = effectiveProfileId()) = factory.get(profileId, FEATURE)

    private val effectiveProfileIdFlow: Flow<Int> = combine(
        profileManager.activeProfileId,
        profileManager.profiles
    ) { activeProfileId, profiles ->
        val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
        if (activeProfile?.usesPrimaryAddons == true) 1 else activeProfileId
    }.distinctUntilChanged()

    /** Derived traffic-light levels, keyed as above. Recomputed on every write. */
    val levels: Flow<Map<String, AddonHealthLevel>> =
        layoutPreferenceDataStore.addonHealthEnabled.flatMapLatest { enabled ->
            if (!enabled) {
                flowOf(emptyMap())
            } else {
                effectiveProfileIdFlow.flatMapLatest { pid ->
                    factory.get(pid, FEATURE).data.map { preferences ->
                        try {
                            val now = System.currentTimeMillis()
                            parseRecords(preferences[recordsKey]).mapValues { (key, record) ->
                                val slow = if (key.startsWith(METADATA_PREFIX)) {
                                    AddonHealthModel.METADATA_SLOW_LATENCY_MS
                                } else {
                                    AddonHealthModel.SLOW_LATENCY_MS
                                }
                                AddonHealthModel.deriveLevel(record, now, slow)
                            }
                        } catch (e: Exception) {
                            emptyMap()
                        }
                    }
                }
            }
        }

    /** Record one request outcome against [key]. Never throws. */
    suspend fun record(key: String, outcome: HealthOutcome, latencyMs: Long) {
        if (!layoutPreferenceDataStore.addonHealthEnabled.first()) return
        val now = System.currentTimeMillis()
        if (outcome == HealthOutcome.SUCCESS) {
            val slow = if (key.startsWith(METADATA_PREFIX)) {
                AddonHealthModel.METADATA_SLOW_LATENCY_MS
            } else {
                AddonHealthModel.SLOW_LATENCY_MS
            }
            if (latencyMs <= slow) {
                val lastFast = lastFastSuccessAtMs[key] ?: 0L
                if (now - lastFast < COALESCE_WINDOW_MS) return
                lastFastSuccessAtMs[key] = now
            }
        }
        val sample = HealthSample(
            atMs = now,
            outcome = outcome,
            latencyMs = latencyMs
        )
        store().edit { preferences ->
            val current = parseRecords(preferences[recordsKey]).toMutableMap()
            val updated = AddonHealthModel.applySample(
                current[key] ?: HealthRecord(),
                sample
            )
            current[key] = updated
            preferences[recordsKey] = gson.toJson(current)
        }
    }

    private fun parseRecords(json: String?): Map<String, HealthRecord> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val parsed: Map<String, HealthRecord>? = gson.fromJson(json, recordMapType)
            parsed ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
