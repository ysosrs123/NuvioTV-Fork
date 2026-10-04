package com.nuvio.tv.ui.v2.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/** A fine outer rim with moving specular highlights. Never redraws or samples the content. */
@Composable
internal fun Modifier.glassFocusEdge(
    shape: Shape,
    focused: Boolean,
    progress: State<Float>,
    artworkColor: State<Color>? = null
): Modifier {
    val position = remember { mutableStateOf(Offset.Zero) }
    val visible = remember(progress) { derivedStateOf { progress.value > 0f } }
    val arrival = remember { Animatable(1f) }
    val variation = remember { mutableStateOf(newGlassGlintVariation()) }
    LaunchedEffect(focused) {
        if (focused) {
            variation.value = newGlassGlintVariation()
            arrival.snapTo(0f)
            arrival.animateTo(1f, tween(variation.value.durationMs, easing = FastOutSlowInEasing))
        }
    }
    // Only the focused item tracks placement. Scroll/layout updates invalidate drawing,
    // not composition or measurement; the glints stop when navigation settles.
    val placement = if (focused) Modifier.onGloballyPositioned {
        position.value = it.positionInRoot()
    } else Modifier
    return then(placement).drawWithCache {
        if (!visible.value) return@drawWithCache onDrawWithContent { drawContent() }
        val contentPath = shape.createOutline(size, layoutDirection, this).asGlassPath()
        val outset = 1.25.dp.toPx()
        val outline = shape.createOutline(
            Size(size.width + outset * 2, size.height + outset * 2), layoutDirection, this
        )
        val perimeter = PathMeasure().apply { setPath(outline.asGlassPath(), true) }
        val hairline = Stroke(1.15.dp.toPx())
        val glintStroke = Stroke(1.65.dp.toPx())
        val haloStroke = Stroke(4.dp.toPx())
        val radius = (size.minDimension * .27f).coerceIn(18.dp.toPx(), 52.dp.toPx())
        onDrawWithContent {
            drawContent()
            val p = progress.value
            if (p > 0f && perimeter.length > 0f) {
                val screen = position.value / density
                // Position changes shift the light around the perimeter. A short entry
                // drift also gives successive focused cards life when a row anchors them
                // at the same screen position. No perpetual orbit or flashing pulse.
                val light = variation.value
                val phase = ((light.start + screen.x * .00037f + screen.y * .00053f +
                    (1f - arrival.value) * light.drift) % 1f + 1f) % 1f
                val tint = lerp(Color.White, artworkColor?.value ?: Color(0xFFD6F0FF), .16f)
                val first = perimeter.getPosition(perimeter.length * phase)
                val second = perimeter.getPosition(perimeter.length * ((phase + light.separation) % 1f))
                val glints = listOf(first to 1f, second to .82f).map { (point, strength) ->
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = strength),
                        .16f to tint.copy(alpha = .95f * strength),
                        .5f to tint.copy(alpha = .35f * strength),
                        1f to Color.Transparent,
                        center = point, radius = radius
                    )
                }
                // Exclude the original shape even from the tiny halo: no inner bevel,
                // fill, magnification, image replay, or dark seam over the artwork.
                clipPath(contentPath, clipOp = ClipOp.Difference) {
                    translate(-outset, -outset) {
                        drawOutline(outline, tint, alpha = .62f * p, style = hairline)
                        glints.forEach { brush ->
                            drawOutline(outline, brush, alpha = .20f * p, style = haloStroke)
                            drawOutline(outline, brush, alpha = p, style = glintStroke)
                        }
                    }
                }
            }
        }
    }.then(if (focused) Modifier.graphicsLayer() else Modifier)
}

private fun Outline.asGlassPath(): Path = Path().apply {
    when (val outline = this@asGlassPath) {
        is Outline.Rectangle -> addRect(outline.rect)
        is Outline.Rounded -> addRoundRect(outline.roundRect)
        is Outline.Generic -> addPath(outline.path)
    }
}

/** Sample once per focus entry, never per frame: motion remains smooth and bounded. */
private data class GlassGlintVariation(val start: Float, val drift: Float, val separation: Float, val durationMs: Int)
private fun newGlassGlintVariation(): GlassGlintVariation = GlassGlintVariation(
    start = Random.nextFloat(),
    drift = (.07f + Random.nextFloat() * .07f) * if (Random.nextBoolean()) 1f else -1f,
    separation = .32f + Random.nextFloat() * .32f,
    durationMs = Random.nextInt(480, 701)
)
