package com.nuvio.tv.data.iptv

import android.content.Context
import com.nuvio.tv.core.iptv.LivePreferenceKeys

class IptvLivePreferences(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("iptv-live", Context.MODE_PRIVATE)

    fun key(ref: IptvSourceRef, name: String) = LivePreferenceKeys.source(ref.profileId, ref.sourceId, name)

    fun removeSource(ref: IptvSourceRef) = remove(LivePreferenceKeys.ofSource(preferences.all.keys, ref.profileId, ref.sourceId))

    fun removeProfile(profileId: Int) = remove(LivePreferenceKeys.ofProfile(preferences.all.keys, profileId))

    fun clearAllProfiles() = remove(LivePreferenceKeys.ofAllProfiles(preferences.all.keys))

    private fun remove(keys: List<String>) {
        if (keys.isEmpty()) return
        preferences.edit().apply { keys.forEach(::remove) }.commit()
    }
}
