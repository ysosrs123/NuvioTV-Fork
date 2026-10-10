package com.nuvio.tv.data.iptv

import android.content.Context

class IptvPhoneAccessStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("iptv-phone-access", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(ENABLED_KEY, false)
        set(value) = preferences.edit().putBoolean(ENABLED_KEY, value).apply()

    var phones: String?
        get() = preferences.getString(PHONES_KEY, null)
        set(value) = preferences.edit().putString(PHONES_KEY, value).apply()

    var port: Int?
        get() = preferences.getInt(PORT_KEY, 0).takeIf { it > 0 }
        set(value) = preferences.edit().putInt(PORT_KEY, value ?: 0).apply()

    override fun toString() = "IptvPhoneAccessStore(withheld)"

    private companion object {
        const val ENABLED_KEY = "enabled"
        const val PHONES_KEY = "phones"
        const val PORT_KEY = "port"
    }
}
