package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTrakrLibraryTest {
    private val dune = WeTrakrIds(wetrakr = 126, imdb = "tt15239678")
    private val severance = WeTrakrIds(wetrakr = 900, imdb = "tt11280740")

    @Test
    fun `lists become library tabs, waiting shows sit under Watching`() {
        val snapshot = WeTrakrSnapshot(
            42,
            lists = mapOf(
                WeTrakrList.PLANNING to listOf(WeTrakrListEntry(WeTrakrTitle(WeTrakrType.MOVIE, dune, "Dune"), "2026-11-02T00:00:00.000Z")),
                WeTrakrList.WAITING to listOf(WeTrakrListEntry(WeTrakrTitle(WeTrakrType.SHOW, severance, "Severance"), "2026-11-03T00:00:00.000Z"))
            )
        )
        val entries = snapshot.libraryEntries()
        assertEquals(listOf("tt11280740", "tt15239678"), entries.map { it.id })
        assertEquals(setOf("wetrakr:status:watching"), entries.first().listKeys)
        assertEquals("series", entries.first().type)
        assertEquals("https://wetrakr.com/shows/900", entries.first().trackingSourceUrl)
        assertEquals(setOf(WeTrakrList.PLANNING), snapshot.listsOf("tt15239678", "movie"))
        assertTrue(snapshot.listsOf("tt15239678", "series").isEmpty())
    }

    @Test
    fun `the history upload skips what WeTrakr already has and what has no usable id`() {
        val watched = listOf(
            WatchedItem("tt15239678", "movie", "Dune", watchedAt = 1L),
            WatchedItem("tt11280740", "series", "Severance", season = 1, episode = 2, watchedAt = 2L),
            WatchedItem("local:abc", "movie", "Home video", watchedAt = 3L)
        )
        val saved = listOf(saved("tt15239678", "movie"), saved("tt0903747", "series"), saved("tt11280740", "series"))
        val snapshot = WeTrakrSnapshot(
            42, lists = mapOf(WeTrakrList.WATCHING to listOf(WeTrakrListEntry(WeTrakrTitle(WeTrakrType.SHOW, severance))))
        )
        val plan = weTrakrUploadPlan(watched, saved, snapshot)
        assertEquals(2, plan.watchedCount)
        assertEquals(listOf("tt0903747"), plan.library.map { it.ids.imdb })
    }

    private fun saved(id: String, type: String) = SavedLibraryItem(
        id = id, type = type, name = id, poster = null, posterShape = PosterShape.POSTER, background = null,
        description = null, releaseInfo = null, imdbRating = null, genres = emptyList(), addonBaseUrl = null
    )
}
