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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.EmojiEvents
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
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Layers
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
import com.nuvio.tv.core.iptv.ListMove
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
fun IptvLiveScreen(onBack: () -> Unit, onSources: () -> Unit, onRecordings: () -> Unit = {}, onSettings: () -> Unit = onSources,
    viewModel: IptvLiveViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var fullscreen by remember { mutableStateOf(false) }
    var railOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    var showTracks by remember(state.player) { mutableStateOf(false) }
    var showHud by remember { mutableStateOf(viewModel.showStatsByDefault) }
    var menuFor by remember { mutableStateOf<IptvListedChannel?>(null) }
    var categoryMenu by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }
    var movingCategory by remember { mutableStateOf<String?>(null) }
    var boostFor by remember { mutableStateOf<IptvListedChannel?>(null) }
    var formatFor by remember { mutableStateOf<IptvListedChannel?>(null) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(15_000); value = System.currentTimeMillis() } }
    var cursor by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var viewStart by remember { mutableLongStateOf(Math.floorDiv(System.currentTimeMillis(), SLOT) * SLOT) }
    val rowFocus = remember(state.source, state.category, state.favourites, state.sports) { mutableMapOf<String, FocusRequester>() }
    val guideList = remember(state.source, state.category, state.favourites, state.sports, state.search) { androidx.compose.foundation.lazy.LazyListState() }
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
    LaunchedEffect(state.playingId, state.tuning) { if (state.playingId == null && !state.tuning) fullscreen = false }
    LaunchedEffect(fullscreen) { viewModel.setFullscreen(fullscreen); if (!fullscreen) viewModel.closeInset() }
    LaunchedEffect(state.source, state.category, state.favourites, state.sports, state.search) { if (moving != null) { moving = null; viewModel.finishMove() } }
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
            moving != null -> { moving = null; viewModel.finishMove() }
            movingCategory != null -> movingCategory = null
            fullscreen -> fullscreen = false
            searching -> { searching = false; viewModel.search("") }
            railOpen -> onBack()
            !railOpen && (state.channels.isNotEmpty() || state.search.isNotEmpty()) -> railOpen = true
            else -> onBack()
        }
    }
    LaunchedEffect(railOpen) { if (railOpen) { withFrameNanos { }; if (movingCategory == null) runCatching { railFocus.requestFocus() } } else movingCategory = null }
    LaunchedEffect(searching) { if (searching) { withFrameNanos { }; runCatching { searchFocus.requestFocus() } } }
    LaunchedEffect(state.source, state.category, state.favourites, state.sports, state.channels.firstOrNull()?.item?.channel?.id, fullscreen) {
        if (!fullscreen && !railOpen && !searching && state.channels.isNotEmpty()) { withFrameNanos { }; focusGridNow() }
    }
    val empty = emptyState(state)
    LaunchedEffect(empty) { if (empty != null && !railOpen && !searching) { withFrameNanos { }; runCatching { emptyFocus.requestFocus() } } }

    val tiles = state.multiview
    if (tiles != null) {
        Multiview(state, tiles, now, onFocusTile = viewModel::focusTile,
            onFull = { index -> tiles.getOrNull(index)?.row?.let { row -> fullscreen = true; viewModel.exitMultiview(row) } },
            onAdd = viewModel::addToMultiview, onReplace = viewModel::replaceTile, onRemove = viewModel::removeTile,
            onExit = { viewModel.exitMultiview(tiles.getOrNull(state.tileFocus)?.row) },
            onSizes = viewModel::setTileSizes, onShowLarge = viewModel::showLarge,
            onLayout = viewModel::setMultiviewLayout, onQuality = viewModel::setMultiviewQuality,
            onPickerSource = viewModel::pickerSource, onPickerMore = viewModel::pickerMore)
    } else if (fullscreen) {
        val playingRow = (state.channels.firstOrNull { it.item.channel.id == state.playingId } ?: state.playingRow?.takeIf { it.item.channel.id == state.playingId })
        val current = playingRow?.let { liveProgramme(state, it.item.channel.id, now) }
        val v2 = LocalV2Appearance.current != null
        viewModel.displaySettings.collectAsStateWithLifecycle(initialValue = null).value?.let {
            IptvLiveDisplayModeEffect(state.player, it.frameRateMatchingMode, it.resolutionMatchingEnabled)
        }
        FullscreenLive(state, now, showHud, onZap = viewModel::zap, onMenu = { playingRow?.let { menuFor = it } },
            onLastChannel = viewModel::lastChannel, onNumber = viewModel::watchNumber, onWatch = { viewModel.watch(it) },
            onPause = { pause -> when (pause) { null -> viewModel.togglePause(); true -> viewModel.pause(); false -> viewModel.resume() } },
            onRewind = viewModel::rewindLive,
            layout = remember(state.controlLayout, v2) { PlayerControlLayout.effective(state.controlLayout, v2) },
            canStartOver = playingRow != null && current != null && hasArchive(playingRow),
            onControl = { action ->
                val player = state.player
                when (action) {
                    PlayerControlAction.PLAY_PAUSE -> viewModel.togglePause()
                    PlayerControlAction.RESTART -> {
                        val catchup = state.catchup
                        if (catchup != null && state.catchupFrom == null) player?.seekTo(0)
                        else if (playingRow != null && (catchup ?: current) != null) viewModel.watch(playingRow, catchup ?: current)
                    }
                    PlayerControlAction.STATS -> { showHud = !showHud }
                    PlayerControlAction.AUDIO, PlayerControlAction.SUBTITLES -> { showTracks = true }
                    else -> Unit
                }
            }, onScrub = viewModel::scrub, onCloseInset = viewModel::closeInset, onSwapInset = viewModel::swapInset, onChannelMenu = { menuFor = it })
    } else {
        Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
            if (!LocalIptvAppearance.current.plainBackground) LocalV2Appearance.current?.let { V2Atmosphere(rich = false, background = it.settingsBackground) }
            Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth().height(188.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    InfoPanel(state, now, cursor, Modifier.weight(1f).fillMaxHeight())
                    Preview(state, Modifier.fillMaxHeight().aspectRatio(16f / 9f), onClick = {
                        val row = state.playingRow ?: state.focused
                        if (state.player != null) fullscreen = true else if (row != null) viewModel.watch(row)
                    })
                }
                if (searching) SearchField(state.search, state.airingSearch, searchFocus, onChange = viewModel::search, onDone = { focusGrid() })
                moving?.let { id -> MoveHint(state.channels.firstOrNull { it.item.channel.id == id }?.let(::channelName).orEmpty()) }
                if (state.sports && state.search.isBlank()) IptvSportsFixturesRow(state.source, state.hiddenCategories, state.playingId, onWatch = { row ->
                    if (row.item.channel.id == state.playingId && state.player != null && state.catchup == null) fullscreen = true else viewModel.watch(row)
                })
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
                        onNearEnd = viewModel::loadMore,
                        moving = moving, onMove = viewModel::moveChannel, onMoveDone = { moving = null; viewModel.finishMove() })
                }
            }
            AnimatedVisibility(railOpen, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)))
            }
            AnimatedVisibility(railOpen, Modifier.align(Alignment.CenterStart),
                enter = fadeIn() + slideInHorizontally { -it / 3 }, exit = fadeOut() + slideOutHorizontally { -it / 3 }) {
                CategoryRail(state, railFocus, Modifier.padding(start = 24.dp, top = 20.dp, bottom = 20.dp).width(340.dp).fillMaxHeight(),
                    onFavourites = { viewModel.showFavourites(); railOpen = false; focusGrid() },
                    onSports = { viewModel.showSports(); railOpen = false; focusGrid() },
                    onCategory = { viewModel.showCategory(it); railOpen = false; focusGrid() },
                    onSource = { viewModel.showSource(it); railOpen = false; focusGrid() },
                    onSources = { railOpen = false; onSettings() },
                    onRecordings = { railOpen = false; onRecordings() },
                    onSearch = { airing -> viewModel.searchMode(airing); railOpen = false; searching = true },
                    onHide = { categoryMenu = it },
                    onClose = { railOpen = false; focusGrid() },
                    onExit = onBack,
                    onAllSources = viewModel::toggleAllSources,
                    onSourceCategory = { ref, name -> viewModel.showSourceCategory(ref, name); railOpen = false; focusGrid() },
                    movingCategory = movingCategory,
                    onMoveCategory = { name, move -> viewModel.moveCategory(name, move) },
                    onMoveCategoryDone = { movingCategory = null })
            }
        }
    }
    if (showTracks) state.player?.let { IptvTrackDialog(it) { showTracks = false } }
    categoryMenu?.let { name ->
        val hidden = name in state.hiddenCategories
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
        NuvioDialog(onDismiss = { categoryMenu = null }, title = name.ifEmpty { stringResource(R.string.iptv_live_uncategorised) },
            subtitle = state.categories.firstOrNull { it.name == name }?.let { pluralStringResource(R.plurals.iptv_source_channels, it.channels, it.channels) }, width = 520.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsActionRow(title = stringResource(R.string.iptv_live_category_open), subtitle = null, leadingIcon = Icons.AutoMirrored.Filled.List, trailingIcon = null,
                    onClick = { categoryMenu = null; railOpen = false; viewModel.showCategory(name); focusGrid() }, modifier = Modifier.focusRequester(first))
                SettingsActionRow(title = stringResource(if (hidden) R.string.iptv_live_category_show else R.string.iptv_live_category_hide), subtitle = null,
                    leadingIcon = if (hidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, trailingIcon = null,
                    onClick = { categoryMenu = null; viewModel.toggleHidden(name) })
                if (!hidden && state.categories.count { it.name !in state.hiddenCategories } > 1) SettingsActionRow(title = stringResource(R.string.iptv_move_category),
                    subtitle = stringResource(R.string.iptv_move_category_subtitle), leadingIcon = Icons.Filled.SwapVert, trailingIcon = null,
                    onClick = { categoryMenu = null; railOpen = true; movingCategory = name })
            }
        }
    }
    menuFor?.let { row ->
        ChannelMenu(row, state, onDismiss = { menuFor = null },
            onWatch = { menuFor = null; if (row.item.channel.id == state.playingId && state.player != null) fullscreen = true else viewModel.watch(row) },
            onFromStart = { programme -> menuFor = null; viewModel.watch(row, programme) },
            onLive = { menuFor = null; viewModel.watch(row) },
            onFavourite = { viewModel.focus(row); viewModel.toggleFavourite(); menuFor = null },
            onGuide = { viewModel.openGuidePicker(row); menuFor = null },
            onFormat = { formatFor = row; menuFor = null },
            boost = viewModel.boost(row), onBoost = { boostFor = row; menuFor = null },
            onTracks = { showTracks = true; menuFor = null },
            onHud = { showHud = !showHud; menuFor = null },
            onStop = { viewModel.stop(); menuFor = null },
            onSources = { menuFor = null; onSources() },
            onMove = { delta -> menuFor = null; viewModel.moveFavourite(row, delta) },
            onMultiview = { menuFor = null; fullscreen = false; viewModel.addToMultiview(row) },
            onReorder = { menuFor = null; fullscreen = false; railOpen = false; viewModel.focus(row); moving = row.item.channel.id },
            onInset = { menuFor = null; viewModel.showInset(row); fullscreen = true },
            onSwapInset = { menuFor = null; viewModel.swapInset() },
            onCloseInset = { menuFor = null; viewModel.closeInset() },
            selected = programmeAt(state.guide[row.item.channel.id], cursor).takeIf { !fullscreen },
            onRecord = { programme -> menuFor = null; viewModel.record(row, programme) },
            onCancelRecording = { id -> menuFor = null; viewModel.cancelRecording(id) }, fullscreen = fullscreen)
    }
    if (state.alarmPrompt) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val settings = remember { viewModel.alarmSettings() }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
        NuvioDialog(onDismiss = viewModel::dismissAlarmPrompt, title = stringResource(R.string.iptv_recording_alarm_settings),
            subtitle = stringResource(R.string.iptv_recording_refused_exact_alarms), width = 560.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (settings != null) NuvioActionPill({
                    viewModel.dismissAlarmPrompt()
                    runCatching { context.startActivity(settings.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, Modifier.focusRequester(first)) { Text(stringResource(R.string.iptv_recording_alarm_settings)) }
                NuvioActionPill(viewModel::dismissAlarmPrompt, if (settings == null) Modifier.focusRequester(first) else Modifier) {
                    Text(stringResource(R.string.iptv_setup_back))
                }
            }
        }
    }
    state.guidePicker?.let { picker ->
        GuidePickerDialog(picker, onAutomatic = { viewModel.chooseGuideChannel(null) }, onFeed = viewModel::pickGuideFeed,
            onSearch = viewModel::searchGuide, onChannel = { viewModel.chooseGuideChannel(it) }, onDismiss = viewModel::closeGuidePicker)
    }
    boostFor?.let { channel ->
        SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_live_boost),
            subtitle = stringResource(R.string.iptv_live_boost_description),
            options = listOf(0, 3, 6, 9, 12).map { SettingsPickerOption(it, boostLabel(it)) },
            selectedValue = viewModel.boost(channel),
            onOptionSelected = { viewModel.setBoost(channel, it); boostFor = null },
            onDismiss = { boostFor = null })
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

private enum class EmptyKind { NO_SOURCES, REFRESHING, NO_CHANNELS, NO_FAVOURITES, NO_SPORTS, NO_RESULTS, FAILED }

private fun emptyState(state: IptvLiveState): EmptyKind? {
    if (state.channels.isNotEmpty() || !state.loaded || state.loading) return null
    if (state.sources.isEmpty()) return EmptyKind.NO_SOURCES
    if (state.search.isNotBlank()) return EmptyKind.NO_RESULTS
    if (state.favourites) return EmptyKind.NO_FAVOURITES
    if (state.sports) return EmptyKind.NO_SPORTS
    val status = state.source?.let { state.refresh[IptvRefreshCoordinator.key(it)] }
    if (status?.running == true) return EmptyKind.REFRESHING
    return if (status?.phase == IptvRefreshPhase.FAILED) EmptyKind.FAILED else EmptyKind.NO_CHANNELS
}

@Composable
private fun heading(state: IptvLiveState): String {
    if (state.search.isNotBlank()) return stringResource(if (state.airingSearch) R.string.iptv_live_airing_results else R.string.iptv_live_results, state.search.trim())
    val name = when {
        state.mergedFavourites -> stringResource(R.string.iptv_live_favourites) + " · " + stringResource(R.string.iptv_all_sources)
        state.favourites -> stringResource(R.string.iptv_live_favourites)
        state.sports -> return stringResource(R.string.iptv_live_sports) + " · " + stringResource(R.string.iptv_live_sports_window)
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
        else Icon(when (kind) {
            EmptyKind.NO_FAVOURITES -> Icons.Filled.StarBorder
            EmptyKind.NO_SPORTS -> Icons.Filled.EmojiEvents
            else -> Icons.Filled.LiveTv
        }, null,
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
            EmptyKind.NO_SPORTS -> stringResource(R.string.iptv_live_no_sports)
            EmptyKind.NO_RESULTS -> stringResource(R.string.iptv_live_no_results, state.search.trim())
            EmptyKind.FAILED -> stringResource(R.string.iptv_live_source_failed, source)
            EmptyKind.NO_CHANNELS -> stringResource(R.string.iptv_live_no_channels, source)
        }, style = MaterialTheme.typography.headlineSmall, color = NuvioTheme.colors.TextPrimary, textAlign = TextAlign.Center)
        Text(when (kind) {
            EmptyKind.NO_SOURCES -> stringResource(R.string.iptv_live_setup_description)
            EmptyKind.REFRESHING -> stringResource(R.string.iptv_live_refreshing_description)
            EmptyKind.NO_FAVOURITES -> stringResource(R.string.iptv_live_no_favourites_description)
            EmptyKind.NO_SPORTS -> stringResource(R.string.iptv_live_no_sports_description)
            EmptyKind.NO_RESULTS -> stringResource(R.string.iptv_live_no_results_description)
            EmptyKind.FAILED -> status?.message?.let { stringResource(it) } ?: stringResource(R.string.iptv_setup_failed)
            EmptyKind.NO_CHANNELS -> stringResource(R.string.iptv_live_no_channels_description)
        }, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp))
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (kind) {
                EmptyKind.NO_SOURCES -> NuvioActionPill(onSources, Modifier.focusRequester(focus)) { Text(stringResource(R.string.iptv_live_add_source)) }
                EmptyKind.NO_FAVOURITES, EmptyKind.NO_SPORTS -> NuvioActionPill(onAll, Modifier.focusRequester(focus)) { Text(stringResource(R.string.iptv_live_all)) }
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
private fun Preview(state: IptvLiveState, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    var focused by remember { mutableStateOf(false) }
    Box(modifier.clip(shape).background(Color.Black, shape)
        .border(if (focused) 3.dp else 1.dp, if (focused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.TextPrimary.copy(alpha = .12f), shape)
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) { onClick(); true } else isSelect(native.keyCode)
        }.focusable()) {
        if (state.player != null || state.tuning) LiveVideo(state.player, null, Modifier.fillMaxSize(), texture = true)
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
            Text(listOfNotNull(stringResource(R.string.iptv_live_title), state.sources.firstOrNull { it.ref.sourceId == (row?.item?.channel?.sourceId ?: state.source?.sourceId) }?.label).joinToString(" · ").uppercase(),
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
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            programmeArt(programme)?.let { ProgrammeArt(it, Modifier.padding(top = 2.dp).size(128.dp, 72.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            }
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.iptv_live_guide_hint), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
    }
}

@Composable
private fun CategoryRail(state: IptvLiveState, first: FocusRequester, modifier: Modifier, onFavourites: () -> Unit, onSports: () -> Unit, onCategory: (String?) -> Unit,
    onSource: (com.nuvio.tv.data.iptv.IptvSourceRef) -> Unit, onSources: () -> Unit, onRecordings: () -> Unit, onSearch: (Boolean) -> Unit, onHide: (String) -> Unit, onClose: () -> Unit,
    onExit: () -> Unit, onAllSources: () -> Unit, onSourceCategory: (com.nuvio.tv.data.iptv.IptvSourceRef, String?) -> Unit,
    movingCategory: String?, onMoveCategory: (String, ListMove) -> Unit, onMoveCategoryDone: () -> Unit) {
    val list = rememberLazyListState()
    val moveFocus = remember { FocusRequester() }
    val visibleCategories = state.categories.filter { it.name !in state.hiddenCategories }
    val movingIndex = movingCategory?.let { name -> visibleCategories.indexOfFirst { it.name == name } } ?: -1
    LaunchedEffect(movingCategory, movingIndex) {
        if (movingIndex < 0) return@LaunchedEffect
        val target = movingIndex + RAIL_HEADER_ITEMS + (if (state.sportEnabled) 1 else 0) + (if (state.sources.size > 1) 1 else 0)
        val visible = list.layoutInfo.visibleItemsInfo
        if (visible.isEmpty() || target <= visible.first().index || target >= visible.last().index) list.scrollToItem((target - 3).coerceAtLeast(0))
        withFrameNanos { }
        runCatching { moveFocus.requestFocus() }
    }
    Column(modifier.iptvPanel(role = GlassRole.NAVIGATION).padding(vertical = 14.dp, horizontal = 10.dp)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (movingCategory != null) {
                if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) { onMoveCategoryDone(); return@onPreviewKeyEvent true }
                val move = when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> ListMove.UP
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> ListMove.DOWN
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> ListMove.TOP
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> ListMove.BOTTOM
                    else -> null
                }
                if (move != null && native.action == AndroidKeyEvent.ACTION_DOWN && (native.repeatCount == 0 || move == ListMove.UP || move == ListMove.DOWN)) onMoveCategory(movingCategory, move)
                return@onPreviewKeyEvent move != null || isSelect(native.keyCode)
            }
            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) { onClose(); true } else false
        }) {
        Text(stringResource(R.string.iptv_live_title), style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
        if (movingCategory != null) Text(stringResource(R.string.iptv_move_category_keys), style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.Secondary, modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp))
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { RailItem(stringResource(R.string.iptv_live_exit), null, false, Modifier, onExit, Icons.AutoMirrored.Filled.ArrowBack) }
            item { RailItem(stringResource(R.string.iptv_live_search), null, state.search.isNotBlank() && !state.airingSearch, Modifier, { onSearch(false) }, Icons.Filled.Search) }
            item { RailItem(stringResource(R.string.iptv_live_search_airing), null, state.search.isNotBlank() && state.airingSearch, Modifier, { onSearch(true) }, Icons.Filled.Schedule) }
            item { RailItem(stringResource(R.string.iptv_recordings_open), state.recordings.count { it.status.holdsConnection }.takeIf { it > 0 }, false, Modifier, onRecordings, Icons.Filled.VideoLibrary) }
            item { RailItem(stringResource(R.string.iptv_settings_title), null, false, Modifier, onSources, Icons.Filled.Settings) }
            item { Spacer(Modifier.height(6.dp)) }
            item { RailItem(stringResource(if (state.allSources) R.string.iptv_all_sources_favourites else R.string.iptv_live_favourites), null, state.favourites,
                Modifier.focusRequester(first), onFavourites, Icons.Filled.Star) }
            if (state.sportEnabled) item { RailItem(stringResource(R.string.iptv_live_sports), null, state.sports, Modifier, onSports, Icons.Filled.EmojiEvents) }
            if (state.sources.size > 1) item { RailItem(stringResource(R.string.iptv_all_sources), null, state.allSources, Modifier, onAllSources, Icons.Filled.Layers) }
            if (state.allSources) {
                state.sourceCategories.forEach { group ->
                    item(key = "group-${group.source.ref.sourceId}") { SectionLabel(group.source.label) }
                    item(key = "group-all-${group.source.ref.sourceId}") {
                        RailItem(stringResource(R.string.iptv_live_all), group.categories.sumOf { it.channels }.takeIf { it > 0 },
                            !state.favourites && !state.sports && state.category == null && state.source == group.source.ref, Modifier,
                            { onSourceCategory(group.source.ref, null) }, Icons.AutoMirrored.Filled.List)
                    }
                    items(group.categories, key = { "group-${group.source.ref.sourceId}-${it.name}" }) { category ->
                        RailItem(category.name.ifEmpty { stringResource(R.string.iptv_live_uncategorised) }, category.channels,
                            !state.favourites && !state.sports && state.category == category.name && state.source == group.source.ref, Modifier,
                            { onSourceCategory(group.source.ref, category.name) })
                    }
                }
                if (state.sourceCategories.isEmpty()) item { LoadingIndicator(Modifier.padding(12.dp).size(24.dp)) }
                return@LazyColumn
            }
            item { RailItem(stringResource(R.string.iptv_live_all), visibleCategories.sumOf { it.channels }.takeIf { it > 0 }, !state.favourites && !state.sports && state.category == null,
                Modifier, { onCategory(null) }, Icons.AutoMirrored.Filled.List) }
            if (state.categories.isNotEmpty()) item { SectionLabel(stringResource(R.string.iptv_live_categories)) }
            if (state.categories.isNotEmpty()) item { Text(stringResource(R.string.iptv_live_hide_hint), style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextTertiary, modifier = Modifier.padding(start = 10.dp, bottom = 4.dp)) }
            items(visibleCategories, key = { "category-${it.name}" }) { category ->
                val moving = category.name == movingCategory
                RailItem(category.name.ifEmpty { stringResource(R.string.iptv_live_uncategorised) }, category.channels,
                    moving || (!state.favourites && !state.sports && state.category == category.name), if (moving) Modifier.focusRequester(moveFocus) else Modifier,
                    { onCategory(category.name) }, if (moving) Icons.Filled.SwapVert else null, onHold = { onHide(category.name) })
            }
            val hidden = state.categories.filter { it.name in state.hiddenCategories }
            if (hidden.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.iptv_live_hidden_categories)) }
                items(hidden, key = { "category-${it.name}" }) { category ->
                    RailItem(category.name.ifEmpty { stringResource(R.string.iptv_live_uncategorised) }, category.channels,
                        !state.favourites && !state.sports && state.category == category.name, Modifier, { onCategory(category.name) }, onHold = { onHide(category.name) }, dim = true)
                }
            }
            if (state.sources.size > 1) {
                item { SectionLabel(stringResource(R.string.iptv_live_sources)) }
                items(state.sources, key = { "source-${it.ref.sourceId}" }) { source ->
                    RailItem(source.label, null, source.ref == state.source, Modifier, { onSource(source.ref) }, Icons.Filled.LiveTv)
                }
            }
        }
    }
}

private const val RAIL_HEADER_ITEMS = 10

@Composable
private fun MoveHint(name: String) {
    Row(Modifier.fillMaxWidth().iptvPanel(RoundedCornerShape(14.dp), GlassRole.CONTROL).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Filled.SwapVert, null, Modifier.size(20.dp), tint = NuvioTheme.colors.Secondary)
        Text(stringResource(R.string.iptv_move_channel_title, name), style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        listOf(R.string.iptv_move_key_step, R.string.iptv_move_key_top, R.string.iptv_move_key_bottom, R.string.iptv_move_key_done).forEach {
            Text(stringResource(it), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(NuvioTheme.colors.TextPrimary.copy(alpha = .08f)).padding(horizontal = 8.dp, vertical = 3.dp))
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
    onGuide: () -> Unit, onFormat: () -> Unit, boost: Int, onBoost: () -> Unit, onTracks: () -> Unit, onHud: () -> Unit, onStop: () -> Unit, onSources: () -> Unit,
    onMove: (Int) -> Unit, onMultiview: () -> Unit, selected: com.nuvio.tv.core.iptv.GuideProgramme?,
    onRecord: (com.nuvio.tv.core.iptv.GuideProgramme?) -> Unit, onCancelRecording: (String) -> Unit,
    onReorder: () -> Unit, onInset: () -> Unit, onSwapInset: () -> Unit, onCloseInset: () -> Unit, fullscreen: Boolean) {
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
            val now = System.currentTimeMillis()
            if (state.canReorder && !fullscreen && state.multiview == null && row.item.channel.sourceId == state.source?.sourceId && state.channels.size > 1 &&
                (!state.favourites || row.item.overlay.favouriteRank != null)) SettingsActionRow(title = stringResource(R.string.iptv_move_channel),
                subtitle = stringResource(R.string.iptv_move_channel_subtitle), onClick = onReorder, leadingIcon = Icons.Filled.SwapVert, trailingIcon = null)
            val channelRecordings = state.recordings.filter { it.channelId == row.item.channel.id && it.sourceId == row.item.channel.sourceId && it.status.holdsConnection }
            val running = channelRecordings.firstOrNull { it.status == com.nuvio.tv.core.iptv.RecordingStatus.RECORDING }
            if (running != null) SettingsActionRow(title = stringResource(R.string.iptv_recording_stop), subtitle = running.title,
                onClick = { onCancelRecording(running.id) }, leadingIcon = Icons.Filled.Stop, trailingIcon = null)
            else SettingsActionRow(title = stringResource(if (current != null) R.string.iptv_recording_record_programme else R.string.iptv_recording_record_now),
                subtitle = current?.let(::title), onClick = { onRecord(current) }, leadingIcon = Icons.Filled.FiberManualRecord, trailingIcon = null)
            if (selected != null && selected.start.epochMillis > now) {
                val scheduled = channelRecordings.firstOrNull { it.status == com.nuvio.tv.core.iptv.RecordingStatus.SCHEDULED && it.programmeStartMillis == selected.start.epochMillis }
                if (scheduled != null) SettingsActionRow(title = stringResource(R.string.iptv_recording_cancel_scheduled), subtitle = title(selected),
                    onClick = { onCancelRecording(scheduled.id) }, leadingIcon = Icons.Filled.Close, trailingIcon = null)
                else SettingsActionRow(title = stringResource(R.string.iptv_recording_schedule, clock(selected.start.epochMillis)), subtitle = title(selected),
                    onClick = { onRecord(selected) }, leadingIcon = Icons.Filled.Schedule, trailingIcon = null)
            }
            if (state.maxTiles >= 2 && (state.multiview?.size ?: 0) < state.maxTiles) SettingsActionRow(title = stringResource(R.string.iptv_multiview_add_channel), subtitle = null,
                onClick = onMultiview, leadingIcon = Icons.Filled.ViewModule, trailingIcon = null)
            val inset = state.inset?.row?.item?.channel?.id
            if (state.player != null && state.multiview == null && !playing && inset != row.item.channel.id) SettingsActionRow(title = stringResource(R.string.iptv_inset_show),
                subtitle = stringResource(R.string.iptv_inset_show_subtitle, state.playingRow?.let(::channelName).orEmpty()), onClick = onInset,
                leadingIcon = Icons.Filled.PictureInPictureAlt, trailingIcon = null)
            if (inset != null && (playing || inset == row.item.channel.id)) {
                SettingsActionRow(title = stringResource(R.string.iptv_inset_swap), subtitle = state.inset?.row?.let(::channelName), onClick = onSwapInset,
                    leadingIcon = Icons.Filled.SwapHoriz, trailingIcon = null)
                SettingsActionRow(title = stringResource(R.string.iptv_inset_close), subtitle = null, onClick = onCloseInset, leadingIcon = Icons.Filled.Close, trailingIcon = null)
            }
            SettingsActionRow(title = stringResource(R.string.iptv_guide_pick_title), subtitle = stringResource(matchLabel(row.guide.reason)),
                onClick = onGuide, leadingIcon = Icons.Filled.Schedule)
            SettingsActionRow(title = stringResource(R.string.iptv_live_format_title), subtitle = null,
                value = stringResource(formatLabel(row.item.overlay.streamFormat)), onClick = onFormat, leadingIcon = Icons.Filled.Tune)
            SettingsActionRow(title = stringResource(R.string.iptv_live_boost), subtitle = null,
                value = boostLabel(boost), onClick = onBoost, leadingIcon = Icons.AutoMirrored.Filled.VolumeUp)
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
private fun SearchField(value: String, airing: Boolean, focus: FocusRequester, onChange: (String) -> Unit, onDone: () -> Unit) {
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
                Box { if (value.isEmpty()) Text(stringResource(if (airing) R.string.iptv_live_search_airing_hint else R.string.iptv_live_search_hint), color = NuvioTheme.colors.TextTertiary); inner() }
            },
            modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN) { onDone(); true } else false
                })
    }
}

@Composable
private fun boostLabel(db: Int): String = if (db == 0) stringResource(R.string.iptv_settings_none) else stringResource(R.string.iptv_live_boost_value, db)
