package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.thumbnail.RequestBudget
import com.nuvio.tv.core.player.thumbnail.RequestTally
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaybackRefusalLogTest {
    private val server = MockWebServer()
    private val lines = ArrayList<String>()
    private var now = 1_000_000L
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.start()
        client = OkHttpClient.Builder()
            .addNetworkInterceptor(PlaybackRefusalLog(budget = RequestBudget { now }, sink = { lines += it }))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun get(path: String = "/f?token=SECRET"): Int =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute().use { it.code }

    private fun answer(code: Int, body: String = "", vararg headers: Pair<String, String>): MockResponse {
        val response = MockResponse().setResponseCode(code).setBody(body)
        headers.forEach { response.addHeader(it.first, it.second) }
        return response
    }

    @Test
    fun `an answered request logs nothing`() {
        server.enqueue(answer(206, "x"))
        assertEquals(206, get())
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `a refusal names the headers, the page and the counts, not the link`() {
        server.enqueue(answer(206, "x"))
        server.enqueue(
            answer(
                429, "<html><title>Too  many requests</title></html>",
                "Server" to "cloudflare", "CF-Ray" to "abc-BNE", "Retry-After" to "300",
                "Content-Type" to "text/html", "Set-Cookie" to "session=SECRET"
            )
        )
        get()
        assertEquals(429, get())
        val line = lines.single()
        assertTrue(line, line.startsWith("NET_REFUSED code=429"))
        assertTrue(line, "last60s=2 last300s=2" in line)
        assertTrue(line, "Retry-After=300" in line)
        assertTrue(line, "CF-Ray=abc-BNE" in line)
        assertTrue(line, "Server=cloudflare" in line)
        assertTrue(line, "page=[Too many requests]" in line)
        assertFalse(line, "SECRET" in line)
    }

    @Test
    fun `a 503 is logged and the caller still reads the page`() {
        server.enqueue(answer(503, "busy", "Content-Type" to "text/plain"))
        client.newCall(Request.Builder().url(server.url("/f")).build()).execute().use {
            assertEquals(503, it.code)
            assertEquals("busy", it.body.string())
        }
        assertTrue(lines.single(), "page=[busy]" in lines.single())
    }

    @Test
    fun `a page that is not text is not read`() {
        server.enqueue(answer(429, "binary", "Content-Type" to "video/x-matroska"))
        get()
        assertTrue(lines.single(), "page=[]" in lines.single())
    }

    @Test
    fun `a refusal behind a redirect counts each hop`() {
        server.enqueue(answer(302, "", "Location" to "/next"))
        server.enqueue(answer(429))
        assertEquals(429, get())
        assertTrue(lines.single(), "last60s=2" in lines.single())
    }

    @Test
    fun `counts forget what is older than their window`() {
        val tally = RequestTally()
        tally.add("tb-cdn.io", 0L)
        tally.add("tb-cdn.io", 250_000L)
        tally.add("tb-cdn.st", 250_000L)
        assertEquals(1, tally.count("tb-cdn.io", 290_000L, 60_000L))
        assertEquals(2, tally.count("tb-cdn.io", 290_000L, 300_000L))
        assertEquals(1, tally.count("tb-cdn.st", 290_000L, 300_000L))
        tally.add("tb-cdn.io", 300_000L)
        assertEquals(2, tally.count("tb-cdn.io", 300_000L, 300_000L))
        assertEquals(0, tally.count("other.net", 300_000L, 300_000L))
    }

    @Test
    fun `servers of one CDN share a domain and addresses stay whole`() {
        assertEquals("tb-cdn.io", RequestBudget.domainOf("store-013.wnam.tb-cdn.io"))
        assertEquals("tb-cdn.io", RequestBudget.domainOf("wormhole-002.wnam.tb-cdn.io"))
        assertEquals("192.168.1.240", RequestBudget.domainOf("192.168.1.240"))
    }

    @Test
    fun `only the listed headers are kept`() {
        val kept = PlaybackRefusalLog.interestingHeaders(
            headersOf("X-RateLimit-After", "8", "Set-Cookie", "a=b", "Authorization", "c", "cf-mitigated", "challenge")
        )
        assertEquals("X-RateLimit-After=8 cf-mitigated=challenge", kept)
    }

    @Test
    fun `a page start stays short and drops secrets`() {
        assertEquals("", PlaybackRefusalLog.pageStart(null))
        assertEquals(160, PlaybackRefusalLog.pageStart("x".repeat(500)).length)
        assertFalse(PlaybackRefusalLog.pageStart("Bearer abcdef refused").contains("abcdef"))
    }
}
