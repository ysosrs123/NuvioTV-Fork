package com.nuvio.tv.core.player.thumbnail

/** What the decoder reported about the decoded keyframe (FFmpeg AVFrame fields, see ThumbNative.INFO_*). */
internal data class FrameColour(
    val bitDepth: Int,
    /** AVColorTransferCharacteristic (same code points as ISO/IEC 23091-2). */
    val transfer: Int,
    val primaries: Int,
    val matrix: Int,
    /** AVColorRange: 1 = MPEG (limited), 2 = JPEG (full), 0 = unspecified. */
    val range: Int,
    val maxCll: Int,
    val masteringMaxNits: Int,
    /** FFmpeg attached Dolby Vision metadata with an IPT signal (profile 5): must be reshaped. */
    val doviIpt: Boolean = false,
)

/** DOVI_IPT: Dolby Vision profile 5, reshaped natively from the frame's DV metadata into PQ BT.2020. */
internal enum class ThumbTransfer(val nativeCode: Int) { SDR(0), PQ(1), HLG(2), DOVI_IPT(3) }

internal data class ColourDecision(
    val transfer: ThumbTransfer,
    val bt2020: Boolean,
    val fullRange: Boolean,
    val peakNits: Float,
    val reason: String,
)

/**
 * Order: Dolby Vision signalling, container Colour, bitstream VUI, else SDR. 10-bit BT.2020 with transfer 14/15
 * counts as PQ: PQ-graded web re-encodes are commonly tagged that way, with no HDR SEI and no container Colour.
 */
internal object ColourClassifier {
    private const val TRC_UNSPECIFIED = 2
    private const val TRC_PQ = 16
    private const val TRC_HLG = 18
    private const val TRC_BT2020_10 = 14
    private const val TRC_BT2020_12 = 15
    private const val PRI_BT2020 = 9
    private const val SPC_BT2020_NCL = 9
    private const val SPC_BT2020_CL = 10
    private const val DEFAULT_PEAK_NITS = 1000f

    fun classify(container: ContainerColour, frame: FrameColour, dv: DolbyVisionConfig?): ColourDecision {
        val bt2020 = container.primaries == PRI_BT2020 || frame.primaries == PRI_BT2020 ||
            frame.matrix == SPC_BT2020_NCL || frame.matrix == SPC_BT2020_CL
        val fullRange = when {
            container.range == 2 -> true
            container.range == 1 -> false
            else -> frame.range == 2
        }
        val peak = when {
            frame.maxCll > 0 -> frame.maxCll.toFloat()
            frame.masteringMaxNits > 0 -> frame.masteringMaxNits.toFloat()
            container.maxCll > 0 -> container.maxCll.toFloat()
            container.masteringMaxNits > 0 -> container.masteringMaxNits.toFloat()
            else -> DEFAULT_PEAK_NITS
        }

        // Dolby Vision profile 5 (or any IPT-signalled RPU): the decoder's metadata decides.
        if (frame.doviIpt) {
            return ColourDecision(ThumbTransfer.DOVI_IPT, true, true, peak, "dolby-vision IPT (p${dv?.profile ?: 5}) reshaped")
        }
        // Dolby Vision: the base layer's signalling is defined by the profile / compatibility id.
        if (dv != null) {
            val t = when {
                dv.profile == 8 && dv.blCompatId == 4 -> ThumbTransfer.HLG
                dv.profile == 8 && dv.blCompatId == 2 -> ThumbTransfer.SDR
                dv.profile == 7 || dv.profile == 8 -> ThumbTransfer.PQ
                else -> null
            }
            if (t != null) return ColourDecision(t, bt2020 || t != ThumbTransfer.SDR, fullRange, peak,
                "dolby-vision p${dv.profile} c${dv.blCompatId}")
        }

        val declared = when {
            container.transfer != 0 && container.transfer != TRC_UNSPECIFIED -> container.transfer to "container"
            frame.transfer != 0 && frame.transfer != TRC_UNSPECIFIED -> frame.transfer to "bitstream"
            else -> 0 to "none"
        }
        val (trc, source) = declared
        return when {
            trc == TRC_PQ -> ColourDecision(ThumbTransfer.PQ, true, fullRange, peak, "$source PQ")
            trc == TRC_HLG -> ColourDecision(ThumbTransfer.HLG, true, fullRange, peak, "$source HLG")
            (trc == TRC_BT2020_10 || trc == TRC_BT2020_12) && frame.bitDepth >= 10 && bt2020 ->
                ColourDecision(ThumbTransfer.PQ, true, fullRange, peak, "D1: 10-bit BT.2020 transfer $trc treated as PQ")
            else -> ColourDecision(ThumbTransfer.SDR, bt2020, fullRange, 0f, "$source SDR")
        }
    }
}
