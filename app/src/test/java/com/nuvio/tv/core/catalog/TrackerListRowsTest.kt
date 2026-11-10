package com.nuvio.tv.core.catalog

import com.nuvio.tv.core.sync.isDeviceOnlyCatalogKey
import com.nuvio.tv.core.sync.withLocalServerKeys
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.repository.LibraryRepository
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerListRowsTest {
    private val tabs = MutableStateFlow(
        listOf(
            LibraryListTab("wetrakr:status:planning", "planning", LibraryListTab.Type.WATCHLIST),
            LibraryListTab("wetrakr:status:watched", "watched", LibraryListTab.Type.STATUS),
            LibraryListTab("wetrakr:list:7", "Weekend sci-fi", LibraryListTab.Type.PERSONAL)
        )
    )
    private val mode = MutableStateFlow(LibrarySourceMode.WETRAKR)
    private val items = MutableStateFlow<List<LibraryEntry>>(emptyList())

    private val rows = TrackerListRows(
        library = Lazy {
            mockk<LibraryRepository> {
                every { sourceMode } returns mode
                every { listTabs } returns tabs
                every { libraryItems } returns items
            }
        },
        titleOf = { it.title },
        providerName = TrackingProviderId::storageId,
        loadWaitMs = 1_000L
    )

    private fun entry(id: String, type: String, rank: Int?, listedAt: Long = 0L) = LibraryEntry(
        id = id,
        type = type,
        name = id,
        poster = null,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null,
        listKeys = setOf("wetrakr:list:7"),
        listRanks = rank?.let { mapOf("wetrakr:list:7" to it) }.orEmpty(),
        listedAt = listedAt
    )

    @Test
    fun `lists become rows of the Library source and finished lists do not`() = runTest {
        val addon = rows.addons.first().single()

        assertEquals("builtin.lists.wetrakr", addon.id)
        assertEquals(listOf("wetrakr:status:planning", "wetrakr:list:7"), addon.catalogs.map { it.id })
        assertTrue(rows.handles(addon.baseUrl))
        assertTrue(rows.ownsAddon(addon.id))
        assertTrue(addon.baseUrl.startsWith(BuiltInCatalogs.BASE_PREFIX))
    }

    @Test
    fun `no tracker as the Library source means no rows`() = runTest {
        mode.value = LibrarySourceMode.LOCAL

        assertTrue(rows.addons.first().isEmpty())
    }

    @Test
    fun `every list row starts off`() = runTest {
        val addons = rows.addons.first()

        assertEquals(2, rows.hiddenByDefaultKeys(addons).size)
        assertTrue(rows.hiddenByDefaultKeys(addons).all(BuiltInCatalogs::isBuiltInKey))
    }

    @Test
    fun `a row mixes films and shows in the list's own order`() = runTest {
        items.value = listOf(entry("show", "series", 1), entry("film", "movie", 0), entry("late", "movie", null, 9))

        val row = rows.catalog("u", "builtin.lists.wetrakr", "WeTrakr", "wetrakr:list:7", "Weekend sci-fi", "all", 0, emptyMap(), "")

        assertEquals(listOf("film", "show", "late"), row.items.map { it.id })
        assertEquals(listOf("movie", "series", "movie"), row.items.map { it.apiType })
        assertFalse(row.hasMore)
    }

    @Test
    fun `a long list loads a page at a time`() = runTest {
        items.value = (0 until TrackerListRows.PAGE_SIZE + 5).map { entry("t$it", "movie", it) }

        val first = rows.catalog("u", "a", "n", "wetrakr:list:7", "c", "all", 0, emptyMap(), "")
        val second = rows.catalog("u", "a", "n", "wetrakr:list:7", "c", "all", first.nextSkip, emptyMap(), "")

        assertEquals(TrackerListRows.PAGE_SIZE, first.items.size)
        assertTrue(first.hasMore)
        assertEquals(5, second.items.size)
        assertFalse(second.hasMore)
    }

    @Test
    fun `an empty list is an empty row`() = runTest {
        val row = rows.catalog("u", "a", "n", "wetrakr:list:7", "c", "all", 0, emptyMap(), "")

        assertTrue(row.items.isEmpty())
        assertFalse(row.hasMore)
    }

    @Test
    fun `account sync leaves list rows as this device has them`() {
        val key = BuiltInCatalogs.homeKey("builtin.lists.wetrakr", "all", "wetrakr:list:7")

        assertTrue(isDeviceOnlyCatalogKey(key))
        assertFalse(isDeviceOnlyCatalogKey("cinemeta_movie_top"))
        assertEquals(listOf(key, "cinemeta_movie_top"), listOf("cinemeta_movie_top").withLocalServerKeys(listOf(key)))
    }
}
