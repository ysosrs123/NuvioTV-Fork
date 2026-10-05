package com.nuvio.tv.data.iptv

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.iptv.CaptureSampleLoadCursor
import com.nuvio.tv.core.iptv.CaptureSampleLoadInput
import com.nuvio.tv.core.iptv.CaptureSampleLoadState
import com.nuvio.tv.core.iptv.CaptureSampleWindow
import com.nuvio.tv.core.iptv.CaptureTransportState
import java.util.concurrent.CancellationException

@UnstableApi
internal class CaptureLoadedBatch internal constructor(internal val owner: Any,
    internal val ticket: CaptureSampleLoadInput, val samples: CapturedSampleBatch)

@UnstableApi
internal data class CaptureBatchLoadResult(val state: CaptureSampleLoadState,
    val batch: CaptureLoadedBatch? = null, val boundary: CaptureSampleWindow? = null,
    val producerState: CaptureTransportState? = null)

/**
 * Blocking LOCAL incremental staging queue; use a governed IO worker, never the playback thread.
 * No MediaSource/decoder/network, asynchronous callbacks, automatic polling/retries or seek ack.
 * Reserves worst-case next encoded batch within its logical aggregate byte/slot caps BEFORE loading;
 * transient/parser/object/decoder memory still requires separate measured parent admission.
 * Cursor/tickets/pins remain owned here; batches are borrowed until explicit release after borrowers
 * stop. WAITING is not EOF. Failed staging/cancellation is sticky; failed closure retains arrays/pins.
 */
@UnstableApi
internal class CaptureSampleBatchQueue(private val cursor: CaptureSampleLoadCursor,
    private val limits: CaptureSampleStagingLimits, val maxResidentBytes: Long,
    private val maxBatches: Int = 2,
    private val stage: (CaptureSampleLoadInput, CaptureSampleStagingLimits, () -> Unit) -> CapturedSampleBatch =
        { input,bounds,cancellation -> LocalCaptureSampleStager(bounds).stage(input.window,input.media,cancellation) },
) : AutoCloseable {
    init { require(maxResidentBytes in limits.maxBatchBytes..(1024L*1024*1024) && maxBatches in 1..16) }
    private val owner = Any()
    private val batches = linkedMapOf<CaptureLoadedBatch, CaptureLoadedBatch>()
    private var candidate: CapturedSampleBatch? = null
    private var terminal: CaptureBatchLoadResult? = null
    private var loading = false
    private var closing = false
    private var closed = false
    val residentBytes: Long get() = synchronized(this) { batches.keys.sumOf { it.samples.chargedBytes } + (candidate?.chargedBytes ?: 0) }
    val isClosed: Boolean get() = synchronized(this) { closed }
    @Synchronized fun snapshotBatches(): List<CaptureLoadedBatch> { check(!closing && !closed); return batches.keys.toList() }

    @Synchronized fun loadNext(checkCancellation: () -> Unit = {}): CaptureBatchLoadResult {
        check(!loading && !closing && !closed) { "Sample queue operation reentered or closed" }
        loading = true
        try { return loadLocked(checkCancellation) } finally { loading = false }
    }

    private fun loadLocked(checkCancellation: () -> Unit): CaptureBatchLoadResult {
        check(!closing && !closed)
        terminal?.let { return it }
        if (batches.size >= maxBatches || limits.maxBatchBytes > maxResidentBytes - residentBytes)
            return CaptureBatchLoadResult(CaptureSampleLoadState.CAPACITY)
        try {
            checkCancellation()
            val next = cursor.poll(checkCancellation)
            if (next.state != CaptureSampleLoadState.READY) {
                val result = CaptureBatchLoadResult(next.state,boundary=next.boundary,producerState=next.producerState)
                if (next.state !in setOf(CaptureSampleLoadState.WAITING,CaptureSampleLoadState.CAPACITY)) terminal = result
                return result
            }
            val input = requireNotNull(next.input)
            val staged = stage(input,limits,checkCancellation)
            require(staged.window === input.window && staged.chargedBytes in 1..limits.maxBatchBytes)
            candidate = staged // Keep the bounded successful stage before cancellation.
            checkCancellation()
            cursor.complete(input) // Verification/staging only; never acknowledges a player seek.
            val loaded = CaptureLoadedBatch(owner,input,staged)
            batches[loaded] = loaded; candidate = null
            return CaptureBatchLoadResult(CaptureSampleLoadState.READY,loaded,producerState=next.producerState)
        } catch (cancel: CancellationException) {
            terminal = CaptureBatchLoadResult(CaptureSampleLoadState.CANCELLED); throw cancel
        } catch (interrupted: InterruptedException) {
            terminal = CaptureBatchLoadResult(CaptureSampleLoadState.CANCELLED); throw interrupted
        } catch (_: Exception) {
            return CaptureBatchLoadResult(CaptureSampleLoadState.FAILED).also { terminal = it }
        }
    }
    @Synchronized fun release(batch: CaptureLoadedBatch) {
        check(!loading && !closing && !closed)
        require(batch.owner === owner && batches[batch] === batch)
        cursor.release(batch.ticket)
        batches.remove(batch) // Only after confirmed input closure; failure retains its byte charge.
    }
    @Synchronized override fun close() {
        check(!loading) { "Cannot close inside a staging callback" }
        if (closed) return
        closing = true
        cursor.close() // A blocked/failing close keeps all arrays/reservations until explicit retry.
        check(cursor.isClosed)
        batches.clear(); candidate = null; closed = true
    }
}
