package com.nuvio.tv.ui.components

/** FIT bounds in the same units as the viewport; no crop or video stretch. */
internal data class TrailerImageBounds(val left: Float, val top: Float, val width: Float, val height: Float)

internal fun fittedTrailerImage(width: Float, height: Float, aspectRatio: Float): TrailerImageBounds {
    val aspect = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
    val w = minOf(width, height * aspect)
    val h = w / aspect
    return TrailerImageBounds((width - w) / 2f, (height - h) / 2f, w, h)
}

/** Fullscreen media extends behind rows, but its feathered window must stop above them. */
internal fun featheredTrailerWidth(width: Float, height: Float, fullScreen: Boolean, bottomLimit: Float): Float {
    val availableHeight = if (fullScreen) minOf(height - 24f, bottomLimit - 12f) else height - 24f
    return minOf((width - 24f).coerceAtLeast(1f) * if (fullScreen) .66f else 1f,
        availableHeight.coerceAtLeast(1f) * (16f / 9f))
}

/** Fade from the image edge, with room for common cinema bars encoded into a 16:9 frame. */
internal fun trailerVerticalFeatherInset(aspectRatio: Float): Float =
    ((1f - aspectRatio / 2.4f) / 2f).coerceIn(0f, .14f)
