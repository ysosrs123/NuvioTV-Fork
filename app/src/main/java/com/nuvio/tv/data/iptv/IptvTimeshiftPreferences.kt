package com.nuvio.tv.data.iptv

import android.content.Context
import com.nuvio.tv.core.iptv.LocalTimeshiftLength
import com.nuvio.tv.core.iptv.RecordingLocations
import java.io.File

class IptvTimeshiftPreferences(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("iptv-live", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(ENABLED_KEY, false)
        set(value) = preferences.edit().putBoolean(ENABLED_KEY, value).apply()
    var length: LocalTimeshiftLength
        get() = preferences.getString(LENGTH_KEY, null)?.let { name -> LocalTimeshiftLength.entries.firstOrNull { it.name == name } }
            ?: LocalTimeshiftLength.AUTOMATIC
        set(value) = preferences.edit().putString(LENGTH_KEY, value.name).apply()
    var location: String
        get() = preferences.getString(LOCATION_KEY, null)?.takeIf { it == RecordingLocations.INTERNAL || RecordingLocations.volumeId(it) != null }
            ?: RecordingLocations.INTERNAL
        set(value) = preferences.edit().putString(LOCATION_KEY, value).apply()

    fun internalDirectory(): File = File(app.noBackupFilesDir, "iptv/$DIRECTORY")

    companion object {
        const val DIRECTORY = "timeshift"
        private const val ENABLED_KEY = "settings-local-timeshift-enabled"
        private const val LENGTH_KEY = "settings-local-timeshift-length"
        private const val LOCATION_KEY = "settings-local-timeshift-location"
    }
}
