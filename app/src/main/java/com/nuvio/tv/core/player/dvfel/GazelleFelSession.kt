package com.nuvio.tv.core.player.dvfel

import android.util.Log
import java.nio.ByteBuffer

/**
 * Owns one libdvfelplay handle. Used only on the ExoPlayer playback thread (the renderer's
 * onQueueInputBuffer and the codec adapter's flush/release), as libdvfelplay requires.
 */
internal class GazelleFelSession {
    var handle = 0L
        private set
    val isOpen: Boolean get() = handle != 0L

    /**
     * Media3 adds a large renderer offset (1e12 us) to every timestamp. VLC passes the file's own
     * times, and the Amlogic decoders and the dvfel pairing are only proven with those, so the
     * decoder and libdvfelplay get the timestamp minus this base; output gets it added back.
     * Set from the first timestamp of this codec, cleared when the session closes.
     */
    private var ptsBaseUs = -1L

    /** Player timestamp -> the one given to the decoder and libdvfelplay. */
    fun toCodecPts(ptsUs: Long): Long {
        // only an offset timestamp sets the base (EOS and workaround buffers carry 0)
        if (ptsBaseUs < 0 && ptsUs >= RENDERER_OFFSET_US) {
            ptsBaseUs = ptsUs / RENDERER_OFFSET_US * RENDERER_OFFSET_US
            Log.i(TAG, "codec pts base $ptsBaseUs us (first pts $ptsUs)")
        }
        return if (ptsBaseUs > 0 && ptsUs >= ptsBaseUs) ptsUs - ptsBaseUs else ptsUs
    }

    /** Decoder output timestamp -> player timestamp. */
    fun toPlayerPts(codecPtsUs: Long): Long = if (ptsBaseUs > 0) codecPtsUs + ptsBaseUs else codecPtsUs

    fun open(width: Int, height: Int, mode: Int, elType: Int): Boolean {
        check(!isOpen) { "session already open" }
        handle = DvFelPlay.open(width, height, mode)
        if (isOpen) {
            GazelleFelState.onOpened(DvFelPlay.mode(handle), elType)
            Log.i(TAG, "opened ${width}x$height mode=${DvFelPlay.mode(handle)} elType=$elType")
        }
        return isOpen
    }

    /** Filters [data] (position..limit) in place and commits [ptsUs]; returns the new length or < 0. */
    fun filterAndCommit(data: ByteBuffer, ptsUs: Long): Int {
        val n = DvFelPlay.filterInPlace(handle, data, data.position(), data.remaining())
        if (n >= 0) DvFelPlay.commit(handle, toCodecPts(ptsUs))
        return n
    }

    /** After the MediaCodec flush (seek). Blocks up to debug.dvfp.seek_drain_ms on Gazelle; expected. */
    fun flush() {
        if (isOpen) DvFelPlay.flush(handle)
    }

    /** After the MediaCodec release: ends the kernel session. */
    fun close() {
        ptsBaseUs = -1L
        if (!isOpen) return
        snapshotStats()
        GazelleFelState.stats?.let { Log.i(TAG, "close stats=${it.joinToString(",")}") }
        DvFelPlay.close(handle)
        handle = 0L
        GazelleFelState.onClosed()
    }

    fun snapshotStats() {
        if (isOpen) GazelleFelState.stats = DvFelPlay.stats(handle)
    }

    private companion object {
        const val TAG = "DvFel"
        /** Media3's initial renderer position offset. */
        const val RENDERER_OFFSET_US = 1_000_000_000_000L
    }
}
