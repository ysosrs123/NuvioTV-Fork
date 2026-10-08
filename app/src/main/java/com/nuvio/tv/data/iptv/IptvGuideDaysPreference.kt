package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.GuideDays

class IptvGuideDaysPreference(private val live: IptvLivePreferences) {
    var days: GuideDays
        get() = GuideDays.of(live.preferences.getInt(PAST_KEY, 1), live.preferences.getInt(FUTURE_KEY, 3))
        set(value) = live.preferences.edit().putInt(PAST_KEY, value.past).putInt(FUTURE_KEY, value.future).apply()

    private companion object {
        const val PAST_KEY = "settings-guide-past"
        const val FUTURE_KEY = "settings-guide-future"
    }
}
