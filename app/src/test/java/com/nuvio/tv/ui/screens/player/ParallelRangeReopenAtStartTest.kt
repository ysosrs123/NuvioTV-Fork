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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The film is loaded again from its start (the player let go for the thumbnails and prepares again) while the
 * speed-up session still holds pieces from far into the file: the start must read as the file's own first bytes.
 */
class ParallelRangeReopenAtStartTest {

    private val server = MockWebServer()
    private val file = Random(7).nextBytes(24 * CHUNK)
    private val opened = ConcurrentHashMap.newKeySet<ParallelRangeDataSource>()

    private val owned: Any = ParallelRangeDataSource::class.java.declaredClasses.single { it.simpleName == "Resources" }
        .declaredConstructors.single { it.parameterCount == 2 }.apply { isAccessible = true }.newInstance(false, 4)

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
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $from-$to/${file.size}")
                    .setBody(Buffer().write(file, from, to - from + 1))
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
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

    private fun source(): ParallelRangeDataSource {
        val source = ParallelRangeDataSource(
            OkHttpDataSource.Factory(OkHttpClient()),
            parallelConnections = 3,
            chunkSize = CHUNK.toLong(),
            prefetchDepthChunks = 3,
            depthLimit = { 3 },
            requestMeter = RequestMeter(),
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

    @Suppress("UNCHECKED_CAST")
    private fun futures(source: ParallelRangeDataSource) =
        field(field(source, "session")!!, "futures") as ConcurrentHashMap<Long, CompletableFuture<*>>

    private fun readExactly(source: ParallelRangeDataSource, bytes: Int): ByteArray {
        val out = ByteArray(bytes)
        var got = 0
        while (got < bytes) {
            val read = source.read(out, got, bytes - got)
            if (read == C.RESULT_END_OF_INPUT) break
            got += read
        }
        return out.copyOf(got)
    }

    private fun awaitTrue(message: String, fact: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!fact() && System.nanoTime() < end) Thread.sleep(5)
        assertTrue(message, fact())
    }

    @Test
    fun `the start loaded again reads the file's first bytes while far pieces are still held`() {
        val first = source()
        first.open(spec(0))
        assertArrayEquals(file.copyOfRange(0, CHUNK + 10), readExactly(first, CHUNK + 10))
        // The resume point, far into the file: the session holds pieces there.
        val far = 14L * CHUNK
        first.close()
        first.open(spec(far))
        assertArrayEquals(file.copyOfRange(far.toInt(), far.toInt() + 2 * CHUNK), readExactly(first, 2 * CHUNK))
        awaitTrue("pieces ahead of the resume point held") { futures(first).keys.count { it >= 14L } >= 3 }
        // Whether the start is still held here depends on download timing; the start must read right either way.
        first.close()
        // The hold behind the thumbnails screen: seconds pass before the film loads again.
        Thread.sleep(2_200)

        val again = source()
        again.open(spec(0))
        val head = readExactly(again, 3 * CHUNK)
        assertArrayEquals(file.copyOfRange(0, 3 * CHUNK), head)
        again.close()
    }

    private companion object {
        const val CHUNK = 256 * 1024
    }
}
