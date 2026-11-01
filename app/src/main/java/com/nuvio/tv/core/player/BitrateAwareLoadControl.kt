package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
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
    budgetBytes: Long,
    allocator: DefaultAllocator = DefaultAllocator(/* trimOnReset= */ false, C.DEFAULT_BUFFER_SEGMENT_SIZE, 64),
    topUpPieceBytes: Long = BufferTopUp.DEFAULT_PIECE_BYTES
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
), PlaybackBufferReserve, BufferBudgetControl, StartCushionControl {

    override val bufferAllocator: DefaultAllocator = allocator

    override val stopTrim: AllocatorStopTrim = AllocatorStopTrim(allocator)

    override fun onStopped(playerId: PlayerId) {
        super.onStopped(playerId)
        stopTrim.onPlayerGone()
    }

    override fun onReleased(playerId: PlayerId) {
        super.onReleased(playerId)
        stopTrim.onPlayerGone()
    }

    override val startCushion: StartCushion = StartCushion(bufferForPlaybackMs, maxBufferMs)

    override val maxBufferMs: Int = maxBufferMs

    override val topUp: BufferTopUp = BufferTopUp(maxBufferMs, minBufferMs, topUpPieceBytes)

    @Volatile override var backBufferBytes: Long = 0L

    // Effective byte budget when >= 0, else the constructed budget. Re-read on track
    // (re)selection, so this can change mid-playback.
    @Volatile
    private var budgetBytesOverride: Long = -1L

    /** Set the byte budget at runtime; negative restores the constructed budget. */
    fun setBudgetBytesOverride(bytes: Long) {
        budgetBytesOverride = if (bytes < 0L) -1L else bytes.coerceAtMost(MAX_BUDGET_BYTES)
    }

    private val budgetBytes: Long = budgetBytes.coerceAtMost(MAX_BUDGET_BYTES)

    override fun calculateTargetBufferBytes(
        trackSelectionArray: Array<out ExoTrackSelection?>
    ): Int {
        // Target = the memory budget. Time (Max Buffer Duration) is the real limit; this is
        // just the memory cap. Sizing from advertised bitrate starved variable-bitrate peaks,
        // so let high-bitrate fill up to the budget and low-bitrate stop at the time limit.
        return effectiveBudgetBytes().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    @Volatile
    private var pressureCapBytes: Long = -1L

    @Volatile
    private var growthBytes: Long = 0L

    override val uncappedBudgetBytes: Long
        get() = ((if (budgetBytesOverride >= 0L) budgetBytesOverride else budgetBytes) + growthBytes)
            .coerceAtMost(MAX_BUDGET_BYTES)

    override fun setPressureCapBytes(bytes: Long) {
        pressureCapBytes = if (bytes < 0L) -1L else bytes
    }

    override fun setGrowthBytes(bytes: Long) {
        growthBytes = bytes.coerceAtLeast(0L)
    }

    override fun allocatorTargetBytes(): Int =
        (effectiveBudgetBytes() - reserveCore.reservedBytes).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun effectiveBudgetBytes(): Long {
        val cap = pressureCapBytes
        val budget = uncappedBudgetBytes
        return if (cap >= 0L) minOf(budget, cap) else budget
    }

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
    override fun yieldLoading(on: Boolean) = reserveCore.yieldLoading(on)
    override val yieldHolding: Boolean get() = reserveCore.yieldHolding

    // Time before size lets the buffer pass the budget until minBufferMs is met. Once enough is
    // buffered to start playback, it stops a quarter over the budget instead.
    private val minAheadUs: Long = maxOf(bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs) * 1000L

    internal fun ceilingBytes(): Long {
        val budget = effectiveBudgetBytes()
        return budget + budget / 4
    }

    private fun overCeiling(parameters: LoadControl.Parameters): Boolean =
        parameters.bufferedDurationUs >= minAheadUs && bufferAllocator.totalBytesAllocated >= ceilingBytes()

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        if (reserveCore.holdsLoading(parameters) || overCeiling(parameters)) false
        else super.shouldContinueLoading(parameters) || topUp.wantsLoading(
            parameters.bufferedDurationUs, bufferAllocator.totalBytesAllocated.toLong(), allocatorTargetBytes().toLong()
        )

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = startCushion.shouldStart(
        base = super.shouldStartPlayback(parameters) || overCeiling(parameters),
        rebuffering = parameters.rebuffering,
        bufferedDurationUs = parameters.bufferedDurationUs,
        allocatedBytes = bufferAllocator.totalBytesAllocated.toLong(),
        targetBytes = allocatorTargetBytes().toLong(),
    )

    companion object {
        // The allocator counts its bytes in an int, which wraps at 2 GiB and then reads as "not full".
        // 1,600 MB keeps the ceiling (a quarter over) at 2,000 MB.
        const val MAX_BUDGET_BYTES: Long = 1600L * 1024L * 1024L
    }
}
