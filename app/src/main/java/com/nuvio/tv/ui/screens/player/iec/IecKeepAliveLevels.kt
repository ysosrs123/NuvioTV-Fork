package com.nuvio.tv.ui.screens.player.iec

/**
 * Keep-alive levels for an IEC track. The fixed levels suit the full one second track; a track that
 * opened smaller can never queue that much, so the levels shrink with it and filler never lands in
 * the middle of content that is still arriving on time.
 */
internal object IecKeepAliveLevels {

    /** Content queued while playing; above this the playback thread waits. */
    fun capFrames(sampleRate: Int, bufferFrames: Long): Long =
        limited(IecPassthroughAudioSink.KEEP_ALIVE_CAP_MS, sampleRate, bufferFrames * 3L / 4L)

    /** Below this the keep-alive adds a padding-only frame. */
    fun lowWaterFrames(sampleRate: Int, bufferFrames: Long): Long =
        limited(IecPassthroughAudioSink.KEEP_ALIVE_LOW_WATER_MS, sampleRate, bufferFrames / 4L)

    private fun limited(ms: Long, sampleRate: Int, shareOfBuffer: Long): Long {
        val frames = ms * sampleRate / 1000L
        return if (shareOfBuffer > 0L) minOf(frames, shareOfBuffer) else frames
    }
}
