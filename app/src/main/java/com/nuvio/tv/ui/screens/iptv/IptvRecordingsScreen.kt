@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.net.Uri
import android.text.format.Formatter
import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.RecordingStatus
import com.nuvio.tv.data.iptv.IptvRecording
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.V2Atmosphere
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun IptvRecordingsScreen(onBack: () -> Unit, viewModel: IptvRecordingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf<IptvRecording?>(null) }
    var confirmDelete by remember { mutableStateOf<IptvRecording?>(null) }
    LaunchedEffect(state.ready, state.empty) {
        if (state.ready && !state.empty && !focused) { withFrameNanos { }; runCatching { first.requestFocus() }; focused = true }
    }
    val playing = state.playing != null
    LaunchedEffect(playing) {
        if (!playing && focused) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        LocalV2Appearance.current?.let { V2Atmosphere(rich = false, background = it.settingsBackground) }
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(340.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.iptv_recordings_title), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary)
                Text(stringResource(R.string.iptv_recordings_description), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                Spacer(Modifier.height(18.dp))
                Text(stringResource(R.string.iptv_recordings_hint), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextTertiary)
                Spacer(Modifier.weight(1f))
                state.freeBytes?.let {
                    Text(stringResource(R.string.iptv_recordings_free, Formatter.formatShortFileSize(context, it)),
                        style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
                }
                state.message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.Error) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                if (state.ready && state.empty) item(key = "empty") {
                    SettingsGroupCard(title = stringResource(R.string.iptv_recordings_title)) {
                        Text(stringResource(R.string.iptv_recordings_empty), color = NuvioTheme.colors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
                    }
                }
                val groups = listOf(R.string.iptv_recordings_group_now to state.recording, R.string.iptv_recordings_group_scheduled to state.scheduled,
                    R.string.iptv_recordings_group_recorded to state.recorded).filter { it.second.isNotEmpty() }
                groups.forEachIndexed { groupIndex, (title, entries) ->
                    item(key = "group:$title") {
                        SettingsGroupCard(title = stringResource(title)) {
                            entries.forEachIndexed { index, recording ->
                                RecordingRow(recording, Modifier.then(if (groupIndex == 0 && index == 0) Modifier.focusRequester(first) else Modifier),
                                    onClick = { if (recording.id in state.playable) viewModel.play(recording) else options = recording },
                                    onMenu = { options = recording })
                            }
                        }
                    }
                }
            }
        }
        state.playing?.let { playback -> key(playback.recording.id) { RecordingPlayer(playback, onClose = viewModel::closePlayer) } }
    }
    options?.let { recording ->
        val playable = recording.id in state.playable
        NuvioDialog(onDismiss = { options = null }, title = recording.title ?: recording.channelName,
            subtitle = recording.failure?.let { stringResource(iptvRecordingFailureMessage(it)) } ?: recording.description, width = 560.dp) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).focusRequester(focus), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (playable) RecordingOption(stringResource(R.string.iptv_recording_play), Icons.Filled.PlayArrow) { options = null; viewModel.play(recording) }
                if (recording.status == RecordingStatus.RECORDING) RecordingOption(stringResource(R.string.iptv_recording_stop), Icons.Filled.Stop) {
                    options = null; viewModel.stop(recording)
                }
                if (recording.status == RecordingStatus.SCHEDULED) RecordingOption(stringResource(R.string.iptv_recording_cancel), Icons.Filled.Cancel) {
                    options = null; viewModel.cancel(recording)
                }
                RecordingOption(stringResource(R.string.iptv_recording_delete), Icons.Filled.Delete) { options = null; confirmDelete = recording }
            }
        }
    }
    confirmDelete?.let { recording ->
        val cancel = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { cancel.requestFocus() } }
        NuvioDialog(onDismiss = { confirmDelete = null }, title = stringResource(R.string.iptv_recording_delete_title, recording.title ?: recording.channelName),
            subtitle = stringResource(R.string.iptv_recording_delete_description), width = 520.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NuvioActionPill({ confirmDelete = null }, Modifier.focusRequester(cancel)) { Text(stringResource(R.string.iptv_recording_keep)) }
                NuvioActionPill({ confirmDelete = null; viewModel.delete(recording) }) {
                    Text(stringResource(R.string.iptv_recording_delete_confirm), color = NuvioTheme.colors.Error)
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(recording: IptvRecording, modifier: Modifier, onClick: () -> Unit, onMenu: () -> Unit) {
    val context = LocalContext.current
    val longPress = rememberLongPressKeyTracker()
    val status = stringResource(iptvRecordingStatusLabel(recording.status))
    val size = recording.bytes.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
    SettingsActionRow(title = recording.title ?: recording.channelName, subtitle = null,
        subtitleContent = { _, _ -> RecordingLine(recording) },
        value = listOfNotNull(size, status).joinToString(" · "),
        valueColor = when (recording.status) {
            RecordingStatus.RECORDING -> NuvioTheme.colors.Error
            RecordingStatus.FAILED -> NuvioTheme.colors.Error
            else -> NuvioTheme.colors.TextSecondary
        },
        onClick = onClick, leadingIcon = statusIcon(recording.status), trailingIcon = null,
        modifier = modifier.onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                if (native.action == AndroidKeyEvent.ACTION_UP) onMenu()
                return@onPreviewKeyEvent true
            }
            longPress.handle(native, ::isSelect) { onMenu() }
        })
}

@Composable
private fun RecordingLine(recording: IptvRecording) {
    val now = System.currentTimeMillis()
    val start = recording.startedAtMillis ?: recording.startMillis
    val stop = if (recording.status.finished) recording.finishedAtMillis ?: recording.stopMillis else recording.stopMillis
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(start))
    val parts = listOfNotNull(
        recording.title?.let { recording.channelName },
        "$date ${clock(start)} – ${clock(stop)}",
        recording.failure?.let { stringResource(iptvRecordingFailureMessage(it)) },
        if (recording.gaps > 0 && recording.failure == null) stringResource(R.string.iptv_recording_gaps) else null,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(parts.joinToString(" · "), color = if (recording.status == RecordingStatus.FAILED) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (recording.status == RecordingStatus.RECORDING) {
            ProgressLine(((now - start).toFloat() / (recording.stopMillis - start).coerceAtLeast(1)), Modifier.fillMaxWidth(.5f))
        }
    }
}

@Composable
private fun RecordingOption(title: String, icon: ImageVector, onClick: () -> Unit) {
    SettingsActionRow(title = title, subtitle = null, onClick = onClick, leadingIcon = icon, trailingIcon = null)
}

private fun statusIcon(status: RecordingStatus): ImageVector = when (status) {
    RecordingStatus.SCHEDULED -> Icons.Filled.Schedule
    RecordingStatus.RECORDING -> Icons.Filled.FiberManualRecord
    RecordingStatus.DONE, RecordingStatus.PARTIAL -> Icons.Filled.PlayArrow
    RecordingStatus.FAILED -> Icons.Filled.ErrorOutline
    RecordingStatus.CANCELLED -> Icons.Filled.Cancel
}

@Composable
private fun RecordingPlayer(playback: IptvRecordingPlayback, onClose: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setMediaItem(MediaItem.Builder().setUri(Uri.fromFile(playback.file)).setMimeType(MimeTypes.VIDEO_MP2T).build())
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val focus = remember { FocusRequester() }
    var bar by remember { mutableStateOf(true) }
    var shownAt by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    fun show() { bar = true; shownAt = System.nanoTime() }
    fun seek(delta: Long) {
        val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + delta).coerceIn(0, maxOf(0, end - 1_000)))
        position = player.currentPosition
        show()
    }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    LaunchedEffect(bar, shownAt) { if (bar) { delay(BAR_MILLIS); bar = false } }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L
            playing = player.playWhenReady
            delay(500)
        }
    }
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.keyCode == AndroidKeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
        if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent true
        when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> seek(-SKIP_MILLIS)
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(SKIP_MILLIS)
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> { player.playWhenReady = !player.playWhenReady; show() }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { player.playWhenReady = true; show() }
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { player.playWhenReady = false; show() }
            else -> if (isSelect(native.keyCode) && native.repeatCount == 0) { if (bar) bar = false else show() }
        }
        true
    }.focusable()) {
        AndroidView(factory = { viewContext -> PlayerView(viewContext).apply {
            useController = false; isFocusable = false; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setShutterBackgroundColor(android.graphics.Color.BLACK)
        } }, modifier = Modifier.fillMaxSize(),
            update = { it.player = player; it.keepScreenOn = true }, onRelease = { it.player = null; it.keepScreenOn = false })
        if (bar) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f))))
                .padding(horizontal = 56.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(playback.recording.title ?: playback.recording.channelName, color = Color.White, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(playback.recording.title?.let { playback.recording.channelName },
                    if (!playing) stringResource(R.string.iptv_recording_paused) else null,
                    stringResource(R.string.iptv_recording_skip_hint)).joinToString(" · "),
                    color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.bodySmall)
                ProgressLine(if (duration > 0) position.toFloat() / duration else 0f, Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth()) {
                    Text(elapsed(position), color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(elapsed(duration), color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun elapsed(millis: Long): String {
    val seconds = millis / 1000
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

private const val SKIP_MILLIS = 30_000L
private const val BAR_MILLIS = 4_000L
