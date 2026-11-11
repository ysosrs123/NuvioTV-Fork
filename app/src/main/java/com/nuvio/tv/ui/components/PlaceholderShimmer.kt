package com.nuvio.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate

private const val PLACEHOLDER_SHIMMER_DISTANCE_PX = 1000f
private const val PLACEHOLDER_SHIMMER_WIDTH_FRACTION = 0.6f
private val PLACEHOLDER_SHIMMER_COLOR_STOPS = arrayOf(
    0.0f to Color.Transparent,
    0.4f to Color.White.copy(alpha = 0.07f),
    0.5f to Color.White.copy(alpha = 0.13f),
    0.6f to Color.White.copy(alpha = 0.07f),
    1.0f to Color.Transparent
)

@Composable
fun rememberPlaceholderShimmerOffsetState(label: String): State<Float> {
    val shimmerTransition = rememberInfiniteTransition(label = label)
    return shimmerTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )
}

fun Modifier.placeholderCardShimmer(
    shimmerOffsetState: State<Float>,
    backgroundColor: Color? = null
): Modifier = drawWithCache {
    // One band, built once and slid across: a new gradient per frame is a new native shader per frame.
    val band = Brush.linearGradient(
        colorStops = PLACEHOLDER_SHIMMER_COLOR_STOPS,
        start = Offset.Zero,
        end = Offset(PLACEHOLDER_SHIMMER_WIDTH_FRACTION * PLACEHOLDER_SHIMMER_DISTANCE_PX, 0f)
    )
    onDrawBehind {
        backgroundColor?.let { drawRect(color = it) }
        val left = shimmerOffsetState.value * PLACEHOLDER_SHIMMER_DISTANCE_PX
        translate(left = left) {
            drawRect(brush = band, topLeft = Offset(-left, 0f), size = size)
        }
    }
}
