package com.nuvio.tv.core.player.thumbnail

/**
 * Memory for 4K thumbnail decodes, taken out of playback's read-ahead. A high-bitrate remux fills the whole byte
 * budget, so the read-ahead is capped [reserve] bytes below it until [release].
 */
interface PlaybackBufferReserve {
    val readAheadBudgetBytes: Long

    /** Read-ahead bytes in use. */
    val bufferedBytes: Long

    /** Bytes the allocator holds from the system (in use + pooled). */
    val footprintBytes: Long

    /** 0 = none. */
    val reservedBytes: Long

    /** True after a rebuffer blamed on the reserve: for 5 stall-free minutes after the first, for good after a second. */
    val cancelledByRebuffer: Boolean

    /** SystemClock.elapsedRealtime of the most recent stall, 0 = none. Seeks do not count. */
    val lastStallRealtimeMs: Long

    /** Caps the read-ahead [bytes] below the budget, never below the minimum buffered time. */
    fun reserve(bytes: Long)

    /** Returns pooled buffer memory above the cap to the system. Result: bytes freed. */
    fun giveBack(): Long

    fun release()
}
