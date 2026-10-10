@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.iptv.LiveAudioOptions
import com.nuvio.tv.core.iptv.LiveDisplayMatch
import com.nuvio.tv.core.iptv.LiveDisplayPlan
import com.nuvio.tv.core.iptv.LiveFrameRate
import com.nuvio.tv.core.iptv.LiveFrameRateChoice
import com.nuvio.tv.core.iptv.LiveResolutionChoice
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.data.iptv.IptvStreamingPreferences
import com.nuvio.tv.data.local.FrameRateMatchingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object IptvLiveDisplayMode {
    suspend fun match(activity: Activity, player: ExoPlayer, mode: FrameRateMatchingMode, resolutionMatching: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (mode == FrameRateMatchingMode.OFF) { FrameRateUtils.restoreOriginalDisplayMode(activity); return }
        val rate = withTimeoutOrNull(DETECT_TIMEOUT_MS) { frameRate(player) } ?: return
        val snapped = FrameRateUtils.snapToStandardRate(rate)
        if (!FrameRateUtils.isNearStandardRate(snapped)) return
        val target = FrameRateUtils.refineFrameRateForDisplay(activity, snapped, rate in 23.95f..23.999f)
        val format = player.videoFormat
        runCatching {
            FrameRateUtils.matchFrameRateAndWait(activity, target, format?.width?.takeIf { it > 0 }, format?.height?.takeIf { it > 0 }, resolutionMatching)
        }
    }

    fun leave(activity: Activity, mode: FrameRateMatchingMode) {
        if (mode == FrameRateMatchingMode.START_STOP) FrameRateUtils.restoreOriginalDisplayMode(activity)
        else { FrameRateUtils.cleanupDisplayListener(); FrameRateUtils.clearOriginalDisplayMode() }
    }

    private suspend fun frameRate(player: ExoPlayer): Float {
        var frames: Long? = null
        var position = 0L
        while (true) {
            player.videoFormat?.frameRate?.takeIf { it > 0f }?.let { return it }
            val counters = player.videoDecoderCounters
            if (player.isPlaying && counters != null) {
                counters.ensureUpdated()
                val count = counters.renderedOutputBufferCount.toLong() + counters.skippedOutputBufferCount + counters.droppedBufferCount
                val start = frames
                if (start == null || count < start || player.currentPosition < position) { frames = count; position = player.currentPosition }
                else LiveFrameRate.estimate(count - start, player.currentPosition - position)?.let { return it }
            } else frames = null
            delay(SAMPLE_MS)
        }
    }

    fun plan(frameRate: LiveFrameRateChoice, resolution: LiveResolutionChoice, nuvioMode: FrameRateMatchingMode, nuvioResolution: Boolean): LiveDisplayPlan =
        LiveAudioOptions.display(frameRate, resolution, LiveDisplayMatch.valueOf(nuvioMode.name), nuvioResolution)

    fun mode(match: LiveDisplayMatch): FrameRateMatchingMode = FrameRateMatchingMode.valueOf(match.name)

    private const val DETECT_TIMEOUT_MS = 15_000L
    private const val SAMPLE_MS = 500L
}

@Composable
internal fun IptvLiveDisplayModeEffect(player: ExoPlayer?, nuvioMode: FrameRateMatchingMode, nuvioResolution: Boolean) {
    val context = LocalContext.current
    val activity = context as? Activity
    val choices by produceState<Pair<LiveFrameRateChoice, LiveResolutionChoice>?>(null, player) {
        value = withContext(Dispatchers.IO) { IptvStreamingPreferences(context).let { it.frameRate to it.resolution } }
    }
    val plan = choices?.let { (frameRate, resolution) -> IptvLiveDisplayMode.plan(frameRate, resolution, nuvioMode, nuvioResolution) }
    val mode = plan?.let { IptvLiveDisplayMode.mode(it.frameRate) }
    val resolutionMatching = plan?.resolution == true
    val currentMode by rememberUpdatedState(mode)
    LaunchedEffect(activity, player, mode, resolutionMatching) {
        if (activity != null && player != null && mode != null) IptvLiveDisplayMode.match(activity, player, mode, resolutionMatching)
    }
    DisposableEffect(activity) {
        onDispose { activity?.let { a -> currentMode?.let { IptvLiveDisplayMode.leave(a, it) } } }
    }
}
