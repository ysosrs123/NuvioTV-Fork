@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureSide
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.SportsDetail
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureText
import com.nuvio.tv.core.iptv.SportsGuideCells
import com.nuvio.tv.core.iptv.SportsGuideChip
import com.nuvio.tv.core.iptv.SportsGuideIndex
import com.nuvio.tv.core.iptv.SportsGuideLink
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.ui.theme.NuvioTheme

internal class IptvSportsGuide(val active: Boolean, val index: SportsGuideIndex, val showScores: Boolean, val spoilerKeys: Set<String>,
    val reminders: Set<String>, val items: List<IptvFixtureItem>) {
    fun hidden(fixture: SportsFixture): Boolean = SportsGuideCells.hidden(fixture, showScores, spoilerKeys)
    fun reminded(fixture: SportsFixture): Boolean = fixture.status == FixtureStatus.SCHEDULED && fixture.key in reminders
    fun games(now: Long): List<IptvFixtureItem> = if (!active) emptyList() else SportsGuideCells.lane(items, { it.fixture }, now)
}

internal val NoSportsGuide = IptvSportsGuide(false, SportsGuideIndex.EMPTY, true, emptySet(), emptySet(), emptyList())

internal object IptvSportOnly { var on = false }

@Composable
internal fun rememberIptvSportsGuide(enabled: Boolean, viewModel: IptvSportsFixturesViewModel = hiltViewModel()): IptvSportsGuide {
    val state by viewModel.state.collectAsStateWithLifecycle()
    return remember(state, enabled) {
        if (!enabled || !state.enabled) NoSportsGuide else {
            val items = state.rows.flatMap { it.items }.filter { it.links.isNotEmpty() }.distinctBy { it.fixture.key }
            val links = items.flatMap { item ->
                item.links.mapNotNull { link -> link.programme?.let { SportsGuideLink(item.fixture, link.row.item.channel.id, it, link.reason) } }
            }
            IptvSportsGuide(true, SportsGuideCells.index(links), state.showScores, state.spoilerKeys, state.reminders, items)
        }
    }
}

internal fun sportOnlyChannels(channels: List<IptvListedChannel>, sport: IptvSportsGuide, from: Long, until: Long, keep: String?): List<IptvListedChannel> =
    channels.filter { it.item.channel.id == keep || sport.index.within(it.item.channel.id, from, until) }

@Composable
internal fun IptvGamesNowLane(games: List<IptvFixtureItem>, sport: IptvSportsGuide, playingId: String?, onWatch: (IptvListedChannel) -> Unit,
    onRail: () -> Unit, onDown: () -> Unit, modifier: Modifier = Modifier) {
    var choosing by remember { mutableStateOf<IptvFixtureItem?>(null) }
    val listState = rememberLazyListState()
    var lastFocused by remember { mutableIntStateOf(0) }
    val requesters = remember { mutableMapOf<Int, FocusRequester>() }
    fun requester(index: Int) = requesters.getOrPut(index) { FocusRequester() }
    Row(modifier.fillMaxWidth().height(LANE_HEIGHT), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.iptv_sport5g_games_now).uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold,
            color = NuvioTheme.colors.Error, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
        LazyRow(state = listState, modifier = Modifier.weight(1f)
            .focusRestorer {
                val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
                val index = lastFocused.takeIf { it in visible } ?: visible.firstOrNull()
                index?.let { requesters[it] } ?: FocusRequester.Default
            }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        val left = lastFocused == 0
                        if (left && native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) onRail()
                        left
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { if (native.action == AndroidKeyEvent.ACTION_DOWN) onDown(); true }
                    else -> false
                }
            }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(games, key = { _, item -> item.fixture.key }) { index, item ->
                GameChip(item, sport, playing = item.links.any { it.row.item.channel.id == playingId }, modifier = Modifier.focusRequester(requester(index)),
                    onFocused = { lastFocused = index },
                    onClick = { if (item.links.size == 1) onWatch(item.links.first().row) else choosing = item })
            }
        }
    }
    choosing?.let { chosen ->
        val item = games.firstOrNull { it.fixture.key == chosen.fixture.key } ?: chosen
        FixtureChannelsDialog(item, onWatch = { choosing = null; onWatch(it) }, onDismiss = { choosing = null })
    }
}

@Composable
private fun GameChip(item: IptvFixtureItem, sport: IptvSportsGuide, playing: Boolean, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fixture = item.fixture
    val hidden = sport.hidden(fixture)
    val live = fixture.status == FixtureStatus.LIVE
    val detail = fixture.sportDetail
    val home = fixture.home
    val away = fixture.away
    val banded = detail == null || detail is SportsDetail.Cricket || detail is SportsDetail.Baseball
    val channel = item.links.first().row.let(::channelName).let { name ->
        if (item.links.size > 1) stringResource(R.string.iptv_sport5g_channels, name, item.links.size - 1) else name
    }
    Row(modifier.height(CHIP_HEIGHT)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .iptvItem(focused, playing, ChipShape)
        .then(if (focused) Modifier else Modifier.background(NuvioTheme.colors.TextPrimary.copy(alpha = .06f), ChipShape))
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (isSelect(native.keyCode)) { if (native.action == AndroidKeyEvent.ACTION_UP) onClick(); true } else false
        }
        .focusable()
        .padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (banded && home != null && away != null) {
            val sides = if (SportsFixtureText.awayFirst(fixture)) listOf(FixtureSide.AWAY, FixtureSide.HOME) else listOf(FixtureSide.HOME, FixtureSide.AWAY)
            Row(Modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                sides.forEach { side ->
                    val team = if (side == FixtureSide.HOME) home else away
                    TeamBand(team, LANE_LOGO, false, focused, Modifier.width(LANE_BAND_WIDTH).fillMaxHeight()) {
                        BandScore(fixture, team, side, hidden, focused, record = false)
                    }
                }
            }
        } else Text(SportsGuideCells.label(fixture, hidden), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = itemContent(focused),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).widthIn(max = 240.dp))
        CardState(fixture, detail, hidden)
        Text(channel, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (focused) itemContent(true).copy(alpha = .8f) else NuvioTheme.colors.TextSecondary, modifier = Modifier.widthIn(max = 180.dp))
        if (live && SportsGuideCells.close(fixture, hidden)) Text(stringResource(R.string.iptv_sport5g_close).uppercase(), style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold, color = NuvioTheme.colors.Warning, maxLines = 1)
    }
}

@Composable
internal fun SportOnlyToggle(on: Boolean, onToggle: () -> Unit, onRail: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Row(modifier.height(26.dp)
        .onFocusChanged { focused = it.isFocused }
        .iptvItem(focused, false, ToggleShape)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            when {
                isSelect(native.keyCode) -> { if (native.action == AndroidKeyEvent.ACTION_UP) onToggle(); true }
                native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) onRail(); true }
                native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
        }
        .focusable()
        .padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ToggleSegment(stringResource(R.string.iptv_sport5g_sport_only), on, focused)
        ToggleSegment(stringResource(R.string.iptv_sport5g_all_channels), !on, focused)
    }
}

@Composable
private fun ToggleSegment(text: String, selected: Boolean, focused: Boolean) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
        color = when {
            selected -> itemContent(focused)
            focused -> itemContent(true).copy(alpha = .7f)
            else -> NuvioTheme.colors.TextTertiary
        },
        modifier = Modifier.clip(ToggleShape).then(if (selected) Modifier.background(NuvioTheme.colors.TextPrimary.copy(alpha = .12f), ToggleShape) else Modifier)
            .padding(horizontal = 9.dp, vertical = 2.dp))
}

@Composable
internal fun SportGuideBadge(text: String, sport: String?, modifier: Modifier = Modifier) {
    val colour = sport?.let(::sportColour)
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false,
        color = if (colour != null) Color(0xFF0D0D0D) else NuvioTheme.colors.TextSecondary,
        modifier = modifier.clip(BadgeShape).background(colour ?: NuvioTheme.colors.TextPrimary.copy(alpha = .14f), BadgeShape).padding(horizontal = 4.dp))
}

@Composable
internal fun SportGuideScore(chip: SportsGuideChip, modifier: Modifier = Modifier) {
    Row(modifier.clip(ScoreShape).background(Color.Black.copy(alpha = .55f), ScoreShape).padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        chip.score?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false) }
        chip.clock?.let { Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.Error, maxLines = 1, softWrap = false) }
    }
}

private fun sportColour(sport: String): Color = Color(when (sport) {
    "soccer" -> 0xFFFF8A80
    "motorsport" -> 0xFF90CAF9
    "tennis" -> 0xFFC5E1A5
    "baseball" -> 0xFFFFCC80
    "basketball" -> 0xFFFFAB91
    "american-football" -> 0xFFBCAAA4
    "ice-hockey" -> 0xFF80DEEA
    "australian-football" -> 0xFFCE93D8
    "rugby", "rugby-league" -> 0xFFA5D6A7
    "cricket" -> 0xFFE6EE9C
    "golf" -> 0xFFAED581
    "mma" -> 0xFFEF9A9A
    else -> 0xFFBDBDBD
})

private val LANE_HEIGHT = 40.dp
private val CHIP_HEIGHT = 34.dp
private val LANE_LOGO = 22.dp
private val LANE_BAND_WIDTH = 148.dp
private val ChipShape = RoundedCornerShape(10.dp)
private val ToggleShape = RoundedCornerShape(999.dp)
private val BadgeShape = RoundedCornerShape(3.dp)
private val ScoreShape = RoundedCornerShape(6.dp)
