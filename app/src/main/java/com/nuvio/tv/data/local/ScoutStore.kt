package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.scout.sync.ImportChange
import com.nuvio.tv.core.scout.sync.OutAction
import com.nuvio.tv.domain.model.ScoutAskTarget
import com.nuvio.tv.domain.model.ScoutProfile
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutRatingEntry
import com.nuvio.tv.domain.model.ScoutSettings
import com.nuvio.tv.domain.model.ScoutTitle
import com.nuvio.tv.domain.model.decodeScoutRating
import com.nuvio.tv.domain.model.encodeScoutRating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val FEATURE = "scout"
private const val RATING_PREFIX = "rating:"
private const val OUT_PREFIX = "out:"
private const val SENT_PREFIX = "sent:"
private const val PULLED_PREFIX = "pulled:"
private const val OUT_FIELD = '\u001F'

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
    private val leaveOutLanguages = stringSetPreferencesKey("leave_out_languages")
    private val asked = stringSetPreferencesKey("asked")
    private val savedSlices = stringPreferencesKey("saved_slices")
    private val tasteRow = stringPreferencesKey("taste_row")
    private val sendToTrackers = booleanPreferencesKey("send_to_trackers")
    private val useTrackerRatings = booleanPreferencesKey("use_tracker_ratings")
    private val dismissed = stringSetPreferencesKey("import_dismissed")

    private val _pendingAsk = MutableStateFlow<ScoutAskTarget?>(null)
    val pendingAsk: StateFlow<ScoutAskTarget?> = _pendingAsk.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val profile: Flow<ScoutProfile> = profileManager.activeProfileId.flatMapLatest { id ->
        factory.get(id, FEATURE).data.map { readProfile(id, it) }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

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

    /** A thumb pressed in Arc. With sending on, the same write queues it for the trackers. */
    suspend fun setRating(title: ScoutTitle, rating: ScoutRating?, now: Long = System.currentTimeMillis()) {
        if (title.id.isBlank()) return
        val key = stringPreferencesKey(RATING_PREFIX + title.id)
        factory.get(profileManager.activeProfileId.value, FEATURE).edit {
            if (rating == null) {
                it.remove(key)
                it[dismissed] = (it[dismissed] ?: emptySet()) + title.id
            } else {
                it[key] = encodeScoutRating(ScoutRatingEntry(title, rating, now))
            }
            if (it[sendToTrackers] == true) it[stringPreferencesKey(OUT_PREFIX + title.id)] = encodeOut(title, rating, now)
        }
    }

    /** Applies a tracker's thumbs in one write; the plan is made against what is stored at that moment. */
    suspend fun applyImport(
        profileId: Int,
        plan: (profile: ScoutProfile, pending: Set<String>, sent: Map<String, Double>) -> List<ImportChange>
    ): Int {
        var count = 0
        factory.get(profileId, FEATURE).edit { prefs ->
            val profile = readProfile(profileId, prefs)
            val pending = prefs.asMap().keys.filter { it.name.startsWith(OUT_PREFIX) }.map { it.name.removePrefix(OUT_PREFIX) }.toSet()
            val changes = plan(profile, pending, sentValues(prefs))
            changes.forEach { change ->
                val key = stringPreferencesKey(RATING_PREFIX + change.contentId)
                if (change.entry == null) prefs.remove(key) else prefs[key] = encodeScoutRating(change.entry)
            }
            count = changes.size
        }
        return count
    }

    /** Thumbs waiting for the trackers, by title id. */
    suspend fun outbox(profileId: Int): Map<String, ScoutOutItem> =
        factory.get(profileId, FEATURE).data.first().asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(OUT_PREFIX) || value !is String) return@mapNotNull null
            val id = key.name.removePrefix(OUT_PREFIX)
            decodeOut(id, value)?.let { id to it }
        }.toMap()

    fun outboxSize(profileId: Int): Flow<Int> = factory.get(profileId, FEATURE).data
        .map { prefs -> prefs.asMap().keys.count { it.name.startsWith(OUT_PREFIX) } }

    /** What Arc last sent one tracker, by title id. */
    suspend fun sent(profileId: Int, provider: String): Map<String, Double> =
        sentValues(factory.get(profileId, FEATURE).data.first())
            .filterKeys { it.startsWith("$provider:") }
            .mapKeys { it.key.removePrefix("$provider:") }

    /**
     * Records one tracker's outcome for a title: the value now remembered as sent (null forgets it) and the
     * tracker as done. The queued action goes once every tracker in [required] is done, and only if no newer
     * press replaced it meanwhile.
     */
    suspend fun finishOut(profileId: Int, id: String, stamp: Long, provider: String, sentValue: Double?, required: Set<String>) {
        factory.get(profileId, FEATURE).edit { prefs ->
            val sentKey = stringPreferencesKey("$SENT_PREFIX$provider:$id")
            val outKey = stringPreferencesKey(OUT_PREFIX + id)
            val item = prefs[outKey]?.let { decodeOut(id, it) }
            if (item != null && item.action.stamp != stamp) return@edit
            if (sentValue == null) prefs.remove(sentKey) else prefs[sentKey] = sentValue.toString()
            if (item == null) return@edit
            val done = item.done + provider
            if (done.containsAll(required)) prefs.remove(outKey) else prefs[outKey] = encodeOut(item.copy(done = done))
        }
    }

    /** Drops a queued action nobody can take any more, such as one for a title with no tracker id. */
    suspend fun dropOut(profileId: Int, id: String, stamp: Long) {
        factory.get(profileId, FEATURE).edit { prefs ->
            val outKey = stringPreferencesKey(OUT_PREFIX + id)
            if (prefs[outKey]?.let { decodeOut(id, it) }?.action?.stamp == stamp) prefs.remove(outKey)
        }
    }

    suspend fun lastPulled(profileId: Int, provider: String): Long =
        factory.get(profileId, FEATURE).data.first()[stringPreferencesKey(PULLED_PREFIX + provider)]?.toLongOrNull() ?: 0L

    suspend fun markPulled(profileId: Int, provider: String, at: Long) {
        factory.get(profileId, FEATURE).edit { it[stringPreferencesKey(PULLED_PREFIX + provider)] = at.toString() }
    }

    private fun sentValues(prefs: Preferences): Map<String, Double> = prefs.asMap().mapNotNull { (key, value) ->
        if (!key.name.startsWith(SENT_PREFIX) || value !is String) return@mapNotNull null
        value.toDoubleOrNull()?.let { key.name.removePrefix(SENT_PREFIX) to it }
    }.toMap()

    private fun encodeOut(title: ScoutTitle, rating: ScoutRating?, now: Long) =
        encodeOut(ScoutOutItem(title, OutAction(rating, now), emptySet()))

    private fun encodeOut(item: ScoutOutItem): String = listOf(
        item.action.rating?.name.orEmpty(), item.action.stamp.toString(), item.title.type, item.title.name, item.done.joinToString(",")
    ).joinToString(OUT_FIELD.toString())

    private fun decodeOut(id: String, raw: String): ScoutOutItem? {
        val parts = raw.split(OUT_FIELD)
        if (parts.size < 5) return null
        val rating = if (parts[0].isBlank()) null else runCatching { ScoutRating.valueOf(parts[0]) }.getOrNull() ?: return null
        return ScoutOutItem(
            ScoutTitle(id, parts[2], parts[3]),
            OutAction(rating, parts[1].toLongOrNull() ?: 0L),
            parts[4].split(",").filter(String::isNotBlank).toSet()
        )
    }

    suspend fun clearRatings() {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(RATING_PREFIX) }.forEach { prefs.remove(it) }
            prefs.remove(asked)
            prefs.asMap().keys.filter { it.name.startsWith(OUT_PREFIX) }.forEach { prefs.remove(it) }
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
            it[leaveOutLanguages] = next.leaveOutLanguages
            it[savedSlices] = next.savedSlices.joinToString(",")
            it[tasteRow] = next.tasteRow
            it[sendToTrackers] = next.sendToTrackers
            it[useTrackerRatings] = next.useTrackerRatings
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
        leaveOutLanguages = p[leaveOutLanguages].orEmpty(),
        savedSlices = p[savedSlices].orEmpty().split(",").filter(String::isNotBlank),
        tasteRow = p[tasteRow] ?: "titles",
        sendToTrackers = p[sendToTrackers] ?: false,
        useTrackerRatings = p[useTrackerRatings] ?: true
    )

    private fun readProfile(profileId: Int, p: Preferences): ScoutProfile {
        val ratings = p.asMap().entries.mapNotNull { (key, value) ->
            if (!key.name.startsWith(RATING_PREFIX) || value !is String) return@mapNotNull null
            val id = key.name.removePrefix(RATING_PREFIX)
            decodeScoutRating(id, value)?.let { id to it }
        }.toMap()
        return ScoutProfile(profileId, readSettings(p), ratings, p[asked].orEmpty(), p[dismissed].orEmpty())
    }
}

data class ScoutOutItem(val title: ScoutTitle, val action: OutAction, val done: Set<String>)
