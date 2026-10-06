package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.*
import java.net.URI
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IptvCaptureHttpTest {
    @get:Rule val temp = TemporaryFolder()
    private fun MockWebServer.uri(path: String = "/list.m3u8") = URI(url(path).toString())
    private suspend fun failure(reason: HlsCaptureFailure, block: suspend () -> Unit) {
        try { block(); fail() } catch (error: HlsCaptureException) { assertEquals(reason, error.failure); assertNull(error.cause) }
    }
    @Test fun responseRemainsReadableAfterOpenAndOnlyOneBodyIsAllowed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcd")); server.start()
            val client = IptvCaptureHttp()
            val input = client.open(server.uri(), 4)
            assertEquals("abcd", input.readBytes().toString(Charsets.UTF_8))
            try { client.open(server.uri(), 4); fail() } catch (_: IllegalStateException) { }
            input.close(); assertTrue(client.close()); assertEquals(1, server.requestCount)
            assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
        }
    }
    @Test fun redirectIsNotFollowedAndFailureIsNotRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/secret")); server.start()
            val client = IptvCaptureHttp()
            failure(HlsCaptureFailure.HTTP) { client.open(server.uri(), 4) }
            assertTrue(client.close()); assertEquals(1, server.requestCount)
        }
    }
    @Test fun advertisedOversizeAndCompressedBodiesAreRejected() = runBlocking {
        for (compressed in listOf(false, true)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcde").apply { if (compressed) addHeader("Content-Encoding", "gzip") }); server.start()
            val client = IptvCaptureHttp()
            failure(if (compressed) HlsCaptureFailure.HTTP else HlsCaptureFailure.LIMIT) { client.open(server.uri(), 4) }
            assertTrue(client.close()); assertEquals(1, server.requestCount)
        }
    }
    @Test fun unknownLengthStreamCannotExceedByteBudget() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("abcde", 1)); server.start()
            val client = IptvCaptureHttp(); val body = client.open(server.uri(), 4)
            body.use { failure(HlsCaptureFailure.LIMIT) { it.readBytes() } }
            assertTrue(client.close())
        }
    }
    @Test fun outstandingBodyPreventsSuccessfulCloseAndLateOpenIsFenced() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcd")); server.start()
            val client = IptvCaptureHttp(closeTimeoutMs = 30); val body = client.open(server.uri(), 4)
            assertFalse(client.close())
            failure(HlsCaptureFailure.CLOSED) { client.open(server.uri(), 4) }
            body.close(); assertTrue(client.close()); assertEquals(1, server.requestCount)
        }
    }
    @Test fun closingConnectCancelsAndWaitsForActualResponseCleanup() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("abcd")); server.start()
            val client = IptvCaptureHttp()
            val request = async { runCatching { client.open(server.uri(), 4) } }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            assertTrue(client.close())
            assertTrue(request.await().exceptionOrNull() is HlsCaptureException)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun unsupportedManifestNeverFetchesItsMedia() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST\n")); server.start()
            val source = HlsCaptureSegmentSource(server.uri(), IptvCaptureHttp(), 1024)
            failure(HlsCaptureFailure.UNSUPPORTED_PLAYLIST) { source.next() }
            assertTrue(source.close()); assertEquals(1, server.requestCount)
        }
    }
    @Test fun controlledHttpSegmentsArePublishedAndReadLocallyThroughRealCaptureLoop() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:5\n#EXTINF:1,\na.ts\n#EXTINF:1,\nb.ts\n#EXT-X-ENDLIST\n"))
            server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1,2,3,4))))
            server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(5,6,7,8))))
            server.start()
            CaptureSegmentStore(temp.newFolder(), 8, 4).use { store ->
                val transport = SegmentCaptureTransport(store, HlsCaptureSegmentSource(server.uri(), IptvCaptureHttp(), 4))
                transport.start()
                withTimeout(5000) { transport.state.first { it == CaptureTransportState.COMPLETE || it == CaptureTransportState.FAILED } }
                assertEquals(CaptureTransportState.COMPLETE, transport.state.value)
                assertTrue(transport.close())
                assertEquals(listOf(0L, 1000L), store.snapshot().map { it.startMs })
                store.openSnapshotFrom(0).use { assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8), it.readBytes()) }
                assertEquals(3, server.requestCount)
                assertEquals(listOf("/list.m3u8", "/a.ts", "/b.ts"), List(3) { server.takeRequest().path })
            }
        }
    }
    @Test fun suppressedResponseCloseFailureCannotAuthorizeReservationRelease() = runBlocking {
        var closeAttempts = 0
        val bytes = object : ForwardingSource(Buffer().writeUtf8("abcd")) {
            override fun close() { closeAttempts++; throw IOException("private fixture endpoint") }
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
        val body = client.open(URI("https://fixture.invalid/private"), 4)
        failure(HlsCaptureFailure.NETWORK) { body.close() }
        assertFalse(client.close())
        failure(HlsCaptureFailure.NETWORK) { body.close() }
        assertFalse(client.close()); assertEquals(1, closeAttempts)
    }
    @Test fun cancellationBeforeDispatchBackReclaimsTheUndeliveredResponse() = runBlocking {
        val closes = AtomicInteger()
        val bytes = object : ForwardingSource(Buffer().writeUtf8("abcd")) {
            override fun close() { closes.incrementAndGet(); super.close() }
        }.buffer()
        val http = IptvCaptureHttp.newClient().newBuilder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = 4L
                    override fun source() = bytes
                }).build()
        }.build()
        val client = IptvCaptureHttp(http)
        val queue = LinkedBlockingQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val request = scope.async { client.open(URI("https://fixture.invalid/list"), 4) }
            assertNotNull(queue.poll(5, TimeUnit.SECONDS)?.also { it.run() })
            val resume = queue.poll(5, TimeUnit.SECONDS)
            assertNotNull(resume)
            request.cancel(); resume!!.run()
            assertTrue(request.isCancelled)
            assertTrue(client.close()); assertEquals(1, closes.get())
        } finally { scope.cancel() }
    }
}
