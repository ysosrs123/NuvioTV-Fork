package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.ScoutMix
import com.nuvio.tv.domain.model.ScoutMixShow
import com.nuvio.tv.domain.model.ScoutMixes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val FEATURE = "scout_mixes"
private const val PREFIX = "mix:"
private val PICK_SOURCES = booleanPreferencesKey("pick_sources")

/** Each profile's mixes, on this device only. */
@Singleton
class ScoutMixStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val mixes: Flow<List<ScoutMix>> = profileManager.activeProfileId.flatMapLatest(::observe)

    fun observe(profileId: Int): Flow<List<ScoutMix>> =
        factory.get(profileId, FEATURE).data.map(::readScoutMixes)

    /** One choice for all of a profile's mixes: an episode picks its own source, as auto-play would. On unless turned off. */
    fun observePickSources(profileId: Int): Flow<Boolean> =
        factory.get(profileId, FEATURE).data.map { it[PICK_SOURCES] ?: true }

    suspend fun setPickSources(on: Boolean) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { it[PICK_SOURCES] = on }
    }

    suspend fun mix(id: String): ScoutMix? = mixes.first().firstOrNull { it.id == id }

    suspend fun create(name: String, show: ScoutMixShow, autoName: Boolean): ScoutMix? {
        val cleaned = name.trim().take(ScoutMixes.NAME_LIMIT)
        if (cleaned.isBlank()) return null
        val mix = ScoutMix(UUID.randomUUID().toString(), cleaned, listOf(show), autoName = autoName)
        write(mix)
        return mix
    }

    suspend fun update(id: String, change: (ScoutMix) -> ScoutMix) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            val key = stringPreferencesKey(PREFIX + id)
            val current = prefs[key]?.let(::decodeScoutMix) ?: return@edit
            prefs[key] = encodeScoutMix(change(current))
        }
    }

    suspend fun delete(id: String) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { it.remove(stringPreferencesKey(PREFIX + id)) }
    }

    private suspend fun write(mix: ScoutMix) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit {
            it[stringPreferencesKey(PREFIX + mix.id)] = encodeScoutMix(mix)
        }
    }
}

internal fun readScoutMixes(preferences: Preferences): List<ScoutMix> =
    preferences.asMap().mapNotNull { (key, value) ->
        if (key.name.startsWith(PREFIX)) (value as? String)?.let(::decodeScoutMix) else null
    }.sortedWith(compareBy<ScoutMix> { it.name.lowercase() }.thenBy { it.id })

internal fun encodeScoutMix(mix: ScoutMix): String = JSONObject()
    .put("id", mix.id)
    .put("name", mix.name)
    .put("unwatchedOnly", mix.unwatchedOnly)
    .put("keepPlaying", mix.keepPlaying)
    .put("autoName", mix.autoName)
    .put("shows", JSONArray().apply {
        mix.shows.forEach { show ->
            put(
                JSONObject()
                    .put("id", show.id)
                    .put("name", show.name)
                    .put("type", show.type)
                    .putOpt("poster", show.poster)
                    .putOpt("backdrop", show.backdrop)
                    .putOpt("imdbId", show.imdbId)
                    .putOpt("addonBaseUrl", show.addonBaseUrl)
                    .putOpt("logo", show.logo)
                    .put("logoBest", show.logoChecked)
                    .put("genres", JSONArray(show.genres))
            )
        }
    })
    .toString()

internal fun decodeScoutMix(raw: String): ScoutMix? = runCatching {
    val json = JSONObject(raw)
    val shows = json.getJSONArray("shows")
    ScoutMix(
        id = json.getString("id"),
        name = json.getString("name"),
        unwatchedOnly = json.optBoolean("unwatchedOnly", true),
        keepPlaying = json.optBoolean("keepPlaying", true),
        autoName = json.optBoolean("autoName", false),
        shows = (0 until shows.length()).map { index ->
            val show = shows.getJSONObject(index)
            ScoutMixShow(
                id = show.getString("id"),
                name = show.getString("name"),
                type = show.optString("type", "series"),
                poster = show.textOrNull("poster"),
                backdrop = show.textOrNull("backdrop"),
                imdbId = show.textOrNull("imdbId"),
                addonBaseUrl = show.textOrNull("addonBaseUrl"),
                logo = show.textOrNull("logo"),
                logoChecked = show.optBoolean("logoBest", false),
                genres = show.optJSONArray("genres")?.let { list ->
                    (0 until list.length()).map(list::getString)
                }.orEmpty()
            )
        }
    )
}.getOrNull()?.takeIf { it.id.isNotBlank() && it.name.isNotBlank() }

private fun JSONObject.textOrNull(key: String): String? = optString(key).takeIf { has(key) && it.isNotBlank() }
