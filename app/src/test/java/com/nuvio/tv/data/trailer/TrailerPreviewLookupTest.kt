package com.nuvio.tv.data.trailer

import com.nuvio.tv.core.tmdb.TmdbService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerPreviewLookupTest {

    private val trailerService = mockk<TrailerService>(relaxed = true)
    private val tmdbService = mockk<TmdbService>(relaxed = true)

    private suspend fun lookup(imdbId: String? = null, ytIds: List<String> = emptyList()) = lookupTrailerPreview(
        trailerService = trailerService,
        tmdbService = tmdbService,
        itemId = "kitsu:1",
        apiType = "movie",
        title = "Title",
        year = "2026",
        imdbId = imdbId,
        ytIds = ytIds
    )

    @Test
    fun `the IMDb id bypasses the TMDB lookup`() = runTest {
        coEvery { tmdbService.ensureTmdbId("kitsu:1", "movie", "tt0137523") } returns "550"
        coEvery { trailerService.lookupTrailer("Title", "2026", "tt0137523", "movie", any()) } returns
            TrailerLookupResult(TrailerPlaybackSource("https://example.test/trailer.mp4"))

        val result = lookup(imdbId = "tt0137523")

        assertEquals("https://example.test/trailer.mp4", result.source?.videoUrl)
        assertFalse(result.rememberMiss)
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any(), any()) }
    }

    @Test
    fun `a failed id lookup still tries the title`() = runTest {
        coEvery { tmdbService.ensureTmdbId(any(), any(), any()) } throws IllegalStateException("offline")
        coEvery { trailerService.lookupTrailer(any(), any(), isNull(), any(), any()) } returns
            TrailerLookupResult(TrailerPlaybackSource("https://example.test/by-title.mp4"))

        assertEquals("https://example.test/by-title.mp4", lookup().source?.videoUrl)
    }

    @Test
    fun `the item's trailer ids are tried in order when the lookup misses`() = runTest {
        coEvery { tmdbService.ensureTmdbId(any(), any(), any()) } returns null
        coEvery { trailerService.lookupTrailer(any(), any(), any(), any(), any()) } returns
            TrailerLookupResult.DEFINITE_MISS
        coEvery {
            trailerService.lookupYouTubeTrailer("https://www.youtube.com/watch?v=aaaaaaaaaaa", any(), any())
        } returns YouTubeTrailerLookup(null)
        coEvery {
            trailerService.lookupYouTubeTrailer("https://www.youtube.com/watch?v=bbbbbbbbbbb", any(), any())
        } returns YouTubeTrailerLookup(TrailerPlaybackSource("https://example.test/second-id.mp4"))

        val result = lookup(ytIds = listOf("aaaaaaaaaaa", "bbbbbbbbbbb"))

        assertEquals("https://example.test/second-id.mp4", result.source?.videoUrl)
        assertFalse(result.rememberMiss)
    }

    @Test
    fun `only a definite miss with nothing left to try is remembered`() = runTest {
        coEvery { tmdbService.ensureTmdbId(any(), any(), any()) } returns "550"
        coEvery { trailerService.lookupYouTubeTrailer(any(), any(), any()) } returns YouTubeTrailerLookup(null)

        coEvery { trailerService.lookupTrailer(any(), any(), any(), any(), any()) } returns
            TrailerLookupResult.DEFINITE_MISS
        assertTrue(lookup().rememberMiss)
        assertFalse(lookup(ytIds = listOf("aaaaaaaaaaa")).rememberMiss)

        coEvery { trailerService.lookupTrailer(any(), any(), any(), any(), any()) } returns
            TrailerLookupResult(null, definiteMiss = false)
        val timedOut = lookup()
        assertNull(timedOut.source)
        assertFalse(timedOut.rememberMiss)
    }

    @Test
    fun `a title YouTube refuses is remembered`() = runTest {
        coEvery { tmdbService.ensureTmdbId(any(), any(), any()) } returns "550"
        coEvery { trailerService.lookupTrailer(any(), any(), any(), any(), any()) } returns
            TrailerLookupResult(null, refused = true)
        assertTrue(lookup().rememberMiss)

        coEvery { trailerService.lookupYouTubeTrailer(any(), any(), any()) } returns
            YouTubeTrailerLookup(null, YouTubeUnplayableReason.SIGN_IN_REQUIRED)
        assertTrue(lookup(ytIds = listOf("aaaaaaaaaaa")).rememberMiss)

        coEvery { trailerService.lookupTrailer(any(), any(), any(), any(), any()) } returns
            TrailerLookupResult.DEFINITE_MISS
        assertTrue(lookup(ytIds = listOf("aaaaaaaaaaa")).rememberMiss)
    }

    @Test
    fun `a refusal next to a failed request is not remembered`() = runTest {
        coEvery { tmdbService.ensureTmdbId(any(), any(), any()) } returns "550"
        coEvery { trailerService.lookupTrailer(any(), any(), any(), any(), any()) } returns
            TrailerLookupResult(null, refused = true)
        coEvery { trailerService.lookupYouTubeTrailer(any(), any(), any()) } returns YouTubeTrailerLookup(null)
        assertFalse(lookup(ytIds = listOf("aaaaaaaaaaa")).rememberMiss)

        coEvery { trailerService.lookupTrailer(any(), any(), any(), any(), any()) } returns
            TrailerLookupResult(null, definiteMiss = false)
        coEvery { trailerService.lookupYouTubeTrailer(any(), any(), any()) } returns
            YouTubeTrailerLookup(null, YouTubeUnplayableReason.UNAVAILABLE)
        assertFalse(lookup(ytIds = listOf("aaaaaaaaaaa")).rememberMiss)
    }

    @Test
    fun `trailer ids are checked, de-duplicated and capped at three`() {
        assertEquals(
            listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"),
            trailerPreviewYtIds(
                listOf(
                    "vi1234567890",
                    "aaaaaaaaaaa",
                    "https://youtu.be/bbbbbbbbbbb",
                    "aaaaaaaaaaa",
                    "ccccccccccc",
                    "ddddddddddd"
                )
            )
        )
    }

    @Test
    fun `a miss that may pass is remembered for two minutes`() {
        val now = 100 * 60_000L
        assertEquals(now, trailerPreviewMissTimestamp(definite = true, nowMs = now))
        val stored = trailerPreviewMissTimestamp(definite = false, nowMs = now)
        assertTrue(isTrailerPreviewMissFresh(stored, now + 119_000L))
        assertFalse(isTrailerPreviewMissFresh(stored, now + 120_000L))
    }

    @Test
    fun `the lookup waits longer when the trailer shows later`() {
        assertEquals(350L, trailerPreviewLookupSettleMs(showAfterMs = 0L))
        assertEquals(350L, trailerPreviewLookupSettleMs(showAfterMs = 2_000L))
        assertEquals(800L, trailerPreviewLookupSettleMs(showAfterMs = 3_000L))
        assertEquals(1_100L, trailerPreviewLookupSettleMs(showAfterMs = 5_000L))
        assertEquals(1_100L, trailerPreviewLookupSettleMs(showAfterMs = 30_000L))
    }

    @Test
    fun `a miss is remembered for ten minutes`() {
        assertTrue(isTrailerPreviewMissFresh(missedAtMs = 1_000L, nowMs = 1_000L + 9 * 60_000L))
        assertFalse(isTrailerPreviewMissFresh(missedAtMs = 1_000L, nowMs = 1_000L + 10 * 60_000L))
    }

    @Test
    fun `a stored link is expired when its video or its audio link is`() {
        val now = Instant.ofEpochSecond(1_800_000_000L)
        val fresh = "https://rr1.googlevideo.com/videoplayback?expire=1800020000"
        val expired = "https://rr1.googlevideo.com/videoplayback?expire=1800000100"

        assertFalse(isTrailerPreviewLinkExpired(fresh, null, now))
        assertFalse(isTrailerPreviewLinkExpired(fresh, fresh, now))
        assertTrue(isTrailerPreviewLinkExpired(expired, null, now))
        assertTrue(isTrailerPreviewLinkExpired(fresh, expired, now))
    }

    @Test
    fun `a link that fails to play is looked up once more per ten minutes`() {
        assertTrue(shouldRetryTrailerPreviewAfterFailure(lastRetryAtMs = null, nowMs = 5_000L))
        assertFalse(shouldRetryTrailerPreviewAfterFailure(lastRetryAtMs = 5_000L, nowMs = 5_000L + 60_000L))
        assertTrue(shouldRetryTrailerPreviewAfterFailure(lastRetryAtMs = 5_000L, nowMs = 5_000L + 10 * 60_000L))
    }
}
