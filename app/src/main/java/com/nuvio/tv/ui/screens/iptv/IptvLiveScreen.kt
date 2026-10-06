@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.GuideGapCell
import com.nuvio.tv.core.iptv.GuideGridRow
import com.nuvio.tv.core.iptv.GuideGridWindow
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.GuideProgrammeCell
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.data.iptv.IptvStreamFormat
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val ChannelColumn = 240.dp
private val RowHeight = 52.dp
private val MinuteWidth = 5.dp
private const val SLOT = GuideGridWindow.SLOT_MILLIS
private const val MINUTE = 60_000L

@Composable
fun IptvLiveScreen(onBack: () -> Unit, onSources: () -> Unit, viewModel: IptvLiveViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var fullscreen by remember { mutableStateOf(false) }
    var railOpen by remember { mutableStateOf(false) }
    var showTracks by remember(state.player) { mutableStateOf(false) }
    var showHud by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<IptvListedChannel?>(null) }
    var formatFor by remember { mutableStateOf<IptvListedChannel?>(null) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    var cursor by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var viewStart by remember { mutableLongStateOf(Math.floorDiv(System.currentTimeMillis(), SLOT) * SLOT) }
    val rowFocus = remember(state.source, state.category, state.favourites) { mutableMapOf<String, FocusRequester>() }
    val railFocus = remember { FocusRequester() }
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.foreground(true)
            if (event == Lifecycle.Event.ON_STOP) viewModel.foreground(false)
        }
        lifecycle.addObserver(observer)
        viewModel.foreground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); viewModel.foreground(false) }
    }
    LaunchedEffect(state.player) { if (state.player == null) fullscreen = false }
    BackHandler {
        when {
            fullscreen -> fullscreen = false
            !railOpen -> railOpen = true
            else -> onBack()
        }
    }
    fun focusGrid() {
        val id = state.focused?.item?.channel?.id ?: state.channels.firstOrNull()?.item?.channel?.id
        id?.let { rowFocus[it] }?.let { runCatching { it.requestFocus() } }
    }
    LaunchedEffect(railOpen) { if (railOpen) { withFrameNanos { }; runCatching { railFocus.requestFocus() } } }
    LaunchedEffect(state.source, state.category, state.favourites, state.channels.firstOrNull()?.item?.channel?.id, fullscreen) {
        if (!fullscreen && !railOpen && state.channels.isNotEmpty()) { withFrameNanos { }; focusGrid() }
    }

    if (fullscreen) {
        FullscreenLive(state, now, showHud, onZap = viewModel::zap, onMenu = { state.channels.firstOrNull { it.item.channel.id == state.playingId }?.let { menuFor = it } })
    } else {
        Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(horizontal = 32.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth().height(250.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                LiveVideo(state.player, state.playback.takeIf { showHud },
                    Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)))
                InfoPanel(state, now, cursor, Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (railOpen) CategoryRail(state, railFocus, Modifier.width(260.dp).fillMaxHeight(),
                    onFavourites = { viewModel.showFavourites(); railOpen = false },
                    onCategory = { viewModel.showCategory(it); railOpen = false },
                    onNextSource = { viewModel.nextSource(); railOpen = false },
                    onSources = onSources,
                    onClose = { railOpen = false; focusGrid() })
                GuideGrid(state, now, cursor, viewStart, rowFocus, Modifier.weight(1f).fillMaxHeight(),
                    onCursor = { time, start -> cursor = time; viewStart = start },
                    onRail = { railOpen = true },
                    onFocus = viewModel::focus,
                    onSelect = { row -> if (row.item.channel.id == state.playingId && state.player != null) fullscreen = true else viewModel.watch(row) },
                    onMenu = { menuFor = it },
                    onNearEnd = viewModel::loadMore)
            }
        }
    }
    if (showTracks) state.player?.let { IptvTrackDialog(it) { showTracks = false } }
    menuFor?.let { row ->
        NuvioDialog(onDismiss = { menuFor = null }, title = channelName(row)) {
            Button(onClick = { viewModel.focus(row); viewModel.toggleFavourite(); menuFor = null }) {
                Text(stringResource(if (row.item.overlay.favouriteRank == null) R.string.iptv_live_add_favourite else R.string.iptv_live_remove_favourite))
            }
            Button(onClick = { formatFor = row; menuFor = null }) {
                Text(stringResource(R.string.iptv_live_format, stringResource(formatLabel(row.item.overlay.streamFormat))))
            }
            if (state.player != null && row.item.channel.id == state.playingId) {
                Button(onClick = { showTracks = true; menuFor = null }) { Text(stringResource(R.string.iptv_live_tracks)) }
                Button(onClick = { showHud = !showHud; menuFor = null }) { Text(stringResource(R.string.iptv_live_hud)) }
                Button(onClick = { viewModel.stop(); menuFor = null }) { Text(stringResource(R.string.iptv_live_stop)) }
            }
            Button(onClick = { menuFor = null; onSources() }) { Text(stringResource(R.string.iptv_sources_title)) }
        }
    }
    formatFor?.let { channel ->
        NuvioDialog(onDismiss = { formatFor = null }, title = stringResource(R.string.iptv_live_format_title)) {
            Text(channelName(channel), color = NuvioTheme.colors.TextPrimary)
            Text(stringResource(R.string.iptv_live_format_description), color = NuvioTheme.colors.TextSecondary)
            IptvStreamFormat.entries.forEach { format ->
                Button(onClick = { viewModel.setStreamFormat(channel, format); formatFor = null }) { Text(stringResource(formatLabel(format))) }
            }
        }
    }
}

@Composable
private fun InfoPanel(state: IptvLiveState, now: Long, cursor: Long, modifier: Modifier) {
    val row = state.focused
    val programme = row?.let { programmeAt(state.guide[it.item.channel.id], cursor) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(row?.let(::channelName) ?: stringResource(R.string.iptv_live_choose), style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (programme != null && airing(programme, now)) Chip(stringResource(R.string.iptv_live_playing), NuvioTheme.colors.Error)
            if (row != null && row.item.channel.id == state.playingId) Chip(stringResource(if (state.playing) R.string.iptv_live_now_playing else R.string.iptv_live_connecting), NuvioTheme.colors.FocusRing)
        }
        Text(programme?.let(::title) ?: stringResource(R.string.iptv_live_no_programme), style = MaterialTheme.typography.headlineSmall,
            color = NuvioTheme.colors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        programme?.let { item ->
            Text(timeRange(item), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            if (airing(item, now)) item.stop?.epochMillis?.let { stop ->
                val fraction = ((now - item.start.epochMillis).toFloat() / (stop - item.start.epochMillis)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth(0.6f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(NuvioTheme.colors.Border)) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(NuvioTheme.colors.FocusRing))
                }
            }
            description(item)?.let { Text(it, color = NuvioTheme.colors.TextSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
        }
        Spacer(Modifier.weight(1f))
        (state.message ?: state.updating)?.let { Text(stringResource(it), color = NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun CategoryRail(state: IptvLiveState, first: FocusRequester, modifier: Modifier, onFavourites: () -> Unit, onCategory: (String?) -> Unit,
    onNextSource: () -> Unit, onSources: () -> Unit, onClose: () -> Unit) {
    LazyColumn(modifier.onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) { onClose(); true } else false
    }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val source = state.sources.firstOrNull { it.ref == state.source }
        if (source != null) item { Text(source.label, style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(4.dp)) }
        item { RailItem(stringResource(R.string.iptv_live_favourites), state.favourites, Modifier.focusRequester(first), onFavourites) }
        item { RailItem(stringResource(R.string.iptv_live_all), !state.favourites && state.category == null, Modifier, { onCategory(null) }) }
        items(state.categories, key = { "category-${it.name}" }) { category ->
            RailItem(category.name.ifEmpty { stringResource(R.string.iptv_live_uncategorised) } + "  ${category.channels}",
                !state.favourites && state.category == category.name, Modifier, { onCategory(category.name) })
        }
        if (state.sources.size > 1) item { RailItem(stringResource(R.string.iptv_live_next_source), false, Modifier, onNextSource) }
        item { RailItem(stringResource(R.string.iptv_sources_title), false, Modifier, onSources) }
    }
}

@Composable
private fun RailItem(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
        color = if (focused) NuvioTheme.colors.FocusContent else if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (focused) NuvioTheme.colors.FocusBackground else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) { onClick(); true } else false
            }
            .focusable().padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun GuideGrid(state: IptvLiveState, now: Long, cursor: Long, viewStart: Long, rowFocus: MutableMap<String, FocusRequester>, modifier: Modifier,
    onCursor: (Long, Long) -> Unit, onRail: () -> Unit, onFocus: (IptvListedChannel) -> Unit, onSelect: (IptvListedChannel) -> Unit,
    onMenu: (IptvListedChannel) -> Unit, onNearEnd: () -> Unit) {
    BoxWithConstraints(modifier) {
        val stripWidth = maxWidth - ChannelColumn
        val visibleMillis = (stripWidth.value / MinuteWidth.value * MINUTE).toLong().coerceAtLeast(SLOT)
        val listState = rememberLazyListState()
        Column(Modifier.fillMaxSize()) {
            TimeBar(viewStart, visibleMillis, Modifier.padding(start = ChannelColumn).fillMaxWidth().height(28.dp))
            if (state.channels.isEmpty()) {
                Text(stringResource(if (state.loading) R.string.iptv_setup_working else R.string.iptv_live_empty),
                    color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(16.dp))
            }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(state.channels, key = { _, row -> row.item.channel.id }) { index, row ->
                        if (index >= state.channels.size - 8) LaunchedEffect(state.channels.size) { onNearEnd() }
                        GuideRow(row, state.guide[row.item.channel.id], index, now, cursor, viewStart, visibleMillis,
                            row.item.channel.id == state.playingId, rowFocus.getOrPut(row.item.channel.id) { FocusRequester() },
                            onCursor, onRail, onFocus, onSelect, onMenu)
                    }
                }
                if (now in viewStart until viewStart + visibleMillis) {
                    Box(Modifier.padding(start = ChannelColumn + minuteOffset(now - viewStart)).width(2.dp).fillMaxHeight().background(NuvioTheme.colors.Error.copy(alpha = 0.8f)))
                }
            }
        }
    }
}

@Composable
private fun TimeBar(start: Long, span: Long, modifier: Modifier) {
    val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Box(modifier.clipToBounds()) {
        var slot = start
        while (slot < start + span) {
            Text(format.format(Date(slot)), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = minuteOffset(slot - start) + 6.dp))
            slot += SLOT
        }
    }
}

@Composable
private fun GuideRow(row: IptvListedChannel, grid: GuideGridRow?, index: Int, now: Long, cursor: Long, viewStart: Long, visibleMillis: Long,
    playing: Boolean, focusRequester: FocusRequester, onCursor: (Long, Long) -> Unit, onRail: () -> Unit,
    onFocus: (IptvListedChannel) -> Unit, onSelect: (IptvListedChannel) -> Unit, onMenu: (IptvListedChannel) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val longPress = rememberLongPressKeyTracker()
    var longPressed by remember { mutableStateOf(false) }
    val cells = grid?.cells.orEmpty()
    fun move(step: Int): Boolean {
        val programmes = cells.withIndex().filter { it.value is GuideProgrammeCell }
        if (programmes.isEmpty()) return false
        val current = programmes.indexOfFirst { cursor >= it.value.startMillis && cursor < it.value.endMillis }
            .takeIf { it >= 0 } ?: programmes.indexOfLast { it.value.startMillis <= cursor }.coerceAtLeast(0)
        val target = programmes.getOrNull(current + step)?.value ?: return false
        val time = target.startMillis
        val start = when {
            target.startMillis < viewStart -> Math.floorDiv(target.startMillis, SLOT) * SLOT
            target.startMillis >= viewStart + visibleMillis - SLOT -> Math.floorDiv(target.startMillis, SLOT) * SLOT - SLOT
            else -> viewStart
        }
        onCursor(time, start)
        return true
    }
    Row(Modifier.fillMaxWidth().height(RowHeight).clip(RoundedCornerShape(8.dp))
        .background(if (focused) NuvioTheme.colors.FocusBackground.copy(alpha = 0.35f) else Color.Transparent)
        .focusRequester(focusRequester)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus(row) }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (longPress.handle(native, ::isSelect) { longPressed = true; onMenu(row) }) {
                if (native.action == AndroidKeyEvent.ACTION_UP) longPressed = false
                return@onPreviewKeyEvent true
            }
            when {
                native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_MENU -> { onMenu(row); true }
                native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { move(1); true }
                native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!move(-1)) onRail(); true
                }
                native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode) -> { if (!longPressed) onSelect(row); longPressed = false; true }
                else -> false
            }
        }
        .focusable(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(ChannelColumn).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (playing) Box(Modifier.width(3.dp).height(24.dp).background(NuvioTheme.colors.FocusRing))
            Text("${index + 1}", color = NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.labelMedium)
            Text(channelName(row), color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal)
            if (row.item.overlay.favouriteRank != null) Text("★", color = NuvioTheme.colors.Rating, style = MaterialTheme.typography.labelMedium)
        }
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            if (cells.none { it is GuideProgrammeCell }) {
                Text(stringResource(R.string.iptv_live_no_programme), color = NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
            }
            val viewEnd = viewStart + visibleMillis
            for (cell in cells) {
                if (cell !is GuideProgrammeCell || cell.endMillis <= viewStart || cell.startMillis >= viewEnd) continue
                val start = maxOf(cell.startMillis, viewStart)
                val end = minOf(cell.endMillis, viewEnd)
                val selected = focused && cursor >= cell.startMillis && cursor < cell.endMillis
                val current = now >= cell.startMillis && now < cell.endMillis
                Box(Modifier.padding(start = minuteOffset(start - viewStart), top = 3.dp, bottom = 3.dp).width(minuteOffset(end - start) - 2.dp)
                    .fillMaxHeight().clip(RoundedCornerShape(6.dp))
                    .background(when {
                        selected -> NuvioTheme.colors.FocusRing
                        current -> NuvioTheme.colors.SurfaceVariant
                        else -> NuvioTheme.colors.Surface
                    }).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    Text(title(cell.programme), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = if (selected) NuvioTheme.colors.FocusContent else NuvioTheme.colors.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun FullscreenLive(state: IptvLiveState, now: Long, showHud: Boolean, onZap: (Int) -> Unit, onMenu: () -> Unit) {
    var banner by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(banner, state.playingId) { if (banner >= 0) { delay(4_000); banner = -1 } }
    val longPress = rememberLongPressKeyTracker()
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (longPress.handle(native, ::isSelect) { onMenu() }) return@onPreviewKeyEvent true
        if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) {
            banner = if (banner < 0) 0 else -1
            return@onPreviewKeyEvent true
        }
        if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
        when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { onZap(-1); banner++; true }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { onZap(1); banner++; true }
            AndroidKeyEvent.KEYCODE_MENU -> { onMenu(); true }
            else -> false
        }
    }.focusable()) {
        LiveVideo(state.player, state.playback.takeIf { showHud }, Modifier.fillMaxSize())
        if (banner >= 0) {
            val row = state.channels.firstOrNull { it.item.channel.id == state.playingId }
            val programme = row?.let { programmeAt(state.guide[it.item.channel.id], now) }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(NuvioTheme.colors.VideoControlsScrim).padding(horizontal = 40.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row?.let(::channelName) ?: state.playingTitle.orEmpty(), style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextSecondary)
                Text(programme?.let(::title) ?: stringResource(R.string.iptv_live_no_programme), style = MaterialTheme.typography.headlineSmall, color = NuvioTheme.colors.TextPrimary)
                programme?.let { Text(timeRange(it), color = NuvioTheme.colors.TextSecondary) }
                if (!state.playing) Text(stringResource(R.string.iptv_live_connecting), color = NuvioTheme.colors.TextTertiary)
            }
        }
    }
}

private fun minuteOffset(millis: Long): Dp = MinuteWidth * (millis.toFloat() / MINUTE)

private fun isSelect(keyCode: Int) = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
    keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

private fun programmeAt(row: GuideGridRow?, time: Long): GuideProgramme? =
    row?.cells?.firstOrNull { it is GuideProgrammeCell && time >= it.startMillis && time < it.endMillis }?.let { (it as GuideProgrammeCell).programme }

private fun airing(programme: GuideProgramme, now: Long) = programme.start.epochMillis <= now && (programme.stop?.epochMillis ?: Long.MAX_VALUE) > now

private fun channelName(row: IptvListedChannel) = row.item.overlay.customName ?: row.item.channel.data.name

private fun title(programme: GuideProgramme): String {
    val language = Locale.getDefault().language
    return programme.titles.firstOrNull { it.language?.substringBefore('-') == language }?.text ?: programme.titles.firstOrNull()?.text.orEmpty()
}

private fun description(programme: GuideProgramme): String? {
    val language = Locale.getDefault().language
    return (programme.descriptions.firstOrNull { it.language?.substringBefore('-') == language } ?: programme.descriptions.firstOrNull())?.text?.takeIf { it.isNotBlank() }
}

private fun timeRange(programme: GuideProgramme): String {
    val format = DateFormat.getTimeInstance(DateFormat.SHORT)
    val start = format.format(Date(programme.start.epochMillis))
    val stop = programme.stop?.epochMillis ?: return start
    return "$start – ${format.format(Date(stop))} · ${(stop - programme.start.epochMillis) / MINUTE} min"
}

private fun formatLabel(format: IptvStreamFormat): Int = when (format) {
    IptvStreamFormat.AUTO -> R.string.iptv_live_format_auto
    IptvStreamFormat.HLS -> R.string.iptv_live_format_hls
    IptvStreamFormat.MPEG_TS -> R.string.iptv_live_format_ts
}

@Composable
private fun LiveVideo(player: ExoPlayer?, playback: IptvLivePlayback?, modifier: Modifier) {
    Box(modifier.background(Color.Black)) {
        AndroidView(factory = { context -> PlayerView(context).apply {
            useController = false; isFocusable = false; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } }, modifier = Modifier.fillMaxSize(),
            update = { it.player = player; it.keepScreenOn = player != null }, onRelease = { it.player = null; it.keepScreenOn = false })
        playback?.let { IptvStatsOverlay(it, Modifier.padding(8.dp)) }
    }
}
