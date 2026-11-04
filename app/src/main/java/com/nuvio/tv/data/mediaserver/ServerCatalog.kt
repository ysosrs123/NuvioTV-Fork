package com.nuvio.tv.data.mediaserver

import android.content.Context
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.sync.homeCatalogKey
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
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

data class ServerLibraryRef(
    val connection: ServerConnection,
    val library: ServerLibrary,
    val title: String = "${connection.shownName} · ${library.name}"
)

internal fun serverRowLabels(
    connections: List<ServerConnection>,
    productName: (ServerConnection) -> String
): Map<String, String> {
    val byName = connections.groupBy { it.shownName.trim().lowercase() }
    return connections.associate { connection ->
        val name = connection.shownName
        val sameName = byName.getValue(name.trim().lowercase())
        val product = productName(connection)
        val label = when {
            sameName.size == 1 -> name
            sameName.count { productName(it) == product } == 1 -> "$product · $name"
            else -> "$product · $name (${connection.userName})"
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
class ServerCatalog internal constructor(
    private val repository: ServerRepository,
    private val addonRepository: AddonRepository,
    private val artwork: ServerArtwork,
    private val kindName: (ServerMediaKind) -> String = { if (it == ServerMediaKind.SERIES) "Series" else "Movies" },
    private val sortName: (ServerSort) -> String = { if (it == ServerSort.TITLE) "A to Z" else "Newest release" }
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        repository: ServerRepository,
        addonRepository: AddonRepository,
        artwork: ServerArtwork
    ) : this(
        repository = repository,
        addonRepository = addonRepository,
        artwork = artwork,
        kindName = { kind ->
            context.getString(if (kind == ServerMediaKind.SERIES) R.string.type_series_plural else R.string.type_movies)
        },
        sortName = { sort ->
            context.getString(if (sort == ServerSort.TITLE) R.string.server_library_sort_title else R.string.server_library_sort_released)
        }
    )

    val detailsLoaded = MutableSharedFlow<ServerItemDetails>(extraBufferCapacity = DETAILS_BUFFER)

    private val refreshedHomeRows = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val searchLock = Mutex()
    private val serverSearches = HashMap<String, ServerSearch>()
    internal var searchWaitMs = SEARCH_WAIT_MS
    private val genreCache = java.util.concurrent.ConcurrentHashMap<String, CachedGenres>()
    private val genreLoads = java.util.concurrent.ConcurrentHashMap<String, Deferred<List<ServerGenre>>>()
    internal var genreWaitMs = GENRE_WAIT_MS
    internal var now: () -> Long = System::currentTimeMillis

    val libraryLoaded = MutableSharedFlow<String>(extraBufferCapacity = DETAILS_BUFFER)

    val addons: Flow<List<Addon>> = repository.uiState
        .map { state ->
            val labels = rowLabels(state.enabledConnections)
            state.enabledConnections.mapNotNull { addonFor(it, labels) }
        }
        .distinctUntilChanged()

    /** Search rows, one per type and server, and the movie and show libraries Discover browses, with their genres. */
    val searchAddons: Flow<List<Addon>> = addons.map { addons ->
        withGenres(
            addons.map { addon ->
                addon.copy(
                    catalogs = addon.catalogs.filter {
                        !it.id.startsWith(HOME_ROW_PREFIX) && ServerMediaKind.fromContentType(it.apiType) != null
                    }
                )
            }.filter { it.catalogs.isNotEmpty() }
        )
    }

    fun libraries(): List<ServerLibraryRef> {
        val connections = repository.enabledConnections()
        val labels = rowLabels(connections)
        return connections.flatMap { connection ->
            connection.selectedLibraries.map { library ->
                ServerLibraryRef(connection, library, "${labels[connection.id] ?: connection.shownName} · ${library.name}")
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
        if (skip == 0 && query == null) refreshHomeRowsOnce(connection)
        val page = when {
            catalogId.startsWith(HOME_ROW_PREFIX) -> homeRowPage(connection, catalogId.removePrefix(HOME_ROW_PREFIX), skip, posterPattern)
            catalogId.startsWith(COLLECTION_PREFIX) -> pageOf(connection, skip, posterPattern) {
                repository.call(connection.id) { provider, session ->
                    provider.collectionPage(session, catalogId.removePrefix(COLLECTION_PREFIX), skip, PAGE_SIZE)
                }
            }
            catalogId.startsWith(SEARCH_PREFIX) ->
                searchPage(connection, catalogId.removePrefix(SEARCH_PREFIX), query, skip, posterPattern)
            else -> {
                val (sort, libraryId) = libraryCatalog(catalogId)
                val library = connection.libraries.firstOrNull { it.id == libraryId }
                    ?: throw ServerException(ServerFailure.NOT_FOUND)
                val genre = extraArgs["genre"]?.trim()?.takeIf { it.isNotEmpty() }
                val genreId = genre?.let { name -> genres(connection, library).firstOrNull { it.name.equals(name, ignoreCase = true) }?.id }
                val filter = ServerLibraryFilter.of(extraArgs[LETTER_EXTRA], extraArgs[UNWATCHED_EXTRA])
                if (genre != null && genreId == null) {
                    Page(emptyList(), null)
                } else {
                    pageOf(connection, skip, posterPattern) {
                        repository.call(connection.id) { provider, session ->
                            provider.libraryPage(session, library, skip, PAGE_SIZE, sort, genreId, filter)
                        }
                    }.also { if (skip == 0 && genre == null && filter == ServerLibraryFilter.NONE) libraryLoaded.tryEmit(connection.id) }
                }
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

    val artworkUpdates: StateFlow<Long> get() = artwork.updates

    /** Rows as (row addon base URL, titles about to be shown), most wanted first. */
    fun requestArtwork(rows: List<Pair<String?, List<MetaPreview>>>) {
        val items = rows.flatMap { (baseUrl, titles) ->
            val connection = baseUrl?.let(::connectionId)?.let(repository::connection)
            if (connection?.useCatalogMetadata == true) titles else emptyList()
        }
        if (items.isNotEmpty()) artwork.request(items)
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
        val homeRows = connection.homeRows.takeIf { connection.showHomeRows }.orEmpty()
        val searchKinds = SEARCH_KINDS.filter { kind ->
            provider.supports(ServerCapability.SEARCH) && connection.librariesHolding(kind).isNotEmpty()
        }
        if (libraries.isEmpty() && homeRows.isEmpty() && searchKinds.isEmpty()) return null
        val rowLabel = rowLabels[connection.id] ?: connection.shownName
        val homeCatalogs = homeRows.map { row ->
            CatalogDescriptor(
                type = row.kind.domainType(),
                rawType = row.kind.contentType,
                id = HOME_ROW_PREFIX + row.id,
                name = "$rowLabel · ${row.title}",
                extra = emptyList(),
                showInHome = true,
                hasExplicitShowInHome = true
            )
        }
        val catalogs = homeCatalogs + libraries.flatMap { library ->
            val titles = library.kind == ServerMediaKind.MOVIE || library.kind == ServerMediaKind.SERIES
            val sorts = if (titles) SORTED_VERSIONS else emptyList()
            (listOf<ServerSort?>(null) + sorts).map { sort ->
                CatalogDescriptor(
                    type = library.kind.domainType(),
                    rawType = library.kind.contentType,
                    id = if (sort == null) library.id else sortedCatalogId(sort, library.id),
                    name = listOfNotNull(rowLabel, library.name, sort?.let(sortName)).joinToString(" · "),
                    extra = listOf(CatalogExtra(name = "skip")) + libraryFilterExtras(titles, sort),
                    showInHome = true,
                    hasExplicitShowInHome = true
                )
            }
        }
        val searches = searchKinds.map { kind ->
            CatalogDescriptor(
                type = kind.domainType(),
                rawType = kind.contentType,
                id = SEARCH_PREFIX + kind.contentType,
                name = "$rowLabel · ${kindName(kind)}",
                extra = listOf(CatalogExtra(name = "search", isRequired = true))
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
            catalogs = catalogs + searches,
            types = listOf(ContentType.MOVIE, ContentType.SERIES),
            resources = listOf(AddonResource(name = "catalog", types = emptyList(), idPrefixes = null))
        )
    }

    /**
     * The genres [library] can be narrowed to, read from the server at most every [GENRE_TTL_MS]
     * and again after [GENRE_RETRY_MS] when the server could not answer.
     */
    suspend fun genres(connection: ServerConnection, library: ServerLibrary): List<ServerGenre> {
        if (library.kind != ServerMediaKind.MOVIE && library.kind != ServerMediaKind.SERIES) return emptyList()
        val key = genreKey(connection.id, library.id)
        freshGenres(key)?.let { return it }
        val load = genreLoads.computeIfAbsent(key) {
            refreshScope.async(start = CoroutineStart.LAZY) { loadGenres(connection.id, library, key) }
        }
        load.invokeOnCompletion { genreLoads.remove(key, load) }
        load.start()
        return load.await()
    }

    /** Per genre name: how many titles it holds and a picture of its newest title; genres the server can't answer for are left out. */
    suspend fun genreSummaries(connectionId: String, libraryId: String): Map<String, GenreSummary> {
        val connection = repository.connection(connectionId) ?: return emptyMap()
        val library = connection.libraries.firstOrNull { it.id == libraryId } ?: return emptyMap()
        val gate = Semaphore(GENRE_COUNTS_AT_ONCE)
        val pages = coroutineScope {
            genres(connection, library).map { genre ->
                async {
                    gate.withPermit {
                        try {
                            repository.call(connection.id) { provider, session ->
                                provider.libraryPage(session, library, 0, 1, ServerSort.ADDED, genre.id)
                            }?.let { genre.name to it }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val firsts = pages.mapNotNull { (_, page) -> page.items.firstOrNull() }
        val shown = present(connection, firsts, "").associateBy { it.id }
        return pages.associate { (name, page) ->
            val first = page.items.firstOrNull()?.let { shown[it.preview.id] ?: it.preview }
            val picture = listOf(first?.background, first?.poster).firstOrNull { shareableArtwork(it, connection) }
            name to GenreSummary(page.totalCount, picture?.trim())
        }
    }

    private fun freshGenres(key: String): List<ServerGenre>? =
        genreCache[key]?.takeIf { now() - it.readAtMs < if (it.failed) GENRE_RETRY_MS else GENRE_TTL_MS }?.genres

    private suspend fun loadGenres(connectionId: String, library: ServerLibrary, key: String): List<ServerGenre> {
        val genres = try {
            repository.call(connectionId) { provider, session -> provider.libraryGenres(session, library) }
                .distinctBy { it.name.lowercase() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Library genres not read: ${error.forLog()}")
            genreCache[key] = CachedGenres(emptyList(), now(), failed = true)
            return emptyList()
        }
        genreCache[key] = CachedGenres(genres, now(), failed = false)
        return genres
    }

    /** Adds each library's genres as an optional genre choice; a library without genres offers none. */
    private suspend fun withGenres(addons: List<Addon>): List<Addon> {
        val connections = repository.enabledConnections().associateBy { it.id }
        val wanted = addons.flatMap { addon ->
            val connection = connectionIdFromAddonId(addon.id)?.let(connections::get) ?: return@flatMap emptyList()
            addon.catalogs.mapNotNull { catalog -> libraryOf(connection, catalog.id)?.let { connection to it } }
        }.distinctBy { (connection, library) -> genreKey(connection.id, library.id) }
        if (wanted.isEmpty()) return addons
        withTimeoutOrNull(genreWaitMs) {
            coroutineScope {
                wanted.map { (connection, library) -> async { genres(connection, library) } }.awaitAll()
            }
        }
        return addons.map { addon ->
            val connection = connectionIdFromAddonId(addon.id)?.let(connections::get) ?: return@map addon
            addon.copy(
                catalogs = addon.catalogs.map { catalog ->
                    val library = libraryOf(connection, catalog.id)
                    val genres = library?.let { genreCache[genreKey(connection.id, it.id)]?.genres }.orEmpty()
                    if (genres.isEmpty()) catalog
                    else catalog.copy(extra = catalog.extra + CatalogExtra(name = "genre", options = genres.map { it.name }))
                }
            )
        }
    }

    private fun libraryOf(connection: ServerConnection, catalogId: String): ServerLibrary? {
        if (catalogId.startsWith(HOME_ROW_PREFIX) || catalogId.startsWith(COLLECTION_PREFIX) || catalogId.startsWith(SEARCH_PREFIX)) return null
        val libraryId = libraryCatalog(catalogId).second
        return connection.libraries.firstOrNull { it.id == libraryId }
    }

    private class CachedGenres(val genres: List<ServerGenre>, val readAtMs: Long, val failed: Boolean)

    /** Once per app run the server's own rows are read again, also when none were read at sign-in. */
    private fun refreshHomeRowsOnce(connection: ServerConnection) {
        if (!connection.showHomeRows) return
        if (repository.provider(connection)?.capabilities?.contains(ServerCapability.HOME_ROWS) != true) return
        if (!refreshedHomeRows.add(connection.id)) return
        refreshScope.launch {
            try {
                repository.refreshHomeRows(connection.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Home rows not read again: ${error.forLog()}")
                if ((error as? ServerException)?.failure == ServerFailure.INCOMPLETE) refreshedHomeRows.remove(connection.id)
            }
        }
    }

    private suspend fun homeRowPage(connection: ServerConnection, rowId: String, skip: Int, posterPattern: String): Page {
        if (skip > 0) return Page(emptyList(), null)
        val titles = repository.call(connection.id) { provider, session -> provider.homeRowItems(session, rowId) }
        return Page(present(connection, titles, posterPattern), null)
    }

    private suspend fun searchPage(
        connection: ServerConnection,
        contentType: String,
        query: String?,
        skip: Int,
        posterPattern: String
    ): Page {
        val kind = ServerMediaKind.fromContentType(contentType)
        if (query == null || kind == null || skip > 0) return Page(emptyList(), null)
        val titles = serverSearch(connection, kind, query)
        return Page(present(connection, titles, posterPattern), null)
    }

    /**
     * One search per type on a server at a time, across that type's libraries. A server keeps
     * working on a search the app has stopped waiting for, so a newer word waits for it, up to
     * [searchWaitMs], instead of piling up behind it. The same word already running is shared. A
     * search nobody waits for any more is dropped once that wait is over.
     */
    private suspend fun serverSearch(connection: ServerConnection, kind: ServerMediaKind, query: String): List<ServerTitle> {
        val key = "${connection.id}/${kind.contentType}"
        while (true) {
            val search = searchLock.withLock {
                val running = serverSearches[key]?.takeIf { it.result.isActive && (it.matches(query) || it.waitLeftMs() > 0) }
                    ?: ServerSearch(query, searchWaitMs, startSearch(connection, kind, query)).also { serverSearches[key] = it }
                running.also { if (it.matches(query)) it.waiters.incrementAndGet() }
            }
            if (!search.matches(query)) {
                withTimeoutOrNull(search.waitLeftMs()) { search.result.join() }
                continue
            }
            try {
                return search.result.await()
            } finally {
                if (search.waiters.decrementAndGet() == 0 && search.result.isActive) dropWhenUnwanted(search)
            }
        }
    }

    private fun dropWhenUnwanted(search: ServerSearch) {
        refreshScope.launch {
            delay(search.waitLeftMs())
            searchLock.withLock { if (search.waiters.get() == 0) search.result.cancel() }
        }
    }

    private fun startSearch(connection: ServerConnection, kind: ServerMediaKind, query: String): Deferred<List<ServerTitle>> =
        refreshScope.async {
            repository.call(connection.id) { provider, session ->
                if (!provider.supports(ServerCapability.SEARCH)) throw ServerException(ServerFailure.UNSUPPORTED)
                val libraries = session.connection.librariesHolding(kind)
                if (libraries.isEmpty()) emptyList() else provider.search(session, kind, libraries, query, SEARCH_LIMIT)
            }
        }

    private class ServerSearch(val query: String, private val waitMs: Long, val result: Deferred<List<ServerTitle>>) {
        private val started = TimeSource.Monotonic.markNow()

        val waiters = java.util.concurrent.atomic.AtomicInteger()

        fun matches(other: String): Boolean = query.equals(other, ignoreCase = true)

        fun waitLeftMs(): Long = waitMs - started.elapsedNow().inWholeMilliseconds
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
        when {
            !connection.useCatalogMetadata -> items
            currentCoroutineContext()[ArtworkOnDemand.Key] != null -> artwork.cached(items)
            else -> artwork.resolve(items, ARTWORK_WAIT_MS)
        }

    private suspend fun presenter(connection: ServerConnection): (ServerTitle) -> MetaPreview {
        if (!connection.useCatalogMetadata) return ServerTitle::preview
        val addons = addonRepository.getInstalledAddons().first().enabledAddons()
        return { title -> title.catalogPreview(addons) }
    }

    private class Page(val items: List<MetaPreview>, val nextSkip: Int?)

    companion object {
        private const val TAG = "ServerCatalog"
        const val PAGE_SIZE = 50
        private const val SEARCH_LIMIT = 30
        private const val SEARCH_WAIT_MS = 6_000L
        private const val DETAILS_BUFFER = 16
        private const val ARTWORK_WAIT_MS = 700L
        private const val ARTWORK_SETTLE_MS = 8_000L
        private const val COLLECTION_PREFIX = "collection:"
        private const val HOME_ROW_PREFIX = "home:"
        private const val SEARCH_PREFIX = "search."
        private val SEARCH_KINDS = listOf(ServerMediaKind.MOVIE, ServerMediaKind.SERIES)
        private const val SORTED_PREFIX = "sorted."
        private val SORTED_VERSIONS = listOf(ServerSort.TITLE, ServerSort.RELEASED)
        private const val GENRE_WAIT_MS = 3_000L
        private const val GENRE_TTL_MS = 6 * 60 * 60 * 1000L
        private const val GENRE_RETRY_MS = 10 * 60 * 1000L
        private const val GENRE_COUNTS_AT_ONCE = 4
        private val PRIVATE_QUERY = Regex("token|key|auth|sig|session|password", RegexOption.IGNORE_CASE)

        /** A picture link that may be saved into a collection, which syncs: public, not from the server, nothing secret in it. */
        fun shareableArtwork(url: String?, connection: ServerConnection): Boolean {
            if (url.isNullOrBlank()) return false
            val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return false
            if (uri.userInfo != null) return false
            val serverHosts = listOfNotNull(connection.address, connection.alternateAddress, connection.lastGoodAddress)
                .mapNotNull { runCatching { java.net.URI(it.trim()).host }.getOrNull()?.lowercase() }
            if (uri.host.lowercase() in serverHosts) return false
            val names = uri.rawQuery?.split('&')?.map { it.substringBefore('=') }.orEmpty()
            return names.none { PRIVATE_QUERY.containsMatchIn(it) }
        }

        const val LETTER_EXTRA = "letter"
        const val UNWATCHED_EXTRA = "unwatched"

        /** Discover's letter choice on the A to Z version, and the unwatched choice on every version of a title library. */
        internal fun libraryFilterExtras(titles: Boolean, sort: ServerSort?): List<CatalogExtra> {
            if (!titles) return emptyList()
            val letter = CatalogExtra(name = LETTER_EXTRA, options = ServerLibraryFilter.LETTERS.map { it.toString() })
            val unwatched = CatalogExtra(name = UNWATCHED_EXTRA, options = listOf("true"))
            return if (sort == ServerSort.TITLE) listOf(letter, unwatched) else listOf(unwatched)
        }

        private const val BASE_URL_PREFIX = "nuvio-server://"
        private const val ADDON_ID_PREFIX = "server."

        private fun genreKey(connectionId: String, libraryId: String) = "$connectionId/$libraryId"

        internal fun sortedCatalogId(sort: ServerSort, libraryId: String): String = "$SORTED_PREFIX${sort.key}:$libraryId"

        /** The order and library a library catalog id stands for; a plain library id is the added order. */
        internal fun libraryCatalog(catalogId: String): Pair<ServerSort, String> {
            if (!catalogId.startsWith(SORTED_PREFIX)) return ServerSort.ADDED to catalogId
            val rest = catalogId.removePrefix(SORTED_PREFIX)
            val sort = ServerSort.fromKey(rest.substringBefore(':')) ?: return ServerSort.ADDED to catalogId
            return sort to rest.substringAfter(':')
        }

        /** Home keys of the ready-sorted library versions, which stay off Home until switched on. */
        fun hiddenByDefaultKeys(addons: List<Addon>): List<String> = addons
            .filter { isServerAddonId(it.id) }
            .flatMap { addon ->
                addon.catalogs.filter { it.id.startsWith(SORTED_PREFIX) }.map { homeCatalogKey(addon.id, it.apiType, it.id) }
            }

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
