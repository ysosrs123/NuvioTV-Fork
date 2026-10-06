package com.nuvio.tv.core.iptv

import java.io.StringReader
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistGuidesTest {
    @Test fun headerGuideAddressesAreFilteredDeduplicatedAndBounded() {
        val playlist = "#EXTM3U url-tvg=\"https://a.invalid/one.xml, guide/two.xml.gz ,https://user:pw@a.invalid/x.xml\" x-tvg-url=\"https://a.invalid/one.xml,ftp://a.invalid/f.xml\"\n" +
            "#EXTINF:-1,One\nhttps://media.invalid/1\n"
        val parsed = PlaylistCatalogueParser().parse(StringReader(playlist), URI("https://lists.invalid/p/list.m3u"))
        assertEquals(listOf("https://a.invalid/one.xml", "https://lists.invalid/p/guide/two.xml.gz"), playlistGuideAddresses(parsed.guideUrls))
        assertEquals(listOf("https://a.invalid/1"), playlistGuideAddresses(listOf(" https://a.invalid/1 ", "https://a.invalid/1", "https://a.invalid/2#x", "", "https:///x"), limit = 1))
        assertEquals((0 until 4).map { "https://a.invalid/$it" }, playlistGuideAddresses((0 until 9).map { "https://a.invalid/$it" }))
    }
}
