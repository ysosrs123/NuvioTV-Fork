package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingRowKeysTest {

    private fun movie(lastWatched: Long, source: String = WatchProgress.SOURCE_LOCAL) = ContinueWatchingItem.InProgress(
        WatchProgress("tt0111161", "movie", "Movie", null, null, null,
            "tt0111161", null, null, null, 3_000_000, 8_000_000, lastWatched, source = source)
    )

    private fun episode(episode: Int) = ContinueWatchingItem.InProgress(
        WatchProgress("tt0903747", "series", "Show", null, null, null,
            "tt0903747:1:$episode", 1, episode, "Episode $episode", 1_000_000, 3_000_000, 100)
    )

    @Test
    fun theSameMovieTwice_keepsOnlyTheFirstCard() {
        val newest = movie(lastWatched = 200)
        val items = distinctContinueWatchingItems(listOf(newest, episode(3), movie(lastWatched = 100, source = "trakt")))
        assertEquals(listOf(newest, episode(3)), items)
        assertEquals(items.size, items.map(::continueWatchingItemKey).toSet().size)
    }

    @Test
    fun oneShowKeepsOneCardWhateverTheEpisode() {
        val items = listOf(episode(3), episode(4))
        assertEquals(listOf(episode(3)), distinctContinueWatchingItems(items))
        assertEquals(continueWatchingItemKey(episode(3)), continueWatchingItemKey(episode(4)))
    }

    private fun blank(name: String, season: Int? = null, episode: Int? = null) = ContinueWatchingItem.InProgress(
        WatchProgress("", if (season == null) "movie" else "series", name, null, null, null,
            "", season, episode, null, 1_000_000, 3_000_000, 100)
    )

    @Test
    fun entriesWithABlankId_neverShareAKey() {
        val merged = mergeContinueWatchingItems(
            inProgressItems = listOf(blank("One"), blank("Two"), blank("Show", 1, 2), blank("Show", 1, 3)),
            nextUpItems = emptyList()
        )
        for (mode in ContinueWatchingSortMode.entries) {
            val (main, upcoming) = splitUpcomingItems(merged, mode)
            val keys = (main + upcoming).map(::continueWatchingItemKey)
            assertEquals(keys.size, keys.toSet().size)
            assertEquals(3, keys.size)
        }
    }

    @Test
    fun everyHomeListPassesThroughTheDedupe() {
        val newest = movie(lastWatched = 200)
        val items = listOf(newest, episode(3), movie(lastWatched = 100, source = "trakt"))
        for (mode in ContinueWatchingSortMode.entries) {
            val (main, upcoming) = splitUpcomingItems(items, mode)
            assertEquals(listOf(newest, episode(3)), main + upcoming)
        }
    }
}
