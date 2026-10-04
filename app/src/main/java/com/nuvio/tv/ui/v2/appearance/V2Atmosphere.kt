package com.nuvio.tv.ui.v2.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.nuvio.tv.domain.model.SettingsBackground
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.screens.home.PageHeroArtwork
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.components.v2GlassSource

/** Shared cached artwork source for bounded frosted panels; no video capture. */
@Composable
fun V2Atmosphere(
    modifier: Modifier = Modifier,
    rich: Boolean = true,
    background: SettingsBackground = SettingsBackground.STYLE_DEFAULT
) {
    val cinematic = when (background) {
        SettingsBackground.STYLE_DEFAULT -> LocalV2Appearance.current?.visualStyle == VisualStyle.CINEMATIC_GLASS
        SettingsBackground.HERO -> true
        else -> false
    }
    val showArtwork = background == SettingsBackground.HERO || (rich && cinematic)
    val liquid = background == SettingsBackground.PURE_LIQUID_DARK ||
        (background == SettingsBackground.STYLE_DEFAULT && !cinematic)
    val base = when (background) {
        SettingsBackground.MIDNIGHT -> Color(0xFF071426)
        SettingsBackground.CHARCOAL -> Color(0xFF191C20)
        else -> Color(0xFF040B13)
    }
    val neutral = NuvioTheme.currentTheme == com.nuvio.tv.domain.model.AppTheme.GLASS
    val clearGlass = com.nuvio.tv.ui.v2.components.usesClearGlassTheme()
    val accent = if (neutral) Color(0xFF899198) else NuvioTheme.colors.Secondary
    val context = LocalContext.current
    val artwork = HeroBackdropState.pageArtwork
    val settledArtwork by produceState<PageHeroArtwork?>(null, artwork, showArtwork) {
        value = null
        if (showArtwork && artwork.url != null) {
            delay(250)
            value = artwork
        }
    }
    val request = remember(context, artwork, settledArtwork, showArtwork) {
        artwork.url?.takeIf { showArtwork && settledArtwork == artwork }?.let {
            ImageRequest.Builder(context).data(it).size(960, 540)
                .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED)
                .build()
        }
    }
    Box(modifier.fillMaxSize().background(base).v2GlassSource()) {
        if (background == SettingsBackground.POSTERS) {
            com.nuvio.tv.ui.v2.profile.ProfilePosterWallBackground(pageBackground = true)
        }
        if (showArtwork && request != null) {
            key(artwork) {
                AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop,
                    alpha = .82f, modifier = Modifier.fillMaxSize())
            }
        }
        Box(Modifier.fillMaxSize().drawWithCache {
            val ambience = Brush.radialGradient(listOf(accent.copy(alpha = if(rich) .15f else .045f),Color.Transparent),
                center=Offset(size.width*.77f,size.height*.15f), radius=size.width*.8f)
            val shade = Brush.horizontalGradient(if (background == SettingsBackground.POSTERS)
                listOf(Color(0x80000000), Color(0x18000000), Color(0x38000000))
                else if (clearGlass) listOf(Color(0xED000000), Color(0x40000000), Color(0x70000000))
                else listOf(Color(0xED040B13),Color(0x40040B13),Color(0x70040B13)))
            val folds = (0..2).map { i -> Path().apply {
                val x=size.width*(.50f+i*.14f)
                moveTo(x,size.height)
                cubicTo(x-size.width*.24f,size.height*.56f,x+size.width*.34f,size.height*.38f,x+size.width*.12f,0f)
                lineTo(x+size.width*.17f,0f)
                cubicTo(x+size.width*.4f,size.height*.36f,x-size.width*.17f,size.height*.6f,x+size.width*.08f,size.height)
                close()
            } }
            val silk = Brush.linearGradient(listOf(Color.Transparent,Color(0x123E6078),Color(0x07040B13)))
            onDrawBehind {
                if (!clearGlass) drawRect(ambience)
                if (liquid) folds.forEach { drawPath(it,silk) }
                drawRect(shade)
            }
        })
    }
}
