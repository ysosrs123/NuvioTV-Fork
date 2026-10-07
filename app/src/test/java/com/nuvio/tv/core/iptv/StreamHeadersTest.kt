package com.nuvio.tv.core.iptv

import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class StreamHeadersTest {
    private fun parse(text: String) = PlaylistCatalogueParser().parse(StringReader(text))

    @Test fun vlcOptionsBecomeChannelHeaders() {
        val p = parse("#EXTM3U\n#EXTINF:-1,One\n#EXTVLCOPT:http-user-agent=Agent/1.0 (TV)\n#EXTVLCOPT:http-referrer=https://ref.invalid/\n" +
            "#EXTVLCOPT:http-origin=https://ref.invalid\n#EXTVLCOPT:network-caching=1000\nhttps://example.invalid/one\n#EXTINF:-1,Two\nhttps://example.invalid/two")
        assertTrue(p.canPublish)
        assertEquals(mapOf("User-Agent" to "Agent/1.0 (TV)", "Referer" to "https://ref.invalid/", "Origin" to "https://ref.invalid"),
            StreamHeaders.requestHeaders(p.channels[0].attributes))
        assertTrue(StreamHeaders.requestHeaders(p.channels[1].attributes).isEmpty())
    }

    @Test fun extHttpJsonKeepsOnlyAllowedHeaders() {
        val p = parse("#EXTM3U\n#EXTINF:-1,One\n#EXTHTTP:{\"User-Agent\":\"Agent\",\"Referer\":\"https://r.invalid/\",\"Cookie\":\"s=1\",\"Authorization\":\"Bearer x\",\"X-Other\":\"1\"}\nhttps://example.invalid/one")
        assertTrue(p.canPublish)
        assertEquals(mapOf("User-Agent" to "Agent", "Referer" to "https://r.invalid/"), StreamHeaders.requestHeaders(p.channels.single().attributes))
        assertFalse(p.channels.single().attributes.values.any { "Bearer" in it || "s=1" in it })
    }

    @Test fun malformedExtHttpIsIgnored() {
        val p = parse("#EXTM3U\n#EXTINF:-1,One\n#EXTHTTP:{not json\nhttps://example.invalid/one")
        assertTrue(p.canPublish); assertTrue(StreamHeaders.requestHeaders(p.channels.single().attributes).isEmpty())
    }

    @Test fun kodiStreamHeadersAreDecoded() {
        val p = parse("#EXTM3U\n#EXTINF:-1,One\n#KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha\n" +
            "#KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Agent%20One&Referer=https%3A%2F%2Fr.invalid%2F\nhttps://example.invalid/one")
        assertTrue(p.canPublish)
        assertEquals(mapOf("User-Agent" to "Agent One", "Referer" to "https://r.invalid/"), StreamHeaders.requestHeaders(p.channels.single().attributes))
    }

    @Test fun pipeSuffixIsStrippedAndOverridesDirectives() {
        val p = parse("#EXTM3U\n#EXTINF:-1,One\n#EXTVLCOPT:http-user-agent=Old\nhttps://example.invalid/one.m3u8?token=a|User-Agent=New%2F2&Referer=https://r.invalid/&Cookie=x")
        assertTrue(p.canPublish)
        val channel = p.channels.single()
        assertEquals("https://example.invalid/one.m3u8?token=a", channel.locator)
        assertEquals(mapOf("User-Agent" to "New/2", "Referer" to "https://r.invalid/"), StreamHeaders.requestHeaders(channel.attributes))
    }

    @Test fun directivesBeforeExtinfApplyToTheNextChannelOnly() {
        val p = parse("#EXTM3U\n#EXTVLCOPT:http-user-agent=Agent\n#EXTINF:-1,One\nhttps://example.invalid/one\n#EXTINF:-1,Two\nhttps://example.invalid/two")
        assertEquals("Agent", StreamHeaders.requestHeaders(p.channels[0].attributes)["User-Agent"])
        assertNull(StreamHeaders.requestHeaders(p.channels[1].attributes)["User-Agent"])
    }

    @Test fun playlistDefaultsAreInherited() {
        val p = parse("#EXTM3U http-user-agent=\"Agent\"\n#EXTINF:-1,One\nhttps://example.invalid/one")
        assertEquals("Agent", StreamHeaders.requestHeaders(p.channels.single().attributes)["User-Agent"])
    }

    @Test fun unsafeOrOversizedValuesAreDropped() {
        assertNull(StreamHeaders.clean("a\r\nInjected: 1"))
        assertNull(StreamHeaders.clean("é"))
        assertNull(StreamHeaders.clean("x".repeat(StreamHeaders.MAX_VALUE + 1)))
        assertEquals("x".repeat(StreamHeaders.MAX_VALUE), StreamHeaders.clean("x".repeat(StreamHeaders.MAX_VALUE)))
        assertTrue(StreamHeaders.splitLocator("https://e.invalid/a|User-Agent=a%0D%0AX: y").second.isEmpty())
        assertTrue(StreamHeaders.requestHeaders(mapOf(StreamHeaders.USER_AGENT to "bad\u0000")).isEmpty())
    }

    @Test fun bareLocatorHasNoHeaders() {
        assertEquals("https://e.invalid/a" to emptyMap<String, String>(), StreamHeaders.splitLocator("https://e.invalid/a"))
        assertEquals("https://e.invalid/a" to emptyMap<String, String>(), StreamHeaders.splitLocator("https://e.invalid/a|"))
    }
}
