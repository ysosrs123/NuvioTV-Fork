package com.nuvio.tv.data.iptv

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.iptv.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class CaptureReaderState { NEW, LOADING, READY, EXPIRED, STALE, FAILED, CLEANUP_REQUIRED, CLOSING, CLOSED }

/**
 * One asynchronous, exact-request pinned reader consumer for SharedCaptureRuntime. Construction opens
 * nothing; start commits/stages off the caller thread. No decoder, player, network or polling loop.
 * The parent must admit staging/transient memory and retain its lease until close confirms true.
 * READY never acknowledges a seek. Borrowing requires the still-current exact request; the future
 * player must fence commands again, then stop/confirm its renderers BEFORE closing this reader.
 * A stale/cancelled load cannot publish. Failed/timed-out cleanup retains the pin/period and lease;
 * explicit close retries only after the sole existing worker/closer finishes. Never use as live EOF.
 * Injectable staging is an internal fixture seam and must transfer the input only on success.
 */
@UnstableApi
internal class PinnedCaptureReaderConsumer(
    private val seeks: CaptureSeekController,
    private val request: CaptureSeekRequest,
    private val limits: CaptureSampleStagingLimits,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val closeTimeoutMs: Long = 15_000,
    private val stage: (CaptureSeekInput, CaptureSampleStagingLimits, () -> Unit) -> PinnedCaptureSegmentPeriod =
        { input, bounds, cancellation -> PinnedCaptureSegmentPeriod.stage(input, bounds, cancellation) },
) : OwnedCaptureConsumer {
    init { require(closeTimeoutMs in 1..120_000) }
    private val closeMutex = Mutex()
    private val stateMutable = MutableStateFlow(CaptureReaderState.NEW)
    val state: StateFlow<CaptureReaderState> = stateMutable.asStateFlow()
    private var stopping = false
    private var worker: Job? = null
    private var cleanup: Job? = null
    private var input: CaptureSeekInput? = null
    private var period: PinnedCaptureSegmentPeriod? = null

    @Synchronized override fun start() {
        check(!stopping && worker == null)
        stateMutable.value = CaptureReaderState.LOADING
        worker = CoroutineScope(dispatcher).launch(start = CoroutineStart.LAZY) {
            var published = false
            var outcome = CaptureReaderState.FAILED
            try {
                val context = currentCoroutineContext()
                context.ensureActive()
                val committed = seeks.commit(request)
                val opened = committed.input
                if (opened == null) {
                    outcome = if (committed.state == CaptureSeekState.EXPIRED) CaptureReaderState.EXPIRED else CaptureReaderState.STALE
                } else {
                    synchronized(this@PinnedCaptureReaderConsumer) { input = opened }
                    context.ensureActive()
                    val staged = stage(opened, limits) { context.ensureActive() }
                    // Keep the returned owner BEFORE any cancellation or stale publication check.
                    synchronized(this@PinnedCaptureReaderConsumer) { period = staged; input = null }
                    context.ensureActive()
                    synchronized(this@PinnedCaptureReaderConsumer) {
                        if (!stopping && seeks.isCurrentCommitted(request)) {
                            stateMutable.value = CaptureReaderState.READY; published = true
                        } else outcome = CaptureReaderState.STALE
                    }
                }
            } catch (_: CancellationException) { outcome = CaptureReaderState.STALE }
            catch (_: Exception) { outcome = CaptureReaderState.FAILED }
            finally {
                if (!published) {
                    val confirmed = withContext(NonCancellable) { cleanupOwned() }
                    synchronized(this@PinnedCaptureReaderConsumer) {
                        if (!stopping) stateMutable.value = if (confirmed) outcome else CaptureReaderState.CLEANUP_REQUIRED
                    }
                }
            }
        }.also { it.start() }
    }

    /** Borrowed only; this consumer still owns closure. Does not prepare, select or acknowledge. */
    @Synchronized fun readyPeriod(expected: CaptureSeekRequest): PinnedCaptureSegmentPeriod? {
        if (expected !== request || stopping || stateMutable.value != CaptureReaderState.READY) return null
        if (!seeks.isCurrentCommitted(request)) {
            stateMutable.value = CaptureReaderState.STALE
            return null // Existing borrowed use must be fenced by its future player owner.
        }
        return period?.takeUnless { it.released }
    }

    override suspend fun close(): Boolean = closeMutex.withLock {
        val active = synchronized(this) {
            if (stateMutable.value == CaptureReaderState.CLOSED) return@withLock true
            stopping = true; stateMutable.value = CaptureReaderState.CLOSING; worker
        }
        active?.cancel()
        val confirmed = withTimeoutOrNull(closeTimeoutMs) {
            active?.join() // Never close a file while the sole staging worker might still read it.
            val closing = synchronized(this@PinnedCaptureReaderConsumer) {
                cleanup?.takeUnless { it.isCompleted } ?: CoroutineScope(dispatcher).launch(start = CoroutineStart.LAZY) {
                    cleanupOwned()
                }.also { cleanup = it; it.start() }
            }
            closing.join()
            synchronized(this@PinnedCaptureReaderConsumer) { input == null && period == null }
        } ?: false
        if (confirmed) synchronized(this) { stateMutable.value = CaptureReaderState.CLOSED }
        confirmed
    }

    private fun cleanupOwned(): Boolean {
        val handles = synchronized(this) { period to input }
        return try {
            handles.first?.let { it.close(); check(it.released) }
            handles.second?.let { it.close(); check(it.media.isClosed) }
            synchronized(this) {
                if (period === handles.first) period = null
                if (input === handles.second) input = null
            }
            true
        } catch (_: Exception) { false } // Keep exact handles for explicit retry; no error URL leakage.
    }
}
