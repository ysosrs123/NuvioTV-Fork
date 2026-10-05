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
    val maxSegmentBytes: Long, storagePolicy: CaptureStoragePolicy? = null) : AutoCloseable {
    private val storageFence: CaptureStorageFence?
    val minimumStorageOverheadBytes: Long? get() = storageFence?.overhead(MAX_INDEX_BYTES, MAX_SEGMENTS)
    private val channel: FileChannel
    private val lock: FileLock
    private val appendMutex = Any()
    private var appending = false
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
        storageFence = storagePolicy?.bind(directory)
        val marker = File(directory, "owner-v1")
        if (!marker.exists()) {
            require(directory.listFiles()?.isEmpty() == true) { "Capture directory must be empty" }
            storageFence?.growth(0, MARKER.toByteArray().size.toLong(), MAX_INDEX_BYTES)
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

    /** Governed admission requires physical observations, margins and allocation/index overhead.
     * This is an observation fence, never an OS reservation; repeated write checks and I/O still apply.
     * Legacy direct stores may omit a guard for isolated byte fixtures; runtime sharing cannot.
     */
    @Synchronized fun checkStorageReservation(plan: CaptureStorageReservation) {
        checkOpen()
        require(plan.retainedBytes == maxRetainedBytes && plan.segmentBytes == maxSegmentBytes)
        val fence = storageFence ?: throw CaptureStorageUnavailable(CaptureStorageFailure.UNGUARDED)
        val overhead = requireNotNull(minimumStorageOverheadBytes)
        if (plan.overheadBytes < overhead) throw CaptureStorageUnavailable(CaptureStorageFailure.RESERVATION_TOO_SMALL)
        val remaining = Math.addExact(maxRetainedBytes - segments.sumOf { it.bytes }, maxSegmentBytes)
        fence.admission(remaining, plan.overheadBytes)
    }

    /**
     * Atomically pin a fixed, uninterrupted snapshot starting at an existing segment. New appends
     * are outside this reader, so EOF means snapshot completion, not a temporarily empty live tail.
     * This is a byte reader for capture/export adapters, not a claim of decoder-safe seeking.
     */
    @Synchronized fun openSnapshotFrom(sequence: Long): CaptureSnapshotReader {
        checkOpen()
        val start = segments.indexOfFirst { it.sequence == sequence }
        require(start >= 0) { "Capture segment is no longer retained" }
        val selected = mutableListOf(segments[start])
        for (next in segments.drop(start + 1)) {
            val previous = selected.last()
            if (next.startMs != previous.endMs || next.continuity != previous.continuity) break
            selected += next
        }
        return CaptureSnapshotReader(selected.toList(), pinFrom(sequence), ::open)
    }

    /** Lazy live-tail byte reader. This API does not certify decoder-safe starts or seek times. */
    @Synchronized fun openLiveFrom(sequence: Long, producerState: () -> CaptureTransportState): CaptureLiveReader {
        checkOpen(); require(sequence in 0..nextSequence)
        return CaptureLiveReader(this, sequence, producerState)
    }

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

    /**
     * Input stays caller-owned. Source reads and segment sync run outside the state monitor so local
     * readers/pins remain available. One writer owns staging; publication rechecks current pins.
     * A failed/cancelled append cannot publish partial media or evict old rows.
     */
    fun append(startMs: Long, endMs: Long, continuity: Long, input: InputStream,
        checkCancellation: () -> Unit = {}): CaptureSegment = synchronized(appendMutex) {
        val id = UUID.randomUUID().toString()
        val pending = managedFile("pending-$id")
        val complete = managedFile("segment-$id.bin")
        synchronized(this) {
            checkOpen()
            require(startMs >= 0 && endMs > startMs && continuity >= 0)
            segments.lastOrNull()?.let { require(startMs >= it.endMs && continuity >= it.continuity) }
            check(nextSequence < Long.MAX_VALUE)
            cleanOrphans()
            appending = true // close cannot release the directory lock while this writer is outside the monitor.
        }
        var committed = false
        try {
            var count = 0L
            storageFence?.growth(0, maxSegmentBytes, MAX_INDEX_BYTES)
            FileOutputStream(pending).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancellation()
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), maxSegmentBytes - count + 1).toInt())
                    if (read < 0) break
                    if (read == 0) throw IOException("Capture source made no progress")
                    val nextCount = count + read
                    if (nextCount > maxSegmentBytes) throw IOException("Capture segment byte limit")
                    storageFence?.growth(count, nextCount, MAX_INDEX_BYTES)
                    out.write(buffer, 0, read)
                    count = nextCount
                }
                if (count == 0L) throw IOException("Empty capture segment")
                out.fd.sync()
                storageFence?.growth(count, count, MAX_INDEX_BYTES)
            }
            synchronized(this) {
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
                segment
            }
        } finally {
            pending.delete()
            if (!committed) complete.delete()
            synchronized(this) { appending = false }
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
        check(!appending) { "Capture append is still active" }
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
        storageFence?.growth(0, MAX_INDEX_BYTES)
        FileOutputStream(pending).use { raw ->
            val out = DataOutputStream(raw)
            out.writeInt(MAGIC); out.writeLong(next); out.writeInt(rows.size)
            rows.forEach { out.writeLong(it.sequence); out.writeLong(it.startMs); out.writeLong(it.endMs); out.writeLong(it.continuity); out.writeLong(it.bytes); out.writeUTF(it.fileName) }
            out.flush(); raw.fd.sync()
        }
        storageFence?.growth(0, 0)
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
