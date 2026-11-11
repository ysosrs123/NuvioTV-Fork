package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.dvmkv.DtsStreamType
import com.nuvio.tv.core.player.dvmkv.TrueHdAtmos

/**
 * The source audio name when more is known than the codec: the DTS type from the frames, or Atmos inside
 * TrueHD from the frames or the track's own name.
 */
internal fun bitstreamAudioLabel(format: Format?): String? {
    DtsStreamType.of(format)?.label?.let { return it }
    if (format == null || format.sampleMimeType != MimeTypes.AUDIO_TRUEHD) return null
    return if (TrueHdAtmos.isPresent(format) || trackNameSaysAtmos(format.label)) "TrueHD Atmos" else null
}

internal fun trackNameSaysAtmos(label: String?): Boolean =
    label != null && TRACK_ATMOS.containsMatchIn(label)

private val TRACK_ATMOS = Regex("""(?<![a-z])atmos(?![a-z])""", RegexOption.IGNORE_CASE)
