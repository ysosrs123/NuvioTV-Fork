package com.nuvio.tv.data.iptv

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class IptvLiveNetTest {
    private class Server(private val closeFirst: Boolean = false) : AutoCloseable {
        val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val accepts = AtomicInteger()
        val port get() = socket.localPort
        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: IOException) { break }
                    val index = accepts.incrementAndGet()
                    thread(isDaemon = true) { serve(client, closeFirst && index == 1) }
                }
            }
        }
        private fun serve(client: Socket, close: Boolean) = client.use {
            if (close) return
            val input = it.getInputStream().bufferedReader()
            while (true) { val line = input.readLine() ?: return; if (line.isEmpty()) break }
            it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
            it.getOutputStream().flush()
        }
        override fun close() = socket.close()
    }

    private fun connections(maxAgeMs: Long = 10_000) = IptvWarmConnections(64 * 1024, resolve = { listOf(InetAddress.getLoopbackAddress()) }, maxAgeMs = maxAgeMs)

    private fun client(warm: IptvWarmConnections) = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(warm.dns).socketFactory(warm.sockets)
        .addNetworkInterceptor(warm.redirects).retryOnConnectionFailure(true).readTimeout(5, TimeUnit.SECONDS).build()

    private fun fetch(client: OkHttpClient, url: String) = client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) { check(System.currentTimeMillis() < deadline) { "Timed out" }; Thread.sleep(10) }
    }

    @Test fun preparedConnectionCarriesTheFirstRequest() = Server().use { server ->
        val warm = connections()
        val url = "http://warm.test:${server.port}/live/1.ts"
        warm.warm(url)
        waitFor { warm.warmCount() == 1 && server.accepts.get() == 1 }
        assertEquals("ok", fetch(client(warm), url))
        assertEquals(1, server.accepts.get())
        assertEquals(1, warm.used)
        assertEquals(0, warm.warmCount())
    }

    @Test fun droppedConnectionIsNotUsed() = Server().use { server ->
        val warm = connections()
        val url = "http://warm.test:${server.port}/live/1.ts"
        warm.warm(url)
        waitFor { warm.warmCount() == 1 }
        warm.drop()
        assertEquals("ok", fetch(client(warm), url))
        assertEquals(2, server.accepts.get())
        assertEquals(0, warm.used)
    }

    @Test fun connectionClosedByTheServerFallsBackToANewOne() = Server(closeFirst = true).use { server ->
        val warm = connections()
        val url = "http://warm.test:${server.port}/live/1.ts"
        warm.warm(url)
        waitFor { warm.warmCount() == 1 && server.accepts.get() == 1 }
        Thread.sleep(100)
        assertEquals("ok", fetch(client(warm), url))
        assertEquals(2, server.accepts.get())
        assertEquals(0, warm.used)
    }

    @Test fun oldConnectionExpires() = Server().use { server ->
        val warm = connections(maxAgeMs = 50)
        val url = "http://warm.test:${server.port}/live/1.ts"
        warm.warm(url)
        waitFor { server.accepts.get() == 1 }
        Thread.sleep(150)
        assertEquals("ok", fetch(client(warm), url))
        assertEquals(0, warm.used)
        assertEquals(0, warm.warmCount())
    }

    @Test fun redirectTargetIsPreparedNextTime() = Server().use { target ->
        val origin = MockWebServer()
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://stream.test:${target.port}/play/1.ts"))
        origin.start()
        try {
            val warm = connections()
            val url = "http://panel.test:${origin.port}/live/1.ts"
            assertEquals("ok", fetch(client(warm), url))
            assertEquals(1, target.accepts.get())
            warm.warm(url)
            waitFor { warm.warmCount() == 2 && target.accepts.get() == 2 }
        } finally { origin.shutdown() }
    }

    @Test fun trackedCallsEndWhenCancelledAndLateCallsAreRefused() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("x".repeat(200_000)).throttleBody(1_024, 100, TimeUnit.MILLISECONDS))
        server.start()
        try {
            val calls = IptvLiveCalls()
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).eventListenerFactory(calls).build()
            val url = server.url("/live/1.ts")
            val reading = thread {
                runCatching { client.newCall(Request.Builder().url(url).build()).execute().use { it.body.source().readByteArray() } }
            }
            waitFor { calls.count == 1 }
            calls.cancelAll()
            reading.join(5_000)
            waitFor { calls.count == 0 }
            assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(url).build()).execute().close() }
            waitFor { calls.count == 0 }
        } finally { server.shutdown() }
    }
}
