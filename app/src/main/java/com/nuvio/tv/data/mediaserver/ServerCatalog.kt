package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.poster.CustomPosterUrlResolver.ContentIds
import com.nuvio.tv.core.poster.withCustomPosterUrl
import com.nuvio.tv.core.streams.supportsResource
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class ServerLibraryRef(
    val connection: ServerConnection,
    val library: ServerLibrary,
    val title: String = "${connection.name} · ${library.name}"
)

internal fun serverRowLabels(
    connections: List<ServerConnection>,
    productName: (ServerConnection) -> String
): Map<String, String> {
    val byName = connections.groupBy { it.name.trim().lowercase() }
    return connections.associate { connection ->
        val sameName = byName.getValue(connection.name.trim().lowercase())
        val product = productName(connection)
        val label = when {
            sameName.size == 1 -> connection.name
            sameName.count { productName(it) == product } == 1 -> "$product · ${connection.name}"
            else -> "$product · ${connection.name} (${connection.userName})"
        }
        connection.id to label
    }
}

internal fun ServerTitle.catalogPreview(addons: List<Addon>): MetaPreview {
    if (preview.apiType == ServerMediaKind.COLLECTION.contentType) return preview
    val ids = externalIds.catalogIds()
    val id = addons.firstNotNullOfOrNull { addon ->
        ids.firstOrNull { addon.supportsResource("meta", preview.apiType, it) }
    } ?: ids.firstOrNull { it.startsWith("tmdb:") } ?: return preview
    return preview.copy(id = id, imdbId = externalIds.imdb)
}

internal fun ServerTitle.withPosterPattern(item: MetaPreview, pattern: String): MetaPreview {
    if (pattern.isBlank() || item.apiType == ServerMediaKind.COLLECTION.contentType) return item
    if (!ServerItemRef.isServerId(item.id)) return item.withCustomPosterUrl(pattern)
    val id = externalIds.catalogIds().firstOrNull() ?: return item
    val ids = ContentIds(
        id = id,
        imdbId = externalIds.imdb,
        tmdbId = externalIds.tmdb?.toString(),
        tvdbId = externalIds.tvdb,
        kitsuId = externalIds.kitsu?.toString(),
        anilistId = externalIds.anilist?.toString(),
        malId = externalIds.mal?.toString(),
        anidbId = externalIds.anidb?.toString()
    )
    return item.withCustomPosterUrl(pattern, ids)
}

private fun MetaPreview.withPosterPatternAfterSwap(before: MetaPreview, pattern: String): MetaPreview = when {
    pattern.isBlank() || this === before || ServerItemRef.isServerId(id) -> this
    poster == before.poster -> withCustomPosterUrl(pattern).copy(rawPosterUrl = rawPosterUrl)
    else -> withCustomPosterUrl(pattern)
}

@Singleton
class ServerCatalog @Inject constructor(
    private val repository: ServerRepository,
    private val addonRepository: AddonRepository,
    private val artwork: ServerArtwork
) {
    val detailsLoaded = MutableSharedFlow<ServerItemDetails>(extraBufferCapacity = DETAILS_BUFFER)

    val libraryLoaded = MutableSharedFlow<String>(extraBufferCapacity = DETAILS_BUFFER)

    val addons: Flow<List<Addon>> = repository.uiState
        .map { state ->
            val labels = rowLabels(state.enabledConnections)
            state.enabledConnections.mapNotNull { addonFor(it, labels) }
        }
        .distinctUntilChanged()

    val searchAddons: Flow<List<Addon>> = addons.map { addons ->
        addons.map { addon -> addon.copy(catalogs = addon.catalogs.filter { it.supportsExtra("search") }) }
            .filter { it.catalogs.isNotEmpty() }
    }

    fun libraries(): List<ServerLibraryRef> {
        val connections = repository.enabledConnections()
        val labels = rowLabels(connections)
        return connections.flatMap { connection ->
            connection.selectedLibraries.map { library ->
                ServerLibraryRef(connection, library, "${labels[connection.id] ?: connection.name} · ${library.name}")
            }
        }
    }

    fun titleLibraries(): List<ServerLibraryRef> =
        libraries().filter { it.library.kind != ServerMediaKind.COLLECTION }

    fun sourceLabel(connectionId: String): String =
        repository.connection(connectionId)?.let(repository::sourceLabel).orEmpty()

    suspend fun catalog(
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
        val connectionId = connectionId(addonBaseUrl) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val connection = repository.connection(connectionId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val query = extraArgs["search"]?.trim()?.takeIf { it.isNotEmpty() }
        val page = when {
            catalogId.startsWith(COLLECTION_PREFIX) -> pageOf(connection, skip, posterPattern) {
                repository.call(connection.id) { provider, session ->
                    provider.collectionPage(session, catalogId.removePrefix(COLLECTION_PREFIX), skip, PAGE_SIZE)
                }
            }
            query != null -> searchPage(connection, catalogId, query, skip, posterPattern)
            else -> {
                val library = connection.libraries.firstOrNull { it.id == catalogId }
                    ?: throw ServerException(ServerFailure.NOT_FOUND)
                pageOf(connection, skip, posterPattern) {
                    repository.call(connection.id) { provider, session ->
                        provider.libraryPage(session, library, skip, PAGE_SIZE)
                    }
                }.also { if (skip == 0) libraryLoaded.tryEmit(connection.id) }
            }
        }
        return CatalogRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = ContentType.fromString(type),
            rawType = type,
            items = page.items,
            isLoading = false,
            hasMore = page.nextSkip != null,
            currentPage = skip / PAGE_SIZE,
            supportsSkip = true,
            skipStep = PAGE_SIZE,
            nextSkip = page.nextSkip ?: skip,
            extraArgs = extraArgs
        )
    }

    suspend fun collectionRow(ref: ServerItemRef, posterPattern: String): CatalogRow {
        val connection = repository.connection(ref.connectionId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val name = details(ref).meta.name
        val row = CatalogRow(
            addonId = addonId(connection.id),
            addonName = repository.sourceLabel(connection),
            addonBaseUrl = baseUrl(connection.id),
            catalogId = COLLECTION_PREFIX + ref.itemId,
            catalogName = name,
            type = ContentType.UNKNOWN,
            rawType = ServerMediaKind.COLLECTION.contentType,
            items = emptyList()
        )
        return page(row, skip = 0, posterPattern)
    }

    suspend fun row(connectionId: String, catalogId: String, posterPattern: String): CatalogRow {
        val addon = repository.connection(connectionId)?.let { addonFor(it, rowLabels(repository.enabledConnections())) }
            ?: throw ServerException(ServerFailure.NOT_FOUND)
        val descriptor = addon.catalogs.firstOrNull { it.id == catalogId } ?: throw ServerException(ServerFailure.NOT_FOUND)
        val row = CatalogRow(
            addonId = addon.id,
            addonName = addon.displayName,
            addonBaseUrl = addon.baseUrl,
            catalogId = descriptor.id,
            catalogName = descriptor.name,
            type = descriptor.type,
            rawType = descriptor.apiType,
            items = emptyList()
        )
        return page(row, skip = 0, posterPattern)
    }

    suspend fun page(row: CatalogRow, skip: Int, posterPattern: String): CatalogRow =
        catalog(row.addonBaseUrl, row.addonId, row.addonName, row.catalogId, row.catalogName, row.apiType, skip, row.extraArgs, posterPattern)

    suspend fun details(ref: ServerItemRef): ServerItemDetails =
        repository.call(ref.connectionId) { provider, session -> provider.details(session, ref.itemId) }
            .also { detailsLoaded.tryEmit(it) }

    fun withArtwork(row: CatalogRow, posterPattern: String): CatalogRow {
        val cached = artwork.cached(row.items)
        if (cached === row.items) return row
        val items = cached.mapIndexed { index, item -> item.withPosterPatternAfterSwap(row.items[index], posterPattern) }
        return if (items == row.items) row else row.copy(items = items)
    }

    suspend fun awaitArtwork(row: CatalogRow) {
        artwork.settle(row.items, ARTWORK_SETTLE_MS)
    }

    suspend fun settledArtwork(row: CatalogRow, posterPattern: String): CatalogRow? {
        awaitArtwork(row)
        return withArtwork(row, posterPattern).takeIf { it !== row }
    }

    private fun rowLabels(connections: List<ServerConnection>): Map<String, String> =
        serverRowLabels(connections) { repository.provider(it)?.displayName ?: it.providerId }

    private fun addonFor(connection: ServerConnection, rowLabels: Map<String, String>): Addon? {
        val provider = repository.provider(connection) ?: return null
        val libraries = connection.selectedLibraries
        if (libraries.isEmpty()) return null
        val rowLabel = rowLabels[connection.id] ?: connection.name
        val catalogs = libraries.map { library ->
            CatalogDescriptor(
                type = library.kind.domainType(),
                rawType = library.kind.contentType,
                id = library.id,
                name = "$rowLabel · ${library.name}",
                extra = buildList {
                    add(CatalogExtra(name = "skip"))
                    if (library.kind != ServerMediaKind.COLLECTION && provider.supports(ServerCapability.SEARCH)) {
                        add(CatalogExtra(name = "search"))
                    }
                },
                showInHome = true,
                hasExplicitShowInHome = true
            )
        }
        val label = repository.sourceLabel(connection)
        return Addon(
            id = addonId(connection.id),
            name = provider.displayName,
            displayName = label,
            version = if (connection.useCatalogMetadata) "metadata" else "native",
            description = null,
            logo = null,
            baseUrl = baseUrl(connection.id),
            catalogs = catalogs,
            types = listOf(ContentType.MOVIE, ContentType.SERIES),
            resources = listOf(AddonResource(name = "catalog", types = emptyList(), idPrefixes = null))
        )
    }

    private suspend fun searchPage(
        connection: ServerConnection,
        libraryId: String,
        query: String,
        skip: Int,
        posterPattern: String
    ): Page {
        if (skip > 0) return Page(emptyList(), null)
        val library = connection.libraries.firstOrNull { it.id == libraryId } ?: throw ServerException(ServerFailure.NOT_FOUND)
        val titles = repository.call(connection.id) { provider, session ->
            if (!provider.supports(ServerCapability.SEARCH)) throw ServerException(ServerFailure.UNSUPPORTED)
            provider.search(session, library, query, SEARCH_LIMIT)
        }
        return Page(present(connection, titles, posterPattern), null)
    }

    private suspend fun pageOf(
        connection: ServerConnection,
        skip: Int,
        posterPattern: String,
        load: suspend () -> ServerPage<ServerTitle>
    ): Page {
        val page = load()
        val loaded = skip + page.items.size
        val hasMore = page.items.isNotEmpty() && (page.totalCount?.let { loaded < it } ?: (page.items.size >= PAGE_SIZE))
        return Page(present(connection, page.items, posterPattern), loaded.takeIf { hasMore })
    }

    private suspend fun present(connection: ServerConnection, titles: List<ServerTitle>, posterPattern: String): List<MetaPreview> {
        val items = withAddonArtwork(connection, titles.map(presenter(connection)))
        if (posterPattern.isBlank()) return items
        return titles.zip(items) { title, item -> title.withPosterPattern(item, posterPattern) }
    }

    private suspend fun withAddonArtwork(connection: ServerConnection, items: List<MetaPreview>): List<MetaPreview> =
        if (connection.useCatalogMetadata) artwork.resolve(items, ARTWORK_WAIT_MS) else items

    private suspend fun presenter(connection: ServerConnection): (ServerTitle) -> MetaPreview {
        if (!connection.useCatalogMetadata) return ServerTitle::preview
        val addons = addonRepository.getInstalledAddons().first().enabledAddons()
        return { title -> title.catalogPreview(addons) }
    }

    private class Page(val items: List<MetaPreview>, val nextSkip: Int?)

    companion object {
        const val PAGE_SIZE = 50
        private const val SEARCH_LIMIT = 30
        private const val DETAILS_BUFFER = 16
        private const val ARTWORK_WAIT_MS = 700L
        private const val ARTWORK_SETTLE_MS = 8_000L
        private const val COLLECTION_PREFIX = "collection:"
        private const val BASE_URL_PREFIX = "nuvio-server://"
        private const val ADDON_ID_PREFIX = "server."

        fun isServerAddon(baseUrl: String?): Boolean = baseUrl?.startsWith(BASE_URL_PREFIX) == true

        fun isServerAddonId(addonId: String?): Boolean = addonId?.startsWith(ADDON_ID_PREFIX) == true

        fun isCollection(id: String?, type: String?): Boolean =
            type == ServerMediaKind.COLLECTION.contentType && ServerItemRef.isServerId(id)

        fun isServerKey(key: String): Boolean = key.startsWith(ADDON_ID_PREFIX) || key.startsWith(BASE_URL_PREFIX)

        fun connectionId(baseUrl: String): String? = baseUrl.takeIf(::isServerAddon)?.removePrefix(BASE_URL_PREFIX)

        fun baseUrl(connectionId: String): String = BASE_URL_PREFIX + connectionId

        fun addonId(connectionId: String): String = ADDON_ID_PREFIX + connectionId

        fun connectionIdFromAddonId(addonId: String): String? = addonId.takeIf(::isServerAddonId)?.removePrefix(ADDON_ID_PREFIX)
    }
}
