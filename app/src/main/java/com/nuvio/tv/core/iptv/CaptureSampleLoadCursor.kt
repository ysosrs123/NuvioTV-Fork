package com.nuvio.tv.core.iptv

import java.util.concurrent.CancellationException

enum class CaptureSampleLoadState { READY, WAITING, CAPACITY, ENDED, STOPPED, EXPIRED, DISCONTINUITY, FAILED, CANCELLED }

class CaptureSampleLoadInput internal constructor(internal val owner: Any,
    val window: CaptureSampleWindow, val media: InspectedCaptureInput)

data class CaptureSampleLoadResult(val state: CaptureSampleLoadState,
    val input: CaptureSampleLoadInput? = null, val boundary: CaptureSampleWindow? = null,
    val producerState: CaptureTransportState? = null)

class CaptureSampleLoadCursor(private val store: CaptureSegmentStore,
    private val index: CaptureTsInspectionIndex, private val timeline: CaptureSampleTimeline,
    startingSequence: Long, private val producerState: () -> CaptureTransportState,
    private val maxOpenInputs: Int = 2,
    private val openMedia: (CaptureSampleWindow) -> InspectedCaptureInput = { timeline.open(it) },
) : AutoCloseable {
    private val owner = Any()
    private var expectedSequence = startingSequence
    private var previous: CaptureSampleWindow? = null
    private var current: CaptureSampleLoadInput? = null
    private val inputs = linkedMapOf<CaptureSampleLoadInput, Boolean>()
    private var anchor: AutoCloseable? = null
    private var candidatePin: AutoCloseable? = null
    private var candidateInput: InspectedCaptureInput? = null
    private var terminal: CaptureSampleLoadResult? = null
    private var polling = false
    private var closing = false
    private var closed = false
    init {
        require(maxOpenInputs in 1..16 && startingSequence >= 0)
        val tail = store.snapshot().lastOrNull()?.sequence
        require(startingSequence <= if (tail == null) 0 else Math.addExact(tail,1))
    }
    val openInputs: Int get() = synchronized(this) { inputs.size + if (candidateInput == null) 0 else 1 }
    val isClosed: Boolean get() = synchronized(this) { closed }

    @Synchronized fun poll(checkCancellation: () -> Unit = {}): CaptureSampleLoadResult {
        check(!polling && !closing && !closed) { "Sample cursor operation reentered or closed" }
        polling = true
        try { return pollLocked(checkCancellation) } finally { polling = false }
    }

    private fun pollLocked(checkCancellation: () -> Unit): CaptureSampleLoadResult {
        check(!closing && !closed)
        terminal?.let { return it }
        current?.let {
            if (it.media.isClosed) return stop(CaptureSampleLoadResult(CaptureSampleLoadState.FAILED))
            return CaptureSampleLoadResult(CaptureSampleLoadState.READY,it)
        }
        if (inputs.size >= maxOpenInputs) return CaptureSampleLoadResult(CaptureSampleLoadState.CAPACITY)
        try {
            checkCancellation()

            val state = producerState()
            val row = synchronized(store) {
                val rows = store.snapshot()
                val found = rows.singleOrNull { it.sequence == expectedSequence }
                if (found == null) {
                    val result = when {
                        rows.any { it.sequence > expectedSequence } -> CaptureSampleLoadState.EXPIRED
                        state == CaptureTransportState.COMPLETE -> CaptureSampleLoadState.ENDED
                        state in setOf(CaptureTransportState.NEW,CaptureTransportState.RUNNING) -> CaptureSampleLoadState.WAITING
                        else -> CaptureSampleLoadState.STOPPED
                    }
                    val outcome = CaptureSampleLoadResult(result,producerState=state)
                    return if (result == CaptureSampleLoadState.WAITING) outcome else stop(outcome)
                }
                candidatePin = store.pinFrom(found.sequence)
                found
            }
            checkCancellation()
            val proof = index.inspect(row.sequence,checkCancellation)
            check(proof.segment == row)
            val window = timeline.accept(proof)
            previous?.let {
                if (window.epoch != it.epoch || row.sequence != it.proof.segment.sequence + 1 || window.start90k != it.endExclusive90k)
                    return stop(CaptureSampleLoadResult(CaptureSampleLoadState.DISCONTINUITY,boundary=window,producerState=state))
            }
            candidateInput = openMedia(window)
            val opened = requireNotNull(candidateInput)
            require(opened.proof === window.proof && !opened.isClosed && !opened.verified)
            checkCancellation()

            anchor?.close(); anchor = candidatePin; candidatePin = null
            val ticket = CaptureSampleLoadInput(owner,window,opened)
            inputs[ticket] = false; current = ticket; candidateInput = null
            return CaptureSampleLoadResult(CaptureSampleLoadState.READY,ticket,producerState=state)
        } catch (cancel: CancellationException) {
            stop(CaptureSampleLoadResult(CaptureSampleLoadState.CANCELLED)); throw cancel
        } catch (interrupted: InterruptedException) {
            stop(CaptureSampleLoadResult(CaptureSampleLoadState.CANCELLED)); throw interrupted
        } catch (_: CaptureMediaExpired) {
            return stop(CaptureSampleLoadResult(CaptureSampleLoadState.EXPIRED))
        } catch (_: Exception) {
            return stop(CaptureSampleLoadResult(CaptureSampleLoadState.FAILED))
        }
    }

    @Synchronized fun complete(ticket: CaptureSampleLoadInput) {
        check(!polling && !closing && !closed && terminal == null)
        require(ticket.owner === owner && current === ticket && inputs[ticket] == false)
        require(ticket.media.verified && !ticket.media.isClosed) { "Sample load must verify complete pinned bytes" }
        val next = Math.addExact(ticket.window.proof.segment.sequence,1)
        inputs[ticket] = true; previous = ticket.window; expectedSequence = next; current = null
    }

    @Synchronized fun release(ticket: CaptureSampleLoadInput) {
        check(!polling && !closing && !closed)
        require(ticket.owner === owner && inputs[ticket] == true)
        ticket.media.close(); check(ticket.media.isClosed)
        inputs.remove(ticket)
    }

    @Synchronized override fun close() {
        check(!polling) { "Cannot close inside a sample-loading callback" }
        if (closed) return
        closing = true
        for (ticket in inputs.keys.toList()) {
            ticket.media.close(); check(ticket.media.isClosed); inputs.remove(ticket)
        }
        candidateInput?.let { it.close(); check(it.isClosed); candidateInput = null }
        candidatePin?.close(); candidatePin = null
        anchor?.close(); anchor = null
        current = null; closed = true
    }
    private fun stop(result: CaptureSampleLoadResult): CaptureSampleLoadResult { terminal = result; return result }
}
