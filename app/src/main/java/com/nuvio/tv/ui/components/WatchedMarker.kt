package com.nuvio.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush
import kotlinx.coroutines.flow.collectLatest

/** True on Home when the watched titles setting is Dim or Hide: watched posters sit under a veil until focused. */
val LocalDimWatchedPosters = staticCompositionLocalOf { false }

private const val WATCHED_VEIL_ALPHA = 0.68f
private const val WATCHED_SATURATION = 0.4f
private const val WATCHED_OPACITY = 0.8f

/** 1 while a watched poster is dimmed, 0 while it is shown normally (focused, or dimming off). */
@Composable
fun watchedPosterDim(isWatched: Boolean, focused: Boolean): State<Float> {
    // Posters that are never dimmed get no animation of their own: rows create them by the dozen while scrolling.
    if (!isWatched || !LocalDimWatchedPosters.current) return NotDimmed
    return animateFloatAsState(
        targetValue = if (focused) 0f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "watchedPosterDim"
    )
}

/** As [watchedPosterDim], with focus read only by the animation, so a focus change does not recompose the caller. */
@Composable
fun watchedPosterDim(isWatched: Boolean, focused: State<Boolean>): State<Float> {
    if (!isWatched || !LocalDimWatchedPosters.current) return NotDimmed
    return focusDrivenFloat(focused, focusedValue = 0f, unfocusedValue = 1f, durationMillis = 150)
}

/**
 * The value [animateFloatAsState] would give for `if (focused) focusedValue else unfocusedValue`, with
 * [focused] read in the animation's coroutine instead of in composition.
 */
@Composable
internal fun focusDrivenFloat(
    focused: State<Boolean>,
    focusedValue: Float,
    unfocusedValue: Float,
    durationMillis: Int
): State<Float> {
    val value = remember(focused) {
        val start = Snapshot.withoutReadObservation { focused.value }
        Animatable(if (start) focusedValue else unfocusedValue)
    }
    LaunchedEffect(value, focusedValue, unfocusedValue, durationMillis) {
        snapshotFlow { focused.value }.collectLatest { now ->
            value.animateTo(if (now) focusedValue else unfocusedValue, tween(durationMillis))
        }
    }
    return value.asState()
}

private object NotDimmed : State<Float> {
    override val value: Float get() = 0f
}

fun watchedPosterSaturation(dim: Float): Float = 1f - (1f - WATCHED_SATURATION) * dim.coerceIn(0f, 1f)

fun watchedPosterOpacity(dim: Float): Float = 1f - (1f - WATCHED_OPACITY) * dim.coerceIn(0f, 1f)

fun watchedPosterFilter(dim: Float): ColorFilter? =
    if (dim <= 0f) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(watchedPosterSaturation(dim)) })

fun Modifier.watchedPosterFade(dim: State<Float>): Modifier =
    if (dim === NotDimmed) this else graphicsLayer { alpha = watchedPosterOpacity(dim.value) }

@Composable
fun WatchedPosterVeil(
    focused: Boolean,
    modifier: Modifier = Modifier
) {
    val veilColor = NuvioTheme.colors.Background
    val veilAlpha by animateFloatAsState(
        targetValue = if (focused) 0f else WATCHED_VEIL_ALPHA,
        animationSpec = tween(durationMillis = 150),
        label = "watchedPosterVeil"
    )
    Box(modifier = modifier.drawBehind { drawRect(veilColor, alpha = veilAlpha) })
}

/** As [WatchedPosterVeil], with focus read only by the animation and the draw. */
@Composable
fun WatchedPosterVeil(
    focused: State<Boolean>,
    modifier: Modifier = Modifier
) {
    val veilColor = NuvioTheme.colors.Background
    val veilAlpha = focusDrivenFloat(focused, focusedValue = 0f, unfocusedValue = WATCHED_VEIL_ALPHA, durationMillis = 150)
    Box(modifier = modifier.drawBehind { drawRect(veilColor, alpha = veilAlpha.value) })
}

@Composable
fun WatchedMarker(
    modifier: Modifier = Modifier,
    size: Dp = 21.dp,
    iconSize: Dp = 20.dp
) {
    val palette = NuvioTheme.palette

    Box(
        modifier = modifier
            .size(size)
            .shadow(10.dp, shape = CircleShape, spotColor = Color.Transparent)
            .background(palette.accentBrush(), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            tint = palette.onSecondary,
            contentDescription = stringResource(R.string.episodes_cd_watched),
            modifier = Modifier.size(iconSize)
        )
    }
}
