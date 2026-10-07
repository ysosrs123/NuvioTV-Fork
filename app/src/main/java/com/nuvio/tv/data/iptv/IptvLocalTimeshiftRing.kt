package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.LocalTimeshiftFailure
import com.nuvio.tv.core.iptv.LocalTimeshiftIndex
import com.nuvio.tv.core.iptv.LocalTimeshiftLength
import com.nuvio.tv.core.iptv.LocalTimeshiftPackets
import com.nuvio.tv.core.iptv.LocalTimeshiftRing
import com.nuvio.tv.core.iptv.LocalTimeshiftSizing
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class IptvLocalTimeshiftBehindException : IOException("Local timeshift reader fell behind")
class IptvLocalTimeshiftStalledException : IOException("Local timeshift reader stalled")
class IptvLocalTimeshiftClosedException(val failure: LocalTimeshiftFailure?) : IOException("Local timeshift closed")

class IptvLocalTimeshiftRing(private val file: File, provisionalCapacity: Long,
    private val clock: () -> Long = System::currentTimeMillis) : Closeable {
    init { require(provisionalCapacity > 0 && provisionalCapacity % LocalTimeshiftSizing.PACKET == 0L) }
    private val access = RandomAccessFile(file, "rw")
    private val channel = access.channel
    private val lock = Object()
    val index = LocalTimeshiftIndex()
    @Volatile var capacity = provisionalCapacity
        private set
    @Volatile var head = 0L
        private set
    @Volatile var finalized = false
        private set
    private var closed = false
    private var failure: LocalTimeshiftFailure? = null

    fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(length >= 0 && length % LocalTimeshiftSizing.PACKET == 0 && length <= LocalTimeshiftRing.WRITE_CHUNK)
        if (length == 0) return
        val start = head
        val size = capacity
        var done = 0
        while (done < length) {
            val logical = start + done
            val part = LocalTimeshiftRing.contiguous(logical, length - done, size)
            val buffer = ByteBuffer.wrap(bytes, offset + done, part)
            var position = LocalTimeshiftRing.filePosition(logical, size)
            while (buffer.hasRemaining()) position += channel.write(buffer, position)
            done += part
        }
        synchronized(lock) {
            if (closed) throw IptvLocalTimeshiftClosedException(failure)
            head = start + length
            index.add(clock(), start)
            index.trim(LocalTimeshiftRing.oldest(head, size))
            lock.notifyAll()
        }
    }

    fun resize(newCapacity: Long) = synchronized(lock) {
        check(!finalized)
        val floor = LocalTimeshiftSizing.alignUp(head) + LocalTimeshiftRing.margin(newCapacity) + LocalTimeshiftRing.WRITE_CHUNK
        capacity = LocalTimeshiftSizing.alignDown(maxOf(newCapacity, floor)).coerceAtMost(maxOf(capacity, floor))
        finalized = true
    }

    fun oldest(): Long = LocalTimeshiftRing.oldest(head, capacity)

    fun read(at: Long, target: ByteArray, offset: Int, length: Int, waitMillis: Long): Int {
        if (length == 0) return 0
        val size: Long
        val available: Long
        synchronized(lock) {
            val deadline = System.nanoTime() + waitMillis * 1_000_000
            while (!closed && failure == null && head <= at) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) return 0
                try { lock.wait(remaining) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt(); throw InterruptedIOException()
                }
            }
            if (closed || head <= at) throw IptvLocalTimeshiftClosedException(failure)
            size = capacity
            if (at < LocalTimeshiftRing.oldest(head, size)) throw IptvLocalTimeshiftBehindException()
            available = head - at
        }
        val part = LocalTimeshiftRing.contiguous(at, minOf(length.toLong(), available).toInt(), size)
        val buffer = ByteBuffer.wrap(target, offset, part)
        var position = LocalTimeshiftRing.filePosition(at, size)
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, position)
            if (count < 0) throw IptvLocalTimeshiftClosedException(LocalTimeshiftFailure.STORAGE)
            position += count
        }
        if (!LocalTimeshiftRing.intact(at, head, size)) throw IptvLocalTimeshiftBehindException()
        return part
    }

    fun fail(reason: LocalTimeshiftFailure) = synchronized(lock) { if (failure == null) failure = reason; lock.notifyAll() }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; lock.notifyAll() }
        try { channel.close() } catch (_: IOException) { }
        try { access.close() } catch (_: IOException) { }
        file.delete()
    }
}

class IptvLocalTimeshiftWriter(private val ring: IptvLocalTimeshiftRing, private val length: LocalTimeshiftLength,
    private val usable: () -> Long, private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }) {
    var bitsPerSecond: Long? = null
        private set

    fun run(input: InputStream, stopped: () -> Boolean): LocalTimeshiftFailure? {
        val packet = LocalTimeshiftSizing.PACKET
        val buffer = ByteArray(LocalTimeshiftRing.WRITE_CHUNK)
        var filled = 0
        var synced = false
        val started = elapsed()
        var checked = 0L
        while (!stopped()) {
            val count = try { input.read(buffer, filled, buffer.size - filled) } catch (_: IOException) {
                return if (stopped()) null else LocalTimeshiftFailure.NETWORK
            }
            if (count < 0) return if (stopped()) null else LocalTimeshiftFailure.ENDED
            filled += count
            if (!synced) {
                val at = LocalTimeshiftPackets.sync(buffer, 0, filled)
                if (at < 0 || at + packet >= filled) {
                    if (filled == buffer.size) { System.arraycopy(buffer, filled - packet, buffer, 0, packet); filled = packet }
                    continue
                }
                System.arraycopy(buffer, at, buffer, 0, filled - at); filled -= at; synced = true
            }
            val whole = filled / packet * packet
            if (whole == 0) continue
            try { ring.write(buffer, 0, whole) } catch (_: IOException) { return if (stopped()) null else LocalTimeshiftFailure.STORAGE }
            System.arraycopy(buffer, whole, buffer, 0, filled - whole); filled -= whole
            if (!ring.finalized) {
                val spent = elapsed() - started
                if (spent >= LocalTimeshiftSizing.MEASURE_MILLIS) {
                    val bits = LocalTimeshiftSizing.bitrate(ring.head, spent) ?: return LocalTimeshiftFailure.ENDED
                    bitsPerSecond = bits
                    val capacity = LocalTimeshiftSizing.capacity(length, bits, usable() + ring.head) ?: return LocalTimeshiftFailure.NO_SPACE
                    ring.resize(capacity)
                }
            }
            if (ring.head < ring.capacity && ring.head - checked >= SPACE_CHECK_BYTES) {
                checked = ring.head
                if (usable() < LocalTimeshiftSizing.SAFETY_BYTES / 2) return LocalTimeshiftFailure.NO_SPACE
            }
        }
        return null
    }

    private companion object { const val SPACE_CHECK_BYTES = 64L * 1024 * 1024 }
}
