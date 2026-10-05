package com.nuvio.tv.core.iptv

import java.io.IOException
import java.io.InputStream

enum class CaptureLiveReadState { DATA, WAITING, ENDED, EXPIRED, DISCONTINUITY, STOPPED }
data class CaptureLiveRead(val state: CaptureLiveReadState, val bytes: Int = 0,
    val segment: CaptureSegment? = null, val producerState: CaptureTransportState? = null)

/**
 * Local committed-byte reader, NOT a decoder-safe seek or Media3 timeline. No network or polling loop.
 * WAITING is a live tail; only producer COMPLETE can yield ENDED. Failed/backpressured/closed producers
 * yield STOPPED after draining committed bytes. An unavailable old start yields EXPIRED, never a jump.
 * Gap/discontinuity requires explicit close and a new reader. Metadata remains caller/manifest timing.
 * Reading starts lazily. Once started, a pin retains the current segment and successors, including at
 * a waiting/terminal tail, until advancement or explicit close. Consumer ownership must include this
 * reader's close; reaching a terminal state does not release retention or infrastructure reservations.
 */
class CaptureLiveReader internal constructor(private val store: CaptureSegmentStore,
    private var expectedSequence: Long, private val producerState: () -> CaptureTransportState,
) : AutoCloseable {
    private var input: InputStream? = null
    private var anchor: AutoCloseable? = null
    private var current: CaptureSegment? = null
    private var previous: CaptureSegment? = null
    private var closed = false

    @Synchronized fun read(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): CaptureLiveRead {
        check(!closed) { "Capture reader is closed" }
        require(offset >= 0 && length > 0 && offset <= bytes.size - length)
        while (true) {
            input?.let { local ->
                val n = local.read(bytes, offset, length)
                if (n > 0) return CaptureLiveRead(CaptureLiveReadState.DATA, n, current)
                if (n == 0) throw IOException("Local capture read made no progress")
                local.close(); input = null
                previous = current; current = null
                expectedSequence = requireNotNull(previous).sequence + 1
            }
            // Read the producer state BEFORE the store snapshot. COMPLETE implies all publications
            // already happened; sampling it after a stale snapshot could manufacture premature EOF.
            val state = producerState()
            synchronized(store) {
                val rows = store.snapshot()
                val next = rows.firstOrNull { it.sequence == expectedSequence }
                if (next == null) {
                    val outcome = when {
                        rows.any { it.sequence > expectedSequence } -> CaptureLiveReadState.EXPIRED
                        state == CaptureTransportState.COMPLETE -> CaptureLiveReadState.ENDED
                        state == CaptureTransportState.NEW || state == CaptureTransportState.RUNNING -> CaptureLiveReadState.WAITING
                        else -> CaptureLiveReadState.STOPPED
                    }
                    return CaptureLiveRead(outcome, producerState = state)
                }
                previous?.let {
                    if (next.startMs != it.endMs || next.continuity != it.continuity) {
                        return CaptureLiveRead(CaptureLiveReadState.DISCONTINUITY, segment = next, producerState = state)
                    }
                }
                val pin = store.pinFrom(next.sequence)
                val opened = try { store.open(next.sequence) } catch (error: Exception) { pin.close(); throw error }
                anchor?.close(); anchor = pin
                input = opened; current = next
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        input?.close(); input = null
        anchor?.close(); anchor = null
        closed = true
    }
}
