package com.nuvio.tv.ui.screens.iptv

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.GuideDensity
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog

@Composable
internal fun IptvDensityRow(viewModel: IptvSettingsViewModel) {
    val density by viewModel.density.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    SettingsActionRow(title = stringResource(R.string.iptv_density), subtitle = stringResource(R.string.iptv_density_subtitle),
        value = stringResource(densityLabel(density)), onClick = { choosing = true })
    if (choosing) SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_density),
        options = GuideDensity.entries.map { SettingsPickerOption(it, stringResource(densityLabel(it)), stringResource(densityDescription(it))) },
        selectedValue = density, onOptionSelected = { viewModel.setDensity(it); choosing = false }, onDismiss = { choosing = false }, width = 520.dp)
}

private fun densityLabel(value: GuideDensity): Int = when (value) {
    GuideDensity.COMFORTABLE -> R.string.iptv_density_comfortable
    GuideDensity.COMPACT -> R.string.iptv_density_compact
}

private fun densityDescription(value: GuideDensity): Int = when (value) {
    GuideDensity.COMFORTABLE -> R.string.iptv_density_comfortable_description
    GuideDensity.COMPACT -> R.string.iptv_density_compact_description
}
