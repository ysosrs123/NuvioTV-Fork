package com.nuvio.tv.core.iptv

import java.io.InputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Complete segment supplied by a protocol adapter. Times/continuity alone do not prove decodability. */
data class CaptureInput(val startMs: Long, val endMs: Long, val continuity: Long, val body: InputStream)

/**
 * next opens at most one body and performs no speculative prefetch/retries. It must be cancellable or
 * be unblocked by close. close fences late opens and confirms all connecting/active upstream work is
 * closed. close must be idempotent, safe alongside next/close, and honour coroutine cancellation.
 * A protocol adapter owns those guarantees; this interface does not implement HTTP or HLS.
 */
interface CaptureSegmentSource {
    suspend fun next(): CaptureInput?
    suspend fun close(): Boolean
}

enum class CaptureTransportState { NEW, RUNNING, COMPLETE, BACKPRESSURE, FAILED, CLOSED }

/** One pull/append/body at a time, with no queued segments. Store byte limits apply during streaming. */
class SegmentCaptureTransport(private val store: CaptureSegmentStore, private val source: CaptureSegmentSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO, private val closeTimeoutMs: Long = 15_000,
) : OwnedCaptureTransport {
    init { require(closeTimeoutMs > 0) }
    private val closeMutex = Mutex()
    private val stateMutable = MutableStateFlow(CaptureTransportState.NEW)
    val state: StateFlow<CaptureTransportState> = stateMutable.asStateFlow()
    private var worker: Job? = null
    private var stopping = false
    private var body: InputStream? = null
    private var bodyCleanup: Job? = null

    @Synchronized override fun start() {
        check(!stopping && worker == null)
        stateMutable.value = CaptureTransportState.RUNNING
        // The worker is the only owner of next/append/body closure. The source handles cancellation
        // of its network work; close never concurrently closes a file being read by append.
        worker = CoroutineScope(dispatcher).launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val next = source.next() ?: break
                    synchronized(this@SegmentCaptureTransport) { body = next.body }
                    try {
                        val context = currentCoroutineContext()
                        context.ensureActive()
                        store.append(next.startMs, next.endMs, next.continuity, next.body) { context.ensureActive() }
                    } finally { closeBody() }
                }
                stateMutable.value = CaptureTransportState.COMPLETE
            } catch (_: CaptureRetentionBlocked) {
                stateMutable.value = CaptureTransportState.BACKPRESSURE
            } catch (_: CancellationException) {
                // Explicit close owns the terminal CLOSED state, after actual closure confirmation.
            } catch (_: Exception) {
                stateMutable.value = CaptureTransportState.FAILED
            } finally {
                // Stop upstream even at EOF/backpressure/failure. Reservations remain runtime-owned.
                withContext(NonCancellable) {
                    try { source.close() } catch (_: Exception) { }
                }
            }
        }.also { it.start() }
    }

    override suspend fun close(): Boolean = closeMutex.withLock {
        if (stateMutable.value == CaptureTransportState.CLOSED) return@withLock true
        val active = synchronized(this) { stopping = true; worker }
        active?.cancel()
        // Must also close before joining: a blocking source read may need its request cancelled.
        val confirmed = try {
            withTimeoutOrNull(closeTimeoutMs) {
                if (!source.close()) return@withTimeoutOrNull false
                active?.join()
                cleanupBody()?.join()
                synchronized(this@SegmentCaptureTransport) { body == null }
            } ?: false
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { false }
        if (confirmed) stateMutable.value = CaptureTransportState.CLOSED
        confirmed
    }

    private fun cleanupBody(): Job? = synchronized(this) {
        if (body == null) return@synchronized null
        // Retry a failed close off the caller thread. A timed-out cleanup remains the sole closer;
        // a later retry waits for it instead of concurrently closing the same body again.
        bodyCleanup?.takeUnless { it.isCompleted } ?: CoroutineScope(dispatcher).launch(start = CoroutineStart.LAZY) {
            try { closeBody() } catch (_: Exception) { }
        }.also { bodyCleanup = it; it.start() }
    }

    private fun closeBody() {
        val closing = synchronized(this) { body } ?: return
        // Never hold the state monitor through I/O: close must be able to cancel the source and
        // time out waiting for this worker even when a body's close is blocked.
        closing.close()
        synchronized(this) {
            if (body === closing) body = null // On failure retain it for an explicit cleanup retry.
        }
    }
}
