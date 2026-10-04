package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import java.util.Locale

/** Extraction-time decoding moves the original subtitle MIME type into Format.codecs. */
internal fun subtitleCodecName(format: Format): String? =
    CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
        ?: format.codecs?.split(',')?.firstNotNullOfOrNull {
            CustomDefaultTrackNameProvider.formatNameFromMime(it.trim().lowercase(Locale.ROOT))
        }
