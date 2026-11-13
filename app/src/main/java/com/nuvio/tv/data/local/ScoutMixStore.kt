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
import kotlinx.coroutines.flow.combine
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
private const val READY = "ready:"
private val PICK_SOURCES = booleanPreferencesKey("pick_sources")
private val NO_SOURCES = stringPreferencesKey("no_sources")
private const val NO_SOURCES_DAYS = 7L

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

    /** Ready-made mixes this profile has opened, kept apart so they never join the viewer's own. */
    fun observeReady(profileId: Int): Flow<List<ScoutMix>> =
        factory.get(profileId, FEATURE).data.map { readScoutMixes(it, READY) }

    fun observeAny(profileId: Int): Flow<List<ScoutMix>> =
        combine(observe(profileId), observeReady(profileId)) { own, ready -> own + ready }

    suspend fun mix(id: String): ScoutMix? = mixes.first().firstOrNull { it.id == id }

    /** Episodes a mix found no link for in the last week, as show id to episode id; mixes leave them out. */
    fun observeNoSources(profileId: Int): Flow<Set<Pair<String, String>>> =
        factory.get(profileId, FEATURE).data.map { scoutNoSourcesRead(it[NO_SOURCES], java.time.LocalDate.now().toEpochDay()) }

    suspend fun markNoSources(contentId: String, videoId: String) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit {
            it[NO_SOURCES] = scoutNoSourcesAdd(it[NO_SOURCES], contentId to videoId, java.time.LocalDate.now().toEpochDay())
        }
    }

    fun profileId(): Int = profileManager.activeProfileId.value

    suspend fun readyMix(profileId: Int, id: String): ScoutMix? = observeReady(profileId).first().firstOrNull { it.id == id }

    suspend fun putReady(mix: ScoutMix) {
        if (!mix.readyMade) return
        factory.get(profileManager.activeProfileId.value, FEATURE).edit {
            it[stringPreferencesKey(READY + mix.id)] = encodeScoutMix(mix)
        }
    }

    /** A ready-made mix kept as the viewer's own: it stops changing with the week and can be renamed and edited. */
    suspend fun saveCopy(mix: ScoutMix): ScoutMix {
        val copy = mix.copy(id = UUID.randomUUID().toString(), autoName = false, recipe = null, builtAt = 0L, savedFrom = mix.id)
        write(copy)
        return copy
    }

    suspend fun create(name: String, show: ScoutMixShow, autoName: Boolean): ScoutMix? {
        val cleaned = name.trim().take(ScoutMixes.NAME_LIMIT)
        if (cleaned.isBlank()) return null
        val mix = ScoutMix(UUID.randomUUID().toString(), cleaned, listOf(show), autoName = autoName)
        write(mix)
        return mix
    }

    suspend fun update(id: String, change: (ScoutMix) -> ScoutMix) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            val key = stringPreferencesKey((if (id.startsWith(ScoutMixes.READY_PREFIX)) READY else PREFIX) + id)
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

internal fun readScoutMixes(preferences: Preferences, prefix: String = PREFIX): List<ScoutMix> =
    preferences.asMap().mapNotNull { (key, value) ->
        if (key.name.startsWith(prefix)) (value as? String)?.let(::decodeScoutMix) else null
    }.sortedWith(compareBy<ScoutMix> { it.name.lowercase() }.thenBy { it.id })

internal fun encodeScoutMix(mix: ScoutMix): String = JSONObject()
    .put("id", mix.id)
    .put("name", mix.name)
    .put("unwatchedOnly", mix.unwatchedOnly)
    .put("keepPlaying", mix.keepPlaying)
    .put("autoName", mix.autoName)
    .putOpt("recipe", mix.recipe)
    .putOpt("savedFrom", mix.savedFrom)
    .apply { if (mix.rules > 0) put("rules", mix.rules) }
    .apply { if (mix.builtAt > 0L) put("builtAt", mix.builtAt) }
    .apply {
        if (mix.only.isNotEmpty()) put("only", JSONObject().apply {
            mix.only.forEach { (show, episodes) -> put(show, JSONArray(episodes.map { "${it.first}:${it.second}" })) }
        })
    }
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
        recipe = json.textOrNull("recipe"),
        savedFrom = json.textOrNull("savedFrom"),
        rules = json.optInt("rules", 0),
        builtAt = json.optLong("builtAt", 0L),
        only = json.optJSONObject("only")?.let { only ->
            only.keys().asSequence().associateWith { show ->
                val list = only.getJSONArray(show)
                (0 until list.length()).mapNotNull { index ->
                    val parts = list.getString(index).split(':')
                    val season = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    val episode = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    season to episode
                }.toSet()
            }
        }.orEmpty(),
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

private const val NO_SOURCES_JOIN = "|"

internal fun scoutNoSourcesRead(raw: String?, today: Long): Set<Pair<String, String>> {
    val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptySet()
    return json.keys().asSequence().filter { today - json.optLong(it, 0L) < NO_SOURCES_DAYS }.mapNotNull { key ->
        val parts = key.split(NO_SOURCES_JOIN)
        if (parts.size == 2) parts[0] to parts[1] else null
    }.toSet()
}

/** Adds one episode and drops the ones a week old, so the list stays small. */
internal fun scoutNoSourcesAdd(raw: String?, episode: Pair<String, String>, today: Long): String {
    val old = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    val kept = JSONObject()
    old.keys().forEach { key -> old.optLong(key, 0L).takeIf { today - it < NO_SOURCES_DAYS }?.let { kept.put(key, it) } }
    kept.put(episode.first + NO_SOURCES_JOIN + episode.second, today)
    return kept.toString()
}

private fun JSONObject.textOrNull(key: String): String? = optString(key).takeIf { has(key) && it.isNotBlank() }
