@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureSections
import com.nuvio.tv.core.iptv.SportsFixtureText
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker

private const val FILTER_ALL = "all"
private const val FILTER_LIVE = "live"
private const val FILTER_MINE = "mine"
private const val FILTER_SPORT = "sport:"
private val LineShape = RoundedCornerShape(10.dp)
private val TEAM_LINE = 66.dp
private val PLAIN_LINE = 40.dp

private sealed class ScoreEntry {
    data class Header(val league: String, val live: Int) : ScoreEntry()
    data class Line(val item: IptvFixtureItem) : ScoreEntry()
}

@Composable
internal fun IptvAllScores(state: IptvFixturesState, playingId: String?, onWatch: (IptvListedChannel) -> Unit, onReveal: (String) -> Unit,
    onReminder: (SportsFixture) -> Unit, onDismiss: () -> Unit) {
    val all = state.items
    val leagueOrder = remember { SportsLeagues.ALL.withIndex().associate { it.value.id to it.index } }
    val sportOrder = remember { SportsLeagues.ALL.map { it.sport }.distinct().withIndex().associate { it.value to it.index } }
    val sports = remember(all) { all.map { it.fixture.sport }.distinct().sortedBy { sportOrder[it] ?: Int.MAX_VALUE } }
    var filter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var choosing by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    fun wanted(item: IptvFixtureItem, key: String): Boolean = when {
        key == FILTER_LIVE -> item.fixture.status == FixtureStatus.LIVE
        key == FILTER_MINE -> item.favourite
        key.startsWith(FILTER_SPORT) -> item.fixture.sport == key.removePrefix(FILTER_SPORT)
        else -> true
    }
    val entries = remember(all, filter) {
        all.filter { wanted(it, filter) }.groupBy { it.fixture.league }.entries
            .sortedWith(compareBy({ entry -> if (entry.value.any { it.fixture.status == FixtureStatus.LIVE }) 0 else 1 }, { leagueOrder[it.key] ?: Int.MAX_VALUE }))
            .flatMap { (league, list) ->
                listOf<ScoreEntry>(ScoreEntry.Header(league, list.count { it.fixture.status == FixtureStatus.LIVE })) + list.sortedWith(compareBy(
                    { when (it.fixture.status) { FixtureStatus.LIVE -> 0; FixtureStatus.SCHEDULED -> 1; FixtureStatus.FINAL -> 2 } },
                    { if (it.fixture.status == FixtureStatus.FINAL) -it.fixture.startMillis else it.fixture.startMillis })).map { ScoreEntry.Line(it) }
            }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
        Row(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(horizontal = 36.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.width(240.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.iptv_sport5_section_all_scores), style = iptvTitleStyle(), color = NuvioTheme.colors.TextPrimary, maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val tabs = listOf(FILTER_ALL to stringResource(R.string.iptv_sport4_all), FILTER_LIVE to stringResource(R.string.iptv_sport5_section_live),
                        FILTER_MINE to stringResource(R.string.iptv_sport5_section_my_teams)) + sports.map { FILTER_SPORT + it to sportName(it) }
                    tabs.forEachIndexed { index, (key, label) ->
                        val count = all.count { wanted(it, key) }
                        if (count > 0 || key == FILTER_ALL || key == filter) IptvRailItem(label, count, filter == key,
                            (if (index == 0) Modifier.focusRequester(first) else Modifier).onFocusChanged { if (it.isFocused) filter = key }, onClick = { filter = key })
                    }
                }
                Text(stringResource(R.string.iptv_sport5_section_scores_hint), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(start = 8.dp))
            }
            if (entries.isEmpty()) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.iptv_sport5_section_nothing), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
            } else LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(entries, key = { entry -> when (entry) { is ScoreEntry.Header -> "h:${entry.league}"; is ScoreEntry.Line -> entry.item.fixture.key } }) { entry ->
                    when (entry) {
                        is ScoreEntry.Header -> ScoreHeader(entry)
                        is ScoreEntry.Line -> {
                            val item = entry.item
                            val fixture = item.fixture
                            ScoreLine(item, state.hidden(fixture), state.spoiler(fixture), fixture.key in state.reminders,
                                item.links.any { it.row.item.channel.id == playingId },
                                onClick = {
                                    when {
                                        item.links.size == 1 -> onWatch(item.links.first().row)
                                        item.links.isNotEmpty() || fixture.status != FixtureStatus.FINAL -> choosing = fixture.key
                                    }
                                },
                                onHold = {
                                    when {
                                        state.spoiler(fixture) -> onReveal(fixture.key)
                                        sportCanRemind(fixture) -> onReminder(fixture)
                                    }
                                })
                        }
                    }
                }
            }
        }
        choosing?.let { key ->
            state.item(key)?.let { item -> FixtureChannelsDialog(item, onWatch = { choosing = null; onWatch(it) }, onDismiss = { choosing = null }) }
        }
    }
}

@Composable
private fun ScoreHeader(entry: ScoreEntry.Header) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text((SportsLeagues.byId(entry.league)?.name ?: entry.league).uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = NuvioTheme.colors.TextSecondary, maxLines = 1)
        if (entry.live > 0) Text("${entry.live} ${stringResource(R.string.iptv_sport_live)}", style = SportCaps.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Bold, color = NuvioTheme.colors.Error, maxLines = 1)
    }
}

@Composable
private fun ScoreLine(item: IptvFixtureItem, hidden: Boolean, spoiler: Boolean, reminded: Boolean, playing: Boolean, onClick: () -> Unit, onHold: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val longPress = rememberLongPressKeyTracker()
    val fixture = item.fixture
    val live = fixture.status == FixtureStatus.LIVE
    val close = live && !hidden && !item.scheduleOnly && SportsFixtureSections.close(fixture)
    val home = fixture.home
    val away = fixture.away
    val teams = home != null && away != null
    Row(Modifier.fillMaxWidth().height(if (teams) TEAM_LINE else PLAIN_LINE)
        .onFocusChanged { focused = it.isFocused }
        .iptvItem(focused, playing, LineShape)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (longPress.handle(native, ::isSelect) { held = true; onHold() }) {
                if (native.action == AndroidKeyEvent.ACTION_UP) held = false
                return@onPreviewKeyEvent true
            }
            if (isSelect(native.keyCode)) { if (native.action == AndroidKeyEvent.ACTION_UP) { if (!held) onClick(); held = false }; true } else false
        }
        .focusable().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(3.dp).height(if (teams) 44.dp else 22.dp).clip(RoundedCornerShape(2.dp)).background(if (close) NuvioTheme.colors.Warning else Color.Transparent))
        Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterStart) { CardState(fixture, fixture.sportDetail.takeIf { !item.scheduleOnly }, hidden || item.scheduleOnly) }
        val content = itemContent(focused)
        if (home != null && away != null) {
            CardBands(fixture, home, away, focused, Modifier.weight(1f).fillMaxHeight().padding(vertical = 3.dp), 22.dp, extra = null) { team, side ->
                BandScore(fixture, team, side, hidden, focused, item.scheduleOnly)
            }
        } else {
            Text(fixture.title, style = MaterialTheme.typography.bodyMedium, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            val bug = SportsFixtureText.bug(fixture).takeIf { !hidden && fixture.status != FixtureStatus.SCHEDULED && !item.scheduleOnly }
            if (bug != null && bug.primary != fixture.title) Text(bug.primary, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            else if (hidden && fixture.status != FixtureStatus.SCHEDULED) MaskBar(46.dp)
            Spacer(Modifier.width(4.dp))
        }
        if (item.favourite) Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = NuvioTheme.colors.Secondary)
        if (reminded) Icon(Icons.Filled.NotificationsActive, null, Modifier.size(14.dp), tint = NuvioTheme.colors.Secondary)
        val channel = item.links.firstOrNull()?.let { channelName(it.row) + if (item.links.size > 1) " +${item.links.size - 1}" else "" }
        Text(when {
            spoiler && hidden -> stringResource(R.string.iptv_sport5_section_hold_to_show)
            channel != null -> channel
            fixture.status == FixtureStatus.FINAL -> ""
            item.linking -> stringResource(R.string.iptv_sport3_finding_channels)
            else -> stringResource(R.string.iptv_sport_no_channel)
        }, style = MaterialTheme.typography.labelMedium, color = if (channel != null && !(spoiler && hidden)) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextTertiary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.width(160.dp))
    }
}
