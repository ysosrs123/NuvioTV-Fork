package com.nuvio.tv.ui.v2.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.MemberBrandWordmark
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

object EntryIdentPolicy {
    const val MinimumCycleMs = 5_200L
    const val RecoveryTimeoutMs = 10_000L
    fun shouldFinish(elapsedMs: Long, homeReady: Boolean): Boolean =
        elapsedMs >= RecoveryTimeoutMs || (homeReady && elapsedMs >= MinimumCycleMs)
}

/** Presentation-only handoff, scoped to this Activity. No profile/session or navigation ownership. */
@Stable
class ProfileEntryState {
    var pending by mutableStateOf(false)
        private set
    var homeReady by mutableStateOf(false)
    var revealRequested by mutableStateOf(false)
        private set
    var revealing by mutableStateOf(false)
        private set
    var hasRunThisSession = false
        private set
    fun start(enabled: Boolean = true) { hasRunThisSession = true; homeReady = false; revealRequested = false; revealing = false; pending = enabled }
    fun requestReveal() { if (homeReady) revealRequested = true }
    fun beginReveal() { revealing = true }
    fun finish() { pending = false }
}

val LocalProfileEntryState = staticCompositionLocalOf<ProfileEntryState?> { null }

/** Compose ident: no video decoder, remote assets, display-mode request or per-frame recomposition. */
@Composable
fun ProfileEntryIdent(state: ProfileEntryState, onReturnToProfiles: () -> Unit) {
    val opacity = remember { Animatable(1f) }
    var leaving by remember { mutableStateOf(false) }
    var minimumComplete by remember { mutableStateOf(false) }
    var ribbonStarted by remember { mutableStateOf(false) }
    // The ident follows the chosen palette, independently of the focused poster/avatar.
    val palette = NuvioTheme.palette
    val accent = palette.secondary
    val ribbonColors = palette.accentGradient.ifEmpty { listOf(accent) }
    LaunchedEffect(ribbonStarted) {
        if (!ribbonStarted) return@LaunchedEffect
        delay(EntryIdentPolicy.MinimumCycleMs)
        minimumComplete = true
    }
    LaunchedEffect(minimumComplete, state.homeReady, state.revealRequested) {
        if (state.revealRequested || EntryIdentPolicy.shouldFinish(if (minimumComplete) EntryIdentPolicy.MinimumCycleMs else 0L, state.homeReady)) {
            leaving = true
        }
    }
    LaunchedEffect(Unit) {
        delay(EntryIdentPolicy.RecoveryTimeoutMs)
        leaving = true // Expose Home's existing loading/error/retry and navigation surfaces.
    }
    LaunchedEffect(leaving) {
        if (leaving) {
            state.beginReveal()
            androidx.compose.runtime.withFrameNanos { }
            opacity.animateTo(0f, tween(800))
            state.finish()
        }
    }
    BackHandler { state.finish(); onReturnToProfiles() }
    Box(Modifier.fillMaxSize().graphicsLayer {
        alpha = opacity.value
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Auto
    }.background(Color(0xFF050609)), contentAlignment = Alignment.Center) {
        // Static ambience stays cached while the native vector advances on RenderThread.
        Box(Modifier.fillMaxSize().drawWithCache {
            val halo = Brush.radialGradient(listOf(accent.copy(alpha = .14f), Color.Transparent),
                Offset(size.width * .5f, size.height * .48f), size.width * .36f)
            onDrawBehind { drawRect(halo) }
        })
        NativeEntryRibbon(ribbonColors) { ribbonStarted = true }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                MemberBrandWordmark(height = 72.dp, contentDescription = stringResource(R.string.cd_nuvio_logo))
            }
        }
    }
}

/** A real Android drawable keeps its path animation off the busy Compose/UI thread (API 25+). */
@Composable
private fun NativeEntryRibbon(colors: List<Color>, onStarted: () -> Unit) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            EntryRibbonView(context).apply {
                setImageDrawable(androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.v2_intro_animated))
                setPalette(colors)
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isFocusable = false
                post { (drawable as? android.graphics.drawable.AnimatedVectorDrawable)?.start(); onStarted() }
            }
        },
        update = { it.setPalette(colors) },
        onRelease = { (it.drawable as? android.graphics.drawable.AnimatedVectorDrawable)?.stop() }
    )
}

private class EntryRibbonView(context: android.content.Context) : androidx.appcompat.widget.AppCompatImageView(context) {
    private var palette: List<Int> = emptyList()
    private val paint = android.graphics.Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
    }
    fun setPalette(colors: List<Color>) {
        val next = colors.map { it.toArgb() }.ifEmpty { listOf(android.graphics.Color.WHITE) }
        if (next == palette) return
        palette = next
        updateGradient()
        invalidate()
    }
    private fun updateGradient() {
        val colors = if (palette.size < 2) intArrayOf(palette.firstOrNull() ?: -1, palette.firstOrNull() ?: -1) else palette.toIntArray()
        paint.shader = android.graphics.LinearGradient(0f, 0f, width.coerceAtLeast(1).toFloat(), height.coerceAtLeast(1).toFloat(),
            colors, null, android.graphics.Shader.TileMode.CLAMP)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGradient()
    }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val checkpoint = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        canvas.restoreToCount(checkpoint)
    }
}
