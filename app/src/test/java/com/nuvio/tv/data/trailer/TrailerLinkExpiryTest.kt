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
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class TrailerLinkExpiryTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val start = Instant.ofEpochSecond(1_800_000_000L)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `reads expiry from youtube query, youtube path and imdb links`() {
        assertEquals(
            Instant.ofEpochSecond(1_800_021_600L),
            trailerUrlExpiresAt("https://rr1.googlevideo.com/videoplayback?expire=1800021600&ei=x&sparams=expire,ei")
        )
        assertEquals(
            Instant.ofEpochSecond(1_800_021_600L),
            trailerUrlExpiresAt("https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1800021600/ei/x/file/index.m3u8")
        )
        assertEquals(
            Instant.ofEpochSecond(1_800_003_600L),
            trailerUrlExpiresAt("https://imdb-video.media-imdb.com/vi123/1434659607842-pgv4ql-1563742642.mp4?Expires=1800003600&Signature=abc&Key-Pair-Id=K")
        )
        assertNull(trailerUrlExpiresAt("https://cdn.example/trailer.mp4?sparams=expire,ei"))
    }

    @Test
    fun `link counts as expired fifteen minutes before its expiry`() {
        val url = "https://rr1.googlevideo.com/videoplayback?expire=${start.epochSecond + 3600}"
        assertFalse(isTrailerUrlExpired(url, start.plus(Duration.ofMinutes(44))))
        assertTrue(isTrailerUrlExpired(url, start.plus(Duration.ofMinutes(45))))
        assertFalse(isTrailerUrlExpired("https://cdn.example/trailer.mp4", start.plus(Duration.ofDays(3))))
    }

    @Test
    fun `title cache drops an expired youtube link and resolves again`() = runTest {
        val clock = MutableClock(start)
        val harness = newService(clock, TrailerSource.YOUTUBE)
        coEvery { harness.tmdbApi.getMovieVideos(any(), any(), any()) } returns Response.success(
            TmdbVideosResponse(
                id = 123,
                results = listOf(
                    TmdbVideoResult(iso6391 = "en", key = "abcdefghijk", site = "YouTube", type = "Trailer", official = true)
                )
            )
        )
        val first = "https://rr1.googlevideo.com/videoplayback?expire=${start.epochSecond + 6 * 3600}&id=1"
        val second = "https://rr1.googlevideo.com/videoplayback?expire=${start.epochSecond + 12 * 3600}&id=2"
        coEvery { harness.extractor.extractPlaybackSource(any()) } returnsMany listOf(
            TrailerPlaybackSource(videoUrl = first),
            TrailerPlaybackSource(videoUrl = second)
        )

        assertEquals(first, harness.lookup()?.videoUrl)
        clock.now = start.plus(Duration.ofHours(5))
        assertEquals(first, harness.lookup()?.videoUrl)
        clock.now = start.plus(Duration.ofHours(6))
        assertEquals(second, harness.lookup()?.videoUrl)
        coVerify(exactly = 2) { harness.extractor.extractPlaybackSource(any()) }
    }

    @Test
    fun `title cache drops an expired imdb link and resolves again`() = runTest {
        val clock = MutableClock(start)
        val harness = newService(clock, TrailerSource.IMDB)
        coEvery { harness.tmdbService.tmdbToImdb(123, "movie") } returns "tt0137523"
        val first = "https://imdb-video.media-imdb.com/vi1/a.mp4?Expires=${start.epochSecond + 3600}&Signature=s"
        val second = "https://imdb-video.media-imdb.com/vi1/a.mp4?Expires=${start.epochSecond + 7200}&Signature=t"
        coEvery { harness.imdb.resolve("tt0137523", any()) } returnsMany listOf(
            TrailerPlaybackSource(videoUrl = first),
            TrailerPlaybackSource(videoUrl = second)
        )

        assertEquals(first, harness.lookup()?.videoUrl)
        clock.now = start.plus(Duration.ofMinutes(30))
        assertEquals(first, harness.lookup()?.videoUrl)
        clock.now = start.plus(Duration.ofMinutes(50))
        assertEquals(second, harness.lookup()?.videoUrl)
    }

    @Test
    fun `no tmdb trailers is a definite miss, failed extraction is not`() = runTest {
        val clock = MutableClock(start)
        val harness = newService(clock, TrailerSource.YOUTUBE)
        coEvery { harness.tmdbApi.getMovieVideos(any(), any(), any()) } returns
            Response.success(TmdbVideosResponse(id = 123, results = emptyList()))
        val none = harness.target.lookupTrailer("Some Movie", "2024", "123", "movie")
        assertNull(none.source)
        assertTrue(none.definiteMiss)

        val other = newService(clock, TrailerSource.YOUTUBE)
        coEvery { other.tmdbApi.getMovieVideos(any(), any(), any()) } returns Response.success(
            TmdbVideosResponse(
                id = 123,
                results = listOf(TmdbVideoResult(iso6391 = "en", key = "abcdefghijk", site = "YouTube", type = "Trailer"))
            )
        )
        coEvery { other.extractor.extractPlaybackSource(any()) } returnsMany listOf(
            null,
            TrailerPlaybackSource(videoUrl = "https://cdn.example/second-try.mp4")
        )
        val failed = other.target.lookupTrailer("Some Movie", "2024", "123", "movie")
        assertNull(failed.source)
        assertFalse(failed.definiteMiss)
        assertEquals("https://cdn.example/second-try.mp4", other.lookup()?.videoUrl)
    }

    @Test
    fun `a failed tmdb request is not a definite miss`() = runTest {
        val harness = newService(MutableClock(start), TrailerSource.YOUTUBE)
        coEvery { harness.tmdbApi.getMovieVideos(any(), any(), any()) } throws java.io.IOException("timeout")
        val result = harness.target.lookupTrailer("Some Movie", "2024", "123", "movie")
        assertNull(result.source)
        assertFalse(result.definiteMiss)
    }

    @Test
    fun `a missing tmdb id is not a definite miss`() = runTest {
        val harness = newService(MutableClock(start), TrailerSource.YOUTUBE)
        val result = harness.target.lookupTrailer("Some Movie", "2024", null, "movie")
        assertNull(result.source)
        assertFalse(result.definiteMiss)
    }

    @Test
    fun `only the first three tmdb trailers are tried`() = runTest {
        val harness = newService(MutableClock(start), TrailerSource.YOUTUBE)
        coEvery { harness.tmdbApi.getMovieVideos(any(), any(), any()) } returns fiveTrailers()
        coEvery { harness.extractor.extractPlaybackSource(any()) } returns null
        every { harness.extractor.unplayableReason(any()) } returns YouTubeUnplayableReason.UNAVAILABLE

        val result = harness.target.lookupTrailer("Some Movie", "2024", "123", "movie")

        assertNull(result.source)
        assertFalse(result.definiteMiss)
        coVerify(exactly = 3) { harness.extractor.extractPlaybackSource(any()) }
    }

    @Test
    fun `a sign-in answer ends the lookup at that trailer`() = runTest {
        val harness = newService(MutableClock(start), TrailerSource.YOUTUBE)
        coEvery { harness.tmdbApi.getMovieVideos(any(), any(), any()) } returns fiveTrailers()
        coEvery { harness.extractor.extractPlaybackSource(any()) } returns null
        every { harness.extractor.unplayableReason(any()) } returns YouTubeUnplayableReason.SIGN_IN_REQUIRED

        val result = harness.target.lookupTrailer("Some Movie", "2024", "123", "movie")

        assertNull(result.source)
        assertFalse(result.definiteMiss)
        coVerify(exactly = 1) { harness.extractor.extractPlaybackSource(any()) }
    }

    private fun fiveTrailers() = Response.success(
        TmdbVideosResponse(
            id = 123,
            results = listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc", "ddddddddddd", "eeeeeeeeeee").map { key ->
                TmdbVideoResult(iso6391 = "en", key = key, site = "YouTube", type = "Trailer")
            }
        )
    )

    @Test
    fun `title cache drops a link that failed to play`() = runTest {
        val harness = newService(MutableClock(start), TrailerSource.IMDB)
        coEvery { harness.tmdbService.tmdbToImdb(123, "movie") } returns "tt0137523"
        coEvery { harness.imdb.resolve("tt0137523", any()) } returnsMany listOf(
            TrailerPlaybackSource(videoUrl = "https://cdn.example/broken.mp4"),
            TrailerPlaybackSource(videoUrl = "https://cdn.example/working.mp4")
        )

        assertEquals("https://cdn.example/broken.mp4", harness.lookup()?.videoUrl)
        TrailerPlaybackFailures.report("https://cdn.example/broken.mp4")
        assertEquals("https://cdn.example/working.mp4", harness.lookup()?.videoUrl)
        assertEquals("https://cdn.example/working.mp4", harness.lookup()?.videoUrl)
    }

    @Test
    fun `a link without expiry is kept for three hours`() = runTest {
        val clock = MutableClock(start)
        val harness = newService(clock, TrailerSource.IMDB)
        coEvery { harness.tmdbService.tmdbToImdb(123, "movie") } returns "tt0137523"
        coEvery { harness.imdb.resolve("tt0137523", any()) } returnsMany listOf(
            TrailerPlaybackSource(videoUrl = "https://cdn.example/one.mp4"),
            TrailerPlaybackSource(videoUrl = "https://cdn.example/two.mp4")
        )

        assertEquals("https://cdn.example/one.mp4", harness.lookup()?.videoUrl)
        clock.now = start.plus(Duration.ofMinutes(179))
        assertEquals("https://cdn.example/one.mp4", harness.lookup()?.videoUrl)
        clock.now = start.plus(Duration.ofHours(3))
        assertEquals("https://cdn.example/two.mp4", harness.lookup()?.videoUrl)
    }

    private class Harness(
        val target: TrailerService,
        val tmdbApi: TmdbApi,
        val extractor: InAppYouTubeExtractor,
        val tmdbService: TmdbService,
        val imdb: ImdbTrailerResolver
    ) {
        suspend fun lookup(): TrailerPlaybackSource? = target.getTrailerPlaybackSource(
            title = "Some Movie",
            year = "2024",
            tmdbId = "123",
            type = "movie"
        )
    }

    private fun newService(clock: Clock, source: TrailerSource): Harness {
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val extractor = mockk<InAppYouTubeExtractor>(relaxed = true)
        val tmdbService = mockk<TmdbService>(relaxed = true) {
            every { apiKey() } returns "tmdb-key"
        }
        val imdb = mockk<ImdbTrailerResolver>(relaxed = true)
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> {
            every { settings } returns MutableStateFlow(TmdbSettings(language = "en", useTrailers = true))
        }
        val trailerSettingsDataStore = mockk<TrailerSettingsDataStore> {
            every { settings } returns MutableStateFlow(TrailerSettings(source = source))
        }
        return Harness(
            target = TrailerService(
                trailerApi = mockk(relaxed = true),
                tmdbApi = tmdbApi,
                inAppYouTubeExtractor = extractor,
                tmdbSettingsDataStore = tmdbSettingsDataStore,
                tmdbService = tmdbService,
                imdbTrailerResolver = imdb,
                trailerSettingsDataStore = trailerSettingsDataStore,
                clock = clock
            ),
            tmdbApi = tmdbApi,
            extractor = extractor,
            tmdbService = tmdbService,
            imdb = imdb
        )
    }
}
