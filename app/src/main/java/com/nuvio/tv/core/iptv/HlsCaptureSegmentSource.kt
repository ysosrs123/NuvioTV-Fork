package com.nuvio.tv.core.iptv

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Dedicated transport; one body at a time. close fences/cancels connects and confirms body closure. */
interface HlsCaptureHttp {
    suspend fun open(address: URI, maxBytes: Long): InputStream
    suspend fun close(): Boolean
}

/**
 * Sequential bounded HLS ingestion. Starts at the oldest currently advertised segment, then follows
 * that one media playlist. Lost sequence/changed overlap stops capture; there is no hidden gap,
 * master/rendition fallback, failed-request retry or decoder-safety claim. Construction opens nothing.
 */
class HlsCaptureSegmentSource(private val address: URI, private val http: HlsCaptureHttp,
    private val maxSegmentBytes: Long, private val parser: HlsCapturePlaylistParser = HlsCapturePlaylistParser(),
    private val stallTimeoutMs: Long = 60_000,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : CaptureSegmentSource {
    init { require(maxSegmentBytes > 0 && stallTimeoutMs > 0); parser.validateAddress(address) }
    private val pull = Mutex()
    @Volatile private var stopping = false
    private var owner: Job? = null
    private var playlist: HlsCapturePlaylist? = null
    private var lastLoadMs = 0L
    private var reloadWaitMs = 0L
    private var nextSequence: Long? = null
    private var endMs = 0L
    private var lastBody: TrackedBody? = null
    private var bodyDelivered = false
    private var cleanup: Job? = null

    override suspend fun next(): CaptureInput? = pull.withLock {
        val job = currentCoroutineContext()[Job]
        synchronized(this) {
            if (stopping) throw HlsCaptureException(HlsCaptureFailure.CLOSED)
            check(lastBody == null) { "Close the previous HLS capture body before pulling" }
            owner = job
        }
        try {
            val started = monotonicMs()
            while (true) {
                currentCoroutineContext().ensureActive()
                if (stopping) throw HlsCaptureException(HlsCaptureFailure.CLOSED)
                val loaded = playlist
                val sequence = nextSequence
                val segment = if (sequence == null) loaded?.segments?.firstOrNull()
                    else loaded?.segments?.firstOrNull { it.sequence == sequence }
                if (segment != null) {
                    val end = try { Math.addExact(endMs, segment.durationMs) }
                        catch (_: ArithmeticException) { throw HlsCaptureException(HlsCaptureFailure.LIMIT) }
                    val input = http.open(segment.address, maxSegmentBytes)
                    val body = TrackedBody(input)
                    // Install even a late result so close/transport cleanup can account for it.
                    synchronized(this) { lastBody = body; bodyDelivered = false }
                    try {
                        currentCoroutineContext().ensureActive()
                        if (stopping) throw HlsCaptureException(HlsCaptureFailure.CLOSED)
                    } catch (error: Exception) { body.close(); throw error }
                    val result = CaptureInput(endMs, end, segment.discontinuity, body)
                    nextSequence = segment.sequence + 1; endMs = end
                    synchronized(this) { bodyDelivered = true }
                    return@withLock result
                }
                if (loaded?.ended == true) return@withLock null
                if (loaded != null) {
                    val remaining = stallTimeoutMs - (monotonicMs() - started)
                    val due = (lastLoadMs + reloadWaitMs - monotonicMs()).coerceAtLeast(0)
                    if (remaining <= 0 || due >= remaining) throw HlsCaptureException(HlsCaptureFailure.STALLED)
                    if (due > 0) wait(due)
                }
                reload()
                if (nextSequence == null) nextSequence = requireNotNull(playlist).mediaSequence
            }
            @Suppress("UNREACHABLE_CODE") null
        } catch (error: Exception) {
            stopping = true
            if (error is CancellationException || error is HlsCaptureException) throw error
            throw HlsCaptureException(HlsCaptureFailure.NETWORK)
        } finally { synchronized(this) { owner = null } }
    }

    private suspend fun reload() {
        val started = monotonicMs()
        val metadata = TrackedBody(http.open(address, parser.maxBytes.toLong()))
        synchronized(this) { lastBody = metadata; bodyDelivered = false }
        val bytes = metadata.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val size = input.read(buffer, 0, minOf(buffer.size, parser.maxBytes - out.size() + 1))
                if (size < 0) break
                if (size == 0 || out.size() + size > parser.maxBytes) throw HlsCaptureException(HlsCaptureFailure.LIMIT)
                out.write(buffer, 0, size)
            }
            out.toByteArray()
        }
        currentCoroutineContext().ensureActive()
        val next = parser.parse(bytes, address)
        val previous = playlist
        if (previous != null) {
            if (next.targetMs != previous.targetMs || next.mediaSequence < previous.mediaSequence ||
                next.segments.last().sequence < previous.segments.last().sequence) {
                throw HlsCaptureException(HlsCaptureFailure.CHANGED_SEGMENT)
            }
            val before = previous.segments.associateBy { it.sequence }
            if (next.segments.any { before[it.sequence]?.let { old -> old != it } == true }) {
                throw HlsCaptureException(HlsCaptureFailure.CHANGED_SEGMENT)
            }
            val expected = requireNotNull(nextSequence)
            if (next.mediaSequence > expected) throw HlsCaptureException(HlsCaptureFailure.EXPIRED)
            if (next.segments.first().discontinuity < previous.segments.first().discontinuity) {
                throw HlsCaptureException(HlsCaptureFailure.CHANGED_SEGMENT)
            }
            if (next.mediaSequence > previous.segments.last().sequence &&
                next.segments.first().discontinuity < previous.segments.last().discontinuity) {
                throw HlsCaptureException(HlsCaptureFailure.CHANGED_SEGMENT)
            }
        }
        playlist = next
        lastLoadMs = started
        reloadWaitMs = if (next == previous) next.targetMs / 2 else next.targetMs
    }

    override suspend fun close(): Boolean {
        val caller = currentCoroutineContext()[Job]
        val active = synchronized(this) { stopping = true; owner }
        if (active !== caller) active?.cancel()
        val retry = synchronized(this) {
            val body = lastBody
            if (owner != null || body == null || bodyDelivered) null else {
                cleanup?.takeUnless { it.isCompleted } ?: CoroutineScope(Dispatchers.IO).launch(start = CoroutineStart.LAZY) {
                    try { body.close() } catch (_: Exception) { }
                }.also { cleanup = it; it.start() }
            }
        }
        if (retry != null && withTimeoutOrNull(5000) { retry.join(); true } != true) return false
        // Transport is responsible for closing the handed-out body; do not race its store read.
        val confirmed = http.close()
        return confirmed && synchronized(this) { owner == null && lastBody == null }
    }

    private inner class TrackedBody(private val input: InputStream) : InputStream() {
        private var closed = false
        override fun read(): Int = input.read()
        override fun read(bytes: ByteArray, offset: Int, length: Int) = input.read(bytes, offset, length)
        @Synchronized override fun close() {
            if (closed) return
            input.close() // Failure retains this handle for an explicit close retry.
            closed = true
            synchronized(this@HlsCaptureSegmentSource) { if (lastBody === this) lastBody = null }
        }
    }
}
