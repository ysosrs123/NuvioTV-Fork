package com.nuvio.tv.core.report

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

internal const val RECORDING_MAX_MS = 30L * 60_000L
internal const val RECORDING_MIN_FREE_BYTES = 300L * 1024L * 1024L
internal const val RECORDING_SAMPLE_CAPACITY = 1_800
internal const val RECORDING_EVENT_CAPACITY = 2_000
internal const val RECORDING_MARKS_MAX = 200

/** Upper edges in milliseconds; a frame gap at or above the last edge lands in the final bucket. */
internal val FRAME_BUCKET_EDGES_MS = intArrayOf(18, 28, 38, 50, 80)

internal fun frameBucketIndex(gapMs: Long): Int {
    for (i in FRAME_BUCKET_EDGES_MS.indices) if (gapMs < FRAME_BUCKET_EDGES_MS[i]) return i
    return FRAME_BUCKET_EDGES_MS.size
}

/**
 * Folds the gap between rendered frames into counts. One writer (the playback thread) and one reader that drains it
 * once a second, so a count is never lost and nothing allocates per frame.
 */
internal class FrameBucketCounter {
    private val counts = AtomicIntegerArray(FRAME_BUCKET_COUNT)
    private var lastFrameNs = 0L

    fun onFrame(nowNs: Long) {
        val last = lastFrameNs
        lastFrameNs = nowNs
        if (last == 0L) return
        val gapMs = (nowNs - last) / 1_000_000L
        if (gapMs < 0L) return
        val index = frameBucketIndex(gapMs)
        counts.incrementAndGet(index)
    }

    fun restart() {
        lastFrameNs = 0L
    }

    fun drainInto(out: IntArray) {
        for (i in 0 until FRAME_BUCKET_COUNT) out[i] = counts.getAndSet(i, 0)
    }
}

/**
 * Audio clock jumps noticed on the playback thread, handed to the progress tick. One writer, one reader, a fixed
 * handful of slots: a burst older than the slots is overwritten, never queued without limit.
 */
internal class ClockJumpQueue(private val capacity: Int = 32) {
    private val sizes = IntArray(capacity)
    private val walls = LongArray(capacity)
    private val positions = LongArray(capacity)
    private val written = AtomicInteger(0)
    private var read = 0

    fun offer(sizeMs: Int, wallMs: Long, positionMs: Long) {
        val count = written.get()
        val slot = count % capacity
        sizes[slot] = sizeMs
        walls[slot] = wallMs
        positions[slot] = positionMs
        written.lazySet(count + 1)
    }

    fun drain(consumer: (sizeMs: Int, wallMs: Long, positionMs: Long) -> Unit) {
        val end = written.get()
        if (end - read > capacity) read = end - capacity
        while (read < end) {
            val slot = read % capacity
            consumer(sizes[slot], walls[slot], positions[slot])
            read += 1
        }
    }
}

/** Minutes and seconds, as the pill and the Ready step show a recording's length. */
internal fun recordingClock(lengthMs: Long): String {
    val total = (lengthMs / 1_000L).coerceAtLeast(0L)
    return "${total / 60L}:${(total % 60L).toString().padStart(2, '0')}"
}

/** Average of how early frames were released over the frames since the last read; a restarted decoder starts over. */
internal class FrameLeadTracker {
    private var lastTotalUs = 0L
    private var lastCount = 0

    fun update(totalUs: Long, count: Int): Int {
        val frames = count - lastCount
        val spanUs = totalUs - lastTotalUs
        lastTotalUs = totalUs
        lastCount = count
        if (frames <= 0) return RECORDER_UNKNOWN
        val average = (spanUs / frames).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        return if (average == RECORDER_UNKNOWN) 0 else average
    }

    fun reset() {
        lastTotalUs = 0L
        lastCount = 0
    }
}

/** Underruns since the last sample; nothing for the first reading or for a count that went back down. */
internal fun underrunsAdded(previous: Int, total: Int): Int =
    if (previous < 0 || total <= previous) 0 else total - previous

/** How the sound position was worked out; null for "held at the end of what was written", which is not a way of its own. */
internal fun clockSourceLabel(source: Int): String? = when (source) {
    0 -> "head"
    1 -> "timestamp"
    2 -> "steadied"
    else -> null
}

/** The new way's name when it differs from the one before; nothing for the first reading. */
internal fun clockSourceChange(previous: Int, current: Int): String? {
    val label = clockSourceLabel(current) ?: return null
    return if (clockSourceLabel(previous) == null || previous == current) null else label
}

internal enum class ReportKeyAction { OPEN_PANEL, ADD_MARK, UNAVAILABLE }

/** What the "Report a problem / mark" remote job does: add a mark while recording, otherwise open the panel. */
internal fun reportKeyAction(reportsAvailable: Boolean, recording: Boolean): ReportKeyAction = when {
    recording -> ReportKeyAction.ADD_MARK
    reportsAvailable -> ReportKeyAction.OPEN_PANEL
    else -> ReportKeyAction.UNAVAILABLE
}

class RecorderMark internal constructor(val wallMs: Long, val positionMs: Long, val elapsedMs: Long)

internal class RecordingMarks(private val max: Int = RECORDING_MARKS_MAX) {
    private val list = ArrayList<RecorderMark>()

    val size: Int get() = list.size

    /** Null when the list is full; earlier marks are never pushed out. */
    fun add(wallMs: Long, positionMs: Long, elapsedMs: Long): RecorderMark? {
        if (list.size >= max) return null
        return RecorderMark(wallMs, positionMs, elapsedMs).also { list.add(it) }
    }

    fun snapshot(): List<RecorderMark> = list.toList()
}

enum class RecordingEnd(val label: String) {
    STOPPED("stopped"),
    TIME_UP("time_up"),
    LOW_STORAGE("low_storage"),
    PLAYER_CLOSED("player_closed")
}

internal fun recordingStopReason(elapsedMs: Long, freeBytes: Long): RecordingEnd? = when {
    elapsedMs >= RECORDING_MAX_MS -> RecordingEnd.TIME_UP
    freeBytes in 0 until RECORDING_MIN_FREE_BYTES -> RecordingEnd.LOW_STORAGE
    else -> null
}

/** What a stopped or running recording says about itself in the report. */
class RecordingInfo internal constructor(
    val startedAtWallMs: Long,
    val lengthMs: Long,
    val end: RecordingEnd?
)
