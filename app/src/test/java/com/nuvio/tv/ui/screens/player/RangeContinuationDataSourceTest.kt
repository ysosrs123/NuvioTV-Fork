package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import androidx.media3.common.ByteBufferDataReader
import java.util.concurrent.atomic.AtomicInteger

class RangeContinuationDataSourceTest {

    private val file = ByteArray(700 * 1024) { (it * 17 + 3).toByte() }
    private val server = MockWebServer()
    private val requests = AtomicInteger()
    @Volatile private var capBytes = 256 * 1024
    @Volatile private var continuationAnswer: (Int) -> MockResponse? = { null }

    private fun uriOf(url: String): Uri = mockk(relaxed = true) {
        every { this@mockk.toString() } returns url
        every { host } returns "127.0.0.1"
        every { path } returns "/file"
        every { scheme } returns "http"
        every { getQueryParameter(any()) } returns null
    }

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { uriOf(firstArg()) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = requests.incrementAndGet()
                continuationAnswer(n)?.let { return it }
                val range = request.getHeader("Range")?.removePrefix("bytes=")?.split("-")
                val from = range?.get(0)?.toInt() ?: 0
                val asked = range?.getOrNull(1)?.toIntOrNull() ?: (file.size - 1)
                val to = minOf(asked, file.size - 1, from + capBytes - 1)
                // A capped piece comes back as a 206 even without a Range header, as the reported server did.
                return MockResponse().setResponseCode(206)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes $from-$to/${file.size}")
                    .setBody(Buffer().write(file, from, to - from + 1))
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkStatic(Uri::class)
    }

    private fun newSource() = RangeContinuationDataSource(OkHttpDataSource.Factory(OkHttpClient()))

    private fun spec(position: Long = 0L, length: Long = C.LENGTH_UNSET.toLong()) = DataSpec.Builder()
        .setUri(uriOf(server.url("/file").toString()))
        .setPosition(position)
        .setLength(length)
        .build()

    private fun readToEnd(source: DataSource): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    @Test
    fun `a capped server still gives the whole file and its real length`() {
        val source = newSource()
        try {
            assertEquals(file.size.toLong(), source.open(spec()))
            assertArrayEquals(file, readToEnd(source))
        } finally {
            source.close()
        }
        assertEquals(3, requests.get())
    }

    @Test
    fun `an open after a seek reads on past the capped piece`() {
        val from = 100_000L
        val source = newSource()
        try {
            assertEquals(file.size - from, source.open(spec(position = from)))
            assertArrayEquals(file.copyOfRange(from.toInt(), file.size), readToEnd(source))
        } finally {
            source.close()
        }
    }

    @Test
    fun `a bounded request longer than the cap gets exactly what it asked for`() {
        val from = 50_000L
        val length = 400_000L
        val source = newSource()
        try {
            assertEquals(length, source.open(spec(position = from, length = length)))
            assertArrayEquals(file.copyOfRange(from.toInt(), (from + length).toInt()), readToEnd(source))
        } finally {
            source.close()
        }
    }

    @Test
    fun `a server that sends the whole file is left alone`() {
        capBytes = Int.MAX_VALUE
        val source = newSource()
        try {
            assertEquals(file.size.toLong(), source.open(spec()))
            assertArrayEquals(file, readToEnd(source))
        } finally {
            source.close()
        }
        assertEquals(1, requests.get())
    }

    @Test
    fun `an empty continuation fails instead of ending the file early`() {
        val uri = uriOf("http://127.0.0.1/file")
        val opens = AtomicInteger()
        val factory = DataSource.Factory {
            object : DataSource {
                private var left = 0
                override fun addTransferListener(transferListener: TransferListener) {}
                override fun open(dataSpec: DataSpec): Long {
                    left = if (opens.getAndIncrement() == 0) 10 else 0
                    return left.toLong()
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (left == 0) return C.RESULT_END_OF_INPUT
                    val n = minOf(length, left)
                    left -= n
                    return n
                }
                override fun getUri(): Uri = uri
                override fun getResponseHeaders(): Map<String, List<String>> =
                    mapOf("Content-Range" to listOf("bytes 0-9/100"))
                override fun close() {}
            }
        }
        val source = RangeContinuationDataSource(factory)
        assertEquals(100L, source.open(DataSpec.Builder().setUri(uri).build()))
        val buffer = ByteArray(64)
        assertEquals(10, source.read(buffer, 0, buffer.size))
        assertThrows(IOException::class.java) { source.read(buffer, 0, buffer.size) }
        source.close()
    }

    @Test
    fun `a continuation answered with a 200 fails instead of giving the wrong bytes`() {
        continuationAnswer = { n -> if (n == 2) MockResponse().setResponseCode(200).setBody(Buffer().write(file)) else null }
        val source = newSource()
        try {
            source.open(spec())
            assertThrows(IOException::class.java) { readToEnd(source) }
        } finally {
            source.close()
        }
    }

    @Test
    fun `a continuation from a changed file fails`() {
        continuationAnswer = { n ->
            if (n == 2) MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes ${256 * 1024}-${512 * 1024 - 1}/999999")
                .setBody(Buffer().write(file, 256 * 1024, 256 * 1024))
            else null
        }
        val source = newSource()
        try {
            source.open(spec())
            assertThrows(IOException::class.java) { readToEnd(source) }
        } finally {
            source.close()
        }
    }

    @Test
    fun `buffer reads also read on past the capped piece`() {
        val source = newSource()
        try {
            assertEquals(file.size.toLong(), source.open(spec()))
            val out = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(32 * 1024)
            while (true) {
                buffer.clear()
                val read = (source as ByteBufferDataReader).read(buffer, buffer.capacity())
                if (read == C.RESULT_END_OF_INPUT) break
                buffer.flip()
                val chunk = ByteArray(buffer.remaining())
                buffer.get(chunk)
                out.write(chunk)
            }
            assertArrayEquals(file, out.toByteArray())
        } finally {
            source.close()
        }
    }

    @Test
    fun `continuation only when the content range proves more is left`() {
        val unset = C.LENGTH_UNSET.toLong()
        val uri = uriOf("http://127.0.0.1/file")
        val open = DataSpec.Builder().setUri(uri).setPosition(100L).build()
        fun range(value: String) = mapOf("Content-Range" to listOf(value))

        assertEquals(5_000L, RangeContinuationDataSource.continuationEnd(open, 900L, range("bytes 100-999/5000")))
        assertEquals(unset, RangeContinuationDataSource.continuationEnd(open, 4_900L, range("bytes 100-4999/5000")))
        assertEquals(unset, RangeContinuationDataSource.continuationEnd(open, 900L, emptyMap()))
        assertEquals(unset, RangeContinuationDataSource.continuationEnd(open, 900L, range("bytes 100-999/*")))
        assertEquals(unset, RangeContinuationDataSource.continuationEnd(open, 900L, range("bytes 0-899/5000")))
        assertEquals(5_000L, RangeContinuationDataSource.continuationEnd(open, unset, range("bytes 100-999/5000")))

        val bounded = DataSpec.Builder().setUri(uri).setPosition(100L).setLength(2_000L).build()
        assertEquals(2_100L, RangeContinuationDataSource.continuationEnd(bounded, 2_000L, range("bytes 100-999/5000")))
        assertEquals(unset, RangeContinuationDataSource.continuationEnd(bounded, 2_000L, range("bytes 100-2099/5000")))
    }
}
