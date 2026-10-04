package com.nuvio.tv.ui.screens.player

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Owns the optional head/suffix pair for the currently selected playback. */
internal class PlaybackPrewarmCoordinator(
    private val onSelection: () -> Unit,
    private val onEvent: (Event) -> Unit = {},
    private val publish: (Request, String, PlaybackPrewarmReader.Window, Boolean) -> Boolean
) {
    enum class Outcome { STARTED, CANCEL_REQUESTED, CANCELLED, RESPONSE_REJECTED, TRANSPORT_FAILURE,
        ENQUEUE_FAILURE, STORED, STORE_DECLINED, STALE_RESULT }
    enum class Cancellation { SELECTION_REPLACED, EXPLICIT_STOP }

    /** Payload length means a validated window, not wire bytes or playback adoption. */
    data class Event(
        val batchId: Long,
        val head: Boolean,
        val outcome: Outcome,
        val elapsedMs: Long,
        val status: Int? = null,
        val rejection: PlaybackPrewarmReader.Rejection? = null,
        val cancellation: Cancellation? = null,
        val validatedBytes: Int = 0
    )

    private class Batch(val id: Long, val request: Request, val calls: List<Call>) {
        val startedAt = System.nanoTime()
        val finished = BooleanArray(calls.size)
        var pending = calls.size
        var cancellation: Cancellation? = null
    }

    private val lock = Any()
    private var current: Batch? = null
    private var nextId = 0L

    /** Only in-flight requests are deduplicated; another playback can warm again after completion. */
    fun start(request: Request, factory: () -> Call.Factory) {
        val batch = synchronized(lock) {
            val previous = current
            if (previous != null && previous.pending > 0 && sameRequest(previous.request, request)) return
            val calls = try {
                val client = factory()
                listOf(
                    client.newCall(request.newBuilder().header("Range", "bytes=0-${HEAD_BYTES - 1}").build()),
                    client.newCall(request.newBuilder().header("Range", "bytes=-$TAIL_BYTES").build())
                )
            } catch (_: IllegalArgumentException) {
                return
            }
            current = null
            previous?.let { cancelBatch(it, Cancellation.SELECTION_REPLACED) }
            onSelection()
            Batch(++nextId, request, calls).also { current = it }
        }
        batch.calls.forEachIndexed { index, call ->
            emit(batch, index, Outcome.STARTED)
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        finish(batch, index, if (call.isCanceled()) Outcome.CANCELLED else Outcome.TRANSPORT_FAILURE)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        var outcome = Outcome.STALE_RESULT
                        var rejection: PlaybackPrewarmReader.Rejection? = null
                        var validatedBytes = 0
                        try {
                            if (!isCurrent(batch) || call.isCanceled()) {
                                call.cancel()
                                response.close()
                                return
                            }
                            val resolvedUrl = response.request.url.toString()
                            val head = index == 0
                            outcome = Outcome.RESPONSE_REJECTED
                            val window = PlaybackPrewarmReader.read(
                                response,
                                maxBytes = if (head) HEAD_BYTES else TAIL_BYTES,
                                start = if (head) 0L else null,
                                onRejected = { rejection = it },
                                cancel = call::cancel
                            ) ?: return
                            validatedBytes = window.bytes.size
                            synchronized(lock) {
                                // Serializes publication with selection changes and explicit cancellation.
                                outcome = if (current === batch && !call.isCanceled()) {
                                    if (publish(batch.request, resolvedUrl, window, head)) Outcome.STORED
                                    else Outcome.STORE_DECLINED
                                } else Outcome.STALE_RESULT
                            }
                        } finally {
                            finish(batch, index, outcome, response.code, rejection, validatedBytes)
                        }
                    }
                })
            } catch (_: RuntimeException) {
                call.cancel()
                finish(batch, index, Outcome.ENQUEUE_FAILURE)
            }
        }
    }

    /** A departing player must not cancel a newer selection with a different request identity. */
    fun cancel(request: Request) = synchronized(lock) {
        val batch = current ?: return@synchronized
        if (!sameRequest(batch.request, request)) return@synchronized
        current = null
        cancelBatch(batch, Cancellation.EXPLICIT_STOP)
        onSelection()
    }

    private fun cancelBatch(batch: Batch, reason: Cancellation) {
        batch.cancellation = reason
        batch.calls.forEachIndexed { index, call ->
            if (!batch.finished[index]) emit(batch, index, Outcome.CANCEL_REQUESTED)
            call.cancel()
        }
    }

    private fun isCurrent(batch: Batch) = synchronized(lock) { current === batch }

    private fun finish(batch: Batch, index: Int, outcome: Outcome, status: Int? = null,
                       rejection: PlaybackPrewarmReader.Rejection? = null, validatedBytes: Int = 0) {
        synchronized(lock) {
            if (batch.finished[index]) return
            batch.finished[index] = true
            batch.pending--
            emit(batch, index, if (batch.cancellation != null && outcome != Outcome.STORED && outcome != Outcome.STORE_DECLINED) Outcome.CANCELLED else outcome,
                status, rejection, validatedBytes)
        }
    }

    private fun emit(batch: Batch, index: Int, outcome: Outcome, status: Int? = null,
                     rejection: PlaybackPrewarmReader.Rejection? = null, validatedBytes: Int = 0) {
        val event = synchronized(lock) {
            Event(batch.id, index == 0, outcome,
                ((System.nanoTime() - batch.startedAt) / 1_000_000L).coerceAtLeast(0L),
                status, rejection, batch.cancellation, validatedBytes)
        }
        // A broken diagnostic observer must never alter loading or cleanup.
        runCatching { onEvent(event) }
    }

    private fun sameRequest(a: Request, b: Request) =
        a.url == b.url && a.headers.toMultimap() == b.headers.toMultimap()

    companion object {
        const val HEAD_BYTES = 262_144
        const val TAIL_BYTES = 4_194_304
    }
}
