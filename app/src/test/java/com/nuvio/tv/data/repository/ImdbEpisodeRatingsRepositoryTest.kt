package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.ImdbTapframeApi
import com.nuvio.tv.data.remote.api.SeriesGraphApi
import com.nuvio.tv.data.remote.api.SeriesGraphEpisodeRatingDto
import com.nuvio.tv.data.remote.api.SeriesGraphSeasonRatingsDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.net.UnknownHostException

class ImdbEpisodeRatingsRepositoryTest {

    private val backup = listOf(
        SeriesGraphSeasonRatingsDto(
            episodes = listOf(SeriesGraphEpisodeRatingDto(seasonNumber = 1, episodeNumber = 1, voteAverage = 8.1))
        )
    )

    @Test
    fun `a primary host that does not resolve is skipped on the next series`() = runBlocking {
        val primary = mockk<ImdbTapframeApi>()
        coEvery { primary.getSeasonRatings(any()) } throws UnknownHostException("imdb.tapframe.space")
        val fallback = mockk<SeriesGraphApi>()
        coEvery { fallback.getSeasonRatings(any()) } returns Response.success(backup)
        val repository = ImdbEpisodeRatingsRepository(primary, fallback).apply { primaryConfigured = true }

        assertEquals(mapOf((1 to 1) to 8.1), repository.getEpisodeRatings("tt0000001", 11))
        assertEquals(mapOf((1 to 1) to 8.1), repository.getEpisodeRatings("tt0000002", 12))

        coVerify(exactly = 1) { primary.getSeasonRatings(any()) }
        coVerify(exactly = 2) { fallback.getSeasonRatings(any()) }
    }

    @Test
    fun `a primary that answers is still asked first`() = runBlocking {
        val primary = mockk<ImdbTapframeApi>()
        coEvery { primary.getSeasonRatings(any()) } returns Response.success(backup)
        val fallback = mockk<SeriesGraphApi>()
        val repository = ImdbEpisodeRatingsRepository(primary, fallback).apply { primaryConfigured = true }

        repository.getEpisodeRatings("tt0000001", 11)
        repository.getEpisodeRatings("tt0000002", 12)

        coVerify(exactly = 2) { primary.getSeasonRatings(any()) }
        coVerify(exactly = 0) { fallback.getSeasonRatings(any()) }
    }

    @Test
    fun `no primary address goes straight to the backup`() = runBlocking {
        val primary = mockk<ImdbTapframeApi>()
        val fallback = mockk<SeriesGraphApi>()
        coEvery { fallback.getSeasonRatings(any()) } returns Response.success(backup)
        val repository = ImdbEpisodeRatingsRepository(primary, fallback).apply { primaryConfigured = false }

        assertEquals(mapOf((1 to 1) to 8.1), repository.getEpisodeRatings("tt0000001", 11))
        coVerify(exactly = 0) { primary.getSeasonRatings(any()) }
    }

    @Test
    fun `an outage lasts a day when the network works and ten minutes when it may be offline`() {
        var now = 1_000L
        val outage = HostOutage(now = { now })

        outage.markDown(networkUp = false)
        now += HostOutage.OFFLINE_MS - 1
        assertTrue(outage.isDown())
        now += 1
        assertFalse(outage.isDown())

        outage.markDown(networkUp = true)
        now += HostOutage.GONE_MS - 1
        assertTrue(outage.isDown())
        now += 1
        assertFalse(outage.isDown())
    }

    @Test
    fun `only connection failures count as an unreachable host`() {
        assertTrue(HostOutage.isUnreachable(UnknownHostException("x")))
        assertTrue(HostOutage.isUnreachable(java.io.IOException("wrapped", java.net.ConnectException("refused"))))
        assertFalse(HostOutage.isUnreachable(java.net.SocketTimeoutException("slow")))
    }
}
