@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.CatchupScrub
import com.nuvio.tv.core.iptv.GoLiveRoute
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.LiveGoLive
import com.nuvio.tv.core.iptv.MomentKind
import com.nuvio.tv.core.iptv.SportsMarker
import com.nuvio.tv.core.iptv.SummaryMoment
import com.nuvio.tv.data.local.PlayerControlAction
import com.nuvio.tv.data.local.PlayerControlLayout
import com.nuvio.tv.ui.screens.player.PlayerControlChrome
import com.nuvio.tv.ui.screens.player.PlayerControlDeck
import com.nuvio.tv.ui.screens.player.PillControlButton
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

internal fun goLiveRoute(state: IptvLiveState, now: Long): GoLiveRoute {
    if (state.player == null || state.playingRow == null) return GoLiveRoute.NONE
    val playback = state.playback
    return LiveGoLive.route(state.catchup != null, state.catchupFrom != null, state.catchup?.stop?.epochMillis, now,
        state.localTimeshift, state.localBehind, state.paused, playback?.behindLiveMs(), playback?.bufferTargetMs ?: 0L)
}

@Composable
internal fun LiveControls(state: IptvLiveState, now: Long, layout: PlayerControlLayout, available: Set<PlayerControlAction>,
    onAction: (PlayerControlAction) -> Unit, onHide: () -> Unit, onScrub: (Long) -> Unit = {}, onGoLive: () -> Boolean = { false },
    focusGoLive: Boolean = false, markers: List<Pair<SummaryMoment, SportsMarker>> = emptyList(), feeds: Int = 0,
    onCentre: (() -> Unit)? = null, onFeeds: (() -> Unit)? = null) {
    val v2 = LocalV2Appearance.current != null
    val targets = remember { PlayerControlAction.entries.associateWith { FocusRequester() } }
    val goLiveFocus = remember { FocusRequester() }
    var behind by remember { mutableStateOf(goLiveRoute(state, System.currentTimeMillis()) != GoLiveRoute.NONE) }
    var goLiveFocused by remember { mutableStateOf(false) }
    LaunchedEffect(state) { while (true) { behind = goLiveRoute(state, System.currentTimeMillis()) != GoLiveRoute.NONE; delay(1_000) } }
    var moreExpanded by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var playing by remember(state.player) { mutableStateOf(state.player?.playWhenReady == true) }
    val deckAvailable = playerControlDeckAvailable(layout, available, moreExpanded)
    BackHandler { if (moreExpanded) moreExpanded = false else onHide() }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        if (focusGoLive && behind && runCatching { goLiveFocus.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return@LaunchedEffect
        layout.focusFallback(null, deckAvailable)?.let { runCatching { targets.getValue(it).requestFocus() } }
    }
    LaunchedEffect(interaction, moreExpanded) { if (!moreExpanded) { delay(8_000); onHide() } }
    fun focusDeck() { layout.focusFallback(null, deckAvailable)?.let { runCatching { targets.getValue(it).requestFocus() } } }
    LaunchedEffect(behind) { if (!behind && goLiveFocused) { goLiveFocused = false; focusDeck() } }
    val row = (state.channels.firstOrNull { it.item.channel.id == state.playingId } ?: state.playingRow?.takeIf { it.item.channel.id == state.playingId })
    val position by rememberCatchupPosition(state)
    val programme = if (state.catchup != null) position?.let { catchupShown(state, it) } ?: state.catchup else row?.let { liveProgramme(state, it.item.channel.id, now) }
    val seekable = state.catchup != null || state.localTimeshift
    val at = position ?: now
    val scores = if (seekable) markers.filter { scoringMoment(it.first.kind) }.map { it.second.millis - SCORE_LEAD } else emptyList()
    val previousScore = scores.lastOrNull { it < at - 10_000 }
    val nextScore = scores.firstOrNull { it > at + 5_000 && it + SCORE_LEAD < now }
    val sportFocus = remember { FocusRequester() }
    val sportActions = listOfNotNull(
        previousScore?.let { target -> SportAction(Icons.Filled.SkipPrevious, R.string.iptv_sport5p_previous_score) { onScrub(target - at) } },
        nextScore?.let { target -> SportAction(Icons.Filled.SkipNext, R.string.iptv_sport5p_next_score) { onScrub(target - at) } },
        onCentre?.let { SportAction(Icons.Filled.SportsSoccer, R.string.iptv_sport5p_game_centre, it) },
        onFeeds?.let { SportAction(Icons.Filled.SwapHoriz, R.string.iptv_sport5p_feeds, it) })
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
            Box(Modifier.fillMaxWidth()) {
                if (state.catchup != null || state.localTimeshift) ScrubTimeline(programme, position, now, state.scrubTarget != null, Modifier.fillMaxWidth(),
                    onScrub = { interaction++; onScrub(it) }, onFocused = { interaction++; goLiveFocused = false },
                    buffered = state.playback?.takeIf { state.localTimeshift }?.let { playback -> playback::localOldest })
                else ScrubTimeline(programme, null, now, false, Modifier.fillMaxWidth())
                if (seekable && markers.isNotEmpty()) ScoreMarks(markers, programme, at, now, Modifier.fillMaxWidth().height(16.dp))
            }
        }, time = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    if (state.catchup != null || state.localTimeshift) Text(stringResource(R.string.iptv_scrub_deck_hint), style = MaterialTheme.typography.labelSmall,
                        color = NuvioTheme.colors.TextTertiary, maxLines = 1)
                }
                sportActions.forEachIndexed { index, action ->
                    PillControlButton(action.icon, label = if (action.label == R.string.iptv_sport5p_feeds) stringResource(action.label, feeds) else stringResource(action.label),
                        onClick = { interaction++; action.onClick() }, focusRequester = sportFocus.takeIf { index == 0 },
                        onFocused = { interaction++; goLiveFocused = false }, onDownKey = { focusDeck() },
                        modifier = Modifier.focusProperties {
                            if (index == 0) left = FocusRequester.Cancel
                            if (index == sportActions.lastIndex && !behind) right = FocusRequester.Cancel
                        }, labelMaxLines = 1)
                }
                if (behind) PillControlButton(Icons.Filled.LiveTv, label = stringResource(R.string.iptv_live_return),
                    onClick = { interaction++; if (onGoLive()) { focusDeck(); behind = false } },
                    focusRequester = goLiveFocus, onFocused = { interaction++; goLiveFocused = true }, onDownKey = { focusDeck() },
                    modifier = Modifier.focusProperties { if (sportActions.isEmpty()) left = FocusRequester.Cancel; right = FocusRequester.Cancel }, labelMaxLines = 1)
            }
        }, deck = {
            Row(Modifier.fillMaxWidth().focusGroup()) {
                PlayerControlDeck(layout, available, targets::getValue, playing, moreExpanded = moreExpanded,
                    onMoreDismiss = { moreExpanded = false; interaction++ },
                    upFocus = goLiveFocus.takeIf { behind } ?: sportFocus.takeIf { sportActions.isNotEmpty() },
                    onBottom = onHide,
                    onClick = { action ->
                        interaction++
                        when (action) {
                            PlayerControlAction.MORE -> moreExpanded = !moreExpanded
                            PlayerControlAction.PLAY_PAUSE -> { onAction(action); playing = !playing }
                            else -> { moreExpanded = false; onAction(action) }
                        }
                    },
                    onFocused = { interaction++; goLiveFocused = false }, modifier = Modifier.weight(1f))
            }
        })
    }
}

private class SportAction(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: Int, val onClick: () -> Unit)

private const val SCORE_LEAD = 30_000L

@Composable
private fun ScoreMarks(markers: List<Pair<SummaryMoment, SportsMarker>>, programme: GuideProgramme?, at: Long, now: Long, modifier: Modifier) {
    val bar = CatchupScrub.bar(programme, at, now)
    val span = (bar.endMillis - bar.startMillis).coerceAtLeast(1L)
    BoxWithConstraints(modifier) {
        val width = maxWidth
        markers.forEach { (moment, marker) ->
            if (marker.millis < bar.startMillis || marker.millis > bar.endMillis) return@forEach
            val fraction = ((marker.millis - bar.startMillis).toDouble() / span).toFloat()
            val card = moment.kind == MomentKind.CARD_YELLOW || moment.kind == MomentKind.CARD_RED
            if (!card && !scoringMoment(moment.kind)) return@forEach
            val size = if (card) 6.dp else 10.dp
            val shape = if (card) RoundedCornerShape(1.dp) else CircleShape
            val colour = when (moment.kind) {
                MomentKind.CARD_YELLOW -> Color(0xFFFFD54F)
                MomentKind.CARD_RED -> Color(0xFFE53935)
                else -> NuvioTheme.colors.Secondary
            }
            Box(Modifier.align(Alignment.CenterStart).padding(start = (width * fraction - size / 2).coerceIn(0.dp, (width - size).coerceAtLeast(0.dp)))
                .size(size, if (card) 10.dp else size).clip(shape).background(if (marker.exact || card) colour else colour.copy(alpha = .35f))
                .border(1.5.dp, if (marker.exact) Color.White else colour, shape))
        }
    }
}
