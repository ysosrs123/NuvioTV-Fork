package com.nuvio.tv.ui.screens.player

/** Runtime measurement support, independent of the saved tunneling preference. */
internal enum class PlaybackVideoPresentation {
    UNCONFIGURED, MEDIA_CODEC, TUNNELLED, TUNNELLED_UNSUPPORTED, NATIVE, UNSUPPORTED;

    val isTunnelled: Boolean get() = this == TUNNELLED || this == TUNNELLED_UNSUPPORTED
    val supportsFrameLead: Boolean get() = this == MEDIA_CODEC
    val supportsCadenceJudgement: Boolean get() = this == MEDIA_CODEC
    val supportsDropCounters: Boolean get() = this == MEDIA_CODEC || this == TUNNELLED

    companion object {
        fun resolve(hasSelectedVideo: Boolean, configured: Boolean, appliedTunneling: Boolean,
                    decoderName: String?, hasCounters: Boolean): PlaybackVideoPresentation {
            if (!hasSelectedVideo || !configured) return UNCONFIGURED
            val mediaCodec = decoderName?.let {
                it.startsWith("OMX.", ignoreCase = true) || it.startsWith("c2.", ignoreCase = true)
            } == true
            // Applied configuration remains reportable even when output counters are unavailable.
            if (appliedTunneling) return if (mediaCodec && hasCounters) TUNNELLED else TUNNELLED_UNSUPPORTED
            if (decoderName.isNullOrBlank()) return UNCONFIGURED
            if (decoderName == "amstream_dves_hevc" || decoderName == "amstream_dves_hevc.sideband") return NATIVE
            if (!mediaCodec) return UNSUPPORTED
            return if (hasCounters) MEDIA_CODEC else UNCONFIGURED
        }
    }
}
