package com.nuvio.tv.core.network

import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okio.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedNetworkSpeedTestTest {
    private class Body(val data: ByteArray?, val size: Long, val declared: Long = size,
                       val onRead: (Int) -> Unit, val onClose: () -> Unit) : ResponseBody() {
        var actualReads=0L
        var closed=false
        private val stream=object : Source {
            override fun timeout()=Timeout.NONE
            override fun close() { onClose(); closed=true }
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (actualReads>=size) return -1
                val n=minOf(byteCount,size-actualReads).toInt()
                if(data==null) sink.write(ByteArray(n)) else sink.write(data,actualReads.toInt(),n)
                actualReads+=n; onRead(n); return n.toLong()
            }
        }.buffer()
        override fun contentType(): MediaType?=null
        override fun contentLength()=declared
        override fun source()=stream
    }
    private inner class Pending(private val req:Request, private val harness:Harness) : Call by client.newCall(req) {
        lateinit var callback:Callback
        var cancelled=false
        private val limit=Timeout()
        override fun request()=req
        override fun timeout()=limit
        override fun cancel() { cancelled=true }
        override fun enqueue(responseCallback:Callback) { callback=responseCallback; harness.answer(this) }
        fun fail()=callback.onFailure(this,IOException("Synthetic failure"))
        fun respond(text:String?=null,size:Long=text?.toByteArray()?.size?.toLong() ?: 1024,
                    declared:Long=size,code:Int=200,vararg headers:Pair<String,String>):Body {
            val body=Body(text?.toByteArray(),size,declared,{harness.nanos+=1_000_000},{assertTrue(cancelled)})
            harness.bodies+=body
            val response=Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code)
                .message("Synthetic").body(body).apply { headers.forEach { (k,v)->addHeader(k,v) } }.build()
            callback.onResponse(this,response)
            return body
        }
    }
    private inner class Harness(
        limits:BoundedNetworkSpeedTest.Limits=BoundedNetworkSpeedTest.Limits(totalBytes=128*1024,totalMs=2000,downloadMs=1000)
    ) {
        var nanos=0L
        var stallAll=false
        var stallDownloads=true
        var oversizedHtml=false
        val calls=mutableListOf<Pending>()
        val bodies=mutableListOf<Body>()
        val guard=DiagnosticPlaybackGuard()
        val runner=BoundedNetworkSpeedTest(Call.Factory { Pending(it,this).also(calls::add) },guard,limits,nanoTime={nanos})
        val downloads get()=calls.filter { it.request().url.host.startsWith("cdn") }
        fun answer(call:Pending) {
            if(stallAll)return
            when(call.request().url.host) {
                "cloudflare.com"->call.respond("x")
                "fast.com"->if(call.request().url.encodedPath=="/") {
                    if(oversizedHtml)call.respond("x",declared=300000)
                    else call.respond("<script src=\"/app-v1.js\">")
                } else call.respond("token:\"abc123\"")
                "api.fast.com"->call.respond("""{"targets":[{"url":"https://cdn1.invalid/file"},{"url":"https://cdn2.invalid/file"},{"url":"https://cdn3.invalid/file"},{"url":"https://cdn4.invalid/file"},{"url":"https://cdn5.invalid/file"}]}""")
                else->if(!stallDownloads)call.respond()
            }
        }
    }
    @Test fun `discovery and HTTP timing precede at most four owned downloads`()=runTest {
        val h=Harness(); var latency:Long?=null; var phase=false
        val result=async { h.runner.run({latency=it},{phase=true}) }; runCurrent()
        assertEquals(4,h.downloads.size); assertEquals(1L,latency); assertTrue(phase)
        assertEquals(10,h.calls.size)
        h.downloads.forEach { assertEquals("identity",it.request().header("Accept-Encoding")); assertTrue(it.request().header("Range")!!.startsWith("bytes=0-")); it.respond() }
        val value=result.await()
        assertNull(value.failure); assertEquals(4096L,value.measuredBytes); assertTrue(value.mbps!!>0)
        assertEquals(setOf("cdn1.invalid","cdn2.invalid","cdn3.invalid","cdn4.invalid"),value.servingHosts)
        assertTrue(h.calls.all { it.cancelled }); assertTrue(h.bodies.all { it.closed })
    }
    @Test fun `ignored range responses stop at reserved body budget`()=runTest {
        val h=Harness(); val result=async { h.runner.run() }; runCurrent()
        val caps=h.downloads.map { it.request().header("Range")!!.substringAfter('-').toLong()+1 }
        h.downloads.forEach { it.respond(size=10_000_000) }
        val value=result.await()
        assertEquals(caps.sum(),value.measuredBytes); assertTrue(value.measuredBytes<128*1024)
        assertTrue(h.calls.all { it.cancelled }); assertTrue(h.bodies.all { it.closed })
    }
    @Test fun `oversized discovery is rejected before any body reads`()=runTest {
        val h=Harness(); h.oversizedHtml=true
        val result=h.runner.run()
        assertEquals(BoundedNetworkSpeedTest.Failure.UNAVAILABLE,result.failure)
        assertEquals(0L,h.bodies.last().actualReads); assertTrue(h.bodies.last().closed)
        assertTrue(h.downloads.isEmpty())
    }
    @Test fun `whole run deadline also bounds unresolved discovery`()=runTest {
        val h=Harness(); h.stallAll=true
        val result=async { h.runner.run() }; runCurrent(); advanceTimeBy(2000); runCurrent()
        assertEquals(BoundedNetworkSpeedTest.Failure.TIMED_OUT,result.await().failure)
        assertTrue(h.calls.single().cancelled)
        assertEquals(BoundedNetworkSpeedTest.Failure.BUSY,h.runner.run().failure)
        h.calls.single().fail()
        h.stallAll=false; h.stallDownloads=false
        assertNull(h.runner.run().failure)
    }
    @Test fun `caller cancellation cancels every started download`()=runTest {
        val h=Harness(); val result=async { h.runner.run() }; runCurrent()
        result.cancel(); runCurrent()
        assertTrue(result.isCancelled); assertTrue(h.downloads.all { it.cancelled })
        assertEquals(BoundedNetworkSpeedTest.Failure.BUSY,h.runner.run().failure)
        h.downloads.forEach { it.fail() }
        h.stallDownloads=false; assertNull(h.runner.run().failure)
    }
    @Test fun `download deadline returns an honest partial sample without late mutation`()=runTest {
        val h=Harness(); val result=async { h.runner.run() }; runCurrent()
        h.downloads.first().respond(); h.nanos+=1_000_000_000
        advanceTimeBy(1000); runCurrent()
        val value=result.await(); assertEquals(1024L,value.measuredBytes); assertNotNull(value.mbps)
        assertTrue(h.downloads.all { it.cancelled })
        h.downloads.drop(1).forEach { val body=it.respond(size=5000); assertTrue(body.closed); assertEquals(0L,body.actualReads) }
        assertEquals(1024L,value.measuredBytes)
    }
    @Test fun `empty or failed downloads do not become zero speed success`()=runTest {
        val h=Harness(); val result=async { h.runner.run() }; runCurrent()
        h.downloads.forEach { it.fail() }
        val value=result.await(); assertEquals(BoundedNetworkSpeedTest.Failure.UNAVAILABLE,value.failure)
        assertNull(value.mbps); assertEquals(4,value.failedWorkers)
    }
    @Test fun `one failed source is qualified without losing other samples`()=runTest {
        val h=Harness(); val result=async { h.runner.run() }; runCurrent()
        h.downloads.first().fail(); h.downloads.drop(1).forEach { it.respond() }
        val value=result.await(); assertNull(value.failure); assertEquals(1,value.failedWorkers)
        assertEquals(3072L,value.measuredBytes)
    }
    @Test fun `an existing player prevents any HTTP request`()=runTest {
        val h=Harness(); val player=h.guard.enterPlayback()
        assertEquals(BoundedNetworkSpeedTest.Failure.PLAYBACK_ACTIVE,h.runner.run().failure)
        assertTrue(h.calls.isEmpty()); player.close()
        h.stallDownloads=false; assertNull(h.runner.run().failure)
    }
    @Test fun `starting playback cancels an admitted network run`()=runTest {
        val h=Harness(); val result=async { h.runner.run() }; runCurrent()
        val player=h.guard.enterPlayback(); runCurrent()
        assertTrue(result.isCancelled); assertTrue(h.downloads.all { it.cancelled })
        h.downloads.forEach { it.fail() }; player.close()
        h.stallDownloads=false; assertNull(h.runner.run().failure)
    }
    @Test fun `repeated starts do not enqueue behind a blocked worker`()=runTest {
        val h=Harness(); h.stallAll=true
        val first=async { h.runner.run() }; runCurrent()
        repeat(20) { assertEquals(BoundedNetworkSpeedTest.Failure.BUSY,h.runner.run().failure) }
        assertEquals(1,h.calls.size); first.cancel(); runCurrent(); h.calls.single().fail()
    }
    @Test fun `encoded download bodies are rejected and closed unread`()=runTest {
        val h=Harness(); val result=async { h.runner.run() }; runCurrent()
        h.downloads.forEach { val body=it.respond(headers=arrayOf("Content-Encoding" to "gzip")); assertEquals(0L,body.actualReads); assertTrue(body.closed) }
        assertEquals(BoundedNetworkSpeedTest.Failure.UNAVAILABLE,result.await().failure)
    }
    @Test fun `partial responses must match the requested starting range`()=runTest {
        val h=Harness(); val result=async { h.runner.run() }; runCurrent()
        h.downloads.forEach { it.respond(code=206,headers=arrayOf("Content-Range" to "bytes 100-200/10000")) }
        assertEquals(BoundedNetworkSpeedTest.Failure.UNAVAILABLE,result.await().failure)
    }
    @Test fun `successful byte zero ranges are accepted`()=runTest {
        val h=Harness(); val result=async { h.runner.run() }; runCurrent()
        h.downloads.forEach { it.respond(code=206,headers=arrayOf("Content-Range" to "bytes 0-1023/10000")) }
        assertEquals(4096L,result.await().measuredBytes)
    }
    @Test fun `budget cannot oversubscribe with concurrent reservations`() {
        val budget=BoundedNetworkSpeedTest.Budget(10000)
        val start=CountDownLatch(1); val done=CountDownLatch(4); val total=AtomicLong()
        repeat(4) { Thread {
            try { start.await(); while(true) { val n=budget.reserve(73); if(n==0)break; total.addAndGet(n.toLong()); budget.complete(n,n) } }
            finally { done.countDown() }
        }.start() }
        start.countDown(); assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(10000L,total.get()); assertEquals(0L,budget.remaining())
    }
    @Test fun `short reads return unused reservations`() {
        val budget=BoundedNetworkSpeedTest.Budget(100)
        assertEquals(80,budget.reserve(80)); budget.complete(80,30)
        assertEquals(70L,budget.remaining()); assertEquals(70,budget.reserve(80))
    }
    @Test fun `discovery target count and URL forms are bounded`() {
        val json="""{"targets":[{"url":"http://invalid.test/file"},{"url":"https://user:pass@invalid.test/file"},{"url":"https://cdn.invalid/a"},{"url":"https://cdn.invalid/a"},{"url":"https://extra.invalid/a"}]}"""
        assertEquals(listOf("https://cdn.invalid/a"),BoundedNetworkSpeedTest.targets(json,4))
        assertNull(BoundedNetworkSpeedTest.scriptPath("<script src=\"https://elsewhere.invalid/app.js\">"))
        assertNull(BoundedNetworkSpeedTest.apiToken("token:\""+"a".repeat(129)+"\""))
    }
    @Test fun `production executor starts all four workers without waiting for queued work`() {
        val client=GeneralNetworkSpeedTest.createClient()
        val executor=client.dispatcher.executorService
        val started=CountDownLatch(4); val release=CountDownLatch(1)
        try {
            repeat(4) { executor.execute {
                started.countDown()
                try { release.await() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            } }
            assertTrue("Four workers must start before any completes",started.await(5,java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS))
            client.connectionPool.evictAll()
        }
    }
    companion object { private val client=OkHttpClient() }
}
