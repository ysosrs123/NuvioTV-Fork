package com.nuvio.tv.core.iptv

import java.io.IOException
import java.io.InputStream

/** Finite local bytes, pinned until close. Segment time metadata is not a keyframe/codec guarantee. */
class CaptureSnapshotReader internal constructor(
    val segments: List<CaptureSegment>,
    private val pin: AutoCloseable,
    private val openSegment: (Long) -> InputStream,
) : InputStream() {
    val bounds = CaptureBounds(segments.first().startMs, segments.last().endMs)
    val length: Long = segments.sumOf { it.bytes }
    private var index = 0
    private var current: InputStream? = null
    private var remaining = 0L
    private var closed = false

    @Synchronized override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 255
    }

    @Synchronized override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (closed) throw IOException("Capture reader has closed")
        if (offset < 0 || count < 0 || offset > buffer.size - count) throw IndexOutOfBoundsException()
        if (count == 0) return 0
        while (index < segments.size) {
            if (current == null) {
                current = openSegment(segments[index].sequence)
                remaining = segments[index].bytes
            }
            if (remaining == 0L) {
                current!!.close(); current = null; index++
                continue
            }
            val read = current!!.read(buffer, offset, minOf(count.toLong(), remaining).toInt())
            if (read <= 0) throw IOException("Capture segment ended before its committed length")
            remaining -= read
            return read
        }
        return -1
    }

    @Synchronized override fun close() {
        if (closed) return
        // Keep the retention pin when file closure is uncertain, so the owner cannot free storage.
        current?.close(); current = null
        pin.close(); closed = true
    }
}
