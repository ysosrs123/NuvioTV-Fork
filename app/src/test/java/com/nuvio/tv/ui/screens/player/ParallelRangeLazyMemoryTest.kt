package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.upstream.Allocation
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class ParallelRangeLazyMemoryTest {

    private class Memory {
        val live: MutableSet<Allocation> = ConcurrentHashMap.newKeySet()
        val sizes = CopyOnWriteArrayList<Int>()
        val mostLive = java.util.concurrent.atomic.AtomicInteger()

        fun allocate(size: Int): Allocation = Allocation(ByteBuffer.allocate(size), 0).also {
            sizes.add(size)
            live.add(it)
            mostLive.accumulateAndGet(live.size, ::maxOf)
        }

        fun free(allocation: Allocation) {
            live.remove(allocation)
        }
    }

    private val memory = Memory()
    private val server = MockWebServer()
    private val file = Random(11).nextBytes((24 * CHUNK + CHUNK / 3).toInt())
    private val held = ConcurrentHashMap<Long, CountDownLatch>()
    private val opened = ConcurrentHashMap.newKeySet<ParallelRangeDataSource>()
    private lateinit var allocateBefore: (Int) -> Allocation?
    private lateinit var freeBefore: (Allocation) -> Unit

    private val owned: Any = ParallelRangeDataSource::class.java.declaredClasses.single { it.simpleName == "Resources" }
        .declaredConstructors.single { it.parameterCount == 2 }.apply { isAccessible = true }.newInstance(false, 2)

    @Before
    fun setUp() {
        mockkStatic(Uri::class, SystemClock::class)
        every { Uri.parse(any()) } answers { uriOf(firstArg()) }
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000L }
        every { SystemClock.uptimeMillis() } answers { System.nanoTime() / 1_000_000L }
        allocateBefore = ParallelRangeDataSource.allocateChunk
        freeBefore = ParallelRangeDataSource.freeChunk
        ParallelRangeDataSource.allocateChunk = memory::allocate
        ParallelRangeDataSource.freeChunk = memory::free
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val parts = request.getHeader("Range")!!.removePrefix("bytes=").split("-")
                val from = parts[0].toInt()
                val to = minOf(parts.getOrNull(1)?.toIntOrNull() ?: (file.size - 1), file.size - 1)
                held[(from / CHUNK).toLong()]?.await(10, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $from-$to/${file.size}")
                    .setBody(Buffer().write(file, from, to - from + 1))
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        held.values.forEach { it.countDown() }
        opened.forEach { runCatching { it.close() } }
        call(owned, "releaseRetainedSession")
        call(owned, "clearGlobalPool")
        val executor = field(owned, "executorDelegate") as Lazy<*>
        if (executor.isInitialized()) {
            (executor.value as ThreadPoolExecutor).shutdownNow()
            (executor.value as ThreadPoolExecutor).awaitTermination(5, TimeUnit.SECONDS)
        }
        ParallelRangeDataSource.allocateChunk = allocateBefore
        ParallelRangeDataSource.freeChunk = freeBefore
        server.shutdown()
        unmockkStatic(Uri::class, SystemClock::class)
    }

    private fun uriOf(url: String): Uri = mockk(relaxed = true) {
        every { this@mockk.toString() } returns url
        every { host } returns "127.0.0.1"
        every { path } returns "/file"
        every { getQueryParameter(any()) } returns null
    }

    private val fileUri by lazy { uriOf(server.url("/file").toString()) }

    private fun spec(position: Long) = DataSpec.Builder().setUri(fileUri).setPosition(position).build()

    private fun source(heldLimit: (() -> Long)?): ParallelRangeDataSource {
        val source = ParallelRangeDataSource(
            OkHttpDataSource.Factory(OkHttpClient()),
            parallelConnections = 2,
            chunkSize = CHUNK.toLong(),
            heldLimit = heldLimit
        )
        source.close()
        ParallelRangeDataSource::class.java.getDeclaredField("resources").apply { isAccessible = true }.set(source, owned)
        return source.also { opened.add(it) }
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? = target.javaClass.declaredMethods
        .single { (it.name == name || it.name.startsWith("$name$")) && it.parameterCount == args.size }
        .apply { isAccessible = true }.invoke(target, *args)

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun session(source: ParallelRangeDataSource): Any = field(source, "session")!!

    private fun heldBytes(source: ParallelRangeDataSource): Long = (field(session(source), "heldBytes") as AtomicLong).get()

    @Suppress("UNCHECKED_CAST")
    private fun futures(source: ParallelRangeDataSource) =
        field(session(source), "futures") as ConcurrentHashMap<Long, CompletableFuture<*>>

    private fun readAll(source: ParallelRangeDataSource, viaByteBuffer: Boolean): ByteArray {
        val out = ByteArray(file.size)
        var position = 0
        source.open(spec(0))
        val scratch = ByteArray(70_000)
        val target = ByteBuffer.allocateDirect(70_000)
        while (position < file.size) {
            val read = if (viaByteBuffer) {
                target.clear()
                source.read(target, scratch.size).also { if (it > 0) { target.flip(); target.get(scratch, 0, it) } }
            } else {
                source.read(scratch, 0, scratch.size)
            }
            if (read == C.RESULT_END_OF_INPUT) break
            System.arraycopy(scratch, 0, out, position, read)
            position += read
        }
        source.close()
        return out
    }

    private fun awaitTrue(message: String, fact: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!fact() && System.nanoTime() < end) Thread.sleep(5)
        assertTrue(message, fact())
    }

    @Test
    fun `pieces in parts read back every byte through both read paths and give all memory back`() {
        for (viaByteBuffer in listOf(false, true)) {
            val read = readAll(source { Long.MAX_VALUE }, viaByteBuffer)
            assertTrue("bytes differ (viaByteBuffer=$viaByteBuffer)", read.contentEquals(file))
            call(owned, "releaseRetainedSession")
            call(owned, "clearGlobalPool")
        }
        assertTrue(memory.sizes.isNotEmpty())
        assertTrue(memory.sizes.all { it == ParallelRangeDataSource.SEGMENT_BYTES })
        assertEquals(0, memory.live.size)
    }

    @Test
    fun `parts given back are reused, so a film never takes more parts than it holds at once`() {
        val read = readAll(source { Long.MAX_VALUE }, viaByteBuffer = false)
        assertTrue(read.contentEquals(file))
        assertTrue("allocated ${memory.sizes.size}, most live ${memory.mostLive.get()}",
            memory.sizes.size <= memory.mostLive.get())
    }

    @Test
    fun `switch off takes whole pieces exactly as before`() {
        val read = readAll(source(null), viaByteBuffer = false)
        assertTrue(read.contentEquals(file))
        assertTrue(memory.sizes.isNotEmpty())
        assertTrue(memory.sizes.all { it == CHUNK })
    }

    @Test
    fun `a piece waiting for its first byte holds no memory`() {
        val gate = CountDownLatch(1)
        held[3L] = gate
        val source = source { Long.MAX_VALUE }
        source.open(spec(0))
        call(source, "ensureChunkScheduled", 0L)
        futures(source)[0L]!!.get(10, TimeUnit.SECONDS)
        val before = heldBytes(source)
        val allocations = memory.sizes.size
        call(source, "ensureChunkScheduled", 3L)
        Thread.sleep(200)
        assertEquals(before, heldBytes(source))
        assertEquals(allocations, memory.sizes.size)
        gate.countDown()
        futures(source)[3L]!!.get(10, TimeUnit.SECONDS)
        assertEquals(before + 2L * ParallelRangeDataSource.SEGMENT_BYTES, heldBytes(source))
    }

    @Test
    fun `pieces ahead wait while the share is full and the piece being read never waits`() {
        val limit = 2L * ParallelRangeDataSource.SEGMENT_BYTES
        val source = source { limit }
        source.open(spec(0))
        val first = ByteArray(1_000)
        assertTrue(source.read(first, 0, first.size) > 0)
        futures(source)[0L]!!.get(10, TimeUnit.SECONDS)
        call(source, "ensureChunkScheduled", 1L)
        awaitTrue("the piece ahead started") { memory.sizes.size >= 3 || futures(source)[1L] != null }
        Thread.sleep(300)
        assertFalse("piece 1 must wait while piece 0 holds the share", futures(source)[1L]!!.isDone)
        assertEquals(2L * ParallelRangeDataSource.SEGMENT_BYTES, heldBytes(source))
        source.close()

        val read = readAll(source { limit }, viaByteBuffer = true)
        assertTrue("bytes differ after waiting", read.contentEquals(file))
    }

    private fun withLongHeldWait(block: () -> Unit) {
        val before = ParallelRangeDataSource.heldWaitMs
        ParallelRangeDataSource.heldWaitMs = 60_000L
        try {
            block()
        } finally {
            ParallelRangeDataSource.heldWaitMs = before
        }
    }

    private fun parkPieceOne(source: ParallelRangeDataSource): ByteArray {
        source.open(spec(0))
        val first = ByteArray(1_000)
        assertTrue(source.read(first, 0, first.size) > 0)
        futures(source)[0L]!!.get(10, TimeUnit.SECONDS)
        call(source, "ensureChunkScheduled", 1L)
        awaitTrue("the piece ahead started") { futures(source)[1L] != null }
        Thread.sleep(300)
        assertFalse("piece 1 must wait while piece 0 holds the share", futures(source)[1L]!!.isDone)
        return first
    }

    @Test
    fun `a waiting piece goes on as soon as the reader reaches it`() = withLongHeldWait {
        val source = source { 2L * ParallelRangeDataSource.SEGMENT_BYTES }
        val first = parkPieceOne(source)
        val out = ByteArray(CHUNK + 50_000)
        System.arraycopy(first, 0, out, 0, first.size)
        var position = first.size
        val started = System.nanoTime()
        while (position < out.size) {
            val read = source.read(out, position, minOf(70_000, out.size - position))
            assertTrue(read > 0)
            position += read
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("reader waited $tookMs ms for the parked piece", tookMs < 5_000L)
        assertTrue(out.contentEquals(file.copyOfRange(0, out.size)))
    }

    @Test
    fun `a waiting piece stops as soon as its session ends`() = withLongHeldWait {
        val source = source { 2L * ParallelRangeDataSource.SEGMENT_BYTES }
        parkPieceOne(source)
        val executor = (field(owned, "executorDelegate") as Lazy<*>).value as ThreadPoolExecutor
        assertTrue(executor.activeCount > 0)
        source.close()
        call(owned, "releaseRetainedSession")
        awaitTrue("the parked download is still running") { executor.activeCount == 0 }
    }

    private companion object {
        const val CHUNK = 1_500_000
    }
}
