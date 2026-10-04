@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.PlayerChromeStyle
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.R
import com.nuvio.tv.data.local.*
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.player.V2PlayerActions
import kotlinx.coroutines.launch

/** Stable shared IDs survive panels, groups, style changes and capability changes. */
@Stable
internal class PlayerCustomFocusState {
    private val targets = PlayerControlAction.entries.associateWith { FocusRequester() }
    var lastFocused by mutableStateOf<PlayerControlAction?>(null)
    var pendingReturn by mutableStateOf<PlayerControlAction?>(null)
    var requested by mutableStateOf<PlayerControlAction?>(null)
    var requestGeneration by mutableIntStateOf(0)
    var buttonFocused by mutableStateOf(false)
    fun target(action: PlayerControlAction): FocusRequester = targets.getValue(action)
    fun restore(layout: PlayerControlLayout, available: Set<PlayerControlAction>) {
        requested = layout.focusFallback(pendingReturn ?: lastFocused, available)
        pendingReturn = null
        requestGeneration++
    }
}

@Composable
internal fun playerControlLabel(action: PlayerControlAction, playing: Boolean = false): String = stringResource(when (action) {
    PlayerControlAction.PLAY_PAUSE -> if (playing) R.string.player_pill_pause else R.string.player_pill_play
    PlayerControlAction.RESTART -> R.string.player_pill_restart
    PlayerControlAction.EPISODES -> R.string.player_pill_episodes
    PlayerControlAction.NEXT_EPISODE -> R.string.player_pill_next_episode
    PlayerControlAction.STATS -> R.string.cd_playback_stats
    PlayerControlAction.AUDIO -> R.string.cd_audio_tracks
    PlayerControlAction.SUBTITLES -> R.string.cd_subtitles
    PlayerControlAction.SOURCES -> R.string.cd_sources
    PlayerControlAction.MORE -> R.string.player_more_short
    PlayerControlAction.SPEED -> R.string.cd_playback_speed
    PlayerControlAction.ASPECT -> R.string.cd_aspect_ratio
    PlayerControlAction.EXTERNAL -> R.string.cd_open_external_player
    PlayerControlAction.ENGINE -> R.string.cd_switch_player_engine
    PlayerControlAction.REPORT -> R.string.player_report_issue
    PlayerControlAction.WATCH_PARTY -> R.string.party_title
    PlayerControlAction.INFO -> R.string.cd_stream_info
})

internal fun playerControlIcon(action: PlayerControlAction, playing: Boolean): ImageVector = when (action) {
    PlayerControlAction.PLAY_PAUSE -> if (playing) Icons.Default.Pause else Icons.Default.PlayArrow
    PlayerControlAction.RESTART -> Icons.Default.RestartAlt
    PlayerControlAction.EPISODES -> Icons.AutoMirrored.Filled.List
    PlayerControlAction.NEXT_EPISODE -> Icons.Default.SkipNext
    PlayerControlAction.STATS -> Icons.Default.Equalizer
    PlayerControlAction.AUDIO -> Icons.Default.Speaker
    PlayerControlAction.SUBTITLES -> Icons.Default.Subtitles
    PlayerControlAction.SOURCES -> Icons.Default.Cloud
    PlayerControlAction.MORE -> Icons.Default.MoreHoriz
    PlayerControlAction.SPEED -> Icons.Default.Speed
    PlayerControlAction.ASPECT -> Icons.Default.AspectRatio
    PlayerControlAction.EXTERNAL -> Icons.AutoMirrored.Filled.OpenInNew
    PlayerControlAction.ENGINE -> Icons.Default.SwapHoriz
    PlayerControlAction.REPORT -> Icons.Default.BugReport
    PlayerControlAction.WATCH_PARTY -> Icons.Default.Groups
    PlayerControlAction.INFO -> Icons.Default.Info
}

@Composable
internal fun playerControlPainter(action: PlayerControlAction, playing: Boolean): androidx.compose.ui.graphics.painter.Painter? = when (action) {
    PlayerControlAction.PLAY_PAUSE -> rememberRawSvgPainter(if (playing) R.raw.ic_player_pause else R.raw.ic_player_play)
    PlayerControlAction.AUDIO -> rememberRawSvgPainter(R.raw.ic_player_audio_filled)
    PlayerControlAction.SUBTITLES -> rememberRawSvgPainter(R.raw.ic_player_subtitles)
    PlayerControlAction.SOURCES -> rememberRawSvgPainter(R.raw.ic_player_source)
    PlayerControlAction.ASPECT -> rememberRawSvgPainter(R.raw.ic_player_aspect_ratio)
    PlayerControlAction.EPISODES -> rememberRawSvgPainter(R.raw.ic_player_episodes)
    else -> null
}

private fun actionCallback(action: PlayerControlAction, actions: V2PlayerActions): () -> Unit = when (action) {
    PlayerControlAction.PLAY_PAUSE -> actions.playPause
    PlayerControlAction.RESTART -> actions.seekToStart
    PlayerControlAction.EPISODES -> actions.episodes
    PlayerControlAction.NEXT_EPISODE -> actions.playNext
    PlayerControlAction.STATS -> actions.stats
    PlayerControlAction.AUDIO -> actions.audio
    PlayerControlAction.SUBTITLES -> actions.subtitles
    PlayerControlAction.SOURCES -> actions.sources
    PlayerControlAction.MORE -> actions.more
    PlayerControlAction.SPEED -> actions.speed
    PlayerControlAction.ASPECT -> actions.aspect
    PlayerControlAction.EXTERNAL -> actions.external
    PlayerControlAction.ENGINE -> actions.engine
    PlayerControlAction.REPORT -> actions.report
    PlayerControlAction.WATCH_PARTY -> actions.party
    PlayerControlAction.INFO -> actions.info
}

@Composable
internal fun PlayerCustomControls(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    layout: PlayerControlLayout,
    available: Set<PlayerControlAction>,
    focus: PlayerCustomFocusState,
    progressFocus: FocusRequester,
    progressUpFocus: FocusRequester?,
    containerFocus: FocusRequester,
    hasSeekTimeline: Boolean,
    onSkipAnchorChanged: (Dp) -> Unit,
    actions: V2PlayerActions,
    reportCodeVisible: Boolean
) {
    val v2 = LocalV2Appearance.current != null
    val appearance = LocalV2Appearance.current
    val chromePolicy = playerControlChromePolicy(v2, appearance?.playerChromeStyle == PlayerChromeStyle.CONTROL_DECK, appearance?.visualStyle == VisualStyle.CINEMATIC_GLASS)
    val density = LocalDensity.current
    val root = LocalView.current
    val deckAvailable = playerControlDeckAvailable(layout, available, state.showMoreDialog)
    val collapsed = playerControlCollapsedActions(layout, available)
    val order = layout.focusOrder(deckAvailable)
    val fallback = layout.focusFallback(focus.lastFocused, deckAvailable)
    val scope = rememberCoroutineScope()
    val recoveryFocus = if (hasSeekTimeline) progressFocus else containerFocus
    var rowHeight by remember { mutableIntStateOf(0) }
    val bottomPadding = if (v2) 24.dp else 48.dp
    val bottomFraction = viewModel.controller.videoBottomFractionState.value
    val band = bottomFraction?.takeIf { it > .5f && it < .995f && root.height > 0 }?.let {
        with(density) { ((1f - it) * root.height).toDp() }
    }
    val gap = if (band != null && band >= 64.dp && rowHeight > 0 &&
        state.resizeMode == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT) {
        (band - with(density) { rowHeight.toDp() } - bottomPadding).coerceAtLeast(8.dp)
    } else 8.dp
    val panelVisible = state.showStreamInfoOverlay || state.showEpisodesPanel ||
        state.showSourcesPanel || state.showAudioOverlay || state.showSubtitleOverlay || state.showSubtitleStylePanel ||
        state.showSubtitleTimingDialog || state.showSubtitleDelayOverlay || state.showSpeedDialog ||
        state.showPartyPanel || state.postPlayMode is PostPlayMode.StillWatching
    LaunchedEffect(focus.requestGeneration, layout, deckAvailable, state.showMoreDialog, hasSeekTimeline, panelVisible) {
        if (focus.requestGeneration == 0) return@LaunchedEffect
        if (panelVisible) { focus.requestGeneration = 0; return@LaunchedEffect }
        if (!state.showMoreDialog && focus.requested in collapsed) { actions.more(); return@LaunchedEffect }
        withFrameNanos { }; withFrameNanos { }
        val target = if (state.showMoreDialog) playerControlPopupFocusFallback(layout, available, focus.requested)
            else layout.focusFallback(focus.requested, deckAvailable)
        if (state.showMoreDialog && target == null) { focus.requestGeneration = 0; return@LaunchedEffect }
        val result = runCatching { (target?.let(focus::target) ?: recoveryFocus).requestFocus() }
        if (result.getOrNull() != true) runCatching { recoveryFocus.requestFocus() }
        focus.requestGeneration = 0
    }
    // Capture before removing the focused target can clear the group's focus state.
    val needsRecovery = !state.showMoreDialog && focus.buttonFocused && focus.lastFocused !in order
    SideEffect { if (needsRecovery && !panelVisible && focus.requestGeneration == 0) {
        if (PlayerControlAction.MORE in order) focus.pendingReturn = PlayerControlAction.MORE
        focus.restore(layout, deckAvailable)
    } }
    LaunchedEffect(Unit) { if (!panelVisible) focus.restore(layout, deckAvailable) }
    fun invoke(action: PlayerControlAction) {
        actions.interaction()
        focus.pendingReturn = action
        actionCallback(action, actions)()
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = chromePolicy.backgroundAlpha)))))
        PlayerControlChrome(v2, Modifier.align(Alignment.BottomCenter).verticalScroll(rememberScrollState()),
            gap = gap,
            title = {
            if (state.activeSkipInterval == null) {
                val title = state.contentName ?: state.title
                var failed by remember(state.logo) { mutableStateOf(false) }
                if (!state.logo.isNullOrBlank() && !failed) AsyncImage(state.logo, title,
                    Modifier.sizeIn(maxWidth = 300.dp, maxHeight = 64.dp), contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart, onError = { failed = true })
                else Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge)
                PlayerSecondaryMetadata(state)
            }
            }, timeline = {
            if (hasSeekTimeline) Box(Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                if (root.height > 0) onSkipAnchorChanged(with(density) {
                    (root.height - coordinates.positionInRoot().y).toDp() + 8.dp
                })
            }) {
                PlayerControlsProgressBarHost(viewModel, progressFocus,
                    upFocusRequester = progressUpFocus,
                    downFocusRequester = fallback?.let(focus::target),
                    onUpKey = actions.hide, onFocused = actions.interaction)
            }
            }, time = {
            if (hasSeekTimeline) PlayerControlsTimeTextHost(viewModel)
            }, deck = {
            Row(Modifier.fillMaxWidth().onGloballyPositioned {
                    rowHeight = it.size.height
                    if (!hasSeekTimeline && root.height > 0) onSkipAnchorChanged(with(density) {
                        (root.height - it.positionInRoot().y).toDp() + 8.dp
                    })
                }
                .onFocusChanged { focus.buttonFocused = it.hasFocus }.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerControlDeck(layout, available, focus::target, state.isPlaying, moreExpanded = state.showMoreDialog,
                    popupSelected = focus.requested ?: focus.pendingReturn,
                    onMoreDismiss = {
                        actions.interaction()
                        viewModel.onEvent(PlayerEvent.OnDismissMoreDialog)
                        focus.pendingReturn = PlayerControlAction.MORE
                        focus.restore(layout, playerControlDeckAvailable(layout, available, false))
                    },
                    upFocus = if (hasSeekTimeline) progressFocus else progressUpFocus ?: containerFocus,
                    onBottom = actions.hide, onClick = ::invoke,
                    onFocused = { focus.lastFocused = it; actions.interaction() }, modifier = Modifier.weight(1f))
            }
            if (reportCodeVisible && !state.playbackIssueReportId.isNullOrBlank()) Text(
                state.playbackIssueReportId, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall)
            })
    }
    }
}
