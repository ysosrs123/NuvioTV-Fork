package com.nuvio.tv.ui.screens.player

import androidx.media3.ui.CaptionStyleCompat
import com.nuvio.tv.data.local.SubtitleEdgeStyle
import com.nuvio.tv.data.local.SubtitleStyleSettings

internal const val SUBTITLE_SHADOW_COLOR: Int = 0xB3000000.toInt()

internal fun subtitleCaptionEdge(style: SubtitleEdgeStyle): Int = when (style) {
    SubtitleEdgeStyle.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
    SubtitleEdgeStyle.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
    SubtitleEdgeStyle.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
}

private fun hasSubtitleBackground(style: SubtitleStyleSettings): Boolean = (style.backgroundColor ushr 24) > 0

internal data class ExoSubtitleColors(val background: Int, val window: Int, val edgeColor: Int)

internal fun exoSubtitleColors(style: SubtitleStyleSettings): ExoSubtitleColors {
    if (style.effectiveEdgeStyle != SubtitleEdgeStyle.DROP_SHADOW) {
        return ExoSubtitleColors(style.backgroundColor, 0, style.outlineColor)
    }
    // Media3 paints span backgrounds with the shadowed text paint; the window box is painted without it.
    return if (hasSubtitleBackground(style)) {
        ExoSubtitleColors(0, style.backgroundColor, SUBTITLE_SHADOW_COLOR)
    } else {
        ExoSubtitleColors(style.backgroundColor, 0, SUBTITLE_SHADOW_COLOR)
    }
}

internal data class MpvSubtitleEdges(
    val outlineSize: Double, val shadowOffset: Double,
    val borderStyle: String, val backColor: Int
)

internal fun mpvSubtitleEdges(style: SubtitleStyleSettings, ass: Boolean): MpvSubtitleEdges {
    // sub-ass-override=no leaves authored ASS styles intact.
    val edge = style.effectiveEdgeStyle
    val background = hasSubtitleBackground(style)
    // libass has no text shadow inside a background box, so the box wins when one is set.
    if (edge == SubtitleEdgeStyle.DROP_SHADOW && !background) return MpvSubtitleEdges(
        0.0, 1.5, "outline-and-shadow", SUBTITLE_SHADOW_COLOR
    )
    return MpvSubtitleEdges(
        if (edge != SubtitleEdgeStyle.OUTLINE) 0.0 else if (ass) style.outlineWidth.coerceIn(1, 6).toDouble() else 1.0,
        if (background) 5.0 else 0.0,
        if (background) "background-box" else "outline-and-shadow",
        style.backgroundColor
    )
}
