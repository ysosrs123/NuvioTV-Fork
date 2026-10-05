package com.nuvio.tv.core.iptv

import kotlin.math.abs

enum class CaptureEpochStart { INITIAL, SEQUENCE_GAP, CAPTURE_DISCONTINUITY, INITIALIZATION_CHANGE, TIMESTAMP_CHANGE }

/** Positions are 90 kHz ticks relative to this epoch's first video PTS, stable across eviction. */
class CaptureSampleWindow internal constructor(val proof: InspectedCaptureSegment,
    val epoch: Long, val start90k: Long, val endExclusive90k: Long, val audioStart90k: Long,
    val audioEnd90k: Long, val epochStart: CaptureEpochStart?) {
    val lastVideo90k get() = endExclusive90k - proof.inspection.videoStep90k
}

data class CaptureSampleSnapshot(val revision: Long, val windows: List<CaptureSampleWindow>) {
    val latestEpoch: Long? get() = windows.lastOrNull()?.epoch
}

/**
 * Structural sample timeline over retained, inspected complete files; no Media3 publication or
 * decode guarantee. Manifest times only detect capture gaps, never set sample durations/positions.
 * Exact video cadence and audio adjacency (one tick rounding allowance), configuration and capture
 * continuity are required to join rows. A gap starts an explicit epoch; old epochs remain distinct.
 * Keep the accepted tail after pruning so PTS wrap/eviction cannot rebase positions. On process/store
 * reopen create a new owner/timeline; epochs are not persisted. No pins are held between operations.
 */
class CaptureSampleTimeline(private val index: CaptureTsInspectionIndex, private val maxWindows: Int = 256) {
    private val state = Any()
    private var windows = emptyList<CaptureSampleWindow>()
    private var tail: CaptureSampleWindow? = null
    private var revision = 0L
    init { require(maxWindows in 1..4096) }

    fun accept(proof: InspectedCaptureSegment): CaptureSampleWindow = index.open(proof).use {
        val retained = index.retainedSegments()
        synchronized(state) {
            prune(retained)
            windows.firstOrNull { it.proof.segment == proof.segment }?.let { existing ->
                require(existing.proof.inspection == proof.inspection) { "Committed capture evidence changed" }
                return@synchronized existing
            }
            val previous = tail
            require(previous == null || proof.segment.sequence > previous.proof.segment.sequence) { "Capture timeline cannot rewind" }
            val reason = boundary(previous, proof)
            val epoch = if (previous == null) 0 else if (reason == null) previous.epoch else Math.addExact(previous.epoch, 1)
            val start = if (reason == null) requireNotNull(previous).endExclusive90k else 0
            val media = proof.inspection
            val end = Math.addExact(start, Math.multiplyExact(media.videoFrames.toLong(), media.videoStep90k))
            val next = CaptureSampleWindow(proof, epoch, start, end,
                Math.addExact(start, media.audioFirstPts90k - media.videoFirstPts90k),
                Math.addExact(start, media.audioEndPts90k - media.videoFirstPts90k), reason)
            val nextRevision = Math.addExact(revision, 1)
            windows = (windows + next).takeLast(maxWindows)
            tail = next; revision = nextRevision
            next
        }
    }

    fun snapshot(): CaptureSampleSnapshot {
        val retained = index.retainedSegments()
        return synchronized(state) { prune(retained); CaptureSampleSnapshot(revision, windows.toList()) }
    }

    /** Pin the exact published start before the caller stages/decodes. Expiry never substitutes a row. */
    internal fun open(window: CaptureSampleWindow): InspectedCaptureInput = synchronized(state) {
        if (windows.none { it == window }) throw CaptureMediaExpired()
        index.open(window.proof)
    }

    private fun prune(retained: Set<CaptureSegment>) {
        val next = windows.filter { it.proof.segment in retained }
        if (next.size != windows.size) {
            val nextRevision = Math.addExact(revision, 1)
            windows = next; revision = nextRevision
        }
    }

    private fun boundary(previous: CaptureSampleWindow?, proof: InspectedCaptureSegment): CaptureEpochStart? {
        if (previous == null) return CaptureEpochStart.INITIAL
        val before = previous.proof.segment; val after = proof.segment
        if (after.sequence != before.sequence + 1) return CaptureEpochStart.SEQUENCE_GAP
        if (after.continuity != before.continuity || after.startMs != before.endMs) return CaptureEpochStart.CAPTURE_DISCONTINUITY
        val a = previous.proof.inspection; val b = proof.inspection
        if (a.initializationSha256 != b.initializationSha256) return CaptureEpochStart.INITIALIZATION_CHANGE
        if (a.videoStep90k != b.videoStep90k ||
            delta(b.videoFirstPts90k, a.videoLastPts90k + a.videoStep90k) != 0L ||
            abs(delta(b.audioFirstPts90k, a.audioEndPts90k)) > 1) return CaptureEpochStart.TIMESTAMP_CHANGE
        return null
    }

    private fun delta(a: Long, b: Long): Long = ((a - b + (1L shl 32)) and ((1L shl 33) - 1)) - (1L shl 32)
}
