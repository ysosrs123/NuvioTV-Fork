package com.nuvio.tv.core.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.nuvio.tv.core.player.thumbnail.PlaybackBufferReserve

/**
 * Read-ahead reserve for seek thumbnails, shared by [BitrateAwareLoadControl] and [ReservableLoadControl]. While
 * reserved, loading stops once the allocator holds the budget minus the reserve. A stall soon after the cap held
 * loading back ends the reserve (one retry), and it lapses unless the thumbnail engine keeps renewing it.
 */
@UnstableApi
internal class ReadAheadReserve(
    private val allocator: DefaultAllocator,
    private val budget: () -> Long,
    bufferForPlaybackMs: Int,
    bufferForPlaybackAfterRebufferMs: Int,
) : PlaybackBufferReserve {
    private val minAheadUs: Long =
        maxOf(MIN_AHEAD_US, bufferForPlaybackMs * 1000L, bufferForPlaybackAfterRebufferMs * 1000L)
    @Volatile private var held = 0L
    @Volatile private var leaseUntilMs = 0L
    @Volatile private var lastCapAtMs = 0L
    @Volatile private var cancels = 0
    @Volatile private var cancelledAtMs = 0L
    @Volatile private var lastStallAtMs = 0L

    override val readAheadBudgetBytes: Long get() = budget()
    override val bufferedBytes: Long get() = allocator.totalBytesAllocated.toLong()
    override val footprintBytes: Long get() = allocator.memoryFootprint.toLong()
    override val reservedBytes: Long get() = effective()
    override val lastStallRealtimeMs: Long get() = lastStallAtMs
    override val cancelledByRebuffer: Boolean
        get() = cancels >= MAX_CANCELS || (cancels > 0 &&
            SystemClock.elapsedRealtime() - maxOf(cancelledAtMs, lastStallAtMs) < RETRY_QUIET_MS)

    /** Clamped again here because the budget can change while reserved. */
    private fun effective(): Long = held.coerceAtMost(budget() * MAX_FRACTION_PERCENT / 100)

    override fun reserve(bytes: Long) {
        if (cancelledByRebuffer || bytes <= 0L) return
        leaseUntilMs = SystemClock.elapsedRealtime() + LEASE_MS
        held = bytes.coerceAtMost(budget() * MAX_FRACTION_PERCENT / 100)
    }

    override fun giveBack(): Long {
        val r = effective()
        if (r <= 0L) return 0L
        leaseUntilMs = SystemClock.elapsedRealtime() + LEASE_MS     // renew the lease
        // The allocator pools released segments up to its target, a lower target frees the surplus.
        // DefaultLoadControl resets the target on the next track selection.
        val before = allocator.memoryFootprint.toLong()
        allocator.setTargetBufferSize((budget() - r).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        allocator.trim()
        return (before - allocator.memoryFootprint.toLong()).coerceAtLeast(0L)
    }

    override fun release() {
        if (held <= 0L) return
        held = 0L
        allocator.setTargetBufferSize(budget().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
    }

    /** True when the reserve holds loading back now. */
    fun holdsLoading(parameters: LoadControl.Parameters): Boolean {
        // media3 stamps lastRebufferRealtimeMs on READY -> BUFFERING and clears it on a seek and on READY.
        val stallAt = parameters.lastRebufferRealtimeMs.takeIf { it != C.TIME_UNSET && it > 0L }
        if (stallAt != null && stallAt > lastStallAtMs) lastStallAtMs = stallAt
        val r = effective()
        if (r <= 0L) return false
        val now = SystemClock.elapsedRealtime()
        if (stallAt != null && lastCapAtMs > 0L && stallAt - lastCapAtMs < REBUFFER_WINDOW_MS) {
            cancels++
            cancelledAtMs = now
            lastCapAtMs = 0L
            release()
            Log.w(TAG, "4K-E reserve cancelled ($cancels of $MAX_CANCELS): rebuffer while the read-ahead was capped" +
                (if (cancels < MAX_CANCELS) " - may retry after ${RETRY_QUIET_MS / 60_000} min without a stall" else ""))
            return false
        }
        if (now > leaseUntilMs) {
            release()
            Log.w(TAG, "4K-E reserve expired: not renewed by the thumbnail engine")
            return false
        }
        if (parameters.bufferedDurationUs >= minAheadUs && allocator.totalBytesAllocated >= budget() - r) {
            lastCapAtMs = now
            return true
        }
        return false
    }

    private companion object {
        const val TAG = "ThumbReserve"
        /** The cap never holds the read-ahead below this much buffered time. */
        const val MIN_AHEAD_US = 8_000_000L
        /** A stall this soon after the cap held loading back is blamed on the reserve. */
        const val REBUFFER_WINDOW_MS = 20_000L
        /** The reserve lapses unless giveBack() renews it within this time. */
        const val LEASE_MS = 30_000L
        const val MAX_CANCELS = 2
        const val RETRY_QUIET_MS = 5L * 60 * 1000
        /**
         * Share of the budget. With a 200 MB budget and a 71 Mb/s remux, 60 % leaves ~80 MB (~9 s) of read-ahead.
         * Half is often not enough for a cold 4K decode (~280 MB) on a Fire TV Stick.
         */
        const val MAX_FRACTION_PERCENT = 60L
    }
}

/**
 * DefaultLoadControl with the seek-thumbnail read-ahead reserve, for the native-performance and stock paths.
 * A targetBufferBytes of C.LENGTH_UNSET keeps media3's per-track target, which then is the reserve's budget.
 */
@UnstableApi
class ReservableLoadControl(
    allocator: DefaultAllocator,
    minBufferMs: Int,
    maxBufferMs: Int,
    bufferForPlaybackMs: Int,
    bufferForPlaybackAfterRebufferMs: Int,
    private val targetBufferBytes: Int,
    prioritizeTimeOverSizeThresholds: Boolean,
    backBufferDurationMs: Int,
    retainBackBufferFromKeyframe: Boolean,
) : DefaultLoadControl(
    allocator,
    minBufferMs,
    maxBufferMs,
    bufferForPlaybackMs,
    bufferForPlaybackAfterRebufferMs,
    targetBufferBytes,
    prioritizeTimeOverSizeThresholds,
    backBufferDurationMs,
    retainBackBufferFromKeyframe
), PlaybackBufferReserve {
    /** 0 until the first track selection. */
    @Volatile private var computedTargetBytes = 0

    override fun calculateTargetBufferBytes(trackSelectionArray: Array<out ExoTrackSelection?>): Int =
        super.calculateTargetBufferBytes(trackSelectionArray).also { computedTargetBytes = it }

    private fun budgetBytes(): Long =
        (if (targetBufferBytes != C.LENGTH_UNSET) targetBufferBytes else computedTargetBytes).toLong()

    private val reserveCore = ReadAheadReserve(allocator, ::budgetBytes,
        bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)

    override val readAheadBudgetBytes: Long get() = reserveCore.readAheadBudgetBytes
    override val bufferedBytes: Long get() = reserveCore.bufferedBytes
    override val footprintBytes: Long get() = reserveCore.footprintBytes
    override val reservedBytes: Long get() = reserveCore.reservedBytes
    override val cancelledByRebuffer: Boolean get() = reserveCore.cancelledByRebuffer
    override val lastStallRealtimeMs: Long get() = reserveCore.lastStallRealtimeMs
    override fun reserve(bytes: Long) = reserveCore.reserve(bytes)
    override fun giveBack(): Long = reserveCore.giveBack()
    override fun release() = reserveCore.release()

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        if (reserveCore.holdsLoading(parameters)) false else super.shouldContinueLoading(parameters)

    companion object {
        /** Same as DefaultLoadControl.Builder().setBackBuffer(backBufferMs, true).build(). */
        fun stock(backBufferMs: Int): ReservableLoadControl = ReservableLoadControl(
            allocator = DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            minBufferMs = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            maxBufferMs = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            bufferForPlaybackMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            bufferForPlaybackAfterRebufferMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            targetBufferBytes = C.LENGTH_UNSET,
            prioritizeTimeOverSizeThresholds = DefaultLoadControl.DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS,
            backBufferDurationMs = backBufferMs,
            retainBackBufferFromKeyframe = true,
        )
    }
}
