package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.ByteBufferDataReader
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Some servers answer a request for the rest of a file with one capped piece, a 206 for 64 MB of a 5 GB file.
 * Taken as is, the end of that piece reads as the end of the file: the player marks every track finished and the
 * disk cache stores the short length. The Content-Range total shows how much is left, so the open reports the
 * length that was asked for and the rest is fetched with further requests.
 */
@UnstableApi
internal class RangeContinuationDataSource(private val factory: DataSource.Factory) : DataSource, ByteBufferDataReader {

    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null
    private var spec: DataSpec? = null
    private var position = 0L
    private var endExclusive = C.LENGTH_UNSET.toLong()
    private var scratch: ByteArray? = null
    private var total = C.LENGTH_UNSET.toLong()
    private var capLogged = false

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        position = dataSpec.position
        endExclusive = C.LENGTH_UNSET.toLong()
        val source = create()
        current = source
        val length = source.open(dataSpec)
        val end = continuationEnd(dataSpec, length, source.responseHeaders)
        if (end == C.LENGTH_UNSET.toLong()) return length
        endExclusive = end
        total = contentRange(source.responseHeaders)?.total ?: C.LENGTH_UNSET.toLong()
        if (!capLogged) {
            capLogged = true
            Log.i(TAG, "RANGE_CAP: server sent a capped piece at ${dataSpec.position}, reading on to $end")
        }
        return end - dataSpec.position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var continued = false
        while (true) {
            val source = current ?: throw IOException("Not open")
            val read = source.read(buffer, offset, length)
            if (read != C.RESULT_END_OF_INPUT) {
                if (read > 0) position += read
                return read
            }
            if (endExclusive == C.LENGTH_UNSET.toLong() || position >= endExclusive) return read
            if (continued) throw IOException("Server sent nothing at $position of $endExclusive")
            continueAt(position)
            continued = true
        }
    }

    override fun supportsByteBufferRead(): Boolean = current.let { it is ByteBufferDataReader && it.supportsByteBufferRead() }

    override fun read(buffer: ByteBuffer, length: Int): Int {
        if (length == 0) return 0
        var continued = false
        while (true) {
            val source = current ?: throw IOException("Not open")
            val read = if (source is ByteBufferDataReader && source.supportsByteBufferRead()) {
                source.read(buffer, length)
            } else {
                val array = scratch ?: ByteArray(SCRATCH_BYTES).also { scratch = it }
                source.read(array, 0, minOf(length, array.size)).also { if (it > 0) buffer.put(array, 0, it) }
            }
            if (read != C.RESULT_END_OF_INPUT) {
                if (read > 0) position += read
                return read
            }
            if (endExclusive == C.LENGTH_UNSET.toLong() || position >= endExclusive) return read
            if (continued) throw IOException("Server sent nothing at $position of $endExclusive")
            continueAt(position)
            continued = true
        }
    }

    private fun continueAt(at: Long) {
        val open = checkNotNull(spec)
        val old = current
        current = null
        try {
            old?.close()
        } catch (_: IOException) {
        }
        val offset = at - open.position
        val nextSpec = if (open.length == C.LENGTH_UNSET.toLong()) {
            open.subrange(offset)
        } else {
            open.subrange(offset, endExclusive - at)
        }
        val next = create()
        current = next
        next.open(nextSpec)
        val range = contentRange(next.responseHeaders)
        if (range == null || range.start != at || range.total != total) {
            throw IOException("Server answered the piece at $at with ${range ?: "no range"}, expected a part of $total")
        }
    }

    private fun create(): DataSource = factory.createDataSource().also { source ->
        listeners.forEach(source::addTransferListener)
    }

    override fun getUri(): Uri? = current?.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        endExclusive = C.LENGTH_UNSET.toLong()
        val source = current
        current = null
        source?.close()
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = RangeContinuationDataSource(upstream)
    }

    companion object {
        private const val TAG = "RangeContinuation"
        private const val SCRATCH_BYTES = 64 * 1024
        private val CONTENT_RANGE = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$", RegexOption.IGNORE_CASE)

        /** Where the read should end when the response stops short of it, else C.LENGTH_UNSET. */
        internal data class ContentRange(val start: Long, val last: Long, val total: Long) {
            override fun toString() = "bytes $start-$last/$total"
        }

        internal fun contentRange(headers: Map<String, List<String>>): ContentRange? {
            val values = headers.entries.filter { it.key.equals("Content-Range", ignoreCase = true) }.flatMap { it.value }
            val match = CONTENT_RANGE.matchEntire(values.singleOrNull()?.trim() ?: return null) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val last = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].toLongOrNull() ?: return null
            return if (last < start || last >= total) null else ContentRange(start, last, total)
        }

        internal fun continuationEnd(spec: DataSpec, openedLength: Long, headers: Map<String, List<String>>): Long {
            val unset = C.LENGTH_UNSET.toLong()
            if (openedLength != unset && openedLength <= 0L) return unset
            val (start, last, total) = contentRange(headers) ?: return unset
            if (start != spec.position) return unset
            val wanted = if (spec.length == unset) total else minOf(total, spec.position + spec.length)
            return if (last + 1 < wanted) wanted else unset
        }
    }
}
