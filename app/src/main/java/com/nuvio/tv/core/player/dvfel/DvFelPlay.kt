package com.nuvio.tv.core.player.dvfel

import android.util.Log
import com.nuvio.tv.BuildConfig
import java.nio.ByteBuffer

/**
 * JNI facade for libnuvio_dvfel.so (app/src/main/cpp/dvfel_jni.cpp), which dlopens signde's prebuilt
 * libdvfelplay.so (third_party/libdvfelplay): Dolby Vision Profile 7 FEL on a rooted Fire TV Cube 3
 * (Gazelle) with the dvfel kernel helper from the Fire TV AV module.
 *
 * Every call that takes a handle must run on one thread: ExoPlayer's playback thread
 * ([GazelleDvFelVideoRenderer] and [GazelleFelCodecAdapter]). The UI reads [GazelleFelState] instead.
 */
internal object DvFelPlay {
    private const val TAG = "DvFel"
    const val MODE_CONVERT = 0
    const val MODE_FEL = 1
    const val EL_NONE = 0
    const val EL_MEL = 1
    const val EL_FEL = 2
    /** Number of uint64_t counters in dvfp_stats (see [GazelleFelState.stats]). */
    const val STATS_COUNT = 10

    data class Probe(val hasRpu: Boolean, val profile: Int, val elType: Int, val hasEl: Boolean)

    /** Loaded lazily and only on Gazelle, so other boxes never touch libdvfelplay. */
    val isLoaded: Boolean by lazy {
        BuildConfig.DVFEL_GAZELLE_ENABLED && GazelleFelSupport.isGazelleBoard() &&
            runCatching { System.loadLibrary("nuvio_dvfel"); nativeLoad() }
                .onFailure { Log.w(TAG, "not loaded: ${it.message}") }
                .getOrDefault(false)
    }

    /** The dvfel helper is loaded, new enough and accessible from this app. */
    fun available(): Boolean = isLoaded && runCatching { nativeAvailable() }.getOrDefault(false)

    fun libVersion(): String = if (isLoaded) nativeLibVersion() else ""
    fun moduleVersion(): String = if (isLoaded) nativeModuleVersion() else ""

    /** Dolby Vision info of the Annex-B access unit between [ByteBuffer.position] and the limit. */
    fun probe(b: ByteBuffer): Probe? {
        if (!isLoaded) return null
        val o = IntArray(4)
        return if (nativeProbe(b, b.position(), b.remaining(), o) == 0) {
            Probe(o[0] != 0, o[1], o[2], o[3] != 0)
        } else null
    }

    /** Native handle, 0 on failure. One FEL session per device. */
    fun open(width: Int, height: Int, mode: Int): Long = if (isLoaded) nativeOpen(width, height, mode) else 0L
    fun mode(handle: Long): Int = nativeMode(handle)

    /**
     * Filters [length] bytes at [offset] of the direct buffer [buf] in place. Returns the new length, or
     * -1 filter error, -2 output larger than the buffer, -3 not a direct buffer, -4 bad range.
     */
    fun filterInPlace(handle: Long, buf: ByteBuffer, offset: Int, length: Int): Int =
        nativeFilterInPlace(handle, buf, offset, length)

    fun commit(handle: Long, ptsUs: Long) = nativeCommit(handle, ptsUs)
    fun drop(handle: Long) = nativeDrop(handle)
    fun flush(handle: Long) = nativeFlush(handle)
    fun close(handle: Long) = nativeClose(handle)

    /** The [STATS_COUNT] dvfp_stats counters, in declaration order. */
    fun stats(handle: Long): LongArray = LongArray(STATS_COUNT).also { nativeStats(handle, it) }

    @JvmStatic private external fun nativeLoad(): Boolean
    @JvmStatic private external fun nativeAvailable(): Boolean
    @JvmStatic private external fun nativeLibVersion(): String
    @JvmStatic private external fun nativeModuleVersion(): String
    @JvmStatic private external fun nativeProbe(buf: ByteBuffer, offset: Int, length: Int, out: IntArray): Int
    @JvmStatic private external fun nativeOpen(width: Int, height: Int, mode: Int): Long
    @JvmStatic private external fun nativeMode(handle: Long): Int
    @JvmStatic private external fun nativeFilterInPlace(handle: Long, buf: ByteBuffer, offset: Int, length: Int): Int
    @JvmStatic private external fun nativeCommit(handle: Long, ptsUs: Long)
    @JvmStatic private external fun nativeDrop(handle: Long)
    @JvmStatic private external fun nativeFlush(handle: Long)
    @JvmStatic private external fun nativeClose(handle: Long)
    @JvmStatic private external fun nativeStats(handle: Long, out: LongArray)
}
