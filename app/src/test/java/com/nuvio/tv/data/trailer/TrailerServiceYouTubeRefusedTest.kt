package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettings
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbVideoResult
import com.nuvio.tv.data.remote.api.TmdbVideosResponse
import com.nuvio.tv.data.remote.api.TrailerApi
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class TrailerServiceYouTubeRefusedTest {

    private var nowMs = 1_800_000_000_000L
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
        override fun millis(): Long = nowMs
    }
    private val tmdbApi = mockk<TmdbApi>()
    private val extractor = mockk<InAppYouTubeExtractor>()
    private val imdb = mockk<ImdbTrailerResolver>(relaxed = true)
    private val tmdb = mockk<TmdbService> { every { apiKey() } returns "tmdb-key" }
    private val service = TrailerService(
        trailerApi = mockk<TrailerApi>(relaxed = true),
        tmdbApi = tmdbApi,
        inAppYouTubeExtractor = extractor,
        tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> {
            every { settings } returns MutableStateFlow(TmdbSettings(language = "en", useTrailers = true))
        },
        tmdbService = tmdb,
        imdbTrailerResolver = imdb,
        trailerSettingsDataStore = mockk<TrailerSettingsDataStore> {
            every { settings } returns MutableStateFlow(TrailerSettings())
        },
        clock = clock,
        backendConfigured = false
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        coEvery { tmdbApi.getMovieVideos(any(), any(), any()) } returns Response.success(
            TmdbVideosResponse(
                id = 1,
                results = listOf(
                    TmdbVideoResult(
                        iso6391 = "en",
                        name = "Official Trailer",
                        key = "dQw4w9WgXcQ",
                        site = "YouTube",
                        type = "Trailer",
                        official = true
                    )
                )
            )
        )
        every { extractor.isRateLimited() } returns false
        every { extractor.unplayableReason(any()) } returns null
        coEvery { tmdb.tmdbToImdb(any(), any()) } answers { "tt" + firstArg<Int>().toString().padStart(7, '0') }
        coEvery { imdb.resolveFromDataService(any()) } returns null
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    private suspend fun lookup(tmdbId: String) = service.lookupTrailer("Title", "2026", tmdbId, "movie")

    private fun youTubeDemandsSignIn() {
        coEvery { extractor.extractPlaybackSource(any()) } returns null
        every { extractor.unplayableReason(any()) } returns YouTubeUnplayableReason.SIGN_IN_REQUIRED
    }

    private fun youTubeAnswers() {
        coEvery { extractor.extractPlaybackSource(any()) } returns TrailerPlaybackSource("https://cdn.example/yt.mp4")
        every { extractor.unplayableReason(any()) } returns null
    }

    @Test
    fun `YouTube is asked first and IMDb is left alone when it answers`() = runTest {
        youTubeAnswers()

        assertEquals("https://cdn.example/yt.mp4", lookup("101").source?.videoUrl)

        coVerify(exactly = 0) { imdb.resolveFromDataService(any()) }
    }

    @Test
    fun `IMDb stands in when YouTube demands a sign-in`() = runTest {
        youTubeDemandsSignIn()
        coEvery { imdb.resolveFromDataService("tt0000101") } returns TrailerPlaybackSource("https://cdn.example/imdb.mp4")

        assertEquals("https://cdn.example/imdb.mp4", lookup("101").source?.videoUrl)
    }

    @Test
    fun `the next titles skip YouTube for ten minutes, then it is asked again`() = runTest {
        youTubeDemandsSignIn()
        lookup("101")
        coVerify(exactly = 1) { extractor.extractPlaybackSource(any()) }

        coEvery { imdb.resolveFromDataService("tt0000102") } returns TrailerPlaybackSource("https://cdn.example/imdb2.mp4")
        assertEquals("https://cdn.example/imdb2.mp4", lookup("102").source?.videoUrl)
        assertNull(lookup("103").source)
        coVerify(exactly = 1) { extractor.extractPlaybackSource(any()) }

        nowMs += 10 * 60_000L
        youTubeAnswers()
        assertEquals("https://cdn.example/yt.mp4", lookup("104").source?.videoUrl)
    }

    @Test
    fun `a title YouTube removed does not switch the others to IMDb`() = runTest {
        coEvery { extractor.extractPlaybackSource(any()) } returns null
        every { extractor.unplayableReason(any()) } returns YouTubeUnplayableReason.UNAVAILABLE

        assertNull(lookup("101").source)

        coVerify(exactly = 0) { imdb.resolveFromDataService(any()) }
        youTubeAnswers()
        assertEquals("https://cdn.example/yt.mp4", lookup("102").source?.videoUrl)
    }
}
