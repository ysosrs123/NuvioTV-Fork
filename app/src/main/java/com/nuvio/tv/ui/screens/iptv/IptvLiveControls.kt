@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.PlayerControlAction
import com.nuvio.tv.data.local.PlayerControlLayout
import com.nuvio.tv.ui.screens.player.PlayerControlChrome
import com.nuvio.tv.ui.screens.player.PlayerControlDeck
import com.nuvio.tv.ui.screens.player.playerControlDeckAvailable
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import kotlinx.coroutines.delay

internal fun liveControlActions(state: IptvLiveState, canStartOver: Boolean): Set<PlayerControlAction> = buildSet {
    add(PlayerControlAction.PLAY_PAUSE)
    if (canStartOver || state.catchup != null) add(PlayerControlAction.RESTART)
    add(PlayerControlAction.STATS)
    val groups = state.player?.currentTracks?.groups.orEmpty()
    if (groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }) add(PlayerControlAction.AUDIO)
    add(PlayerControlAction.SUBTITLES)
    add(PlayerControlAction.ASPECT)
    add(PlayerControlAction.INFO)
    add(PlayerControlAction.MORE)
}

@Composable
internal fun LiveControls(state: IptvLiveState, now: Long, layout: PlayerControlLayout, available: Set<PlayerControlAction>,
    onAction: (PlayerControlAction) -> Unit, onHide: () -> Unit, onScrub: (Long) -> Unit = {}) {
    val v2 = LocalV2Appearance.current != null
    val targets = remember { PlayerControlAction.entries.associateWith { FocusRequester() } }
    var moreExpanded by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var playing by remember(state.player) { mutableStateOf(state.player?.playWhenReady == true) }
    val deckAvailable = playerControlDeckAvailable(layout, available, moreExpanded)
    BackHandler { if (moreExpanded) moreExpanded = false else onHide() }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        layout.focusFallback(null, deckAvailable)?.let { runCatching { targets.getValue(it).requestFocus() } }
    }
    LaunchedEffect(interaction, moreExpanded) { if (!moreExpanded) { delay(8_000); onHide() } }
    val row = (state.channels.firstOrNull { it.item.channel.id == state.playingId } ?: state.playingRow?.takeIf { it.item.channel.id == state.playingId })
    val position by rememberCatchupPosition(state)
    val programme = if (state.catchup != null) position?.let { catchupShown(state, it) } ?: state.catchup else row?.let { liveProgramme(state, it.item.channel.id, now) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .6f)))))
        PlayerControlChrome(v2, Modifier.align(Alignment.BottomCenter), title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ChannelLogo(row?.let(::logoUrl), row?.let(::channelName) ?: state.playingTitle.orEmpty(), Modifier.size(80.dp, 46.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(row?.let(::channelName) ?: state.playingTitle.orEmpty(), style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Tag(stringResource(playbackTag(state, programme, now)), live = state.catchup == null)
                        qualityBadges(state.player).forEach { Tag(it) }
                    }
                    Text(programme?.let(::title) ?: stringResource(R.string.iptv_live_no_programme), style = MaterialTheme.typography.titleLarge,
                        color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }, timeline = {
            if (state.catchup != null) ScrubTimeline(programme, position, now, state.scrubTarget != null, Modifier.fillMaxWidth(),
                onScrub = { interaction++; onScrub(it) }, onFocused = { interaction++ })
            else ScrubTimeline(programme, null, now, false, Modifier.fillMaxWidth())
        }, time = {
            if (state.catchup != null) Text(stringResource(R.string.iptv_scrub_deck_hint), style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextTertiary, maxLines = 1)
        }, deck = {
            Row(Modifier.fillMaxWidth().focusGroup()) {
                PlayerControlDeck(layout, available, targets::getValue, playing, moreExpanded = moreExpanded,
                    onMoreDismiss = { moreExpanded = false; interaction++ },
                    onBottom = onHide,
                    onClick = { action ->
                        interaction++
                        when (action) {
                            PlayerControlAction.MORE -> moreExpanded = !moreExpanded
                            PlayerControlAction.PLAY_PAUSE -> { onAction(action); playing = !playing }
                            else -> { moreExpanded = false; onAction(action) }
                        }
                    },
                    onFocused = { interaction++ }, modifier = Modifier.weight(1f))
            }
        })
    }
}
