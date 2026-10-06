package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class StalkerPortalTest {
    @Test fun portalAddressesResolveToTheirApiEndpoint() {
        assertEquals("http://portal.invalid:8080/portal.php", StalkerPortal.apiUrl("http://portal.invalid:8080/c/"))
        assertEquals("http://portal.invalid/portal.php", StalkerPortal.apiUrl("http://portal.invalid"))
        assertEquals("https://portal.invalid/stalker_portal/server/load.php", StalkerPortal.apiUrl("https://portal.invalid/stalker_portal/c/"))
        assertEquals("http://portal.invalid/custom/portal.php", StalkerPortal.apiUrl("http://portal.invalid/custom/portal.php"))
        for (bad in listOf("ftp://portal.invalid/c/", "http://user:pass@portal.invalid/c/", "http://portal.invalid/c/?mac=1", "not a url"))
            assertNull(bad, StalkerPortal.apiUrl(bad))
    }
    @Test fun macAddressesAreValidatedAndNormalised() {
        assertEquals("00:1A:79:AB:CD:EF", StalkerPortal.normalizeMac(" 00:1a:79:ab:cd:ef "))
        for (bad in listOf("00:1A:79:AB:CD", "00-1A-79-AB-CD-EF", "", null)) assertNull(StalkerPortal.normalizeMac(bad))
    }
    @Test fun handshakeProfileAndLinkResponsesAreChecked() {
        assertEquals("ABC123", StalkerPortal.parseToken("""{"js":{"token":"ABC123"}}"""))
        for (bad in listOf("""{"js":{}}""", """{"js":{"token":"bad token"}}""", """{"js":[]}"""))
            try { StalkerPortal.parseToken(bad); fail(bad) } catch (_: Exception) { }
        StalkerPortal.requireProfile("""{"js":{"id":"5","status":0}}""")
        for (blocked in listOf("""{"js":{"status":1}}""", """{"js":{"blocked":"1"}}""", """{"js":{"block_msg":"Expired"}}"""))
            try { StalkerPortal.requireProfile(blocked); fail(blocked) } catch (_: SecurityException) { }
        assertEquals("http://media.invalid/live/1.ts?token=x", StalkerPortal.parseLink("""{"js":{"cmd":"ffmpeg http://media.invalid/live/1.ts?token=x"}}"""))
        try { StalkerPortal.parseLink("""{"js":{"cmd":"ffmpeg rtmp://media.invalid/live"}}"""); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun channelListsAcceptBothShapesAndCountInvalidRows() {
        val rows = """[{"id":"1","name":"One","number":"101","cmd":"ffmpeg http://localhost/ch/1_","tv_genre_id":"3","xmltv_id":"one.uk"},
            {"id":2,"name":"Two","cmd":"auto http://localhost/ch/2_"},{"id":"3","name":"","cmd":"ffmpeg http://localhost/ch/3_"},
            {"id":"4","name":"Four","cmd":"rtmp://x"},{"id":"1","name":"Changed","cmd":"ffmpeg http://localhost/ch/1_"}]"""
        for (json in listOf("""{"js":{"data":$rows}}""", """{"js":$rows}""")) {
            val catalogue = StalkerPortal.parseChannels(json)
            assertEquals(listOf("1", "2"), catalogue.channels.map { it.id }); assertEquals(3, catalogue.invalidRows); assertFalse(catalogue.canPublish)
            assertEquals(101, catalogue.channels[0].number); assertEquals("3", catalogue.channels[0].genreId); assertEquals("one.uk", catalogue.channels[0].guideId)
            assertFalse(catalogue.channels[0].toString().contains("localhost"))
        }
        assertEquals(mapOf("3" to "News"), StalkerPortal.parseGenres("""{"js":[{"id":"*","title":"All"},{"id":"3","title":"News"},{"id":"4"}]}"""))
    }
}
