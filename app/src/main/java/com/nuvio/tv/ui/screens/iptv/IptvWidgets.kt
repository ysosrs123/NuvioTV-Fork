@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureSection
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.LiveWidgetKind
import com.nuvio.tv.core.iptv.LiveWidgetLayout
import com.nuvio.tv.core.iptv.LiveWidgets
import com.nuvio.tv.core.iptv.RecordingStatus
import com.nuvio.tv.core.iptv.WidgetClock
import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.data.iptv.IptvRecording
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import java.time.ZoneId
import kotlinx.coroutines.delay

@Stable
internal class IptvWidgetSettings(private val preferences: IptvLivePreferences, val deviceZone: String) {
    var layout by mutableStateOf(preferences.widgetLayout)
        private set
    var kinds by mutableStateOf(preferences.widgetKinds)
        private set
    var cities by mutableStateOf(preferences.widgetCities(deviceZone))
        private set

    fun chooseLayout(value: LiveWidgetLayout) { layout = value; preferences.widgetLayout = value }

    fun setKind(slot: Int, kind: LiveWidgetKind) {
        kinds = kinds.mapIndexed { index, old -> if (index == slot) kind else old }
        preferences.widgetKinds = kinds
    }

    fun chooseCities(ids: List<String>) {
        cities = ids.distinct().take(LiveWidgets.MAX_CITIES)
        preferences.setWidgetCities(cities)
    }
}

@Composable
internal fun rememberIptvWidgetSettings(): IptvWidgetSettings {
    val context = LocalContext.current
    return remember { IptvWidgetSettings(IptvLivePreferences(context), ZoneId.systemDefault().id) }
}

private enum class WidgetStep { CHOOSE, CITIES, ADD_CITY, LAYOUT }

private data class WidgetDialog(val slot: Int, val step: WidgetStep)

private val TileShape = RoundedCornerShape(16.dp)

@Composable
internal fun IptvWidgetRow(slots: List<Int>, settings: IptvWidgetSettings, state: IptvLiveState, modifier: Modifier, onDown: () -> Unit, onRail: () -> Unit) {
    val minute by produceState(System.currentTimeMillis()) { while (true) { delay(LiveWidgets.nextMinute(System.currentTimeMillis())); value = System.currentTimeMillis() } }
    var dialog by remember { mutableStateOf<WidgetDialog?>(null) }
    var refocus by remember { mutableStateOf<Int?>(null) }
    val requesters = remember { List(LiveWidgetLayout.THREE.slots) { FocusRequester() } }
    LaunchedEffect(dialog) {
        if (dialog != null) return@LaunchedEffect
        val slot = refocus ?: return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        refocus = null
        requesters.getOrNull(slot.coerceAtMost(slots.lastIndex))?.let { runCatching { it.requestFocus() } }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(LiveWidgets.GAP.dp)) {
        slots.forEachIndexed { index, width ->
            val kind = settings.kinds[index]
            WidgetTile(kind, width.dp, requesters[index], first = index == 0,
                onChoose = { dialog = WidgetDialog(index, WidgetStep.CHOOSE) }, onDown = onDown, onRail = onRail) { wide, roomy ->
                when (kind) {
                    LiveWidgetKind.CLOCKS -> ClocksWidget(settings, minute, wide)
                    LiveWidgetKind.SPORT -> if (state.sportEnabled) SportWidget(roomy) else WidgetMessage(stringResource(R.string.iptv_widgets_sport_off))
                    LiveWidgetKind.UP_NEXT -> UpNextWidget(state, minute, roomy)
                    LiveWidgetKind.RECORDINGS -> RecordingsWidget(state.recordings, minute, roomy)
                    LiveWidgetKind.STREAM -> StreamWidget(state.player, state.playing, wide)
                    LiveWidgetKind.EMPTY -> EmptyWidget()
                }
            }
        }
    }
    val open = dialog ?: return
    fun close() { refocus = open.slot; dialog = null }
    when (open.step) {
        WidgetStep.CHOOSE -> WidgetChooser(settings, open.slot, sport = state.sportEnabled, onKind = { kind ->
            settings.setKind(open.slot, kind)
            if (kind == LiveWidgetKind.CLOCKS) dialog = open.copy(step = WidgetStep.CITIES) else close()
        }, onCities = { dialog = open.copy(step = WidgetStep.CITIES) }, onLayout = { dialog = open.copy(step = WidgetStep.LAYOUT) }, onDismiss = ::close)
        WidgetStep.CITIES -> CitiesDialog(settings, minute, onAdd = { dialog = open.copy(step = WidgetStep.ADD_CITY) }, onDismiss = ::close)
        WidgetStep.ADD_CITY -> CityPicker(settings, onPick = { id -> settings.chooseCities(settings.cities + id); dialog = open.copy(step = WidgetStep.CITIES) },
            onDismiss = { dialog = open.copy(step = WidgetStep.CITIES) })
        WidgetStep.LAYOUT -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_widgets_layout),
            subtitle = stringResource(R.string.iptv_widgets_layout_subtitle),
            options = LiveWidgetLayout.entries.map { SettingsPickerOption(it, stringResource(layoutLabel(it))) },
            selectedValue = settings.layout, onOptionSelected = { settings.chooseLayout(it); close() }, onDismiss = ::close)
    }
}

@Composable
private fun WidgetTile(kind: LiveWidgetKind, width: Dp, requester: FocusRequester, first: Boolean, onChoose: () -> Unit, onDown: () -> Unit, onRail: () -> Unit,
    content: @Composable (wide: Boolean, roomy: Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val longPress = rememberLongPressKeyTracker()
    var held by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.width(width).fillMaxHeight().focusRequester(requester).fieldFocus(focused, TileShape).iptvPanel(TileShape)
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (longPress.handle(native, ::isSelect) { held = true; onChoose() }) {
                if (native.action == AndroidKeyEvent.ACTION_UP) held = false
                return@onPreviewKeyEvent true
            }
            when (native.keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { if (native.action == AndroidKeyEvent.ACTION_DOWN) onDown(); true }
                AndroidKeyEvent.KEYCODE_DPAD_UP -> true
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (first && native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) onRail()
                    first
                }
                AndroidKeyEvent.KEYCODE_MENU -> { if (native.action == AndroidKeyEvent.ACTION_DOWN) onChoose(); true }
                else -> if (isSelect(native.keyCode)) {
                    if (native.action == AndroidKeyEvent.ACTION_UP) { if (!held) onChoose(); held = false }
                    true
                } else false
            }
        }
        .focusable().padding(horizontal = 12.dp, vertical = 10.dp)) {
        val roomy = maxHeight >= 150.dp
        Column(Modifier.fillMaxSize().clipToBounds(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (kind != LiveWidgetKind.EMPTY || focused) Text(if (focused) stringResource(R.string.iptv_widgets_change) else stringResource(kindLabel(kind)).uppercase(),
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary)
            Box(Modifier.fillMaxWidth().weight(1f)) { content(width >= LiveWidgets.WIDE.dp, roomy) }
        }
    }
}

@Composable
private fun WidgetMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextTertiary, textAlign = TextAlign.Center, maxLines = 3,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyWidget() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
        Icon(Icons.Filled.Add, null, Modifier.size(22.dp), tint = NuvioTheme.colors.TextTertiary)
        Text(stringResource(R.string.iptv_widgets_add), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ClocksWidget(settings: IptvWidgetSettings, minute: Long, wide: Boolean) {
    val clocks = remember(settings.cities, minute / 60_000L) { LiveWidgets.clocks(settings.cities, minute, settings.deviceZone) }
    if (clocks.isEmpty()) { WidgetMessage(stringResource(R.string.iptv_widgets_no_cities)); return }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        clocks.forEach { clock -> ClockLine(clock, wide) }
    }
}

@Composable
private fun ClockLine(clock: WidgetClock, wide: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(if (clock.day) Icons.Filled.WbSunny else Icons.Filled.NightsStay, null, Modifier.size(14.dp),
            tint = if (clock.day) SunColour else NuvioTheme.colors.TextTertiary)
        Text(clock.city.name, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (clock.dayOffset != 0) Text(dayOffset(clock.dayOffset, wide), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
        Text(clock.time, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
    }
}

@Composable
private fun dayOffset(days: Int, wide: Boolean): String = when {
    !wide -> if (days > 0) "+$days" else "−${-days}"
    days > 0 -> pluralStringResource(R.plurals.iptv_widgets_days_ahead, days, days)
    else -> pluralStringResource(R.plurals.iptv_widgets_days_behind, -days, -days)
}

@Composable
private fun SportWidget(roomy: Boolean) {
    val viewModel: IptvSportsFixturesViewModel = hiltViewModel()
    val fixtures by viewModel.state.collectAsStateWithLifecycle()
    val games = remember(fixtures) { sportStrip(fixtures) }
    if (!fixtures.enabled) { WidgetMessage(stringResource(R.string.iptv_widgets_sport_off)); return }
    if (games.isEmpty()) { WidgetMessage(stringResource(R.string.iptv_widgets_no_games)); return }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        games.take(if (roomy) 3 else 2).forEach { item ->
            val fixture = item.fixture
            val hidden = fixtures.hidden(fixture)
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (fixture.status == FixtureStatus.LIVE) Box(Modifier.size(6.dp).clip(CircleShape).background(NuvioTheme.colors.Error))
                    Text(iptvScoreLine(fixture, hidden), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(iptvFixtureState(fixture, hidden), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun sportStrip(state: IptvFixturesState): List<IptvFixtureItem> {
    val followed = state.items.filter { it.favourite }
    val live = followed.filter { it.fixture.status == FixtureStatus.LIVE }
    val close = state.rows.filter { it.section == FixtureSection.CLOSE }.flatMap { it.items }
    val next = followed.filter { it.fixture.status == FixtureStatus.SCHEDULED }.sortedBy { it.fixture.startMillis }
    return (live + close + next).distinctBy { it.fixture.key }.take(3)
}

@Composable
private fun UpNextWidget(state: IptvLiveState, minute: Long, roomy: Boolean) {
    val row = state.focused
    val id = row?.item?.channel?.id
    val programmes = remember(id, state.guide, state.extraGuide, state.shortGuide, minute / 60_000L) {
        if (id == null) emptyList() else {
            val first = nextProgramme(state, id, liveProgramme(state, id, minute), minute)
            listOfNotNull(first, first?.let { nextProgramme(state, id, it, minute) })
        }
    }
    if (row == null) { WidgetMessage(stringResource(R.string.iptv_widgets_no_channel)); return }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(channelName(row), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (programmes.isEmpty()) Text(stringResource(R.string.iptv_widgets_no_guide), style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        programmes.forEach { programme -> UpNextLine(programme, roomy) }
    }
}

@Composable
private fun UpNextLine(programme: GuideProgramme, roomy: Boolean) {
    Column {
        Text(clock(programme.start.epochMillis), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.Secondary, maxLines = 1)
        Text(title(programme), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary,
            maxLines = if (roomy) 2 else 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RecordingsWidget(recordings: List<IptvRecording>, minute: Long, roomy: Boolean) {
    val now = remember(recordings) { recordings.filter { it.status == RecordingStatus.RECORDING }.sortedBy { it.stopMillis } }
    val next = remember(recordings, minute / 60_000L) {
        recordings.filter { it.status == RecordingStatus.SCHEDULED && it.stopMillis > minute }.minByOrNull { it.startMillis }
    }
    if (now.isEmpty() && next == null) { WidgetMessage(stringResource(R.string.iptv_widgets_no_recordings)); return }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        now.firstOrNull()?.let { recording ->
            val more = if (now.size > 1) " · " + stringResource(R.string.iptv_widgets_recording_more, now.size - 1) else ""
            RecordingLine(recording, true, stringResource(R.string.iptv_widgets_recording_now, clock(recording.stopMillis)) + more, roomy)
        }
        next?.let { recording ->
            RecordingLine(recording, false, stringResource(R.string.iptv_widgets_recording_next, "${sportDayLabel(recording.startMillis)} ${clock(recording.startMillis)}"), roomy)
        }
    }
}

@Composable
private fun RecordingLine(recording: IptvRecording, live: Boolean, label: String, roomy: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (live) Box(Modifier.size(6.dp).clip(CircleShape).background(NuvioTheme.colors.Error))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                color = if (live) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(recording.title ?: recording.channelName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (roomy && recording.title != null) Text(recording.channelName, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class StreamInfo(val resolution: String?, val codec: String?, val range: String?, val bitrate: String?)

private fun streamInfo(player: ExoPlayer?): StreamInfo? {
    val video = player?.videoFormat ?: return null
    val fps = video.frameRate.takeIf { it > 0 }?.let { " · ${kotlin.math.round(it).toInt()} fps" }.orEmpty()
    val range = when {
        video.sampleMimeType == "video/dolby-vision" -> "Dolby Vision"
        video.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
        video.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        else -> "SDR"
    }
    return StreamInfo(LiveWidgets.resolution(video.width, video.height)?.plus(fps), LiveWidgets.codec(video.sampleMimeType),
        range, LiveWidgets.bitrate(video.bitrate))
}

@Composable
private fun StreamWidget(player: ExoPlayer?, playing: Boolean, wide: Boolean) {
    val info by produceState(streamInfo(player), player) { while (true) { value = streamInfo(player); delay(STREAM_POLL_MILLIS) } }
    if (player == null) { WidgetMessage(stringResource(R.string.iptv_widgets_nothing_playing)); return }
    val shown = info
    if (shown == null || !playing && shown.resolution == null) { WidgetMessage(stringResource(R.string.iptv_widgets_stream_waiting)); return }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        listOf(R.string.iptv_widgets_stream_resolution to shown.resolution, R.string.iptv_widgets_stream_codec to shown.codec,
            R.string.iptv_widgets_stream_range to shown.range, R.string.iptv_widgets_stream_bitrate to shown.bitrate).forEach { (label, value) ->
            if (value != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (wide) Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1,
                    modifier = Modifier.width(56.dp), overflow = TextOverflow.Ellipsis)
                Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun WidgetChooser(settings: IptvWidgetSettings, slot: Int, sport: Boolean, onKind: (LiveWidgetKind) -> Unit, onCities: () -> Unit, onLayout: () -> Unit,
    onDismiss: () -> Unit) {
    val current = settings.kinds[slot]
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.iptv_widgets_choose_title),
        subtitle = stringResource(R.string.iptv_widgets_choose_subtitle, slot + 1), width = 560.dp) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LiveWidgetKind.entries.filter { sport || it != LiveWidgetKind.SPORT || it == current }.forEach { kind ->
                SettingsActionRow(title = stringResource(kindLabel(kind)), subtitle = stringResource(kindDescription(kind)), onClick = { onKind(kind) },
                    leadingIcon = kindIcon(kind), trailingIcon = if (kind == current) Icons.Filled.Check else null,
                    modifier = if (kind == current) Modifier.focusRequester(first) else Modifier)
            }
            if (current == LiveWidgetKind.CLOCKS) SettingsActionRow(title = stringResource(R.string.iptv_widgets_cities),
                subtitle = settings.cities.mapNotNull { LiveWidgets.city(it)?.name }.joinToString(" · ").ifEmpty { null }, onClick = onCities, leadingIcon = Icons.Filled.Public)
            SettingsActionRow(title = stringResource(R.string.iptv_widgets_layout), subtitle = null, value = stringResource(layoutLabel(settings.layout)),
                onClick = onLayout, leadingIcon = Icons.Filled.ViewModule)
        }
    }
}

@Composable
private fun CitiesDialog(settings: IptvWidgetSettings, minute: Long, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val clocks = remember(settings.cities, minute / 60_000L) { LiveWidgets.clocks(settings.cities, minute, settings.deviceZone) }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.iptv_widgets_kind_clocks), subtitle = stringResource(R.string.iptv_widgets_cities_subtitle), width = 560.dp) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            clocks.forEachIndexed { index, clock ->
                SettingsActionRow(title = clock.city.name, subtitle = if (clock.city.zone == settings.deviceZone) stringResource(R.string.iptv_widgets_city_device) else null,
                    value = clock.time, onClick = { settings.chooseCities(settings.cities.filter { LiveWidgets.city(it)?.id != clock.city.id }) },
                    leadingIcon = if (clock.day) Icons.Filled.WbSunny else Icons.Filled.NightsStay, trailingIcon = Icons.Filled.Close,
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier)
            }
            if (clocks.size < LiveWidgets.MAX_CITIES) SettingsActionRow(title = stringResource(R.string.iptv_widgets_city_add), subtitle = null, onClick = onAdd,
                leadingIcon = Icons.Filled.Add, modifier = if (clocks.isEmpty()) Modifier.focusRequester(first) else Modifier)
        }
        NuvioActionPill(onDismiss) { Text(stringResource(R.string.iptv_widgets_done)) }
    }
}

@Composable
private fun CityPicker(settings: IptvWidgetSettings, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, settings.cities) { LiveWidgets.search(query, settings.cities) }
    val first = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val shape = RoundedCornerShape(12.dp)
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.iptv_widgets_city_add), width = 560.dp) {
        BasicTextField(query, { query = it.take(40) }, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = NuvioTheme.colors.TextPrimary),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            cursorBrush = SolidColor(NuvioTheme.colors.TextPrimary),
            decorationBox = { inner ->
                Box { if (query.isEmpty()) Text(stringResource(R.string.iptv_widgets_city_search), color = NuvioTheme.colors.TextTertiary); inner() }
            },
            modifier = Modifier.fillMaxWidth().focusRequester(first).onFocusChanged { fieldFocused = it.isFocused }
                .fieldFocus(fieldFocused, shape).clip(shape).background(NuvioTheme.colors.TextPrimary.copy(alpha = .07f), shape)
                .padding(horizontal = 16.dp, vertical = 12.dp))
        if (results.isEmpty()) Text(stringResource(R.string.iptv_widgets_city_none), color = NuvioTheme.colors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(results, key = { it.id }) { city ->
                SettingsActionRow(title = city.name, subtitle = null, onClick = { onPick(city.id) }, trailingIcon = null)
            }
        }
    }
}

private fun kindLabel(kind: LiveWidgetKind): Int = when (kind) {
    LiveWidgetKind.CLOCKS -> R.string.iptv_widgets_kind_clocks
    LiveWidgetKind.SPORT -> R.string.iptv_widgets_kind_sport
    LiveWidgetKind.UP_NEXT -> R.string.iptv_widgets_kind_up_next
    LiveWidgetKind.RECORDINGS -> R.string.iptv_widgets_kind_recordings
    LiveWidgetKind.STREAM -> R.string.iptv_widgets_kind_stream
    LiveWidgetKind.EMPTY -> R.string.iptv_widgets_kind_empty
}

private fun kindDescription(kind: LiveWidgetKind): Int = when (kind) {
    LiveWidgetKind.CLOCKS -> R.string.iptv_widgets_kind_clocks_description
    LiveWidgetKind.SPORT -> R.string.iptv_widgets_kind_sport_description
    LiveWidgetKind.UP_NEXT -> R.string.iptv_widgets_kind_up_next_description
    LiveWidgetKind.RECORDINGS -> R.string.iptv_widgets_kind_recordings_description
    LiveWidgetKind.STREAM -> R.string.iptv_widgets_kind_stream_description
    LiveWidgetKind.EMPTY -> R.string.iptv_widgets_kind_empty_description
}

private fun kindIcon(kind: LiveWidgetKind): ImageVector = when (kind) {
    LiveWidgetKind.CLOCKS -> Icons.Filled.Public
    LiveWidgetKind.SPORT -> Icons.Filled.SportsSoccer
    LiveWidgetKind.UP_NEXT -> Icons.Filled.Schedule
    LiveWidgetKind.RECORDINGS -> Icons.Filled.FiberManualRecord
    LiveWidgetKind.STREAM -> Icons.Filled.Equalizer
    LiveWidgetKind.EMPTY -> Icons.Filled.CropFree
}

private fun layoutLabel(layout: LiveWidgetLayout): Int = when (layout) {
    LiveWidgetLayout.ONE -> R.string.iptv_widgets_layout_one
    LiveWidgetLayout.TWO -> R.string.iptv_widgets_layout_two
    LiveWidgetLayout.THREE -> R.string.iptv_widgets_layout_three
}

private val SunColour = Color(0xFFFFC14D)
private const val STREAM_POLL_MILLIS = 5_000L
