package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.local.mixPlayKey
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueWatchingMixPlaysTest {
    private fun progress(id: String, season: Int?, episode: Int?, source: String = WatchProgress.SOURCE_LOCAL) = WatchProgress(
        contentId = id, contentType = "series", name = id, poster = null, backdrop = null, logo = null,
        videoId = "$id:$season:$episode", season = season, episode = episode, episodeTitle = null,
        position = 600_000L, duration = 2_400_000L, lastWatched = 1L, source = source
    )

    @Test
    fun `an episode last played in a Mix stays out, local or from Trakt, and other episodes stay in`() {
        val mixPlayed = setOf(mixPlayKey("tt1", 1, 7))
        val local = progress("tt1", 1, 7)
        val trakt = progress("tt1", 1, 7, WatchProgress.SOURCE_TRAKT_PLAYBACK)
        val other = progress("tt1", 1, 6)
        val film = progress("tt2", null, null)
        assertEquals(listOf(other, film), withoutMixPlays(listOf(local, trakt, other, film), mixPlayed))
    }

    @Test
    fun `nothing played in a Mix leaves the list as it is`() {
        val items = listOf(progress("tt1", 1, 7))
        assertEquals(items, withoutMixPlays(items, emptySet()))
    }

    private fun watched(id: String, season: Int, episode: Int, at: Long) =
        WatchedItem(contentId = id, contentType = "series", title = id, season = season, episode = episode, watchedAt = at)

    @Test
    fun `a show whose furthest episode was played in a Mix keeps its Next up from the episodes watched outside it`() {
        val mixPlayed = setOf(mixPlayKey("tt1", 4, 7))
        val seed = progress("tt1", 4, 7)
        val watched = listOf(watched("tt1", 2, 3, 10L), watched("tt1", 2, 4, 20L), watched("tt1", 4, 7, 30L))
        val result = withoutMixPlaySeeds(listOf(seed), mixPlayed, watched, fromFurthest = true).single()
        assertEquals(2 to 4, result.season to result.episode)
        assertEquals(20L, result.lastWatched)

        val afterNormalWatch = watched + watched("tt1", 2, 5, 40L)
        val moved = withoutMixPlaySeeds(listOf(seed), mixPlayed, afterNormalWatch, fromFurthest = true).single()
        assertEquals(2 to 5, moved.season to moved.episode)
    }

    @Test
    fun `a show watched only in a Mix has no Next up, and other shows keep theirs`() {
        val mixPlayed = setOf(mixPlayKey("tt1", 1, 2))
        val other = progress("tt2", 3, 1)
        val result = withoutMixPlaySeeds(
            listOf(progress("tt1", 1, 2), other),
            mixPlayed,
            listOf(watched("tt1", 1, 2, 5L)),
            fromFurthest = false
        )
        assertEquals(listOf(other), result)
        assertNull(result.firstOrNull { it.contentId == "tt1" })
    }
}
