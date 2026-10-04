@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.local.DeviceUiPreferenceStore
import com.nuvio.tv.data.local.V2AppearancePreferenceStore
import com.nuvio.tv.domain.model.InterfaceExperience
import com.nuvio.tv.domain.model.AccentMode
import com.nuvio.tv.domain.model.GlassPreset
import com.nuvio.tv.domain.model.GlassTintMode
import com.nuvio.tv.domain.model.NavigationStyle
import com.nuvio.tv.domain.model.PlayerChromeStyle
import com.nuvio.tv.domain.model.SettingsPresentation
import com.nuvio.tv.domain.model.FocusStyle
import com.nuvio.tv.domain.model.UiScaleMode
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.domain.model.VisualQualityMode
import com.nuvio.tv.ui.v2.appearance.LocalDeviceUiPreferences
import com.nuvio.tv.ui.v2.appearance.LocalUiScaleDecision
import com.nuvio.tv.ui.v2.quality.LocalVisualQuality
import com.nuvio.tv.ui.v2.quality.VisualQualityTier
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import kotlinx.coroutines.launch

@Composable
internal fun V2PreferenceControls(initialFocusRequester: FocusRequester? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val device = LocalDeviceUiPreferences.current
    val appearance = LocalV2Appearance.current
    SettingsGroupCard(title = stringResource(R.string.v2_experience_title)) {
        V2ChoiceRow(
            title = stringResource(R.string.v2_experience_title),
            subtitle = stringResource(R.string.v2_experience_subtitle),
            initialFocusRequester = initialFocusRequester,
            selected = device.interfaceExperience,
            options = listOf(
                InterfaceExperience.NUVIO_V2 to stringResource(R.string.v2_experience_v2),
                InterfaceExperience.ORIGINAL_NUVIO to stringResource(R.string.v2_experience_original)
            ),
            onSelect = { value ->
                DeviceUiPreferenceStore.update(context) { it.copy(interfaceExperience = value) }
            }
        )
        if (appearance != null) {
            SettingsToggleRow(
                title = stringResource(R.string.v2_intro_animation_title),
                subtitle = stringResource(R.string.v2_intro_animation_subtitle),
                checked = appearance.introAnimationEnabled,
                onToggle = { scope.launch {
                    V2AppearancePreferenceStore.update(context) { it.copy(introAnimationEnabled = !it.introAnimationEnabled) }
                } }
            )
            V2ChoiceRow(
                title = stringResource(R.string.v2_visual_style_title),
                selected = appearance.visualStyle,
                options = listOf(
                    VisualStyle.CINEMATIC_GLASS to stringResource(R.string.v2_style_cinematic),
                    VisualStyle.PURE_LIQUID_DARK to stringResource(R.string.v2_style_dark)
                ),
                onSelect = { value ->
                    V2AppearancePreferenceStore.update(context) { it.copy(visualStyle = value) }
                }
            )
            V2ChoiceRow(
                title = stringResource(R.string.v2_navigation_title),
                selected = appearance.navigationStyle,
                options = listOf(
                    NavigationStyle.FLOATING_SIDEBAR to stringResource(R.string.v2_navigation_sidebar),
                    NavigationStyle.TOP_NAVIGATION to stringResource(R.string.v2_navigation_top)
                ),
                onSelect = { value ->
                    V2AppearancePreferenceStore.update(context) { it.copy(navigationStyle = value) }
                }
            )
            V2ChoiceRow(
                title = stringResource(R.string.v2_accent_title),
                subtitle = stringResource(R.string.v2_accent_fixed_hint),
                selected = appearance.accentMode,
                options = listOf(
                    AccentMode.FIXED_THEME to com.nuvio.tv.ui.theme.LocalAppTheme.current.localizedName(),
                    AccentMode.ADAPTIVE_ARTWORK to stringResource(R.string.v2_accent_artwork)
                ),
                onSelect = { value ->
                    V2AppearancePreferenceStore.update(context) { it.copy(accentMode = value) }
                }
            )
            V2ChoiceRow(
                title = stringResource(R.string.v2_glass_tint),
                selected = appearance.glassTintMode,
                options = listOf(
                    GlassTintMode.NEUTRAL to stringResource(R.string.v2_tint_neutral),
                    GlassTintMode.ACCENT to stringResource(R.string.v2_accent_title),
                    GlassTintMode.ARTWORK to stringResource(R.string.tmdb_artwork_title)
                ),
                onSelect = { value ->
                    V2AppearancePreferenceStore.update(context) { it.copy(glassTintMode = value) }
                }
            )
            if (appearance.visualStyle == VisualStyle.CINEMATIC_GLASS) {
                V2ChoiceRow(
                    title = stringResource(R.string.v2_glass_preset),
                    subtitle = if (appearance.isGlassFineTuned) stringResource(R.string.v2_glass_fine_tuned)
                        else stringResource(R.string.v2_glass_preset_hint),
                    selected = appearance.glassPreset,
                    options = listOf(
                        GlassPreset.CLEAR to stringResource(R.string.v2_glass_clear),
                        GlassPreset.LIGHT_FROST to stringResource(R.string.v2_glass_light_frost),
                        GlassPreset.BALANCED to stringResource(R.string.v2_glass_balanced),
                        GlassPreset.FROSTED to stringResource(R.string.v2_glass_frosted)
                    ),
                    onSelect = { value -> V2AppearancePreferenceStore.update(context) { it.withGlassPreset(value) } }
                )
                SliderSettingsItem(
                    title = stringResource(R.string.v2_glass_transparency),
                    subtitle = stringResource(R.string.v2_glass_transparency_hint),
                    value = appearance.glassTransparencyPercent,
                    valueText = "${appearance.glassTransparencyPercent}%",
                    minValue = 0, maxValue = 100, step = 5,
                    onValueChange = { value -> scope.launch {
                        V2AppearancePreferenceStore.update(context) { it.copy(glassTransparencyPercent = value) }
                    } },
                    onFocused = {}
                )
                SliderSettingsItem(
                    title = stringResource(R.string.v2_glass_blur_strength),
                    subtitle = stringResource(if (LocalVisualQuality.current.supportsLiveBlur &&
                        LocalVisualQuality.current.tier != VisualQualityTier.PERFORMANCE)
                        R.string.v2_glass_blur_hint else R.string.v2_glass_blur_unavailable),
                    value = appearance.glassBlurStrengthPercent,
                    valueText = "${appearance.glassBlurStrengthPercent}%",
                    minValue = 0, maxValue = 100, step = 5,
                    onValueChange = { value -> scope.launch {
                        V2AppearancePreferenceStore.update(context) { it.copy(glassBlurStrengthPercent = value) }
                    } },
                    onFocused = {}
                )
            }
            V2ChoiceRow(
                title = stringResource(R.string.v2_focus_style_title),
                selected = appearance.focusStyle,
                options = listOf(
                    FocusStyle.GLASS_LIFT to stringResource(R.string.v2_focus_glass_lift),
                    FocusStyle.CINEMATIC_FOCUS to stringResource(R.string.v2_focus_cinematic)
                ),
                onSelect = { value ->
                    V2AppearancePreferenceStore.update(context) { it.copy(focusStyle = value) }
                }
            )
            V2ChoiceRow(
                title = stringResource(R.string.v2_player_chrome),
                selected = appearance.playerChromeStyle,
                options = listOf(
                    PlayerChromeStyle.CONTROL_DECK to stringResource(R.string.v2_player_deck),
                    PlayerChromeStyle.INVISIBLE to stringResource(R.string.v2_player_invisible)
                ),
                onSelect = { value ->
                    V2AppearancePreferenceStore.update(context) { it.copy(playerChromeStyle = value) }
                }
            )
            V2ChoiceRow(
                title = stringResource(R.string.v2_settings_background),
                subtitle = stringResource(R.string.v2_settings_background_help),
                selected = appearance.settingsBackground,
                options = listOf(
                    com.nuvio.tv.domain.model.SettingsBackground.STYLE_DEFAULT to stringResource(R.string.v2_settings_background_default),
                    com.nuvio.tv.domain.model.SettingsBackground.HERO to stringResource(R.string.v2_settings_background_hero),
                    com.nuvio.tv.domain.model.SettingsBackground.POSTERS to stringResource(R.string.v2_settings_background_posters),
                    com.nuvio.tv.domain.model.SettingsBackground.PURE_LIQUID_DARK to stringResource(R.string.v2_style_dark),
                    com.nuvio.tv.domain.model.SettingsBackground.MIDNIGHT to stringResource(R.string.v2_settings_background_midnight),
                    com.nuvio.tv.domain.model.SettingsBackground.CHARCOAL to stringResource(R.string.v2_settings_background_charcoal)
                ),
                onSelect = { value -> V2AppearancePreferenceStore.update(context) { it.copy(settingsBackground = value) } }
            )
            V2ChoiceRow(
                title = stringResource(R.string.v2_settings_presentation),
                selected = appearance.settingsPresentation,
                options = listOf(
                    SettingsPresentation.MINIMAL to stringResource(R.string.v2_settings_minimal),
                    SettingsPresentation.GLASS to stringResource(R.string.v2_settings_glass)
                ),
                onSelect = { value ->
                    V2AppearancePreferenceStore.update(context) { it.copy(settingsPresentation = value) }
                }
            )
        }
    }
    if (appearance != null) {
        SettingsGroupCard(title = stringResource(R.string.v2_quality_title)) {
            V2ChoiceRow(
                title = stringResource(R.string.v2_quality_title),
                subtitle = stringResource(R.string.v2_quality_subtitle),
                selected = device.visualQualityMode,
                options = listOf(
                    VisualQualityMode.AUTOMATIC to stringResource(R.string.v2_scale_automatic),
                    VisualQualityMode.PERFORMANCE to stringResource(R.string.v2_quality_performance),
                    VisualQualityMode.ENHANCED to stringResource(R.string.v2_quality_enhanced),
                    VisualQualityMode.MAXIMUM to stringResource(R.string.v2_quality_maximum)
                ),
                onSelect = { value ->
                    DeviceUiPreferenceStore.update(context) { it.copy(visualQualityMode = value) }
                }
            )
        }
        SettingsGroupCard(title = stringResource(R.string.ui_scale_title)) {
            V2ChoiceRow(
                title = stringResource(R.string.ui_scale_title),
                subtitle = stringResource(R.string.v2_scale_resolved, LocalUiScaleDecision.current.percent),
                selected = device.uiScaleMode,
                options = listOf(
                    UiScaleMode.AUTOMATIC to stringResource(R.string.v2_scale_automatic),
                    UiScaleMode.MANUAL to stringResource(R.string.v2_scale_manual)
                ),
                onSelect = { value ->
                    DeviceUiPreferenceStore.update(context) { it.copy(uiScaleMode = value) }
                }
            )
            if (device.uiScaleMode == UiScaleMode.AUTOMATIC) {
                SliderSettingsItem(
                    title = stringResource(R.string.v2_scale_fine_tune),
                    value = device.autoScaleFineTunePercent,
                    valueText = "${device.autoScaleFineTunePercent}%",
                    minValue = -10, maxValue = 10, step = 2,
                    onValueChange = { value -> scope.launch {
                        DeviceUiPreferenceStore.update(context) { it.copy(autoScaleFineTunePercent = value) }
                    } },
                    onFocused = {}
                )
            } else {
                SliderSettingsItem(
                    title = stringResource(R.string.ui_scale_title),
                    value = device.manualUiScalePercent,
                    valueText = "${device.manualUiScalePercent}%",
                    minValue = 75, maxValue = 115, step = 5,
                    onValueChange = { value -> scope.launch {
                        DeviceUiPreferenceStore.update(context) { it.copy(manualUiScalePercent = value) }
                    } },
                    onFocused = {}
                )
            }
        }
    }
}

@Composable
private fun <T> V2ChoiceRow(
    title: String,
    selected: T,
    options: List<Pair<T, String>>,
    onSelect: suspend (T) -> Unit,
    subtitle: String? = null,
    initialFocusRequester: FocusRequester? = null
) {
    val scope = rememberCoroutineScope()
    if (selected is VisualStyle || selected is NavigationStyle || selected is FocusStyle ||
        selected is PlayerChromeStyle || selected is SettingsPresentation) {
        V2VisualOptions(title, selected, options) { value -> scope.launch { onSelect(value) } }
        return
    }
    var open by remember { mutableStateOf(false) }
    val fallbackFocus = remember { FocusRequester() }
    val rowFocus = initialFocusRequester ?: fallbackFocus
    val rowVisibility = remember { BringIntoViewRequester() }
    val dismiss: () -> Unit = {
        open = false
        scope.launch {
            repeat(2) { withFrameNanos { } }
            runCatching { rowFocus.requestFocus() }
            // Density changes can retain focus while moving its row outside the
            // scrolled viewport. Reposition after the updated layout has settled.
            repeat(2) { withFrameNanos { } }
            rowVisibility.bringIntoView()
        }
    }
    SettingsActionRow(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.bringIntoViewRequester(rowVisibility).focusRequester(rowFocus),
        value = options.firstOrNull { it.first == selected }?.second,
        onClick = { open = true }
    )
    if (open) {
        SettingsSingleChoiceDialog(
            title = title,
            options = options.map { SettingsPickerOption(it.first, it.second) },
            selectedValue = selected,
            onOptionSelected = { value -> scope.launch { onSelect(value); dismiss() } },
            onDismiss = dismiss
        )
    }
}
