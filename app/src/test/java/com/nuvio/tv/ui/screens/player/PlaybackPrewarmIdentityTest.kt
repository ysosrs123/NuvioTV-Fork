package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackPrewarmIdentityTest {
    private val url = "https://example.invalid/media"
    private fun key(headers: Map<String, String> = emptyMap(), overrides: Map<String, String> = emptyMap()) =
        PlaybackPrewarmIdentity.from(url, headers, overrides)

    @Test fun `header case order and implicit transport defaults do not lose useful bytes`() {
        assertEquals(key(mapOf("Authorization" to "abc", "Referer" to "source")),
            key(mapOf("referer" to "source", "authorization" to "abc", "accept-encoding" to "identity",
                "user-agent" to PlayerMediaSourceFactory.DEFAULT_USER_AGENT)))
    }
    @Test fun `authorization cookie user agent encoding and source headers isolate representations`() {
        for (name in listOf("Authorization", "Cookie", "User-Agent", "Accept-Encoding", "Referer", "X-Source")) {
            assertNotEquals(name, key(mapOf(name to "one")), key(mapOf(name to "two")))
        }
    }
    @Test fun `data spec headers override default request properties case insensitively`() {
        assertEquals(key(mapOf("authorization" to "two")),
            key(mapOf("Authorization" to "one"), mapOf("authorization" to "two")))
    }
    @Test fun `range geometry is checked separately without discarding matching identity`() {
        assertEquals(key(), key(mapOf("Range" to "bytes=100-200")))
        assertNotEquals(key(), PlaybackPrewarmIdentity.from("https://example.invalid/other", emptyMap()))
    }
    @Test fun `actual warm request matches vendored Media3 progressive request headers`() {
        // ProgressiveMediaPeriod.ExtractingLoadable adds this map in the vendored 1.8.0 binary.
        val caller = mapOf("Authorization" to "test-token")
        val warm = PlayerPlaybackNetworking.prewarmRequest(url, caller)!!
        assertEquals("1", warm.header("Icy-MetaData"))
        assertEquals(PlaybackPrewarmIdentity.from(url, caller, mapOf("Icy-MetaData" to "1")),
            PlaybackPrewarmIdentity.from(warm.url.toString(), warm.headers.toMap()))
    }

    @Test fun `invalid URLs cannot create identities and diagnostics redact secrets`() {
        assertNull(PlaybackPrewarmIdentity.from("file:///media", emptyMap()))
        assertFalse(key(mapOf("Authorization" to "secret"))!!.toString().contains("secret"))
        assertFalse(key()!!.toString().contains("example.invalid"))
    }
}
