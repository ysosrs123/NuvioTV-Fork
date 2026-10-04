package com.nuvio.tv.core.player.amlfel

import android.util.Log
import android.view.Surface
import com.nuvio.tv.BuildConfig
import java.nio.ByteBuffer

/**
 * JNI facade for libaml_fel.so (app/src/main/cpp/aml_fel_native.cpp): the Amlogic legacy amstream
 * dual-layer port /dev/amstream_dves_hevc, used to present Dolby Vision Profile 7 FEL with the
 * enhancement layer actually reconstructed by the SoC's Dolby core (BL decoder + EL decoder + VD1/VD2).
 *
 * All calls are cheap and non-blocking except [open]/[close] (decoder bring-up/teardown, tens of ms).
 * Negative return values are -errno.
 */
internal object AmlFelNative {
    private const val TAG = "AmlFel"
    const val DEVICE_DUAL_LAYER_PORT = 1
    const val DEVICE_AMVIDEO_CONTROL = 2
    const val DEVICE_DOLBY_VISION = 4
    /** AM_VIDEO_DEFAULT: the main video layer (VD1). */
    const val SIDEBAND_CHANNEL_DEFAULT = 1
    /** AM_OMX_SIDEBAND: the droidlogic HWC shows the amvideo plane (VD1) in the layer's rect. */
    const val SIDEBAND_TYPE_OMX = 2

    val isLoaded: Boolean = if (!BuildConfig.AML_FEL_NATIVE_ENABLED) {
        false
    } else {
        runCatching { System.loadLibrary("aml_fel") }
            .onFailure { Log.w(TAG, "libaml_fel not loaded: ${it.message}") }
            .isSuccess
    }

    fun probeDevice(): Int = if (isLoaded) runCatching { nativeProbeDevice() }.getOrDefault(0) else 0

    fun open(width: Int, height: Int, rate96k: Int, metaWithEl: Int = -1): Long =
        nativeOpen(width, height, rate96k, metaWithEl)

    fun queueSample(handle: Long, data: ByteBuffer, offset: Int, length: Int, ptsUs: Long): Int =
        nativeQueueSample(handle, data, offset, length, ptsUs)

    fun drain(handle: Long): Int = nativeDrain(handle)
    fun setPcrscr(handle: Long, pts90k: Int): Int = nativeSetPcrscr(handle, pts90k)
    fun getVpts(handle: Long): Long = nativeGetVpts(handle)
    fun getBufferStatus(handle: Long, out: IntArray): Int = nativeGetBufferStatus(handle, out)
    fun setPaused(handle: Long, paused: Boolean): Int = nativeSetPaused(handle, paused)
    fun close(handle: Long) = nativeClose(handle)

    /** Attaches an Amlogic sideband stream to [surface]; handle > 0 or -errno. See aml_fel_native.cpp. */
    fun attachSideband(surface: Surface, type: Int = SIDEBAND_TYPE_OMX, channel: Int = SIDEBAND_CHANNEL_DEFAULT): Long =
        nativeAttachSideband(surface, type, channel)

    fun detachSideband(handle: Long) = nativeDetachSideband(handle)

    @JvmStatic private external fun nativeProbeDevice(): Int
    @JvmStatic private external fun nativeOpen(width: Int, height: Int, rate: Int, metaWithEl: Int): Long
    @JvmStatic private external fun nativeQueueSample(handle: Long, buffer: ByteBuffer, offset: Int, length: Int, ptsUs: Long): Int
    @JvmStatic private external fun nativeDrain(handle: Long): Int
    @JvmStatic private external fun nativeSetPcrscr(handle: Long, pts90k: Int): Int
    @JvmStatic private external fun nativeGetVpts(handle: Long): Long
    @JvmStatic private external fun nativeGetBufferStatus(handle: Long, out: IntArray): Int
    @JvmStatic private external fun nativeSetPaused(handle: Long, paused: Boolean): Int
    @JvmStatic private external fun nativeClose(handle: Long)
    @JvmStatic private external fun nativeAttachSideband(surface: Surface, type: Int, channel: Int): Long
    @JvmStatic private external fun nativeDetachSideband(handle: Long)
}
