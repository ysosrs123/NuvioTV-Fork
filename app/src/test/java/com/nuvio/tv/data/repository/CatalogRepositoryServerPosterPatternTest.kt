package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.mediaserver.FakeMetaRepository
import com.nuvio.tv.data.mediaserver.FakeServerProvider
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.addonMeta
import com.nuvio.tv.data.mediaserver.fakeServerRepository
import com.nuvio.tv.data.mediaserver.serverArtwork
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogRepositoryServerPosterPatternTest {
    private val provider = FakeServerProvider().apply {
        indexedIds["0"] = TrackingExternalIds(imdb = "tt0111161", tmdb = 278)
    }

    private fun setup(
        enabledScreens: Set<CustomPosterScreen> = CustomPosterScreen.ALL,
        pattern: String = PATTERN,
        metaRepository: FakeMetaRepository = FakeMetaRepository()
    ): Triple<CatalogRepositoryImpl, ServerRepository, ServerConnection> {
        val (servers, connection) = fakeServerRepository(provider)
        val addonRepository = mockk<AddonRepository> {
            every { getInstalledAddons() } returns MutableStateFlow(emptyList())
        }
        val layoutPrefs = mockk<LayoutPreferenceDataStore> {
            every { customPosterUrlPattern } returns flowOf(pattern)
            every { customPosterEnabledScreens } returns flowOf(enabledScreens)
        }
        val repository = CatalogRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            api = mockk(),
            layoutPreferenceDataStore = layoutPrefs,
            healthStore = mockk(relaxed = true),
            serverCatalog = ServerCatalog(servers, addonRepository, serverArtwork(metaRepository))
        )
        return Triple(repository, servers, connection)
    }

    private suspend fun CatalogRepositoryImpl.serverRows(connection: ServerConnection): List<CatalogRow> =
        getCatalog(
            addonBaseUrl = ServerCatalog.baseUrl(connection.id),
            addonId = ServerCatalog.addonId(connection.id),
            addonName = "Box",
            catalogId = FakeServerProvider.MOVIE_LIBRARY.id,
            catalogName = "Box · Movies",
            type = "movie",
            skip = 0,
            skipStep = ServerCatalog.PAGE_SIZE,
            extraArgs = emptyMap(),
            supportsSkip = true,
            posterScreen = CustomPosterScreen.HOME
        ).toList().filterIsInstance<NetworkResult.Success<CatalogRow>>().map { it.data }

    @Test
    fun serverRowsFollowTheHomeSwitch() = runBlocking {
        val (on, _, onConnection) = setup()
        val shown = on.serverRows(onConnection).single().items
        assertEquals("https://posters.example/tt0111161.jpg", shown[0].poster)
        assertNull(shown[1].poster)

        val (off, _, offConnection) = setup(enabledScreens = CustomPosterScreen.ALL - CustomPosterScreen.HOME)
        assertNull(off.serverRows(offConnection).single().items[0].poster)

        val (unset, _, unsetConnection) = setup(pattern = "")
        assertNull(unset.serverRows(unsetConnection).single().items[0].poster)
    }

    @Test
    fun bothServerEmissionsCarryThePattern() = runBlocking {
        val metaRepository = FakeMetaRepository(
            metas = mapOf("tmdb:278" to addonMeta("tmdb:278", "The Shawshank Redemption")),
            delayMs = 1_200
        )
        val (repository, servers, connection) = setup(metaRepository = metaRepository)
        servers.setCatalogMetadata(connection.id, true)

        val rows = repository.serverRows(connection)

        assertEquals(2, rows.size)
        assertEquals("Item 0", rows[0].items[0].name)
        assertEquals("https://posters.example/tt0111161.jpg", rows[0].items[0].poster)
        assertEquals("The Shawshank Redemption", rows[1].items[0].name)
        assertEquals("https://posters.example/tt0111161.jpg", rows[1].items[0].poster)
        assertEquals("https://addon.example/tmdb:278/poster.jpg", rows[1].items[0].rawPosterUrl)
    }

    private companion object {
        const val PATTERN = "https://posters.example/{imdb_id}.jpg"
    }
}
