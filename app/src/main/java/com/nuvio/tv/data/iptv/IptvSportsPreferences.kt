package com.nuvio.tv.data.iptv

import android.content.Context
import com.nuvio.tv.core.iptv.SportsAlertGames
import com.nuvio.tv.core.iptv.SportsChangeKind
import com.nuvio.tv.core.iptv.SportsChannelPicks
import com.nuvio.tv.core.iptv.SportsChannelRules
import com.nuvio.tv.core.iptv.SportsChannelSource
import com.nuvio.tv.core.iptv.SportsDbLeagues
import com.nuvio.tv.core.iptv.SportsFavourites
import com.nuvio.tv.core.iptv.SportsLeague
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsLogos
import com.nuvio.tv.core.iptv.SportsNuvioAlert
import com.nuvio.tv.core.iptv.SportsOverlayStyle
import com.nuvio.tv.core.iptv.SportsPickList
import com.nuvio.tv.core.iptv.SportsReminder
import com.nuvio.tv.core.iptv.SportsReminders
import com.nuvio.tv.core.iptv.SportsSources
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class IptvSportsPreferences(context: Context, private val box: () -> IptvSecretBox = { EnvelopeIptvSecretBox(AndroidIptvSecretBox()) }) {
    private val preferences = context.applicationContext.getSharedPreferences("iptv-live", Context.MODE_PRIVATE)
    @Volatile private var cachedKey: Pair<String, String>? = null

    private val rulesState = MutableStateFlow(readRules())
    val channelRulesFlow: StateFlow<SportsChannelRules> = rulesState.asStateFlow()

    init { SportsLeagues.custom = customLeagues }

    var channelRules: SportsChannelRules
        get() = rulesState.value
        @Synchronized set(value) {
            val edit = preferences.edit()
            SportsPickList.entries.forEach { which ->
                edit.putStringSet(RULES_KEY + which.name.lowercase(), SportsChannelPicks.clean(value.list(which)).map(SportsChannelPicks::encode).toSet())
            }
            edit.apply()
            rulesState.value = readRules()
        }

    private fun readRules(): SportsChannelRules {
        fun read(which: SportsPickList) = SportsChannelPicks.clean(preferences.getStringSet(RULES_KEY + which.name.lowercase(), null).orEmpty()
            .take(SportsChannelPicks.MAX * 2).mapNotNull(SportsChannelPicks::decode)).sortedBy { it.label.lowercase() }
        return SportsChannelRules(read(SportsPickList.ALWAYS), read(SportsPickList.NEVER), read(SportsPickList.PREFERRED), read(SportsPickList.EXCLUDED))
    }

    var enabled: Boolean
        get() = SportsSources.enabled(if (preferences.contains(ENABLED_KEY)) preferences.getBoolean(ENABLED_KEY, false) else null,
            preferences.getString(SERVICE_KEY, null))
        set(value) = preferences.edit().putBoolean(ENABLED_KEY, value).apply()

    var customLeagues: List<SportsLeague>
        @Synchronized get() = preferences.getStringSet(CUSTOM_KEY, null).orEmpty().take(SportsDbLeagues.MAX_CUSTOM * 2).mapNotNull(SportsDbLeagues::decodeCustom)
            .distinctBy { it.id }.sortedBy { it.name.lowercase() }.take(SportsDbLeagues.MAX_CUSTOM)
        @Synchronized set(value) {
            val kept = value.filter { it.custom }.distinctBy { it.id }.take(SportsDbLeagues.MAX_CUSTOM)
            preferences.edit().putStringSet(CUSTOM_KEY, kept.map(SportsDbLeagues::encodeCustom).toSet()).apply()
            SportsLeagues.custom = kept
        }

    var logos: SportsLogos
        get() = enumOf(LOGOS_KEY, SportsLogos.ESPN)
        set(value) = preferences.edit().putString(LOGOS_KEY, value.name).apply()

    var leagues: Set<String>
        get() = preferences.getStringSet(LEAGUES_KEY, null)?.filter { SportsLeagues.byId(it) != null }?.toSet() ?: SportsLeagues.DEFAULTS
        set(value) = preferences.edit().putStringSet(LEAGUES_KEY, value.filter { SportsLeagues.byId(it) != null }.toSet()).apply()

    var showScores: Boolean
        get() = preferences.getBoolean(SCORES_KEY, true)
        set(value) = preferences.edit().putBoolean(SCORES_KEY, value).apply()

    var favouriteTeams: Set<String>
        get() = preferences.getStringSet(FAVOURITES_KEY, null)?.filter { SportsFavourites.parse(it) != null }?.toSet().orEmpty()
        set(value) = preferences.edit().putStringSet(FAVOURITES_KEY, value.filter { SportsFavourites.parse(it) != null && it.length <= 200 }
            .take(SportsFavourites.MAX).toSet()).apply()

    var channelSource: SportsChannelSource
        get() = enumOf(CHANNELS_KEY, SportsChannelSource.BOTH)
        set(value) = preferences.edit().putString(CHANNELS_KEY, value.name).apply()

    var overlayStyle: SportsOverlayStyle
        get() = enumOf(OVERLAY_KEY, SportsOverlayStyle.GLANCE)
        set(value) = preferences.edit().putString(OVERLAY_KEY, value.name).apply()

    var alertHoldSeconds: Int
        get() = preferences.getInt(HOLD_KEY, DEFAULT_HOLD).takeIf { it in HOLD_CHOICES } ?: DEFAULT_HOLD
        set(value) = preferences.edit().putInt(HOLD_KEY, value.takeIf { it in HOLD_CHOICES } ?: DEFAULT_HOLD).apply()

    var alertGames: SportsAlertGames
        get() = enumOf(GAMES_KEY, SportsAlertGames.FOLLOWED_AND_CLOSE)
        set(value) = preferences.edit().putString(GAMES_KEY, value.name).apply()

    var alertKinds: Set<SportsChangeKind>
        get() = preferences.getStringSet(KINDS_KEY, null)?.mapNotNull { name -> SportsChangeKind.entries.firstOrNull { it.name == name } }?.toSet()
            ?: SportsChangeKind.entries.toSet()
        set(value) = preferences.edit().putStringSet(KINDS_KEY, value.map { it.name }.toSet()).apply()

    var skipOnScreen: Boolean
        get() = preferences.getBoolean(SKIP_KEY, true)
        set(value) = preferences.edit().putBoolean(SKIP_KEY, value).apply()

    var nuvioAlert: SportsNuvioAlert
        get() = enumOf(NUVIO_KEY, SportsNuvioAlert.POPUP)
        set(value) = preferences.edit().putString(NUVIO_KEY, value.name).apply()

    var nuvioQuietEndMinutes: Int
        get() = preferences.getInt(QUIET_KEY, DEFAULT_QUIET).takeIf { it in QUIET_CHOICES } ?: DEFAULT_QUIET
        set(value) = preferences.edit().putInt(QUIET_KEY, value.takeIf { it in QUIET_CHOICES } ?: DEFAULT_QUIET).apply()

    var reminderLeadMinutes: Int
        get() = preferences.getInt(LEAD_KEY, DEFAULT_LEAD).takeIf { it in LEAD_CHOICES } ?: DEFAULT_LEAD
        set(value) = preferences.edit().putInt(LEAD_KEY, value.takeIf { it in LEAD_CHOICES } ?: DEFAULT_LEAD).apply()

    var hideSpoilers: Boolean
        get() = preferences.getBoolean(SPOILERS_KEY, true)
        set(value) = preferences.edit().putBoolean(SPOILERS_KEY, value).apply()

    var reminders: List<SportsReminder>
        @Synchronized get() = preferences.getStringSet(REMINDERS_KEY, null).orEmpty().take(SportsReminders.MAX * 2).mapNotNull(SportsReminders::decode)
            .distinctBy { it.key }.sortedBy { it.startMillis }
        @Synchronized set(value) = preferences.edit().putStringSet(REMINDERS_KEY, value.distinctBy { it.key }.take(SportsReminders.MAX)
            .map(SportsReminders::encode).toSet()).apply()

    private inline fun <reified T : Enum<T>> enumOf(key: String, default: T): T =
        preferences.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

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
        private const val ENABLED_KEY = "settings-sports-enabled"
        private const val CUSTOM_KEY = "settings-sports-custom-leagues"
        private const val LOGOS_KEY = "settings-sports-logos"
        private const val LEAGUES_KEY = "settings-sports-leagues"
        private const val KEY_KEY = "settings-sports-key"
        private const val SCORES_KEY = "settings-sports-scores"
        private const val FAVOURITES_KEY = "settings-sports-favourite-teams"
        const val DEFAULT_HOLD = 45
        const val DEFAULT_QUIET = 10
        const val DEFAULT_LEAD = 5
        val HOLD_CHOICES = listOf(0, 15, 30, 45, 60, 90)
        val QUIET_CHOICES = listOf(0, 5, 10, 15, 20, 30)
        val LEAD_CHOICES = listOf(0, 2, 5, 10, 15, 30)
        private const val OVERLAY_KEY = "settings-sports-overlay"
        private const val CHANNELS_KEY = "settings-sports-channel-source"
        private const val HOLD_KEY = "settings-sports-alert-hold"
        private const val GAMES_KEY = "settings-sports-alert-games"
        private const val KINDS_KEY = "settings-sports-alert-kinds"
        private const val SKIP_KEY = "settings-sports-skip-on-screen"
        private const val NUVIO_KEY = "settings-sports-nuvio-alert"
        private const val QUIET_KEY = "settings-sports-nuvio-quiet-end"
        private const val LEAD_KEY = "settings-sports-reminder-lead"
        private const val SPOILERS_KEY = "settings-sports-hide-spoilers"
        private const val REMINDERS_KEY = "settings-sports-reminders"
        private const val RULES_KEY = "settings-sports-channels-"
        private const val KEY_CONTEXT = "iptv.sports.v1:thesportsdb-key"
    }
}
