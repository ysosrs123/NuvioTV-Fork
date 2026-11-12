package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class StaleLocalWatchedEpisodesTest {
    @Test
    fun `ticks the full answer has are kept when the page map only knows the last episode`() {
        val local = setOf(1 to 1, 1 to 2, 1 to 3)
        val pageMap = mapOf((1 to 3) to progress(1, 3, percent = 100f))
        val loaded = mapOf(
            (1 to 1) to progress(1, 1, percent = 100f),
            (1 to 2) to progress(1, 2, percent = 100f),
            (1 to 3) to progress(1, 3, percent = 100f)
        )

        assertEquals(emptyList<Pair<Int, Int>>(), staleLocalWatchedEpisodes(local, pageMap, loaded))
    }

    @Test
    fun `ticks missing from the full answer are stale`() {
        val local = setOf(1 to 1, 1 to 2)
        val loaded = mapOf((1 to 1) to progress(1, 1, percent = 100f))

        assertEquals(listOf(1 to 2), staleLocalWatchedEpisodes(local, loaded, loaded))
    }

    @Test
    fun `an episode only part watched on the tracker is stale`() {
        val loaded = mapOf((1 to 1) to progress(1, 1, percent = 40f))

        assertEquals(listOf(1 to 1), staleLocalWatchedEpisodes(setOf(1 to 1), loaded, loaded))
    }

    @Test
    fun `an episode just marked on the page is kept`() {
        val pageMap = mapOf((1 to 2) to progress(1, 2, percent = 100f))

        assertEquals(emptyList<Pair<Int, Int>>(), staleLocalWatchedEpisodes(setOf(1 to 2), pageMap, emptyMap()))
    }

    @Test
    fun `specials are never stale`() {
        val loaded = mapOf((1 to 1) to progress(1, 1, percent = 100f))

        assertEquals(emptyList<Pair<Int, Int>>(), staleLocalWatchedEpisodes(setOf(0 to 1, 1 to 1), loaded, loaded))
    }

    private fun progress(season: Int, episode: Int, percent: Float) = WatchProgress(
        contentId = "tt123",
        contentType = "series",
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt123:$season:$episode",
        season = season,
        episode = episode,
        episodeTitle = null,
        position = 0L,
        duration = 0L,
        lastWatched = 1L,
        progressPercent = percent
    )
}
