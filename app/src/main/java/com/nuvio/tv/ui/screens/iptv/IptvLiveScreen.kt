@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.PlayerControlAction
import com.nuvio.tv.data.local.PlayerControlLayout
import com.nuvio.tv.core.iptv.GuideMatchReason
import com.nuvio.tv.data.iptv.IptvGuideRef
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.data.iptv.IptvStreamFormat
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.V2Atmosphere
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun IptvLiveScreen(onBack: () -> Unit, onSources: () -> Unit, viewModel: IptvLiveViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var fullscreen by remember { mutableStateOf(false) }
    var railOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    var showTracks by remember(state.player) { mutableStateOf(false) }
    var showHud by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<IptvListedChannel?>(null) }
    var formatFor by remember { mutableStateOf<IptvListedChannel?>(null) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(15_000); value = System.currentTimeMillis() } }
    var cursor by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var viewStart by remember { mutableLongStateOf(Math.floorDiv(System.currentTimeMillis(), SLOT) * SLOT) }
    val rowFocus = remember(state.source, state.category, state.favourites) { mutableMapOf<String, FocusRequester>() }
    val guideList = remember(state.source, state.category, state.favourites, state.search) { androidx.compose.foundation.lazy.LazyListState() }
    val scope = rememberCoroutineScope()
    val railFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.foreground(true)
            if (event == Lifecycle.Event.ON_STOP) viewModel.foreground(false)
        }
        lifecycle.addObserver(observer)
        viewModel.foreground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); viewModel.foreground(false) }
    }
    LaunchedEffect(state.player, state.tuning) { if (state.player == null && !state.tuning) fullscreen = false }
    LaunchedEffect(state.message) { if (state.message != null) { delay(6_000); viewModel.clearMessage() } }
    suspend fun focusGridNow() {
        val id = state.focused?.item?.channel?.id ?: state.channels.firstOrNull()?.item?.channel?.id ?: return
        val index = state.channels.indexOfFirst { it.item.channel.id == id }
        if (index >= 0 && guideList.layoutInfo.visibleItemsInfo.none { it.index == index }) guideList.scrollToItem((index - 2).coerceAtLeast(0))
        repeat(2) { withFrameNanos { } }
        rowFocus[id]?.let { runCatching { it.requestFocus() } }
    }
    fun focusGrid() { scope.launch { focusGridNow() } }
    BackHandler {
        when {
            fullscreen -> fullscreen = false
            searching -> { searching = false; viewModel.search("") }
            railOpen -> { railOpen = false; focusGrid() }
            !railOpen && (state.channels.isNotEmpty() || state.search.isNotEmpty()) -> railOpen = true
            else -> onBack()
        }
    }
    LaunchedEffect(railOpen) { if (railOpen) { withFrameNanos { }; runCatching { railFocus.requestFocus() } } }
    LaunchedEffect(searching) { if (searching) { withFrameNanos { }; runCatching { searchFocus.requestFocus() } } }
    LaunchedEffect(state.source, state.category, state.favourites, state.channels.firstOrNull()?.item?.channel?.id, fullscreen) {
        if (!fullscreen && !railOpen && !searching && state.channels.isNotEmpty()) { withFrameNanos { }; focusGridNow() }
    }
    val empty = emptyState(state)
    LaunchedEffect(empty) { if (empty != null && !railOpen && !searching) { withFrameNanos { }; runCatching { emptyFocus.requestFocus() } } }

    if (fullscreen) {
        val playingRow = state.channels.firstOrNull { it.item.channel.id == state.playingId }
        val current = playingRow?.let { liveProgramme(state, it.item.channel.id, now) }
        val v2 = LocalV2Appearance.current != null
        FullscreenLive(state, now, showHud, onZap = viewModel::zap, onMenu = { playingRow?.let { menuFor = it } },
            onLastChannel = viewModel::lastChannel, onNumber = viewModel::watchNumber, onWatch = { viewModel.watch(it) },
            layout = remember(state.controlLayout, v2) { PlayerControlLayout.effective(state.controlLayout, v2) },
            canStartOver = playingRow != null && current != null && hasArchive(playingRow),
            onControl = { action ->
                val player = state.player
                when (action) {
                    PlayerControlAction.PLAY_PAUSE -> { player?.let { it.playWhenReady = !it.playWhenReady } }
                    PlayerControlAction.RESTART -> { if (state.catchup != null) player?.seekTo(0) else if (playingRow != null && current != null) viewModel.watch(playingRow, current) }
                    PlayerControlAction.STATS -> { showHud = !showHud }
                    PlayerControlAction.AUDIO, PlayerControlAction.SUBTITLES -> { showTracks = true }
                    else -> Unit
                }
            })
    } else {
        Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
            LocalV2Appearance.current?.let { V2Atmosphere(rich = false, background = it.settingsBackground) }
            Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth().height(188.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    InfoPanel(state, now, cursor, Modifier.weight(1f).fillMaxHeight())
                    Preview(state, Modifier.fillMaxHeight().aspectRatio(16f / 9f))
                }
                if (searching) SearchField(state.search, searchFocus, onChange = viewModel::search, onDone = { focusGrid() })
                if (empty != null) {
                    EmptyPanel(empty, state, emptyFocus, Modifier.fillMaxWidth().weight(1f), onSources = onSources,
                        onRefresh = viewModel::refreshSource, onAll = { viewModel.showCategory(null) }, onRail = { railOpen = true })
                } else {
                    GuideGrid(state, guideList, now, cursor, viewStart, rowFocus, heading(state), Modifier.fillMaxWidth().weight(1f),
                        onCursor = { time, start -> cursor = time; viewStart = start },
                        onRail = { railOpen = true },
                        onFocus = viewModel::focus,
                        onSelect = { row ->
                            val programme = programmeAt(state.guide[row.item.channel.id], cursor)
                            val past = programme != null && (programme.stop?.epochMillis ?: Long.MAX_VALUE) <= now && hasArchive(row)
                            val target = if (past) programme else null
                            if (row.item.channel.id == state.playingId && state.player != null && state.catchup == target) fullscreen = true
                            else viewModel.watch(row, target)
                        },
                        onMenu = { menuFor = it },
                        onNearEnd = viewModel::loadMore)
                }
            }
            AnimatedVisibility(railOpen, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)))
            }
            AnimatedVisibility(railOpen, Modifier.align(Alignment.CenterStart),
                enter = fadeIn() + slideInHorizontally { -it / 3 }, exit = fadeOut() + slideOutHorizontally { -it / 3 }) {
                CategoryRail(state, railFocus, Modifier.padding(start = 24.dp, top = 20.dp, bottom = 20.dp).width(340.dp).fillMaxHeight(),
                    onFavourites = { viewModel.showFavourites(); railOpen = false; focusGrid() },
                    onCategory = { viewModel.showCategory(it); railOpen = false; focusGrid() },
                    onSource = { viewModel.showSource(it); railOpen = false; focusGrid() },
                    onSources = { railOpen = false; onSources() },
                    onSearch = { railOpen = false; searching = true },
                    onHide = viewModel::toggleHidden,
                    onClose = { railOpen = false; focusGrid() })
            }
        }
    }
    if (showTracks) state.player?.let { IptvTrackDialog(it) { showTracks = false } }
    menuFor?.let { row ->
        ChannelMenu(row, state, onDismiss = { menuFor = null },
            onWatch = { menuFor = null; if (row.item.channel.id == state.playingId && state.player != null) fullscreen = true else viewModel.watch(row) },
            onFromStart = { programme -> menuFor = null; viewModel.watch(row, programme) },
            onLive = { menuFor = null; viewModel.watch(row) },
            onFavourite = { viewModel.focus(row); viewModel.toggleFavourite(); menuFor = null },
            onGuide = { viewModel.openGuidePicker(row); menuFor = null },
            onFormat = { formatFor = row; menuFor = null },
            onTracks = { showTracks = true; menuFor = null },
            onHud = { showHud = !showHud; menuFor = null },
            onStop = { viewModel.stop(); menuFor = null },
            onSources = { menuFor = null; onSources() },
            onMove = { delta -> menuFor = null; viewModel.moveFavourite(row, delta) })
    }
    state.guidePicker?.let { picker ->
        GuidePickerDialog(picker, onAutomatic = { viewModel.chooseGuideChannel(null) }, onFeed = viewModel::pickGuideFeed,
            onSearch = viewModel::searchGuide, onChannel = { viewModel.chooseGuideChannel(it) }, onDismiss = viewModel::closeGuidePicker)
    }
    formatFor?.let { channel ->
        SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_live_format_title),
            subtitle = stringResource(R.string.iptv_live_format_description),
            options = IptvStreamFormat.entries.map { SettingsPickerOption(it, stringResource(formatLabel(it))) },
            selectedValue = channel.item.overlay.streamFormat,
            onOptionSelected = { viewModel.setStreamFormat(channel, it); formatFor = null },
            onDismiss = { formatFor = null })
    }
}

private enum class EmptyKind { NO_SOURCES, REFRESHING, NO_CHANNELS, NO_FAVOURITES, NO_RESULTS, FAILED }

private fun emptyState(state: IptvLiveState): EmptyKind? {
    if (state.channels.isNotEmpty() || !state.loaded || state.loading) return null
    if (state.sources.isEmpty()) return EmptyKind.NO_SOURCES
    if (state.search.isNotBlank()) return EmptyKind.NO_RESULTS
    if (state.favourites) return EmptyKind.NO_FAVOURITES
    val status = state.source?.let { state.refresh[IptvRefreshCoordinator.key(it)] }
    if (status?.running == true) return EmptyKind.REFRESHING
    return if (status?.phase == IptvRefreshPhase.FAILED) EmptyKind.FAILED else EmptyKind.NO_CHANNELS
}

@Composable
private fun heading(state: IptvLiveState): String {
    if (state.search.isNotBlank()) return stringResource(R.string.iptv_live_results, state.search.trim())
    val name = when {
        state.favourites -> stringResource(R.string.iptv_live_favourites)
        state.category != null -> state.category.ifEmpty { stringResource(R.string.iptv_live_uncategorised) }
        else -> stringResource(R.string.iptv_live_all)
    }
    val count = if (state.favourites) null else state.category?.let { category -> state.categories.firstOrNull { it.name == category }?.channels }
        ?: state.categories.filter { it.name !in state.hiddenCategories }.sumOf { it.channels }.takeIf { it > 0 }
    return if (count == null) name else "$name · " + pluralStringResource(R.plurals.iptv_source_channels, count, count)
}

@Composable
private fun EmptyPanel(kind: EmptyKind, state: IptvLiveState, focus: FocusRequester, modifier: Modifier, onSources: () -> Unit,
    onRefresh: () -> Unit, onAll: () -> Unit, onRail: () -> Unit) {
    val source = state.sources.firstOrNull { it.ref == state.source }?.label.orEmpty()
    val status = state.source?.let { state.refresh[IptvRefreshCoordinator.key(it)] }
    Column(modifier.iptvPanel().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        if (kind == EmptyKind.NO_RESULTS) Icon(Icons.Filled.Search, null, Modifier.size(48.dp), tint = NuvioTheme.colors.TextTertiary)
        else if (kind == EmptyKind.REFRESHING) LoadingIndicator(Modifier.size(48.dp))
        else Icon(if (kind == EmptyKind.NO_FAVOURITES) Icons.Filled.StarBorder else Icons.Filled.LiveTv, null,
            Modifier.size(48.dp), tint = NuvioTheme.colors.TextTertiary)
        Text(when (kind) {
            EmptyKind.NO_SOURCES -> stringResource(R.string.iptv_live_setup_title)
            EmptyKind.REFRESHING -> stringResource(when (status?.phase) {
                IptvRefreshPhase.SAVING -> R.string.iptv_refresh_saving
                IptvRefreshPhase.GUIDE -> R.string.iptv_refresh_guide
                IptvRefreshPhase.QUEUED -> R.string.iptv_refresh_queued
                else -> R.string.iptv_refresh_downloading
            })
            EmptyKind.NO_FAVOURITES -> stringResource(R.string.iptv_live_no_favourites)
            EmptyKind.NO_RESULTS -> stringResource(R.string.iptv_live_no_results, state.search.trim())
            EmptyKind.FAILED -> stringResource(R.string.iptv_live_source_failed, source)
            EmptyKind.NO_CHANNELS -> stringResource(R.string.iptv_live_no_channels, source)
        }, style = MaterialTheme.typography.headlineSmall, color = NuvioTheme.colors.TextPrimary, textAlign = TextAlign.Center)
        Text(when (kind) {
            EmptyKind.NO_SOURCES -> stringResource(R.string.iptv_live_setup_description)
            EmptyKind.REFRESHING -> stringResource(R.string.iptv_live_refreshing_description)
            EmptyKind.NO_FAVOURITES -> stringResource(R.string.iptv_live_no_favourites_description)
            EmptyKind.NO_RESULTS -> stringResource(R.string.iptv_live_no_results_description)
            EmptyKind.FAILED -> status?.message?.let { stringResource(it) } ?: stringResource(R.string.iptv_setup_failed)
            EmptyKind.NO_CHANNELS -> stringResource(R.string.iptv_live_no_channels_description)
        }, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp))
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (kind) {
                EmptyKind.NO_SOURCES -> NuvioActionPill(onSources, Modifier.focusRequester(focus)) { Text(stringResource(R.string.iptv_live_add_source)) }
                EmptyKind.NO_FAVOURITES -> NuvioActionPill(onAll, Modifier.focusRequester(focus)) { Text(stringResource(R.string.iptv_live_all)) }
                EmptyKind.NO_RESULTS -> Unit
                EmptyKind.REFRESHING -> NuvioActionPill(onSources, Modifier.focusRequester(focus)) { Text(stringResource(R.string.iptv_sources_manage)) }
                EmptyKind.FAILED, EmptyKind.NO_CHANNELS -> {
                    NuvioActionPill(onRefresh, Modifier.focusRequester(focus)) { Text(stringResource(R.string.iptv_setup_refresh)) }
                    NuvioActionPill(onSources) { Text(stringResource(R.string.iptv_sources_manage)) }
                }
            }
            if (kind != EmptyKind.NO_SOURCES && kind != EmptyKind.NO_RESULTS && (state.sources.size > 1 || state.categories.isNotEmpty())) {
                NuvioActionPill(onRail) { Text(stringResource(R.string.iptv_live_browse)) }
            }
        }
    }
}

@Composable
private fun Preview(state: IptvLiveState, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.clip(shape).background(Color.Black, shape).border(1.dp, NuvioTheme.colors.TextPrimary.copy(alpha = .12f), shape)) {
        if (state.player != null || state.tuning) LiveVideo(state.player, null, Modifier.fillMaxSize())
        else {
            val row = state.focused
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (row != null) ChannelLogo(logoUrl(row), channelName(row), Modifier.size(96.dp, 56.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp), tint = Color.White.copy(alpha = .7f))
                    Text(stringResource(if (row == null) R.string.iptv_live_choose else R.string.iptv_live_press_ok), style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = .7f))
                }
            }
        }
        PlaybackState(state, Modifier.align(Alignment.Center), large = false)
        ReconnectingPill(state, Modifier.align(Alignment.TopStart).padding(10.dp))
        if (state.playing) Tag(stringResource(if (state.catchup != null) R.string.iptv_live_catchup else R.string.iptv_live_playing),
            Modifier.align(Alignment.TopEnd).padding(10.dp), live = state.catchup == null)
    }
}

@Composable
private fun InfoPanel(state: IptvLiveState, now: Long, cursor: Long, modifier: Modifier) {
    val row = state.focused
    val programme = row?.let { liveProgramme(state, it.item.channel.id, cursor) ?: liveProgramme(state, it.item.channel.id, now) }
    val index = row?.let { state.channels.indexOf(it) } ?: -1
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(listOfNotNull(stringResource(R.string.iptv_live_title), state.sources.firstOrNull { it.ref == state.source }?.label).joinToString(" · ").uppercase(),
                style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val notice = state.message ?: state.updating
            if (notice != null) Row(Modifier.clip(RoundedCornerShape(12.dp)).background(NuvioTheme.colors.TextPrimary.copy(alpha = .08f))
                .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.message == null) LoadingIndicator(Modifier.size(14.dp))
                Text(stringResource(notice), style = MaterialTheme.typography.labelSmall,
                    color = if (state.message != null) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 420.dp))
            }
            Text(clock(now), style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary)
        }
        if (row == null) {
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.iptv_live_choose), style = MaterialTheme.typography.headlineSmall, color = NuvioTheme.colors.TextSecondary)
            Spacer(Modifier.weight(1f))
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChannelLogo(logoUrl(row), channelName(row), Modifier.size(64.dp, 38.dp))
            if (index >= 0) Text("${index + 1}", style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextTertiary)
            Text(channelName(row), style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (programme != null && airing(programme, now)) Tag(stringResource(R.string.iptv_live_playing), live = true)
            if (hasArchive(row)) Tag(stringResource(R.string.iptv_live_catchup))
        }
        Text(programme?.let(::title) ?: stringResource(R.string.iptv_live_no_programme), style = MaterialTheme.typography.headlineSmall,
            color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        programme?.let { item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(timeRange(item), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                if (airing(item, now)) progress(item, now)?.let { ProgressLine(it, Modifier.width(200.dp)) }
                if (airing(item, now)) item.stop?.epochMillis?.let { stop -> Text(stringResource(R.string.iptv_live_minutes_left, ((stop - now) / MINUTE_MILLIS).toInt() + 1),
                    color = NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.bodyMedium) }
                if (item.start.epochMillis > now) Text(stringResource(R.string.iptv_live_starts_in, ((item.start.epochMillis - now) / MINUTE_MILLIS).toInt() + 1),
                    color = NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.bodyMedium)
            }
            description(item)?.let { Text(it, color = NuvioTheme.colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.iptv_live_guide_hint), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
    }
}

@Composable
private fun CategoryRail(state: IptvLiveState, first: FocusRequester, modifier: Modifier, onFavourites: () -> Unit, onCategory: (String?) -> Unit,
    onSource: (com.nuvio.tv.data.iptv.IptvSourceRef) -> Unit, onSources: () -> Unit, onSearch: () -> Unit, onHide: (String) -> Unit, onClose: () -> Unit) {
    Column(modifier.iptvPanel(role = GlassRole.NAVIGATION).padding(vertical = 14.dp, horizontal = 10.dp)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) { onClose(); true } else false
        }) {
        Text(stringResource(R.string.iptv_live_title), style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { RailItem(stringResource(R.string.iptv_live_search), null, state.search.isNotBlank(), Modifier, onSearch, Icons.Filled.Search) }
            item { RailItem(stringResource(R.string.iptv_live_favourites), null, state.favourites, Modifier.focusRequester(first), onFavourites, Icons.Filled.Star) }
            item { RailItem(stringResource(R.string.iptv_live_all), state.categories.filter { it.name !in state.hiddenCategories }.sumOf { it.channels }.takeIf { it > 0 }, !state.favourites && state.category == null,
                Modifier, { onCategory(null) }, Icons.AutoMirrored.Filled.List) }
            if (state.categories.isNotEmpty()) item { SectionLabel(stringResource(R.string.iptv_live_categories)) }
            if (state.categories.isNotEmpty()) item { Text(stringResource(R.string.iptv_live_hide_hint), style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextTertiary, modifier = Modifier.padding(start = 10.dp, bottom = 4.dp)) }
            items(state.categories.filter { it.name !in state.hiddenCategories }, key = { "category-${it.name}" }) { category ->
                RailItem(category.name.ifEmpty { stringResource(R.string.iptv_live_uncategorised) }, category.channels,
                    !state.favourites && state.category == category.name, Modifier, { onCategory(category.name) }, onHold = { onHide(category.name) })
            }
            val hidden = state.categories.filter { it.name in state.hiddenCategories }
            if (hidden.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.iptv_live_hidden_categories)) }
                items(hidden, key = { "category-${it.name}" }) { category ->
                    RailItem(category.name.ifEmpty { stringResource(R.string.iptv_live_uncategorised) }, category.channels,
                        !state.favourites && state.category == category.name, Modifier, { onCategory(category.name) }, onHold = { onHide(category.name) }, dim = true)
                }
            }
            if (state.sources.size > 1) {
                item { SectionLabel(stringResource(R.string.iptv_live_sources)) }
                items(state.sources, key = { "source-${it.ref.sourceId}" }) { source ->
                    RailItem(source.label, null, source.ref == state.source, Modifier, { onSource(source.ref) }, Icons.Filled.LiveTv)
                }
            }
            item { Spacer(Modifier.height(6.dp)) }
            item { RailItem(stringResource(R.string.iptv_sources_manage), null, false, Modifier, onSources, Icons.Filled.Settings) }
        }
    }
}

@Composable
private fun RailItem(text: String, count: Int?, selected: Boolean, modifier: Modifier, onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onHold: (() -> Unit)? = null, dim: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val longPress = com.nuvio.tv.ui.util.rememberLongPressKeyTracker()
    var held by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth()
        .onFocusChanged { focused = it.isFocused }
        .iptvItem(focused, selected)
        .graphicsLayer { alpha = if (dim && !focused) .55f else 1f }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (onHold != null && longPress.handle(native, ::isSelect) { held = true; onHold() }) {
                if (native.action == AndroidKeyEvent.ACTION_UP) held = false
                return@onPreviewKeyEvent true
            }
            if (onHold != null && native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_MENU) { onHold(); return@onPreviewKeyEvent true }
            if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) { if (!held) onClick(); held = false; true } else false
        }
        .focusable().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = if (selected || focused) itemContent(focused) else NuvioTheme.colors.TextTertiary)
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
            color = if (focused || selected) itemContent(focused) else NuvioTheme.colors.TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (count != null) Text("$count", style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary)
        if (selected) Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(NuvioTheme.colors.Secondary))
    }
}

@Composable
private fun ChannelMenu(row: IptvListedChannel, state: IptvLiveState, onDismiss: () -> Unit, onWatch: () -> Unit,
    onFromStart: (com.nuvio.tv.core.iptv.GuideProgramme) -> Unit, onLive: () -> Unit, onFavourite: () -> Unit,
    onGuide: () -> Unit, onFormat: () -> Unit, onTracks: () -> Unit, onHud: () -> Unit, onStop: () -> Unit, onSources: () -> Unit,
    onMove: (Int) -> Unit) {
    val playing = state.player != null && row.item.channel.id == state.playingId
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    NuvioDialog(onDismiss = onDismiss, title = channelName(row), subtitle = liveProgramme(state, row.item.channel.id, System.currentTimeMillis())?.let(::title),
        width = 560.dp) {
        Column(Modifier.weight(1f, fill = false).verticalScrollable(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SettingsActionRow(title = stringResource(if (playing) R.string.iptv_live_fullscreen else R.string.iptv_live_watch), subtitle = null,
                onClick = onWatch, leadingIcon = if (playing) Icons.Filled.Fullscreen else Icons.Filled.PlayArrow, trailingIcon = null,
                modifier = Modifier.focusRequester(first))
            val current = liveProgramme(state, row.item.channel.id, System.currentTimeMillis())
            if (playing && state.catchup != null) SettingsActionRow(title = stringResource(R.string.iptv_live_return), subtitle = null,
                onClick = onLive, leadingIcon = Icons.Filled.LiveTv, trailingIcon = null)
            if (current != null && hasArchive(row)) SettingsActionRow(title = stringResource(R.string.iptv_live_from_start), subtitle = title(current),
                onClick = { onFromStart(current) }, leadingIcon = Icons.Filled.Replay, trailingIcon = null)
            SettingsActionRow(title = stringResource(if (row.item.overlay.favouriteRank == null) R.string.iptv_live_add_favourite else R.string.iptv_live_remove_favourite),
                subtitle = null, onClick = onFavourite, leadingIcon = if (row.item.overlay.favouriteRank == null) Icons.Filled.StarBorder else Icons.Filled.Star, trailingIcon = null)
            if (state.favourites && state.search.isBlank() && row.item.overlay.favouriteRank != null) {
                val index = state.channels.indexOf(row)
                if (index > 0) SettingsActionRow(title = stringResource(R.string.iptv_live_favourite_up), subtitle = null, onClick = { onMove(-1) },
                    leadingIcon = Icons.Filled.KeyboardArrowUp, trailingIcon = null)
                if (index in 0 until state.channels.lastIndex) SettingsActionRow(title = stringResource(R.string.iptv_live_favourite_down), subtitle = null,
                    onClick = { onMove(1) }, leadingIcon = Icons.Filled.KeyboardArrowDown, trailingIcon = null)
            }
            SettingsActionRow(title = stringResource(R.string.iptv_guide_pick_title), subtitle = stringResource(matchLabel(row.guide.reason)),
                onClick = onGuide, leadingIcon = Icons.Filled.Schedule)
            SettingsActionRow(title = stringResource(R.string.iptv_live_format_title), subtitle = null,
                value = stringResource(formatLabel(row.item.overlay.streamFormat)), onClick = onFormat, leadingIcon = Icons.Filled.Tune)
            if (playing) {
                SettingsActionRow(title = stringResource(R.string.iptv_live_tracks), subtitle = null, onClick = onTracks, leadingIcon = Icons.Filled.Subtitles)
                SettingsActionRow(title = stringResource(R.string.iptv_live_hud), subtitle = null, onClick = onHud, leadingIcon = Icons.Filled.Equalizer, trailingIcon = null)
                SettingsActionRow(title = stringResource(R.string.iptv_live_stop), subtitle = null, onClick = onStop, leadingIcon = Icons.Filled.Stop, trailingIcon = null)
            }
            SettingsActionRow(title = stringResource(R.string.iptv_sources_manage), subtitle = null, onClick = onSources, leadingIcon = Icons.Filled.Settings)
        }
    }
}

@Composable
private fun Modifier.verticalScrollable(): Modifier = verticalScroll(androidx.compose.foundation.rememberScrollState())

private fun matchLabel(reason: GuideMatchReason?): Int = when (reason) {
    GuideMatchReason.MANUAL -> R.string.iptv_guide_match_manual
    GuideMatchReason.EXACT_ID -> R.string.iptv_guide_match_id
    GuideMatchReason.NAME -> R.string.iptv_guide_match_name
    else -> R.string.iptv_guide_match_none
}

@Composable
private fun GuidePickerDialog(picker: IptvGuidePicker, onAutomatic: () -> Unit, onFeed: (IptvGuideRef?) -> Unit, onSearch: (String) -> Unit,
    onChannel: (String) -> Unit, onDismiss: () -> Unit) {
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.iptv_guide_pick_title),
        subtitle = channelName(picker.row) + " · " + stringResource(matchLabel(picker.row.guide.reason)), width = 600.dp) {
        val feed = picker.feed
        if (feed == null) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
            Column(Modifier.weight(1f, fill = false).verticalScrollable(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsActionRow(title = stringResource(R.string.iptv_guide_pick_automatic), subtitle = null, onClick = onAutomatic,
                    trailingIcon = null, modifier = Modifier.focusRequester(first))
                if (picker.feeds.isEmpty()) Text(stringResource(R.string.iptv_guide_pick_none), color = NuvioTheme.colors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                picker.feeds.forEach { item -> SettingsActionRow(title = item.label, subtitle = null, onClick = { onFeed(item.ref) }) }
            }
        } else {
            val first = remember { FocusRequester() }
            var fieldFocused by remember { mutableStateOf(false) }
            LaunchedEffect(feed) { withFrameNanos { }; runCatching { first.requestFocus() } }
            val shape = RoundedCornerShape(12.dp)
            BasicTextField(picker.query, onSearch, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = NuvioTheme.colors.TextPrimary),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                cursorBrush = SolidColor(NuvioTheme.colors.TextPrimary),
                decorationBox = { inner ->
                    Box { if (picker.query.isEmpty()) Text(stringResource(R.string.iptv_guide_pick_search), color = NuvioTheme.colors.TextTertiary); inner() }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(first).onFocusChanged { fieldFocused = it.isFocused }
                    .clip(shape).background(NuvioTheme.colors.TextPrimary.copy(alpha = .07f), shape)
                    .border(if (fieldFocused) 2.dp else 1.dp, if (fieldFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.TextPrimary.copy(alpha = .12f), shape)
                    .padding(horizontal = 16.dp, vertical = 12.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(picker.results, key = { it.externalId }) { channel ->
                    SettingsActionRow(title = channel.names.firstOrNull()?.text ?: channel.externalId, subtitle = channel.externalId,
                        onClick = { onChannel(channel.externalId) }, trailingIcon = null)
                }
            }
            NuvioActionPill({ onFeed(null) }) { Text(stringResource(R.string.iptv_guide_pick_back)) }
        }
    }
}

@Composable
private fun SearchField(value: String, focus: FocusRequester, onChange: (String) -> Unit, onDone: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Row(Modifier.fillMaxWidth().height(52.dp).iptvPanel(shape, GlassRole.CONTROL)
        .then(if (focused) Modifier.border(2.dp, NuvioTheme.colors.FocusRing, shape) else Modifier)
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = NuvioTheme.colors.TextSecondary)
        BasicTextField(value, onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = NuvioTheme.colors.TextPrimary),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide(); onDone() }),
            cursorBrush = SolidColor(NuvioTheme.colors.TextPrimary),
            decorationBox = { inner ->
                Box { if (value.isEmpty()) Text(stringResource(R.string.iptv_live_search_hint), color = NuvioTheme.colors.TextTertiary); inner() }
            },
            modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN) { onDone(); true } else false
                })
    }
}
