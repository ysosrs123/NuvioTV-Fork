@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.MultiviewLayout
import com.nuvio.tv.core.iptv.MultiviewQuality
import com.nuvio.tv.data.iptv.IptvStartView
import com.nuvio.tv.data.iptv.IptvStreamFormat
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.V2Atmosphere

private enum class IptvSettingsChoice { FORMAT, START, LAYOUT, QUALITY, EARLY, LATE, THEME }

@Composable
fun IptvSettingsScreen(onSources: () -> Unit, onSetup: () -> Unit, onRecordings: () -> Unit, viewModel: IptvSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<IptvSettingsChoice?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.reload() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        if (!LocalIptvAppearance.current.plainBackground) LocalV2Appearance.current?.let { V2Atmosphere(rich = false, background = it.settingsBackground) }
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(340.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.iptv_settings_title), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary)
                Text(stringResource(R.string.iptv_settings_description), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                Spacer(Modifier.height(18.dp))
                SettingsActionRow(title = stringResource(R.string.iptv_settings_sources), subtitle = stringResource(R.string.iptv_settings_sources_subtitle),
                    onClick = onSources, leadingIcon = Icons.AutoMirrored.Filled.PlaylistPlay, modifier = Modifier.focusRequester(first))
                SettingsActionRow(title = stringResource(R.string.iptv_remote_entry_title), subtitle = stringResource(R.string.iptv_remote_entry_subtitle),
                    onClick = onSetup, leadingIcon = Icons.Filled.PhoneAndroid)
                SettingsActionRow(title = stringResource(R.string.iptv_recordings_open), subtitle = stringResource(R.string.iptv_settings_recordings_subtitle),
                    onClick = onRecordings, leadingIcon = Icons.Filled.VideoLibrary)
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "playback") {
                    SettingsGroupCard(title = stringResource(R.string.iptv_settings_playback)) {
                        SettingsActionRow(title = stringResource(R.string.iptv_live_format_title), subtitle = stringResource(R.string.iptv_settings_format_subtitle),
                            value = stringResource(formatLabel(state.format)), onClick = { choosing = IptvSettingsChoice.FORMAT })
                        SettingsToggleRow(title = stringResource(R.string.iptv_settings_preview), subtitle = stringResource(R.string.iptv_settings_preview_subtitle),
                            checked = state.preview, onToggle = viewModel::togglePreview)
                        SettingsToggleRow(title = stringResource(R.string.iptv_settings_timeshift), subtitle = stringResource(R.string.iptv_settings_timeshift_subtitle),
                            checked = state.timeshift, onToggle = viewModel::toggleTimeshift)
                        SettingsToggleRow(title = stringResource(R.string.iptv_settings_stats), subtitle = stringResource(R.string.iptv_settings_stats_subtitle),
                            checked = state.stats, onToggle = viewModel::toggleStats)
                    }
                }
                item(key = "appearance") {
                    SettingsGroupCard(title = stringResource(R.string.iptv_settings_appearance), subtitle = stringResource(R.string.iptv_settings_appearance_subtitle)) {
                        SettingsActionRow(title = stringResource(R.string.iptv_settings_theme), subtitle = null,
                            value = stringResource(iptvThemeLabel(iptvTheme(state.appearance.theme))), onClick = { choosing = IptvSettingsChoice.THEME })
                        SettingsToggleRow(title = stringResource(R.string.iptv_settings_black), subtitle = stringResource(R.string.iptv_settings_black_subtitle),
                            checked = state.appearance.black, onToggle = viewModel::toggleBlack)
                        SettingsToggleRow(title = stringResource(R.string.iptv_settings_solid), subtitle = stringResource(R.string.iptv_settings_solid_subtitle),
                            checked = state.appearance.solidPanels, onToggle = viewModel::toggleSolid)
                        SettingsToggleRow(title = stringResource(R.string.iptv_settings_artwork), subtitle = stringResource(R.string.iptv_settings_artwork_subtitle),
                            checked = !state.appearance.plainBackground, onToggle = viewModel::toggleArtwork)
                    }
                }
                item(key = "guide") {
                    SettingsGroupCard(title = stringResource(R.string.iptv_live_guide)) {
                        SettingsActionRow(title = stringResource(R.string.iptv_settings_start), subtitle = null,
                            value = stringResource(startLabel(state.startView)), onClick = { choosing = IptvSettingsChoice.START })
                        SettingsToggleRow(title = stringResource(R.string.iptv_settings_sport), subtitle = stringResource(R.string.iptv_settings_sport_subtitle),
                            checked = state.sport, onToggle = viewModel::toggleSport)
                        SettingsActionRow(title = stringResource(R.string.iptv_live_hidden_categories),
                            subtitle = stringResource(if (state.hiddenCategories > 0) R.string.iptv_settings_hidden_subtitle else R.string.iptv_settings_hidden_none),
                            value = state.hiddenCategories.takeIf { it > 0 }?.toString(), enabled = state.hiddenCategories > 0,
                            onClick = viewModel::unhideCategories, trailingIcon = null)
                    }
                }
                if (state.multiview) item(key = "multiview") {
                    SettingsGroupCard(title = stringResource(R.string.iptv_multiview_title)) {
                        SettingsActionRow(title = stringResource(R.string.iptv_multiview_layout), subtitle = null,
                            value = stringResource(layoutLabel(state.layout)), onClick = { choosing = IptvSettingsChoice.LAYOUT })
                        SettingsActionRow(title = stringResource(R.string.iptv_multiview_quality), subtitle = stringResource(R.string.iptv_multiview_quality_description),
                            value = stringResource(qualityLabel(state.quality)), onClick = { choosing = IptvSettingsChoice.QUALITY })
                    }
                }
                item(key = "recordings") {
                    SettingsGroupCard(title = stringResource(R.string.iptv_recordings_open)) {
                        SettingsActionRow(title = stringResource(R.string.iptv_settings_record_early), subtitle = null,
                            value = minutes(state.recordEarly), onClick = { choosing = IptvSettingsChoice.EARLY })
                        SettingsActionRow(title = stringResource(R.string.iptv_settings_record_late), subtitle = stringResource(R.string.iptv_settings_record_late_subtitle),
                            value = minutes(state.recordLate), onClick = { choosing = IptvSettingsChoice.LATE })
                    }
                }
            }
        }
    }
    val dismiss = { choosing = null }
    when (choosing) {
        IptvSettingsChoice.FORMAT -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_live_format_title),
            subtitle = stringResource(R.string.iptv_settings_format_subtitle),
            options = IptvStreamFormat.entries.map { SettingsPickerOption(it, stringResource(formatLabel(it))) },
            selectedValue = state.format, onOptionSelected = { viewModel.setFormat(it); dismiss() }, onDismiss = dismiss)
        IptvSettingsChoice.START -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_settings_start),
            options = IptvStartView.entries.filter { state.sport || it != IptvStartView.SPORT }.map { SettingsPickerOption(it, stringResource(startLabel(it))) },
            selectedValue = state.startView, onOptionSelected = { viewModel.setStartView(it); dismiss() }, onDismiss = dismiss)
        IptvSettingsChoice.LAYOUT -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_multiview_layout),
            options = MultiviewLayout.entries.map { SettingsPickerOption(it, stringResource(layoutLabel(it))) },
            selectedValue = state.layout, onOptionSelected = { viewModel.setLayout(it); dismiss() }, onDismiss = dismiss)
        IptvSettingsChoice.QUALITY -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_multiview_quality),
            options = MultiviewQuality.entries.map { SettingsPickerOption(it, stringResource(qualityLabel(it)), stringResource(qualityDescription(it))) },
            selectedValue = state.quality, onOptionSelected = { viewModel.setQuality(it); dismiss() }, onDismiss = dismiss, width = 560.dp)
        IptvSettingsChoice.EARLY -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_settings_record_early),
            options = EARLY_MINUTES.map { SettingsPickerOption(it, minutes(it)) },
            selectedValue = state.recordEarly, onOptionSelected = { viewModel.setRecordEarly(it); dismiss() }, onDismiss = dismiss)
        IptvSettingsChoice.LATE -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_settings_record_late),
            subtitle = stringResource(R.string.iptv_settings_record_late_subtitle),
            options = LATE_MINUTES.map { SettingsPickerOption(it, minutes(it)) },
            selectedValue = state.recordLate, onOptionSelected = { viewModel.setRecordLate(it); dismiss() }, onDismiss = dismiss)
        IptvSettingsChoice.THEME -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_settings_theme),
            options = (listOf(null) + IPTV_THEMES).map { SettingsPickerOption(it, stringResource(iptvThemeLabel(it))) },
            selectedValue = iptvTheme(state.appearance.theme), onOptionSelected = { viewModel.setTheme(it); dismiss() }, onDismiss = dismiss)
        null -> Unit
    }
}

private val EARLY_MINUTES = listOf(0, 1, 2, 5, 10)
private val LATE_MINUTES = listOf(0, 2, 5, 10, 15, 30)

@Composable
private fun minutes(value: Int): String =
    if (value == 0) stringResource(R.string.iptv_settings_none) else pluralStringResource(R.plurals.iptv_settings_minutes, value, value)

private fun startLabel(value: IptvStartView): Int = when (value) {
    IptvStartView.LAST -> R.string.iptv_settings_start_last
    IptvStartView.ALL -> R.string.iptv_live_all
    IptvStartView.FAVOURITES -> R.string.iptv_live_favourites
    IptvStartView.SPORT -> R.string.iptv_live_sports
}

private fun layoutLabel(value: MultiviewLayout): Int = when (value) {
    MultiviewLayout.GRID -> R.string.iptv_multiview_layout_grid
    MultiviewLayout.FOCUS -> R.string.iptv_multiview_layout_focus
}

private fun qualityLabel(value: MultiviewQuality): Int = when (value) {
    MultiviewQuality.AUTO -> R.string.iptv_multiview_quality_auto
    MultiviewQuality.SHARPEST -> R.string.iptv_multiview_quality_sharpest
    MultiviewQuality.LIGHTEST -> R.string.iptv_multiview_quality_lightest
}

private fun qualityDescription(value: MultiviewQuality): Int = when (value) {
    MultiviewQuality.AUTO -> R.string.iptv_multiview_quality_auto_description
    MultiviewQuality.SHARPEST -> R.string.iptv_multiview_quality_sharpest_description
    MultiviewQuality.LIGHTEST -> R.string.iptv_multiview_quality_lightest_description
}
