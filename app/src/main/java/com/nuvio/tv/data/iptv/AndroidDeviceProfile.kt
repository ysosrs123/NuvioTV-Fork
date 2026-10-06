package com.nuvio.tv.data.iptv

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import com.nuvio.tv.core.iptv.IptvDeviceProfile
import com.nuvio.tv.core.iptv.iptvDeviceProfile

object AndroidDeviceProfile {
    fun read(context: Context): IptvDeviceProfile {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return iptvDeviceProfile(memory.totalMem, manager.isLowRamDevice, decoderInstances())
    }

    private fun decoderInstances(): Int? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes.map(String::lowercase) }
            .filterNot { it.name.lowercase().let { name -> name.startsWith("omx.google") || name.startsWith("c2.android") } }
            .maxOfOrNull { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).maxSupportedInstances }
    }.getOrNull()
}
