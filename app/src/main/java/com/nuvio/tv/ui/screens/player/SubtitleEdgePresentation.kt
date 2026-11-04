package com.nuvio.tv.ui.screens.player

import android.graphics.Paint
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import androidx.media3.ui.CaptionStyleCompat
import com.nuvio.tv.data.local.SubtitleOutlineSize
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.local.normalizeSubtitleShadowOpacity

/** Outline stroke width as a share of the text size; Normal is Media3's 2 dp at the 24 sp base size. */
internal fun subtitleOutlineStrokeRatio(size: SubtitleOutlineSize): Float = when (size) {
    SubtitleOutlineSize.OFF -> 0f
    SubtitleOutlineSize.THIN -> 1f / 24f
    SubtitleOutlineSize.NORMAL -> 2f / 24f
    SubtitleOutlineSize.THICK -> 4f / 24f
}

internal const val SUBTITLE_SHADOW_OFFSET_RATIO = 2f / 24f
internal const val SUBTITLE_SHADOW_RADIUS_RATIO = 0.75f / 24f

internal fun subtitleShadowColor(opacity: Int): Int =
    ((normalizeSubtitleShadowOpacity(opacity) * 255 + 50) / 100) shl 24

private fun hasSubtitleBackground(style: SubtitleStyleSettings): Boolean = (style.backgroundColor ushr 24) > 0

/** A background box replaces the shadow, on every engine. */
internal fun drawsSubtitleShadow(style: SubtitleStyleSettings): Boolean =
    style.effectiveShadowEnabled && !hasSubtitleBackground(style)

internal data class ExoSubtitleEdges(val edgeType: Int, val edgeColor: Int, val background: Int)

internal fun exoSubtitleEdges(style: SubtitleStyleSettings): ExoSubtitleEdges = when {
    style.effectiveOutlineSize != SubtitleOutlineSize.OFF ->
        ExoSubtitleEdges(CaptionStyleCompat.EDGE_TYPE_OUTLINE, style.outlineColor, style.backgroundColor)
    drawsSubtitleShadow(style) ->
        ExoSubtitleEdges(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, subtitleShadowColor(style.shadowOpacity), style.backgroundColor)
    else -> ExoSubtitleEdges(CaptionStyleCompat.EDGE_TYPE_NONE, style.outlineColor, style.backgroundColor)
}

/** Null when Media3's own drawing already matches the style. */
internal fun subtitleEdgeSpan(style: SubtitleStyleSettings): SubtitleEdgeSpan? {
    val stroke = subtitleOutlineStrokeRatio(style.effectiveOutlineSize)
    val shadow = if (drawsSubtitleShadow(style)) subtitleShadowColor(style.shadowOpacity) else 0
    return if (stroke == 0f && shadow == 0) null else SubtitleEdgeSpan(stroke, shadow)
}

/**
 * Media3 draws an outlined cue twice: first with a FILL_AND_STROKE paint in the edge colour, then filled in the
 * text colour. This span sets the stroke width of the first pass from the text size and adds the shadow to it,
 * so the shadow sits behind the outline. Without an outline it sizes Media3's drop shadow on the fill pass.
 */
internal class SubtitleEdgeSpan(val strokeRatio: Float, val shadowColor: Int) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        val outlinePass = tp.style == Paint.Style.FILL_AND_STROKE
        if (outlinePass != (strokeRatio > 0f)) return
        val textSize = tp.textSize
        if (outlinePass) tp.strokeWidth = textSize * strokeRatio
        if (shadowColor != 0) {
            val offset = textSize * SUBTITLE_SHADOW_OFFSET_RATIO
            tp.setShadowLayer(textSize * SUBTITLE_SHADOW_RADIUS_RATIO, offset, offset, shadowColor)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SubtitleEdgeSpan && other.strokeRatio == strokeRatio && other.shadowColor == shadowColor

    override fun hashCode(): Int = 31 * strokeRatio.hashCode() + shadowColor
}

internal data class MpvSubtitleEdges(
    val outlineSize: Double, val shadowOffset: Double,
    val borderStyle: String, val backColor: Int
)

internal fun mpvSubtitleOutlineSize(size: SubtitleOutlineSize): Double = when (size) {
    SubtitleOutlineSize.OFF -> 0.0
    SubtitleOutlineSize.THIN -> 0.5
    SubtitleOutlineSize.NORMAL -> 1.0
    SubtitleOutlineSize.THICK -> 2.0
}

internal fun mpvSubtitleEdges(style: SubtitleStyleSettings, ass: Boolean): MpvSubtitleEdges {
    // sub-ass-override=no leaves authored ASS styles intact.
    val size = style.effectiveOutlineSize
    val outline = when {
        size == SubtitleOutlineSize.OFF -> 0.0
        ass -> style.outlineWidth.coerceIn(1, 6).toDouble()
        else -> mpvSubtitleOutlineSize(size)
    }
    // libass has no text shadow inside a background box, so the box wins when one is set.
    if (hasSubtitleBackground(style)) return MpvSubtitleEdges(outline, 5.0, "background-box", style.backgroundColor)
    if (style.effectiveShadowEnabled) {
        return MpvSubtitleEdges(outline, 1.5, "outline-and-shadow", subtitleShadowColor(style.shadowOpacity))
    }
    return MpvSubtitleEdges(outline, 0.0, "outline-and-shadow", style.backgroundColor)
}
