@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.MultiviewLayout
import com.nuvio.tv.core.iptv.MultiviewQuality
import com.nuvio.tv.data.iptv.IptvStartView
import com.nuvio.tv.data.iptv.IptvStreamFormat
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.screens.settings.localizedName
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.V2Atmosphere
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import kotlinx.coroutines.flow.MutableStateFlow

private enum class IptvSettingsChoice { FORMAT, START, LAYOUT, QUALITY, EARLY, LATE, THEME }

object IptvSettingsReturn {
    val requested = MutableStateFlow(false)
}

@Composable
fun IptvSettingsScreen(onBack: () -> Unit, onSources: () -> Unit, onSetup: () -> Unit, onRecordings: () -> Unit,
    viewModel: IptvSettingsViewModel = hiltViewModel()) {
    var link by rememberSaveable { mutableIntStateOf(0) }
    val links = remember { List(3) { FocusRequester() } }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { links[link].requestFocus() } }
    BackHandler(onBack = onBack)
    var leftColumn by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.keyCode != android.view.KeyEvent.KEYCODE_DPAD_LEFT || !leftColumn) false
        else { if (native.action == android.view.KeyEvent.ACTION_DOWN && native.repeatCount == 0) onBack(); true }
    }) {
        if (!LocalIptvAppearance.current.plainBackground) LocalV2Appearance.current?.let { V2Atmosphere(rich = false, background = it.settingsBackground) }
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(340.dp).fillMaxHeight().onFocusChanged { leftColumn = it.hasFocus }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsDetailHeader(stringResource(R.string.iptv_settings_title), stringResource(R.string.iptv_settings_description))
                Spacer(Modifier.height(12.dp))
                SettingsActionRow(title = stringResource(R.string.iptv_settings_sources), subtitle = stringResource(R.string.iptv_settings_sources_subtitle),
                    onClick = { link = 0; onSources() }, leadingIcon = Icons.AutoMirrored.Filled.PlaylistPlay, modifier = Modifier.focusRequester(links[0]))
                SettingsActionRow(title = stringResource(R.string.iptv_ui9_phone_setup), subtitle = stringResource(R.string.iptv_remote_entry_subtitle),
                    onClick = { link = 1; onSetup() }, leadingIcon = Icons.Filled.PhoneAndroid, modifier = Modifier.focusRequester(links[1]))
                SettingsActionRow(title = stringResource(R.string.iptv_recordings_open), subtitle = stringResource(R.string.iptv_settings_recordings_subtitle),
                    onClick = { link = 2; onRecordings() }, leadingIcon = Icons.Filled.VideoLibrary, modifier = Modifier.focusRequester(links[2]))
            }
            IptvSettingsBody(viewModel, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun IptvSettingsBody(viewModel: IptvSettingsViewModel, modifier: Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val themes by viewModel.themes.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<IptvSettingsChoice?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.reload() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val list = rememberLazyListState()
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "playback") {
                SettingsGroupCard(title = stringResource(R.string.iptv_settings_playback)) {
                    SettingsActionRow(title = stringResource(R.string.iptv_live_format_title), subtitle = stringResource(R.string.iptv_settings_format_subtitle),
                        value = stringResource(formatLabel(state.format)), onClick = { choosing = IptvSettingsChoice.FORMAT })
                    SettingsToggleRow(title = stringResource(R.string.iptv_settings_timeshift), subtitle = stringResource(R.string.iptv_settings_timeshift_subtitle),
                        checked = state.timeshift, onToggle = viewModel::toggleTimeshift)
                    SettingsToggleRow(title = stringResource(R.string.iptv_settings_stats), subtitle = stringResource(R.string.iptv_settings_stats_subtitle),
                        checked = state.stats, onToggle = viewModel::toggleStats)
                }
            }
            item(key = "streaming") { IptvStreamingSettingsSection() }
            item(key = "local-timeshift") { IptvTimeshiftSettingsSection() }
            item(key = "appearance") {
                SettingsGroupCard(title = stringResource(R.string.iptv_settings_appearance), subtitle = stringResource(R.string.iptv_settings_appearance_subtitle)) {
                    SettingsActionRow(title = stringResource(R.string.iptv_settings_theme), subtitle = null,
                        value = themeLabel(iptvTheme(state.appearance.theme)?.takeIf { it in themes }), onClick = { choosing = IptvSettingsChoice.THEME })
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
                    IptvDensityRow(viewModel)
                    IptvGuideDaysRow()
                    SettingsToggleRow(title = stringResource(R.string.iptv_settings_sport), subtitle = stringResource(R.string.iptv_settings_sport_subtitle),
                        checked = state.sport, onToggle = viewModel::toggleSport)
                    SettingsActionRow(title = stringResource(R.string.iptv_live_hidden_categories),
                        subtitle = stringResource(if (state.hiddenCategories > 0) R.string.iptv_settings_hidden_subtitle else R.string.iptv_settings_hidden_none),
                        value = state.hiddenCategories.takeIf { it > 0 }?.toString(), enabled = state.hiddenCategories > 0,
                        onClick = viewModel::unhideCategories, trailingIcon = null)
                }
            }
            if (state.sport) item(key = "sports") { IptvSportsSettingsSection() }
            item(key = "vod") { IptvVodSettingsSection() }
            item(key = "home") { IptvHomeSettingsSection() }
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
                    SettingsActionRow(title = stringResource(R.string.iptv_location_title), subtitle = stringResource(R.string.iptv_location_subtitle),
                        value = locationLabel(state.location), valueColor = if (state.location.available) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.Error,
                        onClick = viewModel::openLocations)
                }
            }
        }
        SettingsVerticalScrollIndicators(state = list)
    }
    IptvOpaqueDialogs { IptvSettingsDialogs(state, choosing, { choosing = null }, viewModel) }
}

@Composable
private fun IptvSettingsDialogs(state: IptvSettingsState, choosing: IptvSettingsChoice?, dismiss: () -> Unit, viewModel: IptvSettingsViewModel) {
    val locations by viewModel.locations.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val shareForm by viewModel.shareForm.collectAsStateWithLifecycle()
    val shareStatus by viewModel.shareStatus.collectAsStateWithLifecycle()
    val themes by viewModel.themes.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
            options = (listOf(null) + themes).map { SettingsPickerOption(it, themeLabel(it)) },
            selectedValue = iptvTheme(state.appearance.theme)?.takeIf { it in themes }, onOptionSelected = { viewModel.setTheme(it); dismiss() }, onDismiss = dismiss)
        null -> Unit
    }
    locations?.let { options ->
        SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_location_title), subtitle = stringResource(R.string.iptv_location_dialog_subtitle),
            options = options.map { option -> SettingsPickerOption(option.value, locationTitle(option), locationDescription(option, context)) },
            selectedValue = state.location.value, onOptionSelected = { value -> options.firstOrNull { it.value == value }?.let(viewModel::chooseLocation) },
            onDismiss = viewModel::closeLocations, width = 560.dp, maxHeight = 400.dp)
    }
    notice?.let { message ->
        val ok = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { ok.requestFocus() } }
        NuvioDialog(onDismiss = viewModel::dismissNotice, title = stringResource(R.string.iptv_location_title), subtitle = stringResource(message), width = 560.dp) {
            NuvioActionPill(viewModel::dismissNotice, Modifier.focusRequester(ok)) { Text(stringResource(R.string.iptv_location_ok)) }
        }
    }
    shareForm?.let { form -> key(form) { IptvShareDialog(form, shareStatus, viewModel) } }
}

@Composable
private fun themeLabel(theme: com.nuvio.tv.domain.model.AppTheme?): String =
    theme?.localizedName() ?: stringResource(R.string.iptv_settings_theme_nuvio)

@Composable
private fun locationLabel(summary: IptvLocationSummary): String = when (summary.kind) {
    IptvLocationKind.DEVICE -> stringResource(R.string.iptv_location_device)
    IptvLocationKind.DRIVE -> if (summary.available) summary.label ?: stringResource(R.string.iptv_location_drive) else stringResource(R.string.iptv_location_drive_missing)
    IptvLocationKind.SHARE -> if (summary.available) stringResource(R.string.iptv_network_location) else stringResource(R.string.iptv_location_share_not_set)
    IptvLocationKind.MEDIA -> stringResource(if (summary.available) R.string.iptv_media_label else R.string.iptv_media_missing)
}

@Composable
private fun locationTitle(option: IptvLocationOption): String = when (option.kind) {
    IptvLocationKind.DEVICE -> stringResource(R.string.iptv_location_device)
    IptvLocationKind.DRIVE -> option.label ?: stringResource(R.string.iptv_location_drive)
    IptvLocationKind.SHARE -> stringResource(R.string.iptv_network_location)
    IptvLocationKind.MEDIA -> stringResource(R.string.iptv_media_location)
}

@Composable
private fun locationDescription(option: IptvLocationOption, context: android.content.Context): String? {
    val free = option.freeBytes?.let { stringResource(R.string.iptv_location_free, Formatter.formatShortFileSize(context, it)) }
    return when (option.kind) {
        IptvLocationKind.DEVICE -> free
        IptvLocationKind.DRIVE -> listOfNotNull(free, option.fileSystem?.label,
            if (option.fileSystem?.largeFiles == false) stringResource(R.string.iptv_location_parts) else null).joinToString(" · ")
        IptvLocationKind.SHARE -> option.label ?: stringResource(R.string.iptv_location_share_setup)
        IptvLocationKind.MEDIA -> free
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

private fun layoutLabel(value: MultiviewLayout): Int = multiviewLayoutLabel(value)

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
