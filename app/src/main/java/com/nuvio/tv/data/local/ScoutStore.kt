package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.ScoutAskTarget
import com.nuvio.tv.domain.model.ScoutProfile
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutRatingEntry
import com.nuvio.tv.domain.model.ScoutSettings
import com.nuvio.tv.domain.model.ScoutTitle
import com.nuvio.tv.domain.model.decodeScoutRating
import com.nuvio.tv.domain.model.encodeScoutRating
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val FEATURE = "scout"
private const val RATING_PREFIX = "rating:"

@Singleton
class ScoutStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val ratingsEnabled = booleanPreferencesKey("ratings_enabled")
    private val askAfterFilm = booleanPreferencesKey("ask_after_film")
    private val pickedRow = booleanPreferencesKey("picked_row")
    private val genreRows = booleanPreferencesKey("genre_rows")
    private val becauseRow = booleanPreferencesKey("because_row")
    private val gemsRow = booleanPreferencesKey("gems_row")
    private val tilesRow = booleanPreferencesKey("tiles_row_chosen")
    private val leaveOutGenres = stringSetPreferencesKey("leave_out_genres")
    private val asked = stringSetPreferencesKey("asked")
    private val savedSlices = stringPreferencesKey("saved_slices")
    private val tasteRow = stringPreferencesKey("taste_row")

    private val _pendingAsk = MutableStateFlow<ScoutAskTarget?>(null)
    val pendingAsk: StateFlow<ScoutAskTarget?> = _pendingAsk.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val profile: Flow<ScoutProfile> = profileManager.activeProfileId.flatMapLatest { id ->
        factory.get(id, FEATURE).data.map { readProfile(id, it) }
    }

    fun offerAsk(target: ScoutAskTarget) {
        _pendingAsk.value = target
    }

    fun clearAsk() {
        _pendingAsk.value = null
    }

    suspend fun markAsked(askKey: String) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit {
            it[asked] = (it[asked] ?: emptySet()) + askKey
        }
    }

    suspend fun setRating(title: ScoutTitle, rating: ScoutRating?, now: Long = System.currentTimeMillis()) {
        if (title.id.isBlank()) return
        val key = stringPreferencesKey(RATING_PREFIX + title.id)
        factory.get(profileManager.activeProfileId.value, FEATURE).edit {
            if (rating == null) it.remove(key)
            else it[key] = encodeScoutRating(ScoutRatingEntry(title, rating, now))
        }
    }

    suspend fun clearRatings() {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(RATING_PREFIX) }.forEach { prefs.remove(it) }
            prefs.remove(asked)
        }
    }

    suspend fun update(transform: (ScoutSettings) -> ScoutSettings) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit {
            val next = transform(readSettings(it))
            it[ratingsEnabled] = next.ratingsEnabled
            it[askAfterFilm] = next.askAfterFilm
            it[pickedRow] = next.pickedRow
            it[genreRows] = next.genreRows
            it[becauseRow] = next.becauseRow
            it[gemsRow] = next.gemsRow
            it[tilesRow] = next.tilesRow
            it[leaveOutGenres] = next.leaveOutGenres.map(Int::toString).toSet()
            it[savedSlices] = next.savedSlices.joinToString(",")
            it[tasteRow] = next.tasteRow
        }
    }

    suspend fun current(): ScoutProfile = profile.first()

    suspend fun profileOf(profileId: Int): ScoutProfile = readProfile(profileId, factory.get(profileId, FEATURE).data.first())

    internal fun readSettings(p: Preferences) = ScoutSettings(
        ratingsEnabled = p[ratingsEnabled] ?: true,
        askAfterFilm = p[askAfterFilm] ?: true,
        pickedRow = p[pickedRow] ?: true,
        genreRows = p[genreRows] ?: p[pickedRow] ?: true,
        becauseRow = p[becauseRow] ?: true,
        gemsRow = p[gemsRow] ?: true,
        tilesRow = p[tilesRow] ?: false,
        leaveOutGenres = p[leaveOutGenres].orEmpty().mapNotNull(String::toIntOrNull).toSet(),
        savedSlices = p[savedSlices].orEmpty().split(",").filter(String::isNotBlank),
        tasteRow = p[tasteRow] ?: "titles"
    )

    private fun readProfile(profileId: Int, p: Preferences): ScoutProfile {
        val ratings = p.asMap().entries.mapNotNull { (key, value) ->
            if (!key.name.startsWith(RATING_PREFIX) || value !is String) return@mapNotNull null
            val id = key.name.removePrefix(RATING_PREFIX)
            decodeScoutRating(id, value)?.let { id to it }
        }.toMap()
        return ScoutProfile(profileId, readSettings(p), ratings, p[asked].orEmpty())
    }
}
