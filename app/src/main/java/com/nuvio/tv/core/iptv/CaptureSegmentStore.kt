package com.nuvio.tv.core.iptv

import java.io.*
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

data class CaptureSegment(val sequence: Long, val startMs: Long, val endMs: Long,
    val continuity: Long, val bytes: Long, internal val fileName: String)
data class CaptureBounds(val startMs: Long, val endExclusiveMs: Long)

class CaptureRetentionBlocked : IOException("Capture retention is pinned")

/**
 * Private, exclusively owned spool of COMPLETE segments supplied by a transport adapter.
 * Bounds describe actual committed media, never a player's configured load-ahead target.
 * One pending segment needs maxSegmentBytes of additional reserved disk beyond maxRetainedBytes.
 * This store does not establish codec/keyframe independence, capture transport or recording policy.
 */
class CaptureSegmentStore(private val directory: File, val maxRetainedBytes: Long,
    val maxSegmentBytes: Long) : AutoCloseable {
    private val channel: FileChannel
    private val lock: FileLock
    private var closed = false
    private var nextSequence = 0L
    private var segments = emptyList<CaptureSegment>()
    private val pins = mutableMapOf<String, Long>()
    private val index = File(directory, "index.bin")

    init {
        require(maxRetainedBytes in 1..(Long.MAX_VALUE / 4) && maxSegmentBytes in 1..maxRetainedBytes)
        require(!Files.isSymbolicLink(directory.toPath()))
        if (!directory.exists()) check(directory.mkdirs())
        require(directory.isDirectory)
        val marker = File(directory, "owner-v1")
        if (!marker.exists()) {
            require(directory.listFiles()?.isEmpty() == true) { "Capture directory must be empty" }
            FileOutputStream(marker).use { it.write(MARKER.toByteArray()); it.fd.sync() }
        }
        require(!Files.isSymbolicLink(marker.toPath()) && marker.readText() == MARKER)
        require(!Files.isSymbolicLink(File(directory, "lock").toPath()))
        channel = FileChannel.open(File(directory, "lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        lock = try { requireNotNull(channel.tryLock()) { "Capture directory is already open" } }
        catch (failure: Exception) { channel.close(); throw failure }
        try { recover(); cleanOrphans() } catch (failure: Exception) { lock.release(); channel.close(); throw failure }
    }

    @Synchronized fun snapshot(): List<CaptureSegment> { checkOpen(); return segments.toList() }

    /** Retain this segment AND its successors until the consumer releases its pause/recording anchor. */
    @Synchronized fun pinFrom(sequence: Long): AutoCloseable {
        checkOpen(); require(segments.any { it.sequence == sequence })
        val id = UUID.randomUUID().toString(); pins[id] = sequence
        return AutoCloseable { synchronized(this) { pins.remove(id) } }
    }

    /** Reader lifetime pins retention too; releasing a separate pause anchor cannot evict an open reader. */
    @Synchronized fun open(sequence: Long): InputStream {
        checkOpen()
        val segment = segments.single { it.sequence == sequence }
        val pin = pinFrom(sequence)
        try {
            val file = managedFile(segment.fileName)
            check(file.length() == segment.bytes) { "Incomplete capture segment" }
            return object : FilterInputStream(FileInputStream(file)) {
                private var ended = false
                override fun close() {
                    if (!ended) { super.close(); ended = true; pin.close() }
                }
            }
        } catch (failure: Exception) { pin.close(); throw failure }
    }

    /** Input stays caller-owned. A failed/cancelled append cannot publish partial media or evict old rows. */
    @Synchronized fun append(startMs: Long, endMs: Long, continuity: Long, input: InputStream,
        checkCancellation: () -> Unit = {}): CaptureSegment {
        checkOpen()
        require(startMs >= 0 && endMs > startMs && continuity >= 0)
        segments.lastOrNull()?.let { require(startMs >= it.endMs && continuity >= it.continuity) }
        check(nextSequence < Long.MAX_VALUE)
        cleanOrphans()
        val id = UUID.randomUUID().toString()
        val pending = managedFile("pending-$id")
        val complete = managedFile("segment-$id.bin")
        var committed = false
        try {
            var count = 0L
            FileOutputStream(pending).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancellation()
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), maxSegmentBytes - count + 1).toInt())
                    if (read < 0) break
                    if (read == 0) throw IOException("Capture source made no progress")
                    count += read
                    if (count > maxSegmentBytes) throw IOException("Capture segment byte limit")
                    out.write(buffer, 0, read)
                }
                if (count == 0L) throw IOException("Empty capture segment")
                out.fd.sync()
            }
            val keep = segments.toMutableList()
            var bytes = keep.sumOf { it.bytes }
            while (bytes > maxRetainedBytes - count || keep.size >= MAX_SEGMENTS) {
                val oldest = keep.first()
                if (pins.values.any { it <= oldest.sequence }) throw CaptureRetentionBlocked()
                keep.removeAt(0); bytes -= oldest.bytes
            }
            val segment = CaptureSegment(nextSequence, startMs, endMs, continuity, count, complete.name)
            checkCancellation()
            Files.move(pending.toPath(), complete.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val next = keep + segment
            checkCancellation()
            persist(next, nextSequence + 1)
            segments = next; nextSequence++; committed = true
            // Deletion is after index promotion; a crash leaves removable orphans, never missing indexed rows.
            // The commit is already authoritative. Do not report a failed append if retiring an
            // old file fails; the NEXT append must clear those orphans before allocating more.
            try { cleanOrphans() } catch (_: IOException) { }
            return segment
        } finally {
            pending.delete()
            if (!committed) complete.delete()
        }
    }

    /** Latest uninterrupted range only. Adapters must still establish decoder/keyframe-safe seek points. */
    @Synchronized fun contiguousBounds(): CaptureBounds? {
        checkOpen(); val last = segments.lastOrNull() ?: return null
        var start = last.startMs
        for (previous in segments.dropLast(1).asReversed()) {
            if (previous.endMs != start || previous.continuity != last.continuity) break
            start = previous.startMs
        }
        return CaptureBounds(start,last.endMs)
    }

    @Synchronized override fun close() {
        if (closed) return
        check(pins.isEmpty()) { "Capture consumers are still open" }
        lock.release(); channel.close(); closed = true
    }

    private fun checkOpen() = check(!closed) { "Capture store has closed" }
    private fun managedFile(name: String): File = File(directory, name).also {
        require(name == "index.new" || name == "index.bin" || SEGMENT.matches(name) || PENDING.matches(name))
        require(!Files.isSymbolicLink(it.toPath())) { "Capture path cannot be a link" }
    }
    private fun recover() {
        if (!index.exists()) return
        managedFile(index.name)
        require(index.length() in 1..MAX_INDEX_BYTES)
        DataInputStream(BufferedInputStream(FileInputStream(index))).use { data ->
            require(data.readInt() == MAGIC)
            nextSequence = data.readLong(); require(nextSequence >= 0)
            val count = data.readInt(); require(count in 0..MAX_SEGMENTS)
            var recoveredBytes = 0L
            segments = (0 until count).map {
                CaptureSegment(data.readLong(), data.readLong(), data.readLong(), data.readLong(), data.readLong(), data.readUTF()).also { row ->
                    require(row.sequence >= 0 && row.sequence < nextSequence && row.startMs >= 0 && row.endMs > row.startMs && row.continuity >= 0)
                    require(row.bytes in 1..maxSegmentBytes && SEGMENT.matches(row.fileName))
                    require(row.bytes <= maxRetainedBytes - recoveredBytes)
                    recoveredBytes += row.bytes
                    require(managedFile(row.fileName).let { it.isFile && it.length() == row.bytes })
                }
            }
            require(data.read() == -1 && segments.sumOf { it.bytes } <= maxRetainedBytes)
            require(segments.map { it.fileName }.distinct().size == segments.size)
            require(segments.zipWithNext().all { (a,b) -> a.sequence < b.sequence && a.endMs <= b.startMs && a.continuity <= b.continuity })
        }
    }
    private fun persist(rows: List<CaptureSegment>, next: Long) {
        val pending = managedFile("index.new")
        FileOutputStream(pending).use { raw ->
            val out = DataOutputStream(raw)
            out.writeInt(MAGIC); out.writeLong(next); out.writeInt(rows.size)
            rows.forEach { out.writeLong(it.sequence); out.writeLong(it.startMs); out.writeLong(it.endMs); out.writeLong(it.continuity); out.writeLong(it.bytes); out.writeUTF(it.fileName) }
            out.flush(); raw.fd.sync()
        }
        Files.move(pending.toPath(), index.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun cleanOrphans() {
        val kept = segments.map { it.fileName }.toSet()
        directory.listFiles().orEmpty().forEach { file ->
            val name = file.name
            if (name == "index.new" || PENDING.matches(name) || (SEGMENT.matches(name) && name !in kept)) {
                managedFile(name)
                if (!file.delete()) throw IOException("Capture cleanup failed")
            } else require(name in setOf("owner-v1", "lock", "index.bin") || name in kept) { "Unexpected capture directory entry" }
        }
    }
    private companion object {
        const val MAGIC = 0x4e435331
        const val MARKER = "Nuvio private capture spool v1\n"
        const val MAX_SEGMENTS = 4096
        const val MAX_INDEX_BYTES = 1024L * 1024
        val SEGMENT = Regex("segment-[0-9a-f-]{36}\\.bin")
        val PENDING = Regex("pending-[0-9a-f-]{36}")
    }
}
