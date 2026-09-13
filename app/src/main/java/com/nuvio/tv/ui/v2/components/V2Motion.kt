package com.nuvio.tv.ui.v2.components

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.FocusStyle

object V2Motion {
    const val FocusResponseMs = 100
    const val FocusSettleMs = 160
    const val PanelInMs = 220
    const val PanelOutMs = 180
    const val BackdropCrossfadeMs = 320
    const val BackdropDebounceMs = 200L
}

@Immutable
data class FocusTransform(val scale: Float, val liftDp: Float) {
    companion object {
        fun forStyle(style: FocusStyle) = when (style) {
            FocusStyle.GLASS_LIFT -> FocusTransform(1.035f, 2f)
            FocusStyle.CINEMATIC_FOCUS -> FocusTransform(1.05f, 3f)
        }
    }
}
