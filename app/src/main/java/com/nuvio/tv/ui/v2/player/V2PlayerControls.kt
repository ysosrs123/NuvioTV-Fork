package com.nuvio.tv.ui.v2.player

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.PlayerChromeStyle
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.screens.player.*
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.*

enum class V2PlayerControl { INFO, STATS, AUDIO, SUBTITLES, SOURCES, EPISODES, RESTART, MORE, SPEED, ASPECT, EXTERNAL, ENGINE, REPORT }

/** Owned by PlayerScreen, so requesters survive a utility panel replacing the control deck. */
@Stable
class V2PlayerFocusState {
    private val targets = V2PlayerControl.entries.associateWith { FocusRequester() }
    var pendingReturn: V2PlayerControl? = null
    var requestedControl by mutableStateOf<V2PlayerControl?>(null)
    fun target(control: V2PlayerControl): FocusRequester = targets.getValue(control)
}

data class V2PlayerActions(
    val playPause: () -> Unit, val playNext: () -> Unit,
    val seekToStart: () -> Unit, val episodes: () -> Unit, val sources: () -> Unit,
    val audio: () -> Unit, val subtitles: () -> Unit, val speed: () -> Unit,
    val aspect: () -> Unit, val engine: () -> Unit, val report: () -> Unit,
    val more: () -> Unit, val external: () -> Unit, val info: () -> Unit,
    val stats: () -> Unit, val interaction: () -> Unit, val hide: () -> Unit,
    val party: () -> Unit = {}
)

private data class Utility(val id: V2PlayerControl, val icon: ImageVector, val label: String, val action: () -> Unit, val panel: Boolean = false, val enabled: Boolean = true)

@Composable
fun V2PlayerControls(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    focus: V2PlayerFocusState,
    playFocus: FocusRequester,
    progressFocus: FocusRequester,
    statsFocus: FocusRequester,
    progressUpFocus: FocusRequester?,
    onSkipAnchorChanged: (Dp) -> Unit,
    actions: V2PlayerActions,
    reportCodeVisible: Boolean
) {
    val deck = LocalV2Appearance.current?.playerChromeStyle != PlayerChromeStyle.INVISIBLE
    val cinematicGlass = LocalV2Appearance.current?.visualStyle == VisualStyle.CINEMATIC_GLASS
    val density = LocalDensity.current
    val view = LocalView.current
    val utilities = buildList {
        // INFO remains in the shared action model for Original; dormant in V2.
        add(Utility(V2PlayerControl.STATS, Icons.Default.Equalizer, stringResource(R.string.cd_playback_stats), {
            actions.stats()
        }))
        if (uiState.audioTracks.isNotEmpty()) add(Utility(V2PlayerControl.AUDIO, Icons.Default.Speaker, stringResource(R.string.cd_audio_tracks), actions.audio, true))
        if (uiState.subtitleTracks.isNotEmpty() || uiState.addonSubtitles.isNotEmpty()) add(Utility(V2PlayerControl.SUBTITLES, Icons.Default.Subtitles, stringResource(R.string.cd_subtitles), actions.subtitles, true))
        add(Utility(V2PlayerControl.SOURCES, Icons.Default.Cloud, stringResource(R.string.cd_sources), actions.sources, true))
        if (uiState.currentSeason != null && uiState.currentEpisode != null) add(Utility(V2PlayerControl.EPISODES, Icons.AutoMirrored.Filled.List, stringResource(R.string.player_pill_episodes), actions.episodes, true))
        add(Utility(V2PlayerControl.MORE, Icons.Default.MoreHoriz, stringResource(R.string.player_more_actions_title), actions.more))
    }
    val more = buildList {
        add(Utility(V2PlayerControl.SPEED, Icons.Default.Speed, stringResource(R.string.cd_playback_speed), actions.speed, true))
        add(Utility(V2PlayerControl.ASPECT, Icons.Default.AspectRatio, stringResource(R.string.cd_aspect_ratio), actions.aspect))
        if (!uiState.isServerStream) add(Utility(V2PlayerControl.EXTERNAL, Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.cd_open_external_player), actions.external))
        add(Utility(V2PlayerControl.ENGINE, Icons.Default.SwapHoriz, stringResource(R.string.cd_switch_player_engine), actions.engine))
        if (uiState.playbackIssueReportsEnabled) add(Utility(V2PlayerControl.REPORT, Icons.Default.BugReport,
            if (reportCodeVisible && uiState.playbackIssueReportId != null) uiState.playbackIssueReportId else stringResource(R.string.player_report_issue),
            actions.report, enabled = uiState.playbackIssueReportStatus != PlaybackIssueReportStatus.Sending && uiState.playbackIssueReportStatus != PlaybackIssueReportStatus.Sent))
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(240.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent,
                Color.Black.copy(alpha = if (cinematicGlass) .20f else if (deck) .55f else .78f)))))
        Column(Modifier.align(Alignment.BottomCenter)
            .padding(start = 32.dp, end = 32.dp, bottom = 24.dp)
            .fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (uiState.activeSkipInterval == null) {
                val title = uiState.contentName ?: uiState.title
                var logoFailed by remember(uiState.logo) { mutableStateOf(false) }
                if (!uiState.logo.isNullOrBlank() && !logoFailed) {
                    AsyncImage(uiState.logo, title, Modifier.padding(start = 12.dp).sizeIn(maxWidth = 240.dp, maxHeight = 54.dp),
                        contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, onError = { logoFailed = true })
                } else Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
                PlayerSecondaryMetadata(uiState, Modifier.padding(start = 12.dp))
            }
            Column(Modifier.fillMaxWidth()
                .then(if (deck) Modifier.nuvioGlass(GlassRole.CONTROL, shape = RoundedCornerShape(12.dp)) else Modifier)
                .padding(horizontal = 12.dp, vertical = if (deck) 14.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                    onSkipAnchorChanged(with(density) { (view.height - coordinates.positionInRoot().y).toDp() } + 12.dp)
                }) {
                    PlayerControlsProgressBarHost(viewModel, progressFocus,
                        upFocusRequester = progressUpFocus ?: FocusRequester.Cancel,
                        downFocusRequester = playFocus, onFocused = actions.interaction)
                }
                PlayerControlsTimeTextHost(viewModel)
                Spacer(Modifier.height(if (deck) 8.dp else 16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    V2TransportButton(if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        stringResource(if (uiState.isPlaying) R.string.player_pill_pause else R.string.player_pill_play),
                        playFocus, progressFocus, deck, actions.playPause, actions)
                    V2TransportButton(Icons.Default.RestartAlt, stringResource(R.string.player_pill_restart),
                        focus.target(V2PlayerControl.RESTART), progressFocus, false, actions.seekToStart, actions)
                    if (uiState.nextEpisode?.hasAired == true && (uiState.postPlayMode as? PostPlayMode.AutoPlay)?.let { !it.searching && it.countdownSec == null } != false) {
                        V2TransportButton(Icons.Default.SkipNext, stringResource(R.string.player_pill_next_episode),
                            remember { FocusRequester() }, progressFocus, false, actions.playNext, actions)
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        UtilityRow(if (uiState.showMoreDialog) utilities.dropLast(1) + more + utilities.last() else utilities,
                            focus, statsFocus, progressFocus, actions)
                    }
                }

            }
        }
    }
}

@Composable
private fun V2TransportButton(icon: ImageVector, label: String, requester: FocusRequester,
    progress: FocusRequester, labelled: Boolean, onClick: () -> Unit, actions: V2PlayerActions) {
    V2StyledTransportButton(icon, label, requester, progress, labelled, onClick, actions.interaction, actions.hide)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun V2StyledTransportButton(icon: ImageVector, label: String, requester: FocusRequester?,
    progress: FocusRequester?, labelled: Boolean, onClick: () -> Unit, onInteraction: () -> Unit, onDown: (() -> Unit)?, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = if (labelled) RoundedCornerShape(9.dp) else CircleShape
    val solidPrimary = labelled && LocalV2Appearance.current?.visualStyle != VisualStyle.CINEMATIC_GLASS
    Button(onClick, modifier = modifier.height(42.dp)
        .then(if (!labelled) Modifier.width(42.dp) else Modifier)
        .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
        .then(if (progress != null) Modifier.focusProperties { up = progress } else Modifier)
        .onFocusChanged { focused = it.isFocused; if (focused) onInteraction() }
        .onPreviewKeyEvent { event ->
            if (onDown != null && event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) onDown()
                true
            } else false
        }.semantics { contentDescription = label }.nuvioV2Focus(focused, shape, hardwareShadow = false)
        .nuvioGlass(GlassRole.CONTROL, focused, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(containerColor = if (solidPrimary) Color(0xE8E8F1FF) else Color.Transparent,
            focusedContainerColor = if (solidPrimary) Color(0xFFE8F1FF) else Color.Transparent,
            contentColor = if (solidPrimary) Color(0xFF071323) else Color.White,
            focusedContentColor = if (solidPrimary) Color(0xFF071323) else Color.White),
        contentPadding = PaddingValues(horizontal = if (labelled) 18.dp else 0.dp, vertical = 0.dp)) {
        Icon(icon, null, Modifier.size(22.dp))
        if (labelled) { Spacer(Modifier.width(8.dp)); Text(label, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun UtilityRow(items: List<Utility>, focus: V2PlayerFocusState, statsFocus: FocusRequester,
    progressFocus: FocusRequester, actions: V2PlayerActions) {
    // The deck leaves composition while a panel is open. Attach the launching item
    // even when it lived beyond the lazy row's viewport at a large UI scale.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex =
        items.indexOfFirst { it.id == focus.pendingReturn }.coerceAtLeast(0))
    val requested = focus.requestedControl
    LaunchedEffect(requested) {
        val index = items.indexOfFirst { it.id == requested }
        if (index >= 0) {
            // More can be outside the viewport after Speed rebuilt the primary dock.
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == requested }
            if (visible == null || visible.offset < listState.layoutInfo.viewportStartOffset ||
                visible.offset + visible.size > listState.layoutInfo.viewportEndOffset) {
                listState.animateScrollToItem(index)
            }
            repeat(2) { withFrameNanos { } }
            if (focus.requestedControl == requested) {
                val target = if (requested == V2PlayerControl.STATS) statsFocus else focus.target(items[index].id)
                if (target.requestFocus()) focus.requestedControl = null
            }
        }
    }
    // Fixed viewport avoids a second layout jump while keyed items move into place.
    LazyRow(modifier = Modifier.fillMaxWidth(), state = listState, contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        items(items, key = { it.id }) { item ->
            val requester = if (item.id == V2PlayerControl.STATS) statsFocus else focus.target(item.id)
            PlayerUtility(item, requester, progressFocus,
                modifier = Modifier.animateItem(fadeInSpec = tween(V2Motion.PanelInMs), placementSpec = tween(V2Motion.PanelInMs), fadeOutSpec = tween(V2Motion.PanelOutMs)), onClick = {
                if (item.panel) focus.pendingReturn = item.id
                item.action()
            }, onFocused = actions.interaction)
        }
    }
}

@Composable
private fun PlayerUtility(item: Utility, focus: FocusRequester, down: FocusRequester,
    modifier: Modifier = Modifier, onClick: () -> Unit, onFocused: () -> Unit) {
    V2StyledUtilityButton(item.icon, item.label, item.enabled, onClick, onFocused, modifier, focus, down)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun V2StyledUtilityButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, onFocused: () -> Unit,
    modifier: Modifier = Modifier, focus: FocusRequester? = null, up: FocusRequester? = null) {
    var focused by remember { mutableStateOf(false) }
    val shape = CircleShape
    Column(modifier.size(42.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Button(onClick, enabled = enabled,
            modifier = Modifier.size(36.dp).then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .then(if (up != null) Modifier.focusProperties { this.up = up } else Modifier)
                .onFocusChanged { focused = it.isFocused; if (focused) onFocused() }
                .semantics { contentDescription = label }
                .nuvioV2Focus(focused, shape, hardwareShadow = false).nuvioGlass(GlassRole.CONTROL, focused, shape),
            scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(shape),
            colors = ButtonDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent,
                contentColor = Color.White, focusedContentColor = Color.White),
            contentPadding = PaddingValues(0.dp)) {
            Icon(icon, null, Modifier.size(18.dp))
        }

    }
}
