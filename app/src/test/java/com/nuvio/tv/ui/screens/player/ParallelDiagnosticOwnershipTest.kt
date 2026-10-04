package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ParallelDiagnosticOwnershipTest {
    private val opened = mutableListOf<ParallelRangeDataSource>()
    private lateinit var uri: Uri
    @Before fun setup() {
        mockkStatic(SystemClock::class)
        every { SystemClock.uptimeMillis() } returns 100_000L
        uri = mockk(relaxed = true)
        every { uri.toString() } returns "https://example.invalid/media"
        every { uri.getQueryParameter(any()) } returns null
        ParallelRangeDataSource.releaseRetainedSession()
        ParallelRangeDataSource.hudClampLatched = false
        ParallelRangeDataSource.hudClampTrips = 0
    }
    @After fun cleanup() {
        opened.forEach { it.close() }
        ParallelRangeDataSource.releaseRetainedSession()
        ParallelRangeDataSource.hudClampLatched = false
        ParallelRangeDataSource.hudClampTrips = 0
        unmockkAll()
    }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun set(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target,value)
    private fun call(target: Any, name: String, vararg args: Any?): Any? = target.javaClass.declaredMethods.single {
        it.name == name && it.parameterCount == args.size
    }.apply { isAccessible = true }.invoke(target,*args)
    private fun owner(source: ParallelRangeDataSource) = field(source,"resources")!!
    private fun source(isolated: Boolean, factory: OkHttpDataSource.Factory = mockk(relaxed = true), workers: Int = 2) =
        ParallelRangeDataSource(factory, parallelConnections=workers, chunkSize=4096, isolateSession=isolated)
            .also { opened.add(it) }
    private fun session(source: ParallelRangeDataSource, pending: Boolean = false): Any =
        call(owner(source),if(pending) "obtainPendingSession" else "obtainSession",uri,emptyMap<String,String>(),4096L,4,4,3)!!
    @Suppress("UNCHECKED_CAST") private fun futures(session: Any) = field(session,"futures") as ConcurrentHashMap<Long,CompletableFuture<Any>>
    private fun abandoned(session: Any) = (field(session,"abandoned") as AtomicBoolean).get()
    private fun failedFactory(): OkHttpDataSource.Factory {
        val factory=mockk<OkHttpDataSource.Factory>(); val upstream=mockk<OkHttpDataSource>(relaxed=true)
        every { factory.createDataSource() } returns upstream
        every { upstream.open(any()) } throws IOException("synthetic open failure")
        return factory
    }
    private fun buffer(): Any = Class.forName("com.nuvio.tv.ui.screens.player.ParallelRangeDataSource\$PooledBuffer")
        .declaredConstructors.single().apply { isAccessible=true }.newInstance(null,ByteBuffer.allocate(4096))
    private fun release(source: ParallelRangeDataSource, buffer: Any) { call(owner(source),"releaseSessionBuffer",buffer,4096L,4) }
    private fun pooled(source: ParallelRangeDataSource): Int = (field(owner(source),"globalBufferPool") as Map<*,*>).values.sumOf { (it as Collection<*>).size }

    @Test fun `failed diagnostic leaves playback current and pending futures untouched`() {
        val playback=source(false); val current=session(playback); val future=CompletableFuture<Any>()
        futures(current)[0L]=future
        // A distinct URI permits a pending session alongside the current playback one.
        val pendingUri=mockk<Uri>(relaxed=true)
        val pending=call(owner(playback),"obtainPendingSession",pendingUri,emptyMap<String,String>(),4096L,4,4,3)!!
        val pendingFuture=CompletableFuture<Any>(); futures(pending)[0L]=pendingFuture
        val diagnostic=source(true,failedFactory())
        try { diagnostic.open(DataSpec(uri)); fail("open must fail") } catch (_: IOException) {}
        diagnostic.close()
        assertFalse(abandoned(current)); assertFalse(abandoned(pending))
        assertFalse(future.isCancelled); assertFalse(pendingFuture.isCancelled)
        assertSame(future,futures(current)[0L]); assertSame(pendingFuture,futures(pending)[0L])
    }
    @Test fun `same URL diagnostic cannot adopt warm playback session`() {
        val playback=source(false); val retained=session(playback)
        set(retained,"totalLength",8192L); set(retained,"resolvedUri",uri)
        val factory=failedFactory(); val diagnostic=source(true,factory)
        try { diagnostic.open(DataSpec(uri)); fail("must perform its own open") } catch (_: IOException) {}
        verify(exactly=1) { factory.createDataSource() }
        assertFalse(abandoned(retained))
    }
    @Test fun `ordinary close retains playback chunks for the next reader`() {
        val first=source(false); val retained=session(first); val future=CompletableFuture<Any>()
        futures(retained)[0L]=future; set(retained,"totalLength",8192L); set(retained,"resolvedUri",uri)
        first.close()
        val factory=mockk<OkHttpDataSource.Factory>(); val second=source(false,factory)
        assertEquals(8192L,second.open(DataSpec(uri)))
        assertFalse(abandoned(retained)); assertSame(future,futures(retained)[0L])
        verify { factory wasNot Called }
    }
    @Test fun `diagnostic close cancels its session and drains only its pool`() {
        val playback=source(false); val diagnostic=source(true)
        release(playback,buffer()); release(diagnostic,buffer())
        val owned=session(diagnostic); val future=CompletableFuture<Any>(); futures(owned)[0L]=future
        diagnostic.close(); diagnostic.close()
        assertTrue(abandoned(owned)); assertTrue(future.isCancelled); assertTrue(futures(owned).isEmpty())
        assertEquals(0,pooled(diagnostic)); assertEquals(1,pooled(playback))
    }
    @Test fun `late cancelled worker cannot repopulate a closed diagnostic pool`() {
        val playback=source(false); release(playback,buffer())
        val diagnostic=source(true); val held=buffer()
        val started=CountDownLatch(1); val proceed=CountDownLatch(1); val ended=CountDownLatch(1)
        val executor=call(owner(diagnostic),"getSharedExecutor") as ExecutorService
        executor.execute {
            started.countDown()
            try { while(proceed.count>0) { try { proceed.await() } catch (_: InterruptedException) {} } }
            finally { release(diagnostic,held); ended.countDown() }
        }
        try { assertTrue(started.await(2,TimeUnit.SECONDS)); diagnostic.close() } finally { proceed.countDown() }
        assertTrue(ended.await(2,TimeUnit.SECONDS)); assertEquals(0,pooled(diagnostic)); assertEquals(1,pooled(playback))
        assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS))
    }
    @Test fun `diagnostic rate limit leaves playback HUD unchanged`() {
        ParallelRangeDataSource.hudClampTrips=7
        val diagnostic=source(true); val own=session(diagnostic)
        call(own,"beginRateLimitEpisode",3)
        assertEquals(1,diagnostic.diagnosticClampTrips)
        assertEquals(7,ParallelRangeDataSource.hudClampTrips); assertFalse(ParallelRangeDataSource.hudClampLatched)
    }
    @Test fun `diagnostic clamp suppresses escalation using its private state`() {
        val diagnostic=source(true); val own=session(diagnostic); val future=CompletableFuture<Any>()
        futures(own)[0L]=future; call(own,"beginRateLimitEpisode",3)
        call(diagnostic,"escalateReaderBlockedChunk",own,0L,future,2500L,0)
        assertTrue((field(own,"escalatedChunks") as Set<*>).isEmpty())
        assertFalse(ParallelRangeDataSource.hudClampLatched)
    }
    @Test fun `diagnostic does not consume or publish shared bootstrap data`() {
        mockkObject(PrefetchWindowStore)
        every { PrefetchWindowStore.consumeHead(any(),any()) } throws AssertionError("shared head consumed")
        every { PrefetchWindowStore.peekTail(any(),any(),any()) } throws AssertionError("shared tail read")
        val consume=mockk<(DataSpec)->ParallelRangeDataSource.BootstrapCacheEntry?>()
        val publish=mockk<(ParallelRangeDataSource.BootstrapCacheEntry?)->Unit>()
        val diagnostic=ParallelRangeDataSource(failedFactory(),isolateSession=true,consumeBootstrapCache=consume,updateBootstrapCache=publish).also { opened.add(it) }
        try { diagnostic.open(DataSpec(uri)); fail() } catch (_: IOException) {}
        verify { consume wasNot Called; publish wasNot Called }
        verify(exactly=0) { PrefetchWindowStore.consumeHead(any(),any()); PrefetchWindowStore.peekTail(any(),any(),any()) }
    }
    @Test fun `closed diagnostic refuses reopen before creating network work`() {
        val factory=mockk<OkHttpDataSource.Factory>(); val diagnostic=source(true,factory); diagnostic.close()
        try { diagnostic.open(DataSpec(uri)); fail() } catch (_: IOException) {}
        verify { factory wasNot Called }
        try { call(owner(diagnostic),"getSharedExecutor"); fail("closed owner must not create workers") }
        catch (e: java.lang.reflect.InvocationTargetException) { assertTrue(e.cause is IllegalStateException) }
        assertFalse((field(owner(diagnostic),"executorDelegate") as Lazy<*>).isInitialized())
    }
    @Test fun `diagnostic workers run concurrently within their private limit`() {
        val diagnostic=source(true,workers=3); val executor=call(owner(diagnostic),"getSharedExecutor") as ExecutorService
        val started=CountDownLatch(3); val gate=CountDownLatch(1)
        repeat(3) { executor.execute { started.countDown(); try { gate.await() } catch (_: InterruptedException) {} } }
        try { assertTrue(started.await(2,TimeUnit.SECONDS)) } finally { gate.countDown(); diagnostic.close() }
        assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS))
    }
}
