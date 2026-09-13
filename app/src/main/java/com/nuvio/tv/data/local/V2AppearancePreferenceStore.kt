package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.domain.model.AccentMode
import com.nuvio.tv.domain.model.FocusStyle
import com.nuvio.tv.domain.model.GlassTintMode
import com.nuvio.tv.domain.model.NavigationStyle
import com.nuvio.tv.domain.model.PlayerChromeStyle
import com.nuvio.tv.domain.model.SettingsPresentation
import com.nuvio.tv.domain.model.V2AppearancePreferences
import com.nuvio.tv.domain.model.VisualStyle
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

// Device-level appearance also acts as the pre-profile snapshot. No hardware values go here.
private val Context.v2AppearanceDataStore by preferencesDataStore(name = "appearance_v2")

object V2AppearancePreferenceStore {
    private val style = stringPreferencesKey("visual_style")
    private val navigation = stringPreferencesKey("navigation_style")
    private val focus = stringPreferencesKey("focus_style")
    private val accent = stringPreferencesKey("accent_mode")
    private val tint = stringPreferencesKey("glass_tint_mode")
    private val settings = stringPreferencesKey("settings_presentation")
    private val player = stringPreferencesKey("player_chrome_style")

    private fun decode(p: androidx.datastore.preferences.core.Preferences) = V2AppearancePreferences(
        visualStyle = VisualStyle.entries.firstOrNull { it.name == p[style] } ?: VisualStyle.CINEMATIC_GLASS,
        navigationStyle = NavigationStyle.entries.firstOrNull { it.name == p[navigation] } ?: NavigationStyle.FLOATING_SIDEBAR,
        focusStyle = FocusStyle.entries.firstOrNull { it.name == p[focus] } ?: FocusStyle.CINEMATIC_FOCUS,
        accentMode = AccentMode.entries.firstOrNull { it.name == p[accent] } ?: AccentMode.FIXED_THEME,
        glassTintMode = GlassTintMode.entries.firstOrNull { it.name == p[tint] } ?: GlassTintMode.ARTWORK,
        settingsPresentation = SettingsPresentation.entries.firstOrNull { it.name == p[settings] } ?: SettingsPresentation.MINIMAL,
        playerChromeStyle = PlayerChromeStyle.entries.firstOrNull { it.name == p[player] } ?: PlayerChromeStyle.CONTROL_DECK
    )

    fun flow(context: Context) = context.v2AppearanceDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::decode)

    suspend fun update(context: Context, transform: (V2AppearancePreferences) -> V2AppearancePreferences) {
        context.v2AppearanceDataStore.edit { p ->
            val next = transform(decode(p))
            p[style] = next.visualStyle.name
            p[navigation] = next.navigationStyle.name
            p[focus] = next.focusStyle.name
            p[accent] = next.accentMode.name
            p[tint] = next.glassTintMode.name
            p[settings] = next.settingsPresentation.name
            p[player] = next.playerChromeStyle.name
        }
    }
}
