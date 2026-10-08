package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettings
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.TrailerSource
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbVideoResult
import com.nuvio.tv.data.remote.api.TmdbVideosResponse
import com.nuvio.tv.data.remote.api.TrailerApi
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import retrofit2.Response
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TrailerServiceImdbTest {
    private val imdb = mockk<ImdbTrailerResolver>(relaxed = true)
    private val tmdb = mockk<TmdbService>(relaxed = true)
    private val api = mockk<TmdbApi>(relaxed = true)
    private val extractor = mockk<InAppYouTubeExtractor>(relaxed = true)
    private val preferences = MutableStateFlow(TrailerSettings(source = TrailerSource.IMDB))
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC)
    private fun service(useTrailers: Boolean = true) = TrailerService(
        mockk<TrailerApi>(relaxed = true), api, extractor,
        mockk<TmdbSettingsDataStore> { every { settings } returns MutableStateFlow(TmdbSettings(useTrailers = useTrailers)) },
        tmdb, imdb, mockk<TrailerSettingsDataStore> { every { settings } returns preferences }, clock
    )

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }
    @After fun cleanup() = unmockkStatic(Log::class)

    @Test fun `IMDb title identity bypasses TMDB even when YouTube enrichment is disabled`() = runTest {
        val service = service(false)
        coEvery { imdb.resolve("tt1234567", "movie") } returns TrailerPlaybackSource("https://cdn.test/4k.mp4")
        val result = service.getTrailerPlaybackSource("Title", tmdbId = "tt1234567", type = "movie")
        assertEquals("https://cdn.test/4k.mp4", result?.videoUrl)
        coVerify(exactly = 0) { tmdb.ensureTmdbId(any(), any(), any()) }
        coVerify(exactly = 0) { tmdb.tmdbToImdb(any(), any()) }
        coVerify(exactly = 0) { api.getMovieVideos(any(), any(), any()) }
    }

    @Test fun `Home and Details names and year formats share the same canonical source`() = runTest {
        val service = service()
        coEvery { imdb.resolve(any(), any()) } returns TrailerPlaybackSource("https://cdn.test/4k.mp4")
        service.getTrailerPlaybackSource("Original", "2026", "tt1234567", "movie")
        service.getTrailerPlaybackSource("Localized", null, "tt1234567", "film")
        coVerify(exactly = 1) { imdb.resolve(any(), any()) }
    }

    @Test fun `concurrent same-title requests share one successful resolution`() = runTest {
        val service = service()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { imdb.resolve(any(), any()) } coAnswers {
            entered.complete(Unit); release.await(); TrailerPlaybackSource("https://cdn.test/v.mp4")
        }
        val a = async { service.getTrailerPlaybackSource("A", tmdbId = "tt1234567", type = "movie") }
        entered.await()
        val b = async { service.getTrailerPlaybackSource("B", tmdbId = "tt1234567", type = "movie") }
        release.complete(Unit)
        assertEquals(a.await(), b.await())
        coVerify(exactly = 1) { imdb.resolve(any(), any()) }
    }

    @Test fun `IMDb miss falls back to YouTube without pinning that choice for the session`() = runTest {
        val service = service()
        coEvery { imdb.resolve(any(), any()) } returns null
        coEvery { tmdb.ensureTmdbId("tt1234567", "movie") } returns "123"
        every { tmdb.apiKey() } returns "key"
        coEvery { api.getMovieVideos(any(), any(), any()) } returns Response.success(TmdbVideosResponse(123,
            listOf(TmdbVideoResult(key = "abcdefghijk", site = "YouTube", type = "Trailer"))))
        coEvery { extractor.extractPlaybackSource(any()) } returns TrailerPlaybackSource("https://cdn.test/youtube.mp4")
        val result = service.getTrailerPlaybackSource("A", tmdbId = "tt1234567", type = "movie")
        assertEquals("https://cdn.test/youtube.mp4", result?.videoUrl)
        assertEquals(clock.millis() + 30_000L, result?.validUntilMs)
    }

    @Test fun `cancelled IMDb lookup is not cached as a miss`() = runTest {
        val service = service()
        coEvery { imdb.resolve(any(), any()) } throws CancellationException("focus moved")
        assertTrue(runCatching { service.getTrailerPlaybackSource("A", tmdbId = "tt1234567") }.exceptionOrNull() is CancellationException)
        coEvery { imdb.resolve(any(), any()) } returns TrailerPlaybackSource("https://cdn.test/v.mp4")
        assertNotNull(service.getTrailerPlaybackSource("A", tmdbId = "tt1234567"))
    }
}
