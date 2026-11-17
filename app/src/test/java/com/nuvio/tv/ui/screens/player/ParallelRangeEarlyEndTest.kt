package com.nuvio.tv.ui.screens.player

import android.net.Uri
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer

class ParallelRangeEarlyEndTest {

    private val file = ByteArray(600 * 1024) { (it * 13 + 5).toByte() }
    private val server = MockWebServer()

    private fun uriOf(url: String): Uri = mockk(relaxed = true) {
        every { this@mockk.toString() } returns url
        every { host } returns "127.0.0.1"
        every { path } returns "/file"
        every { getQueryParameter(any()) } returns null
    }

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { uriOf(firstArg()) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                    ?: return MockResponse().setResponseCode(200).setBody(Buffer().write(file))
                val parts = range.removePrefix("bytes=").split("-")
                val from = parts[0].toInt()
                val to = minOf(parts.getOrNull(1)?.toIntOrNull() ?: (file.size - 1), file.size - 1)
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

    private fun newSource() = ParallelRangeDataSource(
        OkHttpDataSource.Factory(OkHttpClient()),
        parallelConnections = 2,
        chunkSize = 128L * 1024L,
        isolateSession = true,
    )

    private fun spec() = DataSpec.Builder().setUri(uriOf(server.url("/file").toString())).build()

    @Test
    fun `open reports the whole file and a full read ends only at the real end`() {
        val source = newSource()
        try {
            assertEquals(file.size.toLong(), source.open(spec()))
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                total += read
            }
            assertEquals(file.size.toLong(), total)
        } finally {
            source.close()
        }
    }

    @Test
    fun `losing the session before the end fails instead of reporting end of input`() {
        val source = newSource()
        source.open(spec())
        val buffer = ByteArray(16 * 1024)
        assertTrue(source.read(buffer, 0, buffer.size) > 0)
        source.close()
        assertThrows(IOException::class.java) { source.read(buffer, 0, buffer.size) }
    }

    @Test
    fun `byte buffer reads fail the same way before the end`() {
        val source = newSource()
        source.open(spec())
        val target = ByteBuffer.allocateDirect(16 * 1024)
        assertTrue(source.read(target, target.capacity()) > 0)
        source.close()
        target.clear()
        assertThrows(IOException::class.java) { source.read(target, target.capacity()) }
    }

    @Test
    fun `end of input only when nothing is left`() {
        assertTrue(ParallelRangeDataSource.reportsEndOfInput(0L))
        assertTrue(ParallelRangeDataSource.reportsEndOfInput(C.LENGTH_UNSET.toLong()))
        assertFalse(ParallelRangeDataSource.reportsEndOfInput(1L))
        assertFalse(ParallelRangeDataSource.reportsEndOfInput(67_108_864L))
    }
}
