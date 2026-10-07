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
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.tv.material3.Icon
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
    onPause: (Boolean?) -> Unit, onRewind: () -> Boolean,
    layout: PlayerControlLayout, canStartOver: Boolean, onControl: (PlayerControlAction) -> Unit,
    onScrub: (Long) -> Boolean = { false }, onCloseInset: () -> Unit = {}, onSwapInset: () -> Unit = {}, onChannelMenu: (IptvListedChannel) -> Unit = {}) {
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
    BackHandler(state.inset != null && !panel && !controls) { onCloseInset() }
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
        if (native.repeatCount > 0 && native.keyCode in ZAP_KEYS && !(state.catchup != null && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT)) return@onPreviewKeyEvent true
        val player = state.player
        if (state.inset != null && native.keyCode in SWAP_KEYS) { onSwapInset(); return@onPreviewKeyEvent true }
        if (state.catchup != null && (player != null || state.tuning)) {
            val step = when (native.keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> SEEK_STEP
                AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> -SEEK_STEP
                else -> 0L
            }
            if (step != 0L) { if (player != null && onScrub(step)) banner++; return@onPreviewKeyEvent true }
        }
        if (player != null) when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { onPause(null); banner++; return@onPreviewKeyEvent true }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { onPause(false); banner++; return@onPreviewKeyEvent true }
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { onPause(true); banner++; return@onPreviewKeyEvent true }
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> if (state.catchup == null) { if (onRewind()) banner++; return@onPreviewKeyEvent true }
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
            state.sources.firstOrNull { it.ref.sourceId == (state.playingRow?.item?.channel?.sourceId ?: state.source?.sourceId) }?.label)
        PlaybackState(state, Modifier.align(Alignment.Center), large = true)
        ReconnectingPill(state, Modifier.align(Alignment.TopStart).padding(36.dp))
        state.message?.let { message ->
            Text(stringResource(message), style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 36.dp).iptvPanel(RoundedCornerShape(14.dp), GlassRole.HUD)
                    .padding(horizontal = 18.dp, vertical = 10.dp).widthIn(max = 720.dp))
        }
        if (digits.isNotEmpty()) NumberEntry(digits, state, Modifier.align(Alignment.TopEnd).padding(36.dp))
        state.inset?.let { inset ->
            InsetPicture(state, inset, now, Modifier.align(Alignment.BottomEnd)
                .padding(end = 36.dp, bottom = if (controls || (banner >= 0 && !panel)) 236.dp else 36.dp).width(400.dp).aspectRatio(16f / 9f))
        }
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
            }, onHide = { controls = false }, onScrub = { onScrub(it) })
        }
        AnimatedVisibility(banner >= 0 && !panel && !controls, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut() + slideOutVertically { it / 3 }) {
            Banner(state, now)
        }
        AnimatedVisibility(panel, Modifier.align(Alignment.CenterStart),
            enter = fadeIn() + slideInHorizontally { -it / 4 }, exit = fadeOut() + slideOutHorizontally { -it / 4 }) {
            ChannelPanel(state, now, onWatch = { panel = false; onWatch(it) }, onMenu = { panel = false; onChannelMenu(it) })
        }
    }
}

private const val SEEK_STEP = com.nuvio.tv.core.iptv.CatchupScrub.STEP_MILLIS
private val SWAP_KEYS = setOf(AndroidKeyEvent.KEYCODE_WINDOW, AndroidKeyEvent.KEYCODE_PROG_YELLOW)
private val ZAP_KEYS = setOf(AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_UP,
    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_LAST_CHANNEL)

@Composable
internal fun PlaybackState(state: IptvLiveState, modifier: Modifier, large: Boolean) {
    val waiting = state.playingId != null && !state.playing && !state.paused && (state.tuning || state.player != null) && !state.reconnecting
    if (waiting) Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LoadingIndicator(Modifier.size(if (large) 56.dp else 40.dp))
        Text(stringResource(R.string.iptv_live_connecting), style = if (large) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = .85f))
    }
    if (state.paused && state.player != null) Row(modifier.iptvPanel(RoundedCornerShape(if (large) 20.dp else 14.dp), GlassRole.HUD)
        .padding(horizontal = if (large) 22.dp else 14.dp, vertical = if (large) 14.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Pause, null, Modifier.size(if (large) 32.dp else 22.dp), tint = NuvioTheme.colors.TextPrimary)
        Text(stringResource(R.string.iptv_live_paused), style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextPrimary)
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
    val position by rememberCatchupPosition(state)
    val programme = if (catchup != null) position?.let { catchupShown(state, it) } ?: catchup else row?.let { liveProgramme(state, it.item.channel.id, now) }
    val following = if (catchup == null) row?.let { nextProgramme(state, it.item.channel.id, programme, now) } else null
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
                    Tag(stringResource(playbackTag(state, programme, now)), live = catchup == null)
                    if (state.paused) Tag(stringResource(R.string.iptv_live_paused))
                    qualityBadges(state.player).forEach { Tag(it) }
                }
                Text(programme?.let(::title) ?: stringResource(R.string.iptv_live_no_programme), style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (catchup != null) ScrubTimeline(programme, position, now, state.scrubTarget != null, Modifier.fillMaxWidth())
                else programme?.let { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(timeRange(item), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        progress(item, now)?.let { ProgressLine(it, Modifier.width(260.dp)) }
                        item.stop?.epochMillis?.let { stop -> if (stop > now) Text(stringResource(R.string.iptv_live_minutes_left, ((stop - now) / MINUTE_MILLIS).toInt() + 1),
                            color = NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                following?.let { Text(stringResource(R.string.iptv_live_up_next, title(it), clock(it.start.epochMillis)),
                    color = NuvioTheme.colors.TextTertiary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(clock(now), style = MaterialTheme.typography.headlineSmall, color = NuvioTheme.colors.TextPrimary)
                Text(stringResource(when {
                    state.paused && catchup == null -> if (row != null && hasArchive(row) && state.timeshiftEnabled && programme != null) R.string.iptv_live_paused_archive_hint else R.string.iptv_live_paused_live_hint
                    state.inset != null -> R.string.iptv_inset_hint
                    catchup != null -> R.string.iptv_scrub_banner_hint
                    row != null && hasArchive(row) && state.timeshiftEnabled -> R.string.iptv_live_archive_hint
                    else -> R.string.iptv_live_fullscreen_hint
                }), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary,
                    maxLines = 2, modifier = Modifier.widthIn(max = 220.dp))
            }
        }
    }
}

@Composable
internal fun ChannelPanel(state: IptvLiveState, now: Long, onWatch: (IptvListedChannel) -> Unit, channels: List<IptvListedChannel> = state.channels,
    header: @Composable () -> Unit = {}, onNearEnd: () -> Unit = {}, onMenu: ((IptvListedChannel) -> Unit)? = null) {
    var selected by remember(channels.firstOrNull()?.item?.channel?.id) { mutableStateOf(channels.firstOrNull { it.item.channel.id == state.playingId } ?: channels.firstOrNull()) }
    val recent = if (channels !== state.channels) emptyList() else state.recent.drop(1).mapNotNull { id -> state.channels.firstOrNull { it.item.channel.id == id } }.take(4)
    val start = remember { FocusRequester() }
    val playingIndex = channels.indexOfFirst { it.item.channel.id == state.playingId }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (playingIndex - 3).coerceAtLeast(0))
    LaunchedEffect(channels.firstOrNull()?.item?.channel?.id) { withFrameNanos { }; runCatching { start.requestFocus() } }
    Row(Modifier.fillMaxHeight().padding(start = 28.dp, top = 28.dp, bottom = 28.dp)
        .iptvPanel().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.width(440.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            header()
            if (recent.isNotEmpty()) {
                SectionLabel(stringResource(R.string.iptv_live_recent))
                recent.forEach { row -> PanelChannel(row, state, now, state.channels.indexOf(row) + 1, Modifier, { selected = row }, { onWatch(row) }, onMenu?.let { { it(row) } }) }
                Spacer(Modifier.height(4.dp))
            }
            SectionLabel(stringResource(R.string.iptv_live_all))
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(channels, key = { _, row -> row.item.channel.id }) { index, row ->
                    if (index >= channels.size - 8) LaunchedEffect(channels.size) { onNearEnd() }
                    PanelChannel(row, state, now, index + 1, if (row.item.channel.id == state.playingId || (playingIndex < 0 && index == 0)) Modifier.focusRequester(start) else Modifier,
                        { selected = row }, { onWatch(row) }, onMenu?.let { { it(row) } })
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
            val schedule = row?.let { channel -> guideRow(state, channel.item.channel.id)?.cells?.filterIsInstance<GuideProgrammeCell>()?.filter { it.endMillis > now }?.take(9) }.orEmpty()
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
private fun PanelChannel(row: IptvListedChannel, state: IptvLiveState, now: Long, number: Int, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit,
    onMenu: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    val longPress = rememberLongPressKeyTracker()
    var held by remember { mutableStateOf(false) }
    val programme = liveProgramme(state, row.item.channel.id, now)
    val playing = row.item.channel.id == state.playingId
    Row(modifier.fillMaxWidth()
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .iptvItem(focused, selected = playing)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (onMenu != null && native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0 && isSelect(native.keyCode)) held = false
            if (onMenu != null && longPress.handle(native, ::isSelect) { held = true; onMenu() }) {
                if (native.action == AndroidKeyEvent.ACTION_UP) held = false
                return@onPreviewKeyEvent true
            }
            if (onMenu != null && native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_MENU) { onMenu(); return@onPreviewKeyEvent true }
            if (native.action == AndroidKeyEvent.ACTION_UP && isSelect(native.keyCode)) { if (!held) onClick(); held = false; true } else false
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
    provider: String? = null, texture: Boolean = false) {
    Box(modifier.background(Color.Black)) {
        if (texture) AndroidView(factory = { context -> android.view.TextureView(context).apply { isFocusable = false } }, modifier = Modifier.fillMaxSize(),
            update = { view -> player?.setVideoTextureView(view); view.keepScreenOn = player != null },
            onRelease = { view -> player?.clearVideoTextureView(view); view.keepScreenOn = false })
        else AndroidView(factory = { context -> PlayerView(context).apply {
            useController = false; isFocusable = false; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setShutterBackgroundColor(android.graphics.Color.BLACK)
        } }, modifier = Modifier.fillMaxSize(),
            update = { it.player = player; it.keepScreenOn = player != null; it.resizeMode = resize }, onRelease = { it.player = null; it.keepScreenOn = false })
        playback?.let { IptvStatsOverlay(it, provider, Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 32.dp)) }
    }
}

@Composable
private fun InsetPicture(state: IptvLiveState, inset: IptvTile, now: Long, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.clip(shape).background(Color.Black, shape).border(2.dp, NuvioTheme.colors.TextPrimary.copy(alpha = .35f), shape)) {
        LiveVideo(inset.player, null, Modifier.fillMaxSize(), texture = true)
        if (inset.failure != null) Text(stringResource(inset.failure), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .85f),
            modifier = Modifier.align(Alignment.Center).padding(16.dp))
        else if (!inset.playing) LoadingIndicator(Modifier.align(Alignment.Center).size(32.dp))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .7f)))).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(channelName(inset.row), style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            liveProgramme(state, inset.row.item.channel.id, now)?.let {
                Text(title(it), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
