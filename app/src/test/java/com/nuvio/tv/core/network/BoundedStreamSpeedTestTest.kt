package com.nuvio.tv.core.network

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.*
import okio.Buffer
import okio.Source
import okio.buffer
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedStreamSpeedTestTest {
    private class Body(val size: Long, val call: Pending, val h: Harness) : ResponseBody() {
        var consumed = 0L
        var closed = false
        private val source = object : Source {
            override fun timeout() = Timeout.NONE
            override fun read(sink: Buffer, byteCount: Long): Long {
                h.beforeRead()
                if (h.failRead) throw IOException("synthetic body failure")
                if (h.zeroRead) return 0
                if (consumed == size) return -1
                val count = minOf(byteCount, size - consumed, h.perRead.toLong()).toInt()
                consumed += count
                h.nanos += h.readNanos(count)
                sink.write(ByteArray(count))
                return count.toLong()
            }
            override fun close() { assertTrue(call.cancelled); closed = true }
        }.buffer()
        override fun source() = source
        override fun contentType(): MediaType? = null
        override fun contentLength() = size
    }
    private class Pending(val req: Request, val h: Harness) : Call by client.newCall(req) {
        lateinit var callback: Callback
        var cancelled = false
        private val timeout = Timeout()
        override fun request() = req
        override fun timeout() = timeout
        override fun cancel() { cancelled = true }
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        fun respond(size: Long = 100, code: Int = 200, url: String? = null, vararg headers: Pair<String,String>): Body {
            val body = Body(size, this, h)
            h.lastBody = body
            val request = if (url == null) req else req.newBuilder().url(url).build()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").body(body).apply { headers.forEach { (k,v) -> addHeader(k,v) } }.build()
            callback.onResponse(this,response)
            return body
        }
        fun fail() = callback.onFailure(this,IOException("synthetic failure"))
    }
    private class Harness(val limits: BoundedStreamSpeedTest.Limits = BoundedStreamSpeedTest.Limits(warmupBytes=8, measuredBytes=16)) {
        val calls = mutableListOf<Pending>()
        val playback = DiagnosticPlaybackGuard()
        var nanos = 0L
        var perRead = 8
        var zeroRead = false
        var failRead = false
        var beforeRead: () -> Unit = {}
        var readNanos: (Int) -> Long = { 1_000_000L }
        var lastBody: Body? = null
        val runner = BoundedStreamSpeedTest(Call.Factory { request -> Pending(request,this).also { calls += it } },playback,limits) { nanos }
    }
    companion object { private val client = OkHttpClient() }

    @Test fun `ignored range stays within warm and measured byte ceilings and closes after cancel`() = runTest {
        val h=Harness()
        val result=async { h.runner.run("https://media.invalid/title", mapOf("Authorization" to "synthetic", "Range" to "bytes=999-", "Accept-Encoding" to "gzip")) }
        runCurrent(); val call=h.calls.single()
        assertEquals("bytes=0-23",call.req.header("Range")); assertEquals("identity",call.req.header("Accept-Encoding"))
        assertEquals("synthetic",call.req.header("Authorization"))
        val body=call.respond()
        val sample=result.await()
        assertEquals(8L,sample.warmupBytes); assertEquals(16L,sample.measuredBytes)
        assertEquals(24L,body.consumed); assertEquals(24L,sample.totalBytes)
        assertTrue(body.closed); assertTrue(call.cancelled); assertNull(sample.failure)
    }

    @Test fun `measurement excludes warmup bytes and warmup elapsed time`() = runTest {
        val h=Harness(); h.readNanos={ if(h.lastBody!!.consumed <= 8L) 500_000_000L else 1_000_000L }
        val result=async { h.runner.run("https://media.invalid/title",emptyMap()) }
        runCurrent();h.calls.single().respond()
        val sample=result.await()
        assertEquals(2_000_000L,sample.measuredNanos)
        assertEquals(0.064,sample.mbps!!,0.000001)
    }

    @Test fun `response endpoint includes actual redirected host and port without private path`() = runTest {
        val h=Harness();val result=async {h.runner.run("https://redirect.invalid/private",emptyMap())}
        runCurrent();h.calls.single().respond(url="https://cdn.invalid:8443/private?token=synthetic")
        assertEquals("cdn.invalid:8443",result.await().servingEndpoint)
    }

    @Test fun `invalid encoded range status and length responses read no body`() = runTest {
        val cases=listOf(
            200 to listOf("Content-Encoding" to "gzip"),
            403 to emptyList(),
            200 to listOf("Content-Range" to "bytes 0-23/100"),
            200 to listOf("Content-Length" to "24", "Content-Length" to "24"),
            206 to listOf("Content-Range" to "bytes 1-23/100"),
            206 to listOf("Content-Range" to "bytes 0-24/100"),
            206 to listOf("Content-Range" to "bytes 0-23/23"),
            206 to listOf("Content-Range" to "bytes 0-23/*"),
            206 to listOf("Content-Range" to "bytes 0-23/100", "Content-Length" to "25"),
            206 to listOf("Content-Range" to "bytes 0-23/100", "Content-Range" to "bytes 0-23/100")
        )
        for ((code,headers) in cases) {
            val h=Harness();val result=async {h.runner.run("https://media.invalid/title",emptyMap())}
            runCurrent();val body=h.calls.single().respond(100,code,null,*headers.toTypedArray())
            assertEquals(BoundedStreamSpeedTest.Failure.UNAVAILABLE,result.await().failure)
            assertEquals(0L,body.consumed);assertTrue(body.closed)
        }
    }

    @Test fun `valid partial response beginning at zero is measured`() = runTest {
        val h=Harness();val result=async {h.runner.run("https://media.invalid/title",emptyMap())}
        runCurrent();h.calls.single().respond(24,206,null,"Content-Range" to "bytes 0-23/100","Content-Length" to "24")
        assertNotNull(result.await().mbps)
    }

    @Test fun `EOF before measurement is unavailable while shorter measured sample remains qualified`() = runTest {
        val h=Harness();val first=async {h.runner.run("https://media.invalid/title",emptyMap())}
        runCurrent();h.calls.last().respond(4)
        assertNull(first.await().mbps);assertEquals(BoundedStreamSpeedTest.Failure.UNAVAILABLE,first.await().failure)
        val second=async {h.runner.run("https://media.invalid/title",emptyMap())}
        runCurrent();h.calls.last().respond(12)
        assertEquals(4L,second.await().measuredBytes);assertNotNull(second.await().mbps)
    }

    @Test fun `zero progress and body exceptions close the owned request without a rate`() = runTest {
        for (zero in listOf(true,false)) {
            val h=Harness();h.zeroRead=zero;h.failRead=!zero
            val result=async {h.runner.run("https://media.invalid/title",emptyMap())}
            runCurrent();val body=h.calls.single().respond()
            assertNull(result.await().mbps);assertTrue(body.closed);assertTrue(h.calls.single().cancelled)
        }
    }

    @Test fun `measured time target checks read boundaries and records actual elapsed time`() = runTest {
        val h=Harness(BoundedStreamSpeedTest.Limits(warmupBytes=8,measuredBytes=16,measuredMs=2))
        h.perRead=2
        val result=async {h.runner.run("https://media.invalid/title",emptyMap())}
        runCurrent();h.calls.single().respond()
        assertEquals(4L,result.await().measuredBytes);assertEquals(2_000_000L,result.await().measuredNanos)
    }

    @Test fun `warmup time target can end before warmup byte ceiling`() = runTest {
        val h=Harness(BoundedStreamSpeedTest.Limits(warmupBytes=8,measuredBytes=16,warmupMs=1))
        h.perRead=2
        val result=async {h.runner.run("https://media.invalid/title",emptyMap())}
        runCurrent();h.calls.single().respond()
        assertEquals(2L,result.await().warmupBytes);assertEquals(16L,result.await().measuredBytes)
    }

    @Test fun `whole deadline cancels unresolved call and retains admission until callback`() = runTest {
        val h=Harness();val result=async {h.runner.run("https://media.invalid/title",emptyMap())};runCurrent()
        advanceTimeBy(15_000);runCurrent()
        assertEquals(BoundedStreamSpeedTest.Failure.TIMED_OUT,result.await().failure)
        assertTrue(h.calls.single().cancelled)
        repeat(20) {assertEquals(BoundedStreamSpeedTest.Failure.BUSY,h.runner.run("https://media.invalid/title",emptyMap()).failure)}
        assertEquals(1,h.calls.size);h.calls.single().fail()
        val next=async {h.runner.run("https://media.invalid/title",emptyMap())};runCurrent();h.calls.last().respond()
        assertNotNull(next.await().mbps)
    }

    @Test fun `caller cancellation propagates and late response is closed unread`() = runTest {
        val h=Harness();val result=async {h.runner.run("https://media.invalid/title",emptyMap())};runCurrent()
        result.cancelAndJoin();assertTrue(result.isCancelled)
        assertEquals(BoundedStreamSpeedTest.Failure.BUSY,h.runner.run("https://media.invalid/title",emptyMap()).failure)
        val body=h.calls.single().respond();assertEquals(0L,body.consumed);assertTrue(body.closed)
    }

    @Test fun `existing player lease denies baseline without HTTP calls`() = runTest {
        val h=Harness();val player=h.playback.enterPlayback()
        assertEquals(BoundedStreamSpeedTest.Failure.PLAYBACK_ACTIVE,h.runner.run("https://media.invalid/title",emptyMap()).failure)
        assertTrue(h.calls.isEmpty());player.close()
        val result=async {h.runner.run("https://media.invalid/title",emptyMap())};runCurrent();h.calls.single().respond()
        assertNotNull(result.await().mbps)
    }

    @Test fun `starting playback cancels admitted baseline and does not produce zero speed`() = runTest {
        val h=Harness();val result=async {h.runner.run("https://media.invalid/title",emptyMap())};runCurrent()
        val player=h.playback.enterPlayback();runCurrent()
        assertTrue(result.isCancelled);assertTrue(h.calls.single().cancelled)
        h.calls.single().fail();player.close()
    }

    @Test fun `invalid URL or request headers fail before starting network work and release admission`() = runTest {
        val h=Harness()
        assertEquals(BoundedStreamSpeedTest.Failure.UNAVAILABLE,h.runner.run("not a URL",emptyMap()).failure)
        assertEquals(BoundedStreamSpeedTest.Failure.UNAVAILABLE,h.runner.run("https://media.invalid/title",mapOf("Bad\nName" to "value")).failure)
        assertTrue(h.calls.isEmpty())
        val result=async {h.runner.run("https://media.invalid/title",emptyMap())};runCurrent();h.calls.single().respond()
        assertNotNull(result.await().mbps)
    }

    @Test fun `deadline during blocked body read returns and prevents fresh work until callback exits`() = runTest {
        val h=Harness();val entered=CountDownLatch(1);val release=CountDownLatch(1);val exited=CountDownLatch(1)
        h.beforeRead={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val result=async {h.runner.run("https://media.invalid/title",emptyMap())};runCurrent()
        val worker=Thread {try {h.calls.single().respond()} finally {exited.countDown()}}
        try {
            worker.start();assertTrue(entered.await(2,TimeUnit.SECONDS))
            advanceTimeBy(15_000);runCurrent()
            assertEquals(BoundedStreamSpeedTest.Failure.TIMED_OUT,result.await().failure)
            assertTrue(h.calls.single().cancelled)
            assertEquals(BoundedStreamSpeedTest.Failure.BUSY,h.runner.run("https://media.invalid/title",emptyMap()).failure)
            release.countDown();assertTrue(exited.await(2,TimeUnit.SECONDS))
            assertTrue(h.lastBody!!.closed)
        } finally {release.countDown();worker.join(2000);assertFalse(worker.isAlive)}
    }
}
