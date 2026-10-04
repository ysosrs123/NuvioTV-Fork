package com.nuvio.tv.ui.screens.player

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlaybackPrewarmCoordinatorTest {
    private class PendingCall(private val req: Request) : Call by testClient.newCall(req) {
        lateinit var callback: Callback
        var cancelled = false
        private var executed = false
        override fun request() = req
        override fun enqueue(responseCallback: Callback) { executed = true; callback = responseCallback }
        override fun execute(): Response = error("asynchronous only")
        override fun cancel() { cancelled = true }
        override fun isCanceled() = cancelled
        override fun isExecuted() = executed
        override fun timeout() = Timeout()
        override fun clone(): Call = PendingCall(req)
        fun respond(head: Boolean, code: Int = 206) {
            val size = if (head) PlaybackPrewarmCoordinator.HEAD_BYTES else PlaybackPrewarmCoordinator.TAIL_BYTES
            val total = 10_000_000
            val start = if (head) 0 else total - size
            callback.onResponse(this, Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").header("Content-Range", "bytes $start-${start + size - 1}/$total")
                .body(ByteArray(if (code == 206) size else 0).toResponseBody()).build())
        }
        fun fail() = callback.onFailure(this, IOException("test failure"))
    }

    private class Harness {
        val calls = mutableListOf<PendingCall>()
        val published = mutableListOf<Pair<String, Boolean>>()
        val events = mutableListOf<PlaybackPrewarmCoordinator.Event>()
        var acceptStore = true
        var selections = 0
        var clients = 0
        val coordinator = PlaybackPrewarmCoordinator({ selections++; published.clear() }, events::add) { request, _, _, head ->
            if (acceptStore) published += request.url.encodedPath to head
            acceptStore
        }
        fun start(request: Request = request()) = coordinator.start(request) {
            clients++
            Call.Factory { PendingCall(it).also(calls::add) }
        }
    }

    @Test fun `head finishing before suffix never starts a competing tail`() {
        val h = Harness(); h.start()
        h.calls[0].respond(head = true)
        h.start()
        assertEquals(2, h.calls.size)
        assertEquals(1, h.clients)
        h.calls[1].respond(head = false)
        assertEquals(listOf("/a" to true, "/a" to false), h.published)
    }

    @Test fun `suffix finishing before head also uses one pair`() {
        val h = Harness(); h.start()
        h.calls[1].respond(head = false); h.start(); h.calls[0].respond(head = true)
        assertEquals(2, h.calls.size)
        assertEquals(2, h.published.size)
    }

    @Test fun `range refusal provider errors and transport failures do not trigger extra requests`() {
        for (code in listOf(200, 401, 403, 416, 429, 503)) {
            val h = Harness(); h.start()
            h.calls[0].respond(true, code); h.calls[1].respond(false, code)
            assertEquals(2, h.calls.size); assertTrue(h.published.isEmpty())
            assertTrue(h.calls.all { it.cancelled })
        }
        val h = Harness(); h.start(); h.calls.forEach { it.fail() }
        assertEquals(2, h.calls.size); assertTrue(h.published.isEmpty())
    }

    @Test fun `new selection cancels previous calls and rejects their late results`() {
        val h = Harness(); h.start(); h.start(request("b"))
        assertTrue(h.calls.take(2).all { it.cancelled })
        h.calls[0].respond(true); h.calls[1].respond(false)
        assertTrue(h.published.isEmpty())
        h.calls[2].respond(true); h.calls[3].respond(false)
        assertEquals(listOf("/b" to true, "/b" to false), h.published)
    }

    @Test fun `changed credentials at the same URL are a new selection`() {
        val h = Harness(); h.start(request(token = "one")); h.start(request(token = "two"))
        assertEquals(4, h.calls.size)
        assertTrue(h.calls.take(2).all { it.cancelled })
    }

    @Test fun `departing old player cannot cancel a newer source`() {
        val h = Harness(); h.start(); h.start(request("b"))
        h.coordinator.cancel(request())
        assertFalse(h.calls[2].cancelled); assertFalse(h.calls[3].cancelled)
        h.coordinator.cancel(request("b"))
        assertTrue(h.calls.all { it.cancelled })
        h.calls[2].respond(true)
        assertTrue(h.published.isEmpty())
    }

    @Test fun `completed or failed pair permits a later playback and refreshes client policy`() {
        val h = Harness(); h.start(); h.calls[0].respond(true); h.calls[1].fail()
        h.start()
        assertEquals(4, h.calls.size); assertEquals(2, h.clients)
        assertTrue(h.published.isEmpty())
    }

    @Test fun `concurrent duplicate selections create only two calls`() {
        val h = Harness()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..24).map { pool.submit { start.await(); h.start() } }
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(2, h.calls.size); assertEquals(1, h.clients)
        } finally { pool.shutdownNow() }
    }

    @Test fun `request ranges cannot be widened by caller headers`() {
        val h = Harness(); h.start(request().newBuilder().header("Range", "bytes=0-").build())
        assertEquals("bytes=0-262143", h.calls[0].request().header("Range"))
        assertEquals("bytes=-4194304", h.calls[1].request().header("Range"))
    }

    @Test fun `status rejection and transport failure have distinct credential free outcomes`() {
        val h = Harness(); h.start(request("private?token=secret", "Bearer secret"))
        h.calls[0].respond(true, 429); h.calls[1].fail()
        val terminal = h.events.filter { it.outcome != PlaybackPrewarmCoordinator.Outcome.STARTED }
        assertEquals(2, terminal.size)
        assertEquals(PlaybackPrewarmReader.Rejection.HTTP_STATUS, terminal[0].rejection)
        assertEquals(429, terminal[0].status)
        assertEquals(PlaybackPrewarmCoordinator.Outcome.TRANSPORT_FAILURE, terminal[1].outcome)
        assertTrue(terminal.all { it.validatedBytes == 0 && it.elapsedMs >= 0 })
        assertFalse(h.events.toString().contains("secret"))
        assertFalse(h.events.toString().contains("private"))
    }

    @Test fun `replacement and explicit stop remain distinguishable after late callbacks`() {
        val h = Harness(); h.start(); h.start(request("b"))
        h.calls[0].fail(); h.calls[1].respond(false)
        h.coordinator.cancel(request("b")); h.calls[2].fail(); h.calls[3].respond(false)
        val cancelled = h.events.filter { it.outcome == PlaybackPrewarmCoordinator.Outcome.CANCELLED }
        assertEquals(4, cancelled.size)
        assertTrue(cancelled.take(2).all { it.cancellation == PlaybackPrewarmCoordinator.Cancellation.SELECTION_REPLACED })
        assertTrue(cancelled.drop(2).all { it.cancellation == PlaybackPrewarmCoordinator.Cancellation.EXPLICIT_STOP })
        assertTrue(h.published.isEmpty())
        assertEquals(2, cancelled.map { it.batchId }.distinct().size)
    }

    @Test fun `validated window is distinct from accepted store publication`() {
        val h = Harness(); h.start(); h.calls[0].respond(true)
        h.acceptStore = false; h.calls[1].respond(false)
        val completed = h.events.filter { it.validatedBytes > 0 }
        assertEquals(PlaybackPrewarmCoordinator.Outcome.STORED, completed[0].outcome)
        assertEquals(PlaybackPrewarmCoordinator.HEAD_BYTES, completed[0].validatedBytes)
        assertEquals(PlaybackPrewarmCoordinator.Outcome.STORE_DECLINED, completed[1].outcome)
        assertEquals(PlaybackPrewarmCoordinator.TAIL_BYTES, completed[1].validatedBytes)
        assertEquals(1, h.published.size)
    }

    @Test fun `diagnostic observer failure cannot stop publication or cancellation`() {
        val calls = mutableListOf<PendingCall>()
        var published = 0
        val coordinator = PlaybackPrewarmCoordinator({}, { error("diagnostic failure") }) { _, _, _, _ ->
            published++; true
        }
        coordinator.start(request()) { Call.Factory { PendingCall(it).also(calls::add) } }
        calls[0].respond(true)
        coordinator.cancel(request()); calls[1].fail()
        assertEquals(1, published)
        assertTrue(calls[1].cancelled)
    }

    companion object {
        private val testClient = okhttp3.OkHttpClient()
        private fun request(path: String = "a", token: String = "test") = Request.Builder()
            .url("https://example.invalid/$path").header("Authorization", token)
            .header("Accept-Encoding", "identity").build()
    }
}
