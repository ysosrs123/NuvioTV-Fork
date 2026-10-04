package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.network.DiagnosticPayloadBudget
import com.nuvio.tv.core.network.BoundedStreamSpeedTest
import com.nuvio.tv.core.network.DiagnosticPlaybackGuard
import kotlinx.coroutines.runBlocking
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

class ParallelDiagnosticCallsTest {
    @Test fun `private requests bypass playback interceptors events and scheduling`() = runBlocking {
        val events=AtomicInteger(); val dns=AtomicInteger(); val intercepts=AtomicInteger()
        val playback=OkHttpClient.Builder().dns {
            dns.incrementAndGet(); throw UnknownHostException("synthetic")
        }.eventListener(object : EventListener() { override fun callStart(call: Call) { events.incrementAndGet() } })
            .addInterceptor { intercepts.incrementAndGet(); error("playback interceptor") }
            .addNetworkInterceptor { error("playback network interceptor") }.build()
        ParallelDiagnosticCalls(2,playback) { error("no TLS fallback for DNS failure") }.use { calls ->
            assertEquals(BoundedStreamSpeedTest.Failure.UNAVAILABLE,BoundedStreamSpeedTest(calls,DiagnosticPlaybackGuard()).run("https://example.invalid/media",emptyMap()).failure)
        }
        assertEquals(1,dns.get()); assertEquals(0,events.get()); assertEquals(0,intercepts.get())
        assertEquals(0,playback.dispatcher.runningCallsCount()); assertEquals(0,playback.dispatcher.queuedCallsCount())
    }
    @Test fun `TLS fallback stays inside diagnostic HTTP resources`() = runBlocking {
        val primaryDns=AtomicInteger(); val fallbackDns=AtomicInteger(); val events=AtomicInteger()
        val listener=object : EventListener() { override fun callStart(call: Call) { events.incrementAndGet() } }
        val primary=OkHttpClient.Builder().dns { primaryDns.incrementAndGet(); throw SSLException("synthetic") }.eventListener(listener).build()
        val fallback=OkHttpClient.Builder().dns { fallbackDns.incrementAndGet(); throw UnknownHostException("synthetic") }.eventListener(listener).build()
        ParallelDiagnosticCalls(2,primary) { fallback }.use { calls ->
            assertEquals(BoundedStreamSpeedTest.Failure.UNAVAILABLE,BoundedStreamSpeedTest(calls,DiagnosticPlaybackGuard()).run("https://example.invalid/media",emptyMap()).failure)
        }
        assertEquals(1,primaryDns.get()); assertEquals(1,fallbackDns.get()); assertEquals(0,events.get())
        assertEquals(0,primary.dispatcher.runningCallsCount()); assertEquals(0,fallback.dispatcher.runningCallsCount())
    }
    @Suppress("UNCHECKED_CAST") private fun rawCalls(owner: ParallelDiagnosticCalls): List<Call> =
        (owner.javaClass.getDeclaredField("ownedCalls").apply { isAccessible=true }.get(owner) as List<Call>).toList()
    @Test fun `request admission caps retained references and closure cancels all raw calls`() {
        val owner=ParallelDiagnosticCalls(2,OkHttpClient()) { error("unused") }
        val request=Request.Builder().url("https://example.invalid/media").build()
        try {
            repeat(128) { owner.newCall(request) }
            try { owner.newCall(request); fail("request limit") } catch (_: IllegalStateException) {}
            val raw=rawCalls(owner); assertEquals(128,raw.size)
            owner.close(); owner.close()
            assertTrue(raw.all { it.isCanceled() }); assertTrue(rawCalls(owner).isEmpty())
            try { owner.newCall(request); fail("closed owner") } catch (_: IllegalStateException) {}
        } finally { owner.close() }
    }
    @Test fun `closing blocked resolver also delivers queued cancellation without fallback`() {
        val entered=CountDownLatch(1); val unblock=CountDownLatch(1); val done=CountDownLatch(2)
        val fallback=AtomicInteger()
        val client=OkHttpClient.Builder().dns {
            entered.countDown(); unblock.await(3,TimeUnit.SECONDS); throw SSLException("synthetic late TLS failure")
        }.build()
        val owner=ParallelDiagnosticCalls(1,client) { fallback.incrementAndGet(); client }
        val callback=object : Callback {
            override fun onFailure(call: Call,e: IOException) { done.countDown() }
            override fun onResponse(call: Call,response: Response) { response.close(); done.countDown() }
        }
        try {
            repeat(2) { owner.newCall(Request.Builder().url("https://example.invalid/media").build()).enqueue(callback) }
            assertTrue(entered.await(2,TimeUnit.SECONDS)); val raw=rawCalls(owner)
            owner.close(); assertTrue(raw.all { it.isCanceled() }); unblock.countDown()
            assertTrue(done.await(3,TimeUnit.SECONDS)); assertEquals(0,fallback.get())
        } finally { unblock.countDown(); owner.close() }
    }
    @Test fun `close cancels body after response callback has returned`() {
        val gate=CountDownLatch(1); val received=CountDownLatch(1); val ended=CountDownLatch(1)
        val response=AtomicReference<Response>(); val failure=AtomicReference<IOException>()
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        val worker=Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout=2000
                    val input=socket.getInputStream().bufferedReader()
                    while(true) { if(input.readLine().isNullOrEmpty()) break }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\nConnection: close\r\n\r\nx".toByteArray()); flush()
                    }
                    gate.await(3,TimeUnit.SECONDS)
                }
            } finally { ended.countDown() }
        }.apply { isDaemon=true; start() }
        val owner=ParallelDiagnosticCalls(1,OkHttpClient()) { error("unused") }
        try {
            owner.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/media").build()).enqueue(object : Callback {
                override fun onFailure(call: Call,e: IOException) { failure.set(e); received.countDown() }
                override fun onResponse(call: Call,value: Response) { response.set(value); received.countDown() }
            })
            assertTrue(received.await(2,TimeUnit.SECONDS)); assertNull(failure.get())
            val body=response.get().body!!; assertEquals('x'.code,body.byteStream().read())
            val dispatcher=owner.javaClass.getDeclaredField("dispatcher").apply { isAccessible=true }.get(owner) as Dispatcher
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(dispatcher.runningCallsCount()>0 && System.nanoTime()<until) Thread.yield()
            assertEquals(0,dispatcher.runningCallsCount())
            val raw=rawCalls(owner); owner.close(); assertTrue(raw.all { it.isCanceled() })
            try { body.byteStream().read(); fail("body must be cancelled") } catch (_: IOException) {}
        } finally {
            owner.close(); response.get()?.close(); gate.countDown(); server.close(); worker.join(3000)
            assertTrue(ended.await(1,TimeUnit.SECONDS))
        }
    }

    private fun localResponse(encoded: Boolean, check: (ParallelDiagnosticCalls, Response?, IOException?, DiagnosticPayloadBudget) -> Unit) {
        val received=CountDownLatch(1);val response=AtomicReference<Response>();val failure=AtomicReference<IOException>()
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        val worker=Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout=2000
                    val input=socket.getInputStream().bufferedReader()
                    var identity=false
                    while(true) {
                        val line=input.readLine()
                        if(line.isNullOrEmpty()) break
                        if(line.equals("Accept-Encoding: identity",true)) identity=true
                    }
                    require(identity)
                    socket.getOutputStream().apply {
                        val encoding=if(encoded) "Content-Encoding: gzip\r\n" else ""
                        write(("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n"+encoding+"Connection: close\r\n\r\n"+"x".repeat(100)).toByteArray());flush()
                    }
                }
            } finally { server.close() }
        }.apply { isDaemon=true;start() }
        val budget=DiagnosticPayloadBudget(10)
        val owner=ParallelDiagnosticCalls(1,OkHttpClient(),budget) { error("unused") }
        try {
            owner.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/media").build()).enqueue(object:Callback {
                override fun onFailure(call:Call,e:IOException) { failure.set(e);received.countDown() }
                override fun onResponse(call:Call,value:Response) { response.set(value);received.countDown() }
            })
            assertTrue(received.await(2,TimeUnit.SECONDS))
            check(owner,response.get(),failure.get(),budget)
        } finally { owner.close();response.get()?.close();server.close();worker.join(3000) }
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        while(!owner.isQuiescent && System.nanoTime()<until) Thread.yield()
        assertTrue(owner.isQuiescent)
    }
    @Test fun `raw body delivery is wrapped by the shared payload cap`() = localResponse(false) { _,response,failure,budget ->
        assertNull(failure)
        assertEquals(10,response!!.body!!.source().read(okio.Buffer(),100))
        try { response.body!!.source().read(okio.Buffer(),100);fail("cap") } catch (_:DiagnosticPayloadBudget.Exhausted) {}
        assertEquals(10,budget.snapshot().bytes)
    }
    @Test fun `encoded responses are unavailable without reading payload`() = localResponse(true) { _,response,failure,budget ->
        assertNull(response);assertNotNull(failure);assertEquals(0,budget.snapshot().bytes)
    }
}
