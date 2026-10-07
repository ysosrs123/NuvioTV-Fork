package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.LocalTimeshiftFailure
import com.nuvio.tv.core.iptv.LocalTimeshiftLength
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class IptvLocalTimeshiftSessionTest {
    private val directory: File = Files.createTempDirectory("timeshift-session").toFile()
    private val config = IptvLocalTimeshiftConfig(File(directory, "timeshift"), LocalTimeshiftLength.AUTOMATIC)
    private val roomy: (File) -> Long = { 10_000_000_000 }

    @After fun clean() { directory.deleteRecursively() }

    private fun packets(count: Int) = ByteArray(count * 188) { if (it % 188 == 0) 0x47 else (it / 188 % 251).toByte() }

    private class Input(private val stream: InputStream, private val events: MutableList<String>) : IptvLocalTimeshiftInput {
        override fun open(): InputStream { events += "open"; return stream }
        override fun cancel() { events += "cancel"; stream.close() }
        override fun release() { events += "release" }
    }

    @Test fun noRoomRefusesTheSession() {
        assertThrows(IOException::class.java) { IptvLocalTimeshiftSession.create(config, { 100_000_000 }) }
        assertTrue(config.directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun writerEndReleasesTheConnectionBeforeReporting() {
        val clock = AtomicLong(1_000_000)
        val session = IptvLocalTimeshiftSession.create(config, roomy) { clock.addAndGet(1_000) }
        val events = Collections.synchronizedList(mutableListOf<String>())
        val ended = CountDownLatch(1)
        var reason: LocalTimeshiftFailure? = null
        session.start(Input(ByteArrayInputStream(packets(2_000)), events)) { events += "end"; reason = it; ended.countDown() }
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("open", "release", "end"), events.toList())
        assertEquals(LocalTimeshiftFailure.ENDED, reason)
        assertEquals(LocalTimeshiftFailure.ENDED, session.failure)
        assertFalse(session.running)
        assertEquals(2_000L * 188, session.head)
        val buffer = ByteArray(188)
        assertEquals(188, session.read(0, buffer, 0, 188))
        assertEquals(0x47.toByte(), buffer[0])
        assertThrows(IptvLocalTimeshiftClosedException::class.java) { session.read(session.head, buffer, 0, 188) }
        session.close()
        assertTrue(config.directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun anchorsComeFromTheTimeIndexOnPacketBoundaries() {
        val clock = AtomicLong(1_000_000)
        val session = IptvLocalTimeshiftSession.create(config, roomy) { clock.addAndGet(1_000) }
        val ended = CountDownLatch(1)
        session.start(Input(ByteArrayInputStream(packets(5_120)), mutableListOf())) { ended.countDown() }
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        val oldest = session.oldestTime()!!
        val newest = session.newestTime()!!
        assertTrue(newest > oldest)
        val middle = session.anchorAt((oldest + newest) / 2)
        assertEquals(0L, middle % 188)
        assertTrue(middle > 0 && middle < session.head)
        assertTrue(session.timeAt(middle)!! <= (oldest + newest) / 2)
        assertEquals(0L, session.anchorAt(0))
        assertEquals(0L, session.liveAnchor() % 188)
        assertTrue(session.liveAnchor() >= middle)
        session.close()
    }

    @Test fun stalledReaderGivesUpAndStopCancelsTheConnection() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val closed = CountDownLatch(1)
        val pipe = object : InputStream() {
            override fun read(): Int = throw IOException("unused")
            override fun read(b: ByteArray, off: Int, len: Int): Int { closed.await(); throw IOException("closed") }
            override fun close() { closed.countDown() }
        }
        val session = IptvLocalTimeshiftSession.create(config, roomy)
        var ended = false
        session.start(Input(pipe, events)) { ended = true }
        assertThrows(IptvLocalTimeshiftStalledException::class.java) { session.read(0, ByteArray(188), 0, 188, stallMillis = 300) }
        session.stop()
        assertTrue(session.awaitStopped(5_000))
        assertEquals(listOf("open", "cancel", "release"), events.toList())
        assertFalse(ended)
        assertNull(session.failure)
        session.close()
    }

    @Test fun addressesCarryTheSessionAndAnchor() {
        val session = IptvLocalTimeshiftSession.create(config, roomy)
        assertEquals(session.id to 376L, IptvLocalTimeshiftSession.parse(session.uri(376)))
        assertNull(IptvLocalTimeshiftSession.parse("http://example.invalid/1"))
        assertNull(IptvLocalTimeshiftSession.parse("${IptvLocalTimeshiftSession.SCHEME}://abc/-1"))
        assertNull(IptvLocalTimeshiftSession.parse("${IptvLocalTimeshiftSession.SCHEME}:///5"))
        session.close()
    }

    @Test fun sweepRemovesOnlyLeftoverBuffers() {
        val session = IptvLocalTimeshiftSession.create(config, roomy)
        val leftover = File(config.directory, "ring-0123456789abcdef.ts").apply { writeBytes(ByteArray(10)) }
        val other = File(config.directory, "notes.txt").apply { writeText("x") }
        val activeFiles = config.directory.listFiles().orEmpty().filter { it.name.startsWith("ring-") && it != leftover }
        assertEquals(1, activeFiles.size)
        assertEquals(1, IptvLocalTimeshiftSession.sweep(listOf(config.directory, File(directory, "missing"))))
        assertFalse(leftover.exists())
        assertTrue(other.exists() && activeFiles.single().exists())
        session.close()
        assertFalse(activeFiles.single().exists())
    }
}
