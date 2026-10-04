package com.nuvio.tv.core.player.amlfel

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.io.File
import kotlin.math.roundToInt

/**
 * Capability checks for the native Dolby Vision Profile 7 FEL path ([AmlDvFelVideoRenderer]).
 *
 * The path needs: libaml_fel loaded, a writable /dev/amstream_dves_hevc (Amlogic dual-layer amstream
 * port) and the Amlogic Dolby Vision driver (/sys/class/amdolby_vision). Whether the SoC actually
 * reconstructs FEL (rather than dropping the EL) is a kernel property (stock Ugoos firmware forces
 * el_enable=0; the AM9 FEL kernel fixes it): [isFelKernel] recognises that kernel by its own module
 * parameters.
 */
internal object AmlFelSupport {
    private const val VFRAME_STATES = "/sys/class/video/vframe_states"

    /**
     * Parameters that exist only in the AM9 FEL kernel's aml_media.ko (0644, readable).
     * Stock Ugoos firmware has the same dual-layer port and Dolby Vision driver, but its Dolby module forces
     * el_enable=0: the EL is decoded and dropped, the picture is BL-only and nothing triggers a fallback. So the
     * native path must only run on the FEL kernel; elsewhere "Native FEL" behaves exactly like "Auto".
     */
    private val FEL_KERNEL_MARKERS = listOf(
        "/sys/module/aml_media/parameters/am9_dv_dual_layer_hold",
        "/sys/module/aml_media/parameters/am9_dv_fel_keep_el",
    )

    fun deviceBits(): Int = AmlFelNative.probeDevice()

    /** True when the dual-layer port and the Dolby Vision driver are present and usable. */
    fun isDeviceUsable(bits: Int = deviceBits()): Boolean =
        bits and AmlFelNative.DEVICE_DUAL_LAYER_PORT != 0 &&
            bits and AmlFelNative.DEVICE_AMVIDEO_CONTROL != 0 && bits and AmlFelNative.DEVICE_DOLBY_VISION != 0

    /** Enabled markers identify the supported custom-kernel contract; they are not visual FEL proof. */
    fun isFelKernel(): Boolean = FEL_KERNEL_MARKERS.all { readSysfs(it)?.lowercase() in setOf("1", "y", "yes", "true") }

    /** The native FEL path can run: dual-layer port + Dolby Vision driver + FEL-capable kernel. */
    fun isNativeFelUsable(bits: Int = deviceBits()): Boolean = isDeviceUsable(bits) && isFelKernel() && com.nuvio.tv.core.player.DoviBridge.isAvailable()

    /** Dolby Vision profile from the codec string (dvhe/dvh1/dvav/dva1.NN.xx), or null. */
    fun dvProfile(format: Format): Int? {
        val codecs = format.codecs?.trim()?.lowercase() ?: return null
        val match = Regex("(?:^|,)\\s*(?:dvhe|dvh1|dvav|dva1)\\.(\\d+)\\.").find(codecs) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /** Profile 7 HEVC Dolby Vision (the only profile with an enhancement layer that this path handles). */
    fun isProfile7(format: Format): Boolean {
        val mime = format.sampleMimeType
        if (mime != MimeTypes.VIDEO_DOLBY_VISION && mime != MimeTypes.VIDEO_H265) return false
        val codecs = format.codecs?.lowercase() ?: return false
        return dvProfile(format) == 7 && (codecs.contains("dvhe") || codecs.contains("dvh1"))
    }

    /** amstream "rate" = 96000 / fps (4004 for 23.976); 4004 when the frame rate is unknown. */
    fun rate96k(frameRate: Float): Int =
        if (frameRate > 1f && frameRate < 241f) (96000f / frameRate).roundToInt() else 4004

    /** Decoded frames waiting in the amvideo display queue ("vframe buf_avail_num=N"), or -1 if unreadable. */
    fun readVframeAvail(): Int =
        readSysfs(VFRAME_STATES)?.let { Regex("buf_avail_num=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1

    private fun readSysfs(path: String, max: Int = 4096): String? = runCatching {
        File(path).inputStream().use { input ->
            val bytes = ByteArray(max)
            val n = input.read(bytes)
            if (n <= 0) "" else String(bytes, 0, n).trim()
        }
    }.getOrNull()
}
