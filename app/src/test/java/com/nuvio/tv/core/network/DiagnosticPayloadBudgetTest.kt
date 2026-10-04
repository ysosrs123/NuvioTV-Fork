package com.nuvio.tv.core.network

import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class DiagnosticPayloadBudgetTest {
    private fun body(read: (Buffer, Long) -> Long) = object : ResponseBody() {
        private val data = object : Source {
            override fun read(sink: Buffer, byteCount: Long) = read(sink, byteCount)
            override fun close() { }
            override fun timeout() = Timeout.NONE
        }.buffer()
        override fun source() = data
        override fun contentLength() = -1L
        override fun contentType(): MediaType? = null
    }
    @Test fun `ignored range or huge body cannot exceed consumed payload cap`() {
        val budget = DiagnosticPayloadBudget(7)
        val source = budget.wrap(body { sink,n -> sink.write(ByteArray(n.toInt())); n }).source()
        assertEquals(7,source.read(Buffer(),100))
        try { source.read(Buffer(),100); fail() } catch (_: DiagnosticPayloadBudget.Exhausted) { }
        assertEquals(7,budget.snapshot().bytes)
    }
    @Test fun `skip and normal reads share the same cap`() {
        val budget=DiagnosticPayloadBudget(20)
        val wrapped=budget.wrap(body { sink,n -> sink.write(ByteArray(n.toInt())); n })
        wrapped.source().skip(12)
        assertEquals(8,wrapped.source().read(Buffer(),100))
        assertEquals(20,budget.snapshot().bytes)
    }
    @Test fun `partial read refunds unused reservation and eof consumes nothing`() {
        val budget=DiagnosticPayloadBudget(20)
        val data=Buffer().writeUtf8("hello")
        val wrapped=budget.wrap(body { sink,n -> data.read(sink,n) })
        assertEquals(5,wrapped.source().read(Buffer(),100))
        assertEquals(-1,wrapped.source().read(Buffer(),100))
        val rest=budget.wrap(body { sink,n -> sink.write(ByteArray(n.toInt())); n })
        assertEquals(15,rest.source().read(Buffer(),100));assertEquals(20,budget.snapshot().bytes)
    }
    @Test fun `failed reads refund reservations`() {
        val budget=DiagnosticPayloadBudget(10)
        try { budget.wrap(body { _,_ -> throw IOException("fail") }).source().read(Buffer(),10);fail() } catch (_:IOException) {}
        assertEquals(10,budget.wrap(body { s,n -> s.write(ByteArray(n.toInt()));n }).source().read(Buffer(),10))
    }
    @Test fun `concurrent responses cannot reserve more than shared cap`() {
        val budget=DiagnosticPayloadBudget(10)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
        val consumed=AtomicLong()
        val first=budget.wrap(body { s,n -> entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));s.write(ByteArray(n.toInt()));n })
        val worker=Thread { try { consumed.set(first.source().read(Buffer(),10)) } finally { done.countDown() } }.apply { isDaemon=true;start() }
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            try { budget.wrap(body { _,_ -> error("No remaining reservation") }).source().read(Buffer(),10);fail() }
            catch (_:DiagnosticPayloadBudget.Exhausted) {}
        } finally { release.countDown();assertTrue(done.await(2,TimeUnit.SECONDS));worker.join(100) }
        assertEquals(10,consumed.get());assertEquals(10,budget.snapshot().bytes)
    }
    @Test fun `stop denies new reads while reserved in flight reads remain bounded`() {
        val budget=DiagnosticPayloadBudget(10);budget.stop()
        try { budget.wrap(body { _,_ -> error("must not read") }).source().read(Buffer(),1);fail() } catch (_:IOException) {}
        assertEquals(0,budget.snapshot().bytes)
    }
    @Test fun `snapshots use the supplied monotonic clock`() {
        var nanos=123L;val budget=DiagnosticPayloadBudget(1) { nanos }
        assertEquals(123,budget.snapshot().nanos);nanos=456
        assertEquals(456,budget.snapshot().nanos)
    }
}
