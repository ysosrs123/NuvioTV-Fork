package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PlayerMediaSourceFactoryTest {

    @Test
    fun `media segment 404 with an alternative prefers another HLS track`() {
        assertTrue(
            shouldPreferAlternativeHlsTrack(
                responseCode = 404,
                dataType = C.DATA_TYPE_MEDIA,
                alternativeTrackAvailable = true
            )
        )
    }

    @Test
    fun `manifest errors and missing alternatives do not trigger rendition fallback`() {
        assertFalse(
            shouldPreferAlternativeHlsTrack(
                responseCode = 404,
                dataType = C.DATA_TYPE_MANIFEST,
                alternativeTrackAvailable = true
            )
        )
        assertFalse(
            shouldPreferAlternativeHlsTrack(
                responseCode = 404,
                dataType = C.DATA_TYPE_MEDIA,
                alternativeTrackAvailable = false
            )
        )
    }

    @Test
    fun `inferMimeType prefers response content type for manifest urls without extension`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/playback?id=42",
            filename = null,
            responseHeaders = mapOf("Content-Type" to "application/vnd.apple.mpegurl; charset=UTF-8")
        )

        assertEquals(MimeTypes.APPLICATION_M3U8, mimeType)
    }

    @Test
    fun `inferMimeType uses content disposition filename when content type is missing`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/download?id=42",
            filename = null,
            responseHeaders = mapOf("Content-Disposition" to "attachment; filename=manifest.mpd")
        )

        assertEquals(MimeTypes.APPLICATION_MPD, mimeType)
    }

    @Test
    fun `inferMimeType ignores generic playlist path without manifest evidence`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/api/playlist/stream",
            filename = null
        )

        assertNull(mimeType)
    }

    @Test
    fun `inferMimeType recognizes explicit format query values`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/playback?format=m3u8",
            filename = null
        )

        assertEquals(MimeTypes.APPLICATION_M3U8, mimeType)
    }

    @Test
    fun `inferMimeType recognizes percent encoded DASH MIME query values`() {
        for (key in listOf("format", "mime", "mime_type", "content_type", "type")) {
            val url = "https://example.com/playback?$key=application%2Fdash%2Bxml"
            assertEquals(
                url,
                MimeTypes.APPLICATION_MPD,
                PlayerMediaSourceFactory.inferMimeType(url = url, filename = null)
            )
        }
    }

    @Test
    fun `inferMimeType preserves literal MIME plus signs and tolerates invalid escapes`() {
        assertEquals(
            MimeTypes.APPLICATION_MPD,
            PlayerMediaSourceFactory.inferMimeType(
                url = "https://example.com/playback?mime=application/dash+xml",
                filename = null
            )
        )
        assertNull(
            PlayerMediaSourceFactory.inferMimeType(
                url = "https://example.com/playback?mime=%ZZ",
                filename = null
            )
        )
    }

    @Test
    fun `normalizeMimeType recognizes redirected matroska file responses`() {
        val mimeType = PlayerMediaSourceFactory.normalizeMimeType("video/x-matroska")

        assertEquals(MimeTypes.VIDEO_MATROSKA, mimeType)
    }

    @Test
    fun `inferMimeType uses filename star content disposition for octet stream responses`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/extract?id=42",
            filename = null,
            responseHeaders = mapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Disposition" to "attachment; filename*=UTF-8''episode-04.mkv"
            )
        )

        assertEquals(MimeTypes.VIDEO_MATROSKA, mimeType)
    }

    @Test
    fun `inferMimeType prefers URL extension for adaptive formats even if headers specify different type`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/stream.m3u8",
            filename = null,
            responseHeaders = mapOf("Content-Type" to "video/mp4")
        )

        assertEquals(MimeTypes.APPLICATION_M3U8, mimeType)
    }

    @Test
    fun `inferMimeType prefers filename extension for adaptive formats even if headers specify different type`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/download?id=42",
            filename = "movie.mpd",
            responseHeaders = mapOf("Content-Type" to "video/mp4")
        )

        assertEquals(MimeTypes.APPLICATION_MPD, mimeType)
    }

    @Test
    fun `inferMimeType recognizes playlist endpoints with numeric or token ids as HLS`() {
        val urls = listOf(
            "https://example.com/playlist/759755?token=mock_token_123&expires=1788170323&h=1&lang=it",
            "https://example.com/playlist/123456",
            "https://example.com/playlist/a1b2c3d4e5f6?h=1",
            "https://example.com/hls/759755",
            "https://example.com/manifest/abc123456",
            "https://example.com/master/stream99",
            "https://example.com/live/stream.m3u",
            "https://example.com/playback?protocol=hls"
        )

        for (url in urls) {
            val mimeType = PlayerMediaSourceFactory.inferMimeType(
                url = url,
                filename = null
            )
            assertEquals("Expected HLS mimeType for $url", MimeTypes.APPLICATION_M3U8, mimeType)
        }
    }

    @Test
    fun `normalizePlaybackRequest converts URL userinfo to authorization header`() {
        val userInfo = "test-user:test-pass"
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(
            url = "https://" + userInfo + "@webdav.example.org/movies/title.mkv?download=1",
            headers = mapOf("Range" to "bytes=0-1")
        )

        assertEquals("https://webdav.example.org/movies/title.mkv?download=1", request.url)
        assertEquals(userInfo.basicAuthHeader(), request.headers["Authorization"])
        assertFalse(request.headers.containsKey("Range"))
    }

    @Test
    fun `normalizePlaybackRequest strips URL userinfo without replacing explicit authorization`() {
        val explicitAuth = "explicit:value".basicAuthHeader()
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(
            url = "https://" + "test-user:test-pass" + "@webdav.example.org/movies/title.mkv",
            headers = mapOf("Authorization" to explicitAuth)
        )

        assertEquals("https://webdav.example.org/movies/title.mkv", request.url)
        assertEquals(explicitAuth, request.headers["Authorization"])
    }

    @Test
    fun `normalizePlaybackRequest preserves encoded path query and fragment when stripping userinfo`() {
        val userInfo = "user:p@ss"
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(
            url = "https://" + userInfo.replace("@", "%40") + "@webdav.example.org/files/Show%2FSeason%201/Episode%2001.mkv?name=a%2Fb#frag%2Fment",
            headers = emptyMap()
        )

        assertEquals(
            "https://webdav.example.org/files/Show%2FSeason%201/Episode%2001.mkv?name=a%2Fb#frag%2Fment",
            request.url
        )
        assertEquals(userInfo.basicAuthHeader(), request.headers["Authorization"])
    }

    @Test
    fun `normalizePlaybackRequest strips userinfo containing a literal at sign`() {
        val userInfo = "user:p@ss"
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(
            url = "https://" + userInfo + "@webdav.example.org/files/title.mkv",
            headers = emptyMap()
        )

        assertEquals("https://webdav.example.org/files/title.mkv", request.url)
        assertEquals(userInfo.basicAuthHeader(), request.headers["Authorization"])
    }

    @Test
    fun `isLoopbackUrl returns true for engine and local proxy loopback streams`() {
        assertTrue(PlayerMediaSourceFactory.isLoopbackUrl("http://127.0.0.1:51234/stream/movie.mkv"))
        assertTrue(PlayerMediaSourceFactory.isLoopbackUrl("http://localhost:51234/stream/movie.mkv"))
        assertTrue(PlayerMediaSourceFactory.isLoopbackUrl("http://[::1]:51234/stream/movie.mkv"))
        assertTrue(PlayerMediaSourceFactory.isLoopbackUrl("http://127.0.0.1:8080/stream/video.mkv?token=abc"))
    }

    @Test
    fun `isLoopbackUrl returns false for external and non-loopback urls`() {
        assertFalse(PlayerMediaSourceFactory.isLoopbackUrl("https://debrid.example.com/stream/movie.mkv"))
        assertFalse(PlayerMediaSourceFactory.isLoopbackUrl("http://192.168.1.100:8091/stream?link=123"))
        assertFalse(PlayerMediaSourceFactory.isLoopbackUrl(""))
    }

    @Test
    fun `disk cache is used for remote http streams only`() {
        assertTrue(PlayerMediaSourceFactory.shouldUseVodCache("https://debrid.example.com/stream/movie.mkv"))
        assertTrue(PlayerMediaSourceFactory.shouldUseVodCache("http://192.168.1.100:8091/stream?link=123"))
        assertFalse(PlayerMediaSourceFactory.shouldUseVodCache("http://127.0.0.1:41733/clip-hevc-main10.mkv"))
        assertFalse(PlayerMediaSourceFactory.shouldUseVodCache("http://localhost:8080/stream/video.mkv"))
        assertFalse(PlayerMediaSourceFactory.shouldUseVodCache("http://[::1]:51234/stream/movie.mkv"))
        assertFalse(PlayerMediaSourceFactory.shouldUseVodCache("file:///sdcard/movie.mkv"))
        assertFalse(PlayerMediaSourceFactory.shouldUseVodCache(""))
    }

    @Test
    fun `disk cache is used only once it was opened in the background for this stream`() {
        val url = "https://debrid.example.com/stream/movie.mkv"
        assertTrue(PlayerMediaSourceFactory.usesPreparedVodCache(true, false, url, url))
        assertFalse(PlayerMediaSourceFactory.usesPreparedVodCache(true, false, null, url))
        assertFalse(
            PlayerMediaSourceFactory.usesPreparedVodCache(true, false, "https://debrid.example.com/stream/other.mkv", url)
        )
        assertFalse(PlayerMediaSourceFactory.usesPreparedVodCache(false, false, url, url))
        assertFalse(PlayerMediaSourceFactory.usesPreparedVodCache(true, true, url, url))
    }

    @Test
    fun `starts that will not use the disk cache do not wait for it`() {
        val url = "https://debrid.example.com/stream/movie.mkv"
        assertTrue(PlayerMediaSourceFactory.shouldPrepareVodCache(true, false, false, false, url))
        assertFalse(PlayerMediaSourceFactory.shouldPrepareVodCache(false, false, false, false, url))
        assertFalse(PlayerMediaSourceFactory.shouldPrepareVodCache(true, true, false, false, url))
        assertFalse(PlayerMediaSourceFactory.shouldPrepareVodCache(true, false, true, false, url))
        assertFalse(PlayerMediaSourceFactory.shouldPrepareVodCache(true, false, false, true, url))
        assertFalse(
            PlayerMediaSourceFactory.shouldPrepareVodCache(true, false, false, false, "http://127.0.0.1:41733/clip.mkv")
        )
    }

    @Test
    fun `a slow cache open gives up after the timeout and a quick one is used`() = kotlinx.coroutines.runBlocking {
        val stalled = kotlinx.coroutines.CompletableDeferred<Boolean>()
        assertNull(PlayerMediaSourceFactory.awaitOrNull(50L, stalled))
        assertFalse(stalled.isCancelled)
        val ready = kotlinx.coroutines.CompletableDeferred(true)
        assertEquals(true, PlayerMediaSourceFactory.awaitOrNull(50L, ready))
    }

    @Test
    fun `chunk downloads give up on an unreachable server sooner but keep the read wait`() {
        val base = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        val client = PlayerMediaSourceFactory.withChunkSessionTimeouts(base).build()
        assertEquals(6_000, client.connectTimeoutMillis)
        assertEquals(15_000, client.readTimeoutMillis)
    }

    @Test
    fun `a media server stream never gets parallel connections`() {
        assertTrue(PlayerMediaSourceFactory.usesParallelConnections(setting = true, mediaServer = false))
        assertFalse(PlayerMediaSourceFactory.usesParallelConnections(setting = true, mediaServer = true))
        assertFalse(PlayerMediaSourceFactory.usesParallelConnections(setting = false, mediaServer = false))
    }

    @Test
    fun `a media server MP4 takes the plain single connection path`() {
        val url = "https://jellyfin.example.com/Videos/abc/stream.mp4?static=true"
        assertTrue(PlayerMediaSourceFactory.usesMp4Session(false, false, false, MimeTypes.VIDEO_MP4, url))
        assertFalse(PlayerMediaSourceFactory.usesMp4Session(false, true, false, MimeTypes.VIDEO_MP4, url))
    }

    @Test
    fun `the MP4 session keeps its other rules`() {
        val url = "https://cdn.example.com/Film.mp4"
        assertFalse(PlayerMediaSourceFactory.usesMp4Session(true, false, false, MimeTypes.VIDEO_MP4, url))
        assertFalse(PlayerMediaSourceFactory.usesMp4Session(false, false, true, MimeTypes.VIDEO_MP4, url))
        assertFalse(PlayerMediaSourceFactory.usesMp4Session(false, false, false, MimeTypes.VIDEO_MATROSKA, url))
        assertFalse(PlayerMediaSourceFactory.usesMp4Session(false, false, false, MimeTypes.VIDEO_MP4, "http://127.0.0.1:8090/x.mp4"))
    }

    private fun String.basicAuthHeader(): String =
        "Basic " + Base64.getEncoder().encodeToString(toByteArray(Charsets.UTF_8))

    @Test
    fun `automatic speed-up only on plain progressive streams with Custom network off`() {
        assertTrue(PlayerMediaSourceFactory.usesAutoSpeedup(true, false, false, false, false))
        assertFalse(PlayerMediaSourceFactory.usesAutoSpeedup(false, false, false, false, false))
        assertFalse(PlayerMediaSourceFactory.usesAutoSpeedup(true, true, false, false, false))
        assertFalse(PlayerMediaSourceFactory.usesAutoSpeedup(true, false, true, false, false))
        assertFalse(PlayerMediaSourceFactory.usesAutoSpeedup(true, false, false, true, false))
        assertFalse(PlayerMediaSourceFactory.usesAutoSpeedup(true, false, false, false, true))
    }
}
