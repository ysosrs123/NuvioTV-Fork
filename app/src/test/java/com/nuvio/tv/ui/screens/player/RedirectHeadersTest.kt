package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.RedirectHeaders.keepHeadersOnRedirect
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RedirectHeadersTest {
    private val first = MockWebServer()
    private val second = MockWebServer()
    private val headers = mapOf(
        "Authorization" to "Bearer secret",
        "X-Plex-Token" to "plex-account",
        "X-Profile-Token" to "silo-profile",
        "Cookie" to "session=1",
        "X-Profile" to "kids"
    )

    private val client = OkHttpClient.Builder().keepHeadersOnRedirect(headers).build()

    @Before
    fun start() {
        first.start()
        second.start()
    }

    @After
    fun stop() {
        first.shutdown()
        second.shutdown()
    }

    private fun MockWebServer.at(host: String, path: String): HttpUrl =
        url(path).newBuilder().host(host).build()

    private fun redirectTo(location: HttpUrl) =
        MockResponse().setResponseCode(302).addHeader("Location", location.toString())

    private fun ok() = MockResponse().setResponseCode(200).setBody("ok")

    private fun fetch(url: HttpUrl) {
        client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(200, it.code) }
    }

    @Test
    fun `same host redirect keeps authorization`() {
        first.enqueue(redirectTo(first.at("127.0.0.1", "/file")))
        first.enqueue(ok())

        fetch(first.at("127.0.0.1", "/start"))

        first.takeRequest()
        val hop = first.takeRequest()
        assertEquals("Bearer secret", hop.getHeader("Authorization"))
        assertEquals("kids", hop.getHeader("X-Profile"))
    }

    @Test
    fun `redirect to another host drops authorization and keeps the rest`() {
        first.enqueue(redirectTo(second.at("localhost", "/cdn")))
        second.enqueue(ok())

        fetch(first.at("127.0.0.1", "/start"))

        assertEquals("Bearer secret", first.takeRequest().getHeader("Authorization"))
        val hop = second.takeRequest()
        listOf("Authorization", "X-Plex-Token", "X-Profile-Token", "Cookie").forEach { assertNull(it, hop.getHeader(it)) }
        assertEquals("kids", hop.getHeader("X-Profile"))
    }

    @Test
    fun `same host on another port drops the logins but keeps the rest`() {
        first.enqueue(redirectTo(second.at("127.0.0.1", "/file")))
        second.enqueue(ok())

        fetch(first.at("127.0.0.1", "/start"))

        val hop = second.takeRequest()
        assertNull(hop.getHeader("Authorization"))
        assertNull(hop.getHeader("X-Plex-Token"))
        assertEquals("kids", hop.getHeader("X-Profile"))
    }

    @Test
    fun `same host keeps every login`() {
        first.enqueue(redirectTo(first.at("127.0.0.1", "/file")))
        first.enqueue(ok())

        fetch(first.at("127.0.0.1", "/start"))

        first.takeRequest()
        val hop = first.takeRequest()
        assertEquals("plex-account", hop.getHeader("X-Plex-Token"))
        assertEquals("silo-profile", hop.getHeader("X-Profile-Token"))
    }

    @Test
    fun `the credential set is matched without case`() {
        listOf("authorization", "PROXY-AUTHORIZATION", "cookie", "x-plex-token", "X-Emby-Token", "x-emby-authorization",
            "X-MediaBrowser-Token", "x-profile-token").forEach { assertTrue(it, RedirectHeaders.isCredential(it)) }
        listOf("X-Profile-Id", "User-Agent", "Range", "X-Plex-Session-Identifier").forEach {
            assertFalse(it, RedirectHeaders.isCredential(it))
        }
    }

    @Test
    fun `a port change on the same host keeps the login only for http to https`() {
        assertFalse(
            RedirectHeaders.authorizationAllowed("http://media.example:8096/a".toHttpUrl(), "http://media.example:8097/a".toHttpUrl())
        )
        assertFalse(
            RedirectHeaders.authorizationAllowed("https://media.example/a".toHttpUrl(), "https://media.example:8920/a".toHttpUrl())
        )
    }

    @Test
    fun `http to https on the same host keeps authorization`() {
        assertTrue(
            RedirectHeaders.authorizationAllowed(
                "http://media.example:8096/a".toHttpUrl(),
                "https://media.example/a".toHttpUrl()
            )
        )
    }

    @Test
    fun `https down to http drops authorization`() {
        assertFalse(
            RedirectHeaders.authorizationAllowed(
                "https://media.example/a".toHttpUrl(),
                "http://media.example/a".toHttpUrl()
            )
        )
    }

    @Test
    fun `host match ignores case and other hosts never match`() {
        assertTrue(
            RedirectHeaders.authorizationAllowed(
                "https://Media.Example/a".toHttpUrl(),
                "https://media.example/b".toHttpUrl()
            )
        )
        assertFalse(
            RedirectHeaders.authorizationAllowed(
                "https://media.example/a".toHttpUrl(),
                "https://cdn.media.example/a".toHttpUrl()
            )
        )
    }
}
