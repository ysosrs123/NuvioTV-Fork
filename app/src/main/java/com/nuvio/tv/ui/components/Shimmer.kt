package com.nuvio.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun Modifier.shimmer(active: Boolean = true): Modifier {
    if (!active) return this
    val progress = rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2400, easing = LinearEasing)),
        label = "shimmer_progress"
    )

    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val bandWidth = size.width * 0.8f
            val dim = Color.Black.copy(alpha = 0.35f)
            val stops = arrayOf(
                0f to dim,
                0.5f to Color.Black,
                1f to dim
            )
            val band = Brush.linearGradient(
                colorStops = stops,
                start = Offset.Zero,
                end = Offset(bandWidth, 0f)
            )
            onDrawWithContent {
                drawContent()
                val start = (size.width + bandWidth) * progress.value - bandWidth
                translate(left = start) {
                    drawRect(brush = band, topLeft = Offset(-start, 0f), size = size, blendMode = BlendMode.DstIn)
                }
            }
        }
}
