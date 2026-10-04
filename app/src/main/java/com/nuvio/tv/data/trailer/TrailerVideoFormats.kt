package com.nuvio.tv.data.trailer

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.view.Display

internal const val TRAILER_MAX_VIDEO_HEIGHT = 1080
internal const val TRAILER_MAX_VIDEO_WIDTH = 1920

internal enum class TrailerVideoCodec {
    H264,
    VP9,
    AV1,
    OTHER
}

/** What this device can show, as far as picking a trailer stream is concerned. */
internal data class TrailerVideoLimits(
    val maxHeight: Int = TRAILER_MAX_VIDEO_HEIGHT,
    val maxWidth: Int = TRAILER_MAX_VIDEO_WIDTH,
    val av1HardwareDecoder: Boolean = false,
    val hdrDisplay: Boolean = false
)

private val CODECS_REGEX = Regex("codecs=\"([^\"]+)\"")
private val HDR_TRANSFERS = setOf(
    "COLOR_TRANSFER_CHARACTERISTICS_SMPTEST2084",
    "COLOR_TRANSFER_CHARACTERISTICS_ARIB_STD_B67"
)

internal fun trailerVideoCodecOf(mimeType: String): TrailerVideoCodec {
    val codecs = CODECS_REGEX.find(mimeType)?.groupValues?.get(1)?.lowercase().orEmpty()
    return when {
        codecs.startsWith("avc1") || codecs.startsWith("avc3") -> TrailerVideoCodec.H264
        codecs.startsWith("vp9") || codecs.startsWith("vp09") -> TrailerVideoCodec.VP9
        codecs.startsWith("av01") -> TrailerVideoCodec.AV1
        else -> TrailerVideoCodec.OTHER
    }
}

internal fun isHdrTrailerFormat(mimeType: String, qualityLabel: String?, transferCharacteristics: String?): Boolean {
    val codecs = CODECS_REGEX.find(mimeType)?.groupValues?.get(1)?.lowercase().orEmpty()
    return transferCharacteristics in HDR_TRANSFERS ||
        qualityLabel?.contains("HDR", ignoreCase = true) == true ||
        codecs.startsWith("vp09.02")
}

/**
 * The video streams a trailer may use on this device. When the limits leave nothing they are
 * given up one at a time (codec, then HDR, then size) so a trailer still plays.
 */
internal fun trailerVideoCandidates(
    candidates: List<StreamCandidate>,
    limits: TrailerVideoLimits
): List<StreamCandidate> {
    fun allowed(size: Boolean, hdr: Boolean, codec: Boolean) = candidates.filter { candidate ->
        (!size || (candidate.height <= limits.maxHeight && candidate.width <= limits.maxWidth)) &&
            (!hdr || limits.hdrDisplay || !candidate.isHdr) &&
            (!codec || limits.av1HardwareDecoder || candidate.codec != TrailerVideoCodec.AV1)
    }
    return allowed(size = true, hdr = true, codec = true)
        .ifEmpty { allowed(size = true, hdr = true, codec = false) }
        .ifEmpty { allowed(size = true, hdr = false, codec = false) }
        .ifEmpty { allowed(size = false, hdr = true, codec = true) }
        .ifEmpty { allowed(size = false, hdr = true, codec = false) }
        .ifEmpty { candidates }
}

internal fun trailerVideoCodecRank(codec: TrailerVideoCodec?): Int = when (codec) {
    TrailerVideoCodec.H264 -> 0
    TrailerVideoCodec.VP9 -> 1
    TrailerVideoCodec.AV1 -> 2
    TrailerVideoCodec.OTHER -> 3
    null -> 0
}

internal fun deviceTrailerVideoLimits(context: Context): TrailerVideoLimits = TrailerVideoLimits(
    av1HardwareDecoder = hasHardwareDecoder("video/av01"),
    hdrDisplay = displaySupportsHdr(context)
)

private fun hasHardwareDecoder(mimeType: String): Boolean = runCatching {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        !info.isEncoder &&
            info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } &&
            if (Build.VERSION.SDK_INT >= 29) {
                info.isHardwareAccelerated
            } else {
                !info.name.startsWith("OMX.google.", ignoreCase = true) &&
                    !info.name.startsWith("c2.android.", ignoreCase = true)
            }
    }
}.getOrDefault(false)

@Suppress("DEPRECATION")
private fun displaySupportsHdr(context: Context): Boolean = runCatching {
    context.getSystemService(DisplayManager::class.java)
        ?.getDisplay(Display.DEFAULT_DISPLAY)
        ?.hdrCapabilities
        ?.supportedHdrTypes
        ?.isNotEmpty() == true
}.getOrDefault(false)
