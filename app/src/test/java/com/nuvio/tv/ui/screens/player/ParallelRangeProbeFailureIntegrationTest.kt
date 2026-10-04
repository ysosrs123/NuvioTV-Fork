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
class ParallelRangeProbeFailureIntegrationTest {
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
    private class Body(private val data: ByteArray, private val declared: Long = data.size.toLong(), private val readError: IOException? = null, private val closeError: RuntimeException? = null) : ResponseBody() {
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
            override fun close() { closes++; closeError?.let { throw it } }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = declared
        override fun source() = bufferSource
    }
    private data class Reply(val code: Int, val headers: List<Pair<String, String>>, val body: Body)
    private class Fixture(vararg replies: Reply, resolved: (Uri?) -> Unit = {}) {
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
            chunkSize = 262144, onResolvedUri = resolved, shouldAllowBackgroundPrefetch = { false }, isolateSession = true)
        fun spec(position: Long = 0L, length: Long = C.LENGTH_UNSET.toLong()) =
            DataSpec.Builder().setUri(fixtureUri).setPosition(position).setLength(length).build()
        fun close() { source.close(); assertTrue(source.diagnosticWorkersStopped) }
    }
    private fun ranged(body: Body, value: String = "bytes 0-7/8", extra: List<Pair<String, String>> = emptyList()) =
        Reply(206, listOf("Content-Range" to value) + extra, body)

    private fun validationFailure(reply: Reply, label: String, closeError: RuntimeException) {
        val f = Fixture(reply)
        try {
            val failure = assertThrows(IOException::class.java) { f.source.open(f.spec()) }
            assertEquals("Invalid parallel probe $label", failure.message)
            assertArrayEquals(arrayOf(closeError), failure.suppressed)
            assertEquals(0L, reply.body.readBytes)
            assertEquals(1, reply.body.closes)
        } finally { f.close() }
        assertEquals(1, reply.body.closes)
    }

    @Test fun `invalid206 geometry remains primary when actual body close throws`() {
        val closeError = IllegalStateException("fixture close")
        validationFailure(ranged(Body(bytes, closeError = closeError), "bytes 3-7/8"), "geometry", closeError)
    }

    @Test fun `invalid206 encoding remains primary when actual body close throws`() {
        val closeError = IllegalStateException("fixture close")
        validationFailure(ranged(Body(bytes, closeError = closeError), extra = listOf("Content-Encoding" to "gzip")), "encoding", closeError)
    }

    @Test fun `resolved callback failure remains primary before validation`() {
        val primary = IllegalArgumentException("fixture callback")
        val closeError = IllegalStateException("fixture close")
        val body = Body(bytes, closeError = closeError)
        val f = Fixture(ranged(body), resolved = { throw primary })
        try {
            assertSame(primary, assertThrows(IllegalArgumentException::class.java) { f.source.open(f.spec()) })
            assertArrayEquals(arrayOf(closeError), primary.suppressed)
            assertEquals(0L, body.readBytes)
            assertEquals(1, body.closes)
        } finally { f.close() }
    }

    @Test fun `invalid unbounded206 reopen preserves error and closes both response owners`() {
        val closeError = IllegalStateException("fixture close")
        val first = Body(bytes);val second = Body(bytes, closeError = closeError)
        val f = Fixture(Reply(200, emptyList(), first), ranged(second, "bytes 3-7/8"))
        try {
            val failure = assertThrows(IOException::class.java) { f.source.open(f.spec()) }
            assertEquals("Invalid parallel probe geometry", failure.message)
            assertArrayEquals(arrayOf(closeError), failure.suppressed)
            assertEquals(1, first.closes);assertEquals(1, second.closes)
            assertEquals(0L, first.readBytes);assertEquals(0L, second.readBytes)
            assertEquals(2, f.calls.size)
        } finally { f.close() }
    }

    @Test fun `validation error without close error preserves label and empty suppressed list`() {
        val body = Body(bytes);val f = Fixture(ranged(body, "bytes 3-7/8"))
        try {
            val failure = assertThrows(IOException::class.java) { f.source.open(f.spec()) }
            assertEquals("Invalid parallel probe geometry", failure.message)
            assertEquals(0, failure.suppressed.size);assertEquals(1, body.closes)
            assertEquals(0L, body.readBytes)
        } finally { f.close() }
    }

    @Test fun `identical callback and close error does not self suppress`() {
        val primary = IllegalStateException("fixture shared failure")
        val body = Body(bytes, closeError = primary);val f = Fixture(ranged(body), resolved = { throw primary })
        try {
            assertSame(primary, assertThrows(IllegalStateException::class.java) { f.source.open(f.spec()) })
            assertEquals(0, primary.suppressed.size);assertEquals(1, body.closes)
            assertEquals(0L, body.readBytes)
        } finally { f.close() }
    }

    @Test fun `successful206 close failure still propagates once after payload`() {
        val closeError = IllegalStateException("fixture close")
        val body = Body(bytes, closeError = closeError);val f = Fixture(ranged(body))
        try {
            assertSame(closeError, assertThrows(IllegalStateException::class.java) { f.source.open(f.spec()) })
            assertEquals(8L, body.readBytes);assertEquals(1, body.closes)
            assertEquals(0, closeError.suppressed.size)
        } finally { f.close() }
        assertEquals(1, body.closes)
    }

    @Test fun `ignored200 first close error retains successful single reopen ownership`() {
        val first = Body(bytes, closeError = IllegalStateException("fixture first close"));val second = Body(bytes)
        val f = Fixture(Reply(200, emptyList(), first), Reply(200, emptyList(), second))
        try {
            assertEquals(8L, f.source.open(f.spec()))
            assertEquals(1, first.closes);assertEquals(0, second.closes)
            val actual = ByteArray(8);assertEquals(8, f.source.read(actual,0,8));assertArrayEquals(bytes,actual)
            assertEquals(2, f.calls.size)
        } finally { f.close() }
        assertEquals(1, second.closes)
    }

    @Test fun `ordinary404 without secondary failure retains bundled HTTP classification`() {
        val body = Body(ByteArray(0));val f = Fixture(Reply(404, emptyList(), body))
        try {
            val failure = assertThrows(androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException::class.java) { f.source.open(f.spec()) }
            assertEquals(404, failure.responseCode);assertEquals(0, failure.suppressed.size)
            assertEquals(1, body.closes)
        } finally { f.close() }
    }
}
