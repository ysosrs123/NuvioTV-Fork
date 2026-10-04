package com.nuvio.tv.data.trailer

import androidx.media3.common.C

/**
 * Reads one stream as consecutive byte ranges. A chunk that comes back shorter than asked for
 * is the end of the stream; a chunk that fails to open is an error for the caller to handle.
 */
internal class YoutubeChunkReader(
    private val chunkSize: Long,
    private val openChunk: (start: Long, end: Long) -> Boolean,
    private val readChunk: (buffer: ByteArray, offset: Int, length: Int) -> Int,
    private val closeChunk: () -> Unit
) {
    private var remaining = C.LENGTH_UNSET.toLong()
    private var chunkStart = 0L
    private var chunkEnd = 0L
    private var bytesReadInChunk = 0L
    private var ended = false

    fun open(position: Long, length: Long) {
        chunkStart = position
        remaining = length
        ended = !openNextChunk()
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (ended) return C.RESULT_END_OF_INPUT

        val bytesRead = readChunk(buffer, offset, length)
        if (bytesRead != C.RESULT_END_OF_INPUT) {
            bytesReadInChunk += bytesRead
            return bytesRead
        }

        closeChunk()
        val chunkBytesReceived = bytesReadInChunk
        if (chunkBytesReceived < chunkEnd - chunkStart + 1) return end()

        chunkStart += chunkBytesReceived
        if (remaining != C.LENGTH_UNSET.toLong()) {
            remaining -= chunkBytesReceived
            if (remaining <= 0) return end()
        }

        if (!openNextChunk()) return end()
        val nextBytesRead = readChunk(buffer, offset, length)
        if (nextBytesRead == C.RESULT_END_OF_INPUT) {
            closeChunk()
            return end()
        }
        bytesReadInChunk += nextBytesRead
        return nextBytesRead
    }

    private fun openNextChunk(): Boolean {
        chunkEnd = if (remaining != C.LENGTH_UNSET.toLong()) {
            minOf(chunkStart + chunkSize - 1, chunkStart + remaining - 1)
        } else {
            chunkStart + chunkSize - 1
        }
        bytesReadInChunk = 0
        return openChunk(chunkStart, chunkEnd)
    }

    private fun end(): Int {
        ended = true
        return C.RESULT_END_OF_INPUT
    }
}
