package com.nuvio.tv.ui.screens.player

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class PlaybackUrlCredentialsTest {
    @Test
    fun detectsCommonExpiringCredentialParameters() {
        assertTrue("https://example.com/playlist/1234.m3u8?token=abc&expires=1234".hasLikelyExpiringPlaybackCredentials())
        assertTrue("https://example.com/proxy?ext=m3u8&t=abc".hasLikelyExpiringPlaybackCredentials())
        assertTrue("https://example.com/video.mp4?expiresIn=300&signature=sig".hasLikelyExpiringPlaybackCredentials())
    }

    @Test
    fun ignoresStableFormatHints() {
        assertFalse("https://example.com/proxy?ext=m3u8".hasLikelyExpiringPlaybackCredentials())
        assertFalse("https://example.com/video.mp4?quality=1080p&format=mp4".hasLikelyExpiringPlaybackCredentials())
        assertFalse("https://example.com/video.mkv?api_key=static".hasLikelyExpiringPlaybackCredentials())
    }

    @Test
    fun onlyLinkErrorsRefreshAnExpiringLink() {
        val url = "https://example.com/video.mkv?token=abc&expires=1234"
        assertTrue(expiredLinkRefreshApplies(url, null, null, linkError = true))
        assertFalse(expiredLinkRefreshApplies(url, null, null, linkError = false))
        assertFalse(expiredLinkRefreshApplies(url, null, url, linkError = true))
        assertTrue(expiredLinkRefreshApplies("https://example.com/video.mkv", "https://example.com/video.mkv", null, linkError = true))
        assertFalse(expiredLinkRefreshApplies("https://example.com/video.mkv", null, null, linkError = true))
    }
}
