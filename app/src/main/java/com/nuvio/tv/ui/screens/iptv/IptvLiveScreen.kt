@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.data.iptv.IptvStreamFormat
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun IptvLiveScreen(onBack: () -> Unit, onSources: () -> Unit, viewModel: IptvLiveViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var fullscreen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val rows = state.page?.channels.orEmpty()
    val rowFocus = remember(state.source, state.offset, rows.map { it.item.channel.id }) {
        rows.associate { it.item.channel.id to FocusRequester() }
    }
    val fallbackFocus = remember { FocusRequester() }
    val first = rowFocus[rows.firstOrNull()?.item?.channel?.id] ?: fallbackFocus
    var formatChannel by remember(state.source) { mutableStateOf<IptvListedChannel?>(null) }
    val formatFocus = remember { FocusRequester() }
    val favouriteFocus = remember { FocusRequester() }
    val filterFocus = remember { FocusRequester() }
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.foreground(true)
            if (event == Lifecycle.Event.ON_STOP) viewModel.foreground(false)
        }
        lifecycle.addObserver(observer)
        viewModel.foreground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); viewModel.foreground(false) }
    }
    BackHandler(fullscreen) { fullscreen = false }
    LaunchedEffect(state.source, state.offset, rows.firstOrNull()?.item?.channel?.id, fullscreen) {
        if (rows.isNotEmpty() && !fullscreen) {
            listState.scrollToItem(0)
            withFrameNanos { }
            first.requestFocus()
        }
    }
    LaunchedEffect(state.player) { if (state.player == null) fullscreen = false }
    Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(if (fullscreen) 12.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { if (fullscreen) fullscreen = false else onBack() }) { Text(stringResource(R.string.iptv_setup_back)) }
            if (!fullscreen) {
                Button(onClick = onSources) { Text(stringResource(R.string.iptv_sources_title)) }
                Button(onClick = viewModel::nextSource, enabled = state.sources.size > 1) { Text(stringResource(R.string.iptv_live_next_source)) }
                Button(onClick = viewModel::favourites, modifier = Modifier.focusRequester(filterFocus).focusProperties {
                    down = if (state.focused != null) favouriteFocus else FocusRequester.Default
                }) { Text(stringResource(if (state.favourites) R.string.iptv_live_all else R.string.iptv_live_favourites)) }
            }
            if (state.player != null) {
                Button(onClick = { fullscreen = !fullscreen }) { Text(stringResource(if (fullscreen) R.string.iptv_live_guide else R.string.iptv_live_fullscreen)) }
                Button(onClick = viewModel::stop) { Text(stringResource(R.string.iptv_live_stop)) }
            }
        }
        state.message?.let { Text(stringResource(it), color = NuvioTheme.colors.TextSecondary) }
        if (fullscreen) {
            LiveVideo(state.player, Modifier.fillMaxWidth().weight(1f))
        } else {
            Text(state.sources.firstOrNull { it.ref == state.source }?.label ?: stringResource(R.string.iptv_live_title),
                style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiveVideo(state.player, Modifier.fillMaxWidth().weight(1f))
                    Text(state.playingTitle ?: stringResource(R.string.iptv_live_choose), color = NuvioTheme.colors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.player != null) Text(stringResource(if (state.playing) R.string.iptv_live_playing else R.string.iptv_live_connecting), color = NuvioTheme.colors.TextSecondary)
                    Text(state.focused?.item?.let { it.overlay.customName ?: it.channel.data.name }.orEmpty(), color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.focused != null) Button(onClick = viewModel::toggleFavourite, modifier = Modifier.focusRequester(favouriteFocus).focusProperties {
                        right = rowFocus[state.focused?.item?.channel?.id] ?: FocusRequester.Default
                        up = filterFocus
                        down = formatFocus
                    }) {
                        Text(stringResource(if (state.focused?.item?.overlay?.favouriteRank == null) R.string.iptv_live_add_favourite else R.string.iptv_live_remove_favourite))
                    }
                    state.focused?.let { channel ->
                        Button(onClick = { formatChannel = channel }, modifier = Modifier.focusRequester(formatFocus).focusProperties {
                            up = favouriteFocus
                            right = rowFocus[channel.item.channel.id] ?: FocusRequester.Default
                        }) { Text(stringResource(R.string.iptv_live_format, stringResource(formatLabel(channel.item.overlay.streamFormat)))) }
                    }
                    if (state.programmes.isEmpty()) Text(stringResource(R.string.iptv_live_no_programme), color = NuvioTheme.colors.TextSecondary)
                    val locale = Locale.getDefault().language
                    state.programmes.take(3).forEach { programme ->
                        val title = programme.titles.firstOrNull { it.language?.substringBefore('-') == locale }?.text ?: programme.titles.firstOrNull()?.text.orEmpty()
                        val time = if (programme.start.precise) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(programme.start.epochMillis)) + "  " else ""
                        Text("$time$title",
                            color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = viewModel::previousPage, enabled = state.offset > 0 && !state.loading) { Text(stringResource(R.string.iptv_live_previous)) }
                        Button(onClick = viewModel::nextPage, enabled = state.page?.catalogue?.next != null && !state.loading) { Text(stringResource(R.string.iptv_live_next)) }
                    }
                    if (state.loading) Text(stringResource(R.string.iptv_setup_working), color = NuvioTheme.colors.TextSecondary)
                    if (rows.isEmpty() && !state.loading) Text(stringResource(R.string.iptv_live_empty), color = NuvioTheme.colors.TextSecondary)
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
                        items(rows, key = { it.item.channel.id }) { row ->
                            Button(onClick = { viewModel.watch(row) }, modifier = Modifier.fillMaxWidth()
                                .focusRequester(requireNotNull(rowFocus[row.item.channel.id]))
                                .focusProperties { left = favouriteFocus }
                                .onFocusChanged { if (it.isFocused) viewModel.focus(row) }) {
                                Text(row.item.overlay.customName ?: row.item.channel.data.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
    formatChannel?.let { channel ->
        NuvioDialog(onDismiss = { formatChannel = null }, title = stringResource(R.string.iptv_live_format_title)) {
            Text(channel.item.overlay.customName ?: channel.item.channel.data.name, color = NuvioTheme.colors.TextPrimary)
            Text(stringResource(R.string.iptv_live_format_description), color = NuvioTheme.colors.TextSecondary)
            IptvStreamFormat.entries.forEach { format ->
                Button(onClick = { viewModel.setStreamFormat(channel, format); formatChannel = null }) {
                    Text(stringResource(formatLabel(format)))
                }
            }
            Button(onClick = { formatChannel = null }) { Text(stringResource(R.string.iptv_setup_cancel)) }
        }
    }
}

private fun formatLabel(format: IptvStreamFormat): Int = when (format) {
    IptvStreamFormat.AUTO -> R.string.iptv_live_format_auto
    IptvStreamFormat.HLS -> R.string.iptv_live_format_hls
    IptvStreamFormat.MPEG_TS -> R.string.iptv_live_format_ts
}

@Composable
private fun LiveVideo(player: ExoPlayer?, modifier: Modifier) {
    AndroidView(factory = { context -> PlayerView(context).apply {
        useController = false; isFocusable = false; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    } }, modifier = modifier.background(androidx.compose.ui.graphics.Color.Black),
        update = { it.player = player; it.keepScreenOn = player != null }, onRelease = { it.player = null; it.keepScreenOn = false })
}
