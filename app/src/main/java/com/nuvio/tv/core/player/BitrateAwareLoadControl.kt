package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.nuvio.tv.core.player.thumbnail.PlaybackBufferReserve

/**
 * DefaultLoadControl with a byte target tied to the device memory budget. It is
 * built with the full budget and tightened at runtime, through the override
 * setter below, only for streams that really are DV7.
 *
 * The back buffer has no runtime override: media3's ExoPlayerImplInternal reads
 * getBackBufferDurationUs() and retainBackBufferFromKeyframe() once, in its
 * constructor, so it is fixed for the life of the player instance.
 *
 * Also the seek thumbnails' [PlaybackBufferReserve] (logic in [ReadAheadReserve], shared with
 * [ReservableLoadControl]).
 */
@UnstableApi
class BitrateAwareLoadControl(
    minBufferMs: Int,
    maxBufferMs: Int,
    bufferForPlaybackMs: Int,
    bufferForPlaybackAfterRebufferMs: Int,
    prioritizeTimeOverSizeThresholds: Boolean,
    backBufferDurationMs: Int,
    retainBackBufferFromKeyframe: Boolean,
    /** Memory ceiling in bytes. */
    private val budgetBytes: Long,
    allocator: DefaultAllocator = DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE, 64)
) : DefaultLoadControl(
    allocator,
    minBufferMs,
    maxBufferMs,
    bufferForPlaybackMs,
    bufferForPlaybackAfterRebufferMs,
    /* targetBufferBytes= */ C.LENGTH_UNSET,
    prioritizeTimeOverSizeThresholds,
    backBufferDurationMs,
    retainBackBufferFromKeyframe
), PlaybackBufferReserve {

    private val bufferAllocator: DefaultAllocator = allocator

    // Effective byte budget when >= 0, else the constructed budget. Re-read on track
    // (re)selection, so this can change mid-playback.
    @Volatile
    private var budgetBytesOverride: Long = -1L

    /** Set the byte budget at runtime; negative restores the constructed budget. */
    fun setBudgetBytesOverride(bytes: Long) {
        budgetBytesOverride = if (bytes < 0L) -1L else bytes
    }

    override fun calculateTargetBufferBytes(
        trackSelectionArray: Array<out ExoTrackSelection?>
    ): Int {
        // Target = the memory budget. Time (Max Buffer Duration) is the real limit; this is
        // just the memory cap. Sizing from advertised bitrate starved variable-bitrate peaks,
        // so let high-bitrate fill up to the budget and low-bitrate stop at the time limit.
        return effectiveBudgetBytes().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun effectiveBudgetBytes(): Long = if (budgetBytesOverride >= 0L) budgetBytesOverride else budgetBytes

    // Seek thumbnail read-ahead reserve
    private val reserveCore = ReadAheadReserve(bufferAllocator, ::effectiveBudgetBytes,
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
}
