package com.nuvio.tv.ui.v2.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.quality.LocalGlassTokens

/** Decorates an existing focus target without adding another focus node or measuring again. */
@Composable
fun Modifier.nuvioV2Focus(focused: Boolean, shape: Shape): Modifier {
    val appearance = LocalV2Appearance.current ?: return this
    val transform = FocusTransform.forStyle(appearance.focusStyle)
    val tokens = LocalGlassTokens.current
    val density = LocalDensity.current
    val accent = NuvioTheme.colors.Primary
    val progress = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(if (focused) V2Motion.FocusResponseMs else V2Motion.FocusSettleMs),
        label = "v2Focus"
    )
    return graphicsLayer {
        // Read animated state in the layer, not in composition or measurement.
        val p = progress.value
        scaleX = 1f + (transform.scale - 1f) * p
        scaleY = scaleX
        translationY = -transform.liftDp * density.density * p
        shadowElevation = tokens.shadowDp * density.density * p
        this.shape = shape
        clip = false
        ambientShadowColor = accent.copy(alpha = tokens.focusBloomAlpha)
        spotShadowColor = Color.Black
    }.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = Stroke(2.dp.toPx())
        onDrawWithContent {
            drawContent()
            val p = progress.value
            if (p > 0f) drawOutline(outline, accent, alpha = p, style = stroke)
        }
    }
}
