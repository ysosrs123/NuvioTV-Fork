package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerLibraryBrowseTest {
    private val addonRepository = mockk<AddonRepository> {
        every { getInstalledAddons() } returns MutableStateFlow<List<Addon>>(emptyList())
    }

    private fun catalog(
        provider: FakeServerProvider = FakeServerProvider(),
        libraries: List<ServerLibrary> = listOf(FakeServerProvider.MOVIE_LIBRARY, FakeServerProvider.SERIES_LIBRARY)
    ): Pair<ServerCatalog, ServerConnection> {
        val (repository, connection) = fakeServerRepository(provider, libraries = libraries)
        return ServerCatalog(repository, addonRepository, serverArtwork(FakeMetaRepository())) to connection
    }

    private suspend fun ServerCatalog.page(connection: ServerConnection, catalogId: String, extra: Map<String, String> = emptyMap()) =
        catalog(
            addonBaseUrl = ServerCatalog.baseUrl(connection.id),
            addonId = ServerCatalog.addonId(connection.id),
            addonName = "Fake · Box",
            catalogId = catalogId,
            catalogName = "Box · Movies",
            type = "movie",
            skip = 0,
            extraArgs = extra,
            posterPattern = ""
        )

    @Test
    fun movieAndShowLibrariesGetSortedVersionsNamedAfterTheirOrder() = runTest {
        val collection = FakeServerProvider.COLLECTION_LIBRARY
        val mixed = ServerLibrary("90", "Everything", ServerMediaKind.MIXED)
        val (catalog, _) = catalog(libraries = listOf(FakeServerProvider.MOVIE_LIBRARY, collection, mixed))

        val catalogs = catalog.addons.first().single().catalogs

        assertEquals(
            listOf("10", "sorted.title:10", "sorted.released:10", "30", "90", "search.movie", "search.series"),
            catalogs.map { it.id }
        )
        assertEquals("Box · Movies · A to Z", catalogs.single { it.id == "sorted.title:10" }.name)
        assertEquals("Box · Movies · Newest release", catalogs.single { it.id == "sorted.released:10" }.name)
    }

    @Test
    fun aSortedVersionAsksTheServerForItsOrder() = runTest {
        val provider = FakeServerProvider()
        val (catalog, connection) = catalog(provider)

        catalog.page(connection, "10")
        catalog.page(connection, "sorted.title:10")
        catalog.page(connection, "sorted.released:20")

        assertEquals(
            listOf(
                FakeServerProvider.LibraryRequest("10", ServerSort.ADDED, null),
                FakeServerProvider.LibraryRequest("10", ServerSort.TITLE, null),
                FakeServerProvider.LibraryRequest("20", ServerSort.RELEASED, null)
            ),
            provider.libraryRequests
        )
    }

    @Test
    fun catalogIdsNameTheirOrderAndLibrary() {
        assertEquals(ServerSort.ADDED to "10", ServerCatalog.libraryCatalog("10"))
        assertEquals(ServerSort.TITLE to "lib:with:colons", ServerCatalog.libraryCatalog(ServerCatalog.sortedCatalogId(ServerSort.TITLE, "lib:with:colons")))
        assertEquals(ServerSort.RELEASED to "a\u0000movie", ServerCatalog.libraryCatalog("sorted.released:a\u0000movie"))
        assertEquals(ServerSort.ADDED to "sorted.unknown:10", ServerCatalog.libraryCatalog("sorted.unknown:10"))
    }

    @Test
    fun discoverOffersALibrarysGenresAndReadsThemOnce() = runTest {
        val provider = FakeServerProvider().apply {
            genres["10"] = listOf(ServerGenre("g1", "Action"), ServerGenre("g2", "Drama"), ServerGenre("g3", "action"))
        }
        val (catalog, _) = catalog(provider)

        val catalogs = withContext(Dispatchers.Default) { catalog.searchAddons.first() }.single().catalogs
        withContext(Dispatchers.Default) { catalog.searchAddons.first() }

        listOf("10", "sorted.title:10", "sorted.released:10").forEach { id ->
            val genre = catalogs.single { it.id == id }.extra.single { it.name == "genre" }
            assertEquals(listOf("Action", "Drama"), genre.options)
            assertFalse(genre.isRequired)
        }
        assertTrue(catalogs.filter { it.id.endsWith("20") }.none { catalog -> catalog.extra.any { it.name == "genre" } })
        assertEquals(2, provider.genreReads)
    }

    @Test
    fun homeRowsCarryNoGenres() = runTest {
        val provider = FakeServerProvider().apply { genres["10"] = listOf(ServerGenre("g1", "Action")) }
        val (catalog, _) = catalog(provider)

        withContext(Dispatchers.Default) { catalog.searchAddons.first() }

        assertTrue(catalog.addons.first().single().catalogs.none { catalog -> catalog.extra.any { it.name == "genre" } })
    }

    @Test
    fun aChosenGenreIsSentAsTheServersOwnId() = runTest {
        val provider = FakeServerProvider().apply { genres["10"] = listOf(ServerGenre("g1", "Action")) }
        val (catalog, connection) = catalog(provider)

        val row = catalog.page(connection, "sorted.title:10", mapOf("genre" to "action"))

        assertEquals(FakeServerProvider.LibraryRequest("10", ServerSort.TITLE, "g1"), provider.libraryRequests.single())
        assertEquals(FakeServerProvider.GENRE_ITEMS, row.items.size)
        assertFalse(row.hasMore)
    }

    @Test
    fun aGenreTheServerNoLongerHasShowsNothing() = runTest {
        val provider = FakeServerProvider().apply { genres["10"] = listOf(ServerGenre("g1", "Action")) }
        val (catalog, connection) = catalog(provider)

        val row = catalog.page(connection, "10", mapOf("genre" to "Western"))

        assertTrue(row.items.isEmpty())
        assertFalse(row.hasMore)
        assertTrue(provider.libraryRequests.isEmpty())
    }

    @Test
    fun genresThatFailAreAskedForAgainLater() = runTest {
        val provider = FakeServerProvider().apply {
            genres["10"] = listOf(ServerGenre("g1", "Action"))
            failingGenres = true
        }
        val (catalog, connection) = catalog(provider)
        var now = 1_000_000L
        catalog.now = { now }
        val movies = connection.libraries.first { it.id == "10" }

        assertTrue(catalog.genres(connection, movies).isEmpty())
        provider.failingGenres = false
        assertTrue(catalog.genres(connection, movies).isEmpty())
        now += 11 * 60 * 1000L
        assertEquals(listOf("Action"), catalog.genres(connection, movies).map { it.name })
        now += 5 * 60 * 60 * 1000L
        catalog.genres(connection, movies)
        assertEquals(2, provider.genreReads)
    }

    @Test
    fun collectionLibrariesHaveNoGenres() = runTest {
        val provider = FakeServerProvider()
        val (catalog, connection) = catalog(provider, libraries = listOf(FakeServerProvider.COLLECTION_LIBRARY))

        assertTrue(catalog.genres(connection, FakeServerProvider.COLLECTION_LIBRARY).isEmpty())
        assertEquals(0, provider.genreReads)
    }

    @Test
    fun aRemovedLibraryAnswersNotFound() = runTest {
        val (catalog, connection) = catalog()

        try {
            catalog.page(connection, "sorted.title:77")
            fail("expected NOT_FOUND")
        } catch (error: ServerException) {
            assertEquals(ServerFailure.NOT_FOUND, error.failure)
        }
    }

    @Test
    fun onlyTheSortedVersionsStartOffHome() = runTest {
        val (catalog, connection) = catalog()
        val addons = catalog.addons.first()
        val addonId = ServerCatalog.addonId(connection.id)

        assertEquals(
            listOf(
                homeCatalogKey(addonId, "movie", "sorted.title:10"),
                homeCatalogKey(addonId, "movie", "sorted.released:10"),
                homeCatalogKey(addonId, "series", "sorted.title:20"),
                homeCatalogKey(addonId, "series", "sorted.released:20")
            ),
            ServerCatalog.hiddenByDefaultKeys(addons)
        )
        assertTrue(addons.single().catalogs.filter { it.id.startsWith("sorted.") }.all { it.showInHome })
        assertNull(ServerCatalog.hiddenByDefaultKeys(emptyList()).firstOrNull())
    }

    @Test
    fun aSortedVersionOpensAsARow() = runTest {
        val (catalog, connection) = catalog()

        assertEquals("Box · Shows · Newest release", catalog.row(connection.id, "sorted.released:20", "").catalogName)
    }
}
