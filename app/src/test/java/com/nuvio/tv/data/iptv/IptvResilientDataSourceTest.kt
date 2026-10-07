package com.nuvio.tv.data.iptv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class IptvResilientDataSourceTest {
    private sealed interface Step {
        data class Bytes(val data: ByteArray) : Step
        data class Fail(val error: IOException) : Step
        data object End : Step
    }

    private class FakeSource(private val connections: MutableList<List<Step>>, private val refusals: MutableList<IOException?> = mutableListOf()) : DataSource {
        var opens = 0
        var open = 0
        var maxOpen = 0
        private var steps: ArrayDeque<Step> = ArrayDeque()
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            opens++
            refusals.removeFirstOrNull()?.let { throw it }
            val next = connections.removeFirstOrNull() ?: throw IOException("refused")
            open++; maxOpen = maxOf(maxOpen, open)
            steps = ArrayDeque(next)
            return C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return when (val step = steps.removeFirstOrNull() ?: Step.End) {
                is Step.Fail -> throw step.error
                Step.End -> C.RESULT_END_OF_INPUT
                is Step.Bytes -> {
                    val count = minOf(length, step.data.size)
                    System.arraycopy(step.data, 0, buffer, offset, count)
                    if (count < step.data.size) steps.addFirst(Step.Bytes(step.data.copyOfRange(count, step.data.size)))
                    count
                }
            }
        }
        override fun getUri(): Uri? = null
        override fun close() { if (open > 0) open-- }
    }

    private fun packets(first: Int, count: Int): ByteArray = ByteArray(count * 188).also { bytes ->
        for (index in 0 until count) {
            bytes[index * 188] = 0x47
            for (offset in 1 until 188) bytes[index * 188 + offset] = ((first + index) % 100 + 1).toByte()
        }
    }

    private fun drain(source: DataSource, chunk: Int = 100): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(chunk)
        while (true) {
            val count = try { source.read(buffer, 0, buffer.size) } catch (_: IOException) { break }
            if (count == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun assertWholePackets(bytes: ByteArray) {
        assertEquals(0, bytes.size % 188)
        for (index in 0 until bytes.size / 188) {
            assertEquals(0x47.toByte(), bytes[index * 188])
            val fill = bytes[index * 188 + 1]
            for (offset in 1 until 188) assertEquals("packet $index", fill, bytes[index * 188 + offset])
        }
    }

    private val spec: DataSpec = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").let { field ->
        field.isAccessible = true
        val unsafe = field.get(null)
        unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, DataSpec::class.java) as DataSpec
    }

    private fun source(fake: FakeSource, buffered: Long = 10_000, sleeps: MutableList<Long> = mutableListOf(), reconnects: IntArray = IntArray(1), enabled: Boolean = true) =
        IptvResilientDataSource(fake, { enabled }, { buffered }, { true }, { reconnects[0]++ }, clock = { sleeps.sum() }, sleep = { sleeps += it })

    @Test fun rejoinsAtAPacketBoundaryAfterALostConnection() {
        val first = packets(0, 5)
        val second = packets(50, 4)
        val fake = FakeSource(mutableListOf(
            listOf(Step.Bytes(first.copyOfRange(0, 3 * 188 + 70)), Step.Fail(IOException("reset"))),
            listOf(Step.Bytes(ByteArray(41) { 0x47 }), Step.Bytes(second)),
        ))
        val reconnects = IntArray(1)
        val sleeps = mutableListOf<Long>()
        val source = source(fake, sleeps = sleeps, reconnects = reconnects)
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(spec))
        val bytes = drain(source)
        assertWholePackets(bytes)
        assertEquals(7 * 188, bytes.size)
        assertEquals(3.toByte(), bytes[2 * 188 + 1])
        assertEquals(51.toByte(), bytes[3 * 188 + 1])
        assertEquals(1, reconnects[0])
        assertEquals(250L, sleeps.first())
        assertEquals(1, fake.maxOpen)
    }

    @Test fun reconnectsOnEndOfStreamWhilePlayingFromTheBuffer() {
        val fake = FakeSource(mutableListOf(listOf(Step.Bytes(packets(0, 3)), Step.End), listOf(Step.Bytes(packets(10, 3)))))
        val reconnects = IntArray(1)
        val source = source(fake, reconnects = reconnects)
        source.open(spec)
        val bytes = drain(source, 4_096)
        assertWholePackets(bytes)
        assertEquals(6 * 188, bytes.size)
        assertEquals(1, reconnects[0])
        assertEquals(1, fake.maxOpen)
    }

    @Test fun providerRefusalIsHandedOverWithoutHammering() {
        val refusal = HttpDataSource.InvalidResponseCodeException(509, "limit", null, mapOf("Retry-After" to listOf("30")), spec, ByteArray(0))
        val fake = FakeSource(mutableListOf(listOf(Step.Bytes(packets(0, 3)), Step.Fail(IOException("reset")))), mutableListOf(null, refusal))
        val source = source(fake)
        source.open(spec)
        val buffer = ByteArray(4_096)
        assertEquals(3 * 188, source.read(buffer, 0, buffer.size))
        val error = assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) { source.read(buffer, 0, buffer.size) }
        assertEquals(509, error.responseCode)
        assertEquals(2, fake.opens)
    }

    @Test fun givesUpWhenTheBufferWouldRunOut() {
        val fake = FakeSource(mutableListOf(listOf(Step.Bytes(packets(0, 3)), Step.Fail(IOException("reset")))))
        val sleeps = mutableListOf<Long>()
        val source = source(fake, buffered = 2_000, sleeps = sleeps)
        source.open(spec)
        val buffer = ByteArray(4_096)
        source.read(buffer, 0, buffer.size)
        assertThrows(IOException::class.java) { source.read(buffer, 0, buffer.size) }
        assertEquals(listOf(250L), sleeps)
        assertEquals(1, fake.maxOpen)
    }

    @Test fun passesThroughWhenNotContinuousTs() {
        val raw = byteArrayOf(1, 2, 3, 4, 5)
        val fake = FakeSource(mutableListOf(listOf(Step.Bytes(raw), Step.End)))
        val source = source(fake, enabled = false)
        source.open(spec)
        assertArrayEquals(raw, drain(source))
        assertEquals(1, fake.opens)
    }

    @Test fun nonTransportStreamFailsInsteadOfLooping() {
        val fake = FakeSource(mutableListOf(listOf(Step.Bytes(ByteArray(10_000) { 1 }))))
        val source = source(fake)
        source.open(spec)
        assertThrows(IptvStreamSyncException::class.java) { source.read(ByteArray(188), 0, 188) }
        assertEquals(1, fake.opens)
    }

    @Test fun networkInterceptorRecordsProtocolAndAltSvcOncePerHost() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("x").addHeader("Alt-Svc", "h3=\":443\"; ma=60"))
        server.enqueue(MockResponse().setBody("x"))
        server.start()
        try {
            val network = IptvStreamNetwork()
            val client = OkHttpClient.Builder().socketFactory(IptvLiveSocketFactory(IptvLiveSocketFactory.LOW_MEMORY_BYTES)).addNetworkInterceptor(network).build()
            val url = server.url("/live/1.ts").toString()
            client.newCall(Request.Builder().url(url).build()).execute().close()
            client.newCall(Request.Builder().url(url).build()).execute().close()
            assertEquals("http/1.1", network.protocol)
            assertEquals(true, network.h3(url))
        } finally { server.shutdown() }
    }
}
