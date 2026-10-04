package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.text.style.StyleSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import com.nuvio.tv.data.local.SubtitleStyleSettings
import kotlin.math.roundToInt

internal fun isHdrVideoFormat(mime: String?, transfer: Int?): Boolean =
    transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG ||
        mime == "video/dolby-vision"

/** Match the stats/decoder detection when a remux omits Matroska Colour metadata. */
internal fun isHdrVideoFormat(format: Format?): Boolean = format != null &&
    isHdrVideoFormat(format.sampleMimeType, PlaybackVideoColorTransfer.of(format))

/** Cap only bright, visible colours; preserve hue, alpha and already-dim subtitles. */
internal fun capSubtitleHighlight(argb: Int): Int {
    val a = (argb ushr 24) and 255
    if (a <= 180) return argb
    val r = (argb ushr 16) and 255
    val g = (argb ushr 8) and 255
    val b = argb and 255
    val brightness = (.2126 * r + .7152 * g + .0722 * b) * a / 255.0
    if (brightness <= 180.0) return argb
    val scale = 180.0 / brightness
    return (a shl 24) or ((r * scale).roundToInt() shl 16) or
        ((g * scale).roundToInt() shl 8) or (b * scale).roundToInt()
}

/** Keep the horizontal centre and bottom baseline stable when resizing image subtitles. */
internal fun resizedSubtitleAnchor(position: Float, before: Float, after: Float, anchor: Int, vertical: Boolean): Float {
    val fraction = when (anchor) { Cue.ANCHOR_TYPE_MIDDLE -> .5f; Cue.ANCHOR_TYPE_END -> 1f; else -> 0f }
    val reference = if (vertical) 1f else .5f
    return position + (before - after) * (reference - fraction)
}

internal class SubtitlePresentation {
    // Work happens at cue changes, never on a video frame. Bound retained bitmap memory.
    private val originals = java.util.WeakHashMap<Cue, Cue>()
    private var originalBitmap: Bitmap? = null
    private var dimmedBitmap: Bitmap? = null

    @Synchronized
    fun apply(input: Cue, style: SubtitleStyleSettings, dim: Boolean, preserveAss: Boolean): Cue {
        val cue = originals[input] ?: input
        var builder: Cue.Builder? = null
        val text = cue.text
        if (text is Spanned) {
            val result = SpannableString(text)
            var changed = false
            for (span in result.getSpans(0, result.length, Any::class.java)) {
                if (!preserveAss && (span is AbsoluteSizeSpan || span is RelativeSizeSpan ||
                    span is TypefaceSpan || span is StyleSpan)) {
                    result.removeSpan(span)
                    changed = true
                } else if (dim && span is ForegroundColorSpan) {
                    val color = capSubtitleHighlight(span.foregroundColor)
                    if (color != span.foregroundColor) {
                        val start = result.getSpanStart(span)
                        val end = result.getSpanEnd(span)
                        val flags = result.getSpanFlags(span)
                        result.removeSpan(span)
                        result.setSpan(ForegroundColorSpan(color), start, end, flags)
                        changed = true
                    }
                }
            }
            if (changed) builder = cue.buildUpon().setText(result)
        }
        val bitmap = cue.bitmap
        if (bitmap != null) {
            val scale = style.bitmapSize.coerceIn(50, 200) / 100f
            val offset = (style.verticalOffset - 5) / 250f
            if (scale != 1f || offset != 0f) {
                builder = builder ?: cue.buildUpon()
                if (cue.size != Cue.DIMEN_UNSET) {
                    val width = (cue.size * scale).coerceAtMost(1f)
                    builder.setSize(width)
                    if (cue.position != Cue.DIMEN_UNSET) builder.setPosition(
                        resizedSubtitleAnchor(cue.position, cue.size, width, cue.positionAnchor, false).coerceIn(0f, 1f))
                }
                val height = if (cue.bitmapHeight != Cue.DIMEN_UNSET) (cue.bitmapHeight * scale).coerceAtMost(1f) else null
                if (height != null) builder.setBitmapHeight(height)
                if (cue.lineType == Cue.LINE_TYPE_FRACTION && cue.line != Cue.DIMEN_UNSET) {
                    val line = if (height != null) resizedSubtitleAnchor(cue.line, cue.bitmapHeight, height, cue.lineAnchor, true) else cue.line
                    builder.setLine((line - offset).coerceIn(0f, 1f), Cue.LINE_TYPE_FRACTION)
                }
            }
            if (dim && bitmap.width.toLong() * bitmap.height <= 4_194_304) {
                if (originalBitmap !== bitmap) {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    var changed = false
                    for (i in pixels.indices) {
                        val capped = capSubtitleHighlight(pixels[i])
                        if (capped != pixels[i]) { pixels[i] = capped; changed = true }
                    }
                    dimmedBitmap = if (changed) Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888) else bitmap
                    originalBitmap = bitmap
                }
                if (dimmedBitmap !== bitmap) builder = (builder ?: cue.buildUpon()).setBitmap(requireNotNull(dimmedBitmap))
            }
        }
        val result = builder?.build() ?: cue
        if (result !== cue) {
            originals[result] = cue
        }
        return result
    }
}

internal fun PlayerRuntimeController.presentSubtitleCue(cue: Cue): Cue {
    val state = _uiState.value
    val codec = state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)?.codec.orEmpty()
    val url = state.selectedAddonSubtitle?.url.orEmpty()
    val ass = codec.contains("ass", true) || codec.contains("ssa", true) ||
        url.contains(".ass", true) || url.contains(".ssa", true)
    return subtitlePresentation.apply(cue, state.subtitleStyle, state.dimHdrOverlays && state.isHdrVideo, ass || (codec.isBlank() && url.isBlank()))
}

/** Track labels (including SDH) do not identify the rendering format. */
internal fun isBitmapSubtitleCodec(codec: String?): Boolean {
    val name = codec.orEmpty().lowercase(java.util.Locale.ROOT)
    return listOf("pgs", "hdmv", "dvd", "vobsub", "dvb", "xsub").any { it in name }
}
