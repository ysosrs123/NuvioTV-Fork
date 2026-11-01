package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi

/**
 * Colour signalling for a Dolby Vision track that is played as its HEVC base layer.
 *
 * The base layer's transfer follows the DV config's bl_signal_compatibility_id: profile 7 and 8.1 carry
 * HDR10 (PQ), 8.4 carries HLG and 8.2 is SDR. Matroska files often have no Colour element, so without this
 * the decoder is configured with no colour information at all.
 */
@UnstableApi
internal object DolbyVisionBaseLayerColor {

    const val COMPATIBILITY_UNKNOWN = -1

    /** bl_signal_compatibility_id from a dvcC/dvvC payload (the box body, without its header). */
    @JvmStatic
    fun compatibilityId(dolbyVisionConfig: ByteArray?): Int {
        if (dolbyVisionConfig == null || dolbyVisionConfig.size < 5) return COMPATIBILITY_UNKNOWN
        return (dolbyVisionConfig[4].toInt() and 0xFF) ushr 4
    }

    /**
     * The transfer the decoded base layer carries, or [Format.NO_VALUE] when it should not be labelled.
     * [bitstreamTransfer] is the SPS VUI transfer, used only when the compatibility id gives no answer.
     */
    @JvmStatic
    fun baseLayerTransfer(profile: Int, compatibilityId: Int, bitstreamTransfer: Int): Int = when (profile) {
        7 -> C.COLOR_TRANSFER_ST2084
        8 -> when (compatibilityId) {
            1 -> C.COLOR_TRANSFER_ST2084
            2 -> Format.NO_VALUE
            4 -> C.COLOR_TRANSFER_HLG
            else -> when (bitstreamTransfer) {
                C.COLOR_TRANSFER_HLG -> C.COLOR_TRANSFER_HLG
                C.COLOR_TRANSFER_SDR -> Format.NO_VALUE
                else -> C.COLOR_TRANSFER_ST2084
            }
        }
        else -> Format.NO_VALUE
    }

    /**
     * [existing] with the base layer's transfer filled in. An existing PQ or HLG transfer is kept, as are any
     * colour space, range or static metadata the container declared.
     */
    @JvmStatic
    fun colorInfoFor(existing: ColorInfo?, transfer: Int): ColorInfo? {
        if (transfer == Format.NO_VALUE) return existing
        val existingTransfer = existing?.colorTransfer
        if (existingTransfer == C.COLOR_TRANSFER_ST2084 || existingTransfer == C.COLOR_TRANSFER_HLG) return existing
        val builder = existing?.buildUpon() ?: ColorInfo.Builder()
        if (existing == null || existing.colorSpace == Format.NO_VALUE) builder.setColorSpace(C.COLOR_SPACE_BT2020)
        if (existing == null || existing.colorRange == Format.NO_VALUE) builder.setColorRange(C.COLOR_RANGE_LIMITED)
        return builder.setColorTransfer(transfer).build()
    }
}
