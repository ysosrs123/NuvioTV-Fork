package com.nuvio.tv.domain.model

enum class InterfaceExperience { ORIGINAL_NUVIO, NUVIO_V2 }
enum class VisualStyle { CINEMATIC_GLASS, PURE_LIQUID_DARK }
enum class NavigationStyle { FLOATING_SIDEBAR, TOP_NAVIGATION, MINIMAL }
enum class FocusStyle { GLASS_LIFT, CINEMATIC_FOCUS }
enum class AccentMode { FIXED_THEME, ADAPTIVE_ARTWORK }
/** Presets reset both independent controls; fine tuning keeps the chosen starting preset. */
enum class GlassPreset(val transparencyPercent: Int, val blurStrengthPercent: Int) {
    CLEAR(85, 0), LIGHT_FROST(75, 35), BALANCED(60, 65), FROSTED(40, 100)
}

enum class GlassTintMode { NEUTRAL, ACCENT, ARTWORK }
enum class SettingsBackground { STYLE_DEFAULT, HERO, POSTERS, PURE_LIQUID_DARK, MIDNIGHT, CHARCOAL }

enum class SettingsPresentation { MINIMAL, GLASS }
enum class PlayerChromeStyle { CONTROL_DECK, INVISIBLE }
enum class UiScaleMode { AUTOMATIC, MANUAL }
enum class VisualQualityMode { AUTOMATIC, PERFORMANCE, ENHANCED, MAXIMUM }

/** Aesthetic choices have no hardware settings and can become profile-scoped independently. */
data class V2AppearancePreferences(
    val visualStyle: VisualStyle = VisualStyle.CINEMATIC_GLASS,
    val navigationStyle: NavigationStyle = NavigationStyle.FLOATING_SIDEBAR,
    val focusStyle: FocusStyle = FocusStyle.CINEMATIC_FOCUS,
    val accentMode: AccentMode = AccentMode.FIXED_THEME,
    val glassTintMode: GlassTintMode = GlassTintMode.ACCENT,
    val settingsPresentation: SettingsPresentation = SettingsPresentation.GLASS,
    val settingsBackground: SettingsBackground = SettingsBackground.POSTERS,
    val playerChromeStyle: PlayerChromeStyle = PlayerChromeStyle.INVISIBLE,
    val introAnimationEnabled: Boolean = true,
    val glassPreset: GlassPreset = GlassPreset.BALANCED,
    val glassTransparencyPercent: Int = glassPreset.transparencyPercent,
    val glassBlurStrengthPercent: Int = glassPreset.blurStrengthPercent
) {
    fun withGlassPreset(preset: GlassPreset) = copy(
        glassPreset = preset,
        glassTransparencyPercent = preset.transparencyPercent,
        glassBlurStrengthPercent = preset.blurStrengthPercent
    )

    val isGlassFineTuned: Boolean get() =
        glassTransparencyPercent != glassPreset.transparencyPercent ||
            glassBlurStrengthPercent != glassPreset.blurStrengthPercent
}

/** Device-local, excluded from profile sync. Unset installations use Nuvio v2; explicit choices are retained. */
data class DeviceUiPreferences(
    val interfaceExperience: InterfaceExperience = InterfaceExperience.NUVIO_V2,
    val uiScaleMode: UiScaleMode = UiScaleMode.AUTOMATIC,
    val manualUiScalePercent: Int = 100,
    val autoScaleFineTunePercent: Int = 0,
    val visualQualityMode: VisualQualityMode = VisualQualityMode.AUTOMATIC,
    val automaticQualityTier: String? = null,
    val qualityAssessmentKey: String? = null
)
