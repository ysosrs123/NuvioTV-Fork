package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails.PrepareUi
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.createThemeBrush
import com.nuvio.tv.ui.v2.appearance.v2AccentColors
import kotlin.math.roundToInt

/** Lets the player screen route keys into the overlay when its own controls took focus. */
internal object PrepareOverlayFocus {
    var hasFocus = false
    var requester: FocusRequester? = null
    fun grab() {
        runCatching { requester?.requestFocus() }
    }
}

private val RowFrameWidth = 104.dp
private val RowFrameHeight = 58.dp
private val RowGap = 10.dp
private const val RowFrames = 6
private val RowWidth = RowFrameWidth * RowFrames + RowGap * (RowFrames - 1)

/**
 * "Generate thumbnails before play": shown while playback is held at the start and the title's thumbnails
 * are made. Back is handled by the player screen and starts playback.
 */
@Composable
fun ThumbnailPrepareOverlay(backdropUrl: String?, modifier: Modifier = Modifier) {
    val ui by SeekThumbnails.prepareUi
    if (ui is PrepareUi.Hidden) return
    DisposableEffect(Unit) {
        onDispose {
            PrepareOverlayFocus.hasFocus = false
            PrepareOverlayFocus.requester = null
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onFocusChanged { PrepareOverlayFocus.hasFocus = it.hasFocus }
            .focusGroup()
            .onKeyEvent { event ->
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) SeekThumbnails.startWatchingNow()
                        true
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> false
                    else -> true
                }
            }
    ) {
        // Kept so the ready state still shows the row and the timeline.
        val lastGenerating = remember { arrayOfNulls<PrepareUi.Generating>(1) }
        val current = ui
        if (current is PrepareUi.Generating) lastGenerating[0] = current
        PrepareBackdrop(backdropUrl)
        GeneratingScreen(
            ui = if (current is PrepareUi.Checking) null else lastGenerating[0],
            ready = current as? PrepareUi.Ready
        )
    }
}

@Composable
private fun PrepareBackdrop(backdropUrl: String?) {
    val context = LocalContext.current
    val request = remember(context, backdropUrl) {
        backdropUrl?.takeIf { it.isNotBlank() }?.let {
            ImageRequest.Builder(context).data(it).crossfade(true).build()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.60f), Color.Black.copy(alpha = 0.85f)))
            )
        )
    }
}

@Composable
private fun timeLeft(seconds: Int): String =
    if (seconds >= 60) stringResource(R.string.seek_thumbnails_time_left_minutes, seconds / 60, seconds % 60)
    else stringResource(R.string.seek_thumbnails_time_left_seconds, seconds)

private fun clock(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun RequestFirstFocus(requester: FocusRequester) {
    val focusManager = LocalFocusManager.current
    PrepareOverlayFocus.requester = requester
    LaunchedEffect(requester) {
        focusManager.clearFocus(force = true)
        repeat(3) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
    }
}

@Composable
private fun GeneratingScreen(ui: PrepareUi.Generating?, ready: PrepareUi.Ready?) {
    val colors = NuvioTheme.colors
    val type = MaterialTheme.typography
    val accent = createThemeBrush(v2AccentColors())
    val startNow = remember { FocusRequester() }
    if (ready == null) RequestFirstFocus(startNow) else PrepareOverlayFocus.requester = null
    // The press that opened the title must not start it straight away.
    val shownAt = remember { SystemClock.uptimeMillis() }
    val target = when {
        ready != null -> 1f
        ui == null || ui.total <= 0 -> 0f
        ui.done >= ui.total -> 1f
        else -> ui.done.toFloat() / ui.total
    }
    val progress by animateFloatAsState(target, tween(700), label = "progress")
    // Ring and texts sit in the upper part; the row, timeline, button and hint sit at the bottom edge.
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.55f))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                ProgressRing(progress, indeterminate = ui == null && ready == null, ringSize = 180.dp, brush = accent)
                when {
                    ready != null -> ReadyTick(accent)
                    ui != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${(progress * 100).roundToInt()}", style = type.displayMedium.copy(fontSize = 44.sp),
                                color = colors.TextPrimary)
                            Text("%", style = type.titleMedium, color = colors.TextPrimary.copy(alpha = 0.5f),
                                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
                        }
                        Text(stringResource(R.string.seek_thumbnails_count, ui.done, ui.total),
                            style = type.bodyMedium, color = colors.TextSecondary)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (ready != null) R.string.seek_thumbnails_ready else R.string.seek_thumbnails_generating),
                    style = type.titleLarge, color = colors.TextPrimary
                )
                Text(
                    when {
                        ready != null -> stringResource(R.string.seek_thumbnails_ready_detail, ready.total)
                        ui == null -> " "
                        else -> timeLeft(ui.secondsLeft)
                    },
                    style = type.bodyMedium, color = colors.TextSecondary
                )
            }
        }
        Spacer(Modifier.weight(0.45f))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Space is held so the screen does not shift when the first counts arrive.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(Modifier.heightIn(min = RowFrameHeight), contentAlignment = Alignment.Center) {
                    if (ui != null) NewestRow(ui)
                }
                Box(Modifier.heightIn(min = 22.dp), contentAlignment = Alignment.TopCenter) {
                    if (ui != null) CoverageTimeline(ui.coverage, ui.spacingMs, accent)
                }
            }
            if (ready == null) {
                PlayerOverlayButton(
                    text = stringResource(R.string.seek_thumbnails_start_now),
                    onClick = { if (SystemClock.uptimeMillis() - shownAt >= 700L) SeekThumbnails.startWatchingNow() },
                    primary = true,
                    modifier = Modifier.focusRequester(startNow)
                )
            }
            Text(stringResource(R.string.seek_thumbnails_settings_hint), style = type.bodySmall, color = colors.TextTertiary)
        }
        Spacer(Modifier.height(NuvioTheme.spacing.xxl))
    }
}

@Composable
private fun ProgressRing(progress: Float, indeterminate: Boolean, ringSize: Dp, brush: Brush) {
    val transition = rememberInfiniteTransition(label = "ring")
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "spin")
    Canvas(Modifier.size(ringSize)) {
        val stroke = 4.dp.toPx()
        val diameter = size.minDimension - stroke * 2
        val topLeft = Offset(stroke, stroke)
        val arc = Size(diameter, diameter)
        drawArc(Color.White.copy(alpha = 0.10f), 0f, 360f, false, topLeft, arc, style = Stroke(2.dp.toPx()))
        val start = if (indeterminate) spin - 90f else -90f
        val sweep = if (indeterminate) 70f else progress.coerceIn(0.002f, 1f) * 360f
        rotate(start) {
            drawArc(brush, 0f, sweep, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun ReadyTick(brush: Brush) {
    val draw = remember { Animatable(0f) }
    LaunchedEffect(Unit) { draw.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
    Canvas(Modifier.size(56.dp)) {
        val a = Offset(size.width * 0.25f, size.height * 0.52f)
        val b = Offset(size.width * 0.43f, size.height * 0.70f)
        val e = Offset(size.width * 0.77f, size.height * 0.32f)
        val f = draw.value
        val path = Path().apply {
            moveTo(a.x, a.y)
            if (f <= 0.4f) {
                val k = f / 0.4f
                lineTo(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k)
            } else {
                lineTo(b.x, b.y)
                val k = (f - 0.4f) / 0.6f
                lineTo(b.x + (e.x - b.x) * k, b.y + (e.y - b.y) * k)
            }
        }
        drawPath(path, brush, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The newest thumbnails, oldest left; the row follows the newest one on a spring so arrivals read as one glide. */
@Composable
private fun NewestRow(ui: PrepareUi.Generating) {
    val colors = NuvioTheme.colors
    val type = MaterialTheme.typography
    val tick by SeekThumbnails.tick
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    val stridePx = with(LocalDensity.current) { (RowFrameWidth + RowGap).toPx() }
    val newestSeq = ui.recentEndSeq
    val head by animateFloatAsState(
        newestSeq.toFloat(),
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 60f),
        label = "rowHead"
    )
    Box(Modifier.width(RowWidth).height(RowFrameHeight).clip(RoundedCornerShape(0.dp))) {
        ui.recent.forEachIndexed { i, pos ->
            val seq = newestSeq - (ui.recent.size - 1 - i)
            key(seq) {
                val bitmap = remember(tick, pos) { SeekThumbnails.tileFor(pos)?.takeIf { it.exact }?.bitmap }
                Box(
                    Modifier
                        .offset { IntOffset(((RowFrames - 1 - (head - seq)) * stridePx).roundToInt(), 0) }
                        .width(RowFrameWidth)
                        .height(RowFrameHeight)
                        .clip(shape)
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                ) {
                    if (bitmap != null) {
                        Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize())
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                            .padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 3.dp)
                    ) {
                        Text(clock(pos), style = type.labelSmall, color = colors.TextPrimary.copy(alpha = 0.85f))
                    }
                }
            }
        }
    }
}

/** The whole title as a thin line; a segment lights up for every thumbnail made. */
@Composable
private fun CoverageTimeline(coverage: List<Boolean>, spacingMs: Long, brush: Brush) {
    val colors = NuvioTheme.colors
    val type = MaterialTheme.typography
    val pending = SolidColor(Color.White.copy(alpha = 0.14f))
    Column(Modifier.width(RowWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            val n = coverage.size
            if (n == 0) return@Canvas
            val gap = (if (n > 120) 0.5.dp else 1.dp).toPx()
            val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
            val corner = CornerRadius(0.5.dp.toPx())
            for (i in 0 until n) {
                drawRoundRect(
                    if (coverage[i]) brush else pending,
                    topLeft = Offset(i * (w + gap), 0f),
                    size = Size(w, size.height),
                    cornerRadius = corner
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0:00", style = type.labelSmall, color = colors.TextTertiary)
            Text(clock((coverage.size - 1).coerceAtLeast(0) * spacingMs), style = type.labelSmall,
                color = colors.TextTertiary)
        }
    }
}
