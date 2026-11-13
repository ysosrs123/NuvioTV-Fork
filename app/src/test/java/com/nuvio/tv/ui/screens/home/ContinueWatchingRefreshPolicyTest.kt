package com.nuvio.tv.ui.screens.home

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingRefreshPolicyTest {
    private val now = Instant.parse("2026-10-18T10:00:00Z").toEpochMilli()
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    @Test
    fun `stored episode list is fetched again after a day`() {
        val refreshAt = cwMetaRefreshDueAtMs(nextEpisodeAirMs = null, nowMs = now)

        assertFalse(shouldRefreshCwMeta(refreshAt, now + day - 1, playerOpen = false))
        assertTrue(shouldRefreshCwMeta(refreshAt, now + day, playerOpen = false))
    }

    @Test
    fun `stored episode list is fetched again once its next episode has aired`() {
        val airsAt = now + 2 * hour
        val refreshAt = cwMetaRefreshDueAtMs(nextEpisodeAirMs = airsAt, nowMs = now)

        assertEquals(airsAt, refreshAt)
        assertFalse(shouldRefreshCwMeta(refreshAt, airsAt - 1, playerOpen = false))
        assertTrue(shouldRefreshCwMeta(refreshAt, airsAt, playerOpen = false))
    }

    @Test
    fun `an air date further out than a day does not delay the refresh`() {
        val refreshAt = cwMetaRefreshDueAtMs(nextEpisodeAirMs = now + 3 * day, nowMs = now)

        assertEquals(now + day, refreshAt)
    }

    @Test
    fun `an air date already passed falls back to a day`() {
        val refreshAt = cwMetaRefreshDueAtMs(nextEpisodeAirMs = now - hour, nowMs = now)

        assertEquals(now + day, refreshAt)
    }

    @Test
    fun `film details stay fresh for 30 days, an ended show for 7, a running show until its next episode`() {
        assertEquals(now + 30 * day, cwMetaKeepUntilMs(isFilm = true, ended = false, nextEpisodeAirMs = now + hour, nowMs = now))
        assertEquals(now + 7 * day, cwMetaKeepUntilMs(isFilm = false, ended = true, nextEpisodeAirMs = null, nowMs = now))
        assertEquals(now + 2 * hour, cwMetaKeepUntilMs(isFilm = false, ended = false, nextEpisodeAirMs = now + 2 * hour, nowMs = now))
        assertEquals(now + day, cwMetaKeepUntilMs(isFilm = false, ended = false, nextEpisodeAirMs = null, nowMs = now))
    }

    @Test
    fun `episode list without a refresh time is fetched again`() {
        assertTrue(shouldRefreshCwMeta(refreshAtMs = null, nowMs = now, playerOpen = false))
    }

    @Test
    fun `no refresh while the player is open`() {
        assertFalse(shouldRefreshCwMeta(refreshAtMs = now - day, nowMs = now, playerOpen = true))
        assertFalse(shouldRefreshCwMeta(refreshAtMs = null, nowMs = now, playerOpen = true))
    }

    @Test
    fun `caught up show without a future date is checked again after one day`() {
        assertEquals(now + day, caughtUpRecheckAtMs(nextReleaseMs = null, ended = false, nowMs = now))
    }

    @Test
    fun `caught up show with a past release date is checked again after one day`() {
        assertEquals(now + day, caughtUpRecheckAtMs(nextReleaseMs = now - hour, ended = false, nowMs = now))
    }

    @Test
    fun `caught up show is checked again at its next known release`() {
        val airsAt = now + 3 * day
        assertEquals(airsAt, caughtUpRecheckAtMs(nextReleaseMs = airsAt, ended = false, nowMs = now))
        assertEquals(airsAt, caughtUpRecheckAtMs(nextReleaseMs = airsAt, ended = true, nowMs = now))
    }

    @Test
    fun `ended show without a future date keeps the weekly check`() {
        assertEquals(now + 7 * day, caughtUpRecheckAtMs(nextReleaseMs = null, ended = true, nowMs = now))
    }

    @Test
    fun `ended status is recognised`() {
        assertTrue(isEndedSeriesStatus("Ended"))
        assertTrue(isEndedSeriesStatus(" canceled "))
        assertTrue(isEndedSeriesStatus("Cancelled"))
        assertFalse(isEndedSeriesStatus("Continuing"))
        assertFalse(isEndedSeriesStatus("Returning Series"))
        assertFalse(isEndedSeriesStatus(null))
    }

    @Test
    fun `episode list recheck uses status and known dates`() {
        val running = summary(status = "Continuing", videos = listOf(episode(1, 1, "2026-10-01")))
        val ended = summary(status = "Ended", videos = listOf(episode(1, 1, "2026-10-01")))
        val upcoming = summary(
            status = "Continuing",
            videos = listOf(episode(1, 1, "2026-10-01"), episode(1, 2, "2026-10-20T01:00:00Z"))
        )

        assertEquals(now + day, running.caughtUpRecheckMs(now))
        assertEquals(now + 7 * day, ended.caughtUpRecheckMs(now))
        assertEquals(Instant.parse("2026-10-20T01:00:00Z").toEpochMilli(), upcoming.caughtUpRecheckMs(now))
    }

    @Test
    fun `older shows are looked up again once their check again time has passed`() {
        val validated = setOf("due", "fresh")
        val fresh = setOf("fresh")

        val due = olderSeedsDueAgain(
            processedContentIds = setOf("due", "fresh", "never-validated"),
            hasBeenValidated = { it in validated },
            isValidationFresh = { it in fresh }
        )

        assertEquals(setOf("due"), due)
    }

    private fun summary(status: String?, videos: List<CwVideoSummary>) = CwMetaSummary(
        id = "tt-test",
        name = "Show",
        poster = null,
        backdropUrl = null,
        logo = null,
        description = null,
        genres = emptyList(),
        releaseInfo = null,
        imdbRating = null,
        language = null,
        country = null,
        videos = videos,
        status = status
    )

    private fun episode(season: Int, episode: Int, released: String) = CwVideoSummary(
        id = "tt-test:$season:$episode",
        title = "Episode $episode",
        released = released,
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null
    )
}
