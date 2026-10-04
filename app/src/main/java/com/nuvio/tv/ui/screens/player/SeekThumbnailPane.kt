package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import kotlin.math.abs

/** Root bounds of the seek bar on screen, reported by the bar; thumbnails sit just above it. */
internal object SeekBarAnchor {
    var bounds by mutableStateOf<Rect?>(null)
}

private val BarGap = 12.dp

/** Lays [content] out over the seek bar's width, bottom edge [BarGap] above the bar. */
@Composable
private fun AboveSeekBar(
    modifier: Modifier,
    anchored: Boolean = true,
    contentAlignment: Alignment = Alignment.BottomStart,
    content: @Composable BoxScope.(width: Dp) -> Unit
) {
    val bar = SeekBarAnchor.bounds?.takeIf { anchored && it.width > 0f }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val start: Dp
            val end: Dp
            val bottom: Dp
            if (bar != null) {
                with(density) {
                    start = bar.left.coerceAtLeast(0f).toDp()
                    end = (constraints.maxWidth - bar.right).coerceAtLeast(0f).toDp()
                    bottom = (constraints.maxHeight - bar.top).coerceAtLeast(0f).toDp() + BarGap
                }
            } else {
                start = NuvioTheme.spacing.xxl
                end = NuvioTheme.spacing.xxl
                bottom = 140.dp
            }
            val width = (maxWidth - start - end).coerceAtLeast(0.dp)
            Box(
                modifier = Modifier.fillMaxSize().padding(PaddingValues(start = start, end = end, bottom = bottom))
                    .clipToBounds(),
                contentAlignment = contentAlignment
            ) {
                content(width)
            }
        }
    }
}

@Composable
private fun Modifier.thumbnailSurface(shape: Shape): Modifier =
    if (LocalV2Appearance.current != null) clip(shape).nuvioGlass(GlassRole.HUD, shape = shape)
    else clip(shape).background(Color.Black.copy(alpha = 0.85f))

@Composable
private fun Modifier.landingFrame(shape: Shape): Modifier =
    if (LocalV2Appearance.current != null) nuvioV2Focus(focused = true, shape = shape, stationary = true)
    else border(NuvioTheme.focusRing.border(2.dp), shape)

private fun croppedPainter(shown: SeekThumbnails.Shown?): BitmapPainter? {
    val bitmap = shown?.bitmap ?: return null
    val crop = shown.crop?.takeIf { it.fits(bitmap.width, bitmap.height) }
    val size = IntSize(
        (bitmap.width - (crop?.let { it.left + it.right } ?: 0)).coerceAtLeast(1),
        (bitmap.height - (crop?.let { it.top + it.bottom } ?: 0)).coerceAtLeast(1)
    )
    return BitmapPainter(bitmap.asImageBitmap(), IntOffset(crop?.left ?: 0, crop?.top ?: 0), size)
}

/**
 * Thumbnails above the seek bar while a held seek is pending: a strip once the title's coverage is stored,
 * a single picture until then. A tap (one step, then the commit) shows nothing.
 */
@Composable
fun SeekThumbnailOverlayHost(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val timeline by viewModel.playbackTimeline.collectAsState()
    val tick by SeekThumbnails.tick
    val seekPositionMs = uiState.previewThumbPositionMs
        ?.takeIf { uiState.pendingPreviewSeekPosition != null }
    val durationMs = timeline.duration
    val restingMs = seekPositionMs ?: timeline.currentPosition
    val glide = tween<Float>(PlayerScrubRates.STEP_INTERVAL_MS.toInt(), easing = LinearEasing)
    val fraction by animateFloatAsState(
        targetValue = if (durationMs > 0L) (restingMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
        animationSpec = glide,
        label = "thumbFraction"
    )
    val glidePos by animateFloatAsState(targetValue = restingMs.toFloat(), animationSpec = glide, label = "stripPos")
    // [0] last target, [1] size of the last step, [2] steps in this seek, [3] size of the step before.
    val steps = remember { longArrayOf(-1L, 0L, 0L, 0L) }
    if (seekPositionMs == null || durationMs <= 0L) {
        steps[0] = -1L
        steps[1] = 0L
        steps[2] = 0L
        steps[3] = 0L
        return
    }
    if (seekPositionMs != steps[0]) {
        if (steps[0] >= 0L) {
            steps[3] = steps[1]
            steps[1] = abs(seekPositionMs - steps[0])
        }
        steps[0] = seekPositionMs
        steps[2]++
    }
    if (steps[2] < 2L) return

    val stripSpacing = remember(tick) { SeekThumbnails.stripSpacingMs() }
    if (stripSpacing != null) {
        val aspect = SeekThumbnails.frameAspect() ?: (16f / 9f)
        val multiple = SeekStripLayout.multiple(steps[1], steps[3], stripSpacing)
        SeekThumbnailStrip({ glidePos }, seekPositionMs, durationMs, stripSpacing, multiple, aspect, tick, modifier)
        return
    }

    val painter = remember(seekPositionMs, tick) { croppedPainter(SeekThumbnails.shownFor(seekPositionMs)) }
    val aspect = painter?.intrinsicSize?.let { it.width / it.height }
        ?: SeekThumbnails.frameAspect()
        ?: return
    val height = 108.dp
    val shape = RoundedCornerShape(if (LocalV2Appearance.current != null) 12.dp else NuvioTheme.radii.sm)
    AboveSeekBar(modifier, contentAlignment = BiasAlignment(horizontalBias = fraction * 2f - 1f, verticalBias = 1f)) {
        Box(Modifier.height(height).width(height * aspect).landingFrame(shape).thumbnailSurface(shape)) {
            if (painter != null) {
                Image(painter, contentDescription = null, contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Stored coverage thumbnails slide past a fixed centre frame that shows where the seek will land. */
@Composable
private fun SeekThumbnailStrip(
    glidePosMs: () -> Float,
    exactPosMs: Long,
    durationMs: Long,
    baseSpacingMs: Long,
    multiple: Long,
    aspect: Float,
    tick: Int,
    modifier: Modifier
) {
    val spacingMs = (baseSpacingMs * multiple).toFloat()
    // [0] anchor time, [1] spacing it was set for. A new spacing keeps the tiles where they are and refills them.
    val anchor = remember { doubleArrayOf(0.0, 0.0) }
    if (anchor[1] != spacingMs.toDouble()) {
        if (anchor[1] > 0.0) {
            val glide = Snapshot.withoutReadObservation { glidePosMs() }.toDouble()
            anchor[0] = SeekStripLayout.respacedAnchor(anchor[0], glide, anchor[1], spacingMs.toDouble())
        }
        anchor[1] = spacingMs.toDouble()
    }
    val anchorMs = anchor[0]
    val v2 = LocalV2Appearance.current != null
    val centreH = 127.dp
    val tileH = 95.dp
    val gap = 10.dp
    val centreW = centreH * aspect
    val tileW = tileH * aspect
    val tileShape = RoundedCornerShape(NuvioTheme.radii.sm)
    val centreShape = RoundedCornerShape(if (v2) 12.dp else NuvioTheme.radii.sm)
    val density = LocalDensity.current
    val tileWPx = with(density) { tileW.toPx() }
    val stridePx = with(density) { (tileW + gap).toPx() }
    val liftPx = with(density) { ((centreH - tileH) / 2).roundToPx() }
    val pxPerMs = stridePx / spacingMs
    // Resolved before the tiles so their disk loads cannot starve it.
    val centre = remember(exactPosMs, tick) { croppedPainter(SeekThumbnails.shownFor(exactPosMs)) }
    val posMs = exactPosMs.toFloat()
    AboveSeekBar(modifier) { width ->
        val widthPx = with(density) { width.toPx() }
        val centreX = widthPx / 2f
        val reachMs = (centreX / pxPerMs) + spacingMs
        for (i in SeekStripLayout.tileIndices(anchorMs, posMs.toDouble(), reachMs.toDouble(), spacingMs.toDouble())) {
            val tileMs = anchorMs + i * spacingMs.toDouble()
            val t = SeekStripLayout.pictureMs(tileMs, baseSpacingMs)
            if (t < 0L || t > durationMs) continue
            val rel = (tileMs - posMs).toFloat()
            if (abs(rel) < spacingMs * 0.5f) continue
            val xAtTarget = centreX + rel * pxPerMs - tileWPx / 2f
            if (xAtTarget + tileWPx < -stridePx || xAtTarget > widthPx + stridePx) continue
            key(t) {
                // A slot whose thumbnail is not made yet stays an empty frame.
                val painter = remember(t, tick) { croppedPainter(SeekThumbnails.tileFor(t)?.takeIf { it.exact }) }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset {
                            val x = centreX + (tileMs - glidePosMs()).toFloat() * pxPerMs - tileWPx / 2f
                            IntOffset(x.toInt(), -liftPx)
                        }
                        .height(tileH)
                        .width(tileW)
                        .thumbnailSurface(tileShape)
                ) {
                    if (painter != null) {
                        Image(painter, contentDescription = null, contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .height(centreH)
                .width(centreW)
                .landingFrame(centreShape)
                .thumbnailSurface(centreShape)
        ) {
            if (centre != null) {
                Image(centre, contentDescription = null, contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** While paused on a small box, shows how far a 4K title's thumbnails are. */
@Composable
fun SeekThumbnailProgressHint(
    uiState: PlayerUiState,
    modifier: Modifier = Modifier
) {
    val tick by SeekThumbnails.tick
    val paused = !uiState.isPlaying && !uiState.isBuffering && uiState.pendingPreviewSeekPosition == null &&
        uiState.error == null
    val percent = remember(tick, paused) { if (paused) SeekThumbnails.progressPercent() else null } ?: return
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    AboveSeekBar(modifier, anchored = uiState.showControls, contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier
                .then(
                    if (LocalV2Appearance.current != null) Modifier.clip(shape).nuvioGlass(GlassRole.HUD, shape = shape)
                    else Modifier.clip(shape).background(Color.Black.copy(alpha = 0.7f))
                )
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.seek_thumbnails_progress_chip, percent),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}
