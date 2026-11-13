package com.nuvio.tv.core.scout

import android.content.Context
import com.nuvio.tv.data.local.ScoutStore
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.ScoutBecause
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.ScoutPool
import com.nuvio.tv.domain.model.ScoutProfile
import com.nuvio.tv.domain.model.ScoutSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutCatalogProviderTest {
    private fun pick(id: Int, tv: Boolean = false, seed: String? = "Dune", genres: List<Int> = emptyList()) =
        ScoutPick(id, tv, "P$id", poster = "p", backdrop = "b", released = "2020-01-01", seedName = seed, genres = genres)

    private val fullPool = ScoutPool(
        builtAt = 1L,
        picked = listOf(pick(1), pick(2, tv = true)),
        because = listOf(ScoutBecause("Dune", listOf(pick(3)))),
        gems = listOf(pick(4))
    )

    private fun provider(
        pool: ScoutPool? = fullPool,
        profile: ScoutProfile = ScoutProfile(),
        hide: Set<String> = emptySet(),
        resolver: com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver = mockk()
    ): ScoutCatalogProvider {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "text"
            every { getString(any(), *anyVararg()) } returns "text"
        }
        val engine = mockk<ScoutEngine> { every { this@mockk.pool } returns MutableStateFlow(pool) }
        val store = mockk<ScoutStore> { every { this@mockk.profile } returns flowOf(profile) }
        val folders = mockk<ScoutFolderSupport> {
            every { changes } returns kotlinx.coroutines.flow.emptyFlow()
            coEvery { withoutSeen(any()) } answers { firstArg<List<MetaPreview>>().filterNot { it.id in hide } }
        }
        return ScoutCatalogProvider(artwork, context, engine, store, resolver, folders, mockk(relaxed = true), mockk(relaxed = true))
    }

    private val artwork = mockk<ScoutArtwork> { coEvery { withPattern(any(), any()) } answers { firstArg() } }

    @Test
    fun `saved rows follow the poster address too`() {
        runBlocking {
            provider().catalog(
                ScoutCatalogProvider.BASE_URL, ScoutCatalogProvider.ADDON_ID, "Scout",
                ScoutCatalogProvider.CATALOG_SAVED + "mood_laugh", "Name", "scout", 1, emptyMap(), "https://art/{imdb_id}"
            )
        }
        coVerify { artwork.withPattern(any(), "https://art/{imdb_id}") }
    }

    private fun ScoutCatalogProvider.row(id: String, skip: Int = 0) = runBlocking {
        catalog(ScoutCatalogProvider.BASE_URL, ScoutCatalogProvider.ADDON_ID, "Scout", id, "Name", "scout", skip, emptyMap(), "")
    }

    private fun ScoutCatalogProvider.catalogIds(profile: ScoutProfile, pool: ScoutPool? = fullPool) =
        addonsFor(pool, profile).flatMap { addon -> addon.catalogs.map { it.id } }

    @Test
    fun `all rows show by default`() {
        assertEquals(
            listOf(ScoutCatalogProvider.CATALOG_PICKED, ScoutCatalogProvider.CATALOG_BECAUSE + 0, ScoutCatalogProvider.CATALOG_GEMS),
            provider().catalogIds(ScoutProfile())
        )
    }

    @Test
    fun `switches take rows away and none leaves no addon`() {
        val off = ScoutSettings(pickedRow = false, becauseRow = false, gemsRow = false)
        assertTrue(provider().addonsFor(fullPool, ScoutProfile(settings = off)).isEmpty())
    }

    @Test
    fun `no pool yet means no rows and an empty section never shows`() {
        assertTrue(provider().addonsFor(null, ScoutProfile()).isEmpty())
        assertTrue(provider().addonsFor(ScoutPool(), ScoutProfile()).isEmpty())
    }

    @Test
    fun `saved slices become rows and unknown ones are ignored`() {
        val settings = ScoutSettings(pickedRow = false, becauseRow = false, gemsRow = false, savedSlices = listOf("mood_laugh", "gone"))
        assertEquals(listOf(ScoutCatalogProvider.CATALOG_SAVED + "mood_laugh"), provider().catalogIds(ScoutProfile(settings = settings)))
    }

    @Test
    fun `row titles never get a type added`() {
        assertFalse(provider().showsTypeSuffix(ScoutCatalogProvider.ADDON_ID))
    }

    @Test
    fun `items open as films or series with a reason`() {
        val items = provider().row(ScoutCatalogProvider.CATALOG_PICKED).items.associateBy { it.id }
        assertEquals("movie", items.getValue("tmdb:1").apiType)
        assertEquals("series", items.getValue("tmdb:2").apiType)
        assertEquals("text", items.getValue("tmdb:1").scoutReason)
    }

    @Test
    fun `picks from popular have no reason`() {
        val pool = fullPool.copy(picked = listOf(pick(9, seed = null)))
        assertNull(provider(pool).row(ScoutCatalogProvider.CATALOG_PICKED).items.single().scoutReason)
    }

    @Test
    fun `seen titles and left out genres leave at load`() {
        val pool = fullPool.copy(picked = listOf(pick(1), pick(2), pick(3, genres = listOf(27))))
        val profile = ScoutProfile(settings = ScoutSettings(leaveOutGenres = setOf(27)))
        val items = provider(pool, profile, hide = setOf("tmdb:2")).row(ScoutCatalogProvider.CATALOG_PICKED).items
        assertEquals(listOf("tmdb:1"), items.map { it.id })
    }

    @Test
    fun `rows have no further pages`() {
        val row = provider().row(ScoutCatalogProvider.CATALOG_GEMS)
        assertFalse(row.hasMore)
        assertTrue(provider().row(ScoutCatalogProvider.CATALOG_GEMS, skip = 20).items.isEmpty())
    }

    @Test
    fun `because row index out of range is empty`() {
        assertTrue(provider().row(ScoutCatalogProvider.CATALOG_BECAUSE + 7).items.isEmpty())
        assertEquals(listOf("tmdb:3"), provider().row(ScoutCatalogProvider.CATALOG_BECAUSE + 0).items.map { it.id })
    }

    @Test
    fun `tiles start with surprise and end with more and can be switched off`() {
        val folders = provider().tilesFor(fullPool, ScoutProfile(settings = ScoutSettings(tilesRow = true))).single().folders
        assertEquals(ScoutCatalogProvider.FOLDER_SURPRISE, folders.first().id)
        assertEquals(ScoutCatalogProvider.FOLDER_MORE, folders.last().id)
        assertEquals(ScoutSlices.homeTiles, folders.drop(1).dropLast(1).map { it.id })
        assertTrue(provider().tilesFor(fullPool, ScoutProfile(settings = ScoutSettings(tilesRow = false))).isEmpty())
    }

    @Test
    fun `the home tile row starts off`() {
        assertFalse(ScoutSettings().tilesRow)
        assertTrue(provider().tilesFor(fullPool, ScoutProfile()).isEmpty())
    }

    private fun titleWith(id: String, background: String) = MetaPreview(
        id = id, type = com.nuvio.tv.domain.model.ContentType.MOVIE, name = id, poster = null, posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
        background = background, logo = null, description = null, releaseInfo = "2020", imdbRating = null, genres = emptyList()
    )

    @Test
    fun `a home tile's big picture comes from its own list and skips what is excluded`() = runBlocking {
        val row = mockk<com.nuvio.tv.domain.model.CatalogRow> { every { items } returns listOf(titleWith("seen", "other"), titleWith("own", "own art")) }
        val resolver = mockk<com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver> {
            every { resolve(any(), any()) } returns flowOf(com.nuvio.tv.core.network.NetworkResult.Success(row))
        }
        val provider = provider(hide = setOf("seen"), resolver = resolver)
        val korea = ScoutSlices.byId("country_KR")!!
        assertEquals("own art", provider.heroFor(korea))
        io.mockk.verify { resolver.resolve(match { it.filters.withOriginCountry == "KR" }, any()) }
        provider.tileHeroes.value = mapOf(korea.id to "own art")
        val folders = provider.tilesFor(fullPool, ScoutProfile(settings = ScoutSettings(tilesRow = true))).single().folders
        assertEquals("own art", folders.single { it.id == korea.id }.heroBackdropUrl)
        assertNull(folders.single { it.id == "mood_feel_good" }.heroBackdropUrl)
    }

    @Test
    fun `a folder page can open any slice and only for the scout collection`() {
        val collection = provider().collectionForFolders(ScoutCatalogProvider.TILES_COLLECTION_ID)!!
        assertEquals(ScoutSlices.all.size, collection.folders.size)
        assertTrue(collection.folders.filterNot { it.id.startsWith("award_") }.all { it.sources.isNotEmpty() })
        assertNull(provider().collectionForFolders("other"))
    }

    @Test
    fun `address belongs to built-in rows`() {
        assertTrue(ScoutCatalogProvider.BASE_URL.startsWith(com.nuvio.tv.core.catalog.BuiltInCatalogs.BASE_PREFIX))
        assertTrue(provider().handles(ScoutCatalogProvider.BASE_URL))
        assertFalse(provider().handles("http://addon"))
    }

    private val tastePool = fullPool.copy(
        tasteRows = listOf(
            com.nuvio.tv.domain.model.ScoutTasteRow("35_en", picks = listOf(pick(11))),
            com.nuvio.tv.domain.model.ScoutTasteRow("53_ko", picks = listOf(pick(12)))
        )
    )

    private fun key(id: String) = com.nuvio.tv.domain.model.catalogRowLegacyKey(ScoutCatalogProvider.ADDON_ID, "scout", id)

    @Test
    fun `genre rows take numbered places and the genre stays in the title`() {
        val provider = provider(tastePool)
        val ids = provider.catalogIds(ScoutProfile(), tastePool)
        assertEquals(listOf("taste_slot1", "taste_slot2"), ids.filter { it.startsWith(ScoutCatalogProvider.CATALOG_TASTE) })
        assertEquals(listOf("tmdb:12"), provider.row("taste_slot2").items.map { it.id })
        assertTrue(provider.row("taste_slot3").items.isEmpty())
        assertTrue(provider.row("taste_53_ko").items.isEmpty())
    }

    @Test
    fun `slot ids round trip and stay within the row limit`() {
        assertEquals(listOf("slot1", "slot2", "slot3"), List(SCOUT_ROWS_MAX) { scoutTasteSlotId(it) })
        assertEquals(listOf(0, 1, 2), listOf("slot1", "slot2", "slot3").map { scoutTasteSlotIndex(it) })
        assertNull(scoutTasteSlotIndex("slot0"))
        assertNull(scoutTasteSlotIndex("slot${SCOUT_ROWS_MAX + 1}"))
        assertNull(scoutTasteSlotIndex("35_en"))
    }

    @Test
    fun `the first home keys do not change with the day's genres`() {
        assertEquals(provider(fullPool).defaultFirstKeys, provider(tastePool).defaultFirstKeys)
        assertTrue(key("taste_slot3") in provider(null).defaultFirstKeys)
    }

    @Test
    fun `genre rows have their own switch apart from picked for you`() {
        val noGenres = provider(tastePool).catalogIds(ScoutProfile(settings = ScoutSettings(genreRows = false)), tastePool)
        assertTrue(ScoutCatalogProvider.CATALOG_PICKED in noGenres)
        assertTrue(noGenres.none { it.startsWith(ScoutCatalogProvider.CATALOG_TASTE) })
        val onlyGenres = provider(tastePool).catalogIds(ScoutProfile(settings = ScoutSettings(pickedRow = false)), tastePool)
        assertFalse(ScoutCatalogProvider.CATALOG_PICKED in onlyGenres)
        assertEquals(2, onlyGenres.count { it.startsWith(ScoutCatalogProvider.CATALOG_TASTE) })
        assertTrue(ScoutSettings().genreRows)
    }

    @Test
    fun `a new genre place goes next to the scout rows the user placed`() {
        val catalogs = com.nuvio.tv.core.catalog.BuiltInCatalogs(setOf(provider(tastePool)))
        val saved = listOf("a", key("picked"), "b", key("taste_slot1"), "c")
        val order = catalogs.withDefaultFirst(saved)
        assertEquals(listOf(key("taste_slot1"), key("taste_slot2"), key("taste_slot3"), "c"), order.takeLast(4))
        assertEquals(listOf("a", key("picked"), "b"), order.dropWhile { it != "a" }.take(3))

        val fresh = catalogs.withDefaultFirst(listOf("a", key("picked"), "b"))
        assertEquals(listOf(key("picked"), key("taste_slot1"), key("taste_slot2"), key("taste_slot3"), "b"), fresh.takeLast(5))
    }

    @Test
    fun `genre places take over where the old genre rows were saved`() {
        val catalogs = com.nuvio.tv.core.catalog.BuiltInCatalogs(setOf(provider(tastePool)))
        val order = catalogs.withDefaultFirst(listOf(key("picked"), "a", key("taste_35_en"), key("taste_18_en"), "b"))
        val at = order.indexOf(key("taste_35_en"))
        assertEquals(listOf(key("taste_slot1"), key("taste_slot2"), key("taste_slot3")), order.subList(at + 1, at + 4))
    }

    @Test
    fun `with nothing saved the genre places start at the top in scout order`() {
        val order = com.nuvio.tv.core.catalog.BuiltInCatalogs(setOf(provider(tastePool))).withDefaultFirst(emptyList())
        assertEquals(provider(tastePool).defaultFirstKeys, order)
    }
}
