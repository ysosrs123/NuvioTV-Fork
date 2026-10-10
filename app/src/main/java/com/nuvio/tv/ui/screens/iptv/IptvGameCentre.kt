@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureSide
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.FixtureTeam
import com.nuvio.tv.core.iptv.MomentKind
import com.nuvio.tv.core.iptv.SportsEvents
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureText
import com.nuvio.tv.core.iptv.SportsMarker
import com.nuvio.tv.core.iptv.SportsMarkers
import com.nuvio.tv.core.iptv.SportsRefresh
import com.nuvio.tv.core.iptv.SportsSpoilers
import com.nuvio.tv.core.iptv.SportsSummary
import com.nuvio.tv.core.iptv.SummaryCount
import com.nuvio.tv.core.iptv.SummaryMoment
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.data.iptv.IptvSportsSummaryClient
import com.nuvio.tv.data.iptv.IptvSportsSummaryWatch
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class IptvSportsSummaryViewModel @Inject constructor(private val client: IptvSportsSummaryClient) : ViewModel() {
    private class Entry(val watch: IptvSportsSummaryWatch, var fixture: SportsFixture, var users: Int = 0, var closing: Job? = null)
    private val entries = mutableMapOf<String, Entry>()

    fun acquire(fixture: SportsFixture): StateFlow<SportsSummary?> {
        val entry = entries.getOrPut(fixture.key) { Entry(IptvSportsSummaryWatch(client, viewModelScope), fixture) }
        entry.fixture = fixture
        entry.closing?.cancel(); entry.closing = null
        entry.users++
        entry.watch.start(fixture)
        return entry.watch.summary
    }

    fun release(fixture: SportsFixture) {
        val entry = entries[fixture.key] ?: return
        if (--entry.users > 0) return
        entry.closing = viewModelScope.launch {
            delay(LINGER_MILLIS)
            if (entry.users <= 0 && entries[fixture.key] === entry) { entry.watch.stop(); entries.remove(fixture.key) }
        }
    }

    fun pause() { entries.values.forEach { it.watch.stop() } }

    fun resume() { entries.values.filter { it.users > 0 }.forEach { it.watch.start(it.fixture) } }

    override fun onCleared() { entries.values.forEach { it.watch.stop() }; entries.clear() }

    private companion object { const val LINGER_MILLIS = 60_000L }
}

private val NO_SUMMARY = MutableStateFlow<SportsSummary?>(null)

@Composable
internal fun rememberSportsSummary(fixture: SportsFixture?, viewModel: IptvSportsSummaryViewModel = hiltViewModel()): SportsSummary? {
    val key = fixture?.key
    var flow by remember(key) { mutableStateOf<StateFlow<SportsSummary?>>(NO_SUMMARY) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.pause()
            if (event == Lifecycle.Event.ON_START) viewModel.resume()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(key) {
        if (fixture == null) return@DisposableEffect onDispose { }
        flow = viewModel.acquire(fixture)
        onDispose { viewModel.release(fixture) }
    }
    return flow.collectAsStateWithLifecycle().value
}

internal fun playingFixture(rows: List<IptvFixtureRow>, playingId: String?, at: Long, recent: List<IptvFixtureItem> = emptyList()): IptvFixtureItem? {
    playingId ?: return null
    val found = (rows.asSequence().flatMap { it.items.asSequence() } + recent.asSequence()).filter { item -> item.links.any { it.row.item.channel.id == playingId } }
        .distinctBy { it.fixture.key }.filter { item ->
            val duration = SportsRefresh.durationMillis(item.fixture)
            val programme = item.links.first { it.row.item.channel.id == playingId }.programme
            if (programme != null) {
                val start = programme.start.epochMillis
                at >= start - FIXTURE_SLACK && at < (programme.stop?.epochMillis ?: (start + duration)) + FIXTURE_SLACK
            } else item.fixture.status == FixtureStatus.LIVE || at in item.fixture.startMillis - FIXTURE_SLACK until item.fixture.startMillis + duration
        }.toList()
    return found.minByOrNull { if (it.fixture.status == FixtureStatus.LIVE) 0 else 1 }
}

internal fun scoreHidden(state: IptvFixturesState, fixture: SportsFixture): Boolean = !state.showScores || SportsSpoilers.hidden(fixture, state.spoilerKeys)

internal fun scoringMoment(kind: MomentKind): Boolean = kind == MomentKind.GOAL || kind == MomentKind.POINTS || kind == MomentKind.TRY ||
    kind == MomentKind.TOUCHDOWN || kind == MomentKind.RUN

internal fun sportsMarkers(summary: SportsSummary?, fixture: SportsFixture?): List<Pair<SummaryMoment, SportsMarker>> = when {
    fixture == null -> emptyList()
    summary == null -> SportsEvents.moments(fixture).mapNotNull { moment -> SportsMarkers.estimate(moment, fixture.sport, fixture.startMillis, league = fixture.league)?.let { moment to it } }
        .sortedBy { it.second.millis }
    else -> SportsMarkers.all(summary, fixture.startMillis, league = fixture.league).sortedBy { it.second.millis }
}

internal fun endsNow(summary: SportsSummary, sport: String): Pair<Int, Int>? {
    val (win, draw) = when (sport) {
        "soccer" -> 3 to 1
        "rugby-league", "rugby" -> 2 to 1
        "australian-football" -> 4 to 2
        else -> return null
    }
    if (summary.status != FixtureStatus.LIVE) return null
    val table = summary.tables.firstOrNull { t -> t.rows.any { it.side == FixtureSide.HOME } && t.rows.any { it.side == FixtureSide.AWAY } } ?: return null
    fun points(side: FixtureSide) = table.rows.first { it.side == side }.stats.firstOrNull { it.first.equals("PTS", ignoreCase = true) }?.second?.toIntOrNull()
    val home = points(FixtureSide.HOME) ?: return null
    val away = points(FixtureSide.AWAY) ?: return null
    val h = summary.homeLine?.score?.toIntOrNull() ?: return null
    val a = summary.awayLine?.score?.toIntOrNull() ?: return null
    return (home + when { h > a -> win; h == a -> draw; else -> 0 }) to (away + when { a > h -> win; h == a -> draw; else -> 0 })
}

private enum class CentreTab(val label: Int) {
    SUMMARY(R.string.iptv_sport5p_summary), STATS(R.string.iptv_sport5p_stats), LINEUPS(R.string.iptv_sport5p_lineups),
    TABLE(R.string.iptv_sport5p_table), OTHER(R.string.iptv_sport5p_other_games)
}

@Composable
internal fun IptvGameCentrePanel(fixture: SportsFixture, onWatchMoment: (Long) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
    onWatch: (IptvListedChannel) -> Unit = {}) {
    val sports: IptvSportsFixturesViewModel = hiltViewModel()
    val state by sports.state.collectAsStateWithLifecycle()
    val current = state.rows.firstNotNullOfOrNull { row -> row.items.firstOrNull { it.fixture.key == fixture.key } }?.fixture ?: fixture
    val summary = rememberSportsSummary(current)
    var revealed by remember(fixture.key) { mutableStateOf(false) }
    val hidden = !revealed && scoreHidden(state, current)
    var tab by remember { mutableStateOf(CentreTab.SUMMARY) }
    val tabFocus = remember { CentreTab.entries.associateWith { FocusRequester() } }
    val longPress = rememberLongPressKeyTracker()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(10_000); value = System.currentTimeMillis() } }
    BackHandler { onClose() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { tabFocus.getValue(tab).requestFocus() } }
    Column(modifier.fillMaxHeight().iptvPanel().onPreviewKeyEvent { event ->
        hidden && longPress.handle(event.nativeKeyEvent, ::isSelect) { revealed = true }
    }.padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CentreHeader(current, summary, hidden)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CentreTab.entries.forEachIndexed { index, entry ->
                var focused by remember { mutableStateOf(false) }
                Text(stringResource(entry.label), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    fontWeight = if (entry == tab) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (focused || entry == tab) itemContent(focused) else NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.focusRequester(tabFocus.getValue(entry))
                        .focusProperties {
                            if (index == 0) left = FocusRequester.Cancel
                            if (index == CentreTab.entries.lastIndex) right = FocusRequester.Cancel
                            up = FocusRequester.Cancel
                        }
                        .onFocusChanged { focused = it.isFocused; if (it.isFocused) tab = entry }
                        .iptvItem(focused, entry == tab).focusable().padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val list = centreItems(tab, current, summary, hidden, state, now)
            if (list.isEmpty()) Text(stringResource(when {
                hidden && (tab == CentreTab.SUMMARY || tab == CentreTab.STATS) -> R.string.iptv_sport5p_hidden
                summary == null -> R.string.iptv_sport5p_no_summary
                tab == CentreTab.SUMMARY -> R.string.iptv_sport5p_no_moments
                tab == CentreTab.STATS -> R.string.iptv_sport5p_no_stats
                tab == CentreTab.LINEUPS -> R.string.iptv_sport5p_no_lineups
                tab == CentreTab.TABLE -> R.string.iptv_sport5p_no_table
                else -> R.string.iptv_sport5p_no_other
            }), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(12.dp))
            else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(list, key = { it.key }) { item ->
                    CentreRow(item, onClick = when (item) {
                        is CentreItem.Moment -> item.marker?.takeIf { it.millis < now }?.let { marker -> { onWatchMoment(marker.millis) } }
                        is CentreItem.Game -> item.item.links.firstOrNull()?.let { link -> { onWatch(link.row) } }
                        else -> null
                    })
                }
            }
        }
        Text(stringResource(if (current.status == FixtureStatus.LIVE) R.string.iptv_sport5p_updates else R.string.iptv_sport5p_back_hint),
            style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CentreHeader(fixture: SportsFixture, summary: SportsSummary?, hidden: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val round = fixture.round?.let { stringResource(if (fixture.sport == "american-football") R.string.iptv_sport2_week else R.string.iptv_sport2_round, it) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(listOfNotNull(sportLeagueName(fixture), round, fixture.venue).joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (fixture.status == FixtureStatus.LIVE) Tag((if (hidden) null else SportsFixtureText.periodClock(fixture)) ?: stringResource(R.string.iptv_sport_live), live = true)
            else if (fixture.status == FixtureStatus.FINAL) Tag(stringResource(R.string.iptv_sport2_final))
        }
        GameScoreLine(fixture, hidden, 36.dp, MaterialTheme.typography.headlineSmall)
        if (hidden) Text(stringResource(R.string.iptv_sport5p_hidden), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
        else summary?.homeLine?.breakdown?.let { home -> summary?.awayLine?.breakdown?.let { away ->
            Text(if (SportsFixtureText.awayFirst(fixture)) "$away – $home" else "$home – $away", style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary, maxLines = 1)
        } }
        if (summary == null && fixture.status == FixtureStatus.LIVE) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoadingIndicator(Modifier.size(16.dp))
            Text(stringResource(R.string.iptv_sport5p_loading), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
        }
    }
}

@Composable
internal fun GameScoreLine(fixture: SportsFixture, hidden: Boolean, logo: Dp, style: TextStyle, modifier: Modifier = Modifier) {
    val home = fixture.home
    val away = fixture.away
    if (home == null || away == null) {
        Text(fixture.title, style = style, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    val first = SportsFixtureText.awayFirst(fixture)
    val (left, right) = if (first) away to home else home to away
    val scores = SportsFixtureText.scores(fixture)?.takeIf { !hidden && fixture.status != FixtureStatus.SCHEDULED }?.let { if (first) it.second to it.first else it }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TeamLogo(left, logo)
        Text(left.shortName ?: left.name, style = style, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(scores?.let { "${it.first} – ${it.second}" } ?: if (first) "@" else "v", style = style, fontWeight = FontWeight.Bold,
            color = if (scores != null) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary, maxLines = 1)
        Text(right.shortName ?: right.name, style = style, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        TeamLogo(right, logo)
    }
}

private sealed interface CentreItem {
    val key: String
    data class Heading(val text: Int, override val key: String) : CentreItem
    data class Moment(val moment: SummaryMoment, val marker: SportsMarker?, val team: String?, override val key: String) : CentreItem
    data class Stat(val label: String, val home: String, val away: String, val homeColour: Color?, val awayColour: Color?, override val key: String) : CentreItem
    data class Line(val text: String, val trailing: String?, val strong: Boolean, override val key: String) : CentreItem
    data class Game(val item: IptvFixtureItem, val hidden: Boolean, override val key: String) : CentreItem
}

@Composable
private fun centreItems(tab: CentreTab, fixture: SportsFixture, summary: SportsSummary?, hidden: Boolean, state: IptvFixturesState, now: Long): List<CentreItem> {
    val homeName = fixture.home?.let(SportsFixtureText::code) ?: summary?.homeName
    val awayName = fixture.away?.let(SportsFixtureText::code) ?: summary?.awayName
    fun team(side: FixtureSide?) = when (side) { FixtureSide.HOME -> homeName; FixtureSide.AWAY -> awayName; null -> null }
    val stats = summary?.stats.orEmpty().mapIndexed { i, stat ->
        CentreItem.Stat(stat.label, stat.home, stat.away, teamColour(fixture.home), teamColour(fixture.away), "stat:$i")
    }
    val pointsText = summary?.let { endsNow(it, fixture.sport) }?.let { (home, away) ->
        stringResource(R.string.iptv_sport5p_ends_now_points, homeName.orEmpty(), home, awayName.orEmpty(), away)
    }
    return when (tab) {
        CentreTab.SUMMARY -> if (hidden || summary == null) emptyList() else buildList {
            val moments = summary.moments.filter { it.kind != MomentKind.SUBSTITUTION || summary.moments.size <= 20 }
            if (moments.isNotEmpty()) add(CentreItem.Heading(R.string.iptv_sport5p_key_moments, "h:moments"))
            moments.asReversed().forEachIndexed { i, moment ->
                add(CentreItem.Moment(moment, SportsMarkers.estimate(moment, summary.sport, fixture.startMillis, league = fixture.league), team(moment.side), "m:$i"))
            }
            if (stats.isNotEmpty()) { add(CentreItem.Heading(R.string.iptv_sport5p_team_stats, "h:stats")); addAll(stats.take(4)) }
            if (pointsText != null) { add(CentreItem.Heading(R.string.iptv_sport5p_ends_now, "h:ends")); add(CentreItem.Line(pointsText, null, true, "ends")) }
        }
        CentreTab.STATS -> if (hidden) emptyList() else buildList {
            addAll(stats)
            summary?.leaders.orEmpty().forEachIndexed { i, leader ->
                add(CentreItem.Line("${team(leader.side).orEmpty()} · ${leader.category}: ${leader.name}", leader.stat, false, "leader:$i"))
            }
        }
        CentreTab.LINEUPS -> buildList {
            summary?.rosters.orEmpty().forEach { roster ->
                val side = roster.side.name
                add(CentreItem.Line(listOfNotNull(team(roster.side), stringResource(R.string.iptv_sport5p_starting)).joinToString(" · "), null, true, "r:$side"))
                roster.starters.forEachIndexed { i, p -> add(CentreItem.Line(listOfNotNull(p.jersey, p.name).joinToString("  "), p.position, false, "r:$side:s:$i")) }
                if (roster.subs.isNotEmpty()) add(CentreItem.Line(listOfNotNull(team(roster.side), stringResource(R.string.iptv_sport5p_bench)).joinToString(" · "), null, true, "r:$side:b"))
                roster.subs.forEachIndexed { i, p -> add(CentreItem.Line(listOfNotNull(p.jersey, p.name).joinToString("  "), p.position, false, "r:$side:b:$i")) }
            }
        }
        CentreTab.TABLE -> buildList {
            summary?.tables?.firstOrNull()?.let { table ->
                table.rows.forEachIndexed { i, row ->
                    add(CentreItem.Line("${i + 1}  ${row.team}", row.stats.joinToString("  ") { "${it.first} ${it.second}" }, row.side != null, "t:$i"))
                }
            }
            if (pointsText != null) add(CentreItem.Line(pointsText, null, true, "t:ends"))
            val meetings = summary?.headToHead.orEmpty()
            if (meetings.isNotEmpty()) add(CentreItem.Heading(R.string.iptv_sport5p_head_to_head, "h:h2h"))
            meetings.forEachIndexed { i, meeting ->
                val date = meeting.dateMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }?.let { sportDate(it, "dMMMyyyy") }
                val score = meeting.score ?: if (meeting.homeScore != null && meeting.awayScore != null) "${meeting.homeScore}–${meeting.awayScore}" else null
                add(CentreItem.Line(listOfNotNull(date, meeting.opponent, score).joinToString(" · "), meeting.result, false, "hh:$i"))
            }
        }
        CentreTab.OTHER -> state.rows.asSequence().flatMap { it.items.asSequence() }.filter { it.fixture.key != fixture.key && it.fixture.status == FixtureStatus.LIVE }
            .distinctBy { it.fixture.key }.map { CentreItem.Game(it, scoreHidden(state, it.fixture), "g:${it.fixture.key}") }.toList()
    }
}

@Composable
private fun CentreRow(item: CentreItem, onClick: (() -> Unit)?) {
    if (item is CentreItem.Heading) { SectionLabel(stringResource(item.text).uppercase()); return }
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth()
        .focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel }
        .onFocusChanged { focused = it.isFocused }
        .iptvItem(focused)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (onClick != null && isSelect(native.keyCode)) { if (native.action == AndroidKeyEvent.ACTION_UP) onClick(); true } else false
        }
        .focusable().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when (item) {
            is CentreItem.Moment -> {
                val moment = item.moment
                Text(moment.clock ?: moment.period?.let { "P$it" }.orEmpty(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextSecondary, maxLines = 1, modifier = Modifier.width(52.dp))
                MomentMark(moment.kind)
                Column(Modifier.weight(1f)) {
                    Text(moment.text, style = iptvItemStyle(scoringMoment(moment.kind), compact = true), color = itemContent(focused), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val score = if (moment.homeScore != null && moment.awayScore != null) "${moment.homeScore}–${moment.awayScore}" else null
                    listOfNotNull(item.team, score).joinToString(" · ").takeIf(String::isNotEmpty)?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
                    }
                }
                if (onClick != null) Text(stringResource(R.string.iptv_sport5p_watch), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = if (focused) itemContent(true) else NuvioTheme.colors.Secondary, maxLines = 1)
            }
            is CentreItem.Stat -> StatBar(item, focused)
            is CentreItem.Line -> {
                Text(item.text, style = iptvItemStyle(item.strong, compact = true), color = itemContent(focused), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                item.trailing?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 260.dp)) }
            }
            is CentreItem.Game -> {
                val text = SportsFixtureText.bug(item.item.fixture)
                Column(Modifier.weight(1f)) {
                    Text(if (item.hidden) sportTitle(item.item.fixture) else text.primary, style = iptvItemStyle(true, compact = true), color = itemContent(focused),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(sportLeagueName(item.item.fixture), text.state.takeIf { !item.hidden }, item.item.links.firstOrNull()?.row?.let(::channelName))
                        .joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (onClick != null) Text(stringResource(R.string.iptv_sport5p_watch), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = if (focused) itemContent(true) else NuvioTheme.colors.Secondary, maxLines = 1)
            }
            is CentreItem.Heading -> Unit
        }
    }
}

@Composable
private fun MomentMark(kind: MomentKind) {
    val (fill, text) = when (kind) {
        MomentKind.CARD_YELLOW -> Color(0xFFFFD54F) to ""
        MomentKind.CARD_RED -> Color(0xFFE53935) to ""
        MomentKind.SUBSTITUTION -> NuvioTheme.colors.TextPrimary.copy(alpha = .14f) to "⇄"
        MomentKind.GOAL -> NuvioTheme.colors.Secondary to "G"
        MomentKind.TRY -> NuvioTheme.colors.Secondary to "T"
        MomentKind.TOUCHDOWN -> NuvioTheme.colors.Secondary to "TD"
        MomentKind.RUN -> NuvioTheme.colors.Secondary to "R"
        MomentKind.POINTS -> NuvioTheme.colors.Secondary to "•"
        MomentKind.OTHER -> NuvioTheme.colors.TextPrimary.copy(alpha = .14f) to "•"
    }
    val card = kind == MomentKind.CARD_YELLOW || kind == MomentKind.CARD_RED
    Box(Modifier.size(if (card) 14.dp else 24.dp, if (card) 18.dp else 24.dp).clip(if (card) RoundedCornerShape(2.dp) else CircleShape).background(fill),
        contentAlignment = Alignment.Center) {
        if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.Background, maxLines = 1)
    }
}

private fun statValue(text: String): Double? = text.trim().removeSuffix("%").substringBefore('/').substringBefore('-').trim().toDoubleOrNull()

@Composable
private fun RowScope.StatBar(item: CentreItem.Stat, focused: Boolean) {
    val home = statValue(item.home)
    val away = statValue(item.away)
    val share = if (home != null && away != null && home + away > 0) (home / (home + away)).toFloat() else null
    Text(item.home, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = itemContent(focused), maxLines = 1,
        textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.label, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (share != null) Row(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))) {
            if (share > 0f) Box(Modifier.weight(share).fillMaxHeight().background(item.homeColour ?: NuvioTheme.colors.Secondary))
            if (share < 1f) Box(Modifier.weight(1f - share).fillMaxHeight().background(item.awayColour ?: NuvioTheme.colors.TextPrimary.copy(alpha = .3f)))
        }
    }
    Text(item.away, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = itemContent(focused), maxLines = 1,
        modifier = Modifier.width(56.dp))
}

@Composable
internal fun WinProbabilityLine(points: List<Float>, home: FixtureTeam?, away: FixtureTeam?, modifier: Modifier) {
    if (points.size < 2) return
    val homeColour = teamColour(home) ?: NuvioTheme.colors.Secondary
    val awayColour = teamColour(away) ?: NuvioTheme.colors.TextSecondary
    val mid = NuvioTheme.colors.TextPrimary.copy(alpha = .2f)
    Canvas(modifier) {
        val step = size.width / (points.size - 1)
        drawLine(mid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
        val path = Path()
        points.forEachIndexed { i, p -> val x = i * step; val y = size.height * (1f - p); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        drawPath(path, if (points.last() >= .5f) homeColour else awayColour, style = Stroke(2.dp.toPx()))
    }
}

@Composable
internal fun CountBases(count: SummaryCount, size: Dp) {
    Box(Modifier.size(size * 3.2f, size * 2.4f)) {
        Base(count.onSecond, size, Modifier.align(Alignment.TopCenter))
        Base(count.onThird, size, Modifier.align(Alignment.BottomStart))
        Base(count.onFirst, size, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun Base(on: Boolean, size: Dp, modifier: Modifier) {
    Box(modifier.size(size).rotate(45f).clip(RoundedCornerShape(2.dp))
        .background(if (on) NuvioTheme.colors.Secondary else Color.Transparent).border(1.5.dp, NuvioTheme.colors.TextSecondary, RoundedCornerShape(2.dp)))
}

private const val FIXTURE_SLACK = 15L * 60 * 1000
