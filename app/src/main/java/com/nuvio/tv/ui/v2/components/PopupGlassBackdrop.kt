package com.nuvio.tv.ui.v2.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.v2.appearance.LocalResolvedAppearance
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.quality.LocalGlassTokens
import com.nuvio.tv.ui.v2.quality.LocalVisualQuality
import com.nuvio.tv.ui.v2.quality.VisualQualityTier
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

class PopupGlassBackdrop {
    val state = HazeState()
    var consumers by mutableIntStateOf(0)
        private set
    var liveTrailers by mutableIntStateOf(0)
        private set
    fun attachTrailer() { liveTrailers++ }
    fun detachTrailer() { liveTrailers = (liveTrailers - 1).coerceAtLeast(0) }
    fun attach() { consumers++ }
    fun detach() { consumers = (consumers - 1).coerceAtLeast(0) }
}

val LocalPopupGlassBackdrop = staticCompositionLocalOf<PopupGlassBackdrop?> { null }

/** Capture the page below separate dialog windows only while a popup needs it. */
@Composable
fun PopupGlassSource(content: @Composable () -> Unit) {
    val backdrop = LocalPopupGlassBackdrop.current
    val enabled = backdrop != null && backdrop.consumers > 0 && LocalGlassTokens.current.liveBlur &&
        LocalV2Appearance.current?.visualStyle == VisualStyle.CINEMATIC_GLASS &&
        LocalResolvedAppearance.current?.playbackActive != true
    val refreshVideo = enabled && backdrop?.liveTrailers != 0 &&
        LocalVisualQuality.current.tier == VisualQualityTier.MAXIMUM
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(refreshVideo) {
        if (refreshVideo) {
            var lastFrame = 0L
            while (true) {
                withFrameNanos { now ->
                    if (now - lastFrame >= 33_000_000L) {
                        frame.longValue = now
                        lastFrame = now
                    }
                }
            }
        }
    }
    Box(
        modifier = if (enabled && backdrop != null) Modifier.hazeSource(backdrop.state).drawWithContent {
            // TextureView frames do not invalidate an ancestor's captured layer.
            // Refresh only during a visible popup and a moving compatible trailer.
            if (refreshVideo) frame.longValue
            drawContent()
        } else Modifier,
        propagateMinConstraints = true
    ) { content() }
}
