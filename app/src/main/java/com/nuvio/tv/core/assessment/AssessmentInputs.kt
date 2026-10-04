package com.nuvio.tv.core.assessment

import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.data.local.PlayerSettings

/** Private in-memory provenance. Never serializes or prints source URLs/headers. */
internal class AssessmentInputs private constructor(
    private val settings: List<Any?>, private val source: List<Any?>
) {
    fun matches(settings: PlayerSettings, diagnostics: LastPlaybackDiagnostics): Boolean =
        sameAs(capture(settings, diagnostics))
    fun sameAs(other: AssessmentInputs): Boolean = settings == other.settings && source == other.source
    companion object {
        fun capture(s: PlayerSettings, d: LastPlaybackDiagnostics) = AssessmentInputs(
            listOf(s.internalPlayerEngine, s.nuvioPerformanceModeEnabled,
                s.bufferEngineEnabled, s.parallelNetworkEnabled, s.bufferBudgetManaged,
                s.allowLargeTargetBuffer, s.bufferSettings, s.useParallelConnections,
                s.parallelConnectionCount, s.parallelChunkSizeKb, s.enableHttp2,
                s.vodCacheEnabled, s.vodCacheSizeMode, s.vodCacheSizeMb,
                s.frameRateMatchingMode, s.resolutionMatchingEnabled, s.dv7HandlingMode,
                s.dv5ToDv81Enabled, s.stripHdr10PlusSei, s.forceOpticalPassthrough,
                s.tunnelingEnabled),
            listOf(d.timestampMs, d.streamUrl, d.headersJson, d.filename, d.host,
                d.videoBitrate, d.durationMs, d.dvSourceProfile, d.videoHdrType))
    }
}
