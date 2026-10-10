package com.nuvio.tv.data.iptv

import android.content.Context
import com.nuvio.tv.core.iptv.LiveAudioDecoder
import com.nuvio.tv.core.iptv.LiveAudioOptions
import com.nuvio.tv.core.iptv.LiveCushion
import com.nuvio.tv.core.iptv.LiveFrameRateChoice
import com.nuvio.tv.core.iptv.LivePassthroughChoice
import com.nuvio.tv.core.iptv.LiveResolutionChoice
import com.nuvio.tv.core.iptv.LiveStartBuffer

class IptvStreamingPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("iptv-live", Context.MODE_PRIVATE)

    var start: LiveStartBuffer
        get() = preferences.getString(START_KEY, null)?.let { name -> LiveStartBuffer.entries.firstOrNull { it.name == name } } ?: LiveStartBuffer.NORMAL
        set(value) = preferences.edit().putString(START_KEY, value.name).apply()
    var cushion: LiveCushion
        get() = preferences.getString(CUSHION_KEY, null)?.let { name -> LiveCushion.entries.firstOrNull { it.name == name } } ?: LiveCushion.SECONDS_20
        set(value) = preferences.edit().putString(CUSHION_KEY, value.name).apply()
    var cornerPicture: Boolean
        get() = preferences.getBoolean(CORNER_KEY, true)
        set(value) = preferences.edit().putBoolean(CORNER_KEY, value).apply()
    var surroundLift: Boolean
        get() = preferences.getBoolean(SURROUND_KEY, true)
        set(value) = preferences.edit().putBoolean(SURROUND_KEY, value).apply()
    var frameRate: LiveFrameRateChoice
        get() = choice(FRAME_RATE_KEY, LiveFrameRateChoice.NUVIO)
        set(value) = preferences.edit().putString(FRAME_RATE_KEY, value.name).apply()
    var resolution: LiveResolutionChoice
        get() = choice(RESOLUTION_KEY, LiveResolutionChoice.NUVIO)
        set(value) = preferences.edit().putString(RESOLUTION_KEY, value.name).apply()
    var passthrough: LivePassthroughChoice
        get() = choice(PASSTHROUGH_KEY, LivePassthroughChoice.NUVIO)
        set(value) = preferences.edit().putString(PASSTHROUGH_KEY, value.name).apply()
    var tunnelling: Boolean
        get() = preferences.getBoolean(TUNNEL_KEY, false)
        set(value) = preferences.edit().putBoolean(TUNNEL_KEY, value).apply()
    var preferSurround: Boolean
        get() = preferences.getBoolean(PREFER_SURROUND_KEY, false)
        set(value) = preferences.edit().putBoolean(PREFER_SURROUND_KEY, value).apply()
    var audioLanguage: String
        get() = preferences.getString(LANGUAGE_KEY, null)?.takeIf { it.isNotBlank() } ?: LiveAudioOptions.LANGUAGE_DEFAULT
        set(value) = preferences.edit().putString(LANGUAGE_KEY, value).apply()
    var audioDecoder: LiveAudioDecoder
        get() = choice(DECODER_KEY, LiveAudioDecoder.AUTOMATIC)
        set(value) = preferences.edit().putString(DECODER_KEY, value.name).apply()

    private inline fun <reified T : Enum<T>> choice(key: String, fallback: T): T =
        preferences.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: fallback

    companion object {
        private const val START_KEY = "settings-stream-start"
        private const val CUSHION_KEY = "settings-stream-cushion"
        private const val CORNER_KEY = "settings-stream-corner-picture"
        private const val SURROUND_KEY = "settings-stream-surround-lift"
        private const val FRAME_RATE_KEY = "settings-play-frame-rate"
        private const val RESOLUTION_KEY = "settings-play-resolution"
        private const val PASSTHROUGH_KEY = "settings-play-passthrough"
        private const val TUNNEL_KEY = "settings-play-tunnelling"
        private const val PREFER_SURROUND_KEY = "settings-play-prefer-surround"
        private const val LANGUAGE_KEY = "settings-play-audio-language"
        private const val DECODER_KEY = "settings-play-audio-decoder"
    }
}
