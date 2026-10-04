package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.mockk.*
import okhttp3.*
import okhttp3.Call
import okio.*
import org.junit.Assert.*
import org.junit.*
import java.io.IOException

/** Actual bundled OkHttpDataSource + production parallel source, no sockets or player. */
class ParallelRangeProbeResponseIntegrationTest {
    companion object {
        private lateinit var fixtureUri: Uri
        @JvmStatic @BeforeClass fun installUriStub() {
            fixtureUri = mockk(relaxed = true)
            every { fixtureUri.toString() } returns "https://example.invalid/range-fixture"
            every { fixtureUri.getQueryParameter(any()) } returns null
            every { fixtureUri.host } returns "example.invalid"
            mockkStatic(Uri::class)
            every { Uri.parse(any()) } returns fixtureUri
        }
        @JvmStatic @AfterClass fun restoreUriStub() { unmockkStatic(Uri::class) }
    }
    private val bytes = ByteArray(8) { it.toByte() }
    private class Body(private val data: ByteArray, private val declared: Long = data.size.toLong(), private val readError: IOException? = null) : ResponseBody() {
        var readBytes = 0L
        var closes = 0
        private var offset = 0
        private val bufferSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readError?.let { throw it }
                if (offset == data.size) return -1
                val n = minOf(byteCount.toInt(), data.size - offset)
                sink.write(data, offset, n); offset += n; readBytes += n; return n.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closes++ }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = declared
        override fun source() = bufferSource
    }
    private data class Reply(val code: Int, val headers: List<Pair<String, String>>, val body: Body)
    private class Fixture(vararg replies: Reply) {
        val calls = mutableListOf<Call>()
        val requests = mutableListOf<Request>()
        val pending = ArrayDeque(replies.toList())
        val callFactory = Call.Factory { request ->
            requests += request
            val reply = pending.removeFirst()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(reply.code).message("fixture").body(reply.body).also { builder ->
                    reply.headers.forEach { (name, value) -> builder.addHeader(name, value) }
                }.build()
            object : Call {
                private var cancelled = false
                private var executed = false
                override fun request() = request
                override fun execute(): Response { executed = true; return response }
                override fun enqueue(responseCallback: Callback) { executed = true; responseCallback.onResponse(this, response) }
                override fun cancel() { cancelled = true }
                override fun isExecuted() = executed
                override fun isCanceled() = cancelled
                override fun timeout() = Timeout.NONE
                override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null
                override fun <T> tag(type: Class<out T>): T? = null
                override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
                override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
                override fun clone(): Call = this
            }.also { calls += it }
        }
        val source = ParallelRangeDataSource(OkHttpDataSource.Factory(callFactory), parallelConnections = 1,
            chunkSize = 262144, shouldAllowBackgroundPrefetch = { false }, isolateSession = true)
        fun spec(position: Long = 0L, length: Long = C.LENGTH_UNSET.toLong()) =
            DataSpec.Builder().setUri(fixtureUri).setPosition(position).setLength(length).build()
        fun close() { source.close(); assertTrue(source.diagnosticWorkersStopped) }
    }
    private fun ranged(body: Body, value: String = "bytes 0-7/8", extra: List<Pair<String, String>> = emptyList()) =
        Reply(206, listOf("Content-Range" to value) + extra, body)
    private fun rejected(reply: Reply, position: Long = 0L, length: Long = C.LENGTH_UNSET.toLong()) {
        val f = Fixture(reply)
        try {
            assertThrows(IOException::class.java) { f.source.open(f.spec(position, length)) }
            assertEquals(0L, reply.body.readBytes)
            assertEquals(1, reply.body.closes)
            assertEquals(1, f.calls.size)
        } finally { f.close() }
    }
    @Test fun `valid bounded206 reads its exact short file and closes body`() {
        val body = Body(bytes); val f = Fixture(ranged(body))
        try {
            assertEquals(8L, f.source.open(f.spec()))
            assertEquals("bytes=0-262143", f.requests.single().header("Range"))
            assertEquals("identity", f.requests.single().header("Accept-Encoding"))
            val actual = ByteArray(8); assertEquals(8, f.source.read(actual, 0, 8)); assertArrayEquals(bytes, actual)
            assertEquals(1, body.closes)
        } finally { f.close() }
    }
    @Test fun `valid nonzero bounded206 uses requested geometry`() {
        val data = bytes.copyOfRange(3, 5); val body = Body(data); val f = Fixture(ranged(body, "bytes 3-4/8"))
        try {
            assertEquals(2L, f.source.open(f.spec(3, 2)))
            assertEquals("bytes=3-4", f.requests.single().header("Range"))
            val actual = ByteArray(2); assertEquals(2, f.source.read(actual, 0, 2)); assertArrayEquals(data, actual)
        } finally { f.close() }
    }
    @Test fun `wrong206 start rejects before payload`() = rejected(ranged(Body(bytes.copyOfRange(3, 8)), "bytes 3-7/8"))
    @Test fun `wrong206 end rejects before payload`() = rejected(ranged(Body(bytes.copyOfRange(0, 3)), "bytes 0-2/8"))
    @Test fun `duplicate206 range rejects before payload`() = rejected(ranged(Body(bytes), extra = listOf("Content-Range" to "bytes 0-7/8")))
    @Test fun `slash suffix masquerading as206 range rejects before payload`() = rejected(ranged(Body(bytes), "nonsense/8"))
    @Test fun `wrong explicit length rejects before payload`() = rejected(ranged(Body(bytes), extra = listOf("Content-Length" to "7")))
    @Test fun `hidden incompatible encoding rejects before payload`() = rejected(ranged(Body(bytes), extra = listOf("Content-Encoding" to "gzip", "Content-Encoding" to "identity")))
    @Test fun `ignored200 advertised bytes stays single and body owned until caller close`() {
        val first = Body(bytes); val second = Body(bytes)
        val f = Fixture(Reply(200, listOf("Accept-Ranges" to "bytes"), first), Reply(200, listOf("Accept-Ranges" to "bytes"), second))
        try {
            assertEquals(8L, f.source.open(f.spec()))
            assertEquals(2, f.calls.size); assertEquals(1, first.closes)
            assertEquals(0L, first.readBytes); assertEquals(0L, second.readBytes); assertEquals(0, second.closes)
            val actual = ByteArray(8); assertEquals(8, f.source.read(actual, 0, 8)); assertArrayEquals(bytes, actual)
        } finally { f.close() }
        assertEquals(1, second.closes)
    }
    @Test fun `ignored200 nonzero offset preserves upstream skip and single ownership`() {
        val first = Body(bytes); val second = Body(bytes)
        val f = Fixture(Reply(200, listOf("Accept-Ranges" to "bytes"), first), Reply(200, listOf("Accept-Ranges" to "bytes"), second))
        try {
            assertEquals(5L, f.source.open(f.spec(3)))
            assertTrue(first.readBytes > 0); assertTrue(second.readBytes > 0) // bundled open skips before caller sees headers
            assertEquals(0, second.closes)
            val actual = ByteArray(5); assertEquals(5, f.source.read(actual, 0, 5)); assertArrayEquals(bytes.copyOfRange(3, 8), actual)
        } finally { f.close() }
    }
    @Test fun `misaligned206 on unbounded reopen rejects its unread body`() {
        val first = Body(bytes); val second = Body(bytes.copyOfRange(4, 8))
        val f = Fixture(Reply(200, emptyList(), first), ranged(second, "bytes 4-7/8"))
        try {
            assertThrows(IOException::class.java) { f.source.open(f.spec(3)) }
            assertTrue(first.readBytes > 0); assertEquals(0L, second.readBytes); assertEquals(1, second.closes)
        } finally { f.close() }
    }
    @Test fun `valid206 on unbounded reopen retains single fallback ownership`() {
        val first = Body(bytes); val second = Body(bytes.copyOfRange(3, 8))
        val f = Fixture(Reply(200, emptyList(), first), ranged(second, "bytes 3-7/8"))
        try {
            assertEquals(5L, f.source.open(f.spec(3)))
            assertEquals(0L, second.readBytes); assertEquals(0, second.closes)
            val actual = ByteArray(5); assertEquals(5, f.source.read(actual, 0, 5)); assertArrayEquals(bytes.copyOfRange(3, 8), actual)
        } finally { f.close() }
    }
    @Test fun `single valid length and repeated identity encoding accepted`() {
        val body = Body(bytes); val f = Fixture(ranged(body, extra = listOf("Content-Length" to "8", "Content-Encoding" to "identity", "Content-Encoding" to "identity")))
        try { assertEquals(8L, f.source.open(f.spec())); assertEquals(1, body.closes) } finally { f.close() }
    }
    @Test fun `unknown206 total falls back to unbounded unknown length without parallel admission`() {
        val first = Body(bytes); val second = Body(bytes, declared = -1)
        val f = Fixture(ranged(first, "bytes 0-7/*"), Reply(200, listOf("Accept-Ranges" to "bytes"), second))
        try {
            assertEquals(C.LENGTH_UNSET.toLong(), f.source.open(f.spec()))
            assertEquals(0L, first.readBytes); assertEquals(0L, second.readBytes); assertEquals(0, second.closes)
            val actual = ByteArray(8); assertEquals(8, f.source.read(actual, 0, 8)); assertArrayEquals(bytes, actual)
            assertEquals(C.RESULT_END_OF_INPUT, f.source.read(actual, 0, 8))
        } finally { f.close() }
    }
    @Test fun `duplicate explicit length rejects before payload`() = rejected(ranged(Body(bytes), extra = listOf("Content-Length" to "8", "Content-Length" to "8")))
    @Test fun `overflow explicit length rejects before payload`() = rejected(ranged(Body(bytes), extra = listOf("Content-Length" to "9223372036854775808")))
    @Test fun `valid geometry with short body retains current EOF behavior`() {
        val body = Body(bytes.copyOfRange(0, 3), declared = 8); val f = Fixture(ranged(body))
        try {
            assertEquals(8L, f.source.open(f.spec()))
            val actual = ByteArray(3); assertEquals(3, f.source.read(actual, 0, 3)); assertArrayEquals(bytes.copyOfRange(0, 3), actual)
            assertEquals(1, body.closes)
        } finally { f.close() }
    }
    @Test fun `valid geometry with read error closes body and preserves primary`() {
        val error = IOException("fixture payload"); val body = Body(bytes, readError = error); val f = Fixture(ranged(body))
        try {
            val failure = assertThrows(IOException::class.java) { f.source.open(f.spec()) }
            assertSame(error, failure.cause)
            assertEquals(1, body.closes)
        } finally { f.close() }
    }
}
