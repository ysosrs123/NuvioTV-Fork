package com.nuvio.tv.data.wetrakr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTrakrProgressProjectionTest {
    private val dune = WeTrakrIds(wetrakr = 126, imdb = "tt15239678", tmdb = 693134)
    private val severance = WeTrakrIds(wetrakr = 900, imdb = "tt11280740", tmdb = 95396)

    private fun playback(ids: WeTrakrIds, type: WeTrakrType, progress: Float, at: String, season: Int? = null, number: Int? = null) =
        WeTrakrPlayback(
            type, WeTrakrTitle(if (type == WeTrakrType.MOVIE) WeTrakrType.MOVIE else WeTrakrType.SHOW, ids, "Title", poster = "p"),
            progress, at, season, number, runtimeSeconds = 3_600
        )

    @Test
    fun `paused titles become continue watching with the app's watched point`() {
        val projection = WeTrakrProgressProjection(
            WeTrakrSnapshot(
                42,
                playback = listOf(
                    playback(dune, WeTrakrType.MOVIE, 45f, "2026-11-03T09:00:00.000Z"),
                    playback(severance, WeTrakrType.EPISODE, 92f, "2026-11-03T08:00:00.000Z", 1, 2)
                )
            )
        )
        val item = projection.progress.single()
        assertEquals("tt15239678", item.contentId)
        assertEquals(45f, item.progressPercent)
        assertEquals(3_600_000L, item.duration)
        assertEquals("wetrakr", item.trackingProviderId)
        assertEquals("https://wetrakr.com/movies/126", item.trackingSourceUrl)
        assertEquals(0.9f, item.completionThresholdOverride)
    }

    @Test
    fun `a title watched after it was paused leaves continue watching`() {
        val projection = WeTrakrProgressProjection(
            WeTrakrSnapshot(
                42,
                watchedMovies = listOf(WeTrakrWatchedMovie(dune, "2026-11-03T10:00:00.000Z")),
                playback = listOf(playback(dune, WeTrakrType.MOVIE, 45f, "2026-11-03T09:00:00.000Z"))
            )
        )
        assertTrue(projection.progress.isEmpty())
        assertTrue(projection.isWatched("tt15239678", null, null))
        assertTrue(projection.isWatched("tmdb:693134", null, null))
    }

    @Test
    fun `watched episodes answer for every id of the show and seed next up`() {
        val projection = WeTrakrProgressProjection(
            WeTrakrSnapshot(42, watchedEpisodes = listOf(WeTrakrWatchedEpisode(900, severance, 1, 3, null, "2026-11-03T10:00:00.000Z")))
        )
        assertTrue(projection.isWatched("tt11280740", 1, 3))
        assertTrue(projection.isWatched("tmdb:95396", 1, 3))
        assertFalse(projection.isWatched("tt11280740", 1, 4))
        assertEquals(listOf("tt11280740:1:3"), projection.nextUp(false).map { it.videoId })
        assertEquals("series", projection.watchedItems.single().contentType)
    }

    @Test
    fun `dropped shows are hidden and give no next up`() {
        val projection = WeTrakrProgressProjection(
            WeTrakrSnapshot(
                42,
                watchedEpisodes = listOf(WeTrakrWatchedEpisode(900, severance, 1, 3, null, "2026-11-03T10:00:00.000Z")),
                playback = listOf(playback(severance, WeTrakrType.EPISODE, 20f, "2026-11-03T11:00:00.000Z", 1, 4)),
                lists = mapOf(WeTrakrList.DROPPED to listOf(WeTrakrListEntry(WeTrakrTitle(WeTrakrType.SHOW, severance))))
            )
        )
        assertTrue(projection.isHidden("tt11280740"))
        assertTrue(projection.progress.isEmpty())
        assertTrue(projection.nextUp(false).isEmpty())
    }

    @Test
    fun `titles from the lists name the watched items`() {
        val projection = WeTrakrProgressProjection(
            WeTrakrSnapshot(
                42,
                watchedMovies = listOf(WeTrakrWatchedMovie(WeTrakrIds(imdb = "tt15239678"), "2026-11-03T10:00:00.000Z")),
                lists = mapOf(WeTrakrList.WATCHED to listOf(WeTrakrListEntry(WeTrakrTitle(WeTrakrType.MOVIE, dune, "Dune", 2021, "poster"))))
            )
        )
        val item = projection.watchedItems.single()
        assertEquals("Dune", item.title)
        assertEquals("poster", item.poster)
        assertEquals("2021", item.releaseInfo)
    }
}
