package com.nuvio.tv.core.player.thumbnail

import android.graphics.Bitmap
import android.util.Log
import java.io.Closeable
import kotlin.math.roundToInt

/** JNI bridge to libnuviothumb (app/src/main/cpp/thumb_shim.cpp). */
internal object ThumbNative {
    const val INFO_WIDTH = 0
    const val INFO_HEIGHT = 1
    const val INFO_BIT_DEPTH = 2
    const val INFO_SAR_NUM = 3
    const val INFO_SAR_DEN = 4
    const val INFO_TRC = 5
    const val INFO_PRIMARIES = 6
    const val INFO_MATRIX = 7
    const val INFO_RANGE = 8
    const val INFO_MAX_CLL = 9
    const val INFO_MASTERING_MAX_NITS = 10
    const val INFO_DOVI_IPT = 11
    const val INFO_COUNT = 12

    /**
     * False when the library or an in-APK FFmpeg cannot be loaded, thumbnails then stay off. The native side prefers
     * MPV's FFmpeg (~2x faster at 4K) and falls back to the FFmpeg 6.0 in libmediainfo.so.
     */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("nuviothumb")
            nativeInit().also { ok -> if (ok) Log.i("ThumbDecode", "decoder backend: ${nativeBackend()}") }
        } catch (t: Throwable) {
            Log.w("ThumbDecode", "native decoder unavailable: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    @JvmStatic external fun nativeInit(): Boolean
    @JvmStatic external fun nativeBackend(): String
    /** [decoder]: FFmpeg decoder name. width/height: coded size. */
    @JvmStatic external fun nativeOpen(decoder: String, extradata: ByteArray?, width: Int, height: Int, threads: Int): Long
    @JvmStatic external fun nativeDecode(handle: Long, data: ByteArray, len: Int, info: IntArray): Int
    @JvmStatic external fun nativeRender(
        handle: Long, bitmap: Bitmap, transfer: Int, bt2020: Boolean, fullRange: Boolean,
        peakNits: Float, rowSkip: Boolean,
    ): Int
    @JvmStatic external fun nativeTrim(handle: Long)
    @JvmStatic external fun nativeRelease(handle: Long)
    /** Returns freed allocator pages to the system (mallopt M_PURGE). */
    @JvmStatic external fun nativePurge()
}

internal class DecodedFrame(
    val width: Int,
    val height: Int,
    val sarNum: Int,
    val sarDen: Int,
    val colour: FrameColour,
)

internal object ThumbGeometry {
    const val MAX_WIDTH = 320

    /** Output size from the display aspect (container first, then the bitstream's SAR). Never upscaled, even height. */
    fun outputSize(frameW: Int, frameH: Int, sarNum: Int, sarDen: Int, containerAspect: Double): IntArray {
        val dar = when {
            containerAspect > 0.0 -> containerAspect
            sarNum > 0 && sarDen > 0 -> frameW.toDouble() * sarNum / (frameH.toDouble() * sarDen)
            else -> frameW.toDouble() / frameH
        }.takeIf { it.isFinite() && it > 0.1 && it < 10.0 } ?: (frameW.toDouble() / frameH)
        val displayW = (frameH * dar).roundToInt().coerceAtLeast(2)
        val w = minOf(MAX_WIDTH, displayW) and 1.inv()
        val h = ((w / dar).roundToInt() and 1.inv()).coerceAtLeast(2)
        return intArrayOf(w.coerceAtLeast(2), h)
    }
}

/**
 * Software keyframe decoder over the in-APK FFmpeg. One context, reused across a title's keyframes.
 * Not thread-safe: each decode lane owns one.
 */
internal class FfmpegThumbDecoder : Closeable {
    private var handle = 0L
    private var handleTrack: VideoTrack? = null
    private var handleThreads = 0
    private val info = IntArray(ThumbNative.INFO_COUNT)
    private var decodedSinceTrim = false

    /** The working set is allocated and already missing from MemAvailable, so the governor must not charge it again. */
    val warm: Boolean get() = handle != 0L && decodedSinceTrim

    /** [annexB]: one keyframe access unit, Annex-B for H.264/HEVC, the raw sample otherwise. */
    fun decode(annexB: ByteArray, track: VideoTrack, threads: Int): DecodedFrame? {
        if (!ThumbNative.available) return null
        if (handle == 0L || handleTrack !== track || handleThreads != threads) {
            close()
            handle = ThumbNative.nativeOpen(
                track.codec.ffmpegName, track.extradata.takeIf { it.isNotEmpty() }, track.width, track.height, threads,
            )
            if (handle == 0L) {
                Log.w("ThumbDecode", "no ${track.codec.ffmpegName} decoder in this FFmpeg")
                return null
            }
            handleTrack = track
            handleThreads = threads
        }
        val r = ThumbNative.nativeDecode(handle, annexB, annexB.size, info)
        if (r < 0) {
            Log.w("ThumbDecode", "decode failed: $r")
            return null
        }
        decodedSinceTrim = true
        return DecodedFrame(
            width = info[ThumbNative.INFO_WIDTH],
            height = info[ThumbNative.INFO_HEIGHT],
            sarNum = info[ThumbNative.INFO_SAR_NUM],
            sarDen = info[ThumbNative.INFO_SAR_DEN],
            colour = FrameColour(
                bitDepth = info[ThumbNative.INFO_BIT_DEPTH],
                transfer = info[ThumbNative.INFO_TRC],
                primaries = info[ThumbNative.INFO_PRIMARIES],
                matrix = info[ThumbNative.INFO_MATRIX],
                range = info[ThumbNative.INFO_RANGE],
                maxCll = info[ThumbNative.INFO_MAX_CLL],
                masteringMaxNits = info[ThumbNative.INFO_MASTERING_MAX_NITS],
                doviIpt = info[ThumbNative.INFO_DOVI_IPT] != 0,
            ),
        )
    }

    /** Renders the last decoded frame into a new bitmap. */
    fun render(frame: DecodedFrame, decision: ColourDecision, outW: Int, outH: Int, rgb565: Boolean): Bitmap? {
        if (handle == 0L) return null
        val bmp = Bitmap.createBitmap(outW, outH, if (rgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
        val rowSkip = frame.height >= 4 * outH
        val r = ThumbNative.nativeRender(
            handle, bmp, decision.transfer.nativeCode, decision.bt2020, decision.fullRange,
            decision.peakNits, rowSkip,
        )
        if (r < 0) {
            Log.w("ThumbDecode", "render failed: $r")
            bmp.recycle()
            return null
        }
        return bmp
    }

    fun trim() {
        if (handle != 0L) ThumbNative.nativeTrim(handle)
        decodedSinceTrim = false
    }

    override fun close() {
        decodedSinceTrim = false
        if (handle != 0L) {
            ThumbNative.nativeRelease(handle)
            handle = 0L
            handleTrack = null
        }
    }
}

/** One decode lane and its decoder. Close and trim asked for during a decode are done once it ends. */
internal class DecoderLane {
    val decoder = FfmpegThumbDecoder()
    private var busy = false
    private var closeWhenFree = false
    private var trimWhenFree = false

    fun acquire() {
        synchronized(this) { busy = true }
    }

    fun release() {
        synchronized(this) {
            busy = false
            if (closeWhenFree) decoder.close() else if (trimWhenFree) decoder.trim()
            closeWhenFree = false
            trimWhenFree = false
        }
    }

    fun close() {
        synchronized(this) {
            if (!busy) decoder.close()
            closeWhenFree = busy
        }
    }

    fun trim() {
        synchronized(this) {
            if (!busy) decoder.trim()
            trimWhenFree = busy
        }
    }
}
