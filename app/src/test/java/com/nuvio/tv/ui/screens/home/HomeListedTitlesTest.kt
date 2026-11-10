package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.ListedTitles
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.listedTitles
import com.nuvio.tv.domain.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeListedTitlesTest {
    private val listed = ListedTitles(
        planIds = setOf("plan"),
        watchingIds = setOf("watching"),
        planListKeys = setOf("wetrakr:status:planning"),
        watchingListKeys = setOf("wetrakr:status:watching")
    )
    private val both = HomeListedFilter(hidePlan = true, hideWatching = true, titles = listed)

    private fun row(addonId: String, catalogId: String, vararg ids: String) = CatalogRow(
        addonId = addonId,
        addonName = "Add-on",
        addonBaseUrl = "https://addon",
        catalogId = catalogId,
        catalogName = catalogId,
        type = ContentType.MOVIE,
        items = ids.map(::movie)
    )

    private fun movie(id: String) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private val addonRow = row("cinemeta", "top", "plan", "free", "watching", "done")

    @Test
    fun `each switch hides only its own list`() {
        val plan = both.copy(hideWatching = false)
        val watching = both.copy(hidePlan = false)

        assertEquals(listOf("free", "watching", "done"), addonRow.withoutListed(plan).items.map { it.id })
        assertEquals(listOf("plan", "free", "done"), addonRow.withoutListed(watching).items.map { it.id })
    }

    @Test
    fun `both switches off hide nothing and read no lists`() {
        val off = HomeListedFilter(hidePlan = false, hideWatching = false)

        assertTrue(off.ready)
        assertFalse(off.hides)
        assertSame(addonRow, addonRow.withoutListed(off))
    }

    @Test
    fun `nothing is hidden until the lists are read`() {
        val waiting = HomeListedFilter(hidePlan = true, hideWatching = false)

        assertFalse(waiting.ready)
        assertSame(addonRow, addonRow.withoutListed(waiting))
    }

    @Test
    fun `it adds to Watched titles and does not replace it`() {
        val watched = HomeWatchedIds(movieIds = setOf("done"))

        val hiddenByBoth = addonRow.withoutWatched(watched).withoutListed(both)
        assertEquals(listOf("free"), hiddenByBoth.items.map { it.id })

        val onlyWatched = addonRow.withoutWatched(watched).withoutListed(HomeListedFilter(false, false))
        assertEquals(listOf("plan", "free", "watching"), onlyWatched.items.map { it.id })

        val onlyListed = addonRow.withoutListed(both)
        assertEquals(listOf("free", "done"), onlyListed.items.map { it.id })
    }

    @Test
    fun `a list's own row keeps its own titles and still loses others`() {
        val planRow = row("builtin.lists.wetrakr", "wetrakr:status:planning", "plan", "watching")
        val watchingRow = row("builtin.lists.wetrakr", "wetrakr:status:watching", "plan", "watching")
        val otherAddon = row("cinemeta", "wetrakr:status:planning", "plan", "free")

        assertEquals(listOf("plan"), planRow.withoutListed(both).items.map { it.id })
        assertEquals(listOf("watching"), watchingRow.withoutListed(both).items.map { it.id })
        assertEquals(listOf("free"), otherAddon.withoutListed(both).items.map { it.id })
    }

    @Test
    fun `a list's own row still follows Watched titles`() {
        val planRow = row("builtin.lists.wetrakr", "wetrakr:status:planning", "plan", "done")

        val shown = planRow.withoutWatched(HomeWatchedIds(movieIds = setOf("done"))).withoutListed(both)
        assertEquals(listOf("plan"), shown.items.map { it.id })
    }

    @Test
    fun `a list left empty by filtering is an empty row`() {
        val customRow = row("builtin.lists.wetrakr", "wetrakr:list:7", "plan", "watching")

        assertTrue(customRow.withoutListed(both).items.isEmpty())
    }

    @Test
    fun `turning the switches off brings the titles back`() {
        val rowOn = addonRow.withoutListed(both)
        val rowOff = addonRow.withoutListed(both.copy(hidePlan = false, hideWatching = false))

        assertEquals(listOf("free", "done"), rowOn.items.map { it.id })
        assertEquals(addonRow.items, rowOff.items)
    }

    @Test
    fun `a title under focus stays until focus leaves the row`() {
        val focused = addonRow.stableKey()

        val held = listOf(addonRow).withoutListed(both, focused, listOf(addonRow))
        assertEquals(addonRow.items, held.single().items)

        val released = listOf(addonRow).withoutListed(both, null, listOf(addonRow))
        assertEquals(listOf("free", "done"), released.single().items.map { it.id })
    }

    @Test
    fun `a row hiding leaves short asks for more titles`() {
        val source = addonRow.copy(hasMore = true)
        val shown = source.withoutListed(both)

        assertTrue(shouldRefillAfterHidingWatched(source, shown, pagesFetched = 0))
        assertFalse(shouldRefillAfterHidingWatched(source, shown, pagesFetched = WATCHED_REFILL_MAX_PAGES))
        assertFalse(shouldRefillAfterHidingWatched(source.copy(hasMore = false), shown, pagesFetched = 0))
    }

    @Test
    fun `listed titles are known by every id, and Watching includes what is in progress`() {
        val entry = LibraryEntry(
            id = "wetrakr:42",
            type = "movie",
            name = "Dune",
            poster = null,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = setOf("wetrakr:status:planning"),
            imdbId = "tt15239678",
            tmdbId = 693134
        )
        val tabs = listOf(
            LibraryListTab("wetrakr:status:planning", "planning", LibraryListTab.Type.WATCHLIST),
            LibraryListTab("wetrakr:status:watching", "watching", LibraryListTab.Type.STATUS),
            LibraryListTab("wetrakr:list:7", "Mine", LibraryListTab.Type.PERSONAL)
        )
        val custom = entry.copy(id = "tt1", imdbId = null, tmdbId = null, listKeys = setOf("wetrakr:list:7"))

        val titles = listedTitles(tabs, listOf(entry, custom), inProgressIds = listOf("tt2"))

        assertEquals(setOf("wetrakr:42", "tt15239678", "tmdb:693134"), titles.planIds)
        assertEquals(setOf("tt2"), titles.watchingIds)
        assertEquals(setOf("wetrakr:status:planning"), titles.planListKeys)
        assertEquals(setOf("wetrakr:status:watching"), titles.watchingListKeys)
    }
}
