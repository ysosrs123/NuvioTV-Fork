package com.nuvio.tv.core.network

import kotlinx.coroutines.*
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class BoundedParallelSpeedTestTest {
    private fun limits(totalMs: Long = 1000, minimumMs: Long = 0) = BoundedParallelSpeedTest.Limits(
        totalBytes=100,warmupBytes=10,warmupMs=100,measuredMs=500,minimumMeasuredMs=minimumMs,sampleMs=10,totalMs=totalMs)
    private open class Fake(val budget: DiagnosticPayloadBudget, val clock: AtomicLong) : BoundedParallelSpeedTest.Session {
        val cancelled=AtomicInteger();val closed=AtomicBoolean();val quiet=AtomicBoolean(true)
        private val body = object : ResponseBody() {
            private val source = object : Source {
                override fun timeout()=Timeout.NONE
                override fun close() {}
                override fun read(sink: Buffer,byteCount: Long): Long {
                    val count=minOf(byteCount,10);sink.write(ByteArray(count.toInt()));clock.addAndGet(100_000_000);return count
                }
            }.buffer()
            override fun source()=source
            override fun contentLength()=-1L
            override fun contentType(): MediaType?=null
        }
        private val payload=budget.wrap(body).source()
        override fun open() {}
        override fun read(buffer: ByteArray): Int = payload.read(Buffer(),10).toInt()
        override val clampTrips get()=0
        override fun cancel() { cancelled.incrementAndGet() }
        override fun close() { closed.set(true) }
        override val isQuiescent get()=closed.get() && quiet.get()
    }
    private fun awaitUninterruptibly(latch: CountDownLatch) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
        while (latch.count>0 && System.nanoTime()<deadline) try { latch.await(20,TimeUnit.MILLISECONDS) } catch (_:InterruptedException) {}
        check(latch.count==0L) { "fixture was not released" }
    }
    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(2500) { while (!condition()) delay(5) }
    }
    @Test fun `warmup excluded and monotonic payload delta is measured`() = runBlocking {
        val clock=AtomicLong();val runner=BoundedParallelSpeedTest(DiagnosticPlaybackGuard(),limits(),nanoTime=clock::get)
        lateinit var fake:Fake
        val result=runner.run { Fake(it,clock).also { fake=it } }
        assertNull(result.failure);assertEquals(10,result.warmupBytes)
        assertEquals(50,result.measuredBytes);assertEquals(500_000_000,result.measuredNanos)
        assertEquals(0.0008,result.mbps!!,0.0000001);assertEquals(60,result.totalBytes)
        assertTrue(fake.closed.get());assertTrue(fake.cancelled.get()>0)
    }
    @Test fun `caller timeout returns before blocked open and admission stays occupied`() = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val clock=AtomicLong()
        val runner=BoundedParallelSpeedTest(DiagnosticPlaybackGuard(),limits(totalMs=500),nanoTime=clock::get)
        lateinit var fake:Fake
        try {
            val task=async { runner.run { object:Fake(it,clock) {
                override fun open() { entered.countDown();awaitUninterruptibly(release) }
            }.also { fake=it } } }
            eventually { entered.count==0L }
            val result=withTimeout(1500) { task.await() }
            assertEquals("Parallel test timed out",result.failure)
            eventually { fake.cancelled.get()>0 }
            assertFalse(fake.closed.get())
            assertTrue(runner.run { error("busy must not create") }.failure!!.contains("finishing"))
        } finally { release.countDown() }
        eventually { fake.closed.get() }
    }
    @Test fun `cancellation promptly propagates and never closes buffer during read`() = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val clock=AtomicLong()
        val runner=BoundedParallelSpeedTest(DiagnosticPlaybackGuard(),limits(),nanoTime=clock::get)
        lateinit var fake:Fake
        val task=async { runner.run { object:Fake(it,clock) {
            override fun read(buffer:ByteArray):Int { entered.countDown();awaitUninterruptibly(release);return -1 }
        }.also { fake=it } } }
        try {
            eventually { entered.count==0L };task.cancel();withTimeout(300) { task.join() }
            eventually { fake.cancelled.get()>0 };assertFalse(fake.closed.get())
            assertTrue(runner.run { error("busy") }.failure!!.contains("finishing"))
        } finally { release.countDown() }
        eventually { fake.closed.get() }
    }
    @Test fun `worker quiescence holds admission after reader cleanup`() = runBlocking {
        val clock=AtomicLong();val runner=BoundedParallelSpeedTest(DiagnosticPlaybackGuard(),limits(),nanoTime=clock::get)
        val ref=AtomicReference<Fake>()
        val task=async { runner.run { Fake(it,clock).also { f -> ref.set(f);f.quiet.set(false) } } }
        try {
            eventually { ref.get()?.closed?.get()==true }
            assertFalse(task.isCompleted)
            assertTrue(runner.run { error("busy") }.failure!!.contains("finishing"))
            ref.get().quiet.set(true);assertNull(task.await().failure)
            assertNull(runner.run { Fake(it,clock) }.failure)
        } finally { ref.get()?.quiet?.set(true) }
    }
    @Test fun `active playback prevents opening diagnostic`() = runBlocking {
        val guard=DiagnosticPlaybackGuard();val lease=guard.enterPlayback()
        try { assertEquals("Playback is active",BoundedParallelSpeedTest(guard,limits()).run { error("must not create") }.failure) }
        finally { lease.close() }
    }
    @Test fun `playback starts cancel the cell`() = runBlocking {
        val guard=DiagnosticPlaybackGuard();val clock=AtomicLong();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val runner=BoundedParallelSpeedTest(guard,limits(),nanoTime=clock::get)
        val task=async { runner.run { object:Fake(it,clock) { override fun open() { entered.countDown();awaitUninterruptibly(release) } } } }
        try {
            eventually { entered.count==0L };guard.enterPlayback().use { withTimeout(300) { task.join() };assertTrue(task.isCancelled) }
        } finally { release.countDown() }
    }
    @Test fun `cancel during creation cleans late session without opening`() = runBlocking {
        val clock=AtomicLong();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val runner=BoundedParallelSpeedTest(DiagnosticPlaybackGuard(),limits(),nanoTime=clock::get)
        val opened=AtomicBoolean();val done=AtomicBoolean()
        val task=async { runner.run {
            entered.countDown();awaitUninterruptibly(release)
            object:Fake(it,clock) { override fun open() { opened.set(true) };override fun close() { super.close();done.set(true) } }
        } }
        try { eventually { entered.count==0L };task.cancel();withTimeout(300) { task.join() } }
        finally { release.countDown() }
        eventually { done.get() };assertFalse(opened.get())
    }
    @Test fun `failed open returns unavailable and closes its session`() = runBlocking {
        val clock=AtomicLong();val runner=BoundedParallelSpeedTest(DiagnosticPlaybackGuard(),limits(),nanoTime=clock::get)
        lateinit var fake:Fake
        val result=runner.run { object:Fake(it,clock) { override fun open() { throw IOException("source failed") } }.also { fake=it } }
        assertNull(result.mbps);assertEquals("IOException",result.failure);assertTrue(fake.closed.get())
    }
    @Test fun `early eof or short measured interval never produces successful zero`() = runBlocking {
        val clock=AtomicLong();val runner=BoundedParallelSpeedTest(DiagnosticPlaybackGuard(),limits(minimumMs=500),nanoTime=clock::get)
        val result=runner.run { object:Fake(it,clock) { override fun read(buffer:ByteArray)=-1 } }
        assertNull(result.mbps);assertNotNull(result.failure)
    }
    @Test fun `independent sampler runs while foreground reader is blocked`() = runBlocking {
        val clock=AtomicLong();val runner=BoundedParallelSpeedTest(DiagnosticPlaybackGuard(),limits(),nanoTime=clock::get)
        val result=runner.run { object:Fake(it,clock) {
            var reads=0
            override fun read(buffer:ByteArray):Int {
                val result=super.read(buffer)
                if (++reads>1) Thread.sleep(30)
                return result
            }
        } }
        assertNull(result.failure);assertTrue(result.samples.size>=3)
    }
    @Test fun `consumed payload ceiling ends the cell and marks the sample`() = runBlocking {
        val clock=AtomicLong();val runner=BoundedParallelSpeedTest(DiagnosticPlaybackGuard(),limits().copy(measuredMs=1000),nanoTime=clock::get)
        val result=runner.run { Fake(it,clock) }
        assertNull(result.failure);assertEquals(100,result.totalBytes);assertTrue(result.payloadLimited)
    }
    @Test fun `ordinary failures do not become rate limited retries`() {
        assertNull(StreamSpeedTester.ParallelPassResult(0.0,emptyList(),failureReason="Timed out").retryReason(1.0))
        assertNull(StreamSpeedTester.ParallelPassResult(20.0,emptyList(),failureReason="Failed",clampTrips=1).retryReason(1.0))
        assertEquals("rate-limited",StreamSpeedTester.ParallelPassResult(20.0,emptyList(),clampTrips=1).retryReason(1.0))
        assertEquals("no usable transfer",StreamSpeedTester.ParallelPassResult(0.1,emptyList()).retryReason(1.0))
        assertNull(StreamSpeedTester.ParallelPassResult(20.0,emptyList()).retryReason(1.0))
    }

    @Test fun `failed cleanup returns unavailable and retains admission`() = runBlocking {
        val clock=AtomicLong();val runner=BoundedParallelSpeedTest(DiagnosticPlaybackGuard(),limits(),nanoTime=clock::get)
        val result=runner.run { object:Fake(it,clock) {
            override fun close() { super.close();throw IOException("synthetic cleanup failure") }
        } }
        assertNull(result.mbps);assertEquals("Test cleanup failed",result.failure)
        assertTrue(runner.run { error("uncertain cleanup must retain admission") }.failure!!.contains("finishing"))
    }
    @Test fun `resource errors at payload limit cannot become successful samples`() = runBlocking {
        val clock=AtomicLong();val runner=BoundedParallelSpeedTest(DiagnosticPlaybackGuard(),limits().copy(measuredMs=1000),nanoTime=clock::get)
        val result=runner.run { object:Fake(it,clock) {
            override fun read(buffer:ByteArray):Int {
                val read=super.read(buffer)
                if (budget.snapshot().bytes==100L) throw OutOfMemoryError("synthetic")
                return read
            }
        } }
        assertNull(result.mbps);assertEquals("OutOfMemoryError",result.failure)
    }
}
