package com.nuvio.tv.data.iptv

import android.content.Context
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsService
import java.util.Base64

class IptvSportsPreferences(context: Context, private val box: () -> IptvSecretBox = { EnvelopeIptvSecretBox(AndroidIptvSecretBox()) }) {
    private val preferences = context.applicationContext.getSharedPreferences("iptv-live", Context.MODE_PRIVATE)
    @Volatile private var cachedKey: Pair<String, String>? = null

    var service: SportsService
        get() = preferences.getString(SERVICE_KEY, null)?.let { name -> SportsService.entries.firstOrNull { it.name == name } } ?: SportsService.OFF
        set(value) = preferences.edit().putString(SERVICE_KEY, value.name).apply()

    var leagues: Set<String>
        get() = preferences.getStringSet(LEAGUES_KEY, null)?.filter { SportsLeagues.byId(it) != null }?.toSet() ?: SportsLeagues.DEFAULTS
        set(value) = preferences.edit().putStringSet(LEAGUES_KEY, value.filter { SportsLeagues.byId(it) != null }.toSet()).apply()

    val hasKey: Boolean get() = preferences.contains(KEY_KEY)

    @Synchronized fun key(): String? {
        val sealed = preferences.getString(KEY_KEY, null) ?: return null
        cachedKey?.takeIf { it.first == sealed }?.let { return it.second }
        return try {
            box().open(KEY_CONTEXT, Base64.getDecoder().decode(sealed)).also { cachedKey = sealed to it }
        } catch (error: Exception) {
            IptvLog.failure("sports key read", error)
            null
        }
    }

    @Synchronized fun setKey(value: String?) {
        val key = value?.trim()?.takeIf { it.isNotEmpty() }
        cachedKey = null
        if (key == null) { preferences.edit().remove(KEY_KEY).apply(); return }
        require(key.length <= MAX_KEY && key.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        preferences.edit().putString(KEY_KEY, Base64.getEncoder().encodeToString(box().seal(KEY_CONTEXT, key))).apply()
    }

    companion object {
        const val MAX_KEY = 64
        fun validKey(value: String): Boolean = value.trim().let { it.isNotEmpty() && it.length <= MAX_KEY && it.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' } }
        private const val SERVICE_KEY = "settings-sports-service"
        private const val LEAGUES_KEY = "settings-sports-leagues"
        private const val KEY_KEY = "settings-sports-key"
        private const val KEY_CONTEXT = "iptv.sports.v1:thesportsdb-key"
    }
}
