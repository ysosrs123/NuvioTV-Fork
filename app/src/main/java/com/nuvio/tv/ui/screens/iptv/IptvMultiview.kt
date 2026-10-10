@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.*
import com.nuvio.tv.ui.v2.components.GlassRole
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.core.iptv.MultiviewLayout
import com.nuvio.tv.core.iptv.MultiviewQuality
import com.nuvio.tv.core.iptv.multiviewEffectiveLayout
import com.nuvio.tv.core.iptv.multiviewGeometry
import com.nuvio.tv.core.iptv.multiviewMaxTiles
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.OpenInFull
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureText

private sealed interface TilePick {
    data object Add : TilePick
    data class Replace(val index: Int) : TilePick
}

private sealed interface DataTile {
    data class Game(val key: String) : DataTile
    data object Scores : DataTile
}

@Composable
internal fun Multiview(state: IptvLiveState, tiles: List<IptvTile>, now: Long, onFocusTile: (Int) -> Unit, onFull: (Int) -> Unit,
    onAdd: (IptvListedChannel) -> Unit, onReplace: (Int, IptvListedChannel) -> Unit, onRemove: (Int) -> Unit, onExit: () -> Unit,
    onSizes: (List<Int>) -> Unit, onShowLarge: (Int) -> Unit, onLayout: (MultiviewLayout) -> Unit, onQuality: (MultiviewQuality) -> Unit,
    onPickerSource: (com.nuvio.tv.data.iptv.IptvSourceRef?) -> Unit = {}, onPickerMore: () -> Unit = {},
    onPickerCategory: (Boolean, String?) -> Unit = hiltViewModel<IptvLiveViewModel>()::pickerCategory) {
    var choosingQuality by remember { mutableStateOf(false) }
    var choosingLayout by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<TilePick?>(null) }
    var pickCategories by remember(pick) { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    val requesters = remember { List(MAX_SLOTS) { FocusRequester() } }
    val sports: IptvSportsFixturesViewModel = hiltViewModel()
    val fixtures by sports.state.collectAsStateWithLifecycle()
    var data by remember { mutableStateOf(emptyList<DataTile>()) }
    var dataMenu by remember { mutableStateOf<Int?>(null) }
    var choosingGame by remember { mutableStateOf<Int?>(null) }
    var dataFocus by remember { mutableStateOf<Int?>(null) }
    val total = tiles.size + data.size
    val streamRoom = tiles.size < multiviewMaxTiles(state.multiviewLayout, state.maxTiles)
    val addSlot = total < MAX_SLOTS && (streamRoom || fixtures.enabled)
    val slots = (total + if (addSlot) 1 else 0).coerceIn(1, MAX_SLOTS)
    LaunchedEffect(dataFocus, pick) {
        val slot = dataFocus ?: return@LaunchedEffect
        if (pick != null) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { requesters[slot.coerceIn(0, slots - 1)].requestFocus() }
        dataFocus = null
    }
    fun addData(tile: DataTile, at: Int?) {
        data = if (at != null && at in data.indices) data.toMutableList().also { it[at] = tile } else data + tile
        dataFocus = tiles.size + (at ?: data.lastIndex)
    }
    LaunchedEffect(pick, tiles.size) {
        if (pick == null) { repeat(2) { withFrameNanos { } }; runCatching { requesters[state.tileFocus.coerceIn(0, slots - 1)].requestFocus() } }
    }
    val pickerFocus = remember { FocusRequester() }
    var pickerFocused by remember { mutableStateOf(false) }
    LaunchedEffect(pick, pickerFocused) {
        if (pick == null || pickerFocused) return@LaunchedEffect
        repeat(PICKER_FOCUS_FRAMES) {
            withFrameNanos { }
            if (pick == null || pickerFocused) return@LaunchedEffect
            if (it >= 3) runCatching { pickerFocus.requestFocus() }
        }
    }
    BackHandler(pick != null) { pick = null; onPickerSource(null) }
    BackHandler(pick == null && menuFor == null && dataMenu == null && choosingGame == null) { onExit() }
    var header by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(5_000); header = false }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val gap = 2.dp
                val density = LocalDensity.current
                val boxHeight = maxHeight
                val uiHeight = LocalWindowInfo.current.containerSize.height.takeIf { it > 0 } ?: with(density) { boxHeight.roundToPx() }
                val scale = state.panelHeight.toFloat() / uiHeight
                val layout = multiviewEffectiveLayout(state.multiviewLayout, total)
                val rects = multiviewGeometry(layout, slots, state.mainTile.coerceIn(0, (tiles.size - 1).coerceAtLeast(0)), maxWidth.value, maxHeight.value, gap.value)
                rects.forEachIndexed { index, rect ->
                    val modifier = Modifier.offset(rect.x.dp, rect.y.dp).size(rect.width.dp, rect.height.dp).focusProperties { canFocus = pick == null }
                    if (index < tiles.size) TileSlot(state, tiles, index, now, requesters, modifier, { menuFor = it }, onFocusTile, onFull)
                    else if (index < total) DataSlot(data[index - tiles.size], fixtures, index, requesters[index], modifier) { dataMenu = index - tiles.size }
                    else AddSlot(requesters[index], modifier) { if (streamRoom) pick = TilePick.Add else dataMenu = ADD_DATA }
                }
                val physical = tiles.indices.map { index -> rects.getOrNull(index)?.let { with(density) { (it.height.dp.toPx() * scale).toInt() } } ?: 0 }
                LaunchedEffect(physical) { onSizes(physical) }
            }
        }
        AnimatedVisibility(header && pick == null, Modifier.align(Alignment.TopCenter).padding(top = 24.dp), enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.iptvPanel(RoundedCornerShape(12.dp), GlassRole.HUD).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.ViewModule, null, Modifier.size(20.dp), tint = NuvioTheme.colors.TextSecondary)
                Text(stringResource(R.string.iptv_multiview_title), style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextPrimary,
                    fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.iptv_multiview_hint), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary)
            }
        }
        state.message?.let { message ->
            Text(stringResource(message), style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp).iptvPanel(RoundedCornerShape(14.dp), GlassRole.HUD)
                    .padding(horizontal = 18.dp, vertical = 10.dp).widthIn(max = 720.dp))
        }
        AnimatedVisibility(pick != null, Modifier.align(Alignment.CenterStart),
            enter = fadeIn() + slideInHorizontally { -it / 4 }, exit = fadeOut() + slideOutHorizontally { -it / 4 }) {
            val picker = state.picker
            val panelState = if (picker == null) state else state.copy(categories = picker.categories.orEmpty(), favourites = picker.favourites,
                sports = false, category = picker.category, hiddenCategories = picker.hidden)
            Box(Modifier.focusRequester(pickerFocus).onFocusChanged { pickerFocused = it.hasFocus }.focusGroup()) { ChannelPanel(panelState, now, onWatch = { row ->
                when (val target = pick) {
                    TilePick.Add -> onAdd(row)
                    is TilePick.Replace -> onReplace(target.index, row)
                    null -> Unit
                }
                pick = null; onPickerSource(null)
            }, channels = picker?.channels ?: state.channels, onNearEnd = { if (picker != null) onPickerMore() },
                header = {
                    if (pick == TilePick.Add && fixtures.enabled && total < MAX_SLOTS) DataChoices(
                        onGame = { pick = null; onPickerSource(null); choosingGame = ADD_DATA },
                        onScores = { pick = null; onPickerSource(null); addData(DataTile.Scores, null) })
                    if (state.sources.count { it.playbackEligible } > 1) PickerSources(state, onPickerSource)
                }, onLeft = { if (pickCategories) { pick = null; onPickerSource(null) } else pickCategories = true }, showCategories = pickCategories,
                onFavourites = { onPickerCategory(true, null) }, onCategory = { onPickerCategory(false, it) }) }
        }
    }
    if (choosingQuality) {
        SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_multiview_quality), subtitle = stringResource(R.string.iptv_multiview_quality_description),
            options = MultiviewQuality.entries.map { SettingsPickerOption(it, stringResource(qualityLabel(it)), stringResource(when (it) {
                MultiviewQuality.AUTO -> R.string.iptv_multiview_quality_auto_description
                MultiviewQuality.SHARPEST -> R.string.iptv_multiview_quality_sharpest_description
                MultiviewQuality.LIGHTEST -> R.string.iptv_multiview_quality_lightest_description
            })) },
            selectedValue = state.multiviewQuality, onOptionSelected = { onQuality(it); choosingQuality = false }, onDismiss = { choosingQuality = false })
    }
    if (choosingLayout) {
        SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_multiview_layout), subtitle = stringResource(R.string.iptv_multiview_layout_description),
            options = MultiviewLayout.entries.map { SettingsPickerOption(it, stringResource(multiviewLayoutLabel(it))) },
            selectedValue = state.multiviewLayout, onOptionSelected = { onLayout(it); choosingLayout = false }, onDismiss = { choosingLayout = false })
    }
    menuFor?.let { index ->
        val tile = tiles.getOrNull(index) ?: return@let
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
        NuvioDialog(onDismiss = { menuFor = null }, title = channelName(tile.row), subtitle = liveProgramme(state, tile.row.item.channel.id, now)?.let(::title),
            width = 520.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsActionRow(title = stringResource(R.string.iptv_live_fullscreen), subtitle = null, leadingIcon = Icons.Filled.Fullscreen,
                    trailingIcon = null, onClick = { menuFor = null; onFull(index) }, modifier = Modifier.focusRequester(first))
                if (multiviewEffectiveLayout(state.multiviewLayout, tiles.size).let { it == MultiviewLayout.FOCUS || it == MultiviewLayout.ONE_OVER_TWO } && index != state.mainTile) SettingsActionRow(
                    title = stringResource(R.string.iptv_multiview_show_large), subtitle = null, leadingIcon = Icons.Filled.OpenInFull,
                    trailingIcon = null, onClick = { menuFor = null; onShowLarge(index) })
                SettingsActionRow(title = stringResource(R.string.iptv_multiview_layout), subtitle = null, leadingIcon = Icons.Filled.Dashboard,
                    value = stringResource(multiviewLayoutLabel(state.multiviewLayout)), onClick = { menuFor = null; choosingLayout = true })
                SettingsActionRow(title = stringResource(R.string.iptv_multiview_quality), subtitle = null, leadingIcon = Icons.Filled.HighQuality,
                    value = stringResource(qualityLabel(state.multiviewQuality)), onClick = { menuFor = null; choosingQuality = true })
                SettingsActionRow(title = stringResource(R.string.iptv_multiview_replace), subtitle = null, leadingIcon = Icons.Filled.SwapHoriz,
                    onClick = { menuFor = null; pick = TilePick.Replace(index) })
                SettingsActionRow(title = stringResource(R.string.iptv_multiview_remove), subtitle = null, leadingIcon = Icons.Filled.Close,
                    trailingIcon = null, onClick = { menuFor = null; onRemove(index) })
                SettingsActionRow(title = stringResource(R.string.iptv_multiview_exit), subtitle = null, leadingIcon = Icons.Filled.ViewModule,
                    trailingIcon = null, onClick = { menuFor = null; onExit() })
            }
        }
    }
    dataMenu?.let { index ->
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
        val tile = data.getOrNull(index)
        NuvioDialog(onDismiss = { dataMenu = null },
            title = stringResource(when (tile) { is DataTile.Game -> R.string.iptv_sport5p_game_screen; DataTile.Scores -> R.string.iptv_sport5p_all_scores; null -> R.string.iptv_sport5p_add_data }),
            subtitle = stringResource(R.string.iptv_sport5p_data_hint), width = 520.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (tile == null) DataChoices(onGame = { dataMenu = null; choosingGame = ADD_DATA }, onScores = { dataMenu = null; addData(DataTile.Scores, null) },
                    modifier = Modifier.focusRequester(first))
                else {
                    if (tile is DataTile.Game) SettingsActionRow(title = stringResource(R.string.iptv_sport5p_change_game), subtitle = null, leadingIcon = Icons.Filled.SwapHoriz,
                        onClick = { dataMenu = null; choosingGame = index }, modifier = Modifier.focusRequester(first))
                    SettingsActionRow(title = stringResource(R.string.iptv_sport5p_remove), subtitle = null, leadingIcon = Icons.Filled.Close, trailingIcon = null,
                        onClick = { dataMenu = null; data = data.filterIndexed { i, _ -> i != index }; dataFocus = 0 },
                        modifier = if (tile is DataTile.Game) Modifier else Modifier.focusRequester(first))
                    SettingsActionRow(title = stringResource(R.string.iptv_multiview_exit), subtitle = null, leadingIcon = Icons.Filled.ViewModule,
                        trailingIcon = null, onClick = { dataMenu = null; onExit() })
                }
            }
        }
    }
    choosingGame?.let { target ->
        val first = remember { FocusRequester() }
        val games = remember(fixtures.rows, tiles) {
            val all = fixtures.rows.flatMap { it.items }.filter { it.fixture.status != FixtureStatus.FINAL }.distinctBy { it.fixture.key }
            val linked = tiles.mapNotNull { tile -> playingFixture(fixtures.rows, tile.row.item.channel.id, System.currentTimeMillis()) }.map { it.fixture.key }.toSet()
            all.sortedBy { item -> when { item.fixture.key in linked -> 0; item.fixture.status == FixtureStatus.LIVE -> 1; else -> 2 } }.take(40)
        }
        LaunchedEffect(games.isEmpty()) { withFrameNanos { }; runCatching { first.requestFocus() } }
        NuvioDialog(onDismiss = { choosingGame = null }, title = stringResource(R.string.iptv_sport5p_choose_game),
            subtitle = if (games.isEmpty()) stringResource(R.string.iptv_sport5p_no_live) else null, width = 560.dp) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                games.forEachIndexed { i, item ->
                    val fixture = item.fixture
                    val text = SportsFixtureText.bug(fixture)
                    SettingsActionRow(title = sportTitle(fixture), subtitle = listOfNotNull(sportLeagueName(fixture),
                        if (fixture.status == FixtureStatus.LIVE) text.state?.takeIf { !scoreHidden(fixtures, fixture) } ?: stringResource(R.string.iptv_sport_live)
                        else "${sportDayLabel(fixture.startMillis)} · ${clock(fixture.startMillis)}").joinToString(" · "),
                        leadingIcon = Icons.Filled.SportsSoccer, trailingIcon = null,
                        onClick = { choosingGame = null; addData(DataTile.Game(fixture.key), target.takeIf { it >= 0 }) },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
                }
                if (games.isEmpty()) com.nuvio.tv.ui.v2.components.NuvioActionPill({ choosingGame = null }, Modifier.focusRequester(first)) {
                    Text(stringResource(R.string.iptv_sport_close))
                }
            }
        }
    }
}

private const val MAX_SLOTS = 4
private const val ADD_DATA = -1
private const val PICKER_FOCUS_FRAMES = 30

internal fun multiviewLayoutLabel(layout: MultiviewLayout): Int = when (layout) {
    MultiviewLayout.GRID -> R.string.iptv_multiview_layout_grid
    MultiviewLayout.FOCUS -> R.string.iptv_multiview_layout_focus
    MultiviewLayout.SIDE_BY_SIDE -> R.string.iptv_multiview_layout_side
    MultiviewLayout.ONE_OVER_TWO -> R.string.iptv_multiview_layout_one_over_two
}

@Composable
private fun PickerSources(state: IptvLiveState, onPickerSource: (com.nuvio.tv.data.iptv.IptvSourceRef?) -> Unit) {
    val chosen = state.picker?.ref ?: state.source
    SectionLabel(stringResource(R.string.iptv_live_sources))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.sources.filter { it.playbackEligible }.forEach { source ->
            var focused by remember { mutableStateOf(false) }
            val selected = source.ref == chosen
            Text(source.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (focused || selected) itemContent(focused) else NuvioTheme.colors.TextSecondary,
                modifier = Modifier.widthIn(max = 200.dp).onFocusChanged { focused = it.isFocused }.iptvItem(focused, selected)
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) { onPickerSource(source.ref.takeIf { it != state.source || state.mergedFavourites }); true } else false
                    }.focusable().padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
    if (state.picker?.loading == true) LoadingIndicator(Modifier.size(24.dp))
}

private fun qualityLabel(quality: MultiviewQuality): Int = when (quality) {
    MultiviewQuality.AUTO -> R.string.iptv_multiview_quality_auto
    MultiviewQuality.SHARPEST -> R.string.iptv_multiview_quality_sharpest
    MultiviewQuality.LIGHTEST -> R.string.iptv_multiview_quality_lightest
}

@Composable
private fun TileSlot(state: IptvLiveState, tiles: List<IptvTile>, index: Int, now: Long, requesters: List<FocusRequester>, modifier: Modifier,
    onMenu: (Int) -> Unit, onFocusTile: (Int) -> Unit, onFull: (Int) -> Unit) {
    Tile(state, tiles[index], index, index == state.tileFocus, now, requesters[index], modifier,
        onFocus = { onFocusTile(index) }, onSelect = { onFull(index) }, onMenu = { onMenu(index) })
}

@Composable
private fun Modifier.tileFocus(focused: Boolean, shape: RoundedCornerShape): Modifier =
    if (focused) border(2.5.dp, NuvioTheme.colors.FocusRing, shape) else this

@Composable
private fun Tile(state: IptvLiveState, tile: IptvTile, index: Int, audio: Boolean, now: Long, requester: FocusRequester, modifier: Modifier,
    onFocus: () -> Unit, onSelect: () -> Unit, onMenu: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val longPress = rememberLongPressKeyTracker()
    var held by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.focusRequester(requester)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .tileFocus(focused, shape)
        .clip(shape).background(Color.Black, shape)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0 && isSelect(native.keyCode)) held = false
            if (longPress.handle(native, ::isSelect) { held = true; onMenu() }) {
                if (native.action == AndroidKeyEvent.ACTION_UP) held = false
                return@onPreviewKeyEvent true
            }
            when {
                native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode) -> { if (!held) onSelect(); held = false; true }
                native.action == AndroidKeyEvent.ACTION_DOWN && (native.keyCode == AndroidKeyEvent.KEYCODE_MENU || native.keyCode == AndroidKeyEvent.KEYCODE_INFO) -> { onMenu(); true }
                else -> false
            }
        }
        .focusable()) {
        LiveVideo(tile.player, null, Modifier.fillMaxSize())
        if (tile.failure != null) {
            Text(stringResource(tile.failure), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .85f), textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp))
        } else if (!tile.playing) LoadingIndicator(Modifier.align(Alignment.Center).size(40.dp))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (focused) .75f else .55f))))
            .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChannelLogo(logoUrl(tile.row), channelName(tile.row), Modifier.size(48.dp, 28.dp))
            Column(Modifier.weight(1f)) {
                Text(channelName(tile.row), style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                liveProgramme(state, tile.row.item.channel.id, now)?.let {
                    Text(title(it), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (audio) Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.iptv_multiview_audio), Modifier.size(18.dp), tint = Color.White)
        }
        Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = .85f),
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = .45f)).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun AddSlot(requester: FocusRequester, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    Column(modifier.focusRequester(requester)
        .onFocusChanged { focused = it.isFocused }
        .tileFocus(focused, shape)
        .iptvPanel(shape)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) { onClick(); true } else false
        }
        .focusable(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        Icon(Icons.Filled.Add, null, Modifier.size(40.dp), tint = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary)
        Text(stringResource(R.string.iptv_multiview_add), style = MaterialTheme.typography.titleSmall,
            color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary)
        Text(stringResource(R.string.iptv_multiview_add_hint), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
    }
}

@Composable
private fun DataChoices(onGame: () -> Unit, onScores: () -> Unit, modifier: Modifier = Modifier) {
    SettingsActionRow(title = stringResource(R.string.iptv_sport5p_game_screen), subtitle = stringResource(R.string.iptv_sport5p_game_screen_subtitle),
        leadingIcon = Icons.Filled.SportsSoccer, onClick = onGame, modifier = modifier)
    SettingsActionRow(title = stringResource(R.string.iptv_sport5p_all_scores), subtitle = stringResource(R.string.iptv_sport5p_all_scores_subtitle),
        leadingIcon = Icons.Filled.Dashboard, onClick = onScores)
}

@Composable
private fun DataSlot(tile: DataTile, fixtures: IptvFixturesState, index: Int, requester: FocusRequester, modifier: Modifier, onMenu: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val longPress = rememberLongPressKeyTracker()
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.focusRequester(requester)
        .onFocusChanged { focused = it.isFocused }
        .tileFocus(focused, shape)
        .clip(shape).background(NuvioTheme.colors.BackgroundCard, shape)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (longPress.handle(native, ::isSelect) { onMenu() }) return@onPreviewKeyEvent true
            when {
                native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode) -> { onMenu(); true }
                native.action == AndroidKeyEvent.ACTION_DOWN && (native.keyCode == AndroidKeyEvent.KEYCODE_MENU || native.keyCode == AndroidKeyEvent.KEYCODE_INFO) -> { onMenu(); true }
                else -> false
            }
        }
        .focusable()) {
        when (tile) {
            is DataTile.Game -> {
                val fixture = fixtures.rows.firstNotNullOfOrNull { row -> row.items.firstOrNull { it.fixture.key == tile.key } }?.fixture
                if (fixture == null) Text(stringResource(R.string.iptv_sport5p_no_summary), style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                else GameScreen(fixture, scoreHidden(fixtures, fixture), Modifier.fillMaxSize())
            }
            DataTile.Scores -> AllScores(fixtures, Modifier.fillMaxSize())
        }
        Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = .85f),
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = .45f)).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun GameScreen(fixture: SportsFixture, hidden: Boolean, modifier: Modifier) {
    val summary = rememberSportsSummary(fixture.takeIf { it.status != FixtureStatus.SCHEDULED })
    val bug = SportsFixtureText.bug(fixture)
    Column(modifier.padding(start = 48.dp, end = 18.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(listOfNotNull(stringResource(R.string.iptv_sport5p_game_screen), sportLeagueName(fixture), bug.state?.takeIf { !hidden }).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        GameScoreLine(fixture, hidden, 28.dp, MaterialTheme.typography.titleMedium)
        if (hidden) {
            Text(stringResource(R.string.iptv_sport5p_hidden), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
            return@Column
        }
        val count = summary?.count?.takeIf { fixture.sport == "baseball" && fixture.status == FixtureStatus.LIVE }
        if (count != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CountBases(count, 12.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(listOfNotNull(count.outs?.let { stringResource(R.string.iptv_sport5p_outs, it) },
                    if (count.balls != null && count.strikes != null) stringResource(R.string.iptv_sport5p_count, count.balls, count.strikes) else null).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
                listOfNotNull(count.pitcher?.let { stringResource(R.string.iptv_sport5p_pitching, it) }, count.batter?.let { stringResource(R.string.iptv_sport5p_batting, it) })
                    .forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        } else {
            val run = summary?.run?.let { run -> (if (run.side == com.nuvio.tv.core.iptv.FixtureSide.HOME) fixture.home else fixture.away)?.let {
                stringResource(R.string.iptv_sport5p_run, SportsFixtureText.code(it), run.text) } }
            listOfNotNull(bug.extra, summary?.strength?.text, run, fixture.situation?.downDistance?.takeIf { bug.extra == null }).distinct().take(2).forEach {
                Text(it, style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val probability = summary?.winProbability.orEmpty()
        if (probability.size >= 2) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.iptv_sport5p_win_probability), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary,
                    maxLines = 1, modifier = Modifier.weight(1f))
                val last = probability.last()
                val leader = if (last >= .5f) fixture.home else fixture.away
                leader?.let { Text("${SportsFixtureText.code(it)} ${Math.round(maxOf(last, 1f - last) * 100)}%", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1) }
            }
            WinProbabilityLine(probability, fixture.home, fixture.away, Modifier.fillMaxWidth().height(40.dp))
        }
        val plays = summary?.lastPlays.orEmpty()
        if (plays.isNotEmpty()) {
            Text(stringResource(R.string.iptv_sport5p_last_plays), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
            plays.take(3).forEach { play ->
                Text(listOfNotNull(play.clock, play.text).joinToString("  "), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AllScores(fixtures: IptvFixturesState, modifier: Modifier) {
    val now = System.currentTimeMillis()
    val games = fixtures.rows.flatMap { it.items }.map { it.fixture }.distinctBy { it.key }.filter { fixture ->
        fixture.status == FixtureStatus.LIVE || (fixture.status == FixtureStatus.SCHEDULED && fixture.startMillis in now..now + UPCOMING_MILLIS)
    }.sortedBy { if (it.status == FixtureStatus.LIVE) 0L else it.startMillis }
    BoxWithConstraints(modifier.padding(start = 48.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
        val columns = if (maxWidth > 520.dp) 2 else 1
        val rows = ((maxHeight - 32.dp) / (SCORE_CELL + 8.dp)).toInt().coerceAtLeast(1)
        val size = columns * rows
        val pages = ((games.size + size - 1) / size).coerceAtLeast(1)
        var page by remember { mutableIntStateOf(0) }
        LaunchedEffect(pages) { page = 0; while (pages > 1) { delay(PAGE_MILLIS); page = (page + 1) % pages } }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.iptv_sport5p_all_scores), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextSecondary, maxLines = 1, modifier = Modifier.weight(1f))
                if (pages > 1) Text(stringResource(R.string.iptv_sport5p_page, page + 1, pages), style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary, maxLines = 1)
            }
            if (games.isEmpty()) Text(stringResource(R.string.iptv_sport5p_no_live), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
            games.drop(page.coerceAtMost(pages - 1) * size).take(size).chunked(columns).forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    line.forEach { fixture -> ScoreCell(fixture, scoreHidden(fixtures, fixture), Modifier.weight(1f)) }
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ScoreCell(fixture: SportsFixture, hidden: Boolean, modifier: Modifier) {
    val live = fixture.status == FixtureStatus.LIVE
    val close = live && !hidden && com.nuvio.tv.core.iptv.SportsFixtureSections.close(fixture)
    val home = fixture.home
    val away = fixture.away
    Column(modifier.height(SCORE_CELL).clip(RoundedCornerShape(8.dp)).background(NuvioTheme.colors.TextPrimary.copy(alpha = .06f))
        .then(if (close) Modifier.border(1.dp, NuvioTheme.colors.Secondary, RoundedCornerShape(8.dp)) else Modifier), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(sportLeagueName(fixture).uppercase(), style = SportCaps, color = NuvioTheme.colors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            CardState(fixture, fixture.sportDetail, hidden)
        }
        if (home != null && away != null) CardBands(fixture, home, away, false, Modifier.fillMaxWidth().weight(1f).padding(bottom = 4.dp), 22.dp, extra = null) { team, side ->
            BandScore(fixture, team, side, hidden, false)
        } else Text(if (hidden || !live) sportTitle(fixture) else SportsFixtureText.bug(fixture).primary, style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp))
    }
}

private val SCORE_CELL = 86.dp
private const val PAGE_MILLIS = 8_000L
private const val UPCOMING_MILLIS = 6L * 60 * 60 * 1000
