package com.nuvio.tv.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UrlRedactionTest {
    @Test
    fun masksServerTokenInDirectPlayUrl() {
        val url = "http://192.168.1.10:8096/Videos/abc/stream?static=true&mediaSourceId=ms1&api_key=s3cr3t-Token"
        assertEquals(
            "http://192.168.1.10:8096/Videos/abc/stream?static=true&mediaSourceId=ms1&api_key=REDACTED",
            url.redactUrlCredentials()
        )
    }

    @Test
    fun masksTokenAnywhereInTheQueryAndKeepsOtherParameters() {
        val url = "https://media.example.com/emby/videos/1/master.m3u8?ApiKey=abc123&MediaSourceId=ms1&PlaySessionId=ps1"
        assertEquals(
            "https://media.example.com/emby/videos/1/master.m3u8?ApiKey=REDACTED&MediaSourceId=ms1&PlaySessionId=ps1",
            url.redactUrlCredentials()
        )
    }

    @Test
    fun masksEveryUrlInALogLine() {
        val line = "pendingStream=http://a/stream?api_key=one&x=1 currentStream=http://a/master.m3u8?x=1&api_key=two"
        val redacted = line.redactUrlCredentials()
        assertFalse(redacted.contains("one"))
        assertFalse(redacted.contains("two"))
        assertEquals(
            "pendingStream=http://a/stream?api_key=REDACTED&x=1 currentStream=http://a/master.m3u8?x=1&api_key=REDACTED",
            redacted
        )
    }

    @Test
    fun masksHeaderStyleTokenParameters() {
        assertEquals(
            "http://a/Items/1/Download?X-Emby-Token=REDACTED",
            "http://a/Items/1/Download?X-Emby-Token=abc".redactUrlCredentials()
        )
        assertEquals("http://a/dl?token=REDACTED#t=5", "http://a/dl?token=abc#t=5".redactUrlCredentials())
    }

    @Test
    fun leavesUrlsWithoutCredentialsAndLookalikeParametersAlone() {
        val plain = "https://cdn.example.com/file.mkv?with_keywords=api_key&tokens=3&id=7"
        assertEquals(plain, plain.redactUrlCredentials())
        assertEquals("text without a url", "text without a url".redactUrlCredentials())
    }

    @Test
    fun nullUrlIsPrintable() {
        assertEquals("(null)", (null as String?).redactedUrlForLog())
        assertEquals("http://a/s?api_key=REDACTED", "http://a/s?api_key=k".redactedUrlForLog())
    }

    @Test
    fun removesTokenParametersInsteadOfMaskingThem() {
        assertEquals(
            "http://a/Videos/1/stream?static=true&mediaSourceId=ms1",
            "http://a/Videos/1/stream?static=true&api_key=k&mediaSourceId=ms1".withoutUrlCredentials()
        )
        assertEquals(
            "https://a/emby/videos/1/master.m3u8?MediaSourceId=ms1#t=5",
            "https://a/emby/videos/1/master.m3u8?ApiKey=k&MediaSourceId=ms1#t=5".withoutUrlCredentials()
        )
        assertEquals("http://a/dl", "http://a/dl?X-Emby-Token=k&token=t".withoutUrlCredentials())
        assertEquals("http://a/dl#t=5", "http://a/dl?api_key=k#t=5".withoutUrlCredentials())
        val plain = "https://cdn.example.com/file.mkv?with_keywords=api_key&tokens=3&id=7"
        assertEquals(plain, plain.withoutUrlCredentials())
    }

    @Test
    fun onlyServerStreamsLoseTheirTokenForDiagnostics() {
        val addonLink = "https://debrid.example/dl/abc?token=keep"
        assertEquals(addonLink, addonLink.diagnosticStreamUrl(isServerStream = false))
        assertEquals("https://debrid.example/dl/abc", addonLink.diagnosticStreamUrl(isServerStream = true))
        assertEquals(
            "http://a/Videos/1/stream?static=true",
            "http://a/Videos/1/stream?static=true&ApiKey=k".diagnosticStreamUrl(isServerStream = false)
        )
        assertEquals("http://a/file.mkv", "http://a/file.mkv".diagnosticStreamUrl(isServerStream = true))
    }
}
