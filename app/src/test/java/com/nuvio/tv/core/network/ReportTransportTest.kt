package com.nuvio.tv.core.network

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream

class ReportTransportTest {
    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer().apply { start() }
    }

    @After fun stop() = server.shutdown()

    @Test fun `signature matches the relay's own calculation`() {
        val signature = ReportSigning.signature(
            "test-key", 1_700_000_000_000L, "POST", "/api/playback-reports", "install-0001", """{"a":1}""".toByteArray()
        )
        assertEquals("0306d991774ce22121916be474f71cbd6b46d321b88048e43595bc00d4fa1861", signature)
    }

    @Test fun `reports go out compressed and signed over the compressed bytes`() {
        server.enqueue(MockResponse().setBody("""{"reportId":"ARC-7K2Q"}"""))
        val client = OkHttpClient.Builder().addInterceptor(ReportSigningInterceptor("k", { "install-0001" }, { 1_700_000_000_000L })).build()
        client.newCall(post("/api/playback-reports", """{"schemaVersion":1}""")).execute().close()
        val sent = server.takeRequest()
        val bytes = sent.body.readByteArray()
        assertEquals("gzip", sent.getHeader("Content-Encoding"))
        assertEquals("install-0001", sent.getHeader("X-Arc-Install"))
        assertEquals("1700000000000", sent.getHeader("X-Arc-Time"))
        assertEquals(ReportSigning.signature("k", 1_700_000_000_000L, "POST", "/api/playback-reports", "install-0001", bytes), sent.getHeader("X-Arc-Signature"))
        assertEquals("""{"schemaVersion":1}""", GZIPInputStream(bytes.inputStream()).readBytes().decodeToString())
    }

    @Test fun `a box with a wrong clock signs again with the relay's time`() {
        val serverTime = 1_700_000_900_000L
        server.enqueue(MockResponse().setResponseCode(401).setHeader("x-arc-server-time", serverTime.toString()).setBody("""{"error":"clock"}"""))
        server.enqueue(MockResponse().setBody("""{"reportId":"ARC-7K2Q"}"""))
        val client = OkHttpClient.Builder().addInterceptor(ReportSigningInterceptor("k", { "install-0001" }, { 1_600_000_000_000L })).build()
        val response = client.newCall(post("/api/playback-reports", "{}")).execute()
        assertEquals(200, response.code)
        response.close()
        server.takeRequest()
        assertEquals(serverTime.toString(), server.takeRequest().getHeader("X-Arc-Time"))
    }

    @Test fun `a blocked or redirected name is looked up through DNS over HTTPS`() {
        val relay = InetAddress.getByAddress(byteArrayOf(104, 21, 1, 1))
        val fallback = Dns { listOf(relay) }
        val missing = ReportDns(system = Dns { throw UnknownHostException(it) }, fallbacks = listOf(fallback))
        assertEquals(listOf(relay), missing.lookup("arc.example.workers.dev"))
        val sinkhole = ReportDns(system = Dns { listOf(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))) }, fallbacks = listOf(fallback))
        assertEquals(listOf(relay), sinkhole.lookup("arc.example.workers.dev"))
        assertTrue(sinkhole.preferFallback)
        val fine = ReportDns(system = Dns { listOf(relay) }, fallbacks = listOf(Dns { error("not used") }))
        assertEquals(listOf(relay), fine.lookup("arc.example.workers.dev"))
    }

    @Test fun `a report that cannot connect is tried once more after switching to DNS over HTTPS`() {
        val dns = ReportDns(system = Dns.SYSTEM, fallbacks = emptyList())
        var calls = 0
        server.enqueue(MockResponse().setBody("ok"))
        val client = OkHttpClient.Builder()
            .addInterceptor(ReportRetryInterceptor(dns))
            .addInterceptor { chain -> if (calls++ == 0) throw IOException("connect timed out") else chain.proceed(chain.request()) }
            .build()
        client.newCall(post("/api/playback-reports", "{}")).execute().close()
        assertEquals(2, calls)
        assertTrue(dns.preferFallback)
    }

    private fun post(path: String, body: String) = Request.Builder()
        .url(server.url(path))
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()
}
