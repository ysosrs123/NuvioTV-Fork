package com.nuvio.tv.data.iptv

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import com.nuvio.tv.core.iptv.IptvDeviceProfile
import com.nuvio.tv.core.iptv.iptvDeviceProfile

object AndroidDeviceProfile {
    fun read(context: Context): IptvDeviceProfile {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val decoders = hardwareDecoders()
        return iptvDeviceProfile(memory.totalMem, manager.isLowRamDevice,
            decoders.maxOfOrNull { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).maxSupportedInstances },
            decodeBudget(decoders))
    }

    fun panelHeight(context: Context): Int = runCatching {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        display.mode.physicalHeight
    }.getOrNull()?.takeIf { it >= 480 } ?: 1080

    private fun hardwareDecoders(): List<MediaCodecInfo> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes.map(String::lowercase) }
            .filterNot { it.name.lowercase().let { name -> name.startsWith("omx.google") || name.startsWith("c2.android") } }
    }.getOrDefault(emptyList())

    private fun decodeBudget(decoders: List<MediaCodecInfo>): Long? = runCatching {
        val rates = intArrayOf(240, 200, 120, 100, 60, 50, 30)
        decoders.mapNotNull { codec ->
            val video = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities ?: return@mapNotNull null
            val points = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) video.supportedPerformancePoints.orEmpty() else emptyList()
            val fps = if (points.isNotEmpty()) rates.firstOrNull { rate ->
                points.any { it.covers(MediaCodecInfo.VideoCapabilities.PerformancePoint(1920, 1080, rate)) }
            } else rates.firstOrNull { rate -> rate <= 60 && video.areSizeAndRateSupported(1920, 1080, rate.toDouble()) }
            fps?.let { 1920L * 1080 * it }
        }.maxOrNull()
    }.getOrNull()
}
