package com.nuvio.tv.core.player.amlfel

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import java.io.File

/**
 * "Dolby Vision output" on the AM9 FEL kernel: with am9_dv_out_hdr10 set, the kernel renders a Dolby Vision title
 * to HDR10 on the box instead of sending Dolby Vision to the TV. The kernel reads it when a Dolby Vision title starts,
 * and the parameter resets at every boot, so Arc writes the saved choice before each playback.
 */
internal object AmlDvOutput {
    private const val PARAM = "/sys/module/aml_media/parameters/am9_dv_out_hdr10"

    @Volatile private var availableCache: Boolean? = null

    /** The kernel has the switch and Arc may write it. It can't change while the app runs, so it is asked once. */
    fun isAvailable(): Boolean = availableCache ?: probe(File(PARAM)).also { availableCache = it }

    fun availableCached(): Boolean? = availableCache

    internal fun probe(param: File): Boolean = AmlFelSupport.isFelKernel() && param.exists() && param.canWrite()

    fun apply(boxHdr10: Boolean) {
        if (!isAvailable()) return
        runCatching { File(PARAM).writeText(paramValue(boxHdr10)) }
    }

    internal fun paramValue(boxHdr10: Boolean): String = if (boxHdr10) "Y" else "N"

    /** The TV reports Dolby Vision right now. A TV without it already gets box-rendered HDR10 from the kernel. */
    fun tvTakesDolbyVision(context: Context): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.hdrCapabilities
            ?.supportedHdrTypes
            ?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true
    }.getOrDefault(false)

    /** Settings shows the row only where it does something. */
    internal fun rowShown(available: Boolean?, tvTakesDv: Boolean?): Boolean = available == true && tvTakesDv == true
}
