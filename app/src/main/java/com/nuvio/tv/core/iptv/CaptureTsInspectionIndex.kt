package com.nuvio.tv.core.iptv

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class CaptureMediaExpired : IOException("Inspected capture media is no longer retained")

/** Instance-scoped structural evidence for one committed UUID-backed row. Not a decoder certificate. */
class InspectedCaptureSegment internal constructor(internal val owner: Any,
    val segment: CaptureSegment, val inspection: TsCaptureInspection)

/**
 * One bounded inspector at a time; file reads run outside the store/state monitors. Cache entries
 * hold no retention pins. Every open atomically checks the exact row and pins its file/successors.
 * Evidence is ephemeral and belongs to this index/store instance, never a reopened spool.
 */
class CaptureTsInspectionIndex(private val store: CaptureSegmentStore,
    private val inspector: TsCaptureInspector = TsCaptureInspector(), private val maxEntries: Int = 256) {
    private val owner = Any()
    private val inspectionMutex = Any()
    private val cache = LinkedHashMap<CaptureSegment, InspectedCaptureSegment>(16, 0.75f, true)
    init { require(maxEntries in 1..4096) }

    fun inspect(sequence: Long, checkCancellation: () -> Unit = {}): InspectedCaptureSegment = synchronized(inspectionMutex) {
        checkCancellation()
        val (row, input, retained) = synchronized(store) {
            val rows = store.snapshot()
            val row = rows.singleOrNull { it.sequence == sequence } ?: throw CaptureMediaExpired()
            Triple(row, store.open(sequence), rows.toSet())
        }
        input.use {
            val cached = synchronized(cache) { prune(retained); cache[row] }
            checkCancellation()
            if (cached != null) return@synchronized cached
            val result = inspector.inspect(input, checkCancellation)
            if (result.bytes != row.bytes) throw IOException("Capture inspection length differs from committed row")
            checkCancellation()
            val proof = InspectedCaptureSegment(owner, row, result)
            synchronized(cache) {
                cache[row] = proof
                while (cache.size > maxEntries) cache.remove(cache.keys.first())
            }
            proof
        }
    }

    fun open(proof: InspectedCaptureSegment): InspectedCaptureInput {
        require(proof.owner === owner) { "Capture inspection belongs to another owner" }
        val raw = synchronized(store) {
            if (store.snapshot().none { it == proof.segment }) throw CaptureMediaExpired()
            store.open(proof.segment.sequence)
        }
        return InspectedCaptureInput(proof, raw)
    }

    internal fun retainedSegments(): Set<CaptureSegment> = store.snapshot().toSet()

    fun retainedInspections(): List<InspectedCaptureSegment> {
        val retained = retainedSegments()
        return synchronized(cache) { prune(retained); cache.values.toList() }
    }

    private fun prune(retained: Set<CaptureSegment>) { cache.keys.retainAll(retained) }
}

/**
 * Caller-owned pinned input. Bytes remain tentative until verified EOF; early close/cancellation
 * releases the reader without certifying it. Length/hash are checked again during every complete
 * read, including skip. Mark/reset are unsupported. No automatic retention release at EOF.
 */
class InspectedCaptureInput internal constructor(val proof: InspectedCaptureSegment,
    private val raw: InputStream) : InputStream() {
    val segment get() = proof.segment
    val inspection get() = proof.inspection
    private val digest = MessageDigest.getInstance("SHA-256")
    private var count = 0L
    private var closed = false
    private var finished = false
    private var rejected = false
    val verified: Boolean get() = synchronized(this) { finished }

    @Synchronized override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
    }

    @Synchronized override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "Inspected capture input is closed" }
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        if (rejected) throw IOException("Capture input previously failed verification")
        if (length == 0) return 0
        if (finished) return -1
        val n = raw.read(bytes, offset, minOf(length.toLong(), inspection.bytes - count + 1).toInt())
        if (n == 0) reject()
        if (n > 0) {
            count += n
            if (count > inspection.bytes) reject()
            digest.update(bytes, offset, n)
        } else {
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (count != inspection.bytes || hash != inspection.sha256) reject()
            finished = true
        }
        return n
    }

    @Synchronized override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val buffer = ByteArray(8192); var skipped = 0L
        while (skipped < n) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
            if (read < 0) break
            skipped += read
        }
        return skipped
    }

    @Synchronized override fun close() {
        if (closed) return
        raw.close(); closed = true
    }

    private fun reject(): Nothing { rejected = true; throw IOException("Capture bytes differ from inspection") }
}
