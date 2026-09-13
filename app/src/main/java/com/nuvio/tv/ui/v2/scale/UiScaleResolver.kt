package com.nuvio.tv.ui.v2.scale

import com.nuvio.tv.domain.model.DeviceUiPreferences
import com.nuvio.tv.domain.model.UiScaleMode
import com.nuvio.tv.ui.v2.diagnostics.UiCanvasSnapshot
import kotlin.math.roundToInt

data class UiScaleDecision(val percent: Int, val reason: String)

/** Provisional reference from spec v0.4; finalize using paired Ugoos / Fire TV measurements. */
class UiScaleResolver(
    private val referenceWidthDp: Float = 1280f,
    private val referenceHeightDp: Float = 720f
) {
    init {
        require(referenceWidthDp.isFinite() && referenceWidthDp > 0)
        require(referenceHeightDp.isFinite() && referenceHeightDp > 0)
    }

    fun resolve(canvas: UiCanvasSnapshot, preferences: DeviceUiPreferences): UiScaleDecision {
        if (preferences.uiScaleMode == UiScaleMode.MANUAL) {
            return UiScaleDecision(preferences.manualUiScalePercent.coerceIn(75, 115), "Manual")
        }
        if (!canvas.hasValidGeometry) return UiScaleDecision(100, "Waiting for valid window geometry")
        val raw = minOf(
            canvas.logicalWidthDp!! / referenceWidthDp,
            canvas.logicalHeightDp!! / referenceHeightDp
        ).coerceIn(0.75f, 1.15f)
        val fineTune = 1f + preferences.autoScaleFineTunePercent.coerceIn(-10, 10) / 100f
        return UiScaleDecision(
            (raw * fineTune * 100).roundToInt().coerceIn(72, 120),
            "Logical canvas (provisional ${referenceWidthDp.toInt()} x ${referenceHeightDp.toInt()} dp reference)"
        )
    }
}
