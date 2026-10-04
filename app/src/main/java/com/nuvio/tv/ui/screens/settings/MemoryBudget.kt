package com.nuvio.tv.ui.screens.settings

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.data.local.PlayerSettings

/**
 * Shared memory budget constants and helpers for buffer + parallel connection settings.
 * Used by both PlaybackSettingsViewModel and PlaybackBufferNetworkSettings UI.
 */
@UnstableApi
object MemoryBudget {
    const val TAG = "MemoryBudget"

    // Heap-tiered budget ratios. Low-RAM devices (Fire TV class) need more
    // headroom for codec/surface/UI; high-RAM devices can dedicate more to buffering.
    private const val LOW_HEAP_RATIO = 0.65
    private const val HIGH_HEAP_RATIO = 0.85
    private const val HIGH_HEAP_THRESHOLD_MB = 512L
    // The buffer allocator is on the Java heap; on low-RAM reserve a slice for the UI/decoder/caches
    // and give the rest to the buffer (a flat % of max heap overcommits and starves them).
    private const val LOW_HEAP_RESERVE_MB = 210L

    // Conversion overhead tracks the video rather than the device, so these only hold back a margin;
    // high-RAM keeps a larger one because it still retains a back buffer during conversion.
    private const val LOW_RAM_CONVERSION_RATIO = 0.95f
    private const val HIGH_RAM_CONVERSION_RATIO = 0.75f

    /** ParallelRangeDataSource schedules maxAhead = parallelConnections + 1 chunks concurrently */
    // Matches ParallelRangeDataSource: the session holds up to
    // connections + 2 chunks on low-RAM devices (connections + 4 on high-RAM;
    // the extra headroom is deliberately not advertised so the enforcement
    // maths stays conservative).
    private const val BUFFER_OVERHEAD = 2

    // ParallelRangeDataSource's live prefetch window is clamped to at most
    // 4*connections chunks (sized from a device-RAM budget). The DISPLAYED memory
    // estimate uses this upper bound so the "estimated usage" warning over-estimates
    // rather than under-estimates, the safe direction for a guard.
    // These clamp multiples are the SINGLE SOURCE OF TRUTH for the deep-path
    // window, shared by the runtime depth (PlayerMediaSourceFactory delegates to
    // prefetchDepthChunks below) and the displayed estimate, so the two can never
    // drift. bufferCount and the enforcement maths (maxChunkMb / maxBufferMb / enforce)
    // are deliberately left on the conservative connections+2 accounting so the slider
    // maxima are unchanged.
    private const val PREFETCH_DEPTH_LOWER_MULTIPLE = 2
    private const val PREFETCH_DEPTH_UPPER_MULTIPLE = 4

    const val MIN_CONNECTIONS = 2
    const val MAX_CONNECTIONS = 4
    const val MIN_CHUNK_MB = 8
    const val MAX_CHUNK_MB = 32
    // Low-RAM devices (1-2 GB class): the session's protected set (playhead
    // window + tail moov chunks + pinned side chunks) puts the worst-case
    // retained floor at roughly connections + 10 chunks on a scatter-heavy
    // file, so chunk size is hard-capped at 16 MB on that tier — above it
    // the floor alone exceeds what those devices can hold. Applies in
    // performance mode too; the cap is a tier property, not a budget one.
    private const val LOW_RAM_MAX_CHUNK_MB = 16
    const val BUFFER_STEP_MB = 25
    const val MIN_BUFFER_MB = 25
    // Half the device default floors the slider above what a low-RAM stick can sustain.
    const val MIN_TARGET_BUFFER_MB = 50
    private const val MIN_PLAYBACK_BUDGET_MB = 100
    const val MAX_BUFFER_MB = 1024 * 4
    private const val DEFAULT_EFFECTIVE_BUFFER_MB = 50

    val defaultBufferSizeMb: Int = if (BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB > 0) {
        BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
    } else {
        DEFAULT_EFFECTIVE_BUFFER_MB
    }

    private val maxHeapMb: Long = Runtime.getRuntime().maxMemory() / (1024L * 1024L)

    /** True when the app heap is below the high-RAM threshold (Fire TV / TV-stick class). */
    val isLowRamTier: Boolean = maxHeapMb < HIGH_HEAP_THRESHOLD_MB

    // Pre-cap ratio budget, before the low-RAM reserve trims it below.
    private val rawBudgetMb: Int =
        (maxHeapMb * (if (isLowRamTier) LOW_HEAP_RATIO else HIGH_HEAP_RATIO)).toInt()

    // The flat reserve above collapses the budget on small heaps, so hold a floor that still leaves
    // the smallest devices half their heap.
    private val minPlaybackBudgetMb: Int =
        MIN_PLAYBACK_BUDGET_MB.coerceAtMost((maxHeapMb / 2).toInt()).coerceAtLeast(MIN_BUFFER_MB)

    val budgetMb: Int =
        if (isLowRamTier)
            rawBudgetMb.coerceAtMost((maxHeapMb - LOW_HEAP_RESERVE_MB).toInt()).coerceAtLeast(minPlaybackBudgetMb)
        else rawBudgetMb

    val conversionBudgetMb: Int =
        (budgetMb * (if (isLowRamTier) LOW_RAM_CONVERSION_RATIO else HIGH_RAM_CONVERSION_RATIO)).toInt()
            .coerceAtMost(budgetMb).coerceAtLeast(MIN_BUFFER_MB)

    fun effectiveBufferMb(stored: Int): Int =
        if (stored > 0) stored else defaultBufferSizeMb

    /** Number of chunk-sized buffers alive concurrently */
    fun bufferCount(connectionCount: Int): Int =
        connectionCount + BUFFER_OVERHEAD

    fun parallelOverheadMb(connectionCount: Int, chunkSizeMb: Int): Int =
        bufferCount(connectionCount) * chunkSizeMb

    fun totalUsageMb(bufferMb: Int, connectionCount: Int, chunkSizeMb: Int, parallelEnabled: Boolean): Int =
        bufferMb + if (parallelEnabled) parallelOverheadMb(connectionCount, chunkSizeMb) else 0

    /**
     * Deep-path prefetch depth, the SINGLE SOURCE OF TRUTH shared by the runtime
     * (PlayerMediaSourceFactory.computePrefetchDepthChunks delegates here) and the
     * displayed memory estimate. Spend the safe
     * native budget minus the SampleQueue reserve on chunks, divide by chunk size, clamp
     * to [2*connections, 4*connections].
     */
    fun prefetchDepthChunks(
        connections: Int,
        chunkSizeMb: Int,
        safeNativeLimitMb: Int,
        reserveBufferMb: Int,
    ): Int {
        val chunkMb = chunkSizeMb.coerceAtLeast(1)
        val chunkBudgetMb = (safeNativeLimitMb - reserveBufferMb.coerceAtLeast(0))
            .coerceAtLeast(chunkMb * PREFETCH_DEPTH_LOWER_MULTIPLE)
        val byBudget = chunkBudgetMb / chunkMb
        return byBudget.coerceIn(
            connections * PREFETCH_DEPTH_LOWER_MULTIPLE,
            connections * PREFETCH_DEPTH_UPPER_MULTIPLE
        )
    }

    // Assessment-sweep cell budgeting
    //
    // A sweep cell's true memory ceiling is the tester's prefetch WINDOW, not
    // connections x chunk: ParallelRangeDataSource may hold up to its prefetch
    // depth in live chunks (plus the memory-tiered session-cap headroom). An
    // unconditional connections*4 window would give a 3 conn / 64 MB cell a
    // ceiling near 1 GB on a box with a 250 MB budget.
    //
    // SWEEP_BUDGET_FRACTION reserves headroom for the app's own native use
    // while a sweep runs; SWEEP_CELL_MAX_CONCURRENT_MB is the absolute cap on
    // the nominal concurrent-download footprint (connections x chunk) on ANY
    // device: 3 x 64 MB never runs, on the 4 GB tier included. Both are
    // estimates, tunable like the trade constants.
    private const val SWEEP_BUDGET_FRACTION = 0.75
    const val SWEEP_CELL_MAX_CONCURRENT_MB = 128

    /**
     * Prefetch depth (in chunks) a sweep cell may run with on this device, or
     * null when the cell must NOT be scheduled at all. Null when either the
     * nominal concurrent footprint (connections x chunk) exceeds
     * [SWEEP_CELL_MAX_CONCURRENT_MB], or the budget-derived depth falls below
     * ParallelRangeDataSource's own floor of connections + 1: a window that
     * small cannot be honoured, so the cell cannot be memory-bounded.
     * Single source of truth: the sweep's ladder gate and the tester's window
     * both use this value, so the gate can never drift from the allocation.
     */
    fun sweepCellPrefetchDepth(
        connections: Int,
        chunkSizeMb: Int,
        safeNativeLimitMb: Int
    ): Int? {
        val chunkMb = chunkSizeMb.coerceAtLeast(1)
        if (connections * chunkMb > SWEEP_CELL_MAX_CONCURRENT_MB) return null
        val budgetMb = (safeNativeLimitMb * SWEEP_BUDGET_FRACTION).toInt()
        val depth = (budgetMb / chunkMb)
            .coerceAtMost(connections * PREFETCH_DEPTH_UPPER_MULTIPLE)
        return depth.takeIf { it >= connections + 1 }
    }

    /**
     * Parallel overhead for the DISPLAYED memory-usage estimate only. Rather than
     * always assuming the 4*connections upper clamp (which over-states usage whenever
     * the device budget clamps the window lower, and makes the per-step deltas look
     * inflated), this computes the ACTUAL depth the runtime will use via
     * [prefetchDepthChunks], plus the session's live-chunk headroom (depth + 2 on
     * low-RAM, depth + 4 on high-RAM, mirroring
     * ParallelRangeDataSource.sessionChunkCap). When the deep path is inactive
     * (performance mode off, or MP4 session mode) the runtime window is connections + 1
     * and the estimate says so. Unlike [parallelOverheadMb] (which underpins the slider
     * maxima and must stay conservative), this is display-only.
     */
    fun displayParallelOverheadMb(
        connectionCount: Int,
        chunkSizeMb: Int,
        safeNativeLimitMb: Int,
        reserveBufferMb: Int,
        deepPathActive: Boolean,
    ): Int {
        val depth = if (deepPathActive) {
            prefetchDepthChunks(connectionCount, chunkSizeMb, safeNativeLimitMb, reserveBufferMb)
        } else {
            connectionCount + 1
        }
        val sessionHeadroom = if (isLowRamTier) 2 else 4
        return (depth + sessionHeadroom) * chunkSizeMb
    }

    /** Total usage for the DISPLAYED estimate: buffer plus the exact parallel overhead. */
    fun displayTotalUsageMb(
        bufferMb: Int,
        connectionCount: Int,
        chunkSizeMb: Int,
        parallelEnabled: Boolean,
        safeNativeLimitMb: Int,
        deepPathActive: Boolean,
    ): Int =
        bufferMb + if (parallelEnabled) {
            displayParallelOverheadMb(connectionCount, chunkSizeMb, safeNativeLimitMb, bufferMb, deepPathActive)
        } else {
            0
        }

    /** Hard chunk-size ceiling for this device tier; binds everywhere, including performance mode. */
    val tierMaxChunkMb: Int = if (isLowRamTier) LOW_RAM_MAX_CHUNK_MB else MAX_CHUNK_MB

    /** Max chunk size that fits budget given current buffer size */
    fun maxChunkMb(bufferMb: Int, connectionCount: Int): Int =
        ((budgetMb - bufferMb) / bufferCount(connectionCount)).coerceIn(MIN_CHUNK_MB, tierMaxChunkMb)

    /** Max buffer size that fits budget given current parallel overhead */
    fun maxBufferMb(parallelOverheadMb: Int): Int =
        ((budgetMb - parallelOverheadMb) / BUFFER_STEP_MB * BUFFER_STEP_MB)
            .coerceIn(MIN_BUFFER_MB, MAX_BUFFER_MB)

    /** Slider max for target buffer, optionally raised to 2GB when override is on. */
    fun maxBufferMbWithOverride(parallelOverheadMb: Int, allowLargeTargetBuffer: Boolean): Int {
        val safeMax = maxBufferMb(parallelOverheadMb)
        return if (allowLargeTargetBuffer) {
            PlayerSettings.LARGE_TARGET_BUFFER_MAX_MB
                .coerceAtMost(MAX_BUFFER_MB)
                .coerceAtLeast(safeMax)
        } else {
            safeMax
        }
    }

    /**
     * Enforce budget: reduce chunk first, then buffer as last resort.
     * Returns (adjustedBufferMb, adjustedChunkMb).
     */
    fun enforce(bufferMb: Int, chunkMb: Int, connectionCount: Int): Pair<Int, Int> {
        val buffers = bufferCount(connectionCount)
        if (bufferMb + buffers * chunkMb <= budgetMb) return bufferMb to chunkMb

        val newChunkMb = maxChunkMb(bufferMb, connectionCount)
        if (bufferMb + buffers * newChunkMb <= budgetMb) return bufferMb to newChunkMb

        // Even min chunk doesn't fit, also reduce buffer
        val newBufferMb = ((budgetMb - buffers * MIN_CHUNK_MB) / BUFFER_STEP_MB * BUFFER_STEP_MB)
            .coerceAtLeast(MIN_BUFFER_MB)
        return newBufferMb to MIN_CHUNK_MB
    }

    /**
     * Determines the memory usage status based on total usage, safe limit, and warning limit.
     */
    fun getUsageStatus(
        totalUsageMb: Int,
        safeLimitMb: Int,
        warningLimitMb: Int
    ): MemoryUsageStatus {
        return when {
            totalUsageMb > warningLimitMb -> MemoryUsageStatus.DANGER
            totalUsageMb > safeLimitMb -> MemoryUsageStatus.WARNING
            else -> MemoryUsageStatus.SAFE
        }
    }
}

@UnstableApi
enum class MemoryUsageStatus {
    SAFE,
    WARNING,
    DANGER
}

