package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ServerPosterPatternTest {
    private val addonRepository = mockk<AddonRepository> {
        every { getInstalledAddons() } returns MutableStateFlow(emptyList())
    }

    private fun catalog(
        provider: FakeServerProvider,
        metaRepository: FakeMetaRepository = FakeMetaRepository()
    ): Triple<ServerCatalog, ServerRepository, ServerConnection> {
        val (repository, connection) = fakeServerRepository(provider)
        return Triple(ServerCatalog(repository, addonRepository, serverArtwork(metaRepository)), repository, connection)
    }

    private fun provider() = FakeServerProvider().apply {
        indexedIds["0"] = TrackingExternalIds(imdb = "tt0111161", tmdb = 278)
        indexedIds["1"] = TrackingExternalIds(tmdb = 550)
    }

    @Test
    fun nativeRowsTakeThePatternFromTheServerIds() = runTest {
        val (catalog, _, connection) = catalog(provider())

        val plain = catalog.row(connection.id, FakeServerProvider.MOVIE_LIBRARY.id, "").items
        val items = catalog.row(connection.id, FakeServerProvider.MOVIE_LIBRARY.id, IMDB_PATTERN).items

        assertEquals("https://posters.example/tt0111161.jpg", items[0].poster)
        assertEquals(ServerItemRef(connection.id, "0").encode(), items[0].id)
        assertEquals(plain[1], items[1])
        assertEquals(plain[2], items[2])
        assertNull(plain[0].poster)
    }

    @Test
    fun nativeRowsUseCatalogueIdsForTheNuvioPlaceholders() = runTest {
        val (catalog, _, connection) = catalog(provider())

        val items = catalog.row(connection.id, FakeServerProvider.MOVIE_LIBRARY.id, ID_PATTERN).items

        assertEquals("https://posters.example/movie/tt0111161.jpg", items[0].poster)
        assertEquals("https://posters.example/movie/tmdb:550.jpg", items[1].poster)
        assertNull(items[2].poster)
    }

    @Test
    fun searchAndCollectionPagesTakeThePattern() = runTest {
        val provider = FakeServerProvider().apply { indexedIds["7"] = TrackingExternalIds(imdb = "tt0068646") }
        val (catalog, _, connection) = catalog(provider)

        val search = catalog.catalog(
            ServerCatalog.baseUrl(connection.id), ServerCatalog.addonId(connection.id), "Box", FakeServerProvider.MOVIE_LIBRARY.id,
            "Movies", "movie", 0, mapOf("search" to "item"), IMDB_PATTERN
        )
        assertEquals("https://posters.example/tt0068646.jpg", search.items.single().poster)

        val collection = catalog.collectionRow(ServerItemRef(connection.id, "c-popular"), IMDB_PATTERN)
        assertEquals("https://posters.example/tt0068646.jpg", collection.items.first().poster)
        assertNull(collection.items.last().poster)
    }

    @Test
    fun blankPatternLeavesServerRowsAlone() = runTest {
        val (catalog, repository, connection) = catalog(provider())

        val native = catalog.row(connection.id, FakeServerProvider.MOVIE_LIBRARY.id, "").items
        assertNull(native[0].poster)
        assertNull(native[0].rawPosterUrl)

        repository.setCatalogMetadata(connection.id, true)
        val mapped = catalog.row(connection.id, FakeServerProvider.MOVIE_LIBRARY.id, "   ").items
        assertEquals("tmdb:278", mapped[0].id)
        assertNull(mapped[0].poster)
        assertNull(mapped[0].rawPosterUrl)
    }

    @Test
    fun patternWinsOverAddonArtworkAndKeepsItAsTheFallback() = runTest {
        val metaRepository = FakeMetaRepository(cachedMetas = mapOf("tmdb:278" to addonMeta("tmdb:278", "The Shawshank Redemption")))
        val (catalog, repository, connection) = catalog(provider(), metaRepository)
        repository.setCatalogMetadata(connection.id, true)

        val row = catalog.row(connection.id, FakeServerProvider.MOVIE_LIBRARY.id, IMDB_PATTERN)
        val first = row.items[0]
        assertEquals("tmdb:278", first.id)
        assertEquals("The Shawshank Redemption", first.name)
        assertEquals("https://addon.example/tmdb:278/background.jpg", first.background)
        assertEquals("https://posters.example/tt0111161.jpg", first.poster)
        assertEquals("https://addon.example/tmdb:278/poster.jpg", first.rawPosterUrl)
        assertNull(row.items[1].poster)

        assertNull(catalog.settledArtwork(row, IMDB_PATTERN))
        assertSame(row, catalog.withArtwork(row, IMDB_PATTERN))
    }

    @Test
    fun secondEmissionKeepsThePatternAfterTheArtworkSwap() = runBlocking {
        val metaRepository = FakeMetaRepository(
            metas = mapOf("tmdb:278" to addonMeta("tmdb:278", "The Shawshank Redemption")),
            delayMs = 1_200
        )
        val (catalog, repository, connection) = catalog(provider(), metaRepository)
        repository.setCatalogMetadata(connection.id, true)

        val row = catalog.row(connection.id, FakeServerProvider.MOVIE_LIBRARY.id, ID_PATTERN)
        assertEquals("Item 0", row.items[0].name)
        assertEquals("https://posters.example/movie/tmdb:278.jpg", row.items[0].poster)

        val settled = catalog.settledArtwork(row, ID_PATTERN)
        assertNotNull(settled)
        val swapped = settled!!.items[0]
        assertEquals("The Shawshank Redemption", swapped.name)
        assertEquals("https://posters.example/movie/tmdb:278.jpg", swapped.poster)
        assertEquals("https://addon.example/tmdb:278/poster.jpg", swapped.rawPosterUrl)
        assertEquals(row.items.drop(1), settled.items.drop(1))
    }

    @Test
    fun addonArtworkWithoutAPosterKeepsThePatternAndItsFallback() = runBlocking {
        val metaRepository = FakeMetaRepository(
            metas = mapOf("tmdb:278" to addonMeta("tmdb:278", "The Shawshank Redemption").copy(poster = null)),
            delayMs = 1_200
        )
        val (catalog, repository, connection) = catalog(provider(), metaRepository)
        repository.setCatalogMetadata(connection.id, true)

        val row = catalog.row(connection.id, FakeServerProvider.MOVIE_LIBRARY.id, ID_PATTERN)
        val settled = catalog.settledArtwork(row, ID_PATTERN)!!
        val swapped = settled.items[0]

        assertEquals("The Shawshank Redemption", swapped.name)
        assertEquals("https://posters.example/movie/tmdb:278.jpg", swapped.poster)
        assertNull(swapped.rawPosterUrl)
        assertSame(settled, catalog.withArtwork(settled, ID_PATTERN))
    }

    @Test
    fun collectionsAndTitlesWithoutIdsKeepTheirPoster() {
        val ref = ServerItemRef("c1", "b1").encode()
        val collection = ServerTitle(item(ref, ContentType.UNKNOWN, rawType = "collection"), TrackingExternalIds(tmdb = 10))
        assertSame(collection.preview, collection.withPosterPattern(collection.preview, ID_PATTERN))

        val bare = ServerTitle(item(ref, ContentType.MOVIE))
        assertSame(bare.preview, bare.withPosterPattern(bare.preview, ID_PATTERN))

        val tvdbOnly = ServerTitle(item(ref, ContentType.SERIES), TrackingExternalIds(tvdb = "81189"))
        assertSame(tvdbOnly.preview, tvdbOnly.withPosterPattern(tvdbOnly.preview, IMDB_PATTERN))
        assertEquals(
            "https://posters.example/series/tvdb:81189.jpg",
            tvdbOnly.withPosterPattern(tvdbOnly.preview, ID_PATTERN).poster
        )
        assertEquals("https://server.example/poster.jpg", tvdbOnly.withPosterPattern(tvdbOnly.preview, ID_PATTERN).rawPosterUrl)
    }

    private fun item(id: String, type: ContentType, rawType: String = type.toApiString()) = MetaPreview(
        id = id,
        type = type,
        rawType = rawType,
        name = "Title",
        poster = "https://server.example/poster.jpg",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private companion object {
        const val IMDB_PATTERN = "https://posters.example/{imdb_id}.jpg"
        const val ID_PATTERN = "https://posters.example/{type}/{id}.jpg"
    }
}
