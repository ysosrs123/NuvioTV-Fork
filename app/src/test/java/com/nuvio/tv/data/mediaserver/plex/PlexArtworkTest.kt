package com.nuvio.tv.data.mediaserver.plex

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexArtworkTest {
    private val local = "https://192-168-10-24.abc.plex.direct:32400"
    private val remote = "https://1-2-3-4.abc.plex.direct:32400"

    private fun client(access: PlexArtworkAccess?, seen: MutableList<Request>) = OkHttpClient.Builder()
        .addInterceptor(PlexArtworkInterceptor { PlexArtworkSigner { id -> access.takeIf { id == "c1" } } })
        .addInterceptor { chain ->
            seen += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("img".toResponseBody("image/jpeg".toMediaType()))
                .build()
        }
        .build()

    @Test
    fun aMarkedLinkIsSignedAndFollowsTheAddressInUse() {
        val link = PlexArtworkLink.picture(local, "c1", "/library/metadata/1/thumb/2", 400, 600)!!
        val seen = mutableListOf<Request>()

        client(PlexArtworkAccess(remote, "srvt"), seen).newCall(Request.Builder().url(link).build()).execute().close()

        val sent = seen.single()
        assertEquals("srvt", sent.header("X-Plex-Token"))
        assertEquals("1-2-3-4.abc.plex.direct", sent.url.host)
        assertEquals("/library/metadata/1/thumb/2", sent.url.queryParameter("url"))
        assertEquals("c1", PlexArtworkLink.connectionId(link.toHttpUrl()))
    }

    @Test
    fun aMarkedLinkOnAnotherHostGetsNoToken() {
        val seen = mutableListOf<Request>()
        client(PlexArtworkAccess(remote, "srvt"), seen)
            .newCall(Request.Builder().url("https://evil.example.com/library/metadata/1/thumb/2#nuvio-plex=c1").build())
            .execute()
            .close()
        assertNull(seen.single().header("X-Plex-Token"))
        assertEquals("evil.example.com", seen.single().url.host)
    }

    @Test
    fun otherLinksAreLeftAlone() {
        val seen = mutableListOf<Request>()
        client(PlexArtworkAccess(remote, "srvt"), seen).newCall(Request.Builder().url("https://image.tmdb.org/p/w500/x.jpg").build()).execute().close()
        assertNull(seen.single().header("X-Plex-Token"))
    }

    @Test
    fun webPicturesStayAsTheyAre() {
        val url = "https://metadata-static.plex.tv/people/abc.jpg"
        assertEquals(url, PlexArtworkLink.picture(local, "c1", url, 100, 100))
        assertNull(PlexArtworkLink.picture(local, "c1", "relative/path", 100, 100))
        assertTrue(PlexArtworkLink.picture(local, "c1", "/a", 1, 1)!!.contains("#nuvio-plex=c1"))
    }
}
