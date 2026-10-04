package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink

/** Sink reset can precede delivery on the application thread. Use the failure's immutable input.
 * Renderer formats can describe compressed source even when FFmpeg supplied PCM to the sink. */
internal fun failedAudioTrackInputFormat(error: Throwable?): Format? {
    var cause = error
    repeat(8) {
        if (cause is AudioSink.InitializationException) return (cause as AudioSink.InitializationException).format
        cause = cause?.cause ?: return null
    }
    return null
}
