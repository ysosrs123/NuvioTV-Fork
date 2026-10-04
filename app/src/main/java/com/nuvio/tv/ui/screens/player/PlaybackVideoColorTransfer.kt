package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil

/**
 * Reads the transfer characteristics straight out of an HEVC SPS.
 *
 * `MatroskaExtractor` builds `Format.colorInfo` only when the MKV `Colour` element
 * is present, and many remuxes are muxed without it, so `colorInfo` is null and the
 * HUD's HDR row would read "SDR" on an HDR10 stream. `Format.initializationData`
 * holds the VPS/SPS/PPS in Annex-B form, so the SPS VUI can be parsed from there.
 *
 * HEVC only.
 */
@UnstableApi
internal object PlaybackVideoColorTransfer {

    private const val H265_NAL_UNIT_TYPE_SPS = 33
    private const val NAL_HEADER_BYTES = 2

    // Formats do not change often; parse once per initialisation blob.
    @Volatile
    private var cachedInitData: ByteArray? = null

    @Volatile
    private var cachedTransfer: Int = Format.NO_VALUE

    /**
     * The colour transfer the container declared or, when it declared none, the one the
     * HEVC SPS carries. [Format.NO_VALUE] when neither is available.
     */
    fun of(format: Format): Int {
        val declared = format.colorInfo?.colorTransfer ?: Format.NO_VALUE
        if (declared != Format.NO_VALUE) return declared

        val initData = format.initializationData.firstOrNull() ?: return Format.NO_VALUE
        val cached = cachedInitData
        if (cached != null && cached.contentEquals(initData)) return cachedTransfer

        val parsed = parseSpsColorTransfer(initData)
        cachedInitData = initData
        cachedTransfer = parsed
        return parsed
    }

    private fun parseSpsColorTransfer(data: ByteArray): Int = runCatching {
        var offset = 0
        while (offset + 4 + NAL_HEADER_BYTES <= data.size) {
            if (!isStartCode(data, offset)) {
                offset++
                continue
            }
            val nalStart = offset + 4
            var nalEnd = data.size
            var scan = nalStart
            while (scan + 4 <= data.size) {
                if (isStartCode(data, scan)) {
                    nalEnd = scan
                    break
                }
                scan++
            }
            // HEVC NAL header: forbidden_zero_bit, then 6 bits of nal_unit_type.
            val nalType = (data[nalStart].toInt() shr 1) and 0x3F
            if (nalType == H265_NAL_UNIT_TYPE_SPS) {
                return@runCatching NalUnitUtil
                    .parseH265SpsNalUnit(data, nalStart, nalEnd, /* vpsData= */ null)
                    .colorTransfer
            }
            offset = nalEnd
        }
        Format.NO_VALUE
    }.getOrDefault(Format.NO_VALUE)

    private fun isStartCode(data: ByteArray, at: Int): Boolean =
        at + 4 <= data.size &&
            data[at] == 0.toByte() &&
            data[at + 1] == 0.toByte() &&
            data[at + 2] == 0.toByte() &&
            data[at + 3] == 1.toByte()
}
