package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.HlsCaptureException
import com.nuvio.tv.core.iptv.HlsCaptureFailure
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

/** Android OkHttp path using in-process fixtures only; no INTERNET permission or provider traffic. */
class IptvCaptureHttpAndroidTest {
    @Test fun returnedBodyRemainsReadableUntilExplicitClose() = runBlocking {
        var requests = 0
        val http = IptvCaptureHttp.newClient().newBuilder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("abcd".toResponseBody()).build()
        }.build()
        val client = IptvCaptureHttp(http)
        client.open(URI("https://fixture.invalid/list"), 4).use {
            assertEquals("abcd", it.readBytes().toString(Charsets.UTF_8))
        }
        assertTrue(client.close()); assertEquals(1, requests)
    }
    @Test fun redirectsAreRejectedWithoutAnotherRequest() = runBlocking {
        var requests = 0
        val http = IptvCaptureHttp.newClient().newBuilder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("fixture")
                .header("Location", "https://other.invalid/private").body("".toResponseBody()).build()
        }.build()
        val client = IptvCaptureHttp(http)
        try { client.open(URI("https://fixture.invalid/list"), 4); fail() }
        catch (error: HlsCaptureException) { assertEquals(HlsCaptureFailure.HTTP, error.failure); assertNull(error.cause) }
        assertTrue(client.close()); assertEquals(1, requests)
    }
    @Test fun realBufferedSourceCloseFailureRemainsUnconfirmedOnRetry() = runBlocking {
        var attempts = 0
        val bytes = object : ForwardingSource(Buffer().writeUtf8("abcd")) {
            override fun close() { attempts++; throw IOException("fixture private cause") }
        }.buffer()
        val http = IptvCaptureHttp.newClient().newBuilder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = 4L
                    override fun source() = bytes
                }).build()
        }.build()
        val client = IptvCaptureHttp(http, closeTimeoutMs = 25)
        val body = client.open(URI("https://fixture.invalid/list"), 4)
        repeat(2) {
            try { body.close(); fail() }
            catch (error: HlsCaptureException) { assertEquals(HlsCaptureFailure.NETWORK, error.failure); assertNull(error.cause) }
            assertFalse(client.close())
        }
        assertEquals(1, attempts)
    }
}
