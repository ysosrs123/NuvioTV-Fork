package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.ChannelCandidate
import com.nuvio.tv.core.iptv.StoredChannel
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class IptvCatchupTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val start = 1_700_000_000_000L
    private val end = start + 45 * 60_000L
    private val now = start + 3 * 60 * 60_000L

    private fun item(locator: String, attributes: Map<String, String>) =
        IptvCatalogueItem(StoredChannel("c1", "s1", ChannelCandidate("One", locator)), attributes, IptvChannelOverlay())

    @Test fun xtreamTimeshiftUsesEncryptedLoginAndStreamId() {
        val connection = IptvSourceConnection("http://example.invalid:8080", "user /", "pass?")
        val url = IptvCatchup.locator(IptvSourceKind.XTREAM, connection,
            item("http://example.invalid:8080/live/42.ts", mapOf("archive-availability" to "ADVERTISED", "archive-days" to "2")), start, end, now, utc)
        assertEquals("http://example.invalid:8080/timeshift/user%20%2F/pass%3F/45/2023-11-14:22-13/42.ts", url)
    }

    @Test fun xtreamWithoutArchiveOrOutsideDaysIsRefused() {
        val connection = IptvSourceConnection("http://example.invalid:8080", "user", "pass")
        assertNull(IptvCatchup.locator(IptvSourceKind.XTREAM, connection, item("http://example.invalid:8080/live/42.ts", emptyMap()), start, end, now, utc))
        val later = start + 3 * 24 * 60 * 60_000L
        assertNull(IptvCatchup.locator(IptvSourceKind.XTREAM, connection,
            item("http://example.invalid:8080/live/42.ts", mapOf("archive-availability" to "ADVERTISED", "archive-days" to "2")), start, end, later, utc))
        assertNull(IptvCatchup.locator(IptvSourceKind.XTREAM, connection,
            item("http://example.invalid:8080/live/42.ts", mapOf("archive-availability" to "ADVERTISED")), now + 60_000, now + 120_000, now, utc))
    }

    @Test fun m3uTemplateAndShiftTypes() {
        val template = item("http://example.invalid/live/one.m3u8", mapOf("catchup" to "default",
            "catchup-source" to "http://example.invalid/archive/one.m3u8?start={utc}&end={utcend}"))
        assertEquals("http://example.invalid/archive/one.m3u8?start=${start / 1000}&end=${end / 1000}",
            IptvCatchup.locator(IptvSourceKind.M3U, null, template, start, end, now, utc))
        val shift = item("http://example.invalid/live/one.ts", mapOf("catchup" to "shift"))
        assertEquals("http://example.invalid/live/one.ts?utc=${start / 1000}&lutc=${now / 1000}",
            IptvCatchup.locator(IptvSourceKind.M3U, null, shift, start, end, now, utc))
        val corrected = item("http://example.invalid/live/one.ts", mapOf("catchup" to "shift", "catchup-correction" to "-1"))
        assertEquals("http://example.invalid/live/one.ts?utc=${(start - 3_600_000) / 1000}&lutc=${now / 1000}",
            IptvCatchup.locator(IptvSourceKind.M3U, null, corrected, start, end, now, utc))
    }

    @Test fun unsupportedKindsAndPlaylistsWithoutCatchup() {
        assertFalse(IptvCatchup.supported(IptvSourceKind.STALKER, mapOf("archive-availability" to "ADVERTISED")))
        assertFalse(IptvCatchup.supported(IptvSourceKind.M3U, emptyMap()))
        assertNull(IptvCatchup.locator(IptvSourceKind.M3U, null, item("http://example.invalid/one.ts", mapOf("catchup" to "default")), start, end, now, utc))
    }
}
