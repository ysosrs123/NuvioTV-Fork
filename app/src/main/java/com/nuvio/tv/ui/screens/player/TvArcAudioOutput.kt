package com.nuvio.tv.ui.screens.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Arc running on a TV panel whose sound goes out of the TV's own HDMI ARC / eARC port.
 * Boxes play out of a plain HDMI port and never match.
 */
internal object TvArcAudioOutput {
    private const val HDMI_DEVICE_TYPE_TV = "0"

    private val ARC_TYPES = setOf(AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC)

    fun isActive(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val outputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        }.getOrDefault(emptyList())
        return matches(hdmiDeviceType(), routedMediaType(audioManager), outputs)
    }

    /**
     * [hdmiDeviceType] is ro.hdmi.device_type ("0" = TV, "4" = playback box), null when unreadable.
     * [routedType] is the device media plays on now (API 33+), null when unknown.
     */
    @SuppressLint("InlinedApi")
    internal fun matches(hdmiDeviceType: String?, routedType: Int?, outputTypes: Collection<Int>): Boolean {
        val hasHdmiPort = AudioDeviceInfo.TYPE_HDMI in outputTypes
        val tvPanel = hdmiDeviceType
            ?.split(',')
            ?.map { it.trim() }
            ?.let { HDMI_DEVICE_TYPE_TV in it }
            ?: (AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in outputTypes && !hasHdmiPort)
        if (!tvPanel) return false
        if (routedType != null) return routedType in ARC_TYPES
        return !hasHdmiPort && outputTypes.any { it in ARC_TYPES }
    }

    @SuppressLint("NewApi")
    private fun routedMediaType(audioManager: AudioManager): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return runCatching {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            audioManager.getAudioDevicesForAttributes(attrs).firstOrNull()?.type
        }.getOrNull()
    }

    @SuppressLint("PrivateApi")
    internal fun hdmiDeviceType(): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, "ro.hdmi.device_type") as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
