package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.VodCacheSizeMode

/**
 * VOD disk-cache sizing maths, extracted unchanged from
 * PlayerMediaSourceFactory (resolveVodCacheMaxBytes /
 * resolveRuntimeVodCacheUpperBoundBytes) so the runtime and the Device
 * Assessment display resolve the SAME figure from the same formula.
 *
 * The caller reads the cache volume's free space ONCE and passes it in. The
 * inline original read usableSpace twice in quick succession (once per
 * function); a single read is equivalent for any stable value and removes a
 * benign race, which is the only intended behavioural difference.
 */
object VodCacheSizing {

    const val FREE_SPACE_RESERVE_BYTES = 1024L * 1024L * 1024L
    const val AUTO_FLOOR_BYTES = 2L * 1024L * 1024L * 1024L

    /**
     * Free-space-derived ceiling: keep a 1 GB reserve when free space allows,
     * else cap at 80% of what is free; floor 1 MB; never above [hardMaxBytes].
     */
    fun runtimeUpperBoundBytes(freeSpaceBytes: Long, hardMaxBytes: Long): Long {
        val headroomAdjusted = if (freeSpaceBytes > FREE_SPACE_RESERVE_BYTES) {
            freeSpaceBytes - FREE_SPACE_RESERVE_BYTES
        } else {
            (freeSpaceBytes * 8L) / 10L
        }
        return headroomAdjusted.coerceAtLeast(1L * 1024L * 1024L).coerceAtMost(hardMaxBytes)
    }

    /**
     * Resolved cache cap in bytes. 0 = not enough free space to host a useful
     * cache (the caller streams direct). MANUAL clamps the stored size to the
     * runtime ceiling; AUTO takes 20% of free space, at least 2 GB, within
     * [min, ceiling]. [freeSpaceBytes] includes what the cache already holds,
     * since that space is reclaimable.
     */
    fun resolveMaxBytes(
        freeSpaceBytes: Long,
        mode: VodCacheSizeMode,
        manualSizeMb: Int
    ): Long {
        val minBytes = PlayerSettings.MIN_VOD_CACHE_SIZE_MB.toLong() * 1024L * 1024L
        val maxBytes = PlayerSettings.MAX_VOD_CACHE_SIZE_MB.toLong() * 1024L * 1024L
        val runtimeMaxBytes = runtimeUpperBoundBytes(freeSpaceBytes, maxBytes)
        // Not enough free space to host a useful cache: skip it (0 = caller streams direct).
        if (runtimeMaxBytes < minBytes) return 0L
        val manualBytes = manualSizeMb
            .coerceIn(PlayerSettings.MIN_VOD_CACHE_SIZE_MB, PlayerSettings.MAX_VOD_CACHE_SIZE_MB)
            .toLong() * 1024L * 1024L
        val resolvedManualBytes = manualBytes.coerceAtMost(runtimeMaxBytes)

        if (mode == VodCacheSizeMode.MANUAL) return resolvedManualBytes

        // Kept for line-parity with the original even though the earlier
        // runtime-ceiling floor already returns 0 whenever free space is <= 0.
        if (freeSpaceBytes <= 0L) return resolvedManualBytes
        return autoBytes(freeSpaceBytes, minBytes, runtimeMaxBytes)
    }

    fun autoBytes(freeSpaceBytes: Long, minBytes: Long, runtimeMaxBytes: Long): Long =
        maxOf(AUTO_FLOOR_BYTES, freeSpaceBytes / 5L).coerceIn(minBytes, runtimeMaxBytes)
}
