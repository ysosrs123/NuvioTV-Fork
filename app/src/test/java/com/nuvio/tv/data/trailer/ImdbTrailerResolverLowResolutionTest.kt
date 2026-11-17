package com.nuvio.tv.data.trailer

import android.content.Context
import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ImdbTrailerResolverLowResolutionTest {
    private val api = mockk<ImdbTrailerApi>()
    private val selector = mockk<ImdbStreamSelector>()
    private val resolver = ImdbTrailerResolver(mockk<Context>(relaxed = true), selector, api)
    private val video = ImdbTrailerData.Video("vi123456789", listOf(
        ImdbTrailerData.Encoding("https://imdb-video.media-imdb.com/sd.mp4", 480, true)))
    private val sd = TrailerPlaybackSource("https://imdb-video.media-imdb.com/sd.mp4")

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        coEvery { api.primaryVideo("tt1234567") } returns ImdbTrailerData.Result.Found(video)
    }
    @After fun cleanup() = unmockkStatic(Log::class)

    @Test fun `data service answer with only SD keeps the SD file for later`() = runTest {
        coEvery { selector.select(any(), any()) } returns ImdbStreamSelector.Selection(null, true, sd)
        assertNull(resolver.resolveFromDataService("tt1234567"))
        assertEquals(sd, resolver.lowResolutionFallback("tt1234567"))
    }

    @Test fun `an unsettled answer leaves no SD file`() = runTest {
        coEvery { selector.select(any(), any()) } returns ImdbStreamSelector.Selection(null, false, null)
        assertNull(resolver.resolveFromDataService("tt1234567"))
        assertNull(resolver.lowResolutionFallback("tt1234567"))
    }

    @Test fun `a failed SD playback clears the kept file`() = runTest {
        coEvery { selector.select(any(), any()) } returns ImdbStreamSelector.Selection(null, true, sd)
        resolver.resolveFromDataService("tt1234567")
        resolver.invalidate(sd.videoUrl)
        assertNull(resolver.lowResolutionFallback("tt1234567"))
    }
}
