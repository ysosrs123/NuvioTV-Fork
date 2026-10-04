@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.nuvio.tv.ui.v2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.GlassTintMode
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.quality.GlassQualityTokens
import com.nuvio.tv.ui.v2.quality.LocalGlassTokens
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.HazeTint

enum class GlassRole { NAVIGATION, CONTROL, PANEL, MODAL, HUD, CARD_FOCUS, TOOLTIP }

val LocalGlassBackdrop = staticCompositionLocalOf<HazeState?> { null }

/** 0..1 while moving video replaces the glass source behind a surface; null everywhere else. */
val LocalGlassVideoSmoke = compositionLocalOf<State<Float>?> { null }

/** Attach to background layers; compatible trailer textures join only at Maximum quality. */
@Composable
fun Modifier.v2GlassSource(): Modifier {
    val state = LocalGlassBackdrop.current
    return if (state != null && LocalGlassTokens.current.liveBlur &&
        LocalV2Appearance.current?.visualStyle == VisualStyle.CINEMATIC_GLASS) hazeSource(state) else this
}

/** One bounded blur policy shared by surfaces. Playback continues to use static tokens. */
fun Modifier.v2GlassBlur(role: GlassRole, tokens: GlassQualityTokens, state: HazeState?, refreshVideo: Boolean = false): Modifier {
    val allowed = role != GlassRole.CARD_FOCUS
    if (!allowed || !tokens.liveBlur || state == null) return this
    return hazeEffect(state = state) {
        backgroundColor = Color(0xFF080A0C)
        tints = listOf(HazeTint(Color.Transparent))
        blurRadius = tokens.blurRadiusDp.dp
        inputScale = HazeInputScale.Fixed(tokens.inputScale)
        noiseFactor = tokens.noise
        // TextureView can update without a Compose state change. Refresh attached
        // Maximum effects on their window's draw, including cross-window dialogs.
        forceInvalidateOnPreDraw = tokens.edgeLayers >= 3 && refreshVideo
    }
}

/** Smoked glass remains useful without blur; screens choose a role, never blur parameters. */
@Composable
fun NuvioGlassSurface(
    role: GlassRole,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    shape: Shape = RoundedCornerShape(20.dp),
    hazeState: HazeState? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier.nuvioGlass(role, focused, shape, hazeState), content = content)
}

@Composable
fun Modifier.nuvioGlass(
    role: GlassRole,
    focused: Boolean = false,
    shape: Shape = RoundedCornerShape(20.dp),
    hazeState: HazeState? = null,
    trailingEdgeOnly: Boolean = false,
    neutralBackdrop: Boolean = false
): Modifier {
    val appearance = LocalV2Appearance.current
    val clearGlass = usesClearGlassTheme()
    val qualityTokens = LocalGlassTokens.current
    val tokens = if (clearGlass) qualityTokens.copy(noise = 0f) else qualityTokens
    val accent = NuvioTheme.colors.Secondary
    val playback = com.nuvio.tv.ui.v2.appearance.LocalResolvedAppearance.current?.playbackActive == true
    val dark = appearance?.visualStyle == VisualStyle.PURE_LIQUID_DARK
    val playbackGlass = playback && !dark
    // The decoded video is already beneath this surface. Never replace it with
    // the navigation backdrop (which can still contain the last hero artwork).
    val popupBackdrop = LocalPopupGlassBackdrop.current
    val capturePopup = role == GlassRole.MODAL && !playback && !dark && tokens.liveBlur
    DisposableEffect(popupBackdrop, capturePopup) {
        if (capturePopup) popupBackdrop?.attach()
        onDispose { if (capturePopup) popupBackdrop?.detach() }
    }
    val backdrop = if (role == GlassRole.MODAL) popupBackdrop?.state else LocalGlassBackdrop.current
    val cinematic = appearance?.visualStyle == VisualStyle.CINEMATIC_GLASS
    val smoke = LocalGlassVideoSmoke.current.takeIf { cinematic && !playback && role != GlassRole.MODAL }
    val smoked = if (smoke != null) remember(smoke) { derivedStateOf { smoke.value >= 1f } }.value else false
    // Popup capture attaches on demand, after this consumer enters composition.
    // Let Haze observe the initially empty state rather than conditionally adding
    // its effect after attachment; this also survives returning from playback.
    val source = (hazeState ?: backdrop).takeIf {
        !smoked && !neutralBackdrop && !playback && !dark &&
            (it === popupBackdrop?.state || it?.areas?.isNotEmpty() == true)
    }
    val frosted = tokens.liveBlur && source != null
    val artwork = com.nuvio.tv.ui.v2.appearance.LocalArtworkAccent.current
    val adaptive = appearance?.accentMode == com.nuvio.tv.domain.model.AccentMode.ADAPTIVE_ARTWORK
    val neutralGlass = NuvioTheme.currentTheme == com.nuvio.tv.domain.model.AppTheme.GLASS
    // Let the live picture supply playback's colour. Keep navigation artwork/accent
    // tint out of resting player surfaces; focus still uses the selected theme.
    val tintMode = if (neutralBackdrop || neutralGlass || playbackGlass) GlassTintMode.NEUTRAL
        else appearance?.glassTintMode ?: GlassTintMode.NEUTRAL
    val accentColors = com.nuvio.tv.ui.v2.appearance.v2AccentColors()
    val transparency = appearance?.glassTransparencyPercent ?: 60
    val bodyAlpha = remember(dark, role, frosted, playback, transparency, cinematic, clearGlass, neutralBackdrop) {
        val legacyAlpha = when (role) {
            GlassRole.HUD -> if (playbackGlass) .38f else if (playback) .82f else .91f
            GlassRole.CONTROL -> if (playbackGlass) .18f else if (playback) .55f else if (dark) .80f else if (frosted) .48f else .84f
            GlassRole.NAVIGATION -> if (frosted) .48f else .94f
            GlassRole.MODAL -> if (frosted) .62f else .97f
            else -> if (playbackGlass) .28f else if (playback) .70f else if (dark) .94f else if (frosted) .50f else .94f
        }
        if (neutralBackdrop) .96f else if (clearGlass) clearGlassBodyAlpha(role, transparency)
            else if (cinematic) glassBodyAlpha(role, playback, legacyAlpha, transparency) else legacyAlpha
    }
    val background = remember(tokens, dark, neutralGlass, playbackGlass, clearGlass, neutralBackdrop, bodyAlpha) {
        val alpha = bodyAlpha
        // A translucent body and lit rim give playback glass depth without copying
        // decoded video or adding a blur pass to the playback surface.
        Brush.verticalGradient(if (neutralBackdrop) listOf(Color(0xFF202125).copy(alpha = alpha), Color(0xFF121317).copy(alpha = alpha))
            else if (clearGlass) listOf(Color.Black.copy(alpha = alpha), Color.Black.copy(alpha = alpha))
            else if (playbackGlass) listOf(Color(0xFF353B40).copy(alpha = alpha), Color(0xFF101418).copy(alpha = alpha))
            else if (neutralGlass) listOf(Color(0xFF1A1D20).copy(alpha = alpha), Color(0xFF080A0C).copy(alpha = alpha))
            else if (dark) listOf(Color(0xFF102332).copy(alpha = alpha), Color(0xFF040D16).copy(alpha = alpha))
            else listOf(Color(0xFF202225).copy(alpha = alpha), Color(0xFF0C0E11).copy(alpha = alpha)))
    }
    val edge = remember(tokens, dark, playbackGlass, clearGlass) {
        if (clearGlass) Brush.verticalGradient(listOf(
            Color.White.copy(alpha = .28f), Color.White.copy(alpha = .045f), Color.White.copy(alpha = .14f)
        )) else Brush.verticalGradient(listOf(
            Color.White.copy(alpha = if (playbackGlass) .50f else if (dark) 0.12f else 0.18f),
            Color.White.copy(alpha = if (tokens.edgeLayers > 1) 0.08f else 0.03f),
            if (playbackGlass) Color.White.copy(alpha = .23f) else Color.Black.copy(alpha = 0.35f)
        ))
    }
    val smokeFill = if (clearGlass) Color.Black else if (neutralGlass) Color(0xFF080A0C) else Color(0xFF0C0E11)
    val smokeFillAlpha = if (smoke != null) smokedGlassFillAlpha(bodyAlpha, transparency) else 0f
    return clip(shape)
            .v2GlassBlur(role, tokens, source, refreshVideo = (popupBackdrop?.liveTrailers ?: 0) > 0)
            .background(background, shape)
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val stroke = Stroke(1.dp.toPx())
                val accentBrush = com.nuvio.tv.ui.theme.createThemeBrush(accentColors)
                val reflection = if (clearGlass) Brush.verticalGradient(
                    0f to Color.White.copy(alpha = .065f),
                    .10f to Color.Transparent,
                    .92f to Color.Transparent,
                    1f to Color.White.copy(alpha = .015f)
                ) else if (playbackGlass) Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (role == GlassRole.CONTROL) .22f else .14f),
                    .38f to Color.White.copy(alpha = .025f),
                    .72f to Color.Transparent,
                    1f to Color.White.copy(alpha = .06f)
                ) else Brush.linearGradient(listOf(
                    Color.White.copy(alpha = .12f), Color.Transparent, Color.White.copy(alpha = .025f)))
                val rimInset = 1.5.dp.toPx()
                val innerOutline = if (!trailingEdgeOnly && (playbackGlass || (clearGlass && tokens.edgeLayers > 1)) && size.minDimension > rimInset * 2) {
                    shape.createOutline(Size(size.width - rimInset * 2, size.height - rimInset * 2), layoutDirection, this)
                } else null
                val innerRim = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (clearGlass) .07f else .13f), Color.Transparent, Color.Black.copy(alpha = .18f)))
                val smokeEdge = SolidColor(Color.White.copy(alpha = .22f))
                onDrawWithContent {
                    val smokeAmount = smoke?.value ?: 0f
                    if (smokeAmount > 0f) drawOutline(outline, smokeFill, alpha = smokeFillAlpha * smokeAmount)
                    val artworkColor = artwork?.color?.value ?: accent
                    val resolvedAccent = if (adaptive) artworkColor else accent
                    val tint = when (tintMode) {
                        GlassTintMode.NEUTRAL -> Color.Transparent
                        GlassTintMode.ACCENT -> resolvedAccent.copy(alpha = if (focused) .20f else .09f)
                        GlassTintMode.ARTWORK -> artworkColor.copy(alpha = if (focused) .20f else .09f)
                    }
                    if (tintMode == GlassTintMode.ACCENT) {
                        drawOutline(outline, accentBrush, alpha = if (focused) .20f else .09f)
                    } else {
                        drawOutline(outline, tint)
                    }
                    if (focused && !neutralGlass) drawOutline(outline, accentBrush, alpha = .16f)
                    if (playback || (cinematic && tokens.edgeLayers > 1)) {
                        drawOutline(outline, reflection)
                    }
                    drawContent()
                    if (trailingEdgeOnly) {
                        // Edge-attached navigation has a divider, not a rectangular frame.
                        val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr)
                            size.width - stroke.width / 2f else stroke.width / 2f
                        drawLine(edge, androidx.compose.ui.geometry.Offset(x, 0f),
                            androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = stroke.width)
                    } else if (smokeAmount > 0f) {
                        drawOutline(outline, edge, alpha = 1f - smokeAmount, style = stroke)
                        drawOutline(outline, smokeEdge, alpha = smokeAmount, style = stroke)
                    } else drawOutline(outline, edge, style = stroke)
                    if (innerOutline != null) translate(rimInset, rimInset) {
                        drawOutline(innerOutline, innerRim, style = stroke)
                    }
                    if (focused) drawOutline(outline, accentBrush, alpha = if (clearGlass) .18f else .8f, style = stroke)
                }
            }
}
