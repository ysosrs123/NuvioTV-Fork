package com.nuvio.tv.ui.screens.player

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class PlaybackPrewarmCallFactoryTest {
    private class Pending(private val request: Request) : Call by testClient.newCall(request) {
        lateinit var callback: Callback
        var cancelled = false
        val timeout = Timeout()
        override fun request() = request
        override fun timeout() = timeout
        override fun isExecuted() = this::callback.isInitialized
        override fun isCanceled() = cancelled
        override fun cancel() { cancelled = true }
        override fun clone(): Call = Pending(request)
        override fun execute(): Response = error("async")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
    }
    private class Harness {
        val primary = mutableListOf<Pending>()
        val fallback = mutableListOf<Pending>()
        var now = 0L
        var failures = 0
        var onBody: (Call) -> Unit = {}
        val factory = PlaybackPrewarmCallFactory(
            Call.Factory { Pending(it).also(primary::add) },
            { Call.Factory { Pending(it).also(fallback::add) } }, { now }
        )
        val call = factory.newCall(Request.Builder().url("https://example.invalid/media").build())
        fun start() = call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { failures++ }
            override fun onResponse(call: Call, response: Response) { response.use { onBody(call) } }
        })
        fun sslFailure() = primary.single().let { it.callback.onFailure(it, SSLException("self-signed")) }
    }

    @Test fun `TLS fallback gets only the remaining overall time`() {
        val h = Harness(); h.start(); h.now = TimeUnit.SECONDS.toNanos(9); h.sslFailure()
        assertEquals(TimeUnit.SECONDS.toNanos(6), h.fallback.single().timeout.timeoutNanos())
        h.call.cancel(); assertTrue(h.fallback.single().cancelled)
    }
    @Test fun `cancel before fallback prevents any fallback call`() {
        val h = Harness(); h.start(); h.call.cancel(); h.sslFailure()
        assertTrue(h.primary.single().cancelled); assertTrue(h.fallback.isEmpty()); assertEquals(1, h.failures)
    }
    @Test fun `expired deadline prevents fallback`() {
        val h = Harness(); h.start(); h.now = TimeUnit.SECONDS.toNanos(15); h.sslFailure()
        assertTrue(h.fallback.isEmpty()); assertEquals(1, h.failures)
    }
    @Test fun `non TLS failures never fall back or retry`() {
        val h = Harness(); h.start()
        h.primary.single().let { it.callback.onFailure(it, IOException("disconnected")) }
        assertTrue(h.fallback.isEmpty()); assertEquals(1, h.failures)
    }
    @Test fun `rejected fallback body cancels the actual body owner`() {
        val h = Harness(); h.start(); h.sslFailure()
        val fallback = h.fallback.single()
        h.onBody = { outer -> outer.cancel(); assertTrue(fallback.cancelled) }
        fallback.callback.onResponse(fallback, Response.Builder().request(fallback.request())
            .protocol(Protocol.HTTP_1_1).code(200).message("ignored range").body("body".toResponseBody()).build())
        assertTrue(h.call.isCanceled()); assertEquals(0, h.failures)
    }
    @Test fun `TLS failure in fallback terminates after two attempts`() {
        val h = Harness(); h.start(); h.sslFailure()
        h.fallback.single().let { it.callback.onFailure(it, SSLException("failed")) }
        assertEquals(1, h.primary.size); assertEquals(1, h.fallback.size); assertEquals(1, h.failures)
    }
    @Test fun `cancellation before enqueue starts no transport`() {
        val h = Harness(); h.call.cancel(); h.start()
        assertFalse(h.primary.single().isExecuted()); assertTrue(h.primary.single().cancelled)
        assertEquals(1, h.failures)
    }
    companion object { private val testClient = okhttp3.OkHttpClient() }
}
