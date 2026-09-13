@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
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
import com.nuvio.tv.domain.model.UiScaleMode
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.domain.model.VisualQualityMode
import com.nuvio.tv.ui.v2.appearance.LocalDeviceUiPreferences
import com.nuvio.tv.ui.v2.appearance.LocalUiScaleDecision
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import kotlinx.coroutines.launch

@Composable
internal fun V2PreferenceControls() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val device = LocalDeviceUiPreferences.current
    val appearance = LocalV2Appearance.current
    SettingsGroupCard(title = stringResource(R.string.v2_experience_title)) {
        V2ChoiceRow(
            title = stringResource(R.string.v2_experience_title),
            subtitle = stringResource(R.string.v2_experience_subtitle),
            selected = device.interfaceExperience,
            options = listOf(
                InterfaceExperience.NUVIO_V2 to stringResource(R.string.v2_experience_v2),
                InterfaceExperience.ORIGINAL_NUVIO to stringResource(R.string.v2_experience_original)
            ),
            onSelect = { value -> scope.launch {
                DeviceUiPreferenceStore.update(context) { it.copy(interfaceExperience = value) }
            } }
        )
        if (appearance != null) {
            V2ChoiceRow(
                title = stringResource(R.string.v2_visual_style_title),
                selected = appearance.visualStyle,
                options = listOf(
                    VisualStyle.CINEMATIC_GLASS to stringResource(R.string.v2_style_cinematic),
                    VisualStyle.PURE_LIQUID_DARK to stringResource(R.string.v2_style_dark)
                ),
                onSelect = { value -> scope.launch {
                    V2AppearancePreferenceStore.update(context) { it.copy(visualStyle = value) }
                } }
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
                onSelect = { value -> scope.launch {
                    DeviceUiPreferenceStore.update(context) { it.copy(visualQualityMode = value) }
                } }
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
                onSelect = { value -> scope.launch {
                    DeviceUiPreferenceStore.update(context) { it.copy(uiScaleMode = value) }
                } }
            )
            if (device.uiScaleMode == UiScaleMode.AUTOMATIC) {
                SliderSettingsItem(
                    icon = Icons.Default.AspectRatio,
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
                    icon = Icons.Default.AspectRatio,
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
    onSelect: (T) -> Unit,
    subtitle: String? = null
) {
    var open by remember { mutableStateOf(false) }
    SettingsActionRow(
        title = title,
        subtitle = subtitle,
        value = options.firstOrNull { it.first == selected }?.second,
        onClick = { open = true }
    )
    if (open) {
        SettingsSingleChoiceDialog(
            title = title,
            options = options.map { SettingsPickerOption(it.first, it.second) },
            selectedValue = selected,
            onOptionSelected = { onSelect(it); open = false },
            onDismiss = { open = false }
        )
    }
}
