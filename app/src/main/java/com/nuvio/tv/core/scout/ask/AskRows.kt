package com.nuvio.tv.core.scout.ask

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.catalog.BuiltInCatalogProvider
import com.nuvio.tv.core.catalog.BuiltInCatalogs
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.core.poster.withCustomPosterUrls
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.catalogRowLegacyKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal const val ASK_MAX_RECENT = 12
private const val RECENT_POSTERS = 3
internal const val ASK_MAX_SAVED_ROWS = 10
private const val MAX_OVERVIEW = 400
private const val FEATURE = "scout_ask"
private const val ROW_TYPE = "scout"
private const val REFRESH_AFTER_MS = 7L * 24 * 60 * 60 * 1000
private const val RETRY_AFTER_MS = 24L * 60 * 60 * 1000

@Serializable
data class AskSavedItem(
    val id: String,
    val series: Boolean,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val overview: String? = null,
    val year: String? = null,
    val rating: Float? = null,
    val reason: String = "",
    val imdbId: String? = null,
    val logo: String? = null
)

@Serializable
data class AskSavedRow(
    val id: String,
    val question: String,
    val name: String,
    val refreshedAt: Long,
    val attemptedAt: Long = refreshedAt,
    val items: List<AskSavedItem>
)

internal fun AskItem.toSaved() = AskSavedItem(
    id = meta.id,
    series = meta.type == ContentType.SERIES,
    name = meta.name,
    poster = meta.rawPosterUrl ?: meta.poster,
    background = meta.background,
    imdbId = meta.imdbId,
    logo = meta.logo,
    overview = meta.description?.take(MAX_OVERVIEW),
    year = meta.releaseInfo,
    rating = meta.imdbRating,
    reason = reason
)

internal fun AskSavedItem.toMeta() = MetaPreview(
    id = id,
    type = if (series) ContentType.SERIES else ContentType.MOVIE,
    rawType = if (series) "series" else "movie",
    name = name,
    poster = poster,
    posterShape = PosterShape.POSTER,
    background = background,
    logo = logo,
    imdbId = imdbId,
    description = overview,
    releaseInfo = year,
    imdbRating = rating,
    genres = emptyList()
)

/** Weekly per row, so a saved row costs at most about one question a week; a failure waits a day. */
internal fun AskSavedRow.dueForRefresh(now: Long): Boolean =
    now - refreshedAt >= REFRESH_AFTER_MS && now - attemptedAt >= RETRY_AFTER_MS

internal fun recentWith(recent: List<String>, question: String): List<String> =
    (listOf(question) + recent.filterNot { it.equals(question, ignoreCase = true) }).take(ASK_MAX_RECENT)

@Singleton
class AskRowsStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val rowsKey = stringPreferencesKey("rows")
    private val recentKey = stringPreferencesKey("recent")
    private val recentArtKey = stringPreferencesKey("recent_art")

    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: Flow<List<AskSavedRow>> = profileManager.activeProfileId.flatMapLatest { id ->
        factory.get(id, FEATURE).data.map(::readRows)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val recent: Flow<List<String>> = profileManager.activeProfileId.flatMapLatest { id ->
        factory.get(id, FEATURE).data.map(::readRecent)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentArt: Flow<Map<String, List<String>>> = profileManager.activeProfileId.flatMapLatest { id ->
        factory.get(id, FEATURE).data.map(::readRecentArt)
    }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    suspend fun remember(question: String) {
        store().edit { it[recentKey] = json.encodeToString(recentWith(readRecent(it), question)) }
    }

    suspend fun rememberArt(question: String, posters: List<String>) {
        store().edit { prefs ->
            val kept = readRecent(prefs).toSet()
            val art = readRecentArt(prefs).filterKeys(kept::contains) + (question to posters.take(RECENT_POSTERS))
            prefs[recentArtKey] = json.encodeToString(art)
        }
    }

    suspend fun forgetRecent(question: String) {
        store().edit { prefs ->
            prefs[recentKey] = json.encodeToString(readRecent(prefs).filterNot { it.equals(question, ignoreCase = true) })
            prefs[recentArtKey] = json.encodeToString(readRecentArt(prefs) - question)
        }
    }

    suspend fun clearRecent() {
        store().edit { prefs ->
            prefs.remove(recentKey)
            prefs.remove(recentArtKey)
        }
    }

    /** False when the row limit is reached. */
    suspend fun save(question: String, name: String, items: List<AskItem>, now: Long = System.currentTimeMillis()): Boolean {
        var saved = false
        store().edit { prefs ->
            val rows = readRows(prefs)
            val existing = rows.firstOrNull { it.question.equals(question, ignoreCase = true) }
            if (existing == null && rows.size >= ASK_MAX_SAVED_ROWS) return@edit
            val row = AskSavedRow(
                id = existing?.id ?: UUID.randomUUID().toString().take(12),
                question = question,
                name = name.ifBlank { question.take(60) },
                refreshedAt = now,
                items = items.map(AskItem::toSaved)
            )
            prefs[rowsKey] = json.encodeToString(rows.filterNot { it.id == row.id } + row)
            saved = true
        }
        return saved
    }

    suspend fun removeRow(id: String) {
        store().edit { prefs -> prefs[rowsKey] = json.encodeToString(readRows(prefs).filterNot { it.id == id }) }
    }

    suspend fun updateRow(id: String, transform: (AskSavedRow) -> AskSavedRow) {
        store().edit { prefs ->
            prefs[rowsKey] = json.encodeToString(readRows(prefs).map { if (it.id == id) transform(it) else it })
        }
    }

    private fun readRows(prefs: Preferences): List<AskSavedRow> =
        prefs[rowsKey]?.let { runCatching { json.decodeFromString<List<AskSavedRow>>(it) }.getOrNull() }.orEmpty()

    private fun readRecentArt(prefs: Preferences): Map<String, List<String>> =
        prefs[recentArtKey]?.let { runCatching { json.decodeFromString<Map<String, List<String>>>(it) }.getOrNull() }.orEmpty()

    private fun readRecent(prefs: Preferences): List<String> =
        prefs[recentKey]?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()
}

/** Home rows written from a sentence. Nothing runs here until a row has been saved. */
@Singleton
class AskCatalogProvider @Inject constructor(
    private val rowsStore: AskRowsStore,
    private val service: dagger.Lazy<AskService>
) : BuiltInCatalogProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = Mutex()

    @Volatile
    private var rowKeys: List<String> = emptyList()

    override val addons: Flow<List<Addon>> =
        if (!ScoutAskFeature.available) flowOf(emptyList())
        else rowsStore.rows.map { rows ->
            rowKeys = rows.map { homeKey(it.id) }
            addonFor(rows)
        }.distinctUntilChanged()

    override val defaultFirstKeys: List<String>
        get() = rowKeys

    override fun handles(baseUrl: String?): Boolean = baseUrl == BASE_URL

    override fun ownsAddon(addonId: String?): Boolean = addonId == ADDON_ID

    private fun addonFor(rows: List<AskSavedRow>): List<Addon> {
        if (rows.isEmpty()) return emptyList()
        return listOf(
            Addon(
                id = ADDON_ID,
                name = ADDON_NAME,
                version = "1",
                description = null,
                logo = null,
                baseUrl = BASE_URL,
                catalogs = rows.map {
                    CatalogDescriptor(type = ContentType.UNKNOWN, rawType = ROW_TYPE, id = it.id, name = it.name, showInHome = true)
                },
                types = listOf(ContentType.MOVIE, ContentType.SERIES),
                resources = listOf(AddonResource(name = "catalog", types = emptyList(), idPrefixes = null))
            )
        )
    }

    override suspend fun catalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        extraArgs: Map<String, String>,
        posterPattern: String
    ): CatalogRow {
        val row = rowsStore.rows.first().firstOrNull { it.id == catalogId }
        if (row != null && row.dueForRefresh(System.currentTimeMillis())) scope.launch { refresh(row) }
        return CatalogRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = ContentType.UNKNOWN,
            rawType = ROW_TYPE,
            items = if (skip > 0) emptyList() else row?.items.orEmpty().map(AskSavedItem::toMeta).withCustomPosterUrls(posterPattern),
            hasMore = false
        )
    }

    private suspend fun refresh(row: AskSavedRow) = refreshing.withLock {
        val now = System.currentTimeMillis()
        rowsStore.updateRow(row.id) { it.copy(attemptedAt = now) }
        val answer = service.get().ask(row.question) as? AskOutcome.Answer ?: return@withLock
        if (answer.items.isEmpty()) return@withLock
        rowsStore.updateRow(row.id) { it.copy(refreshedAt = now, attemptedAt = now, items = answer.items.map(AskItem::toSaved)) }
    }

    companion object {
        const val ADDON_ID = "scout-ask"
        const val ADDON_NAME = "Scout"
        const val BASE_URL = BuiltInCatalogs.BASE_PREFIX + "scout-ask"

        fun homeKey(rowId: String): String = catalogRowLegacyKey(ADDON_ID, ROW_TYPE, rowId)
    }
}
