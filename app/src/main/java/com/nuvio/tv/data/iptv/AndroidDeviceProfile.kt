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
import com.nuvio.tv.core.iptv.multiviewDecodeBudget
import com.nuvio.tv.core.iptv.multiviewDecodeCapacity
import com.nuvio.tv.core.iptv.multiviewMegapixels

object AndroidDeviceProfile {
    fun read(context: Context): IptvDeviceProfile {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val avc = hardwareDecoders(MediaFormat.MIMETYPE_VIDEO_AVC)
        val hevc = hardwareDecoders(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val instances = avc.mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).maxSupportedInstances }.getOrNull() }.maxOrNull()
        return iptvDeviceProfile(memory.totalMem, manager.isLowRamDevice, instances,
            decodeBudget(avc, MediaFormat.MIMETYPE_VIDEO_AVC), decodeBudget(hevc, MediaFormat.MIMETYPE_VIDEO_HEVC)).also {
            IptvLog.info("multiview device tiles=${it.maxTiles} avc=${multiviewMegapixels(it.decodeBudget)} hevc=${multiviewMegapixels(it.hevcBudget)} instances=$instances " +
                "decoders=${(avc + hevc).distinct().joinToString(",") { codec -> codec.name }}")
        }
    }

    fun panelHeight(context: Context): Int = runCatching {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        display.mode.physicalHeight
    }.getOrNull()?.takeIf { it >= 480 } ?: 1080

    private fun hardwareDecoders(mime: String): List<MediaCodecInfo> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && mime in it.supportedTypes.map(String::lowercase) }
            .filterNot { it.name.lowercase().let { name -> name.startsWith("omx.google") || name.startsWith("c2.android") } }
    }.getOrDefault(emptyList())

    private fun decodeBudget(decoders: List<MediaCodecInfo>, mime: String): Long? = decoders.mapNotNull { codec ->
        runCatching {
            val capabilities = codec.getCapabilitiesForType(mime)
            val video = capabilities.videoCapabilities ?: return@mapNotNull null
            val points = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) video.supportedPerformancePoints.orEmpty() else emptyList()
            val guaranteed = if (points.isEmpty()) null
                else multiviewDecodeBudget { w, h, f -> points.any { it.covers(MediaCodecInfo.VideoCapabilities.PerformancePoint(w, h, f)) } }
            val claimed = multiviewDecodeBudget { w, h, f -> f <= 60 && video.areSizeAndRateSupported(w, h, f.toDouble()) }
            multiviewDecodeCapacity(guaranteed, claimed, capabilities.maxSupportedInstances)
        }.getOrNull()
    }.maxOrNull()
}
