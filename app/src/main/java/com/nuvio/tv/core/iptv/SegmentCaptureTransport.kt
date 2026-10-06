package com.nuvio.tv.core.iptv

import java.io.InputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CaptureInput(val startMs: Long, val endMs: Long, val continuity: Long, val body: InputStream)

interface CaptureSegmentSource {
    suspend fun next(): CaptureInput?
    suspend fun close(): Boolean
}

enum class CaptureTransportState { NEW, RUNNING, COMPLETE, BACKPRESSURE, STORAGE_BLOCKED, FAILED, CLOSED }

class SegmentCaptureTransport(private val store: CaptureSegmentStore, private val source: CaptureSegmentSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO, private val closeTimeoutMs: Long = 15_000,
) : OwnedCaptureTransport {
    init { require(closeTimeoutMs > 0) }
    private val closeMutex = Mutex()
    private val stateMutable = MutableStateFlow(CaptureTransportState.NEW)
    val state: StateFlow<CaptureTransportState> = stateMutable.asStateFlow()
    private val committedMutable = MutableStateFlow<Long?>(null)
    val committedSequence: StateFlow<Long?> = committedMutable.asStateFlow()
    override val refreshEvents: Flow<Unit> = combine(state,committedSequence) { _,_ -> Unit }
    private var worker: Job? = null
    private var stopping = false
    private var body: InputStream? = null
    private var bodyCleanup: Job? = null

    @Synchronized override fun start() {
        check(!stopping && worker == null)
        stateMutable.value = CaptureTransportState.RUNNING

        worker = CoroutineScope(dispatcher).launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val next = source.next() ?: break
                    synchronized(this@SegmentCaptureTransport) { body = next.body }
                    try {
                        val context = currentCoroutineContext()
                        context.ensureActive()
                        val committed = store.append(next.startMs, next.endMs, next.continuity, next.body) { context.ensureActive() }
                    committedMutable.value = committed.sequence
                    } finally { closeBody() }
                }
                stateMutable.value = CaptureTransportState.COMPLETE
            } catch (_: CaptureStorageUnavailable) {
                stateMutable.value = CaptureTransportState.STORAGE_BLOCKED
            } catch (_: CaptureRetentionBlocked) {
                stateMutable.value = CaptureTransportState.BACKPRESSURE
            } catch (_: CancellationException) {

            } catch (_: Exception) {
                stateMutable.value = CaptureTransportState.FAILED
            } finally {

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

        bodyCleanup?.takeUnless { it.isCompleted } ?: CoroutineScope(dispatcher).launch(start = CoroutineStart.LAZY) {
            try { closeBody() } catch (_: Exception) { }
        }.also { bodyCleanup = it; it.start() }
    }

    private fun closeBody() {
        val closing = synchronized(this) { body } ?: return

        closing.close()
        synchronized(this) {
            if (body === closing) body = null
        }
    }
}
