package com.nuvio.tv.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkLogRedactionTest {
    private val rdKey = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567ABCDEFGHIJKLMNOPQRST"
    private val infoHash = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun masksBase64ConfigInStremThruLink() {
        val url = "https://stremthru.example.org/stremio/torz/" +
            "eyJzdG9yZXMiOlt7ImMiOiJyZCIsInQiOiJmYWtlLWtleS0wMDAwIn1dfQ==/stream/movie/tt37287335.json"
        assertEquals(
            "https://stremthru.example.org/stremio/torz/REDACTED/stream/movie/tt37287335.json",
            url.redactLinksForLog()
        )
    }

    @Test
    fun masksUuidAndConfigInSelfHostedAddonLinkAndKeepsHostAndPort() {
        val url = "http://192.168.1.10:3100/stremio/1b4e28ba-2fa1-11d2-883f-0016d3cca427/" +
            "eyJpIjoiMTIzNDU2Nzg5MCIsImUiOiJhYmNkZWYifQ==/catalog/movie/aio.search.json"
        assertEquals(
            "http://192.168.1.10:3100/stremio/REDACTED/REDACTED/catalog/movie/aio.search.json",
            url.redactLinksForLog()
        )
    }

    @Test
    fun masksConfigRightAfterTheHost() {
        assertEquals(
            "https://xxx-streambridge.example.club:8443/REDACTED/stream/movie/tt0111161.json",
            "https://xxx-streambridge.example.club:8443/eyJzZXJ2aWNlIjoiZmFrZSIsImtleSI6IngifQ==/stream/movie/tt0111161.json"
                .redactLinksForLog()
        )
    }

    @Test
    fun masksLongConfigThatIsNotJson() {
        assertEquals(
            "https://kidzone.example.app/REDACTED/catalog/movie/top.json",
            "https://kidzone.example.app/a3otYWdlLWZha2UtY29uZmlnLXZhbHVlLTEyMzQ1Ng==/catalog/movie/top.json"
                .redactLinksForLog()
        )
    }

    @Test
    fun masksConfigAndHashInPlaybackLink() {
        val url = "https://comet.example.legal/eyJtYXhSZXN1bHRzIjo1MCwiZGVicmlkQXBpS2V5IjoiRkFLRSJ9/playback/" +
            "$infoHash/0/Movie.2016.1080p.mkv"
        assertEquals(
            "https://comet.example.legal/REDACTED/playback/REDACTED/0/Movie.2016.1080p.mkv",
            url.redactLinksForLog()
        )
    }

    @Test
    fun masksDebridKeyAndHashInAddonResolveLinkAndKeepsFileName() {
        val fileName = "Example.Movie.2016.UHD.BluRay.2160p.TrueHD.Atmos.7.1.HEVC.REMUX-FraMeSToR.mkv"
        val url = "https://addon.example.com/resolve/realdebrid/$rdKey/$infoHash/null/0/$fileName"
        val redacted = url.redactLinksForLog()
        assertEquals("https://addon.example.com/resolve/realdebrid/REDACTED/REDACTED/null/0/$fileName", redacted)
        assertFalse(redacted.contains(rdKey))
    }

    @Test
    fun masksTokenInTorboxCdnLink() {
        assertEquals(
            "https://store-021.weur.tb-cdn.example/dld/12345678?token=REDACTED",
            "https://store-021.weur.tb-cdn.example/dld/12345678?token=0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b"
                .redactLinksForLog()
        )
    }

    @Test
    fun masksUserAndPasswordInLink() {
        assertEquals(
            "http://REDACTED@192.168.1.10:8080/api/stream",
            "http://user:secret@192.168.1.10:8080/api/stream".redactLinksForLog()
        )
    }

    @Test
    fun masksExtraCredentialParametersAndKeepsOthers() {
        assertEquals(
            "https://cdn.example.com/v.mkv?sig=REDACTED&Signature=REDACTED&auth=REDACTED" +
                "&access_token=REDACTED&password=REDACTED&pass=REDACTED&x=1",
            "https://cdn.example.com/v.mkv?sig=abc&Signature=def&auth=ghi&access_token=jkl&password=mno&pass=pqr&x=1"
                .redactLinksForLog()
        )
        assertEquals(
            "https://proxy.example.com/proxy/stream?d=file.mkv&api_password=REDACTED&auth_token=REDACTED",
            "https://proxy.example.com/proxy/stream?d=file.mkv&api_password=pw1&auth_token=tok2".redactLinksForLog()
        )
    }

    @Test
    fun masksDebridKeyInsideAnAddonConfigSegment() {
        assertEquals(
            "https://addon.example.com/providers=one,two|qualityfilter=480p|realdebrid=REDACTED/stream/movie/tt1.json",
            "https://addon.example.com/providers=one,two|qualityfilter=480p|realdebrid=$rdKey/stream/movie/tt1.json"
                .redactLinksForLog()
        )
        assertEquals(
            "https://addon.example.com/providers=one,two%7Crealdebrid=REDACTED%7Csort=size/manifest.json",
            "https://addon.example.com/providers=one,two%7Crealdebrid=$rdKey%7Csort=size/manifest.json"
                .redactLinksForLog()
        )
    }

    @Test
    fun masksShortKeyAfterADebridServiceName() {
        assertEquals(
            "https://addon.example.com/resolve/alldebrid/REDACTED/REDACTED/null/0/Film.2020.mkv",
            "https://addon.example.com/resolve/alldebrid/staticDemoApikeyPrem/$infoHash/null/0/Film.2020.mkv"
                .redactLinksForLog()
        )
    }

    @Test
    fun longDottedTextStaysFast() {
        val text = "see https://a.example/x " + "a.b-c+d".repeat(8_000)
        val start = System.nanoTime()
        text.redactLinksForLog()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 1_000)
    }

    @Test
    fun leavesLookalikeParametersAlone() {
        val url = "https://api.example.com/discover?with_keywords=210024&passes=3&authority=x&tokens=2"
        assertEquals(url, url.redactLinksForLog())
    }

    @Test
    fun keepsShortSegmentsTitleIdsAndFileNames() {
        val url = "https://v3-cinemeta.strem.io/meta/series/tt0944947:1:2.json"
        assertEquals(url, url.redactLinksForLog())
        val file = "https://cdn.example.com/dl/42/Example.Movie.1999.2160p.UHD.BluRay.REMUX.HDR.HEVC.Atmos-GROUP.mkv"
        assertEquals(file, file.redactLinksForLog())
    }

    @Test
    fun leavesTextWithoutLinksUnchanged() {
        val text = "Streams success addon=Tortoise count=12 for tt37287335 with 0123456789abcdef0123456789abcdef01234567"
        assertEquals(text, text.redactLinksForLog())
        assertEquals("", "".redactLinksForLog())
    }

    @Test
    fun masksEveryLinkInALine() {
        val line = "pending=https://a.example/eyJhYmNkZWZnaGlqa2xtbm9w/stream.json " +
            "current=https://b.example:8443/resolve/realdebrid/$rdKey/x.mkv?token=t1"
        assertEquals(
            "pending=https://a.example/REDACTED/stream.json " +
                "current=https://b.example:8443/resolve/realdebrid/REDACTED/x.mkv?token=REDACTED",
            line.redactLinksForLog()
        )
    }

    @Test
    fun keepsPunctuationAroundALinkInsideAnObjectDump() {
        assertEquals(
            "Stream(url=https://a.example/REDACTED, name=x) (https://b.example/REDACTED).",
            "Stream(url=https://a.example/eyJhYmNkZWZnaGlqa2xtbm9w, name=x) (https://b.example/$rdKey)."
                .redactLinksForLog()
        )
    }

    @Test
    fun alreadyRedactedServerLinkIsUnchanged() {
        val url = "http://192.168.1.10:8096/Videos/abc/stream?static=true&mediaSourceId=ms1&api_key=REDACTED"
        assertEquals(url, url.redactLinksForLog())
        assertEquals(
            "http://192.168.1.10:8096/Videos/abc/stream?static=true&mediaSourceId=ms1&api_key=REDACTED",
            "http://192.168.1.10:8096/Videos/abc/stream?static=true&mediaSourceId=ms1&api_key=s3cr3t-Token"
                .redactLinksForLog()
        )
    }

    @Test
    fun printableLogValueUsesTheSameRules() {
        assertEquals("(null)", (null as String?).redactedUrlForLog())
        assertEquals(
            "https://addon.example.com/resolve/realdebrid/REDACTED/x.mkv",
            "https://addon.example.com/resolve/realdebrid/$rdKey/x.mkv".redactedUrlForLog()
        )
        assertEquals("(null)", (null as String?).urlForLog())
        assertEquals("https://a.example/REDACTED/manifest.json", "https://a.example/$rdKey/manifest.json".urlForLog())
    }
}
