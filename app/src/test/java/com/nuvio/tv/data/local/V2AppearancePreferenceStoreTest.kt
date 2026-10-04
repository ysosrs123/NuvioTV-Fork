package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.*
import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class V2AppearancePreferenceStoreTest {
    @Test fun `legacy snapshot gains balanced glass without changing saved appearance`() {
        val saved = mutablePreferencesOf(
            stringPreferencesKey("visual_style") to "PURE_LIQUID_DARK",
            stringPreferencesKey("glass_tint_mode") to "NEUTRAL",
            stringPreferencesKey("focus_style") to "GLASS_LIFT",
            stringPreferencesKey("player_chrome_style") to "INVISIBLE",
            booleanPreferencesKey("intro_animation_enabled") to false
        )
        val decoded = V2AppearancePreferenceStore.decode(saved)
        assertEquals(GlassPreset.BALANCED, decoded.glassPreset)
        assertEquals(60, decoded.glassTransparencyPercent)
        assertEquals(65, decoded.glassBlurStrengthPercent)
        assertEquals(VisualStyle.PURE_LIQUID_DARK, decoded.visualStyle)
        assertEquals(GlassTintMode.NEUTRAL, decoded.glassTintMode)
        assertEquals(FocusStyle.GLASS_LIFT, decoded.focusStyle)
        assertEquals(PlayerChromeStyle.INVISIBLE, decoded.playerChromeStyle)
        assertFalse(decoded.introAnimationEnabled)
        assertEquals(V2AppearancePreferences(), V2AppearancePreferenceStore.decode(emptyPreferences()))
    }

    @Test fun `each preset and independent fine tuning survive storage roundtrip and unrelated edits`() {
        GlassPreset.entries.forEach { preset ->
            val saved = mutablePreferencesOf()
            val initial = V2AppearancePreferences().withGlassPreset(preset)
            V2AppearancePreferenceStore.encode(saved, initial)
            assertEquals(initial, V2AppearancePreferenceStore.decode(saved))
            val tuned = initial.copy(glassTransparencyPercent = 25, glassBlurStrengthPercent = 80)
            V2AppearancePreferenceStore.encode(saved, tuned)
            val reloaded = V2AppearancePreferenceStore.decode(saved)
            assertEquals(tuned, reloaded)
            assertTrue(reloaded.isGlassFineTuned)
            V2AppearancePreferenceStore.encode(saved, reloaded.copy(introAnimationEnabled = false))
            assertEquals(tuned.copy(introAnimationEnabled = false), V2AppearancePreferenceStore.decode(saved))
            assertEquals(initial, reloaded.withGlassPreset(preset))
            assertFalse(initial.isGlassFineTuned)
        }
    }

    @Test fun `partial and future snapshots use safe defaults and bounded independent values`() {
        val p = mutablePreferencesOf(stringPreferencesKey("glass_preset") to "FROSTED")
        assertEquals(40, V2AppearancePreferenceStore.decode(p).glassTransparencyPercent)
        assertEquals(100, V2AppearancePreferenceStore.decode(p).glassBlurStrengthPercent)
        p[stringPreferencesKey("glass_preset")] = "FUTURE"
        p[intPreferencesKey("glass_transparency_percent")] = -20
        p[intPreferencesKey("glass_blur_strength_percent")] = 300
        val decoded = V2AppearancePreferenceStore.decode(p)
        assertEquals(GlassPreset.BALANCED, decoded.glassPreset)
        assertEquals(0, decoded.glassTransparencyPercent)
        assertEquals(100, decoded.glassBlurStrengthPercent)
        V2AppearancePreferenceStore.encode(p, decoded.copy(glassTransparencyPercent = 400, glassBlurStrengthPercent = -1))
        assertEquals(100, V2AppearancePreferenceStore.decode(p).glassTransparencyPercent)
        assertEquals(0, V2AppearancePreferenceStore.decode(p).glassBlurStrengthPercent)
    }
    @Test fun `settings background survives material switches and missing backgrounds use posters`() {
        val stored = mutablePreferencesOf(stringPreferencesKey("visual_style") to "PURE_LIQUID_DARK")
        assertEquals(SettingsBackground.POSTERS, V2AppearancePreferenceStore.decode(stored).settingsBackground)
        for (background in SettingsBackground.entries) {
            V2AppearancePreferenceStore.encode(stored, V2AppearancePreferenceStore.decode(stored).copy(settingsBackground = background))
            for (style in VisualStyle.entries) {
                V2AppearancePreferenceStore.encode(stored, V2AppearancePreferenceStore.decode(stored).copy(visualStyle = style))
                assertEquals(background, V2AppearancePreferenceStore.decode(stored).settingsBackground)
                assertEquals(style, V2AppearancePreferenceStore.decode(stored).visualStyle)
            }
        }
        stored[stringPreferencesKey("settings_background")] = "FUTURE_BACKGROUND"
        assertEquals(SettingsBackground.POSTERS, V2AppearancePreferenceStore.decode(stored).settingsBackground)
    }

    @Test fun `unset appearance has the requested defaults while prior explicit defaults survive`() {
        val defaults = V2AppearancePreferenceStore.decode(emptyPreferences())
        assertEquals(GlassTintMode.ACCENT, defaults.glassTintMode)
        assertEquals(SettingsPresentation.GLASS, defaults.settingsPresentation)
        assertEquals(PlayerChromeStyle.INVISIBLE, defaults.playerChromeStyle)
        assertEquals(FocusStyle.CINEMATIC_FOCUS, defaults.focusStyle)
        assertEquals(NavigationStyle.FLOATING_SIDEBAR, defaults.navigationStyle)
        assertEquals(VisualStyle.CINEMATIC_GLASS, defaults.visualStyle)
        assertEquals(AccentMode.FIXED_THEME, defaults.accentMode)
        assertTrue(defaults.introAnimationEnabled)
        val saved = mutablePreferencesOf(
            stringPreferencesKey("glass_tint_mode") to "ARTWORK",
            stringPreferencesKey("settings_presentation") to "MINIMAL",
            stringPreferencesKey("player_chrome_style") to "CONTROL_DECK",
            booleanPreferencesKey("intro_animation_enabled") to false
        )
        val old = V2AppearancePreferenceStore.decode(saved)
        assertEquals(GlassTintMode.ARTWORK, old.glassTintMode)
        assertEquals(SettingsPresentation.MINIMAL, old.settingsPresentation)
        assertEquals(PlayerChromeStyle.CONTROL_DECK, old.playerChromeStyle)
        assertFalse(old.introAnimationEnabled)
    }
}
