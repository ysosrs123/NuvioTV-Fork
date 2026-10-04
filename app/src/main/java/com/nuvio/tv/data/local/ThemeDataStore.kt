package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.domain.model.ThemeSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "theme_settings"
        const val DEFAULT_SCREENSAVER_TIMEOUT_MINUTES = 5
        const val DEFAULT_SCREENSAVER_DIM_PERCENT = 70
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val themeKey = stringPreferencesKey("selected_theme")
    private val customThemeColorsKey = stringPreferencesKey("custom_theme_colors")
    private val fontKey = stringPreferencesKey("selected_font")
    private val amoledModeKey = booleanPreferencesKey("amoled_mode")
    private val amoledSurfacesModeKey = booleanPreferencesKey("amoled_surfaces_mode")
    private val settingsUiStyleKey = stringPreferencesKey("settings_ui_style")
    private val screensaverEnabledKey = booleanPreferencesKey("oled_screensaver_enabled")
    private val screensaverTimeoutKey = intPreferencesKey("oled_screensaver_timeout_min")
    private val screensaverDimKey = intPreferencesKey("oled_screensaver_dim_percent")

    val themeSelection: Flow<ThemeSelection> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            ThemeSelection(
                theme = prefs[themeKey]?.let { name -> AppTheme.entries.firstOrNull { it.name == name } },
                customColors = CustomThemeColors.decode(prefs[customThemeColorsKey])
            )
        }
    }

    val selectedFont: Flow<AppFont> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val fontName = prefs[fontKey] ?: AppFont.INTER.name
            try {
                AppFont.valueOf(fontName)
            } catch (e: IllegalArgumentException) {
                AppFont.INTER
            }
        }
    }

    val amoledMode: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[amoledModeKey] ?: false
        }
    }

    val amoledSurfacesMode: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[amoledSurfacesModeKey] ?: false
        }
    }

    val screensaverEnabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[screensaverEnabledKey] ?: true
        }
    }

    val screensaverTimeoutMinutes: Flow<Int> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[screensaverTimeoutKey] ?: DEFAULT_SCREENSAVER_TIMEOUT_MINUTES
        }
    }

    val screensaverDimPercent: Flow<Int> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[screensaverDimKey] ?: DEFAULT_SCREENSAVER_DIM_PERCENT
        }
    }

    val settingsUiStyle: Flow<SettingsUiStyle> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[settingsUiStyleKey]
            SettingsUiStyle.CLASSIC
        }
    }

    suspend fun setTheme(theme: AppTheme) {
        store().edit { prefs ->
            prefs[themeKey] = theme.name
        }
    }

    suspend fun setCustomTheme(colors: CustomThemeColors) {
        store().edit { prefs ->
            prefs[customThemeColorsKey] = colors.encode()
            prefs[themeKey] = AppTheme.CUSTOM.name
        }
    }

    suspend fun setFont(font: AppFont) {
        store().edit { prefs ->
            prefs[fontKey] = font.name
        }
    }

    suspend fun setAmoledMode(enabled: Boolean) {
        store().edit { prefs ->
            prefs[amoledModeKey] = enabled
            if (!enabled) {
                prefs[amoledSurfacesModeKey] = false
            }
        }
    }

    suspend fun setAmoledSurfacesMode(enabled: Boolean) {
        store().edit { prefs ->
            prefs[amoledSurfacesModeKey] = enabled
        }
    }

    suspend fun setScreensaverEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[screensaverEnabledKey] = enabled
        }
    }

    suspend fun setScreensaverTimeoutMinutes(minutes: Int) {
        store().edit { prefs ->
            prefs[screensaverTimeoutKey] = minutes
        }
    }

    suspend fun setScreensaverDimPercent(percent: Int) {
        store().edit { prefs ->
            prefs[screensaverDimKey] = percent
        }
    }

    suspend fun setSettingsUiStyle(style: SettingsUiStyle) {
        store().edit { prefs ->
            prefs[settingsUiStyleKey] = style.name
        }
    }

    suspend fun getThemeForProfile(profileId: Int): AppTheme? {
        val prefs = factory.get(profileId, FEATURE).data.first()
        return prefs[themeKey]?.let { name -> AppTheme.entries.firstOrNull { it.name == name } }
    }

    fun observeThemeForProfile(profileId: Int): Flow<AppTheme?> =
        factory.get(profileId, FEATURE).data.map { prefs ->
            prefs[themeKey]?.let { name -> AppTheme.entries.firstOrNull { it.name == name } }
        }
}
