// SPDX-License-Identifier: GPL-2.0-or-later
// Ported from Wholphin FEL (signde/Wholphin, util/FireTvFel.kt) for Nuvio Arc.
@file:OptIn(markerClass = [UnstableApi::class])

package com.nuvio.tv.core.player.firetvfel

import android.media.MediaCodec
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.ForwardingMediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import java.nio.ByteBuffer

/**
 * Fire TV Cube 3 (AFTGAZL) hardware Dolby Vision Profile 7 FEL.
 *
 * The Fire TV AV Magisk module (signde/firetv-passthrough) exposes `/dev/dvfel`. The base layer and
 * the original Profile 7 RPU go to the platform Dolby Vision MediaCodec as usual; libdvfelplay decodes
 * the enhancement layer itself and hands it to the module, which pairs it with the base layer by
 * presentation timestamp inside the Dolby Vision composer. MEL streams only get the EL NALs removed.
 *
 * Only the armeabi-v7a build carries the native libraries, and they are only loaded on the Cube 3.
 */
object FireTvFel {
    const val TAG = "NuvioFireTvFel"
    private const val CUBE_3_MODEL = "AFTGAZL"

    val isCube3: Boolean get() = Build.MODEL == CUBE_3_MODEL

    private val loaded: Boolean by lazy {
        isCube3 && runCatching {
            System.loadLibrary("nuviofiretvfel")
            true
        }.onFailure { Log.w(TAG, "native library not loaded: ${it.message}") }.getOrDefault(false)
    }

    /** Cube 3 + native library + an active Fire TV AV module with FEL enabled. */
    fun available(): Boolean = loaded && runCatching { nativeAvailable() }.getOrDefault(false)

    @Volatile private var availableCache: Boolean? = null

    /** The module can't appear while the app runs without a reboot, so settings ask once. */
    fun availableOnce(): Boolean = availableCache ?: available().also { availableCache = it }

    /** Original Profile 7 Dolby Vision (dvhe.07 / dvh1.07) as the Dolby Vision decoder sees it. */
    fun isProfile7(format: Format): Boolean {
        val codecs = format.codecs?.trim()?.lowercase() ?: return false
        return format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION &&
            (codecs.startsWith("dvhe.07") || codecs.startsWith("dvh1.07"))
    }

    /** True when [error] (or one of its causes) came from this FEL path. */
    fun isFallbackError(error: Throwable?): Boolean {
        var t = error
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is FireTvFelFallbackException) return true
            t = t.cause
        }
        return false
    }

    @JvmStatic external fun nativeAvailable(): Boolean

    @JvmStatic external fun nativeCreate(width: Int, height: Int): Long

    @JvmStatic external fun nativePrepare(handle: Long, data: ByteBuffer, offset: Int, size: Int): Int

    @JvmStatic external fun nativeCommit(handle: Long, pts: Long)

    @JvmStatic external fun nativeFlush(handle: Long)

    @JvmStatic external fun nativeClose(handle: Long)
}

/** Thrown when the FEL path cannot handle the stream; the player retries it on the normal path. */
class FireTvFelFallbackException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Wraps the video [MediaCodecAdapter.Factory]. Profile 7 tracks on the Dolby Vision decoder get a
 * [FireTvFelCodecAdapter]; everything else is passed through untouched.
 */
class FireTvFelCodecAdapterFactory(
    private val delegate: MediaCodecAdapter.Factory,
) : MediaCodecAdapter.Factory {
    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val format = configuration.format
        if (!FireTvFel.isProfile7(format) || format.drmInitData != null || !FireTvFel.available()) {
            return delegate.createAdapter(configuration)
        }
        if (configuration.codecInfo.mimeType != MimeTypes.VIDEO_DOLBY_VISION) {
            Log.w(FireTvFel.TAG, "P7 on non-Dolby Vision decoder ${configuration.codecInfo.name}; FEL not used")
            return delegate.createAdapter(configuration)
        }
        Log.i(FireTvFel.TAG, "native P7 decoder: ${configuration.codecInfo.name} ${format.width}x${format.height}")
        return FireTvFelCodecAdapter(
            delegate.createAdapter(configuration),
            NativeFelSession(format.width, format.height),
        )
    }
}

internal interface FelSession {
    fun prepare(data: ByteBuffer, offset: Int, size: Int): Int

    fun commit(pts: Long)

    fun flush()

    fun close()
}

private class NativeFelSession(width: Int, height: Int) : FelSession {
    private var handle = FireTvFel.nativeCreate(width, height)

    override fun prepare(data: ByteBuffer, offset: Int, size: Int): Int {
        check(handle != 0L)
        return try {
            FireTvFel.nativePrepare(handle, data, offset, size)
        } catch (e: IllegalStateException) {
            throw FireTvFelFallbackException(e.message ?: "FEL input failed", e)
        }
    }

    override fun commit(pts: Long) = FireTvFel.nativeCommit(handle, pts)

    override fun flush() = FireTvFel.nativeFlush(handle)

    override fun close() {
        if (handle != 0L) FireTvFel.nativeClose(handle)
        handle = 0
    }
}

internal class FireTvFelCodecAdapter(
    delegate: MediaCodecAdapter,
    private val fel: FelSession,
) : ForwardingMediaCodecAdapter(delegate) {
    @Synchronized
    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        val sample = size > 0 && flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
        val outputSize = if (sample) fel.prepare(checkNotNull(getInputBuffer(index)), offset, size) else size
        try {
            super.queueInputBuffer(index, offset, outputSize, presentationTimeUs, flags)
            // BL/EL pairing must use the exact timestamp submitted to MediaCodec.
            if (sample) fel.commit(presentationTimeUs)
        } catch (failure: Exception) {
            fel.flush()
            throw failure
        }
    }

    @Synchronized
    override fun flush() {
        super.flush()
        fel.flush()
    }

    @Synchronized
    override fun release() {
        try {
            super.release()
        } finally {
            fel.close()
        }
    }
}
