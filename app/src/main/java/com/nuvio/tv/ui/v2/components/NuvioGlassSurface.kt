@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.nuvio.tv.ui.v2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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

enum class GlassRole { NAVIGATION, CONTROL, PANEL, MODAL, HUD, CARD_FOCUS, TOOLTIP }

/** One bounded blur policy shared by eligible navigation/panel surfaces. No video/card capture. */
fun Modifier.v2GlassBlur(role: GlassRole, tokens: GlassQualityTokens, state: HazeState?): Modifier {
    val allowed = role == GlassRole.NAVIGATION || role == GlassRole.PANEL || role == GlassRole.MODAL
    if (!allowed || !tokens.liveBlur || state == null) return this
    return hazeEffect(state = state) {
        blurRadius = tokens.blurRadiusDp.dp
        inputScale = HazeInputScale.Fixed(tokens.inputScale)
        noiseFactor = tokens.noise
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
    hazeState: HazeState? = null
): Modifier {
    val appearance = LocalV2Appearance.current
    val tokens = LocalGlassTokens.current
    val accent = NuvioTheme.colors.Primary
    val dark = appearance?.visualStyle == VisualStyle.PURE_LIQUID_DARK
    // Artwork tint falls back to the current accent until an artwork accent is available.
    val tint = if (appearance?.glassTintMode == GlassTintMode.NEUTRAL) Color.Transparent else accent.copy(alpha = 0.07f)
    val background = remember(tokens, dark, role) {
        val opaque = role == GlassRole.HUD || role == GlassRole.CONTROL || dark
        val alpha = if (opaque) 0.96f else tokens.surfaceAlpha
        Brush.verticalGradient(listOf(Color(0xFF1B1E24).copy(alpha = alpha), Color(0xFF080A0F).copy(alpha = alpha)))
    }
    val edge = remember(accent, focused, tokens) {
        Brush.verticalGradient(listOf(
            if (focused) accent else Color.White.copy(alpha = 0.22f),
            Color.White.copy(alpha = if (tokens.edgeLayers > 1) 0.08f else 0.03f),
            Color.Black.copy(alpha = 0.35f)
        ))
    }
    return clip(shape)
            .v2GlassBlur(role, tokens, hazeState)
            .background(background, shape)
            .background(tint, shape)
            .border(1.dp, edge, shape)
}
