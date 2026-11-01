package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultAllocator

/** A load control whose byte budget can be lowered while the box is short of memory. */
@UnstableApi
interface BufferBudgetControl {
    val bufferAllocator: DefaultAllocator

    /** Frees the allocator's idle memory once the player stops; the teardown can defer it to a background thread. */
    val stopTrim: AllocatorStopTrim

    /** Budget without the memory-pressure cap. */
    val uncappedBudgetBytes: Long

    /** What the back buffer was fitted to hold at build; a memory-pressure cut leaves it alone. */
    var backBufferBytes: Long

    /** Negative removes the cap. */
    fun setPressureCapBytes(bytes: Long)

    /** Budget added above the size the player was built with, once the box has room again; 0 removes it. */
    fun setGrowthBytes(bytes: Long)

    /** The allocator target the load control would set now. */
    fun allocatorTargetBytes(): Int

    /** The most buffer ahead the player loads. */
    val maxBufferMs: Int

    /** When loading starts again below [maxBufferMs]. */
    val topUp: BufferTopUp
}
