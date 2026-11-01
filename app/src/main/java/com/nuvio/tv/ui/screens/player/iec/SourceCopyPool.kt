package com.nuvio.tv.ui.screens.player.iec

import java.nio.ByteBuffer

/**
 * Byte arrays for the copies of source access units kept for a replay. A copy goes back only once
 * its frame is written, when nothing else holds it; copies dropped any other way are left to the GC.
 */
internal class SourceCopyPool(private val maxPooled: Int = MAX_POOLED) {
    private val free = ArrayDeque<ByteArray>()

    val pooled: Int get() = free.size

    /** Copies the buffer's remaining bytes without moving its position. The array may be longer. */
    fun copyOf(buffer: ByteBuffer): ByteArray {
        val size = buffer.remaining()
        val head = free.removeFirstOrNull()
        val bytes = if (head != null && head.size >= size) head else ByteArray(roundUp(size))
        val position = buffer.position()
        buffer.get(bytes, 0, size)
        buffer.position(position)
        return bytes
    }

    fun recycle(bytes: ByteArray) {
        if (free.size < maxPooled) free.addLast(bytes)
    }

    fun clear() = free.clear()

    private fun roundUp(size: Int): Int = (size + STEP - 1) / STEP * STEP

    private companion object {
        const val STEP = 4096
        const val MAX_POOLED = 256
    }
}
