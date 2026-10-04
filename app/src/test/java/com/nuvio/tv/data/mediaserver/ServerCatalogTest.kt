package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCatalogTest {
    private val installedAddons = MutableStateFlow<List<Addon>>(emptyList())
    private val addonRepository = mockk<AddonRepository> {
        every { getInstalledAddons() } returns installedAddons
    }

    private fun catalog(
        provider: FakeServerProvider = FakeServerProvider(),
        libraries: List<ServerLibrary> = listOf(FakeServerProvider.MOVIE_LIBRARY, FakeServerProvider.SERIES_LIBRARY),
        metaRepository: FakeMetaRepository = FakeMetaRepository()
    ): Triple<ServerCatalog, ServerRepository, ServerConnection> {
        val (repository, connection) = fakeServerRepository(provider, libraries = libraries)
        return Triple(ServerCatalog(repository, addonRepository, serverArtwork(metaRepository)), repository, connection)
    }

    private suspend fun ServerCatalog.page(
        connection: ServerConnection,
        catalogId: String,
        skip: Int,
        extra: Map<String, String> = emptyMap(),
        posterPattern: String = ""
    ) =
        catalog(
            addonBaseUrl = ServerCatalog.baseUrl(connection.id),
            addonId = ServerCatalog.addonId(connection.id),
            addonName = "Fake · Box",
            catalogId = catalogId,
            catalogName = "Box · Movies",
            type = "movie",
            skip = skip,
            extraArgs = extra,
            posterPattern = posterPattern
        )

    @Test
    fun paginatesLibraryWithOpaqueNumericIds() = runTest {
        val (catalog, _, connection) = catalog()

        val first = catalog.page(connection, FakeServerProvider.MOVIE_LIBRARY.id, skip = 0)
        assertEquals(ServerCatalog.PAGE_SIZE, first.items.size)
        assertTrue(first.hasMore)
        assertEquals(ServerCatalog.PAGE_SIZE, first.nextSkip)
        assertEquals(ServerItemRef(connection.id, "0"), ServerItemRef.parse(first.items.first().id))

        val last = catalog.page(connection, FakeServerProvider.MOVIE_LIBRARY.id, skip = 100)
        assertEquals(20, last.items.size)
        assertFalse(last.hasMore)
    }

    @Test
    fun exposesSelectedLibrariesAsAttributedAddonRows() = runTest {
        val (catalog, repository, connection) = catalog()
        repository.setLibrarySelected(connection.id, FakeServerProvider.SERIES_LIBRARY.id, false)

        val addon = catalog.addons.first().single()
        assertEquals(ServerCatalog.addonId(connection.id), addon.id)
        assertEquals("Fake · Box", addon.displayName)
        assertTrue(ServerCatalog.isServerAddon(addon.baseUrl))
        assertEquals(listOf("10"), addon.catalogs.map { it.id })
        assertEquals("Box · Movies", addon.catalogs.last().name)
        assertEquals(listOf("10"), catalog.searchAddons.first().single().catalogs.map { it.id })
    }

    @Test
    fun serversWithTheSameNameGetTheProductInFront() {
        fun connection(id: String, providerId: String, name: String, user: String = "viewer") = ServerConnection(
            id = id,
            providerId = providerId,
            name = name,
            address = "https://$id.example",
            remoteServerId = "s-$id",
            remoteUserId = "u-$id",
            userName = user,
            credentialRef = "k-$id"
        )
        val product: (ServerConnection) -> String = { it.providerId.replaceFirstChar(Char::uppercase) }

        assertEquals(
            mapOf("a" to "Den", "b" to "Attic"),
            serverRowLabels(listOf(connection("a", "jellyfin", "Den"), connection("b", "emby", "Attic")), product)
        )
        assertEquals(
            mapOf("a" to "Jellyfin · Home", "b" to "Emby · home", "c" to "Attic"),
            serverRowLabels(
                listOf(connection("a", "jellyfin", "Home"), connection("b", "emby", "home"), connection("c", "emby", "Attic")),
                product
            )
        )
        assertEquals(
            mapOf("a" to "Jellyfin · Home (paul)", "b" to "Jellyfin · Home (kids)"),
            serverRowLabels(
                listOf(connection("a", "jellyfin", "Home", user = "paul"), connection("b", "jellyfin", "Home", user = "kids")),
                product
            )
        )
    }

    @Test
    fun rowsOfSameNamedServersCarryTheProduct() = runTest {
        val (catalog, repository, connection) = catalog()
        repository.store(connection.copy(id = "cother", credentialRef = "kother", remoteServerId = "server-2"), token = "token")

        val names = catalog.addons.first().flatMap { addon -> addon.catalogs.map { it.name } }

        assertTrue(names.contains("Fake · Box (viewer) · Movies"))
        assertTrue(names.none { it == "Box · Movies" })
        assertTrue(catalog.libraries().all { it.title.startsWith("Fake · Box (viewer) · ") })
        assertEquals("Fake · Box (viewer) · Movies", catalog.row("cother", FakeServerProvider.MOVIE_LIBRARY.id, "").catalogName)
    }

    @Test
    fun catalogMetadataChangesTheLoadSignature() = runTest {
        val (catalog, repository, connection) = catalog()
        val native = catalog.addons.first().single().version

        repository.setCatalogMetadata(connection.id, true)

        assertTrue(native != catalog.addons.first().single().version)
    }

    @Test
    fun disabledConnectionsProvideNoRows() = runTest {
        val (catalog, repository, connection) = catalog()
        repository.setEnabled(connection.id, false)

        assertTrue(catalog.addons.first().isEmpty())
        assertTrue(catalog.libraries().isEmpty())
    }

    @Test
    fun searchesThroughTheLibraryRows() = runTest {
        val (catalog, _, connection) = catalog()

        val search = catalog.page(connection, FakeServerProvider.MOVIE_LIBRARY.id, skip = 0, extra = mapOf("search" to "item"))
        assertEquals(listOf(ServerItemRef(connection.id, "7").encode()), search.items.map { it.id })
        assertFalse(search.hasMore)
    }

    @Test
    fun serversHaveNoResumeRowOfTheirOwn() = runTest {
        val (catalog, repository, connection) = catalog()

        assertTrue(catalog.addons.first().single().catalogs.none { it.id == "resume" })
        repository.setImportContinueWatching(connection.id, false)
        assertTrue(catalog.addons.first().single().catalogs.none { it.id == "resume" })

        val failure = runCatching { catalog.row(connection.id, "resume", "") }.exceptionOrNull()
        assertEquals(ServerFailure.NOT_FOUND, failure?.serverFailure())
    }

    @Test
    fun loadingTheFirstLibraryPageAnnouncesTheConnection() = runTest {
        val (catalog, _, connection) = catalog()
        val announced = mutableListOf<String>()
        val job = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            catalog.libraryLoaded.collect { announced += it }
        }

        catalog.page(connection, FakeServerProvider.MOVIE_LIBRARY.id, skip = 0)
        catalog.page(connection, FakeServerProvider.MOVIE_LIBRARY.id, skip = 50)
        catalog.page(connection, FakeServerProvider.MOVIE_LIBRARY.id, skip = 0, extra = mapOf("search" to "item"))

        assertEquals(listOf(connection.id), announced)
        job.cancel()
    }

    @Test
    fun collectionCardsOpenTheirContentsAsACatalog() = runTest {
        val (catalog, _, connection) = catalog(
            libraries = listOf(FakeServerProvider.MOVIE_LIBRARY, FakeServerProvider.COLLECTION_LIBRARY)
        )
        assertTrue(catalog.titleLibraries().none { it.library.kind == ServerMediaKind.COLLECTION })
        assertTrue(catalog.searchAddons.first().single().catalogs.none { it.id == FakeServerProvider.COLLECTION_LIBRARY.id })

        val ref = ServerItemRef(connection.id, "c-popular")
        assertTrue(ServerCatalog.isCollection(ref.encode(), "collection"))
        assertFalse(ServerCatalog.isCollection(ref.encode(), "movie"))

        val row = catalog.collectionRow(ref, "")
        assertEquals("collection:c-popular", row.catalogId)
        assertEquals("Item c-popular", row.catalogName)
        assertEquals(listOf("movie", "series"), row.items.map { it.apiType })
        assertFalse(row.hasMore)
    }

    @Test
    fun catalogMetadataUsesCompatibleIdsOnly() = runTest {
        val provider = FakeServerProvider().apply {
            indexedIds["0"] = TrackingExternalIds(imdb = "tt0111161", tmdb = 278)
            indexedIds["1"] = TrackingExternalIds(tmdb = 550)
        }
        val (catalog, repository, connection) = catalog(provider)

        val native = catalog.page(connection, FakeServerProvider.MOVIE_LIBRARY.id, skip = 0).items.take(3)
        assertTrue(native.all { ServerItemRef.isServerId(it.id) })

        repository.setCatalogMetadata(connection.id, true)
        val mapped = catalog.page(connection, FakeServerProvider.MOVIE_LIBRARY.id, skip = 0).items.take(3)
        assertEquals(listOf("tmdb:278", "tmdb:550", ServerItemRef(connection.id, "2").encode()), mapped.map { it.id })
        assertEquals("tt0111161", mapped.first().imdbId)
        assertEquals("Item 0", mapped.first().name)
    }

    @Test
    fun addonMetadataBringsAddonArtworkToServerRows() = runTest {
        val provider = FakeServerProvider().apply {
            indexedIds["0"] = TrackingExternalIds(imdb = "tt0111161", tmdb = 278)
            indexedIds["1"] = TrackingExternalIds(tmdb = 550)
        }
        val metaRepository = FakeMetaRepository(mapOf("tmdb:278" to addonMeta("tmdb:278", "The Shawshank Redemption")))
        val (catalog, repository, connection) = catalog(provider, metaRepository = metaRepository)

        val native = catalog.page(connection, FakeServerProvider.MOVIE_LIBRARY.id, skip = 0).items.first()
        assertNull(native.poster)
        assertEquals("Item 0", native.name)
        assertTrue(metaRepository.requested.isEmpty())

        repository.setCatalogMetadata(connection.id, true)
        val items = catalog.page(connection, FakeServerProvider.MOVIE_LIBRARY.id, skip = 0).items
        assertEquals("tmdb:278", items[0].id)
        assertEquals("tt0111161", items[0].imdbId)
        assertEquals("https://addon.example/tmdb:278/poster.jpg", items[0].poster)
        assertEquals("https://addon.example/tmdb:278/background.jpg", items[0].background)
        assertEquals("The Shawshank Redemption", items[0].name)
        assertEquals("tmdb:550", items[1].id)
        assertNull(items[1].poster)
        assertEquals("Item 1", items[1].name)
        assertNull(items[2].poster)
        assertEquals(listOf("tmdb:278", "tmdb:550"), metaRepository.requested)

        repository.setCatalogMetadata(connection.id, false)
        val back = catalog.page(connection, FakeServerProvider.MOVIE_LIBRARY.id, skip = 0).items.first()
        assertTrue(ServerItemRef.isServerId(back.id))
        assertNull(back.poster)
        assertEquals("Item 0", back.name)
    }

    @Test
    fun settledArtworkOnlyAnswersWhenARowChanges() = runTest {
        val provider = FakeServerProvider().apply { indexedIds["0"] = TrackingExternalIds(tmdb = 278) }
        val metaRepository = FakeMetaRepository(cachedMetas = mapOf("tmdb:278" to addonMeta("tmdb:278")))
        val (catalog, repository, connection) = catalog(provider, metaRepository = metaRepository)

        val native = catalog.row(connection.id, FakeServerProvider.MOVIE_LIBRARY.id, "")
        assertNull(catalog.settledArtwork(native, ""))

        repository.setCatalogMetadata(connection.id, true)
        val row = catalog.row(connection.id, FakeServerProvider.MOVIE_LIBRARY.id, "")
        assertEquals("https://addon.example/tmdb:278/poster.jpg", row.items.first().poster)
        assertNull(catalog.settledArtwork(row, ""))
        val stale = row.copy(items = listOf(row.items.first().copy(poster = null)) + row.items.drop(1))
        assertEquals(row.items, catalog.settledArtwork(stale, "")?.items)
        assertTrue(metaRepository.requested.isEmpty())
    }

    @Test
    fun catalogIdFollowsInstalledMetaAddonsInOrder() {
        val title = ServerTitle(
            preview(ServerItemRef("c1", "7").encode(), ContentType.SERIES),
            TrackingExternalIds(imdb = "tt1", tmdb = 2, kitsu = 3)
        )
        val kitsu = metaAddon("kitsu", listOf("kitsu:"))
        val cinemeta = metaAddon("cinemeta", listOf("tt"))
        assertEquals("kitsu:3", title.catalogPreview(listOf(kitsu, cinemeta)).id)
        assertEquals("tt1", title.catalogPreview(listOf(cinemeta, kitsu)).id)
        assertEquals("tmdb:2", title.catalogPreview(listOf(metaAddon("mal", listOf("mal:")))).id)
        assertEquals(title.preview.id, title.copy(externalIds = TrackingExternalIds(kitsu = 3)).catalogPreview(emptyList()).id)
    }

    @Test
    fun collectionsKeepServerIdentity() {
        val collection = ServerTitle(
            preview(ServerItemRef("c1", "b1").encode(), ContentType.UNKNOWN, rawType = "collection"),
            TrackingExternalIds(imdb = "tt1")
        )
        assertEquals(collection.preview, collection.catalogPreview(listOf(metaAddon("any", emptyList()))))
    }

    @Test
    fun opensAnyServerRowByCatalogId() = runTest {
        val (catalog, _, connection) = catalog()

        val library = catalog.row(connection.id, FakeServerProvider.MOVIE_LIBRARY.id, "")
        assertEquals("Box · Movies", library.catalogName)
        assertEquals(ServerCatalog.PAGE_SIZE, library.items.size)
        assertTrue(library.hasMore)

        val missing = runCatching { catalog.row(connection.id, "unknown", "") }.exceptionOrNull()
        assertEquals(ServerFailure.NOT_FOUND, missing?.serverFailure())
    }

    @Test
    fun loadsNativeDetails() = runTest {
        val (catalog, _, connection) = catalog()
        val details = catalog.details(ServerItemRef(connection.id, FakeServerProvider.SHOW_ID))
        assertEquals("series", details.meta.apiType)
        assertFalse(details.externalIds.hasAny)
        assertEquals(ServerItemRef(connection.id, FakeServerProvider.EPISODE_ID), ServerItemRef.parse(details.meta.videos.single().id))
    }

    @Test
    fun unknownConnectionsFailAsNotFound() = runTest {
        val (catalog, _, _) = catalog()
        val failure = runCatching {
            catalog.catalog(ServerCatalog.baseUrl("missing"), "server.missing", "", "10", "", "movie", 0, emptyMap(), "")
        }.exceptionOrNull()
        assertEquals(ServerFailure.NOT_FOUND, failure?.serverFailure())
        assertNull(ServerCatalog.connectionId("https://addon.example"))
    }

    private fun preview(id: String, type: ContentType, rawType: String = type.toApiString()) = MetaPreview(
        id = id,
        type = type,
        rawType = rawType,
        name = "Show",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun metaAddon(id: String, prefixes: List<String>) = Addon(
        id = id,
        name = id,
        version = "1",
        description = null,
        logo = null,
        baseUrl = "https://$id.example",
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        resources = listOf(AddonResource(name = "meta", types = listOf("movie", "series"), idPrefixes = prefixes)),
        idPrefixes = prefixes
    )
}
