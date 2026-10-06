package com.nuvio.tv.core.iptv

import java.io.StringReader
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class ChannelLogoTest {
    @Test fun onlyBoundedHttpAddressesWithoutUserInfoAreKept() {
        assertEquals("https://cdn.example.invalid/logo.png", channelLogoUrl(" https://cdn.example.invalid/logo.png "))
        assertEquals("http://cdn.example.invalid/a%20b.png", channelLogoUrl("http://cdn.example.invalid/a%20b.png"))
        for (value in listOf(null, "", "   ", "ftp://cdn.example.invalid/logo.png", "file:///sdcard/logo.png", "content://media/logo",
            "javascript:alert(1)", "data:image/png;base64,AAAA", "https://user:secret@cdn.example.invalid/logo.png",
            "https://cdn.example.invalid/logo.png#x", "https:///nohost.png", "not a url", "https://cdn.example.invalid/" + "a".repeat(2_000)))
            assertNull(value, channelLogoUrl(value))
    }

    @Test fun relativeLogosResolveOnlyAgainstASafeBase() {
        assertEquals("http://portal.example.invalid/stalker_portal/misc/logos/320/7.png",
            channelLogoUrl("/stalker_portal/misc/logos/320/7.png", "http://portal.example.invalid/c/"))
        assertEquals("https://portal.example.invalid/c/logo.png", channelLogoUrl("logo.png", "https://portal.example.invalid/c/"))
        assertNull(channelLogoUrl("logo.png"))
        assertNull(channelLogoUrl("logo.png", "https://user:pass@portal.example.invalid/c/"))
        assertNull(channelLogoUrl("logo.png", "file:///data/"))
        assertNull(channelLogoUrl("//other.invalid@evil.invalid/x.png", "https://portal.example.invalid/"))
    }

    @Test fun xtreamStreamIconBecomesTheLogo() {
        val catalogue = XtreamCatalogueParser().parse("""[{"stream_id":1,"name":"One","stream_icon":"https://cdn.example.invalid/1.png"},
            {"stream_id":2,"name":"Two","stream_icon":"ftp://cdn.example.invalid/2.png"},{"stream_id":3,"name":"Three","stream_icon":null}]""")
        assertTrue(catalogue.canPublish)
        assertEquals(listOf("https://cdn.example.invalid/1.png", null, null), catalogue.channels.map { it.logo })
    }

    @Test fun stalkerLogoResolvesAgainstThePortal() {
        val json = """{"js":{"data":[{"id":"1","name":"One","cmd":"ffmpeg http://media.example.invalid/1","logo":"/stalker_portal/misc/logos/1.png"},
            {"id":"2","name":"Two","cmd":"ffmpeg http://media.example.invalid/2","logo":""},
            {"id":"3","name":"Three","cmd":"ffmpeg http://media.example.invalid/3","logo":"https://cdn.example.invalid/3.png"}]}}"""
        assertEquals(listOf("http://portal.example.invalid/stalker_portal/misc/logos/1.png", null, "https://cdn.example.invalid/3.png"),
            StalkerPortal.parseChannels(json, logoBase = "http://portal.example.invalid/c/").channels.map { it.logo })
        assertEquals(listOf(null, null, "https://cdn.example.invalid/3.png"), StalkerPortal.parseChannels(json).channels.map { it.logo })
    }

    @Test fun playlistLogosAreResolvedOrDropped() {
        val playlist = "#EXTM3U\n#EXTINF:-1 tvg-logo=\"logos/one.png\",One\nhttps://media.example.invalid/1\n" +
            "#EXTINF:-1 tvg-logo=\"file:///etc/passwd\" tvg-id=\"two\",Two\nhttps://media.example.invalid/2\n"
        val channels = PlaylistCatalogueParser().parse(StringReader(playlist), URI("https://lists.example.invalid/list.m3u")).channels
        assertEquals("https://lists.example.invalid/logos/one.png", channels[0].attributes[CHANNEL_LOGO_ATTRIBUTE])
        assertEquals(mapOf("tvg-id" to "two"), channels[1].attributes)
    }
}
