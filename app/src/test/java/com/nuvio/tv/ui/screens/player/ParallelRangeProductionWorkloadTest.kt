package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.NuvioEngineConfig
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Actual production session/task/pool ownership, controlled local transport.
 * Private normal Resources isolates the JVM test owner; production policy is
 * unchanged. Heap segments avoid claiming any JNI/ART allocation acceptance.
 */
class ParallelRangeProductionWorkloadTest {
    private val manualClock=AtomicLong(-1)
    private val readerClockCalls=AtomicInteger()
    @Before fun start() {
        NuvioEngineConfig.set(NuvioEngineConfig.stockMode())
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { clock() }
        every { SystemClock.uptimeMillis() } answers { clock() }
    }
    @After fun finish() { unmockkStatic(SystemClock::class);NuvioEngineConfig.set(NuvioEngineConfig.stockMode()) }
    private fun clock():Long {
        if(Thread.currentThread().name=="owned-workload-reader") readerClockCalls.incrementAndGet()
        return manualClock.get().takeIf { it>=0 } ?: TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
    }
    private fun field(owner:Any,name:String):Any?=owner.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(owner)
    private fun invoke(owner:Any,name:String,vararg args:Any?):Any? {
        val m=owner.javaClass.declaredMethods.single { it.name.substringBefore('$')==name && it.parameterCount==args.size }
        m.isAccessible=true;return m.invoke(owner,*args)
    }
    private class Hold(val ignoreClose:Boolean=false) {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
    }
    private inner class Fixture : AutoCloseable {
        val total=64L*CHUNK
        val resourceClass=ParallelRangeDataSource::class.java.declaredClasses.single { it.simpleName=="Resources" }
        val resources=resourceClass.declaredConstructors.single { it.parameterCount==2 }.apply { isAccessible=true }.newInstance(false,3)
        val pool=invoke(resources,"getSharedExecutor") as ThreadPoolExecutor
        val readers=Executors.newCachedThreadPool { r -> Thread(r,"owned-workload-reader").apply { isDaemon=true } }
        val holds=ConcurrentHashMap<Long,Hold>()
        val openHolds=ConcurrentHashMap<Long,Hold>()
        val bodyAttempts=ConcurrentHashMap<Long,AtomicInteger>()
        val workerOpens=CopyOnWriteArrayList<Pair<String,Long>>()
        val sources=mutableListOf<ParallelRangeDataSource>()
        val allHolds=mutableListOf<Hold>()
        val openAttempts=ConcurrentHashMap<Long,AtomicInteger>()
        var beforeWorkerOpen: ((Long,Int,DataSpec)->Unit)?=null
        var beforeBody: ((Long,Int)->Unit)?=null
        fun uri(label:String="normal",subtitle:Boolean=false):Uri {
            val u=mockk<Uri>(relaxed=true)
            every { u.toString() } returns "https://example.invalid/workload/$label"
            every { u.path } returns "/workload/$label"
            every { u.host } returns "example.invalid"
            every { u.getQueryParameter(any()) } returns null
            if(subtitle) {
                val clean=uri(label);val builder=mockk<Uri.Builder>()
                every { u.getQueryParameter("nuvio_type") } returns "subtitle"
                every { u.queryParameterNames } returns setOf("nuvio_type")
                every { u.buildUpon() } returns builder
                every { builder.clearQuery() } returns builder
                every { builder.build() } returns clean
            }
            return u
        }
        val normalUri=uri()
        val factory=mockk<OkHttpDataSource.Factory>()
        init {
            every { factory.createDataSource() } answers {
                val ds=mockk<OkHttpDataSource>(relaxed=true)
                var spec:DataSpec?=null;var cursor=0L;var end=0L;var closed=false
                var bodyHold:Hold?=null;var bodyStarted=false
                every { ds.open(any()) } answers {
                    spec=firstArg();cursor=spec!!.position
                    end=if(spec!!.length==C.LENGTH_UNSET.toLong()) total else minOf(total,cursor+spec!!.length)
                    closed=false;bodyStarted=false;bodyHold=null
                    if(Thread.currentThread().name=="parallel-ds-worker") {
                        workerOpens+=spec!!.uri.toString() to cursor
                        val openAttempt=openAttempts.computeIfAbsent(spec!!.position/CHUNK) { AtomicInteger() }.incrementAndGet()
                        openHolds.remove(spec!!.position/CHUNK)?.let { hold ->
                            hold.entered.countDown()
                            if(!hold.release.await(10,TimeUnit.SECONDS)) throw IOException("controlled open hold expired")
                        }
                        beforeWorkerOpen?.invoke(spec!!.position/CHUNK,openAttempt,spec!!)
                    }
                    end-cursor
                }
                every { ds.uri } answers { spec?.uri ?: normalUri }
                every { ds.responseCode } returns 206
                every { ds.responseHeaders } answers { mapOf("Content-Range" to listOf("bytes ${spec!!.position}-${end-1}/$total")) }
                every { ds.read(any<ByteArray>(),any(),any()) } answers {
                    val worker=Thread.currentThread().name=="parallel-ds-worker"
                    if(worker && !bodyStarted) {
                        bodyStarted=true;val chunk=spec!!.position/CHUNK
                        val attempt=bodyAttempts.computeIfAbsent(chunk) { AtomicInteger() }.incrementAndGet()
                        beforeBody?.invoke(chunk,attempt)
                        bodyHold=holds.remove(chunk)
                        bodyHold?.let { hold ->
                            hold.entered.countDown()
                            if(!hold.release.await(10,TimeUnit.SECONDS)) throw IOException("controlled transport hold expired")
                        }
                    }
                    if(closed && bodyHold?.ignoreClose!=true) throw IOException("controlled response closed")
                    if(cursor>=end) C.RESULT_END_OF_INPUT else {
                        val count=minOf(thirdArg<Int>().toLong(),end-cursor).toInt()
                        val target=firstArg<ByteArray>();val off=secondArg<Int>()
                        val salt=if(spec!!.uri.toString().endsWith("fresh")) 17 else 0
                        for(i in 0 until count) target[off+i]=((cursor+i+salt)%127).toByte()
                        cursor+=count;count
                    }
                }
                every { ds.close() } answers { closed=true;if(bodyHold?.ignoreClose!=true) bodyHold?.release?.countDown();Unit }
                ds
            }
        }
        fun source():ParallelRangeDataSource {
            val ds=ParallelRangeDataSource(factory,parallelConnections=3,chunkSize=CHUNK,
                useNativeMemory=false,shouldAllowBackgroundPrefetch={ false },allowContinuationReopen=false)
            // Return the constructor's actual companion reservation before
            // substituting a test-owned normal Resources. Open reacquires its
            // normal reservation; no live/global executor is stopped or resized.
            ds.close()
            ds.javaClass.getDeclaredField("resources").apply { isAccessible=true }.set(ds,resources)
            sources+=ds;return ds
        }
        fun bootstrap(ds:ParallelRangeDataSource) { assertEquals(total,ds.open(spec(0)));ds.close() }
        fun spec(chunk:Long,requestUri:Uri=normalUri)=DataSpec.Builder().setUri(requestUri).setPosition(chunk*CHUNK).build()
        fun hold(chunk:Long,ignoreClose:Boolean=false)=Hold(ignoreClose).also { holds[chunk]=it;allHolds+=it }
        fun holdOpen(chunk:Long)=Hold().also { openHolds[chunk]=it;allHolds+=it }
        fun session(ds:ParallelRangeDataSource)=field(ds,"session")!!
        @Suppress("UNCHECKED_CAST") fun futures(session:Any)=field(session,"futures") as ConcurrentHashMap<Long,CompletableFuture<*>>
        fun readAsync(ds:ParallelRangeDataSource)=readers.submit<Byte> {
            val bytes=ByteArray(1);assertEquals(1,ds.read(bytes,0,1));bytes[0]
        }
        fun waitDone(ds:ParallelRangeDataSource,chunk:Long) {
            val future=futures(session(ds))[chunk];assertNotNull("real chunk future admitted",future)
            future!!.get(5,TimeUnit.SECONDS)
        }
        fun counts()=(field(resources,"activeInstances") as AtomicInteger).get()
        override fun close() {
            allHolds.forEach { it.release.countDown() }
            sources.forEach { it.close() }
            invoke(resources,"releaseRetainedSession")
            readers.shutdownNow();assertTrue(readers.awaitTermination(5,TimeUnit.SECONDS))
            pool.shutdown();assertTrue("only test-owned workers must drain",pool.awaitTermination(5,TimeUnit.SECONDS))
            invoke(resources,"clearGlobalPool")
            assertEquals(0,counts())
        }
    }
    private fun awaitCondition(message:String,fact:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!fact() && System.nanoTime()<end) Thread.sleep(2)
        assertTrue(message,fact())
    }
    @Test fun `three connection playback repeated seeks actually creates thirty two shared workers`() {
        Fixture().use { f ->
            val ds=f.source();f.bootstrap(ds)
            for(chunk in 1L..36L) {
                assertEquals(f.total-chunk*CHUNK,ds.open(f.spec(chunk)))
                assertEquals(((chunk*CHUNK)%127).toByte(),f.readAsync(ds).get(5,TimeUnit.SECONDS))
                f.waitDone(ds,chunk);ds.close()
            }
            assertEquals(36,f.workerOpens.size)
            assertEquals(32,f.pool.largestPoolSize)
            assertEquals(32,f.pool.corePoolSize);assertEquals(64,f.pool.maximumPoolSize)
            assertTrue(f.pool.queue.isEmpty())
            val live=Thread.getAllStackTraces().keys.count { it.isAlive && it.name=="parallel-ds-worker" }
            assertEquals(32,live)
            println("WORKLOAD worker-creation connections=3 seeks=36 actualTasks=36 peakWorkers=${f.pool.largestPoolSize} liveWorkers=$live")
        }
    }
    @Test fun `closed reader reopens onto the same pending real chunk future`() {
        manualClock.set(1000)
        Fixture().use { f ->
            val ds=f.source();f.bootstrap(ds);val hold=f.hold(1)
            ds.open(f.spec(1));val oldSession=f.session(ds)
            invoke(ds,"ensureChunkScheduled",1L)
            assertTrue(hold.entered.await(5,TimeUnit.SECONDS))
            val future=f.futures(oldSession).getValue(1);ds.close()
            ds.open(f.spec(1));assertSame(oldSession,f.session(ds));assertSame(future,f.futures(f.session(ds))[1])
            val read=f.readAsync(ds);hold.release.countDown();assertEquals(((CHUNK)%127).toByte(),read.get(5,TimeUnit.SECONDS))
            future.get(5,TimeUnit.SECONDS);assertEquals(1,f.workerOpens.size)
        }
    }
    @Test fun `completed real chunks survive repeated ordinary seek reopen without new requests`() {
        Fixture().use { f ->
            val ds=f.source();f.bootstrap(ds);ds.open(f.spec(2))
            val expected=((2*CHUNK)%127).toByte();assertEquals(expected,f.readAsync(ds).get(5,TimeUnit.SECONDS));f.waitDone(ds,2)
            val requests=f.workerOpens.size
            repeat(4) { ds.close();ds.open(f.spec(2));assertEquals(expected,f.readAsync(ds).get(5,TimeUnit.SECONDS)) }
            assertEquals(requests,f.workerOpens.size)
        }
    }
    @Test fun `ordinary peers share pending task while a subtitle closes its own reservation`() {
        manualClock.set(1000)
        Fixture().use { f ->
            val first=f.source();f.bootstrap(first);val hold=f.hold(1)
            first.open(f.spec(1));invoke(first,"ensureChunkScheduled",1L)
            assertTrue(hold.entered.await(5,TimeUnit.SECONDS));val future=f.futures(f.session(first)).getValue(1)
            val second=f.source();second.open(f.spec(1));assertSame(future,f.futures(f.session(second))[1])
            val subtitle=f.source();subtitle.open(f.spec(0,f.uri("subtitle",true)))
            assertEquals(3,f.counts());val small=ByteArray(2);assertEquals(2,subtitle.read(small,0,2));subtitle.close()
            assertEquals(2,f.counts());assertFalse(future.isCancelled)
            val a=f.readAsync(first);val b=f.readAsync(second);hold.release.countDown()
            assertEquals(((CHUNK)%127).toByte(),a.get(5,TimeUnit.SECONDS));assertEquals(((CHUNK)%127).toByte(),b.get(5,TimeUnit.SECONDS))
            future.get(5,TimeUnit.SECONDS);assertEquals(1,f.workerOpens.size)
        }
    }
    @Test fun `actual reader blocked escalation races into its original future`() {
        manualClock.set(1000)
        Fixture().use { f ->
            val ds=f.source();f.bootstrap(ds);val hold=f.hold(1)
            ds.open(f.spec(1));invoke(ds,"ensureChunkScheduled",1L)
            assertTrue(hold.entered.await(5,TimeUnit.SECONDS))
            val future=f.futures(f.session(ds)).getValue(1);val read=f.readAsync(ds)
            awaitCondition("reader has sampled registered zero watermark") { readerClockCalls.get()>=6 }
            manualClock.set(3500)
            assertEquals(((CHUNK)%127).toByte(),read.get(5,TimeUnit.SECONDS))
            future.get(5,TimeUnit.SECONDS);assertEquals(2,f.workerOpens.size)
            hold.release.countDown();awaitCondition("losing original body drains") { f.pool.activeCount==0 }
            assertSame(future,f.futures(f.session(ds))[1])
            assertEquals("winning hedge must not trigger a losing original restart",2,f.workerOpens.size)
        }
    }
    @Test fun `reader starting before in flight registration still escalates a stationary body`() {
        manualClock.set(1000)
        Fixture().use { f ->
            val ds=f.source();f.bootstrap(ds);val open=f.holdOpen(1);val body=f.hold(1)
            ds.open(f.spec(1));val read=f.readAsync(ds)
            assertTrue(open.entered.await(5,TimeUnit.SECONDS))
            awaitCondition("reader sampled absent in-flight registration") { readerClockCalls.get()>=6 }
            open.release.countDown();assertTrue(body.entered.await(5,TimeUnit.SECONDS))
            manualClock.set(3500)
            awaitCondition("registered stationary body must admit a reader hedge") { f.workerOpens.size>=2 }
            assertEquals(((CHUNK)%127).toByte(),read.get(5,TimeUnit.SECONDS))
        }
    }
    @Test fun `saturated actual chunk tasks queue reader and hedge until a worker retires`() {
        manualClock.set(1000)
        Fixture().use { f ->
            val ds=f.source();f.bootstrap(ds);ds.open(f.spec(33))
            // The real session cap may cancel an older future before it starts.
            // Admit each controlled body before the next so cancelled/blocked
            // transports actually occupy the normal worker, not an imagined slot.
            val holds=(1L..32L).map { chunk ->
                f.hold(chunk).also { hold ->
                    invoke(ds,"ensureChunkScheduled",chunk)
                    assertTrue("actual body $chunk entered",hold.entered.await(5,TimeUnit.SECONDS))
                }
            }
            val read=f.readAsync(ds)
            awaitCondition("real current chunk queued") { f.futures(f.session(ds)).containsKey(33) && f.pool.queue.size>=1 }
            awaitCondition("queued reader sampled absent watermark") { readerClockCalls.get()>=6 }
            manualClock.set(3500)
            awaitCondition("normal task plus reader escalation both queued") { f.pool.queue.size>=2 }
            assertEquals(32,f.pool.largestPoolSize);assertFalse(read.isDone)
            holds.first().release.countDown()
            assertEquals(((33*CHUNK)%127).toByte(),read.get(5,TimeUnit.SECONDS))
            f.waitDone(ds,33);holds.forEach { it.release.countDown() }
            awaitCondition("all actual tasks and hedge settle") { f.pool.activeCount==0 && f.pool.queue.isEmpty() }
            println("WORKLOAD saturation workers=${f.pool.largestPoolSize} connections=3 internalActualChunkAdmission=32 queuedReaderAndHedge=true")
        }
    }
    @Test fun `abandoned noncooperative real body cannot publish into a fresh session`() {
        manualClock.set(1000)
        Fixture().use { f ->
            val old=f.source();f.bootstrap(old);val hold=f.hold(1,true)
            old.open(f.spec(1));invoke(old,"ensureChunkScheduled",1L)
            assertTrue(hold.entered.await(5,TimeUnit.SECONDS))
            val oldSession=f.session(old);val oldFuture=f.futures(oldSession).getValue(1)
            old.close();invoke(f.resources,"releaseRetainedSession");assertTrue(oldFuture.isCancelled)
            val freshUri=f.uri("fresh")
            val fresh=f.source();fresh.open(f.spec(0,freshUri));fresh.close()
            fresh.open(f.spec(1,freshUri))
            val freshSession=f.session(fresh);assertNotSame(oldSession,freshSession)
            assertEquals(((CHUNK+17)%127).toByte(),f.readAsync(fresh).get(5,TimeUnit.SECONDS));f.waitDone(fresh,1)
            val freshFuture=f.futures(freshSession).getValue(1)
            hold.release.countDown();awaitCondition("late old task drains") { f.pool.activeCount==0 }
            assertTrue(oldFuture.isCancelled);assertTrue(f.futures(oldSession).isEmpty())
            assertSame(freshFuture,f.futures(freshSession)[1]);assertFalse(freshFuture.isCancelled)
            fresh.close();fresh.open(f.spec(1,freshUri))
            assertEquals("late old bytes must not overwrite fresh retained bytes",((CHUNK+17)%127).toByte(),f.readAsync(fresh).get(5,TimeUnit.SECONDS))
        }
    }
    @Test fun `late original rate limit after hedge success cannot start a new clamp or request`() {
        manualClock.set(1000)
        Fixture().use { f ->
            val ds=f.source();f.bootstrap(ds);val open=f.holdOpen(1)
            f.beforeWorkerOpen={ chunk,attempt,spec ->
                if(chunk==1L && attempt==1) throw HttpDataSource.InvalidResponseCodeException(
                    429,"fixture limiter",null,mapOf("Retry-After" to listOf("0")),spec,byteArrayOf())
            }
            ds.open(f.spec(1));val read=f.readAsync(ds)
            assertTrue(open.entered.await(5,TimeUnit.SECONDS))
            awaitCondition("reader sampled absent original registration") { readerClockCalls.get()>=6 }
            manualClock.set(3500)
            assertEquals(((CHUNK)%127).toByte(),read.get(5,TimeUnit.SECONDS));f.waitDone(ds,1)
            assertEquals(2,f.workerOpens.size)
            assertEquals(false,field(f.resources,"hudClampLatched"))
            open.release.countDown();awaitCondition("late original limiter response drains") { f.pool.activeCount==0 }
            assertEquals("completed chunk must not start a stale rate-limit episode",false,field(f.resources,"hudClampLatched"))
            assertEquals("completed chunk must not issue another request",2,f.workerOpens.size)
        }
    }
    @Test fun `ordinary pending chunk retains the real rate limit retry and clamp policy`() {
        Fixture().use { f ->
            val ds=f.source();f.bootstrap(ds)
            f.beforeWorkerOpen={ chunk,attempt,spec ->
                if(chunk==1L && attempt==1) throw HttpDataSource.InvalidResponseCodeException(
                    429,"fixture limiter",null,mapOf("Retry-After" to listOf("0")),spec,byteArrayOf())
            }
            ds.open(f.spec(1))
            assertEquals(((CHUNK)%127).toByte(),f.readAsync(ds).get(5,TimeUnit.SECONDS));f.waitDone(ds,1)
            assertEquals(2,f.workerOpens.size)
            assertEquals(true,field(f.resources,"hudClampLatched"))
        }
    }
    companion object { private const val CHUNK=262144L }
}
