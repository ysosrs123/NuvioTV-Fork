package com.nuvio.tv.core.assessment

import android.app.Activity
import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.player.DisplayCapabilities
import com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicy
import com.nuvio.tv.core.player.DoviBridge
import com.nuvio.tv.core.player.VodCacheSizing
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.VodCacheSizeMode
import com.nuvio.tv.ui.screens.player.AudioOutputRoute
import com.nuvio.tv.ui.screens.player.AudioOutputRouteDetector
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import kotlin.math.roundToInt

/** Queryable facts behind this assessment; capabilities remain platform claims. */
class AssessmentDeviceFacts internal constructor(
    val safeLimitMb: Int, val warningLimitMb: Int,
    internal val display: DisplayCapabilities.Snapshot,
    internal val policy: DolbyVisionBaseLayerPolicy.Result,
    internal val audioRoute: AudioOutputRoute?,
    internal val nativeFelUsable: Boolean, internal val autoCacheBytes: Long
) {
    // Compare mode values, not framework object identity or list ordering.
    private val modes = display.supportedModes.map {
        listOf(it.modeId, it.physicalWidth, it.physicalHeight, (it.refreshRate * 1000f).roundToInt())
    }.toSet()
    fun sameAs(other: AssessmentDeviceFacts): Boolean =
        safeLimitMb == other.safeLimitMb && warningLimitMb == other.warningLimitMb &&
        display.apiSupported == other.display.apiSupported && display.currentModeId == other.display.currentModeId &&
        display.supportsFrameRateSwitching == other.display.supportsFrameRateSwitching &&
        display.supportsResolutionSwitching == other.display.supportsResolutionSwitching && modes == other.modes &&
        policy == other.policy && audioRoute == other.audioRoute && nativeFelUsable == other.nativeFelUsable &&
        // Existing cache recommendation only changes on useful-cache availability; raw free bytes fluctuate.
        (autoCacheBytes > 0) == (other.autoCacheBytes > 0)

    companion object {
        @androidx.annotation.OptIn(UnstableApi::class)
        internal fun capture(context: Context, activity: Activity?, settings: PlayerSettings): AssessmentDeviceFacts =
            AssessmentDeviceFacts(
                NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context),
                NuvioExoPlayerPerformanceHelper.getWarningNativeMemoryLimitMb(context),
                activity?.let { DisplayCapabilities.detect(it) } ?: DisplayCapabilities.Snapshot.Unknown,
                DolbyVisionBaseLayerPolicy.resolve(context, DoviBridge.isLibraryLoaded),
                AudioOutputRouteDetector.detect(context),
                settings.dv7HandlingMode == com.nuvio.tv.data.local.Dv7HandlingMode.NATIVE_FEL &&
                    com.nuvio.tv.core.player.amlfel.AmlFelSupport.isDeviceUsable(),
                VodCacheSizing.resolveMaxBytes(
                    PlayerMediaSourceFactory.reclaimableVodCacheSpaceBytes(context),
                    VodCacheSizeMode.AUTO,
                    settings.vodCacheSizeMb
                ))
    }
}
