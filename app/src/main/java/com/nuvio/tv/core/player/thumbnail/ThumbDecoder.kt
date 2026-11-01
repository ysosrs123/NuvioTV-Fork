package com.nuvio.tv.core.player.thumbnail

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
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
    const val INFO_STRIPS = 12
    const val INFO_COUNT = 13

    private val retry = NativeLoadBackoff()
    @Volatile private var libraryLoaded = false
    @Volatile private var ready = false
    @Volatile private var backendName: String? = null
    @Volatile private var lastError: String? = null

    /** True once the library and an in-APK FFmpeg are loaded. Never loads anything itself, see [load]. */
    val available: Boolean get() = ready

    /**
     * Loads the library and an FFmpeg backend unless already done. A failed load is tried again by a later session,
     * after [NativeLoadBackoff]'s wait. The native side prefers MPV's FFmpeg (~2x faster at 4K) and falls back to the
     * FFmpeg 6.0 in libmediainfo.so. Takes 150-440 ms the first time: never on the main thread.
     */
    fun load(): Boolean {
        if (ready) return true
        synchronized(this) {
            if (ready) return true
            val now = SystemClock.elapsedRealtime()
            if (!retry.mayTry(now)) return false
            try {
                if (!libraryLoaded) {
                    System.loadLibrary("nuviothumb")
                    libraryLoaded = true
                }
                if (nativeInit()) {
                    val backend = nativeBackend()
                    backendName = backend.substringBefore(' ')
                    ready = true
                    Log.i("ThumbDecode", "decoder backend: $backend" +
                        if (retry.failures > 0) " (after ${retry.failures} failed tries)" else "")
                    return true
                }
                lastError = runCatching { nativeLastError() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "no FFmpeg backend"
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message}"
            }
            retry.failed(now)
            Log.w("ThumbDecode", "native decoder unavailable (try ${retry.failures}): $lastError; " +
                "tried again in ${retry.waitMs(now) / 1000} s at the earliest")
            return false
        }
    }

    /** For the log: the backend in use, or why none is. */
    fun state(): String = when {
        ready -> "ok ${backendName.orEmpty()}".trim()
        lastError == null -> "not loaded yet"
        else -> "unavailable after ${retry.failures} tries ($lastError)"
    }

    @JvmStatic external fun nativeInit(): Boolean
    @JvmStatic external fun nativeLastError(): String
    @JvmStatic external fun nativeBackend(): String
    /** [decoder]: FFmpeg decoder name. width/height: coded size. */
    @JvmStatic external fun nativeOpen(decoder: String, extradata: ByteArray?, width: Int, height: Int, threads: Int): Long
    @JvmStatic external fun nativeDecode(handle: Long, data: ByteArray, len: Int, info: IntArray): Int
    /** As [nativeDecode], from a direct buffer: the native side copies from its address, no Java array. */
    @JvmStatic external fun nativeDecodeDirect(handle: Long, data: ByteBuffer, len: Int, info: IntArray): Int
    @JvmStatic external fun nativeRender(
        handle: Long, bitmap: Bitmap, transfer: Int, bt2020: Boolean, fullRange: Boolean,
        peakNits: Float, rowSkip: Boolean,
    ): Int
    /** Strips an Annex B HEVC keyframe would decode in, 0 = whole. */
    @JvmStatic external fun nativeStripCount(data: ByteArray, len: Int): Int
    @JvmStatic external fun nativeTrim(handle: Long)
    @JvmStatic external fun nativeRelease(handle: Long)
    /** Returns freed allocator pages to the system (mallopt M_PURGE). */
    @JvmStatic external fun nativePurge()
    /** thumbcolor's tables for [transfer] (1 PQ, 2 HLG), see [ToneLuts]. */
    @JvmStatic external fun nativeToneLuts(transfer: Int, peakNits: Float, bt2020: Boolean): FloatArray?
}

/** When a failed native load may be tried again: [FIRST_WAIT_MS] after the first failure, doubling, at most [MAX_WAIT_MS]. */
internal class NativeLoadBackoff {
    companion object {
        const val FIRST_WAIT_MS = 60_000L
        const val MAX_WAIT_MS = 15 * 60_000L
    }

    @Volatile var failures = 0
        private set
    private var nextTryAtMs = Long.MIN_VALUE

    fun mayTry(nowMs: Long): Boolean = nowMs >= nextTryAtMs

    fun failed(nowMs: Long) {
        failures++
        nextTryAtMs = nowMs + (FIRST_WAIT_MS shl (failures - 1).coerceAtMost(10)).coerceAtMost(MAX_WAIT_MS)
    }

    fun waitMs(nowMs: Long): Long = if (failures == 0) 0L else (nextTryAtMs - nowMs).coerceAtLeast(0L)
}

internal class DecodedFrame(
    val width: Int,
    val height: Int,
    val sarNum: Int,
    val sarDen: Int,
    val colour: FrameColour,
    /** Strips the picture was decoded in, 0 = whole. */
    val strips: Int = 0,
)

internal object ThumbGeometry {
    const val MAX_WIDTH = 480

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

/** A decode lane's decoder: software ([FfmpegThumbDecoder]) or hardware ([HardwareThumbDecoder]). */
internal interface ThumbLaneDecoder : Closeable {
    /** The working set is allocated and already missing from MemAvailable, so the governor must not charge it again. */
    val warm: Boolean
    fun trim()
}

/**
 * Software keyframe decoder over the in-APK FFmpeg. One context, reused across a title's keyframes.
 * Not thread-safe: each decode lane owns one.
 */
internal class FfmpegThumbDecoder : ThumbLaneDecoder {
    private var handle = 0L
    private var handleTrack: VideoTrack? = null
    private var handleThreads = 0
    private val info = IntArray(ThumbNative.INFO_COUNT)
    private var decodedSinceTrim = false
    @Volatile private var directDecode = true

    override val warm: Boolean get() = handle != 0L && decodedSinceTrim

    /** [annexB]: one keyframe access unit, Annex-B for H.264/HEVC, the raw sample otherwise. */
    fun decode(annexB: ByteArray, track: VideoTrack, threads: Int): DecodedFrame? {
        if (!open(track, threads)) return null
        return frame(ThumbNative.nativeDecode(handle, annexB, annexB.size, info))
    }

    /** As above, from a buffer; a direct one reaches FFmpeg without a copy on the Java heap. */
    fun decode(au: ThumbAu, track: VideoTrack, threads: Int): DecodedFrame? {
        if (!open(track, threads)) return null
        val d = au.data
        val r = when {
            d.isDirect && directDecode -> try {
                ThumbNative.nativeDecodeDirect(handle, d, au.length, info)
            } catch (e: UnsatisfiedLinkError) {
                directDecode = false
                ThumbNative.nativeDecode(handle, au.toByteArray(), au.length, info)
            }
            d.hasArray() && d.arrayOffset() == 0 -> ThumbNative.nativeDecode(handle, d.array(), au.length, info)
            else -> ThumbNative.nativeDecode(handle, au.toByteArray(), au.length, info)
        }
        return frame(r)
    }

    private fun open(track: VideoTrack, threads: Int): Boolean {
        if (!ThumbNative.available) return false
        if (handle == 0L || handleTrack !== track || handleThreads != threads) {
            close()
            handle = ThumbNative.nativeOpen(
                track.codec.ffmpegName, track.extradata.takeIf { it.isNotEmpty() }, track.width, track.height, threads,
            )
            if (handle == 0L) {
                Log.w("ThumbDecode", "no ${track.codec.ffmpegName} decoder in this FFmpeg")
                return false
            }
            handleTrack = track
            handleThreads = threads
        }
        return true
    }

    private fun frame(r: Int): DecodedFrame? {
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
            strips = info[ThumbNative.INFO_STRIPS],
        )
    }

    /**
     * Renders the last decoded frame into a new full-colour bitmap (stored as WebP from full colour, so no banding is
     * saved). Every other source row is skipped only for 4K-sized sources.
     */
    fun render(frame: DecodedFrame, decision: ColourDecision, outW: Int, outH: Int): Bitmap? {
        if (handle == 0L) return null
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val rowSkip = frame.height >= 6 * outH
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

    override fun trim() {
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

/**
 * One decode lane and its decoders: always the software one, and a hardware one while the hardware path runs. Close
 * and trim asked for during a decode are done once it ends.
 */
internal class DecoderLane {
    val decoder = FfmpegThumbDecoder()
    @Volatile var hardware: HardwareThumbDecoder? = null
        private set
    /** When the running hardware decode began, 0 when none runs. */
    @Volatile var hardwareSince = 0L
    private var busy = false
    private var closeWhenFree = false
    private var trimWhenFree = false
    private var closeHardwareWhenFree = false

    /** The decoder a decode on this lane uses. */
    fun decoderFor(hardware: Boolean): ThumbLaneDecoder = if (hardware) this.hardware ?: NOT_OPEN else decoder

    /** The lane's hardware decoder for [codecName], opened on first use. Only during a decode on this lane. */
    fun hardwareFor(codecName: String, smallInput: Boolean = false): HardwareThumbDecoder = synchronized(this) {
        hardware?.takeIf { it.codecName == codecName && it.smallInput == smallInput } ?: run {
            hardware?.close()
            HardwareThumbDecoder(codecName, smallInput).also { hardware = it }
        }
    }

    /** A hardware decoder is open on this lane, or about to close as its decode ends. */
    val hardwareOpen: Boolean get() = hardware != null

    val inUse: Boolean get() = synchronized(this) { busy }

    /** A close asked for during a decode, done once it ends. */
    val closePending: Boolean get() = synchronized(this) { closeWhenFree }

    fun acquire() {
        synchronized(this) { busy = true }
    }

    fun release() {
        synchronized(this) {
            busy = false
            if (closeWhenFree) decoder.close() else if (trimWhenFree) decoder.trim()
            if (closeWhenFree || closeHardwareWhenFree) dropHardware()
            closeWhenFree = false
            trimWhenFree = false
            closeHardwareWhenFree = false
        }
    }

    fun close() {
        synchronized(this) {
            if (!busy) {
                decoder.close()
                dropHardware()
            }
            closeWhenFree = busy
        }
    }

    /** Closes the software decoder unless a decode runs on this lane; it opens again on its next use. True if it was warm. */
    fun closeSoftwareIfIdle(): Boolean = synchronized(this) {
        if (busy) return false
        val was = decoder.warm
        decoder.close()
        was
    }

    /** Gives the hardware decoder back (to the player), at once or when the running decode ends. Any thread. */
    fun closeHardware() {
        synchronized(this) {
            if (hardware == null) return
            if (!busy) dropHardware()
            closeHardwareWhenFree = busy
        }
    }

    private fun dropHardware() {
        hardware?.close()
        hardware = null
    }

    private companion object {
        val NOT_OPEN = object : ThumbLaneDecoder {
            override val warm = false
            override fun trim() = Unit
            override fun close() = Unit
        }
    }

    fun trim() {
        synchronized(this) {
            if (!busy) decoder.trim()
            trimWhenFree = busy
        }
    }
}
