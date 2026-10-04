package com.nuvio.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex

/** Drawn above every sibling in the root Surface, including the pre-navigation gate screens. */
private const val SCREENSAVER_OVERLAY_Z_INDEX = 100f

/**
 * OLED idle dimmer: a plain black scrim faded in over whatever is on screen.
 *
 * Purely visual - it is not focusable and consumes nothing; key handling while the
 * overlay is visible happens in MainActivity.dispatchKeyEvent, which swallows the
 * waking press (and its key-up) so waking never also navigates.
 */
@Composable
fun ScreensaverOverlay(
    visible: Boolean,
    dimPercent: Int,
    modifier: Modifier = Modifier
) {
    val dimAlpha by animateFloatAsState(
        targetValue = if (visible) dimPercent.coerceIn(0, 100) / 100f else 0f,
        animationSpec = tween(durationMillis = if (visible) 1400 else 250),
        label = "screensaverDimAlpha"
    )
    if (dimAlpha > 0.001f) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .zIndex(SCREENSAVER_OVERLAY_Z_INDEX)
                .background(Color.Black.copy(alpha = dimAlpha))
        )
    }
}
