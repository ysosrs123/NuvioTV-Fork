package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which stream servers get the long first-data wait and no warm-up requests. */
class SlowStartServerTest {
    @Test fun aNasOnTheHomeNetworkMayTakeItsTime() {
        assertTrue(SlowStartServer.matches("http://192.168.1.10:3100/api/v1/debrid/playback/abc/Example.Movie.mkv"))
        assertTrue(SlowStartServer.matches("http://10.0.0.5:3000/x.mkv"))
        assertTrue(SlowStartServer.matches("http://172.16.1.2/x.mkv"))
        assertTrue(SlowStartServer.matches("http://172.31.255.1/x.mkv"))
        assertTrue(SlowStartServer.matches("http://nas.local:3100/x.mkv"))
        assertTrue(SlowStartServer.matches("http://nas.lan:3100/x.mkv"))
        assertTrue(SlowStartServer.matches("http://nas.home.arpa:3100/x.mkv"))
        assertTrue(SlowStartServer.matches("http://nas.tail1234.ts.net:3100/x.mkv"))
        assertTrue(SlowStartServer.matches("http://100.101.102.103:3100/x.mkv"))
        assertFalse(SlowStartServer.matches("http://100.128.0.1:3100/x.mkv"))
        assertTrue(SlowStartServer.matches("http://[fd12:3456::1]:3100/x.mkv"))
        assertTrue(SlowStartServer.matches("http://[fe80::1]/x.mkv"))
    }

    @Test fun anAioStreamsUsenetStreamAnywhereMayTakeItsTime() {
        assertTrue(SlowStartServer.matches("https://aio.example.com/api/v1/usenet/stream/abc/file.mkv"))
    }

    @Test fun usenetStreamsAreKnownByTheirPath() {
        assertTrue(SlowStartServer.isUsenet("http://192.168.1.10:3100/api/v1/usenet/stream/abc/Film.mkv"))
        assertTrue(SlowStartServer.isUsenet("https://aio.example.com/api/v1/USENET/stream/abc"))
        assertFalse(SlowStartServer.isUsenet("http://192.168.1.10:3100/api/v1/debrid/playback/abc/Film.mkv"))
        assertFalse(SlowStartServer.isUsenet("https://cdn.example/Film.mkv?x=/usenet/"))
        assertFalse(SlowStartServer.isUsenet(null))
    }

    private fun b64(json: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    private fun playback(source: String) =
        "http://192.168.1.10:3100/api/v1/debrid/playback/${b64("{\"i\":\"abc\"}")}/0.3.5d967afb/$source/Film.mkv"

    @Test fun aioStreamsPlaybackLinksSayWhenTheyAreUsenet() {
        // The start of a real NAS link's source segment: {"type":"usenet","nzb":"https://api.n
        assertTrue(SlowStartServer.isUsenet(playback("eyJ0eXBlIjoidXNlbmV0IiwibnpiIjoiaHR0cHM6Ly9hcGkub")))
        assertTrue(SlowStartServer.isUsenet(playback(b64("{\"type\":\"usenet\",\"nzb\":\"https://api.example/getnzb?id=1&x=_-\"}"))))
        assertTrue(SlowStartServer.isUsenet("https://aio.example.com" +
            playback(b64("{\"nzb\":\"https://x\", \"type\" : \"Usenet\"}")).substringAfter(":3100")))
        assertFalse(SlowStartServer.isUsenet(playback(b64("{\"type\":\"torrent\",\"hash\":\"abc\",\"service\":\"torbox\"}"))))
        assertFalse(SlowStartServer.isUsenet(playback(b64("{\"type\":\"debrid\",\"note\":\"usenet\"}"))))
        assertFalse(SlowStartServer.isUsenet("http://192.168.1.10:3100/api/v1/debrid/playback/eyJ!!notbase64/Film.mkv"))
    }

    @Test fun publicCdnsKeepTheNormalLimits() {
        assertFalse(SlowStartServer.matches("https://nexus-114.japn.tb-cdn.pw/dl/abc"))
        assertFalse(SlowStartServer.matches("https://stremthru.example.org/stremio/torz/strem/tb/x"))
        assertFalse(SlowStartServer.matches("http://172.32.0.1/x.mkv"))
        assertFalse(SlowStartServer.matches("http://192.169.0.1/x.mkv"))
        assertFalse(SlowStartServer.matches("http://8.8.8.8/x.mkv"))
        assertFalse(SlowStartServer.matches("http://[2001:db8::1]/x.mkv"))
        assertFalse(SlowStartServer.matches("http://local.example.com/x.mkv"))
    }

    @Test fun loopbackAndJunkAreLeftToTheirOwnHandling() {
        assertFalse(SlowStartServer.matches("http://127.0.0.1:11470/x.mkv"))
        assertFalse(SlowStartServer.matches("http://localhost/x.mkv"))
        assertFalse(SlowStartServer.matches(null))
        assertFalse(SlowStartServer.matches("not a link"))
    }
}
