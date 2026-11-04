package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerGenreSummariesTest {
    private val addonRepository = mockk<AddonRepository> {
        every { getInstalledAddons() } returns MutableStateFlow<List<Addon>>(emptyList())
    }

    private fun catalog(provider: FakeServerProvider): Pair<ServerCatalog, ServerConnection> {
        val (repository, connection) = fakeServerRepository(provider)
        return ServerCatalog(repository, addonRepository, serverArtwork(FakeMetaRepository())) to connection
    }

    @Test
    fun eachGenreIsCountedWithASmallRequest() = runTest {
        val provider = FakeServerProvider().apply { genres["10"] = listOf(ServerGenre("g1", "Action"), ServerGenre("g2", "Drama")) }
        val (catalog, connection) = catalog(provider)

        val summaries = catalog.genreSummaries(connection.id, "10")

        assertEquals(mapOf("Action" to FakeServerProvider.GENRE_ITEMS, "Drama" to FakeServerProvider.GENRE_ITEMS), summaries.mapValues { it.value.count })
        assertEquals(setOf("g1", "g2"), provider.libraryRequests.map { it.genreId }.toSet())
        assertTrue(provider.libraryRequests.all { it.sort == ServerSort.ADDED })
    }

    @Test
    fun theNewestTitlesBackdropBecomesTheGenrePicture() = runTest {
        val provider = FakeServerProvider().apply {
            genres["10"] = listOf(ServerGenre("g1", "Action"))
            artworkBase = "https://image.tmdb.org/t/p"
        }
        val (catalog, connection) = catalog(provider)

        assertEquals("https://image.tmdb.org/t/p/backdrop/0.jpg", catalog.genreSummaries(connection.id, "10").getValue("Action").picture)
    }

    @Test
    fun picturesFromTheServerItselfAreNotKept() = runTest {
        val provider = FakeServerProvider().apply {
            genres["10"] = listOf(ServerGenre("g1", "Action"))
            artworkBase = "https://fake.example/Items"
        }
        val (catalog, connection) = catalog(provider)

        val summary = catalog.genreSummaries(connection.id, "10").getValue("Action")

        assertNull(summary.picture)
        assertEquals(FakeServerProvider.GENRE_ITEMS, summary.count)
    }

    @Test
    fun onlyPublicLinksWithoutSecretsAreShareable() {
        val connection = fakeServerRepository(FakeServerProvider()).second.copy(alternateAddress = "http://192.168.1.5:8096")

        assertTrue(ServerCatalog.shareableArtwork("https://image.tmdb.org/t/p/w780/a.jpg", connection))
        assertTrue(ServerCatalog.shareableArtwork("https://cdn.example/a.jpg?w=780", connection))
        assertFalse(ServerCatalog.shareableArtwork("https://fake.example/Items/1/Images/Backdrop", connection))
        assertFalse(ServerCatalog.shareableArtwork("http://192.168.1.5:8096/Items/1/Images/Primary", connection))
        assertFalse(ServerCatalog.shareableArtwork("https://cdn.example/a.jpg?api_key=abc", connection))
        assertFalse(ServerCatalog.shareableArtwork("https://cdn.example/a.jpg?X-Plex-Token=abc", connection))
        assertFalse(ServerCatalog.shareableArtwork("https://user:pw@cdn.example/a.jpg", connection))
        assertFalse(ServerCatalog.shareableArtwork("content://media/a.jpg", connection))
        assertFalse(ServerCatalog.shareableArtwork(null, connection))
    }

    @Test
    fun aLibraryThatFailsOrIsGoneCountsNothing() = runTest {
        val provider = FakeServerProvider().apply {
            genres["10"] = listOf(ServerGenre("g1", "Action"))
            failingLibraries += "10"
        }
        val (catalog, connection) = catalog(provider)

        assertTrue(catalog.genreSummaries(connection.id, "10").isEmpty())
        assertTrue(catalog.genreSummaries(connection.id, "77").isEmpty())
        assertTrue(catalog.genreSummaries("gone", "10").isEmpty())
    }
}
