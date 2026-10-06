@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nuvio.tv.data.local.PlayerControlAction
import com.nuvio.tv.data.local.PlayerControlLayout
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.GuideProgrammeCell
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import com.nuvio.tv.ui.v2.components.GlassRole
import kotlinx.coroutines.delay

@Composable
internal fun FullscreenLive(state: IptvLiveState, now: Long, showHud: Boolean, onZap: (Int) -> Unit, onMenu: () -> Unit,
    onLastChannel: () -> Unit, onNumber: (Int) -> Unit, onWatch: (IptvListedChannel) -> Unit,
    layout: PlayerControlLayout, canStartOver: Boolean, onControl: (PlayerControlAction) -> Unit) {
    var banner by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(false) }
    var resize by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var digits by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(panel, controls) { if (!panel && !controls) runCatching { focus.requestFocus() } }
    LaunchedEffect(banner, state.playingId) { if (banner >= 0) { delay(5_000); banner = -1 } }
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) { delay(1_500); digits.toIntOrNull()?.let(onNumber); digits = ""; banner = 0 }
    }
    BackHandler(panel) { panel = false }
    val longPress = rememberLongPressKeyTracker()
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (panel || controls) return@onPreviewKeyEvent false
        if (longPress.handle(native, ::isSelect) { onMenu() }) return@onPreviewKeyEvent true
        if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) {
            banner = -1; controls = true
            return@onPreviewKeyEvent true
        }
        if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
        val digit = native.keyCode - AndroidKeyEvent.KEYCODE_0
        if (digit in 0..9) { if (digits.length < 5) digits += digit; return@onPreviewKeyEvent true }
        if (native.repeatCount > 0 && native.keyCode in ZAP_KEYS) return@onPreviewKeyEvent true
        val player = state.player
        if (state.catchup != null && player != null) {
            val step = when (native.keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> SEEK_STEP
                AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> -SEEK_STEP
                else -> 0L
            }
            if (step != 0L) { player.seekTo((player.currentPosition + step).coerceAtLeast(0)); banner++; return@onPreviewKeyEvent true }
            if (native.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || native.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_PLAY ||
                native.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_PAUSE) {
                player.playWhenReady = native.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_PLAY ||
                    (native.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && !player.playWhenReady)
                banner++
                return@onPreviewKeyEvent true
            }
        }
        when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { onZap(-1); banner++; true }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { onZap(1); banner++; true }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> { onLastChannel(); banner++; true }
            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_GUIDE -> { panel = true; true }
            AndroidKeyEvent.KEYCODE_MENU -> { onMenu(); true }
            AndroidKeyEvent.KEYCODE_INFO -> { banner = if (banner < 0) 0 else -1; true }
            else -> false
        }
    }.focusable()) {
        LiveVideo(state.player, state.playback.takeIf { showHud }, Modifier.fillMaxSize(), resize,
            state.sources.firstOrNull { it.ref == state.source }?.label)
        PlaybackState(state, Modifier.align(Alignment.Center), large = true)
        ReconnectingPill(state, Modifier.align(Alignment.TopStart).padding(36.dp))
        if (digits.isNotEmpty()) NumberEntry(digits, state, Modifier.align(Alignment.TopEnd).padding(36.dp))
        AnimatedVisibility(controls && !panel, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            LiveControls(state, now, layout, liveControlActions(state, canStartOver), onAction = { action ->
                when (action) {
                    PlayerControlAction.INFO -> { controls = false; banner = 0 }
                    PlayerControlAction.ASPECT -> {
                        resize = when (resize) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    }
                    PlayerControlAction.AUDIO, PlayerControlAction.SUBTITLES -> { controls = false; onControl(action) }
                    else -> onControl(action)
                }
            }, onHide = { controls = false })
        }
        AnimatedVisibility(banner >= 0 && !panel && !controls, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut() + slideOutVertically { it / 3 }) {
            Banner(state, now)
        }
        AnimatedVisibility(panel, Modifier.align(Alignment.CenterStart),
            enter = fadeIn() + slideInHorizontally { -it / 4 }, exit = fadeOut() + slideOutHorizontally { -it / 4 }) {
            ChannelPanel(state, now, onWatch = { panel = false; onWatch(it) })
        }
    }
}

private const val SEEK_STEP = 30_000L
private val ZAP_KEYS = setOf(AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_UP,
    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_LAST_CHANNEL)

@Composable
internal fun PlaybackState(state: IptvLiveState, modifier: Modifier, large: Boolean) {
    val waiting = state.playingId != null && !state.playing && (state.tuning || state.player != null) && !state.reconnecting
    if (waiting) Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LoadingIndicator(Modifier.size(if (large) 56.dp else 40.dp))
        Text(stringResource(R.string.iptv_live_connecting), style = if (large) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = .85f))
    }
}

@Composable
internal fun ReconnectingPill(state: IptvLiveState, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(state.reconnecting) { visible = false; if (state.reconnecting) { delay(2_000); visible = true } }
    AnimatedVisibility(visible && state.reconnecting, modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(Modifier.iptvPanel(RoundedCornerShape(14.dp), GlassRole.HUD).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LoadingIndicator(Modifier.size(20.dp))
            Text(stringResource(R.string.iptv_live_reconnecting), style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextPrimary)
        }
    }
}

@Composable
private fun NumberEntry(digits: String, state: IptvLiveState, modifier: Modifier) {
    val target = digits.toIntOrNull()?.let { state.channels.getOrNull(it - 1) }
    Column(modifier.iptvPanel(RoundedCornerShape(16.dp), GlassRole.HUD).padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.End) {
        Text(digits, style = MaterialTheme.typography.displaySmall, color = NuvioTheme.colors.TextPrimary, fontWeight = FontWeight.SemiBold)
        if (target != null || state.next == null) Text(target?.let(::channelName) ?: stringResource(R.string.iptv_live_number_missing), style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
    }
}

@Composable
private fun Banner(state: IptvLiveState, now: Long) {
    val index = state.channels.indexOfFirst { it.item.channel.id == state.playingId }
    val row = state.channels.getOrNull(index) ?: state.playingRow?.takeIf { it.item.channel.id == state.playingId }
    val catchup = state.catchup
    val programme = catchup ?: row?.let { liveProgramme(state, it.item.channel.id, now) }
    val following = if (catchup == null) row?.let { nextProgramme(state, it.item.channel.id, programme, now) } else null
    val position by produceState(0L, state.player, catchup) {
        while (catchup != null) { value = state.player?.currentPosition ?: 0L; delay(1_000) }
    }
    Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .55f))))
        .padding(start = 40.dp, end = 40.dp, top = 48.dp, bottom = 28.dp)) {
        Row(Modifier.fillMaxWidth().iptvPanel(RoundedCornerShape(18.dp), GlassRole.HUD).padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(row?.let(::logoUrl), row?.let(::channelName) ?: state.playingTitle.orEmpty(), Modifier.size(112.dp, 64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (index >= 0) Text("${index + 1}", style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextTertiary)
                    Text(row?.let(::channelName) ?: state.playingTitle.orEmpty(), style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Tag(stringResource(if (catchup != null) R.string.iptv_live_catchup else R.string.iptv_live_playing), live = catchup == null)
                    qualityBadges(state.player).forEach { Tag(it) }
                }
                Text(programme?.let(::title) ?: stringResource(R.string.iptv_live_no_programme), style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                programme?.let { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(timeRange(item), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        val elapsed = if (catchup != null) item.start.epochMillis + position else now
                        progress(item, elapsed)?.let { ProgressLine(it, Modifier.width(260.dp)) }
                        if (catchup != null) Text(clock(elapsed), color = NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.bodyMedium)
                        else item.stop?.epochMillis?.let { stop -> if (stop > now) Text(stringResource(R.string.iptv_live_minutes_left, ((stop - now) / MINUTE_MILLIS).toInt() + 1),
                            color = NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                following?.let { Text(stringResource(R.string.iptv_live_up_next, title(it), clock(it.start.epochMillis)),
                    color = NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(clock(now), style = MaterialTheme.typography.headlineSmall, color = NuvioTheme.colors.TextPrimary)
                Text(stringResource(if (catchup != null) R.string.iptv_live_catchup_hint else R.string.iptv_live_fullscreen_hint), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary,
                    maxLines = 2, modifier = Modifier.widthIn(max = 220.dp))
            }
        }
    }
}

@Composable
private fun ChannelPanel(state: IptvLiveState, now: Long, onWatch: (IptvListedChannel) -> Unit) {
    var selected by remember { mutableStateOf(state.channels.firstOrNull { it.item.channel.id == state.playingId }) }
    val recent = state.recent.drop(1).mapNotNull { id -> state.channels.firstOrNull { it.item.channel.id == id } }.take(4)
    val start = remember { FocusRequester() }
    val playingIndex = state.channels.indexOfFirst { it.item.channel.id == state.playingId }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (playingIndex - 3).coerceAtLeast(0))
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { start.requestFocus() } }
    Row(Modifier.fillMaxHeight().padding(start = 28.dp, top = 28.dp, bottom = 28.dp)
        .iptvPanel().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.width(440.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (recent.isNotEmpty()) {
                SectionLabel(stringResource(R.string.iptv_live_recent))
                recent.forEach { row -> PanelChannel(row, state, now, Modifier, { selected = row }, { onWatch(row) }) }
                Spacer(Modifier.height(4.dp))
            }
            SectionLabel(stringResource(R.string.iptv_live_all))
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.channels, key = { it.item.channel.id }) { row ->
                    PanelChannel(row, state, now, if (row.item.channel.id == state.playingId || (playingIndex < 0 && row == state.channels.first())) Modifier.focusRequester(start) else Modifier,
                        { selected = row }, { onWatch(row) })
                }
            }
        }
        Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val row = selected
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (row != null) ChannelLogo(logoUrl(row), channelName(row), Modifier.size(64.dp, 38.dp))
                Text(row?.let(::channelName).orEmpty(), style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val schedule = row?.let { channel -> state.guide[channel.item.channel.id]?.cells?.filterIsInstance<GuideProgrammeCell>()?.filter { it.endMillis > now }?.take(9) }.orEmpty()
            if (schedule.isEmpty()) Text(stringResource(R.string.iptv_live_no_programme), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            schedule.forEach { cell ->
                val live = airing(cell.programme, now)
                Row(Modifier.fillMaxWidth().iptvItem(false, selected = live).padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(clock(cell.programme.start.epochMillis), color = NuvioTheme.colors.TextTertiary,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
                    Text(title(cell.programme), color = if (live) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                        fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (live) Tag(stringResource(R.string.iptv_live_playing), live = true)
                }
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary,
        letterSpacing = 1.2.sp, maxLines = 1,
        modifier = modifier.padding(start = 10.dp, top = 4.dp, bottom = 2.dp))
}

@Composable
private fun PanelChannel(row: IptvListedChannel, state: IptvLiveState, now: Long, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val number = state.channels.indexOf(row) + 1
    val programme = liveProgramme(state, row.item.channel.id, now)
    val playing = row.item.channel.id == state.playingId
    Row(modifier.fillMaxWidth()
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .iptvItem(focused, selected = playing)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) { onClick(); true } else false
        }
        .focusable().padding(horizontal = 10.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$number", color = NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
        ChannelLogo(logoUrl(row), channelName(row), Modifier.size(48.dp, 30.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(channelName(row), color = itemContent(focused), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, fontWeight = if (playing || focused) FontWeight.SemiBold else FontWeight.Normal)
            Text(programme?.let(::title) ?: stringResource(R.string.iptv_live_no_programme_short), color = NuvioTheme.colors.TextSecondary,
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            programme?.let { item -> progress(item, now)?.let { ProgressLine(it, Modifier.fillMaxWidth(.6f)) } }
        }
    }
}

@Composable
internal fun LiveVideo(player: ExoPlayer?, playback: IptvLivePlayback?, modifier: Modifier, resize: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    provider: String? = null) {
    Box(modifier.background(Color.Black)) {
        AndroidView(factory = { context -> PlayerView(context).apply {
            useController = false; isFocusable = false; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setShutterBackgroundColor(android.graphics.Color.BLACK)
        } }, modifier = Modifier.fillMaxSize(),
            update = { it.player = player; it.keepScreenOn = player != null; it.resizeMode = resize }, onRelease = { it.player = null; it.keepScreenOn = false })
        playback?.let { IptvStatsOverlay(it, provider, Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 32.dp)) }
    }
}
