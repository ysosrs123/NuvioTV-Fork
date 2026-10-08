package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.LocalTimeshiftFailure
import com.nuvio.tv.core.iptv.LocalTimeshiftLength
import com.nuvio.tv.core.iptv.LocalTimeshiftRing
import com.nuvio.tv.core.iptv.LocalTimeshiftSizing
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class IptvLocalTimeshiftRingTest {
    private val directory: File = Files.createTempDirectory("timeshift-ring").toFile()
    private val capacity = 188L * 4096

    @After fun clean() { directory.deleteRecursively() }

    private fun expected(offset: Long): Byte = if (offset % 188 == 0L) 0x47 else ((offset / 188) % 251).toByte()
    private fun packets(from: Long, count: Int) = ByteArray(count * 188) { expected(from * 188 + it) }

    private fun fill(ring: IptvLocalTimeshiftRing, total: Int) {
        var written = 0
        while (written < total) {
            val count = minOf(512, total - written)
            ring.write(packets(written.toLong(), count), 0, count * 188)
            written += count
        }
    }

    @Test fun writesWrapAroundAndReadsReturnTheLatestBytes() {
        val ring = IptvLocalTimeshiftRing(File(directory, "ring.ts"), capacity)
        fill(ring, 6144)
        assertEquals(6144L * 188, ring.head)
        assertTrue(File(directory, "ring.ts").length() <= capacity)
        var at = ring.oldest()
        val buffer = ByteArray(50_000)
        while (at < ring.head) {
            val count = ring.read(at, buffer, 0, buffer.size, 0)
            assertTrue(count > 0)
            for (i in 0 until count) assertEquals(expected(at + i), buffer[i])
            at += count
        }
        ring.close()
    }

    @Test fun interruptedReaderLeavesTheRingWritable() {
        val ring = IptvLocalTimeshiftRing(File(directory, "ring.ts"), capacity)
        fill(ring, 1024)
        var read = -1
        thread {
            Thread.currentThread().interrupt()
            read = ring.read(ring.oldest(), ByteArray(188 * 8), 0, 188 * 8, 0)
        }.join()
        assertEquals(188 * 8, read)
        ring.write(packets(1024, 8), 0, 188 * 8)
        val buffer = ByteArray(188)
        assertEquals(188, ring.read(1024L * 188, buffer, 0, 188, 0))
        assertEquals(expected(1024L * 188 + 1), buffer[1])
        ring.close()
    }

    @Test fun overwrittenDataIsReportedAsBehind() {
        val ring = IptvLocalTimeshiftRing(File(directory, "ring.ts"), capacity)
        fill(ring, 6144)
        assertThrows(IptvLocalTimeshiftBehindException::class.java) { ring.read(0, ByteArray(188), 0, 188, 0) }
        assertThrows(IptvLocalTimeshiftBehindException::class.java) { ring.read(ring.oldest() - 188, ByteArray(188), 0, 188, 0) }
        ring.close()
    }

    @Test fun readerWaitsAtTheLiveEdge() {
        val ring = IptvLocalTimeshiftRing(File(directory, "ring.ts"), capacity)
        fill(ring, 10)
        assertEquals(0, ring.read(ring.head, ByteArray(188), 0, 188, 30))
        val at = ring.head
        val writer = thread { Thread.sleep(100); ring.write(packets(10, 2), 0, 376) }
        assertEquals(376, ring.read(at, ByteArray(1000), 0, 1000, 5_000))
        writer.join()
        ring.close()
    }

    @Test fun closingWakesReadersAndDeletesTheFile() {
        val file = File(directory, "ring.ts")
        val ring = IptvLocalTimeshiftRing(file, capacity)
        fill(ring, 4)
        var failure: Throwable? = null
        val reader = thread { try { ring.read(ring.head, ByteArray(188), 0, 188, 10_000) } catch (error: Throwable) { failure = error } }
        Thread.sleep(100)
        ring.close()
        reader.join(5_000)
        assertTrue(failure is IptvLocalTimeshiftClosedException)
        assertFalse(file.exists())
    }

    @Test fun failedWriterLetsReadersDrainThenReportsTheReason() {
        val ring = IptvLocalTimeshiftRing(File(directory, "ring.ts"), capacity)
        fill(ring, 4)
        ring.fail(LocalTimeshiftFailure.NETWORK)
        assertEquals(188, ring.read(ring.head - 188, ByteArray(188), 0, 188, 0))
        val error = assertThrows(IptvLocalTimeshiftClosedException::class.java) { ring.read(ring.head, ByteArray(188), 0, 188, 1_000) }
        assertEquals(LocalTimeshiftFailure.NETWORK, error.failure)
        ring.close()
    }

    private fun stepping(): () -> Long { var calls = 0; return { if (calls++ == 0) 0L else 20_000L } }

    @Test fun writerSyncsToPacketsAndFixesCapacityFromTheMeasuredRate() {
        val ring = IptvLocalTimeshiftRing(File(directory, "ring.ts"), LocalTimeshiftSizing.alignDown(1_000_000_000))
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 0x47, 5, 6, 7) + packets(0, 100))
        val writer = IptvLocalTimeshiftWriter(ring, LocalTimeshiftLength.AUTOMATIC, { 10_000_000_000 }, stepping())
        assertEquals(LocalTimeshiftFailure.ENDED, writer.run(input) { false })
        assertEquals(100L * 188, ring.head)
        assertTrue(ring.finalized)
        assertEquals(LocalTimeshiftSizing.MIN_BITS, writer.bitsPerSecond)
        assertEquals(LocalTimeshiftSizing.bytesFor(LocalTimeshiftSizing.MIN_BITS, 30), ring.capacity)
        val first = ByteArray(188)
        assertEquals(188, ring.read(0, first, 0, 188, 0))
        assertArrayEquals(packets(0, 1), first)
        ring.close()
    }

    @Test fun writerStopsWhenThereIsNoRoomForTheBuffer() {
        val ring = IptvLocalTimeshiftRing(File(directory, "ring.ts"), LocalTimeshiftSizing.alignDown(1_000_000_000))
        val writer = IptvLocalTimeshiftWriter(ring, LocalTimeshiftLength.MINUTES_60, { 0 }, stepping())
        assertEquals(LocalTimeshiftFailure.NO_SPACE, writer.run(ByteArrayInputStream(packets(0, 100))) { false })
        ring.close()
    }

    @Test fun writerReportsNetworkErrorsAndStopsQuietlyWhenAsked() {
        val ring = IptvLocalTimeshiftRing(File(directory, "ring.ts"), capacity)
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("reset")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("reset")
        }
        assertEquals(LocalTimeshiftFailure.NETWORK, IptvLocalTimeshiftWriter(ring, LocalTimeshiftLength.MINUTES_15, { 10_000_000_000 }).run(broken) { false })
        var stopped = false
        val stopping = object : InputStream() {
            override fun read(): Int = throw IOException("cancelled")
            override fun read(b: ByteArray, off: Int, len: Int): Int { stopped = true; throw IOException("cancelled") }
        }
        assertNull(IptvLocalTimeshiftWriter(ring, LocalTimeshiftLength.MINUTES_15, { 10_000_000_000 }).run(stopping) { stopped })
        ring.close()
    }

    @Test fun writesStayWithinOneChunkAndWholePackets() {
        val ring = IptvLocalTimeshiftRing(File(directory, "ring.ts"), capacity)
        assertThrows(IllegalArgumentException::class.java) { ring.write(ByteArray(100), 0, 100) }
        assertThrows(IllegalArgumentException::class.java) { ring.write(ByteArray(LocalTimeshiftRing.WRITE_CHUNK + 188), 0, LocalTimeshiftRing.WRITE_CHUNK + 188) }
        ring.close()
    }
}
