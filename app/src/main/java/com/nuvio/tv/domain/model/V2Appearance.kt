package com.nuvio.tv.domain.model

enum class InterfaceExperience { ORIGINAL_NUVIO, NUVIO_V2 }
enum class VisualStyle { CINEMATIC_GLASS, PURE_LIQUID_DARK }
enum class NavigationStyle { FLOATING_SIDEBAR, TOP_NAVIGATION, MINIMAL }
enum class FocusStyle { GLASS_LIFT, CINEMATIC_FOCUS }
enum class AccentMode { FIXED_THEME, ADAPTIVE_ARTWORK }
enum class GlassTintMode { NEUTRAL, ACCENT, ARTWORK }
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
    val glassTintMode: GlassTintMode = GlassTintMode.ARTWORK,
    val settingsPresentation: SettingsPresentation = SettingsPresentation.MINIMAL,
    val playerChromeStyle: PlayerChromeStyle = PlayerChromeStyle.CONTROL_DECK
)

/** Device-local, excluded from profile sync. Original is the safe opt-in rollout default. */
data class DeviceUiPreferences(
    val interfaceExperience: InterfaceExperience = InterfaceExperience.ORIGINAL_NUVIO,
    val uiScaleMode: UiScaleMode = UiScaleMode.AUTOMATIC,
    val manualUiScalePercent: Int = 100,
    val autoScaleFineTunePercent: Int = 0,
    val visualQualityMode: VisualQualityMode = VisualQualityMode.AUTOMATIC
)
