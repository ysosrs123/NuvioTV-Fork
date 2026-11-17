package com.nuvio.tv.ui.components.posteroptions

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingMembershipApplyResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The id and type a poster's title is saved under: the same ones the details page saves it
 * under. Anime catalogues hand out mal:, anilist: or kitsu: ids and an "anime" type, while the
 * meta add-on answers with its own id and "series", so writing the poster's own id gave every
 * such title a second library entry and a second rating.
 */
@Singleton
class PosterTitleIdentity(
    private val metaRepository: MetaRepository,
    private val tmdbService: TmdbService,
    private val preferExternalMeta: () -> Boolean
) {
    @Inject
    constructor(
        metaRepository: MetaRepository,
        tmdbService: TmdbService,
        layoutPreferenceDataStore: LayoutPreferenceDataStore
    ) : this(metaRepository, tmdbService, { layoutPreferenceDataStore.preferExternalMetaAddonDetail.value })

    private val known = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    suspend fun resolve(item: MetaPreview, addonBaseUrl: String?): MetaPreview {
        if (!needsLookup(item)) return item
        val key = "${item.apiType.lowercase()}|${item.id}|${addonBaseUrl.orEmpty()}"
        known[key]?.let { (id, type) -> return item.withIdentity(id, type) }

        val tmdbNumber = parseContentIds(item.id).tmdb ?: item.id.toIntOrNull()
        val identity = if (tmdbNumber != null) {
            val mediaType = if (isSeriesType(item.apiType)) "tv" else "movie"
            runCatching { tmdbService.tmdbToImdb(tmdbNumber, mediaType) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { it to item.apiType }
        } else {
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { detailsPageMeta(item, addonBaseUrl) }
                ?.takeIf { it.id.isNotBlank() }
                ?.let { it.id to it.apiType }
        } ?: return item

        if (known.size >= MAX_KNOWN) known.clear()
        known[key] = identity
        known["${identity.second.lowercase()}|${identity.first}|${addonBaseUrl.orEmpty()}"] = identity
        return item.withIdentity(identity.first, identity.second)
    }

    /** Same order the details page asks in, so both land on the same meta. */
    private suspend fun detailsPageMeta(item: MetaPreview, addonBaseUrl: String?): Meta? {
        val preferred = addonBaseUrl?.takeIf { it.isNotBlank() }
        suspend fun fromCatalogAddon(): Meta? = preferred?.let { baseUrl ->
            metaRepository.getMeta(addonBaseUrl = baseUrl, type = item.apiType, id = item.id)
                .first { it !is NetworkResult.Loading }
                .successData()
        }
        suspend fun fromAllAddons(): Meta? =
            metaRepository.getMetaFromAllAddons(type = item.apiType, id = item.id)
                .first { it !is NetworkResult.Loading }
                .successData()
        return runCatching {
            if (runCatching(preferExternalMeta).getOrDefault(false)) {
                fromAllAddons() ?: fromCatalogAddon()
            } else {
                fromCatalogAddon() ?: fromAllAddons()
            }
        }.getOrNull()
    }

    companion object {
        private const val LOOKUP_TIMEOUT_MS = 6_000L
        private const val MAX_KNOWN = 300

        internal fun needsLookup(item: MetaPreview): Boolean {
            if (item.id.isBlank() || ServerItemRef.isServerId(item.id)) return false
            val plainType = item.apiType.equals("movie", ignoreCase = true) ||
                item.apiType.equals("series", ignoreCase = true)
            return !(item.id.startsWith("tt") && plainType)
        }

        private fun isSeriesType(type: String): Boolean =
            type.equals("series", ignoreCase = true) ||
                type.equals("tv", ignoreCase = true) ||
                type.equals("anime", ignoreCase = true)

        private fun NetworkResult<Meta>.successData(): Meta? = when (this) {
            is NetworkResult.Success -> data
            else -> null
        }

        private fun MetaPreview.withIdentity(id: String, type: String): MetaPreview =
            if (id == this.id && type == apiType) this
            else copy(id = id, type = ContentType.fromString(type), rawType = type)
    }
}

/** Another id and type the same title may already be saved under in the local library. */
data class TitleAlias(val id: String, val type: String) {
    fun sameAs(itemId: String, itemType: String): Boolean =
        id == itemId && type.equals(itemType, ignoreCase = true)

    companion object {
        fun between(saved: MetaPreview, shown: MetaPreview): TitleAlias? =
            of(shown.id, shown.apiType)?.takeUnless { it.sameAs(saved.id, saved.apiType) }

        fun of(id: String?, type: String?): TitleAlias? =
            if (id.isNullOrBlank() || type.isNullOrBlank()) null else TitleAlias(id, type)
    }
}

/**
 * In the library under its own id or under the alias. Trackers match their entries by every id
 * they know, so only the local library needs the alias.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun LibraryRepository.isInLibraryOrAlias(itemId: String, itemType: String, alias: TitleAlias?): Flow<Boolean> {
    if (alias == null || alias.sameAs(itemId, itemType)) return isInLibrary(itemId, itemType)
    return sourceMode.flatMapLatest { mode ->
        if (mode != LibrarySourceMode.LOCAL) {
            isInLibrary(itemId, itemType)
        } else {
            combine(isInLibrary(itemId, itemType), isInLibrary(alias.id, alias.type)) { own, old -> own || old }
        }
    }.distinctUntilChanged()
}

/**
 * The default toggle, merging a local entry saved under the alias: when either entry is there the
 * press removes both, otherwise it adds the title under its own id only.
 */
suspend fun LibraryRepository.toggleDefaultMerging(
    input: LibraryEntryInput,
    alias: TitleAlias?
): TrackingMembershipApplyResult {
    if (alias == null || alias.sameAs(input.itemId, input.itemType)) return toggleDefault(input)
    if (sourceMode.first() != LibrarySourceMode.LOCAL) return toggleDefault(input)
    if (!isInLibrary(alias.id, alias.type).first()) return toggleDefault(input)
    val ownSaved = isInLibrary(input.itemId, input.itemType).first()
    toggleDefault(input.copy(itemId = alias.id, itemType = alias.type))
    if (ownSaved) toggleDefault(input)
    return TrackingMembershipApplyResult(added = false)
}
