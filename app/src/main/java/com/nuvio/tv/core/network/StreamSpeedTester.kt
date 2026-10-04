package com.nuvio.tv.core.network

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.ui.screens.player.ParallelRangeDataSource
import com.nuvio.tv.ui.screens.player.ParallelDiagnosticCalls
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking

@UnstableApi
object StreamSpeedTester {

    /**
     * Headline Mbps plus the per-sub-window Mbps series behind it.
     * [failureReason] is non-null when the pass died: the cell failed, was
     * cleaned up, and the sweep
     * should record it and continue rather than abort or crash.
     */
    data class ParallelPassResult(
        val mbps: Double,
        val subWindowMbps: List<Double>,
        val failureReason: String? = null,
        /**
         * How many times this cell's chunk session tripped the 429
         * rate-limit clamp. Non-zero means the cell did NOT run at its
         * labelled connection count - the clamp drops it to a single
         * connection - so its throughput describes a different
         * configuration than the one under test and must not be measured
         * against the others.
         */
        val clampTrips: Int = 0,
        val measuredBytes: Long = 0,
        val measuredNanos: Long = 0,
        val totalBytes: Long = 0,
        val payloadLimited: Boolean = false
    ) {
        internal fun retryReason(zeroFloor: Double): String? = when {
            failureReason != null -> null
            clampTrips > 0 -> "rate-limited"
            !mbps.isFinite() || mbps < zeroFloor -> "no usable transfer"
            else -> null
        }
    }

    private val baselineTest by lazy {
        BoundedStreamSpeedTest(PlayerPlaybackNetworking.createStreamSpeedTestCallFactory(), DiagnosticPlaybackGuard.shared, coordinator = DiagnosticRunCoordinator.shared)
    }

    // One owned, bounded HTTP response; cancellation propagates to the calling sweep.
    internal suspend fun runBaselineTest(url: String, headers: Map<String, String>): BoundedStreamSpeedTest.Result =
        baselineTest.run(url, headers)

    // 2. Measures parallel connection speed at a specific connection count and
    // chunk size (both swept by the orchestrator).
    suspend fun runParallelChunkTest(
        url: String,
        headers: Map<String, String>,
        chunkSizeBytes: Long,
        parallelConnections: Int,
        // The window is the CALLER's budget-derived figure
        // (MemoryBudget.sweepCellPrefetchDepth), not a fixed connections*4,
        // which on a 3 conn / 64 MB cell would allow a ~1 GB session against
        // a 250 MB safe native budget.
        prefetchDepthChunks: Int
    ): ParallelPassResult {
        val result = parallelTest.run { budget ->
            val calls = ParallelDiagnosticCalls(parallelConnections, payload = budget)
            try {
                val dataSource = ParallelRangeDataSource(
                    upstreamFactory = OkHttpDataSource.Factory(calls).apply { setDefaultRequestProperties(headers) },
                    parallelConnections = parallelConnections,
                    chunkSize = chunkSizeBytes,
                    useNativeMemory = true,
                    prefetchDepthChunks = prefetchDepthChunks,
                    isolateSession = true
                )
                object : BoundedParallelSpeedTest.Session {
                    override fun open() { dataSource.open(DataSpec(android.net.Uri.parse(url))) }
                    override fun read(buffer: ByteArray) = dataSource.read(buffer, 0, buffer.size)
                    override val clampTrips get() = dataSource.diagnosticClampTrips
                    override fun cancel() = calls.close()
                    override fun close() = dataSource.close()
                    override val isQuiescent get() = calls.isQuiescent && dataSource.diagnosticWorkersStopped
                }
            } catch (t: Throwable) { calls.close(); throw t }
        }
        return ParallelPassResult(
            result.mbps ?: 0.0, result.samples, result.failure, result.clampTrips,
            result.measuredBytes, result.measuredNanos, result.totalBytes, result.payloadLimited
        )
    }

    private val parallelTest by lazy { BoundedParallelSpeedTest(DiagnosticPlaybackGuard.shared, coordinator = DiagnosticRunCoordinator.shared) }

    private val contentLengthProbe by lazy {
        StreamContentLengthProbe(PlayerPlaybackNetworking.createContentLengthProbeCallFactory(), coordinator = DiagnosticRunCoordinator.shared)
    }

    suspend fun getStreamContentLength(url: String, headers: Map<String, String>): Long =
        contentLengthProbe.probe(url, headers)
}
