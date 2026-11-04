package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLibraryFilterTest {
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

    private suspend fun ServerCatalog.page(connection: ServerConnection, catalogId: String, extra: Map<String, String>) =
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
    fun onlyTheAToZVersionOffersLetters() = runTest {
        val (catalog, _) = catalog(libraries = listOf(FakeServerProvider.MOVIE_LIBRARY, FakeServerProvider.COLLECTION_LIBRARY))

        val catalogs = catalog.addons.first().single().catalogs
        fun extras(id: String) = catalogs.single { it.id == id }.extra.map { it.name }

        assertEquals(listOf("skip", "unwatched"), extras("10"))
        assertEquals(listOf("skip", "letter", "unwatched"), extras("sorted.title:10"))
        assertEquals(listOf("skip", "unwatched"), extras("sorted.released:10"))
        assertEquals(listOf("skip"), extras("30"))
        val letters = catalogs.single { it.id == "sorted.title:10" }.extra.single { it.name == "letter" }.options.orEmpty()
        assertEquals(26, letters.size)
        assertEquals("A", letters.first())
        assertEquals("Z", letters.last())
        assertTrue(catalogs.single { it.id == "sorted.title:10" }.extra.none { it.isRequired })
    }

    @Test
    fun letterAndUnwatchedReachTheServer() = runTest {
        val provider = FakeServerProvider()
        val (catalog, connection) = catalog(provider)

        catalog.page(connection, "sorted.title:10", mapOf("letter" to "m", "unwatched" to "true"))
        catalog.page(connection, "10", mapOf("unwatched" to "true"))
        catalog.page(connection, "sorted.title:10", emptyMap())

        assertEquals(
            listOf(
                ServerLibraryFilter('M', unwatched = true),
                ServerLibraryFilter(null, unwatched = true),
                ServerLibraryFilter.NONE
            ),
            provider.libraryFilters
        )
    }

    @Test
    fun oddValuesAreIgnored() {
        assertEquals(ServerLibraryFilter.NONE, ServerLibraryFilter.of("MM", "yes"))
        assertEquals(ServerLibraryFilter.NONE, ServerLibraryFilter.of("7", null))
        assertEquals(ServerLibraryFilter.NONE, ServerLibraryFilter.of(" ", "false"))
        assertEquals(ServerLibraryFilter('Q', false), ServerLibraryFilter.of(" q ", null))
    }
}
