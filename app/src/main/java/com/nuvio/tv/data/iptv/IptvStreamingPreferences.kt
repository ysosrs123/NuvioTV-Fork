package com.nuvio.tv.data.iptv

import android.content.Context
import com.nuvio.tv.core.iptv.LiveCushion
import com.nuvio.tv.core.iptv.LiveStartBuffer

class IptvStreamingPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("iptv-live", Context.MODE_PRIVATE)

    var start: LiveStartBuffer
        get() = preferences.getString(START_KEY, null)?.let { name -> LiveStartBuffer.entries.firstOrNull { it.name == name } } ?: LiveStartBuffer.NORMAL
        set(value) = preferences.edit().putString(START_KEY, value.name).apply()
    var cushion: LiveCushion
        get() = preferences.getString(CUSHION_KEY, null)?.let { name -> LiveCushion.entries.firstOrNull { it.name == name } } ?: LiveCushion.SECONDS_20
        set(value) = preferences.edit().putString(CUSHION_KEY, value.name).apply()

    companion object {
        private const val START_KEY = "settings-stream-start"
        private const val CUSHION_KEY = "settings-stream-cushion"
    }
}
