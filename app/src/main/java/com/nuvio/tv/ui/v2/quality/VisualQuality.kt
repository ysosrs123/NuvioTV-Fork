package com.nuvio.tv.ui.v2.quality

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.nuvio.tv.domain.model.VisualQualityMode

enum class VisualQualityTier { PERFORMANCE, ENHANCED, MAXIMUM }

@Immutable
data class UiRenderCapabilities(
    val api: Int,
    val lowRam: Boolean,
    val totalRamBytes: Long,
    val heapMb: Int,
    val hardwareAccelerated: Boolean,
    val rootWidthPx: Int,
    val rootHeightPx: Int,
    val glEsVersion: Int
) {
    val supportsLiveBlur: Boolean get() = api >= 31 && hardwareAccelerated && glEsVersion >= 0x30000
}

@Immutable
data class VisualQualityDecision(val tier: VisualQualityTier, val reason: String, val supportsLiveBlur: Boolean)

/** Conservative starting policy, not a performance score or an adaptive governor. */
object VisualQualityResolver {
    fun automatic(capabilities: UiRenderCapabilities): VisualQualityDecision {
        val reason = when {
            !capabilities.supportsLiveBlur -> "Static material: graphics capability"
            capabilities.lowRam -> "Static material: low-RAM device"
            capabilities.totalRamBytes <= 0 || capabilities.heapMb <= 0 -> "Static material: unknown memory budget"
            capabilities.totalRamBytes < 2_500_000_000L || capabilities.heapMb < 256 -> "Static material: constrained memory budget"
            capabilities.rootWidthPx <= 0 || capabilities.rootHeightPx <= 0 -> "Static material: waiting for root bounds"
            capabilities.rootWidthPx.toLong() * capabilities.rootHeightPx > 4_000_000L -> "Static material: large rendering surface"
            else -> null
        }
        return VisualQualityDecision(
            if (reason == null) VisualQualityTier.ENHANCED else VisualQualityTier.PERFORMANCE,
            reason ?: "Selective glass: initial capability assessment",
            capabilities.supportsLiveBlur
        )
    }

    fun resolve(mode: VisualQualityMode, capabilities: UiRenderCapabilities): VisualQualityDecision {
        if (mode == VisualQualityMode.AUTOMATIC) return automatic(capabilities)
        val tier = when (mode) {
            VisualQualityMode.PERFORMANCE -> VisualQualityTier.PERFORMANCE
            VisualQualityMode.ENHANCED -> VisualQualityTier.ENHANCED
            VisualQualityMode.MAXIMUM -> VisualQualityTier.MAXIMUM
            VisualQualityMode.AUTOMATIC -> error("Handled above")
        }
        return VisualQualityDecision(tier, "Manual ${tier.name.lowercase()}", capabilities.supportsLiveBlur)
    }
}

/** Effect cost only: never contains card sizes, spacing, text sizes or focus destinations. */
@Immutable
data class GlassQualityTokens(
    val liveBlur: Boolean,
    val blurRadiusDp: Float,
    val inputScale: Float,
    val surfaceAlpha: Float,
    val noise: Float,
    val edgeLayers: Int,
    val focusBloomAlpha: Float,
    val shadowDp: Float
) {
    companion object {
        val Performance = GlassQualityTokens(false, 0f, 0f, 0.96f, 0f, 1, 0.04f, 2f)

        fun resolve(decision: VisualQualityDecision, playback: Boolean): GlassQualityTokens {
            // Decoded video is never a live glass source, even with Maximum selected.
            if (playback || decision.tier == VisualQualityTier.PERFORMANCE) return Performance
            val tokens = when (decision.tier) {
                VisualQualityTier.ENHANCED -> GlassQualityTokens(true, 16f, 0.33f, 0.82f, 0.015f, 2, 0.10f, 6f)
                VisualQualityTier.MAXIMUM -> GlassQualityTokens(true, 24f, 0.50f, 0.74f, 0.025f, 3, 0.16f, 10f)
                VisualQualityTier.PERFORMANCE -> Performance
            }
            return if (decision.supportsLiveBlur) tokens else tokens.copy(
                liveBlur = false, blurRadiusDp = 0f, inputScale = 0f, surfaceAlpha = 0.96f, noise = 0f
            )
        }
    }
}

val LocalVisualQuality = staticCompositionLocalOf {
    VisualQualityDecision(VisualQualityTier.PERFORMANCE, "Not assessed", false)
}
val LocalGlassTokens = staticCompositionLocalOf { GlassQualityTokens.Performance }
