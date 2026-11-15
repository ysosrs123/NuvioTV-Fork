package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ParallelRangeTeardownPressureTest {

    private val server = MockWebServer()
    private val file = Random(23).nextBytes(40 * CHUNK)
    private val opened = ConcurrentHashMap.newKeySet<ParallelRangeDataSource>()
    @Volatile private var slowFrom = Int.MAX_VALUE
    @Volatile private var farthestChunk = 0
    private val requested = CopyOnWriteArrayList<IntArray>()
    private val calls = CopyOnWriteArrayList<Call>()
    private val closedOn = CopyOnWriteArrayList<String>()

    private val owned: Any = ParallelRangeDataSource::class.java.declaredClasses.single { it.simpleName == "Resources" }
        .declaredConstructors.single { it.parameterCount == 2 }.apply { isAccessible = true }.newInstance(false, 2)

    @Before
    fun setUp() {
        mockkStatic(Uri::class, SystemClock::class)
        every { Uri.parse(any()) } answers { uriOf(firstArg()) }
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000L }
        every { SystemClock.uptimeMillis() } answers { System.nanoTime() / 1_000_000L }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val parts = request.getHeader("Range")!!.removePrefix("bytes=").split("-")
                val from = parts[0].toInt()
                val to = minOf(parts.getOrNull(1)?.toIntOrNull() ?: (file.size - 1), file.size - 1)
                requested += intArrayOf(from / CHUNK, farthestChunk)
                val response = MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $from-$to/${file.size}")
                    .setBody(Buffer().write(file, from, to - from + 1))
                return if (from >= slowFrom) response.throttleBody(8 * 1024L, 5, TimeUnit.SECONDS) else response
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        ParallelRangeDataSource.onMemoryCritical(false)
        opened.forEach { runCatching { it.close() } }
        call(owned, "releaseRetainedSession")
        call(owned, "clearGlobalPool")
        val executor = field(owned, "executorDelegate") as Lazy<*>
        if (executor.isInitialized()) {
            (executor.value as ThreadPoolExecutor).shutdownNow()
            (executor.value as ThreadPoolExecutor).awaitTermination(5, TimeUnit.SECONDS)
        }
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

    /** Records each request and the thread that closes the body of every piece past the first. */
    private fun trackedCalls(): Call.Factory {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val from = chain.request().header("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
            val body = response.body ?: return@addInterceptor response
            if (from < CHUNK) return@addInterceptor response
            val watched = object : ForwardingSource(body.source()) {
                override fun close() {
                    closedOn += Thread.currentThread().name
                    super.close()
                }
            }.buffer()
            response.newBuilder().body(watched.asResponseBody(body.contentType(), body.contentLength())).build()
        }.build()
        return PieceCallFactory(object : Call.Factory {
            override fun newCall(request: Request): Call = client.newCall(request).also { calls += it }
        })
    }

    private fun source(depthLimit: (() -> Int)? = null, connections: Int = 4): ParallelRangeDataSource {
        val source = ParallelRangeDataSource(
            OkHttpDataSource.Factory(trackedCalls()),
            parallelConnections = connections,
            chunkSize = CHUNK.toLong(),
            depthLimit = depthLimit
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

    @Suppress("UNCHECKED_CAST")
    private fun futures(session: Any) = field(session, "futures") as ConcurrentHashMap<Long, CompletableFuture<*>>

    @Suppress("UNCHECKED_CAST")
    private fun activeCalls(session: Any) = field(session, "activeCalls") as MutableSet<Call>

    private fun fromOf(call: Call): Long =
        call.request().header("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L

    private fun awaitTrue(message: String, seconds: Long = 10, fact: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!fact() && System.nanoTime() < end) Thread.sleep(5)
        assertTrue(message, fact())
    }

    /** Reads up to [until] from [position], checking every byte; returns the new position. */
    private fun readTo(source: ParallelRangeDataSource, position: Int, until: Int): Int {
        val scratch = ByteArray(64 * 1024)
        var at = position
        while (at < until) {
            farthestChunk = (at + scratch.size - 1) / CHUNK
            val read = source.read(scratch, 0, minOf(scratch.size, until - at))
            if (read == C.RESULT_END_OF_INPUT) break
            for (i in 0 until read) if (scratch[i] != file[at + i]) throw AssertionError("byte ${at + i} differs")
            at += read
        }
        return at
    }

    @Test
    fun `tearing a session down cancels the running requests and each worker closes its own`() {
        slowFrom = CHUNK
        val source = source(depthLimit = { 3 })
        source.open(spec(0))
        readTo(source, 0, 16 * 1024)
        val live = session(source)
        fun slow() = activeCalls(live).filter { fromOf(it) >= CHUNK }
        awaitTrue("a slow piece is running") { slow().isNotEmpty() }
        Thread.sleep(300)
        val running = slow()

        val startNs = System.nanoTime()
        call(owned, "releaseRetainedSession")
        awaitTrue("the slow pieces stop at once", seconds = 3) {
            activeCalls(live).isEmpty() && closedOn.size >= running.size
        }
        val tookMs = (System.nanoTime() - startNs) / 1_000_000L

        assertTrue("took $tookMs ms", tookMs < 3_000L)
        assertTrue(running.all { it.isCanceled() })
        assertTrue("closed on $closedOn", closedOn.isNotEmpty() && closedOn.all { it == "parallel-ds-worker" })
        assertEquals(0, futures(live).size)
    }

    @Test
    fun `a request made after the session was torn down is cancelled at once`() {
        val source = source(depthLimit = { 3 })
        source.open(spec(0))
        readTo(source, 0, 16 * 1024)
        val live = session(source)
        call(owned, "releaseRetainedSession")
        val late = OkHttpClient().newCall(Request.Builder().url(server.url("/file")).build())
        call(live, "adoptCall", late)
        assertTrue(late.isCanceled())
    }

    @Test
    fun `critical memory limits downloads to the reader's piece and the next, and easing lifts it`() {
        val source = source()
        ParallelRangeDataSource.onMemoryCritical(true)
        assertTrue(ParallelRangeDataSource.memoryCapped)
        source.open(spec(0))
        var position = readTo(source, 0, 20 * CHUNK)
        val leads = requested.filter { it[0] > 0 }.map { it[0] - it[1] }
        assertTrue("pieces requested ahead: $leads", leads.isNotEmpty() && leads.all { it <= 1 })

        ParallelRangeDataSource.onMemoryCritical(false)
        assertFalse(ParallelRangeDataSource.memoryCapped)
        requested.clear()
        position = readTo(source, position, 30 * CHUNK)
        val after = requested.filter { it[0] > 0 }.map { it[0] - it[1] }
        assertTrue("pieces requested ahead after easing: $after", after.any { it >= 2 })
        readTo(source, position, file.size)
    }

    @Test
    fun `a pressure trim keeps the piece being read and the next, and the film still reads through`() {
        val source = source()
        source.open(spec(0))
        var position = readTo(source, 0, 8 * CHUNK + CHUNK / 2)
        val live = session(source)
        awaitTrue("pieces ahead are held") { futures(live).keys.any { it >= 10 } }

        val reader = (field(live, "lastReadChunkIndex") as Long)
        call(owned, "trimForPressure", 1)

        val held = futures(live).keys
        assertTrue("held $held reader $reader", reader in held && held.none { it > reader + 1 })
        position = readTo(source, position, file.size)
        assertEquals(file.size, position)
    }

    private companion object {
        const val CHUNK = 256 * 1024
    }
}
