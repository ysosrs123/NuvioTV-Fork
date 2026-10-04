package com.nuvio.tv.core.assessment

import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.VodCacheSizeMode
import com.nuvio.tv.ui.screens.settings.MemoryUsageStatus

/**
 * Confidence tiers for Device Assessment rows. The tier states HOW a
 * recommendation was derived, so the UI can be honest about certainty:
 *
 *  MEASURED   - tested on this device against the real last-played stream
 *               (the sweep). Highest confidence.
 *  CALCULATED - derived from queryable device facts (RAM tier, display
 *               modes, HDR capability reads, decoder lists) plus the
 *               documented behaviour of the setting. No measurement ran.
 *  PRIORITY   - a genuine trade-off with no device-derivable answer; the
 *               user picks an intent profile. Never auto-applied.
 *  VERIFY     - a recommendation exists but the deciding fact is not
 *               queryable on this device (unknown HDR caps, uninspectable
 *               display, tunnelling quirks). Eyes-on verification advised.
 */
enum class AssessmentTier { MEASURED, CALCULATED, PRIORITY, VERIFY }

enum class ProfileKind { FAST_START, BALANCED, STALL_RESISTANT }

data class AssessmentItem(
    /** Stable identifier, e.g. "parallel", "target_buffer". */
    val key: String,
    val title: String,
    /** Localised display of the current setting; null when not meaningful. */
    val currentValue: String?,
    val recommendedValue: String,
    /** One-line, honest "why" - names the fact or measurement behind it. */
    val grounds: String,
    val tier: AssessmentTier,
    /** True when the current setting differs from the recommendation. */
    val changeNeeded: Boolean,
    /** Safe/warning/danger colouring for memory-bearing rows, else null. */
    val memoryStatus: MemoryUsageStatus? = null
)

data class IntentProfileOption(
    val kind: ProfileKind,
    val title: String,
    val subtitle: String,
    val initialBufferMs: Int,
    val rebufferMs: Int,
    val minBufferMs: Int,
    /** Set when the byte-derived ceiling capped this profile's min buffer. */
    val minCappedFromMs: Int? = null
)

enum class AssessmentBitrateScope { UNKNOWN, VIDEO_TRACK, FILE_AVERAGE }

data class AssessmentHeaderFacts(
    val deviceRamLabel: String,
    val safeLimitMb: Int,
    val warningLimitMb: Int,
    /** "3840x2160 · 24/50/60 Hz" style summary, null when uninspectable. */
    val displaySummary: String?,
    /** Filename (preferred) or host of the last-played stream, null when none. */
    val streamLabel: String?,
    val streamBitrateMbps: Double?,
    val streamDvProfile: String?,
    val streamHdrType: String?,
    val sourceObservedAtMs: Long = 0L,
    val bitrateScope: AssessmentBitrateScope = AssessmentBitrateScope.UNKNOWN
)

data class AssessmentResult(
    val timestampMs: Long,
    val header: AssessmentHeaderFacts,
    val sweepRan: Boolean,
    val sweepVerdictText: String?,
    val items: List<AssessmentItem>,
    val profiles: List<IntentProfileOption>,
    /** Stability-derived suggestion among the profiles; null = no signal. */
    val suggestedProfile: ProfileKind?,
    val stabilityCoV: Double?,
    val stabilityPassCount: Int,
    /** Machine-actionable side of the items: null field = never touched. */
    val applyPlan: AssessmentApplyPlan,
    val errorText: String?,
    val deviceFacts: AssessmentDeviceFacts? = null
)

/**
 * Only MEASURED/CALCULATED rows with changeNeeded populate fields; VERIFY
 * rows and no-change rows stay null by construction. The Internal Engine
 * row is deliberately advisory-only and never enters the plan.
 */
data class AssessmentApplyPlan(
    val nuvioPerformanceModeEnabled: Boolean? = null,
    val bufferEngineEnabled: Boolean? = null,
    val parallelNetworkEnabled: Boolean? = null,
    val bufferBudgetManaged: Boolean? = null,
    val allowLargeTargetBuffer: Boolean? = null,
    val targetBufferSizeMb: Int? = null,
    val maxBufferMs: Int? = null,
    val useParallelConnections: Boolean? = null,
    val parallelConnectionCount: Int? = null,
    val parallelChunkSizeKb: Int? = null,
    val enableHttp2: Boolean? = null,
    val vodCacheEnabled: Boolean? = null,
    val vodCacheSizeMode: VodCacheSizeMode? = null,
    val frameRateMatchingMode: FrameRateMatchingMode? = null,
    val resolutionMatchingEnabled: Boolean? = null,
    val dv7HandlingMode: Dv7HandlingMode? = null,
    val dv5ToDv81Enabled: Boolean? = null,
    val stripHdr10PlusSei: Boolean? = null,
    val forceOpticalPassthrough: Boolean? = null
) {
    val touchedCount: Int
        get() = listOfNotNull(
            nuvioPerformanceModeEnabled, bufferEngineEnabled, parallelNetworkEnabled,
            bufferBudgetManaged, allowLargeTargetBuffer, targetBufferSizeMb,
            maxBufferMs, useParallelConnections, parallelConnectionCount,
            parallelChunkSizeKb, enableHttp2, vodCacheEnabled, vodCacheSizeMode,
            frameRateMatchingMode, resolutionMatchingEnabled, dv7HandlingMode,
            dv5ToDv81Enabled, stripHdr10PlusSei, forceOpticalPassthrough
        ).size
}
