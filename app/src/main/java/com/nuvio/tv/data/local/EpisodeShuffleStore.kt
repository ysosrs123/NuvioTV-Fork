package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.EpisodeShuffleSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

data class EpisodeShuffleProfile(
    val profileId: Int,
    val available: Boolean = false,
    val shows: Map<String, EpisodeShuffleSettings> = emptyMap()
) {
    fun settings(contentId: String, contentType: String): EpisodeShuffleSettings {
        val saved = shows[contentId] ?: EpisodeShuffleSettings()
        return saved.copy(enabled = saved.enabled && available &&
            (contentType.equals("series", true) || contentType.equals("tv", true)))
    }
}

@Singleton
class EpisodeShuffleStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val layoutPreferences: LayoutPreferenceDataStore
) {
    val profiles = profileManager.activeProfileId.flatMapLatest(::observeProfile)

    fun observeProfile(profileId: Int) = combine(
        factory.get(profileId, "episode_shuffle").data,
        layoutPreferences.randomEpisodeEnabledForProfile(profileId)
    ) { preferences, available ->
        EpisodeShuffleProfile(profileId, available, readShuffleSettings(preferences))
    }

    /** Episodes whose latest play was in a Mix; Continue watching leaves them out. */
    suspend fun mixPlayed(profileId: Int): Set<String> =
        factory.get(profileId, MIX_PLAYED_FEATURE).data.map { it[MIX_PLAYED].orEmpty() }.first()

    suspend fun setMixPlayed(profileId: Int, key: String, played: Boolean) {
        factory.get(profileId, MIX_PLAYED_FEATURE).edit { prefs ->
            val current = prefs[MIX_PLAYED].orEmpty()
            val next = if (played) (current + key).toList().takeLast(MIX_PLAYED_LIMIT).toSet() else current - key
            if (next != current) prefs[MIX_PLAYED] = next
        }
    }

    suspend fun save(contentId: String, settings: EpisodeShuffleSettings, profileId: Int) {
        if (contentId.isBlank()) return
        factory.get(profileId, "episode_shuffle").edit {
            it[booleanPreferencesKey("enabled:$contentId")] = settings.enabled
            it[booleanPreferencesKey("watched:$contentId")] = settings.includeWatched
        }
    }
}

private const val MIX_PLAYED_FEATURE = "mix_continue_watching"
private val MIX_PLAYED = stringSetPreferencesKey("mix_played")
private const val MIX_PLAYED_LIMIT = 300

/** One episode across local and tracker progress: the show's id and the episode's number. */
fun mixPlayKey(contentId: String, season: Int?, episode: Int?): String = "$contentId|${season ?: ""}|${episode ?: ""}"

internal fun readShuffleSettings(preferences: Preferences): Map<String, EpisodeShuffleSettings> =
    preferences.asMap().keys.mapNotNull { key ->
        key.name.takeIf { it.startsWith("enabled:") || it.startsWith("watched:") }
            ?.substringAfter(':')?.takeIf(String::isNotBlank)
    }.distinct().associateWith { id ->
        EpisodeShuffleSettings(
            enabled = preferences[booleanPreferencesKey("enabled:$id")] ?: false,
            includeWatched = preferences[booleanPreferencesKey("watched:$id")] ?: false
        )
    }
