package com.nuvio.tv.ui.screens.player

import android.text.SpannableString
import android.text.Spanned
import android.util.TypedValue
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.nuvio.tv.data.local.SubtitleStyleSettings

/** Draws [text] the way the player draws a plain text cue with [style]. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun SubtitleView.showSubtitlePreview(style: SubtitleStyleSettings, text: CharSequence) {
    setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, EXO_SUBTITLE_BASE_SP * (style.size / 100f))
    setApplyEmbeddedFontSizes(false)
    setApplyEmbeddedStyles(true)
    val edges = exoSubtitleEdges(style)
    setStyle(
        CaptionStyleCompat(
            style.textColor,
            edges.background,
            android.graphics.Color.TRANSPARENT,
            edges.edgeType,
            edges.edgeColor,
            resolvedSubtitleTypeface(context, style)
        )
    )
    setBottomPaddingFraction(PREVIEW_BOTTOM_FRACTION)
    val spanned = SpannableString(text)
    subtitleEdgeSpan(style)?.let { spanned.setSpan(it, 0, spanned.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    setCues(listOf(Cue.Builder().setText(spanned).build()))
}

private const val PREVIEW_BOTTOM_FRACTION = 0.12f
