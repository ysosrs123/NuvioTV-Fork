package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Hands an allocator's idle memory back when its player stops or is released, as media3 does with trimOnReset on.
 * The allocator is built with trimOnReset off so this class decides where that happens: with [deferred] set, the
 * player's teardown calls [trimInBackground] instead, and the main thread no longer waits while the playback thread
 * frees a buffer of up to a gigabyte.
 */
@UnstableApi
class AllocatorStopTrim(
    private val allocator: DefaultAllocator,
    private val background: (Runnable) -> Unit = { work ->
        Thread(work, "AllocatorTrim").apply { isDaemon = true }.start()
    },
    private val settleMs: Long = SETTLE_MS
) {
    @Volatile var deferred = false

    fun onPlayerGone() {
        if (!deferred) allocator.setTargetBufferSize(0)
    }

    /** Pieces a cancelled load gives back after the first pass are freed by the second. */
    fun trimInBackground() {
        background(Runnable {
            allocator.setTargetBufferSize(0)
            if (settleMs > 0L) {
                try {
                    Thread.sleep(settleMs)
                } catch (_: InterruptedException) {
                    return@Runnable
                }
            }
            allocator.trim()
        })
    }

    private companion object {
        const val SETTLE_MS = 2_000L
    }
}
