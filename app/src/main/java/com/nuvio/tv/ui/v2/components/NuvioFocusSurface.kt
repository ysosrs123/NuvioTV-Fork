package com.nuvio.tv.ui.v2.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
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
/** [hardwareShadow] false for see-through glass: a hardware shadow shows through it as a polygon. */
fun Modifier.nuvioV2Focus(focused: Boolean, shape: Shape, accentOverride: Color? = null, hardwareShadow: Boolean = true, stationary: Boolean = false): Modifier {
    // Stationary focus snaps off: idle posters have no outgoing animation or decoration.
    // Do not create per-card animation jobs, draw caches and glint state for an invisible edge.
    // This also keeps idle cards out of the artwork/theme observation path.
    if (stationary && !focused) return this
    val appearance = LocalV2Appearance.current ?: return this
    val clearGlass = usesClearGlassTheme() || (NuvioTheme.currentTheme == com.nuvio.tv.domain.model.AppTheme.GLASS &&
        appearance.focusStyle == com.nuvio.tv.domain.model.FocusStyle.CINEMATIC_FOCUS)
    val transform = FocusTransform.forStyle(appearance.focusStyle)
    val tokens = LocalGlassTokens.current
    val cinematic = appearance.focusStyle == com.nuvio.tv.domain.model.FocusStyle.CINEMATIC_FOCUS
    val maximumFocus = com.nuvio.tv.ui.v2.quality.LocalVisualQuality.current.tier ==
        com.nuvio.tv.ui.v2.quality.VisualQualityTier.MAXIMUM
    val density = LocalDensity.current
    val accent = NuvioTheme.colors.Secondary
    val artwork = com.nuvio.tv.ui.v2.appearance.LocalArtworkAccent.current
    val adaptive = NuvioTheme.currentTheme != com.nuvio.tv.domain.model.AppTheme.GLASS &&
        appearance.accentMode == com.nuvio.tv.domain.model.AccentMode.ADAPTIVE_ARTWORK
    val focusColors = com.nuvio.tv.ui.v2.appearance.v2AccentColors(focus = true, override = accentOverride)
    val progress = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = if (stationary) androidx.compose.animation.core.snap()
            else tween(if (focused) V2Motion.FocusResponseMs else V2Motion.FocusSettleMs),
        label = "v2Focus"
    )
    val visible = remember(progress) { derivedStateOf { progress.value > 0f } }
    val glassDecoration = if (clearGlass && cinematic) Modifier.glassFocusEdge(
        shape, focused, progress, artworkColor = artwork?.color.takeUnless {
            com.nuvio.tv.ui.v2.appearance.LocalResolvedAppearance.current?.playbackActive == true
        }
    ) else Modifier
    // Stationary posters never transform or cast a shadow. Avoid a RenderNode per
    // cached card and, especially, subscribing every idle shadow to hero accent changes.
    val transformLayer = if (stationary) Modifier else Modifier.graphicsLayer {
        // Read animated state in the layer, not in composition or measurement.
        val p = progress.value
        scaleX = if (stationary) 1f else 1f + (transform.scale - 1f) * p
        scaleY = scaleX
        translationY = if (stationary) 0f else -transform.liftDp * density.density * p
        shadowElevation = if (hardwareShadow && !stationary && !clearGlass) tokens.shadowDp * density.density * p else 0f
        this.shape = shape
        clip = false
        ambientShadowColor = if (p > 0f && hardwareShadow && !clearGlass)
            (accentOverride ?: if (adaptive) artwork?.color?.value ?: accent else accent).copy(alpha = tokens.focusBloomAlpha)
            else Color.Transparent
        spotShadowColor = Color.Black
    }
    return then(transformLayer).then(glassDecoration).drawWithCache {
        if ((clearGlass && cinematic) || !visible.value) {
            return@drawWithCache onDrawWithContent { drawContent() }
        }
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = Stroke((if (clearGlass && cinematic) 2.5.dp else if (clearGlass) 1.5.dp else 2.dp).toPx())
        val contrastStroke = Stroke(3.dp.toPx())
        // Closely spaced, low-alpha layers avoid visible concentric bands on TV panels.
        val bloomWidths = when {
            clearGlass && cinematic -> listOf(16, 11, 7, 4)
            clearGlass -> emptyList()
            !cinematic -> listOf(3)
            maximumFocus -> listOf(20, 14, 10, 7, 5, 3)
            tokens.edgeLayers <= 1 -> listOf(10, 6, 3)
            tokens.edgeLayers == 2 -> listOf(12, 8, 5, 3)
            else -> listOf(20, 14, 10, 7, 5, 3)
        }
        val blooms = bloomWidths.map { width ->
            val falloff = (25f - width) / 22f
            val bloomAlpha = if (clearGlass) .16f else if (maximumFocus) .16f else tokens.focusBloomAlpha.coerceAtLeast(.10f)
            Stroke(width.dp.toPx()) to (if (!cinematic) .10f else (.015f + .16f * falloff * falloff) * (bloomAlpha / .10f))
        }
        val bloomBrush = if (clearGlass) androidx.compose.ui.graphics.Brush.linearGradient(listOf(
            Color(0xFFDBF3FF), Color.White, Color(0xFFC6D6EE)
        )) else com.nuvio.tv.ui.theme.createThemeBrush(focusColors)
        val edgeBrush = if (clearGlass) androidx.compose.ui.graphics.Brush.verticalGradient(if (cinematic) listOf(
            Color.White, Color(0xFFB7E8FF).copy(alpha = .85f), Color.White.copy(alpha = .96f)
        ) else listOf(Color.White.copy(alpha = .90f), Color.White.copy(alpha = .48f), Color.White.copy(alpha = .72f)))
        else com.nuvio.tv.ui.theme.createThemeBrush(focusColors.map {
            androidx.compose.ui.graphics.lerp(it, Color.White, .7f)
        })
        onDrawWithContent {
            drawContent()
            val p = progress.value
            if (p > 0f && !(clearGlass && cinematic)) {
                if (clearGlass) drawOutline(outline, Color.Black, alpha = p * .38f, style = contrastStroke)
                blooms.forEach { (width, alpha) -> drawOutline(outline, bloomBrush, alpha = p * alpha, style = width) }
                drawOutline(outline, edgeBrush, alpha = p, style = stroke)
            }
        }
    }
}
