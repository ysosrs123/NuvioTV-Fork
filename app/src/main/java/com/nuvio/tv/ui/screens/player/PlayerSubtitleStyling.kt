package com.nuvio.tv.ui.screens.player

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

// Media3 supports only a subset of ASS; attached fonts and full typesetting need libass.
@OptIn(UnstableApi::class)
internal fun SubtitleView.applyEmbeddedAssStyle(dimHdr: Boolean = false) {
    setApplyEmbeddedStyles(true)
    setApplyEmbeddedFontSizes(true)
    setStyle(
        CaptionStyleCompat(
            if (dimHdr) capSubtitleHighlight(Color.WHITE) else Color.WHITE,
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_NONE,
            Color.BLACK,
            null
        )
    )
    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
    setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
    setPadding(paddingLeft, paddingTop, paddingRight, 0)
}
