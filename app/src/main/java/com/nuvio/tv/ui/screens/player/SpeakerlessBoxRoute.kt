package com.nuvio.tv.ui.screens.player

import android.os.Build
import com.nuvio.tv.ui.screens.player.iec.IecOutputRoutePolicy

/**
 * Fire TV boxes list a speaker output they cannot play on. Fire OS moves media there while HDMI sound is off
 * (TV off or on another input), and the audio clock there does not move, so the film holds its place until
 * HDMI sound returns and the route change rebuilds the audio.
 */
internal object SpeakerlessBoxRoute {
    private const val HDMI_DEVICE_TYPE_TV = "0"
    private const val HDMI_DEVICE_TYPE_PLAYBACK = "4"

    fun hdmiSoundOff(routeKey: String?): Boolean =
        hdmiSoundOff(Build.MANUFACTURER, TvArcAudioOutput.hdmiDeviceType(), routeKey)

    internal fun hdmiSoundOff(manufacturer: String?, hdmiDeviceType: String?, routeKey: String?): Boolean {
        if (!manufacturer.equals("Amazon", ignoreCase = true)) return false
        val types = hdmiDeviceType?.split(',')?.map { it.trim() } ?: return false
        if (HDMI_DEVICE_TYPE_TV in types || HDMI_DEVICE_TYPE_PLAYBACK !in types) return false
        return IecOutputRoutePolicy.routeType(routeKey) == "built_in_speaker"
    }
}
