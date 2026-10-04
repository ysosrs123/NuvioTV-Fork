package com.nuvio.tv.core.network

import com.nuvio.tv.ui.screens.player.PlaybackPrewarmCallFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okio.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLException

@OptIn(ExperimentalCoroutinesApi::class)
class StreamContentLengthProbeTest {
    private class Pending(private val req: Request) : Call by client.newCall(req) {
        lateinit var callback: Callback
        var cancelled = false
        val limit = Timeout()
        override fun request() = req
        override fun timeout() = limit
        override fun cancel() { cancelled = true }
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        fun fail(error: IOException = IOException("synthetic")) = callback.onFailure(this, error)
        fun respond(code: Int = 200, vararg headers: Pair<String, String>): Body {
            val body = Body { assertTrue("Cancel before body close", cancelled) }
            val response = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .code(code).message("synthetic").body(body).apply {
                    headers.forEach { (k,v) -> addHeader(k,v) }
                }.build()
            callback.onResponse(this, response)
            return body
        }
    }
    private class Body(onClose: () -> Unit) : ResponseBody() {
        var reads = 0
        var closed = false
        private val content = object : Source {
            override fun timeout() = Timeout.NONE
            override fun read(sink: Buffer, byteCount: Long): Long {
                reads++; error("Metadata probe must not consume a body")
            }
            override fun close() { onClose(); closed = true }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = -1L
        override fun source() = content
    }
    private class Harness(timeout: Long = 1_000) {
        val pending = mutableListOf<Pending>()
        val factory = Call.Factory { Pending(it).also(pending::add) }
        val probe = StreamContentLengthProbe(factory, timeout)
    }

    @Test fun `HEAD size returns without GET or body reads`() = runTest {
        val h = Harness(); val result = async { h.probe.probe(url, emptyMap()) }; runCurrent()
        val call = h.pending.single()
        assertEquals("HEAD", call.request().method)
        val body = call.respond(200, "Content-Length" to "20000000000")
        assertEquals(20000000000L, result.await()); assertTrue(body.closed); assertEquals(0,body.reads)
        assertEquals(1,h.pending.size)
    }
    @Test fun `caller headers survive but range and encoding are controlled`() = runTest {
        val h = Harness(); val result = async { h.probe.probe(url,
            mapOf("Authorization" to "synthetic", "range" to "bytes=999-", "accept-encoding" to "gzip")) }
        runCurrent(); val head = h.pending.single().request()
        assertEquals("synthetic",head.header("Authorization")); assertNull(head.header("Range"))
        assertEquals("identity",head.header("Accept-Encoding"))
        h.pending.single().respond(405); runCurrent()
        val get = h.pending.last()
        assertEquals("bytes=0-0",get.request().header("Range")); assertEquals("GET",get.request().method)
        get.respond(206,"Content-Range" to "bytes 0-0/999", "Content-Length" to "1")
        assertEquals(999L,result.await())
    }
    @Test fun `range ignored GET uses full length without consuming body`() = runTest {
        val h=Harness(); val result=async { h.probe.probe(url,emptyMap()) }; runCurrent()
        h.pending.single().respond(200); runCurrent()
        val body=h.pending.last().respond(200,"Content-Length" to "999999999")
        assertEquals(999999999L,result.await()); assertTrue(body.closed); assertEquals(0,body.reads)
    }
    @Test fun `partial HEAD cannot report one byte as the media size`() = runTest {
        val h=Harness(); val result=async { h.probe.probe(url,emptyMap()) }; runCurrent()
        h.pending.single().respond(206,"Content-Range" to "bytes 0-0/900", "Content-Length" to "1")
        runCurrent(); assertEquals(2,h.pending.size)
        h.pending.last().respond(206,"Content-Range" to "bytes 0-0/900")
        assertEquals(900L,result.await())
    }
    @Test fun `encoded responses do not supply representation size`() = runTest {
        val h=Harness(); val result=async { h.probe.probe(url,emptyMap()) }; runCurrent()
        h.pending.single().respond(200,"Content-Length" to "200", "Content-Encoding" to "gzip")
        runCurrent(); h.pending.last().respond(206,"Content-Range" to "bytes 0-0/900", "Content-Encoding" to "br")
        assertEquals(0L,result.await())
    }
    @Test fun `malformed and nonzero-start ranges never become total sizes`() {
        listOf("bytes 1-1/20", "bytes 0-1/20", "bytes 0-0/*", "bytes 0-0/0",
            "bytes 0-0/9223372036854775808", "bytes 0-0/20, 0-0/20", "items 0-0/20").forEach { range ->
            assertEquals(range,0L,parse(206,"GET","Content-Range" to range))
        }
    }
    @Test fun `contradictory duplicate or overflowing lengths are unavailable`() {
        assertEquals(0L,parse(200,"HEAD","Content-Length" to "4", "Content-Length" to "4"))
        assertEquals(0L,parse(200,"HEAD","Content-Length" to "9223372036854775808"))
        assertEquals(0L,parse(200,"HEAD","Content-Length" to "-1"))
        assertEquals(0L,parse(200,"HEAD","Content-Length" to "4,4"))
        assertEquals(0L,parse(206,"GET","Content-Range" to "bytes 0-0/100", "Content-Length" to "2"))
        assertEquals(0L,parse(206,"GET","Content-Range" to "bytes 0-0/100", "Content-Range" to "bytes 0-0/100"))
    }
    @Test fun `HTTP errors never become file sizes`() = runTest {
        val h=Harness(); val result=async { h.probe.probe(url,emptyMap()) }; runCurrent()
        h.pending.single().respond(403,"Content-Length" to "200"); runCurrent()
        h.pending.last().respond(404,"Content-Length" to "200")
        assertEquals(0L,result.await()); assertEquals(2,h.pending.size)
    }
    @Test fun `one total deadline covers HEAD and GET`() = runTest {
        val h=Harness(); val result=async { h.probe.probe(url,emptyMap()) }; runCurrent()
        advanceTimeBy(700); h.pending.single().respond(405); runCurrent()
        advanceTimeBy(300); runCurrent()
        assertEquals(0L,result.await()); assertTrue(h.pending.last().cancelled)
        h.pending.last().fail()
    }
    @Test fun `caller cancellation propagates and cancels the active call`() = runTest {
        val h=Harness(); val result=async { h.probe.probe(url,emptyMap()) }; runCurrent()
        result.cancel(); runCurrent(); assertTrue(result.isCancelled); assertTrue(h.pending.single().cancelled)
        try { result.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        h.pending.single().fail()
    }
    @Test fun `timed out unresolved call retains admission until callback exits`() = runTest {
        val h=Harness(); val first=async { h.probe.probe(url,emptyMap()) }; runCurrent()
        advanceTimeBy(1000); runCurrent(); assertEquals(0L,first.await())
        repeat(20) { assertEquals(0L,h.probe.probe(url,emptyMap())) }
        assertEquals(1,h.pending.size)
        h.pending.single().fail()
        val next=async { h.probe.probe(url,emptyMap()) }; runCurrent()
        assertEquals(2,h.pending.size); h.pending.last().respond(200,"Content-Length" to "90")
        assertEquals(90L,next.await())
    }
    @Test fun `concurrent request does not cancel the admitted owner`() = runTest {
        val h=Harness(); val result=async { h.probe.probe(url,emptyMap()) }; runCurrent()
        assertEquals(0L,h.probe.probe(url,emptyMap())); assertFalse(h.pending.single().cancelled)
        h.pending.single().respond(200,"Content-Length" to "90"); assertEquals(90L,result.await())
    }
    @Test fun `late response after timeout is cancelled and closed`() = runTest {
        val h=Harness(); val result=async { h.probe.probe(url,emptyMap()) }; runCurrent()
        advanceTimeBy(1000); runCurrent(); assertEquals(0L,result.await())
        val body=h.pending.single().respond(200,"Content-Length" to "80")
        assertTrue(body.closed); assertEquals(0,body.reads)
    }
    @Test fun `invalid URL releases admission without transport`() = runTest {
        val h=Harness(); assertEquals(0L,h.probe.probe("bad url",emptyMap())); assertTrue(h.pending.isEmpty())
        val result=async { h.probe.probe(url,emptyMap()) }; runCurrent()
        h.pending.single().respond(200,"Content-Length" to "80"); assertEquals(80L,result.await())
    }
    @Test fun `transport failure completes and permits the next probe`() = runTest {
        val h=Harness(); val first=async { h.probe.probe(url,emptyMap()) }; runCurrent()
        h.pending.single().fail(); assertEquals(0L,first.await())
        val next=async { h.probe.probe(url,emptyMap()) }; runCurrent()
        h.pending.last().respond(200,"Content-Length" to "80"); assertEquals(80L,next.await())
    }
    @Test fun `creation failure cannot permanently occupy admission`() = runTest {
        var count=0
        val probe=StreamContentLengthProbe(Call.Factory { count++; throw IllegalArgumentException("synthetic") })
        repeat(2) { assertEquals(0L,probe.probe(url,emptyMap())) }; assertEquals(2,count)
    }
    @Test fun `TLS fallback cancellation stays with actual active transport`() = runTest {
        val primary=mutableListOf<Pending>(); val fallback=mutableListOf<Pending>()
        val factory=PlaybackPrewarmCallFactory(Call.Factory { Pending(it).also(primary::add) },
            { Call.Factory { Pending(it).also(fallback::add) } })
        val probe=StreamContentLengthProbe(factory,1000)
        val result=async { probe.probe(url,emptyMap()) }; runCurrent()
        primary.single().fail(SSLException("synthetic")); assertEquals(1,fallback.size)
        result.cancel(); runCurrent(); assertTrue(fallback.single().cancelled)
        assertEquals(0L,probe.probe(url,emptyMap())); assertEquals(1,primary.size)
        fallback.single().fail()
    }
    @Test fun `successful TLS fallback body is cancelled before close`() = runTest {
        val primary=mutableListOf<Pending>(); val fallback=mutableListOf<Pending>()
        val probe=StreamContentLengthProbe(PlaybackPrewarmCallFactory(
            Call.Factory { Pending(it).also(primary::add) }, { Call.Factory { Pending(it).also(fallback::add) } }))
        val result=async { probe.probe(url,emptyMap()) }; runCurrent()
        primary.single().fail(SSLException("synthetic"))
        val body=fallback.single().respond(200,"Content-Length" to "600")
        assertEquals(600L,result.await()); assertTrue(body.closed)
    }
    private fun parse(code: Int, method: String, vararg headers: Pair<String,String>): Long {
        val response=Response.Builder().request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1).code(code).message("synthetic").apply {
                headers.forEach { (k,v) -> addHeader(k,v) }
            }.build()
        return StreamContentLengthProbe.contentLength(response,method)
    }
    companion object {
        private val client=OkHttpClient()
        private const val url="https://example.invalid/media"
    }
}
