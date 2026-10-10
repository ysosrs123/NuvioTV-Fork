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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhoneAndroid
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import com.nuvio.tv.core.iptv.ExpiryStatus
import com.nuvio.tv.core.iptv.ExpiryWarning
import com.nuvio.tv.core.iptv.GuideMatchReason
import com.nuvio.tv.core.iptv.ListMove
import com.nuvio.tv.core.iptv.LiveMenuItem
import com.nuvio.tv.core.iptv.LiveMenuLayout
import com.nuvio.tv.core.iptv.LiveWidgets
import com.nuvio.tv.data.iptv.IptvGuideRef
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.data.iptv.IptvLivePreferences
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
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import com.nuvio.tv.ui.v2.components.sidebarPageContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun IptvLiveScreen(onSources: () -> Unit, onRecordings: () -> Unit = {}, onSettings: () -> Unit = onSources,
    onVod: (com.nuvio.tv.core.iptv.VodKind) -> Unit = {}, onSetup: () -> Unit = onSources,
    viewModel: IptvLiveViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val expiry by viewModel.expiryWarning.collectAsStateWithLifecycle()
    val vod = rememberIptvVodAvailability()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val entryKey = System.identityHashCode(LocalLifecycleOwner.current)
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var railOpen by remember { mutableStateOf(false) }
    var railFocused by remember { mutableStateOf(false) }
    var awayFrom by rememberSaveable { mutableStateOf<String?>(null) }
    val returning = remember { awayFrom != null && viewModel.screenKey == entryKey }
    remember { viewModel.screenKey = entryKey }
    val settle = remember { booleanArrayOf(true) }
    val settleKeys = Modifier.onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        when {
            !settle[0] || native.keyCode == AndroidKeyEvent.KEYCODE_BACK -> false
            native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0 -> { settle[0] = false; false }
            else -> true
        }
    }
    val vodMenu by IptvVodLiveMenu.requested.collectAsStateWithLifecycle()
    LaunchedEffect(vodMenu) { if (vodMenu) { IptvVodLiveMenu.requested.value = false; railOpen = true } }
    LaunchedEffect(Unit) {
        val from = awayFrom ?: return@LaunchedEffect
        awayFrom = null
        if (returning) LiveMenuItem.entries.firstOrNull { it.name == from }?.let { item -> IptvSettingsReturn.requested.value = item; railOpen = true }
    }
    fun away(from: LiveMenuItem?, keep: Boolean, go: () -> Unit) {
        awayFrom = from?.name ?: GUIDE_ORIGIN
        IptvLiveHold.requested = keep && IptvLiveHold.hosted && state.player != null && state.multiview == null
        go()
    }
    var searching by remember { mutableStateOf(false) }
    var searchFrom by remember { mutableStateOf<LiveMenuItem?>(null) }
    var searchFieldFocused by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    var showTracks by remember(state.player) { mutableStateOf(false) }
    var showHud by remember { mutableStateOf(viewModel.showStatsByDefault) }
    var menuFor by remember { mutableStateOf<IptvListedChannel?>(null) }
    var categoryMenu by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }
    var movingCategory by remember { mutableStateOf<String?>(null) }
    val menuLayout = rememberIptvMenuLayout()
    var menuItemFor by remember { mutableStateOf<LiveMenuItem?>(null) }
    var movingItem by remember { mutableStateOf<LiveMenuItem?>(null) }
    var railReveal by remember { mutableIntStateOf(0) }
    var railReturn by remember { mutableStateOf<LiveMenuItem?>(null) }
    var railHold by remember { mutableStateOf<Set<String>>(emptySet()) }
    val holdFocus = remember { FocusRequester() }
    var boostFor by remember { mutableStateOf<IptvListedChannel?>(null) }
    var formatFor by remember { mutableStateOf<IptvListedChannel?>(null) }
    var recordFor by remember { mutableStateOf<Pair<IptvListedChannel, com.nuvio.tv.core.iptv.GuideProgramme?>?>(null) }
    var leaveAsk by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(15_000); value = System.currentTimeMillis() } }
    var cursor by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var viewStart by rememberSaveable { mutableLongStateOf(Math.floorDiv(System.currentTimeMillis(), SLOT) * SLOT) }
    remember { if (!returning) { fullscreen = false; cursor = System.currentTimeMillis(); viewStart = Math.floorDiv(cursor, SLOT) * SLOT } }
    val rowFocus = remember(state.source, state.category, state.favourites, state.sports) { mutableMapOf<String, FocusRequester>() }
    val guideList = rememberSaveable(state.source, state.category, state.favourites, state.sports, state.search,
        saver = androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }
    val scope = rememberCoroutineScope()
    val railFocus = remember { FocusRequester() }
    val returnFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    val openSidebar = com.nuvio.tv.LocalOpenSidebar.current
    val sidebarExpanded = com.nuvio.tv.LocalSidebarExpanded.current
    val hideChrome = com.nuvio.tv.LocalHideNavigationChrome.current
    val immersive = fullscreen || state.multiview != null
    DisposableEffect(immersive, hideChrome) {
        hideChrome(immersive)
        onDispose { hideChrome(false) }
    }
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.foreground(true)
            if (event == Lifecycle.Event.ON_STOP) viewModel.foreground(false, keep = IptvLiveHold.requested)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.foreground(false, keep = IptvLiveHold.requested) }
    }
    IptvSportsFixturesSync(state.source, state.hiddenCategories, state.sportEnabled)
    val sport = rememberIptvSportsGuide(state.sportEnabled)
    val widgets = rememberIptvWidgetSettings()
    var sportOnly by remember { mutableStateOf(IptvSportOnly.on) }
    var guideSpan by remember { mutableLongStateOf(2 * 60 * MINUTE_MILLIS) }
    val sportOnlyFocus = remember { FocusRequester() }
    val cardsFocus = remember { FocusRequester() }
    val sportPage = state.sports && sport.active && moving == null
    val filtering = sportOnly && sportPage
    val view = remember(state, sport, filtering, viewStart, guideSpan) {
        if (filtering) state.copy(channels = sportOnlyChannels(state.channels, sport, viewStart, viewStart + guideSpan, state.focused?.item?.channel?.id)) else state
    }
    LaunchedEffect(state.playingId, state.tuning) { if (state.playingId == null && !state.tuning) fullscreen = false }
    LaunchedEffect(fullscreen) { viewModel.setFullscreen(fullscreen); if (!fullscreen) viewModel.closeInset() }
    val openFullscreen by viewModel.fullscreenRequest.collectAsStateWithLifecycle()
    LaunchedEffect(openFullscreen) { if (openFullscreen) { fullscreen = true; viewModel.fullscreenShown() } }
    LaunchedEffect(state.source, state.category, state.favourites, state.sports, state.search) { if (moving != null) { moving = null; viewModel.finishMove() } }
    LaunchedEffect(state.message) { if (state.message != null) { delay(6_000); viewModel.clearMessage() } }
    suspend fun focusGridNow() {
        val id = state.focused?.item?.channel?.id?.takeIf { focused -> view.channels.any { it.item.channel.id == focused } }
            ?: view.channels.firstOrNull()?.item?.channel?.id ?: run { if (filtering) runCatching { sportOnlyFocus.requestFocus() }; return }
        val index = view.channels.indexOfFirst { it.item.channel.id == id }
        if (index >= 0 && guideList.layoutInfo.visibleItemsInfo.none { it.index == index }) guideList.scrollToItem((index - 2).coerceAtLeast(0))
        repeat(2) { withFrameNanos { } }
        if (railOpen) return
        rowFocus[id]?.let { runCatching { it.requestFocus() } }
    }
    fun focusGrid() { scope.launch { focusGridNow() } }
    fun focusContent() {
        scope.launch {
            if (view.channels.isNotEmpty() || (filtering && state.channels.isNotEmpty())) focusGridNow()
            else { withFrameNanos { }; if (!railOpen) runCatching { emptyFocus.requestFocus() } }
        }
    }
    fun openRail() {
        if (!railOpen) railOpen = true else if (!railFocused) { railOpen = false; focusContent() }
    }
    BackHandler(enabled = !sidebarExpanded && !leaving && state.multiview == null) {
        when {
            moving != null -> { moving = null; viewModel.finishMove() }
            movingCategory != null -> movingCategory = null
            movingItem != null -> movingItem = null
            fullscreen -> fullscreen = false
            searching -> {
                val back = searchFrom.takeIf { searchFieldFocused }
                searching = false; viewModel.search("")
                if (back != null) { IptvSettingsReturn.requested.value = back; railOpen = true }
            }
            railOpen -> if (railFocused) leaveAsk = true else { railOpen = false; focusContent() }
            state.sports -> { viewModel.leaveSports(); focusContent() }
            else -> railOpen = true
        }
    }
    LaunchedEffect(leaving) { if (leaving) { withFrameNanos { }; backDispatcher?.onBackPressed(); leaving = false } }
    var sidebarWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(sidebarExpanded) {
        if (sidebarExpanded) { sidebarWasOpen = true; return@LaunchedEffect }
        if (!sidebarWasOpen) return@LaunchedEffect
        sidebarWasOpen = false
        repeat(2) { withFrameNanos { } }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !railOpen && !searching && !fullscreen && state.multiview == null) focusContent()
    }
    LaunchedEffect(railOpen, categoryMenu, leaveAsk, movingCategory, menuItemFor, movingItem) {
        if (!railOpen) { railFocused = false; movingCategory = null; movingItem = null; railReturn = null; railHold = emptySet(); return@LaunchedEffect }
        if (categoryMenu != null || leaveAsk || movingCategory != null || menuItemFor != null || movingItem != null) return@LaunchedEffect
        IptvSettingsReturn.requested.value?.let { railReturn = it; railHold = emptySet(); IptvSettingsReturn.requested.value = null }
        val target = if (railReturn != null) returnFocus else railFocus
        repeat(RAIL_FOCUS_FRAMES) {
            withFrameNanos { }
            if (railFocused || !railOpen) return@LaunchedEffect
            if (it == 0) { railReveal++; withFrameNanos { } }
            if (railHold.isEmpty() || runCatching { holdFocus.requestFocus() }.isFailure) runCatching { target.requestFocus() }
        }
    }
    LaunchedEffect(searching) { if (searching) { withFrameNanos { }; runCatching { searchFocus.requestFocus() } } }
    LaunchedEffect(state.source, state.category, state.favourites, state.sports, state.channels.firstOrNull()?.item?.channel?.id, fullscreen) {
        if (!fullscreen && !railOpen && !searching && state.channels.isNotEmpty()) { withFrameNanos { }; focusGridNow() }
    }
    val empty = emptyState(state)
    val games = remember(sport, now) { sport.games(now) }
    val laneVisible = games.isNotEmpty() && empty == null && !searching && state.search.isBlank() && !state.sports && moving == null
    val laneShown = rememberUpdatedState(laneVisible)
    var laneFocused by remember { mutableStateOf(false) }
    LaunchedEffect(laneVisible) { if (!laneVisible && laneFocused) { laneFocused = false; if (!railOpen && !fullscreen) focusContent() } }
    LaunchedEffect(empty) { if (empty != null && !railOpen && !searching) { withFrameNanos { }; if (!railOpen) runCatching { emptyFocus.requestFocus() } } }
    val guidePanel = menuFor != null || boostFor != null || formatFor != null || recordFor != null || state.guidePicker != null || showTracks
    var panelShown by remember { mutableStateOf(false) }
    LaunchedEffect(guidePanel) {
        if (guidePanel) { panelShown = true; return@LaunchedEffect }
        if (!panelShown) return@LaunchedEffect
        panelShown = false
        withFrameNanos { }
        if (!fullscreen && !railOpen && !searching && moving == null && state.multiview == null && empty == null) focusGridNow()
    }

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
        Box(Modifier.fillMaxSize().then(settleKeys)) { FullscreenLive(state, now, showHud, onZap = viewModel::zap, onMenu = { playingRow?.let { menuFor = it } },
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
            }, onScrub = viewModel::scrub, onCloseInset = viewModel::closeInset, onSwapInset = viewModel::swapInset, onChannelMenu = { menuFor = it },
            onGoLive = viewModel::goLive, onFavourites = viewModel::showFavourites, onCategory = viewModel::showCategory, onNearEnd = viewModel::loadMore) }
    } else {
        Box(Modifier.fillMaxSize().then(settleKeys).background(NuvioTheme.colors.Background)) {
            if (!LocalIptvAppearance.current.plainBackground) LocalV2Appearance.current?.let { V2Atmosphere(rich = false, background = it.settingsBackground) }
            BoxWithConstraints(Modifier.fillMaxSize().sidebarPageContent().padding(start = if (LocalV2Appearance.current != null) 16.dp else 40.dp, end = 24.dp, top = 16.dp, bottom = 14.dp)) {
                val topHeight = (maxHeight - if (state.density == com.nuvio.tv.core.iptv.GuideDensity.COMPACT) GUIDE_RESERVE_COMPACT else GUIDE_RESERVE).coerceIn(150.dp, 240.dp)
                val hero = state.sports && state.search.isBlank()
                val widgetRoom = (maxWidth - topHeight * (16f / 9f) - LiveWidgets.SPACING.dp).value.toInt()
                val widgetHeight = topHeight.value.toInt()
                val columns = remember(widgetRoom, widgetHeight, widgets.layout, hero) {
                    if (hero) emptyList() else LiveWidgets.columns(widgetRoom, widgetHeight, widgets.layout)
                }
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth().height(topHeight), horizontalArrangement = Arrangement.spacedBy(LiveWidgets.SPACING.dp)) {
                        IptvSportHero(hero, Modifier.weight(1f).fillMaxHeight(), blocked = railOpen) { InfoPanel(state, now, cursor, it) }
                        if (columns.isNotEmpty()) IptvWidgetRow(columns, widgets, state, Modifier.fillMaxHeight(), fits = { LiveWidgets.fits(widgetRoom, widgetHeight, it) },
                            onDown = { focusContent() }, onRail = ::openRail, blocked = railOpen)
                        Preview(state, Modifier.fillMaxHeight().aspectRatio(16f / 9f), blocked = railOpen, onClick = {
                            val row = state.playingRow ?: state.focused
                            if (state.player != null) fullscreen = true else if (row != null) viewModel.watch(row)
                        })
                    }
                    if (searching) Box(Modifier.onFocusChanged { searchFieldFocused = it.hasFocus }) {
                        IptvSearchField(state.search, stringResource(if (state.airingSearch) R.string.iptv_live_search_airing_hint else R.string.iptv_live_search_hint),
                            searchFocus, onChange = viewModel::search, onDone = { focusGrid() })
                    }
                    moving?.let { id -> MoveHint(state.channels.firstOrNull { it.item.channel.id == id }?.let(::channelName).orEmpty()) }
                    if (state.sports && state.search.isBlank()) IptvSportsFixturesRow(state.source, state.hiddenCategories, state.playingId, onWatch = { row ->
                        if (row.item.channel.id == state.playingId && state.player != null && state.catchup == null) fullscreen = true else viewModel.watch(row)
                    }, onRail = ::openRail, blocked = railOpen, toggle = sportOnlyFocus.takeIf { sportPage && empty == null }, restore = cardsFocus)
                    if (laneVisible) IptvGamesNowLane(games, sport, state.playingId, onWatch = { row ->
                        if (row.item.channel.id == state.playingId && state.player != null && state.catchup == null) fullscreen = true else viewModel.watch(row)
                    }, onRail = ::openRail, onDown = { focusContent() }, blocked = railOpen,
                        modifier = Modifier.onFocusChanged { if (it.hasFocus) laneFocused = true else if (laneShown.value) laneFocused = false })
                    if (empty != null) {
                        EmptyPanel(empty, state, emptyFocus, Modifier.fillMaxWidth().weight(1f), onSources = { away(null, true, onSources) },
                            onRefresh = viewModel::refreshSource, onAll = { viewModel.showCategory(null) }, onRail = ::openRail, onSetup = { away(null, true, onSetup) })
                    } else {
                        GuideGrid(view, guideList, now, cursor, viewStart, rowFocus, heading(state), Modifier.fillMaxWidth().weight(1f), updating = state.updating != null,
                            onCursor = { time, start -> cursor = time; viewStart = start },
                            onRail = ::openRail,
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
                            moving = moving, onMove = viewModel::moveChannel, onMoveDone = { moving = null; viewModel.finishMove() },
                            sport = sport, sportOnly = if (sportPage) sportOnly else null,
                            onSportOnly = { sportOnly = !sportOnly; IptvSportOnly.on = sportOnly }, onSpan = { guideSpan = it }, sportOnlyFocus = sportOnlyFocus,
                            onSportOnlyUp = { runCatching { cardsFocus.requestFocus() }.isSuccess }, blocked = railOpen)
                    }
                }
            }
            AnimatedVisibility(railOpen, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)))
            }
            AnimatedVisibility(railOpen, Modifier.align(Alignment.CenterStart).fillMaxHeight(),
                enter = fadeIn() + slideInHorizontally { -it / 3 }, exit = fadeOut() + slideOutHorizontally { -it / 3 }) {
                CategoryRail(state, railFocus, returnFocus, Modifier.onFocusChanged { railFocused = it.hasFocus }.sidebarPageContent().then(if (LocalV2Appearance.current != null) Modifier.width(320.dp)
                    else Modifier.padding(start = 24.dp, top = 20.dp, bottom = 20.dp).width(320.dp)).fillMaxHeight(),
                    onFavourites = { viewModel.showFavourites(); railOpen = false; focusGrid() },
                    onSports = { viewModel.showSports(); railOpen = false; focusGrid() },
                    onCategory = { viewModel.showCategory(it); railOpen = false; focusGrid() },
                    onSource = { viewModel.showSource(it); railOpen = false; focusGrid() },
                    onSources = { away(LiveMenuItem.SETTINGS, true) { railOpen = false; onSettings() } },
                    onSetup = { away(LiveMenuItem.PHONE_SETUP, true) { railOpen = false; onSetup() } },
                    onRecordings = { away(LiveMenuItem.RECORDINGS, true) { railOpen = false; onRecordings() } },
                    vod = vod, onVod = { kind ->
                        away(if (kind == com.nuvio.tv.core.iptv.VodKind.MOVIE) LiveMenuItem.MOVIES else LiveMenuItem.SERIES, false) { railOpen = false; onVod(kind) }
                    },
                    onSearch = { airing -> viewModel.searchMode(airing); searchFrom = if (airing) LiveMenuItem.AIRING else LiveMenuItem.SEARCH; railOpen = false; searching = true },
                    onHide = { categoryMenu = it; railHold = setOf("category-$it") },
                    onClose = { railOpen = false; focusContent() },
                    onSidebar = openSidebar?.let { open -> { railOpen = false; open() } },
                    onAllSources = viewModel::toggleAllSources,
                    onSourceCategory = { ref, name -> viewModel.showSourceCategory(ref, name); railOpen = false; focusGrid() },
                    movingCategory = movingCategory,
                    onMoveCategory = { name, move -> viewModel.moveCategory(name, move) },
                    onMoveCategoryDone = { movingCategory = null },
                    menu = menuLayout, onMenuItem = { menuItemFor = it; railHold = setOf("anchor-${it.id}", "hidden-${it.id}") }, movingItem = movingItem,
                    onMoveItemDone = { movingItem = null }, reveal = railReveal, returnItem = railReturn, expiry = expiry, hold = railHold, holdFocus = holdFocus)
            }
        }
    }
    if (showTracks) state.player?.let { IptvTrackDialog(it) { showTracks = false } }
    if (leaveAsk) {
        val leave = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { leave.requestFocus() } }
        NuvioDialog(onDismiss = { leaveAsk = false }, title = stringResource(R.string.iptv_ui9_leave_title),
            subtitle = stringResource(R.string.iptv_ui9_leave_subtitle), width = 520.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NuvioActionPill({ leaveAsk = false; railOpen = false; leaving = true }, Modifier.focusRequester(leave)) { Text(stringResource(R.string.iptv_ui9_leave_home)) }
                NuvioActionPill({ leaveAsk = false }) { Text(stringResource(R.string.iptv_ui9_stay)) }
            }
        }
    }
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
    menuItemFor?.let { item ->
        val hidden = !item.required && item in menuLayout.hidden
        val shown = LiveMenuLayout.shown(menuLayout.order, menuLayout.hidden, menuAvailable(state, vod))
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
        NuvioDialog(onDismiss = { menuItemFor = null }, title = menuLabel(item, state),
            subtitle = if (item.required) stringResource(R.string.iptv_ui14_menu_required) else null, width = 520.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!item.required) SettingsActionRow(title = stringResource(if (hidden) R.string.iptv_ui14_menu_show else R.string.iptv_ui14_menu_hide), subtitle = null,
                    leadingIcon = if (hidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, trailingIcon = null,
                    onClick = { menuItemFor = null; menuLayout.toggle(item) }, modifier = Modifier.focusRequester(first))
                if (!hidden && item in shown && shown.size > 1) SettingsActionRow(title = stringResource(R.string.iptv_ui14_menu_move),
                    subtitle = stringResource(R.string.iptv_ui14_menu_move_subtitle), leadingIcon = Icons.Filled.SwapVert, trailingIcon = null,
                    onClick = { menuItemFor = null; railOpen = true; movingItem = item }, modifier = if (item.required) Modifier.focusRequester(first) else Modifier)
                if (menuLayout.customised) SettingsActionRow(title = stringResource(R.string.iptv_ui14_menu_reset),
                    subtitle = stringResource(R.string.iptv_ui14_menu_reset_subtitle), leadingIcon = Icons.Filled.Replay, trailingIcon = null,
                    onClick = { menuItemFor = null; menuLayout.reset() })
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
            onSources = { menuFor = null; away(null, true, onSources) },
            onMove = { delta -> menuFor = null; viewModel.moveFavourite(row, delta) },
            onMultiview = { menuFor = null; fullscreen = false; viewModel.addToMultiview(row) },
            onReorder = { menuFor = null; fullscreen = false; railOpen = false; viewModel.focus(row); moving = row.item.channel.id },
            onInset = { menuFor = null; viewModel.showInset(row); fullscreen = true },
            onSwapInset = { menuFor = null; viewModel.swapInset() },
            onCloseInset = { menuFor = null; viewModel.closeInset() },
            selected = programmeAt(state.guide[row.item.channel.id], cursor).takeIf { !fullscreen },
            onRecord = { programme -> menuFor = null; recordFor = row to programme },
            onCancelRecording = { id -> menuFor = null; viewModel.cancelRecording(id) }, fullscreen = fullscreen,
            onExternal = { menuFor = null; fullscreen = false; viewModel.openExternal(row) })
    }
    recordFor?.let { (row, programme) ->
        RecordPlaceDialog(title = when {
                programme == null -> stringResource(R.string.iptv_recording_record_now)
                programme.start.epochMillis > System.currentTimeMillis() -> stringResource(R.string.iptv_recording_schedule, clock(programme.start.epochMillis))
                else -> stringResource(R.string.iptv_recording_record_programme)
            }, subtitle = listOfNotNull(channelName(row), programme?.let(::title)).joinToString(" · "),
            onRecord = { location -> recordFor = null; viewModel.record(row, programme, location) }, onDismiss = { recordFor = null })
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
    onRefresh: () -> Unit, onAll: () -> Unit, onRail: () -> Unit, onSetup: () -> Unit) {
    val source = state.sources.firstOrNull { it.ref == state.source }?.label.orEmpty()
    val status = state.source?.let { state.refresh[IptvRefreshCoordinator.key(it)] }
    Column(modifier.iptvPanel().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        if (kind == EmptyKind.NO_RESULTS) Icon(Icons.Filled.Search, null, Modifier.size(48.dp), tint = NuvioTheme.colors.TextTertiary)
        else if (kind == EmptyKind.REFRESHING) LoadingIndicator(Modifier.size(48.dp))
        else Icon(when (kind) {
            EmptyKind.NO_FAVOURITES -> Icons.Filled.StarBorder
            EmptyKind.NO_SPORTS -> Icons.Filled.SportsSoccer
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
                EmptyKind.NO_SOURCES -> {
                    NuvioActionPill(onSources, Modifier.focusRequester(focus)) { Text(stringResource(R.string.iptv_live_add_source)) }
                    NuvioActionPill(onSetup) { Text(stringResource(R.string.iptv_ui9_phone_setup)) }
                }
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
private fun Preview(state: IptvLiveState, modifier: Modifier, blocked: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    var focused by remember { mutableStateOf(false) }
    val frame = if (LocalV2Appearance.current != null) Modifier.nuvioV2Focus(focused, shape, hardwareShadow = false, stationary = true).clip(shape).background(Color.Black, shape)
        else Modifier.clip(shape).background(Color.Black, shape)
            .border(if (focused) 3.dp else 1.dp, if (focused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.TextPrimary.copy(alpha = .12f), shape)
    Box(modifier.then(frame)
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) { onClick(); true } else isSelect(native.keyCode)
        }.focusable(enabled = !blocked)) {
        if (state.player != null || state.tuning) LiveVideo(state.player, null, Modifier.fillMaxSize(), texture = true, cover = state.playingRow, coverHint = R.string.iptv_play_corner_full)
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
            Modifier.align(Alignment.TopEnd).padding(10.dp), live = state.catchup == null, scrim = true)
    }
}

@Composable
private fun InfoPanel(state: IptvLiveState, now: Long, cursor: Long, modifier: Modifier) {
    val row = state.focused
    val programme = row?.let { liveProgramme(state, it.item.channel.id, cursor) ?: liveProgramme(state, it.item.channel.id, now) }
    val index = row?.let { state.channels.indexOf(it) } ?: -1
    Column(modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (row == null) {
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.iptv_live_choose), style = MaterialTheme.typography.headlineSmall, color = NuvioTheme.colors.TextSecondary)
            Spacer(Modifier.weight(1f))
            state.message?.let { Text(stringResource(it), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextPrimary, maxLines = 1,
                overflow = TextOverflow.Ellipsis) }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChannelLogo(logoUrl(row), channelName(row), Modifier.size(56.dp, 34.dp))
            if (index >= 0) Text("${index + 1}", style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextTertiary)
            Text(channelName(row), style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (programme != null && airing(programme, now) && state.player != null && state.playingId == row.item.channel.id) Tag(stringResource(R.string.iptv_live_playing), live = true)
            if (hasArchive(row)) Tag(stringResource(R.string.iptv_live_catchup))
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            programmeArt(programme)?.let { ProgrammeArt(it, Modifier.padding(top = 2.dp).size(128.dp, 72.dp)) }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProgrammeTitle(programme?.let(::title) ?: stringResource(R.string.iptv_live_no_programme))
                programme?.let { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(timeRange(item), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        if (airing(item, now)) progress(item, now)?.let { ProgressLine(it, Modifier.weight(1f).widthIn(max = 200.dp)) }
                        if (airing(item, now)) item.stop?.epochMillis?.let { stop -> Text(stringResource(R.string.iptv_live_minutes_left, ((stop - now) / MINUTE_MILLIS).toInt() + 1),
                            color = NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
                        if (item.start.epochMillis > now) Text(stringResource(R.string.iptv_live_starts_in, ((item.start.epochMillis - now) / MINUTE_MILLIS).toInt() + 1),
                            color = NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    description(item)?.let { FittedDescription(it, Modifier.weight(1f).fillMaxWidth().padding(top = 2.dp)) }
                }
            }
        }
        val message = state.message
        Text(stringResource(message ?: R.string.iptv_live_guide_hint), style = if (message != null) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
            color = if (message != null) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProgrammeTitle(text: String) {
    val measurer = rememberTextMeasurer()
    val large = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
    val small = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val single = remember(text, width, large) {
            !constraints.hasBoundedWidth || !measurer.measure(text, large, maxLines = 1, constraints = Constraints(maxWidth = width)).hasVisualOverflow
        }
        Text(text, style = if (single) large else small, color = NuvioTheme.colors.TextPrimary, maxLines = if (single) 1 else 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FittedDescription(text: String, modifier: Modifier) {
    val style = MaterialTheme.typography.bodySmall
    val line = with(LocalDensity.current) { (if (style.lineHeight.isSp) style.lineHeight else style.fontSize * 1.4f).toDp() }
    BoxWithConstraints(modifier) {
        val lines = ((maxHeight + 1.dp) / line).toInt()
        if (lines > 0) Text(text, color = NuvioTheme.colors.TextSecondary, maxLines = lines, overflow = TextOverflow.Ellipsis, style = style)
    }
}

private class RailRow(val key: String, val focusable: Boolean = true, val anchor: LiveMenuItem? = null, val content: @Composable (Modifier) -> Unit)

@Stable
internal class IptvMenuLayout(private val preferences: IptvLivePreferences) {
    var order by mutableStateOf(preferences.menuOrder)
        private set
    var hidden by mutableStateOf(preferences.menuHidden)
        private set
    val customised: Boolean get() = LiveMenuLayout.customised(order, hidden)
    fun move(item: LiveMenuItem, shown: List<LiveMenuItem>, move: ListMove) {
        LiveMenuLayout.move(order, shown, item, move)?.let { order = it; preferences.menuOrder = it }
    }
    fun toggle(item: LiveMenuItem) { hidden = LiveMenuLayout.toggle(hidden, item); preferences.menuHidden = hidden }
    fun reset() { preferences.resetMenu(); order = LiveMenuLayout.DEFAULT; hidden = emptySet() }
}

@Composable
private fun rememberIptvMenuLayout(): IptvMenuLayout {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember { IptvMenuLayout(IptvLivePreferences(context)) }
}

private fun menuAvailable(state: IptvLiveState, vod: IptvVodAvailability): (LiveMenuItem) -> Boolean = { item ->
    when (item) {
        LiveMenuItem.MOVIES -> vod.movies
        LiveMenuItem.SERIES -> vod.series
        LiveMenuItem.SPORT -> state.sportEnabled
        LiveMenuItem.ALL_SOURCES -> state.sources.size > 1
        LiveMenuItem.SOURCES -> state.sources.size > 1 && !state.allSources
        else -> true
    }
}

@Composable
private fun menuLabel(item: LiveMenuItem, state: IptvLiveState): String = stringResource(when (item) {
    LiveMenuItem.SEARCH -> R.string.iptv_live_search
    LiveMenuItem.AIRING -> R.string.iptv_live_search_airing
    LiveMenuItem.RECORDINGS -> R.string.iptv_recordings_open
    LiveMenuItem.MOVIES -> R.string.iptv_vod_browse_movies
    LiveMenuItem.SERIES -> R.string.iptv_vod_browse_series
    LiveMenuItem.SPORT -> R.string.iptv_live_sports
    LiveMenuItem.SETTINGS -> R.string.nav_settings
    LiveMenuItem.PHONE_SETUP -> R.string.iptv_ui9_phone_setup
    LiveMenuItem.FAVOURITES -> if (state.allSources) R.string.iptv_all_sources_favourites else R.string.iptv_live_favourites
    LiveMenuItem.ALL_SOURCES -> R.string.iptv_all_sources
    LiveMenuItem.CATEGORIES -> R.string.iptv_live_categories
    LiveMenuItem.SOURCES -> R.string.iptv_live_sources
})

@Composable
private fun CategoryRail(state: IptvLiveState, first: FocusRequester, returnFocus: FocusRequester, modifier: Modifier, onFavourites: () -> Unit, onSports: () -> Unit, onCategory: (String?) -> Unit,
    onSource: (com.nuvio.tv.data.iptv.IptvSourceRef) -> Unit, onSources: () -> Unit, onSetup: () -> Unit, onRecordings: () -> Unit, onSearch: (Boolean) -> Unit, onHide: (String) -> Unit, onClose: () -> Unit,
    vod: IptvVodAvailability, onVod: (com.nuvio.tv.core.iptv.VodKind) -> Unit,
    onSidebar: (() -> Unit)?, onAllSources: () -> Unit, onSourceCategory: (com.nuvio.tv.data.iptv.IptvSourceRef, String?) -> Unit,
    movingCategory: String?, onMoveCategory: (String, ListMove) -> Unit, onMoveCategoryDone: () -> Unit,
    menu: IptvMenuLayout, onMenuItem: (LiveMenuItem) -> Unit, movingItem: LiveMenuItem?, onMoveItemDone: () -> Unit,
    reveal: Int, returnItem: LiveMenuItem?, expiry: ExpiryWarning? = null, notice: String? = null, hold: Set<String> = emptySet(),
    holdFocus: FocusRequester? = null) {
    val list = rememberLazyListState()
    val moveFocus = remember { FocusRequester() }
    val visibleCategories = state.categories.filter { it.name !in state.hiddenCategories }
    val hiddenCategories = state.categories.filter { it.name in state.hiddenCategories }
    val available = menuAvailable(state, vod)
    val shown = LiveMenuLayout.shown(menu.order, menu.hidden, available)
    val selectedAll = !state.favourites && !state.sports && state.category == null
    fun entry(item: LiveMenuItem, dim: Boolean) = RailRow((if (dim) "hidden-" else "menu-") + item.id, anchor = item.takeIf { !dim }) { m ->
        val hold = { onMenuItem(item) }
        when (item) {
            LiveMenuItem.SEARCH -> IptvRailItem(stringResource(R.string.iptv_live_search), null, state.search.isNotBlank() && !state.airingSearch, m, { onSearch(false) },
                Icons.Filled.Search, onHold = hold, dim = dim)
            LiveMenuItem.AIRING -> IptvRailItem(stringResource(R.string.iptv_live_search_airing), null, state.search.isNotBlank() && state.airingSearch, m, { onSearch(true) },
                Icons.Filled.Schedule, onHold = hold, dim = dim)
            LiveMenuItem.RECORDINGS -> IptvRailItem(stringResource(R.string.iptv_recordings_open), state.recordings.count { it.status.holdsConnection }.takeIf { it > 0 }, false, m,
                onRecordings, Icons.Filled.VideoLibrary, onHold = hold, dim = dim)
            LiveMenuItem.MOVIES -> IptvRailItem(stringResource(R.string.iptv_vod_browse_movies), null, false, m, { onVod(com.nuvio.tv.core.iptv.VodKind.MOVIE) },
                Icons.Filled.Movie, onHold = hold, dim = dim)
            LiveMenuItem.SERIES -> IptvRailItem(stringResource(R.string.iptv_vod_browse_series), null, false, m, { onVod(com.nuvio.tv.core.iptv.VodKind.SERIES) },
                Icons.Filled.Tv, onHold = hold, dim = dim)
            LiveMenuItem.SPORT -> IptvRailItem(stringResource(R.string.iptv_live_sports), null, state.sports, m, onSports, Icons.Filled.SportsSoccer, onHold = hold, dim = dim)
            LiveMenuItem.SETTINGS -> IptvRailItem(stringResource(R.string.nav_settings), null, false, m, onSources, Icons.Filled.Settings, onHold = hold, dim = dim)
            LiveMenuItem.PHONE_SETUP -> IptvRailItem(stringResource(R.string.iptv_ui9_phone_setup), null, false, m, onSetup, Icons.Filled.PhoneAndroid, onHold = hold, dim = dim)
            LiveMenuItem.FAVOURITES -> IptvRailItem(stringResource(if (state.allSources) R.string.iptv_all_sources_favourites else R.string.iptv_live_favourites), null,
                state.favourites, m, onFavourites, Icons.Filled.Star, onHold = hold, dim = dim)
            LiveMenuItem.ALL_SOURCES -> IptvRailItem(stringResource(R.string.iptv_all_sources), null, state.allSources, m, onAllSources, Icons.Filled.Layers, onHold = hold, dim = dim)
            LiveMenuItem.CATEGORIES, LiveMenuItem.SOURCES -> Unit
        }
    }
    val rows = buildList {
        var previous: LiveMenuItem? = null
        shown.forEach { item ->
            if (previous != null && previous?.list != item.list) add(RailRow("divider-${item.id}", focusable = false) { RailDivider() })
            previous = item
            when (item) {
                LiveMenuItem.CATEGORIES -> if (state.allSources) {
                    state.sourceCategories.forEachIndexed { index, group ->
                        val id = group.source.ref.sourceId
                        add(RailRow("group-$id", focusable = false) { SectionLabel(group.source.label) })
                        add(RailRow("group-all-$id", anchor = LiveMenuItem.CATEGORIES.takeIf { index == 0 }) { m ->
                            IptvRailItem(stringResource(R.string.iptv_live_all), group.categories.sumOf { it.channels }.takeIf { it > 0 },
                                selectedAll && state.source == group.source.ref, m, { onSourceCategory(group.source.ref, null) }, Icons.AutoMirrored.Filled.List,
                                onHold = { onMenuItem(LiveMenuItem.CATEGORIES) })
                        })
                        group.categories.forEach { category ->
                            add(RailRow("group-$id-${category.name}") { m ->
                                IptvRailItem(category.name.ifEmpty { stringResource(R.string.iptv_live_uncategorised) }, category.channels,
                                    !state.favourites && !state.sports && state.category == category.name && state.source == group.source.ref, m,
                                    { onSourceCategory(group.source.ref, category.name) })
                            })
                        }
                    }
                    if (state.sourceCategories.isEmpty()) add(RailRow("group-loading", focusable = false) { LoadingIndicator(Modifier.padding(12.dp).size(24.dp)) })
                } else {
                    add(RailRow("all", anchor = LiveMenuItem.CATEGORIES) { m ->
                        IptvRailItem(stringResource(R.string.iptv_live_all), visibleCategories.sumOf { it.channels }.takeIf { it > 0 }, selectedAll, m, { onCategory(null) },
                            Icons.AutoMirrored.Filled.List, onHold = { onMenuItem(LiveMenuItem.CATEGORIES) })
                    })
                    if (state.categories.isNotEmpty()) {
                        add(RailRow("categories-label", focusable = false) { SectionLabel(stringResource(R.string.iptv_live_categories)) })
                        add(RailRow("categories-hint", focusable = false) {
                            Text(stringResource(R.string.iptv_live_hide_hint), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextTertiary,
                                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
                        })
                    }
                    visibleCategories.forEach { category ->
                        add(RailRow("category-${category.name}") { m ->
                            val moving = category.name == movingCategory
                            IptvRailItem(category.name.ifEmpty { stringResource(R.string.iptv_live_uncategorised) }, category.channels,
                                moving || (!state.favourites && !state.sports && state.category == category.name), m,
                                { onCategory(category.name) }, if (moving) Icons.Filled.SwapVert else null, onHold = { onHide(category.name) })
                        })
                    }
                    if (hiddenCategories.isNotEmpty()) {
                        add(RailRow("categories-hidden", focusable = false) { SectionLabel(stringResource(R.string.iptv_live_hidden_categories)) })
                        hiddenCategories.forEach { category ->
                            add(RailRow("category-${category.name}") { m ->
                                IptvRailItem(category.name.ifEmpty { stringResource(R.string.iptv_live_uncategorised) }, category.channels,
                                    !state.favourites && !state.sports && state.category == category.name, m, { onCategory(category.name) },
                                    onHold = { onHide(category.name) }, dim = true)
                            })
                        }
                    }
                }
                LiveMenuItem.SOURCES -> {
                    add(RailRow("sources-label", focusable = false) { SectionLabel(stringResource(R.string.iptv_live_sources)) })
                    state.sources.forEachIndexed { index, source ->
                        add(RailRow("source-${source.ref.sourceId}", anchor = LiveMenuItem.SOURCES.takeIf { index == 0 }) { m ->
                            IptvRailItem(source.label, null, source.ref == state.source, m, { onSource(source.ref) }, Icons.Filled.LiveTv,
                                onHold = { onMenuItem(LiveMenuItem.SOURCES) })
                        })
                    }
                }
                else -> add(entry(item, dim = false))
            }
        }
        val hiddenItems = menu.order.filter { !it.required && it in menu.hidden && available(it) }
        if (hiddenItems.isNotEmpty()) {
            add(RailRow("menu-hidden", focusable = false) { SectionLabel(stringResource(R.string.iptv_ui14_menu_hidden)) })
            hiddenItems.forEach { add(entry(it, dim = true)) }
        }
    }
    val firstFocusable = rows.indexOfFirst { it.focusable }
    val returnIndex = returnItem?.let { item -> rows.indexOfFirst { it.key == "menu-${item.id}" || it.key == "hidden-${item.id}" } }?.takeIf { it >= 0 } ?: firstFocusable
    val movingIndex = when {
        movingCategory != null -> rows.indexOfFirst { it.key == "category-$movingCategory" }
        movingItem != null -> rows.indexOfFirst { it.anchor == movingItem }
        else -> -1
    }
    LaunchedEffect(movingCategory, movingItem, movingIndex) {
        if (movingIndex < 0) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo
        if (visible.isEmpty() || movingIndex <= visible.first().index || movingIndex >= visible.last().index) list.scrollToItem((movingIndex - 3).coerceAtLeast(0))
        withFrameNanos { }
        runCatching { moveFocus.requestFocus() }
    }
    LaunchedEffect(reveal) {
        if (reveal == 0) return@LaunchedEffect
        val index = if (returnItem != null) returnIndex else firstFocusable
        if (index >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem((index - 2).coerceAtLeast(0))
    }
    val surface = if (LocalV2Appearance.current == null) Modifier.iptvPanel() else Modifier.iptvPanel(RectangleShape, edge = true)
    Column(modifier.then(surface).padding(vertical = 18.dp, horizontal = 12.dp)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (movingCategory != null || movingItem != null) {
                if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) {
                    if (movingCategory != null) onMoveCategoryDone() else onMoveItemDone()
                    return@onPreviewKeyEvent true
                }
                val move = when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> ListMove.UP
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> ListMove.DOWN
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> ListMove.TOP
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> ListMove.BOTTOM
                    else -> null
                }
                if (move != null && native.action == AndroidKeyEvent.ACTION_DOWN && (native.repeatCount == 0 || move == ListMove.UP || move == ListMove.DOWN)) {
                    if (movingCategory != null) onMoveCategory(movingCategory, move) else movingItem?.let { menu.move(it, shown, move) }
                }
                return@onPreviewKeyEvent move != null || isSelect(native.keyCode)
            }
            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT && onSidebar != null) {
                if (native.repeatCount == 0) onSidebar()
                return@onPreviewKeyEvent true
            }
            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) { onClose(); true } else false
        }) {
        Text(stringResource(R.string.iptv_live_title), style = iptvTitleStyle(), color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = if (notice == null && expiry == null) 12.dp else 4.dp))
        if (expiry != null) Text(expiryWarningText(expiry), style = MaterialTheme.typography.labelMedium,
            color = if (expiry.status is ExpiryStatus.Expired) NuvioTheme.colors.Error else NuvioTheme.colors.Warning, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
        if (notice != null) Text(notice, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
        if (movingCategory != null || movingItem != null) Text(stringResource(R.string.iptv_move_category_keys), style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                var target: Modifier = Modifier
                if (index == firstFocusable) target = target.focusRequester(first)
                if (index == returnIndex) target = target.focusRequester(returnFocus)
                if (index == movingIndex) target = target.focusRequester(moveFocus)
                if (holdFocus != null && (row.key in hold || row.anchor?.let { "anchor-${it.id}" in hold } == true)) target = target.focusRequester(holdFocus)
                row.content(target)
            }
        }
    }
}

private const val RAIL_FOCUS_FRAMES = 60
private const val GUIDE_ORIGIN = "guide"
private val GUIDE_RESERVE_COMPACT = 340.dp
private val GUIDE_RESERVE = 384.dp

@Composable
private fun MoveHint(name: String) {
    Row(Modifier.fillMaxWidth().iptvPanel(RoundedCornerShape(14.dp), GlassRole.CONTROL).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Filled.SwapVert, null, Modifier.size(20.dp), tint = NuvioTheme.colors.Secondary)
        Text(stringResource(R.string.iptv_move_channel_title, name), style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(listOf(R.string.iptv_move_key_step, R.string.iptv_move_key_top, R.string.iptv_move_key_bottom, R.string.iptv_move_key_done)
            .map { stringResource(it) }.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RailDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(1.dp).background(NuvioTheme.colors.Border))
}

@Composable
private fun ChannelMenu(row: IptvListedChannel, state: IptvLiveState, onDismiss: () -> Unit, onWatch: () -> Unit,
    onFromStart: (com.nuvio.tv.core.iptv.GuideProgramme) -> Unit, onLive: () -> Unit, onFavourite: () -> Unit,
    onGuide: () -> Unit, onFormat: () -> Unit, boost: Int, onBoost: () -> Unit, onTracks: () -> Unit, onHud: () -> Unit, onStop: () -> Unit, onSources: () -> Unit,
    onMove: (Int) -> Unit, onMultiview: () -> Unit, selected: com.nuvio.tv.core.iptv.GuideProgramme?,
    onRecord: (com.nuvio.tv.core.iptv.GuideProgramme?) -> Unit, onCancelRecording: (String) -> Unit,
    onReorder: () -> Unit, onInset: () -> Unit, onSwapInset: () -> Unit, onCloseInset: () -> Unit, fullscreen: Boolean, onExternal: () -> Unit) {
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
            SettingsActionRow(title = stringResource(R.string.cd_open_external_player), subtitle = stringResource(R.string.iptv_ui14_external_subtitle),
                onClick = onExternal, leadingIcon = Icons.AutoMirrored.Filled.OpenInNew, trailingIcon = null)
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
                    .fieldFocus(fieldFocused, shape).clip(shape).background(NuvioTheme.colors.TextPrimary.copy(alpha = .07f), shape)
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
private fun boostLabel(db: Int): String = if (db == 0) stringResource(R.string.iptv_settings_none) else stringResource(R.string.iptv_live_boost_value, db)
