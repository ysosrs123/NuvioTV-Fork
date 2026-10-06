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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.*
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
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
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
    onAdd: (IptvListedChannel) -> Unit, onReplace: (Int, IptvListedChannel) -> Unit, onRemove: (Int) -> Unit, onExit: () -> Unit) {
    var pick by remember { mutableStateOf<TilePick?>(null) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    val requesters = remember { List(MAX_SLOTS) { FocusRequester() } }
    val slots = (tiles.size + if (tiles.size < state.maxTiles) 1 else 0).coerceIn(1, MAX_SLOTS)
    LaunchedEffect(pick, tiles.size) {
        if (pick == null) { repeat(2) { withFrameNanos { } }; runCatching { requesters[state.tileFocus.coerceIn(0, slots - 1)].requestFocus() } }
    }
    BackHandler(pick != null) { pick = null }
    BackHandler(pick == null && menuFor == null) { onExit() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.ViewModule, null, Modifier.size(20.dp), tint = NuvioTheme.colors.TextSecondary)
                Text(stringResource(R.string.iptv_multiview_title), style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.iptv_multiview_hint), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary)
                Text(clock(now), style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val gap = 12.dp
                val columns = 2
                val rows = if (slots <= 2) 1 else 2
                val cellWidth = minOf((maxWidth - gap * (columns - 1)) / columns, ((maxHeight - gap * (rows - 1)) / rows) * 16f / 9f)
                val cellHeight = cellWidth * 9f / 16f
                Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(gap)) {
                    for (r in 0 until rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            for (c in 0 until columns) {
                                val index = r * columns + c
                                val modifier = Modifier.size(cellWidth, cellHeight)
                                when {
                                    index < tiles.size -> Tile(state, tiles[index], index, index == state.tileFocus, now, requesters[index], modifier,
                                        onFocus = { onFocusTile(index) }, onSelect = { onFull(index) }, onMenu = { menuFor = index })
                                    index == tiles.size && tiles.size < state.maxTiles -> AddSlot(requesters[index], modifier) { pick = TilePick.Add }
                                    else -> Spacer(modifier)
                                }
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(pick != null, Modifier.align(Alignment.CenterStart),
            enter = fadeIn() + slideInHorizontally { -it / 4 }, exit = fadeOut() + slideOutHorizontally { -it / 4 }) {
            ChannelPanel(state, now, onWatch = { row ->
                when (val target = pick) {
                    TilePick.Add -> onAdd(row)
                    is TilePick.Replace -> onReplace(target.index, row)
                    null -> Unit
                }
                pick = null
            })
        }
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
    val shape = RoundedCornerShape(14.dp)
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
