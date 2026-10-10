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
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureSection
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.LiveWidgetKind
import com.nuvio.tv.core.iptv.LiveStreamFacts
import com.nuvio.tv.core.iptv.LiveWidgetColumn
import com.nuvio.tv.core.iptv.LiveWidgetLayout
import com.nuvio.tv.core.iptv.LiveWidgets
import com.nuvio.tv.core.iptv.RecordingStatus
import com.nuvio.tv.core.iptv.StreamCell
import com.nuvio.tv.core.iptv.StreamCellKind
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
internal fun IptvWidgetRow(columns: List<LiveWidgetColumn>, settings: IptvWidgetSettings, state: IptvLiveState, modifier: Modifier, fits: (LiveWidgetLayout) -> Boolean,
    onDown: () -> Unit, onRail: () -> Unit, blocked: Boolean = false) {
    val minute by produceState(System.currentTimeMillis()) { while (true) { delay(LiveWidgets.nextMinute(System.currentTimeMillis())); value = System.currentTimeMillis() } }
    var dialog by remember { mutableStateOf<WidgetDialog?>(null) }
    var refocus by remember { mutableStateOf<Int?>(null) }
    val requesters = remember { List(LiveWidgets.MAX_SLOTS) { FocusRequester() } }
    val shown = columns.flatMap { it.slots }
    LaunchedEffect(dialog) {
        if (dialog != null) return@LaunchedEffect
        val slot = refocus ?: return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        refocus = null
        val target = slot.takeIf { it in shown } ?: shown.firstOrNull() ?: return@LaunchedEffect
        requesters.getOrNull(target)?.let { runCatching { it.requestFocus() } }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(LiveWidgets.GAP.dp)) {
        columns.forEachIndexed { column, part ->
            Column(Modifier.width(part.width.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(LiveWidgets.GAP.dp)) {
                part.slots.forEachIndexed { position, index ->
                    val kind = settings.kinds[index]
                    WidgetTile(kind, part.width.dp, requesters[index], Modifier.fillMaxWidth().weight(1f), first = column == 0, blocked = blocked,
                        above = part.slots.getOrNull(position - 1)?.let { requesters[it] }, below = part.slots.getOrNull(position + 1)?.let { requesters[it] },
                        onChoose = { dialog = WidgetDialog(index, WidgetStep.CHOOSE) }, onDown = onDown, onRail = onRail) { wide, roomy, short ->
                        when (kind) {
                            LiveWidgetKind.CLOCKS -> ClocksWidget(settings, minute, wide)
                            LiveWidgetKind.SPORT -> if (state.sportEnabled) SportWidget(roomy, short) else WidgetMessage(stringResource(R.string.iptv_widgets_sport_off))
                            LiveWidgetKind.UP_NEXT -> UpNextWidget(state, minute, roomy, short)
                            LiveWidgetKind.RECORDINGS -> RecordingsWidget(state.recordings, minute, roomy, short)
                            LiveWidgetKind.STREAM -> StreamWidget(state.player, state.playing)
                            LiveWidgetKind.EMPTY -> EmptyWidget()
                        }
                    }
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
            options = LiveWidgetLayout.entries.map { layout ->
                SettingsPickerOption(layout, stringResource(layoutLabel(layout)), description = if (fits(layout)) null else stringResource(R.string.iptv_ui10_layout_partial))
            },
            selectedValue = settings.layout, onOptionSelected = { settings.chooseLayout(it); close() }, onDismiss = ::close)
    }
}

@Composable
private fun WidgetTile(kind: LiveWidgetKind, width: Dp, requester: FocusRequester, modifier: Modifier, first: Boolean, blocked: Boolean, above: FocusRequester?, below: FocusRequester?,
    onChoose: () -> Unit, onDown: () -> Unit, onRail: () -> Unit, content: @Composable (wide: Boolean, roomy: Boolean, short: Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val longPress = rememberLongPressKeyTracker()
    var held by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.focusRequester(requester).fieldFocus(focused, TileShape).iptvPanel(TileShape)
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (longPress.handle(native, ::isSelect) { held = true; onChoose() }) {
                if (native.action == AndroidKeyEvent.ACTION_UP) held = false
                return@onPreviewKeyEvent true
            }
            when (native.keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (native.action == AndroidKeyEvent.ACTION_DOWN) { if (below != null) runCatching { below.requestFocus() } else onDown() }
                    true
                }
                AndroidKeyEvent.KEYCODE_DPAD_UP -> { if (native.action == AndroidKeyEvent.ACTION_DOWN && above != null) runCatching { above.requestFocus() }; true }
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
        .focusable(enabled = !blocked).padding(horizontal = 12.dp, vertical = 10.dp)) {
        val roomy = maxHeight >= 150.dp
        val short = maxHeight < 110.dp
        Column(Modifier.fillMaxSize().clipToBounds(), verticalArrangement = Arrangement.spacedBy(if (short) 4.dp else 6.dp)) {
            if (kind != LiveWidgetKind.EMPTY || focused) Text(if (focused) stringResource(R.string.iptv_widgets_change) else stringResource(kindLabel(kind)).uppercase(),
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary)
            Box(Modifier.fillMaxWidth().weight(1f)) { content(width >= LiveWidgets.WIDE.dp, roomy, short) }
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fit = (maxHeight / 24.dp).toInt().coerceAtLeast(1)
        val narrow = maxWidth < 120.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            clocks.take(fit).forEach { clock -> ClockLine(clock, wide, narrow) }
        }
    }
}

@Composable
private fun ClockLine(clock: WidgetClock, wide: Boolean, narrow: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!narrow) Icon(if (clock.day) Icons.Filled.WbSunny else Icons.Filled.NightsStay, null, Modifier.size(14.dp),
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
private fun SportWidget(roomy: Boolean, short: Boolean) {
    val viewModel: IptvSportsFixturesViewModel = hiltViewModel()
    val fixtures by viewModel.state.collectAsStateWithLifecycle()
    val games = remember(fixtures) { sportStrip(fixtures) }
    if (!fixtures.enabled) { WidgetMessage(stringResource(R.string.iptv_widgets_sport_off)); return }
    if (games.isEmpty()) { WidgetMessage(stringResource(R.string.iptv_widgets_no_games)); return }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        games.take(if (roomy) 3 else if (short) 1 else 2).forEach { item ->
            val fixture = item.fixture
            val hidden = fixtures.hidden(fixture)
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardState(fixture, fixture.sportDetail, hidden)
                    Text(sportLeagueName(fixture), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Text(iptvScoreLine(fixture, hidden), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun UpNextWidget(state: IptvLiveState, minute: Long, roomy: Boolean, short: Boolean) {
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
        programmes.take(if (short) 1 else 2).forEach { programme -> UpNextLine(programme, roomy) }
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
private fun RecordingsWidget(recordings: List<IptvRecording>, minute: Long, roomy: Boolean, short: Boolean) {
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
        next?.takeIf { !short || now.isEmpty() }?.let { recording ->
            RecordingLine(recording, false, stringResource(R.string.iptv_widgets_recording_next, "${sportDayLabel(recording.startMillis)} ${clock(recording.startMillis)}"), roomy)
        }
    }
}

@Composable
private fun RecordingLine(recording: IptvRecording, live: Boolean, label: String, roomy: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                color = if (live) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(recording.title ?: recording.channelName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (roomy && recording.title != null) Text(recording.channelName, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun streamFacts(player: ExoPlayer?): LiveStreamFacts? {
    val video = player?.videoFormat ?: return null
    val audio = player.audioFormat
    val counters = player.videoDecoderCounters?.also { it.ensureUpdated() }
    return LiveStreamFacts(video.width, video.height, video.frameRate, video.sampleMimeType, video.codecs, video.colorInfo?.colorTransfer ?: 0,
        video.bitrate.takeIf { it > 0 } ?: video.peakBitrate, audio?.sampleMimeType, audio?.channelCount ?: 0, audio?.language,
        video.containerMimeType ?: player.currentMediaItem?.localConfiguration?.mimeType, player.totalBufferedDuration, counters?.droppedBufferCount ?: -1)
}

@Composable
private fun StreamWidget(player: ExoPlayer?, playing: Boolean) {
    val facts by produceState(streamFacts(player), player) { while (true) { value = streamFacts(player); delay(STREAM_POLL_MILLIS) } }
    if (player == null) { WidgetMessage(stringResource(R.string.iptv_widgets_nothing_playing)); return }
    val shown = facts
    if (shown == null || !playing && shown.width <= 0) { WidgetMessage(stringResource(R.string.iptv_widgets_stream_waiting)); return }
    val language = shown.language?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
        runCatching { java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.getDefault()) }.getOrNull()?.takeIf { it.isNotBlank() } ?: code
    }
    val cells = LiveWidgets.streamCells(shown, language)
    val badges = LiveWidgets.badges(shown)
    val headline = listOfNotNull(LiveWidgets.resolution(shown.width, shown.height), LiveWidgets.frameRate(shown.frameRate)).joinToString(" · ")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val plan = LiveWidgets.streamLayout(maxWidth.value.toInt(), maxHeight.value.toInt(), cells.size, badges.isNotEmpty())
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LiveWidgets.STREAM_SPACING.dp)) {
            if (plan.badgeRow) Row(Modifier.height(LiveWidgets.STREAM_BADGES.dp).clipToBounds(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) { badges.forEach { StreamBadge(it) } }
            Row(Modifier.height(LiveWidgets.STREAM_HEADLINE.dp).clipToBounds(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!plan.badgeRow) badges.forEach { StreamBadge(it) }
                if (headline.isNotEmpty()) Text(headline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            cells.take(plan.cells).chunked(plan.columns).forEach { line ->
                Row(Modifier.fillMaxWidth().height(LiveWidgets.STREAM_LINE.dp), horizontalArrangement = Arrangement.spacedBy(LiveWidgets.GAP.dp)) {
                    line.forEach { cell -> StreamCellText(cell, plan.labels, Modifier.weight(1f)) }
                    repeat(plan.columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun StreamCellText(cell: StreamCell, labels: Boolean, modifier: Modifier) {
    val label = stringResource(streamLabel(cell.kind))
    Row(modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (labels) Text(label, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1, softWrap = false,
            modifier = Modifier.width(STREAM_LABEL_WIDTH), overflow = TextOverflow.Ellipsis)
        cell.health?.let { health ->
            Box(Modifier.size(6.dp).clip(CircleShape).background(when (health) {
                2 -> NuvioTheme.colors.Success
                1 -> NuvioTheme.colors.Warning
                else -> NuvioTheme.colors.Error
            }))
        }
        Text(if (labels || cell.health == null) cell.value else "$label ${cell.value}", style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

private fun streamLabel(kind: StreamCellKind): Int = when (kind) {
    StreamCellKind.VIDEO -> R.string.iptv_ui10_stream_video
    StreamCellKind.AUDIO -> R.string.iptv_ui10_stream_audio
    StreamCellKind.BITRATE -> R.string.stream_info_bitrate
    StreamCellKind.BUFFER -> R.string.iptv_ui10_stream_buffer
    StreamCellKind.LANGUAGE -> R.string.stream_info_language
    StreamCellKind.DROPPED -> R.string.iptv_ui10_stream_dropped
    StreamCellKind.FORMAT -> R.string.iptv_ui10_stream_format
}

@Composable
private fun StreamBadge(text: String) {
    val hdr = text == "HDR10" || text == "HLG" || text == "DV"
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1,
        color = if (hdr) Color.Black else NuvioTheme.colors.TextPrimary,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (hdr) SunColour else NuvioTheme.colors.TextPrimary.copy(alpha = .14f))
            .padding(horizontal = 6.dp, vertical = 2.dp))
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
    LiveWidgetLayout.TALL -> R.string.iptv_ui10_layout_tall
    LiveWidgetLayout.SQUARE -> R.string.iptv_ui10_layout_square
    LiveWidgetLayout.ONE -> R.string.iptv_widgets_layout_one
    LiveWidgetLayout.STACKED -> R.string.iptv_ui10_layout_stacked
    LiveWidgetLayout.TWO -> R.string.iptv_ui10_layout_two
    LiveWidgetLayout.THREE -> R.string.iptv_ui10_layout_three
    LiveWidgetLayout.SQUARES -> R.string.iptv_ui10_layout_squares
    LiveWidgetLayout.FOUR -> R.string.iptv_ui10_layout_four
}

private val SunColour = Color(0xFFFFC14D)
private const val STREAM_POLL_MILLIS = 2_000L
private val STREAM_LABEL_WIDTH = 50.dp
