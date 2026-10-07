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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.*
import com.nuvio.tv.ui.v2.components.GlassRole
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
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
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.nuvioV2Focus

private sealed interface TilePick {
    data object Add : TilePick
    data class Replace(val index: Int) : TilePick
}

@Composable
internal fun Multiview(state: IptvLiveState, tiles: List<IptvTile>, now: Long, onFocusTile: (Int) -> Unit, onFull: (Int) -> Unit,
    onAdd: (IptvListedChannel) -> Unit, onReplace: (Int, IptvListedChannel) -> Unit, onRemove: (Int) -> Unit, onExit: () -> Unit,
    onSizes: (List<Int>) -> Unit, onShowLarge: (Int) -> Unit, onLayout: (MultiviewLayout) -> Unit, onQuality: (MultiviewQuality) -> Unit,
    onPickerSource: (com.nuvio.tv.data.iptv.IptvSourceRef?) -> Unit = {}, onPickerMore: () -> Unit = {}) {
    var choosingQuality by remember { mutableStateOf(false) }
    var choosingLayout by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<TilePick?>(null) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    val requesters = remember { List(MAX_SLOTS) { FocusRequester() } }
    val addSlot = tiles.size < multiviewMaxTiles(state.multiviewLayout, state.maxTiles)
    val slots = (tiles.size + if (addSlot) 1 else 0).coerceIn(1, MAX_SLOTS)
    LaunchedEffect(pick, tiles.size) {
        if (pick == null) { repeat(2) { withFrameNanos { } }; runCatching { requesters[state.tileFocus.coerceIn(0, slots - 1)].requestFocus() } }
    }
    BackHandler(pick != null) { pick = null; onPickerSource(null) }
    BackHandler(pick == null && menuFor == null) { onExit() }
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
                val layout = multiviewEffectiveLayout(state.multiviewLayout, tiles.size)
                val rects = multiviewGeometry(layout, slots, state.mainTile.coerceIn(0, (tiles.size - 1).coerceAtLeast(0)), maxWidth.value, maxHeight.value, gap.value)
                rects.forEachIndexed { index, rect ->
                    val modifier = Modifier.offset(rect.x.dp, rect.y.dp).size(rect.width.dp, rect.height.dp)
                    if (index < tiles.size) TileSlot(state, tiles, index, now, requesters, modifier, { menuFor = it }, onFocusTile, onFull)
                    else AddSlot(requesters[index], modifier) { pick = TilePick.Add }
                }
                val physical = tiles.indices.map { index -> rects.getOrNull(index)?.let { with(density) { (it.height.dp.toPx() * scale).toInt() } } ?: 0 }
                LaunchedEffect(physical) { onSizes(physical) }
            }
        }
        AnimatedVisibility(header && pick == null, Modifier.align(Alignment.TopCenter).padding(top = 24.dp), enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.iptvPanel(RoundedCornerShape(14.dp), GlassRole.HUD).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.ViewModule, null, Modifier.size(20.dp), tint = NuvioTheme.colors.TextSecondary)
                Text(stringResource(R.string.iptv_multiview_title), style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextPrimary,
                    fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.iptv_multiview_hint), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary)
            }
        }
        AnimatedVisibility(pick != null, Modifier.align(Alignment.CenterStart),
            enter = fadeIn() + slideInHorizontally { -it / 4 }, exit = fadeOut() + slideOutHorizontally { -it / 4 }) {
            val picker = state.picker
            ChannelPanel(state, now, onWatch = { row ->
                when (val target = pick) {
                    TilePick.Add -> onAdd(row)
                    is TilePick.Replace -> onReplace(target.index, row)
                    null -> Unit
                }
                pick = null; onPickerSource(null)
            }, channels = picker?.channels ?: state.channels, onNearEnd = { if (picker != null) onPickerMore() },
                header = { if (state.sources.count { it.playbackEligible } > 1) PickerSources(state, onPickerSource) })
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
}

private const val MAX_SLOTS = 4

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
    if (LocalV2Appearance.current != null) nuvioV2Focus(focused, shape, hardwareShadow = false)
    else if (focused) border(3.dp, NuvioTheme.colors.FocusRing, shape) else this

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
        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .8f),
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = .5f)).padding(horizontal = 7.dp, vertical = 2.dp))
    }
}

@Composable
private fun AddSlot(requester: FocusRequester, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
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
