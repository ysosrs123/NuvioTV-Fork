package com.nuvio.tv.ui.v2.diagnostics

/** Window geometry and output mode are separate: HDMI pixels must not drive UI density. */
data class UiCanvasSnapshot(
    val windowWidthPx: Int,
    val windowHeightPx: Int,
    val baseDensity: Float,
    val densityDpi: Int,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val modeId: Int?,
    val outputWidthPx: Int?,
    val outputHeightPx: Int?,
    val refreshRateHz: Float?
) {
    val hasValidGeometry: Boolean
        get() = windowWidthPx > 0 && windowHeightPx > 0 &&
            baseDensity.isFinite() && baseDensity > 0f

    val logicalWidthDp: Float?
        get() = if (hasValidGeometry) windowWidthPx / baseDensity else null

    val logicalHeightDp: Float?
        get() = if (hasValidGeometry) windowHeightPx / baseDensity else null

    fun effectiveWidthDp(scalePercent: Int): Float? =
        if (scalePercent > 0) logicalWidthDp?.div(scalePercent / 100f) else null

    fun effectiveHeightDp(scalePercent: Int): Float? =
        if (scalePercent > 0) logicalHeightDp?.div(scalePercent / 100f) else null
}
