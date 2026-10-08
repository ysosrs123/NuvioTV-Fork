package com.nuvio.tv.data.trailer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ImdbStreamSelectorTest {
    private val selector = ImdbStreamSelector()
    private val masterUrl = "https://imdb-video.media-imdb.com/master.m3u8"
    private fun video(height: Int) = ImdbTrailerData.Video("vi123456789", listOf(
        ImdbTrailerData.Encoding("https://imdb-video.media-imdb.com/video.mp4", height, true),
        ImdbTrailerData.Encoding(masterUrl, 0, false, true)))
    private fun master(height: Int) = """#EXTM3U
        |#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",URI="audio.m3u8"
        |#EXT-X-STREAM-INF:BANDWIDTH=10000000,RESOLUTION=3840x$height,AUDIO="audio"
        |video.m3u8
    """.trimMargin()

    @Test fun `4K progressive returns immediately without manifest requests`() = runTest {
        val result = selector.select(video(2160)) { fail("4K MP4 must not wait for HLS"); null }
        assertTrue(result.source!!.videoUrl.endsWith(".mp4"))
    }
    @Test fun `higher resolution HLS wins and retains master audio groups`() = runTest {
        val result = selector.select(video(1080)) { master(2160) }
        assertEquals(masterUrl, result.source?.videoUrl)
    }
    @Test fun `equal resolution prefers fast progressive startup`() = runTest {
        val result = selector.select(video(1080)) { master(1080) }
        assertTrue(result.source!!.videoUrl.endsWith(".mp4"))
    }
    @Test fun `SD across all renditions is a definitive miss`() = runTest {
        val result = selector.select(video(480)) { master(576) }
        assertNull(result.source)
        assertTrue(result.complete)
    }
    @Test fun `failed manifest is not a permanent SD classification`() = runTest {
        val result = selector.select(video(480)) { null }
        assertNull(result.source)
        assertFalse(result.complete)
    }
    @Test fun `parent cancellation propagates through manifest selection`() = runTest {
        assertTrue(runCatching {
            selector.select(video(1080)) { throw CancellationException("focus moved") }
        }.exceptionOrNull() is CancellationException)
    }
    @Test fun `invalid documents and media playlists have no verified resolution`() {
        assertEquals(0, ImdbStreamSelector.highestManifestResolution("<html>error</html>"))
        assertEquals(0, ImdbStreamSelector.highestManifestResolution("#EXTM3U\n#EXTINF:5\nsegment.ts"))
    }
}
