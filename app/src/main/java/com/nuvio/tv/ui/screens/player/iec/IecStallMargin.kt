package com.nuvio.tv.ui.screens.player.iec

internal object IecStallMargin {
    // 192 kHz, 8 channels: 3,686,400 bytes, under the 4 MB audio heap of a 2 GB box.
    const val SMALL_BOX_TRUEHD_BUFFER_MS = 1_200

    fun trueHdBufferMs(smallBox: Boolean): Int =
        if (smallBox) SMALL_BOX_TRUEHD_BUFFER_MS else IecPassthroughAudioSink.IEC_BUFFER_TARGET_MS
}
