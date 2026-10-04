package com.nuvio.tv.core.network

import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** Consumed response payload, including skips/probes/retries; not bytes on the wire. */
internal class DiagnosticPayloadBudget(
    val limit: Long,
    private val nanoTime: () -> Long = System::nanoTime
) {
    init { require(limit > 0) }
    data class Snapshot(val bytes: Long, val nanos: Long)
    class Exhausted : IOException("Diagnostic payload limit reached")
    private var consumed = 0L
    private var reserved = 0L
    private var stopped = false
    @Synchronized fun snapshot() = Snapshot(consumed, nanoTime())
    @Synchronized fun stop() { stopped = true }
    @Synchronized private fun reserve(wanted: Long): Long {
        if (stopped) throw IOException("Diagnostic payload reads stopped")
        if (consumed + reserved == limit) throw Exhausted()
        return minOf(wanted, limit - consumed - reserved).also { reserved += it }
    }
    @Synchronized private fun settle(allowed: Long, actual: Long) {
        reserved -= allowed
        consumed += actual.coerceIn(0L, allowed)
    }
    fun wrap(body: ResponseBody): ResponseBody = object : ResponseBody() {
        private val counted = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                require(byteCount >= 0)
                if (byteCount == 0L) return 0
                val allowed = reserve(byteCount)
                val before = sink.size
                try { return super.read(sink, allowed) }
                finally { settle(allowed, sink.size - before) }
            }
        }.buffer()
        override fun source() = counted
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
    }
}
