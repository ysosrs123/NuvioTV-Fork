package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.net.URI

class PlaylistCatalogueTest {
    private fun parse(text: String, base: URI? = null) = PlaylistCatalogueParser().parse(StringReader(text), base)

    @Test fun bomCrLfUnicodeAndQuotedCommaArePreserved() {
        val p = parse("\uFEFF#EXTM3U\r\n#EXTINF:-1 tvg-id = \"Événements\" group-title=\"Sport, Live\",Sport, Montréal\r\nhttps://example.invalid/CasePath?Token=AbC\r\n")
        assertTrue(p.canPublish)
        assertEquals("Sport, Montréal", p.channels.single().name)
        assertEquals("Sport, Live", p.channels.single().group)
        assertEquals("Événements", p.channels.single().guideId)
        assertEquals("https://example.invalid/CasePath?Token=AbC", p.channels.single().locator)
    }
    @Test fun hlsSegmentsAreNeverChannels() {
        val p = parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\npart.ts\n", URI("https://example.invalid/a/"))
        assertEquals(PlaylistKind.HLS, p.kind); assertTrue(p.channels.isEmpty()); assertFalse(p.canPublish)
    }
    @Test fun lateHlsEvidenceDiscardsStagedRows() {
        val p = parse("#EXTM3U\n#EXTINF:-1,One\nhttps://example.invalid/one\n#EXT-X-VERSION:3")
        assertEquals(PlaylistKind.HLS, p.kind); assertTrue(p.channels.isEmpty())
    }
    @Test fun hlsMasterIsClassifiedWithoutOpeningVariants() {
        assertEquals(PlaylistKind.HLS, parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=300000\nvariant.m3u8").kind)
    }
    @Test fun relativeLocatorsUseFinalRedirectBaseAndPreserveEscapes() {
        val p = parse("#EXTM3U\n#EXTINF:-1,One\n../TV/A%2Fb?Key=X%2fY", URI("https://example.invalid/new/index.m3u"))
        assertEquals("https://example.invalid/TV/A%2Fb?Key=X%2fY", p.channels.single().locator)
    }
    @Test fun malformedPercentEscapeQuarantinesOnlyThatRecordButPreventsPublication() {
        val p = parse("#EXTM3U\n#EXTINF:-1,Bad\nhttps://example.invalid/%zz\n#EXTINF:-1,Good\nhttps://example.invalid/good")
        assertEquals(1, p.channels.size); assertEquals("Good", p.channels.single().name)
        assertEquals(PlaylistIssue.INVALID_LOCATOR, p.diagnostics.single().issue); assertFalse(p.canPublish)
    }
    @Test fun explicitArchiveDisableOverridesPlaylistDefault() {
        val p = parse("#EXTM3U catchup=\"append\" catchup-days=\"7\"\n#EXTINF:-1 catchup=\"\" catchup-days=\"0\",One\nhttps://example.invalid/one\n#EXTINF:-1,Two\nhttps://example.invalid/two")
        assertTrue(p.canPublish); assertEquals("", p.channels[0].attributes["catchup"])
        assertEquals("0", p.channels[0].attributes["catchup-days"])
        assertEquals("append", p.channels[1].attributes["catchup"])
    }
    @Test fun conflictingDuplicateAttributesAreNotGuessed() {
        val p = parse("#EXTM3U\n#EXTINF:-1 tvg-id=\"a\" tvg-id=\"b\",One\nhttps://example.invalid/one")
        assertFalse(p.canPublish); assertTrue(p.channels.isEmpty())
    }
    @Test fun htmlAndPlainUrlsAreRejected() {
        for (body in listOf("<html>login</html>", "https://example.invalid/a", "#EXTM3Ubad\n")) {
            assertEquals(PlaylistKind.INVALID, parse(body).kind)
        }
    }
    @Test fun localRelativeAndUnsupportedSchemesAreRejected() {
        for (url in listOf("relative.ts", "file:///etc/passwd", "https://example.invalid/a#fragment")) {
            assertFalse(parse("#EXTM3U\n#EXTINF:-1,One\n$url").canPublish)
        }
    }
    @Test fun incompleteRecordPreventsPublication() {
        assertFalse(parse("#EXTM3U\n#EXTINF:-1,Missing").canPublish)
        assertFalse(parse("#EXTM3U").canPublish)
    }
    @Test fun authenticationExtensionsAreNotSilentlyIgnored() {
        val p = parse("#EXTM3U\n#EXTINF:-1,One\n#EXTVLCOPT:http-referrer=https://example.invalid\nhttps://example.invalid/one")
        assertFalse(p.canPublish); assertEquals(PlaylistIssue.UNSUPPORTED_EXTENSION, p.diagnostics.single().issue)
    }
    @Test fun boundedLinesAndCatalogueSizeFailClosed() {
        for (limits in listOf(PlaylistLimits(maxLineCharacters = 8), PlaylistLimits(maxCharacters = 10), PlaylistLimits(maxChannels = 1))) {
            val body = "#EXTM3U\n#EXTINF:-1,One\nhttps://example.invalid/a\n#EXTINF:-1,Two\nhttps://example.invalid/b"
            val result = PlaylistCatalogueParser(limits).parse(StringReader(body))
            assertEquals(PlaylistKind.INVALID, result.kind); assertTrue(result.channels.isEmpty())
            assertEquals(PlaylistIssue.LIMIT_EXCEEDED, result.diagnostics.single().issue)
        }
    }
    @Test fun invalidUtf8DoesNotBecomeReplacementText() {
        val bytes = "#EXTM3U\n#EXTINF:-1,".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28)
        assertEquals(PlaylistIssue.INVALID_ENCODING, PlaylistCatalogueParser().parse(ByteArrayInputStream(bytes)).diagnostics.single().issue)
    }
    @Test fun logRepresentationDoesNotLeakLocatorCredentials() {
        val c = parse("#EXTM3U\n#EXTINF:-1,One\nhttps://example.invalid/secret/token").channels.single()
        assertFalse(c.toString().contains("secret"))
    }
}
