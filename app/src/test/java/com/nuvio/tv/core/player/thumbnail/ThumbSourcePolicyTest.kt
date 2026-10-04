package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbSourcePolicyTest {
    @Test
    fun acceptsAServerDirectPlayUrlWithTheTokenInTheQuery() {
        assertTrue(
            isThumbnailSource(
                "http://192.168.1.10:8096/Videos/abc/stream?static=true&mediaSourceId=ms1&playSessionId=ps1&deviceId=d1&api_key=secret"
            )
        )
        assertTrue(isThumbnailSource("https://media.example.com/emby/Videos/abc/stream?static=true&api_key=secret", "video/x-matroska"))
        assertTrue(isThumbnailSource("https://cdn.example.com/dl/Film.2160p.mkv"))
    }

    @Test
    fun refusesServerTranscodeUrls() {
        assertFalse(isThumbnailSource("http://192.168.1.10:8096/videos/abc/master.m3u8?MediaSourceId=ms1&api_key=secret"))
        assertFalse(isThumbnailSource("https://media.example.com/emby/videos/abc/MAIN.M3U8?api_key=secret"))
        assertFalse(isThumbnailSource("http://192.168.1.10:8096/videos/abc/live.m3u8"))
    }

    @Test
    fun refusesSegmentedStreamsByTypeEvenWithoutATellingPath() {
        assertFalse(isThumbnailSource("https://media.example.com/play/abc?api_key=secret", "application/x-mpegURL"))
        assertFalse(isThumbnailSource("https://media.example.com/play/abc", "application/vnd.apple.mpegurl"))
        assertFalse(isThumbnailSource("https://media.example.com/play/abc", "application/dash+xml"))
        assertFalse(isThumbnailSource("https://media.example.com/video/manifest.mpd?x=1"))
        assertFalse(isThumbnailSource("https://media.example.com/video.ism/Manifest"))
    }

    @Test
    fun aQueryThatMentionsAPlaylistDoesNotDisqualifyAFile() {
        assertTrue(isThumbnailSource("https://cdn.example.com/file.mkv?from=list.m3u8"))
        assertTrue(isThumbnailSource("https://cdn.example.com/file.mp4#frag.m3u8"))
    }

    @Test
    fun refusesAnythingThatIsNotHttp() {
        assertFalse(isThumbnailSource(null))
        assertFalse(isThumbnailSource(""))
        assertFalse(isThumbnailSource("content://media/file.mkv"))
        assertFalse(isThumbnailSource("httpx://host/file.mkv"))
        assertFalse(isThumbnailSource("file:///sdcard/file.mkv"))
    }
}
