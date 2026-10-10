@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureSide
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.FixtureTeam
import com.nuvio.tv.core.iptv.PeriodName
import com.nuvio.tv.core.iptv.SportsDetail
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureText
import com.nuvio.tv.core.iptv.SportsGoalies
import com.nuvio.tv.core.iptv.SportsLedger
import com.nuvio.tv.core.iptv.SportsSummary
import com.nuvio.tv.data.iptv.IptvFixtureLink
import com.nuvio.tv.ui.components.rememberShimmerBrush
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun IptvSportHero(active: Boolean, modifier: Modifier, blocked: Boolean = false, fallback: @Composable (Modifier) -> Unit) {
    if (!active) { fallback(modifier); return }
    val viewModel: IptvSportsFixturesViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val key by viewModel.hero.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val item = when (val wanted = key) {
        null -> null
        IptvSportsFixturesViewModel.FEATURED -> state.rows.firstOrNull()?.items?.firstOrNull()
        else -> state.item(wanted)
    }
    val watched = item?.fixture?.takeIf { it.status == FixtureStatus.LIVE && !state.hidden(it) && it.teams && (it.sport == "soccer" || it.sport == "ice-hockey") }
    LaunchedEffect(watched?.key) { viewModel.watchSummary(watched) }
    DisposableEffect(viewModel) { onDispose { viewModel.watchSummary(null) } }
    when {
        !state.enabled -> fallback(modifier)
        item != null -> SportHero(item, state, summary?.takeIf { watched != null && it.eventId == watched.id }, modifier,
            onWatch = { links -> if (links.size == 1) viewModel.prompt(IptvSportPrompt.Watch(links.first().row)) else viewModel.prompt(IptvSportPrompt.Channels(item.fixture.key)) },
            onFeeds = { viewModel.prompt(IptvSportPrompt.Channels(item.fixture.key)) }, onFollow = { viewModel.prompt(IptvSportPrompt.Options(item.fixture.key)) },
            onRemind = { viewModel.toggleReminder(item.fixture) }, blocked = blocked)
        key == IptvSportsFixturesViewModel.FEATURED && state.loading -> SportHeroPlaceholder(modifier)
        else -> fallback(modifier)
    }
}

@Composable
private fun SportHeroPlaceholder(modifier: Modifier) {
    val brush = rememberShimmerBrush(backdropAware = LocalV2Appearance.current != null)
    Column(modifier.iptvPanel().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(brush))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.width(200.dp).height(12.dp).clip(SportPlaceholderShape).background(brush))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.width(90.dp).height(26.dp).clip(SportPlaceholderShape).background(brush))
                    Box(Modifier.width(150.dp).height(52.dp).clip(SportPlaceholderShape).background(brush))
                    Box(Modifier.width(90.dp).height(26.dp).clip(SportPlaceholderShape).background(brush))
                }
            }
            Column(Modifier.width(HERO_SIDE), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(90.dp).height(12.dp).clip(SportPlaceholderShape).background(brush))
                Box(Modifier.width(150.dp).height(36.dp).clip(SportPlaceholderShape).background(brush))
                Box(Modifier.fillMaxWidth().height(28.dp).clip(SportPlaceholderShape).background(brush))
            }
        }
    }
}

private class Clock(val label: String?, val big: String?, val sentence: String?)

@Composable
private fun SportHero(item: IptvFixtureItem, state: IptvFixturesState, summary: SportsSummary?, modifier: Modifier, onWatch: (List<IptvFixtureLink>) -> Unit,
    onFeeds: () -> Unit, onFollow: () -> Unit, onRemind: () -> Unit, blocked: Boolean = false) {
    val fixture = item.fixture
    val hidden = state.hidden(fixture)
    val now by produceState(System.currentTimeMillis(), fixture.status) {
        while (fixture.status == FixtureStatus.SCHEDULED) { delay(COUNTDOWN_TICK_MILLIS); value = System.currentTimeMillis() }
    }
    BoxWithConstraints(modifier.iptvPanel().padding(horizontal = 20.dp, vertical = 12.dp)) {
        val compact = maxHeight < COMPACT_HEIGHT
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(NuvioTheme.colors.TextPrimary))
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
                    StatusLine(item, hidden)
                    LedgerBody(item, state, summary, hidden, compact, Modifier.fillMaxWidth().weight(1f))
                }
                Spacer(Modifier.width(20.dp))
                Box(Modifier.width(1.dp).fillMaxHeight().background(hairlineColour()))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.width(HERO_SIDE).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
                    val clock = heroClock(fixture, item, hidden, now, summary)
                    clock.label?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    clock.big?.let { big ->
                        val size = when {
                            big.length > 14 -> if (compact) 18.sp else 22.sp
                            big.length > 9 -> if (compact) 22.sp else 28.sp
                            else -> if (compact) 26.sp else 38.sp
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(big, style = ledgerNumerals(size), color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false))
                            val bases = (fixture.sportDetail as? SportsDetail.Baseball)?.takeIf { fixture.status == FixtureStatus.LIVE && !hidden && !item.scheduleOnly }
                            if (bases != null) BasesDiamond(bases.first, bases.second, bases.third, 24.dp)
                        }
                    }
                    clock.sentence?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary, maxLines = if (compact) 1 else 3,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    } ?: Spacer(Modifier.weight(1f))
                    LedgerActions(item, fixture.key in state.reminders, compact, onWatch, onFeeds, onFollow, onRemind, blocked)
                }
            }
        }
    }
}

@Composable
private fun hairlineColour(): Color = NuvioTheme.colors.TextPrimary.copy(alpha = .16f)

@Composable
private fun ledgerNumerals(size: TextUnit): TextStyle =
    MaterialTheme.typography.displaySmall.copy(fontSize = size, lineHeight = size, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

@Composable
private fun StatusLine(item: IptvFixtureItem, hidden: Boolean) {
    val fixture = item.fixture
    Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (fixture.status) {
            FixtureStatus.LIVE -> {
                Box(Modifier.size(7.dp).clip(CircleShape).background(NuvioTheme.colors.Error))
                Text(stringResource(R.string.iptv_sport_live), style = SportCaps, color = NuvioTheme.colors.Error, maxLines = 1)
            }
            FixtureStatus.FINAL -> Text(stringResource(R.string.iptv_sport2_final).uppercase(), style = SportCaps, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
            FixtureStatus.SCHEDULED -> Unit
        }
        fixture.leagueLogo?.let { LeagueLogo(it, 14.dp) }
        val round = fixture.round?.let { stringResource(if (fixture.sport == "american-football") R.string.iptv_sport2_week else R.string.iptv_sport2_round, it) }
        val detail = fixture.sportDetail
        val place = when (detail) {
            is SportsDetail.Golf -> detail.tournament
            is SportsDetail.Tennis -> listOfNotNull(detail.tournament, detail.court).joinToString(" · ").takeIf(String::isNotEmpty)
            is SportsDetail.Sessions -> detail.venue ?: fixture.venue
            else -> fixture.venue
        }
        Text(listOfNotNull(sportLeagueName(fixture), round, place).joinToString(" · "), style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (item.favourite) Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = NuvioTheme.colors.Secondary)
    }
}

@Composable
private fun LedgerBody(item: IptvFixtureItem, state: IptvFixturesState, summary: SportsSummary?, hidden: Boolean, compact: Boolean, modifier: Modifier) {
    val fixture = item.fixture
    val home = fixture.home
    val away = fixture.away
    val detail = fixture.sportDetail.takeIf { !item.scheduleOnly }
    val scheduled = fixture.status == FixtureStatus.SCHEDULED
    when {
        detail is SportsDetail.Golf -> Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!hidden && !scheduled && detail.leaders.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val rows = ((maxHeight - GOLF_HEAD) / GOLF_ROW).toInt().coerceIn(1, GOLF_MAX_ROWS)
                GolfBoard(fixture, detail, state.favourites, rows, Modifier.fillMaxWidth())
            } else {
                LedgerTitle(detail.tournament, compact)
                if (hidden && detail.leaders.isNotEmpty()) MaskBar(160.dp)
            }
        }
        detail is SportsDetail.Tennis && home != null && away != null -> Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
            TennisLedgerLine(home, detail.homePlayer.seed, FixtureSide.HOME, detail, fixture.status == FixtureStatus.LIVE, hidden, compact)
            Box(Modifier.fillMaxWidth().height(1.dp).background(hairlineColour()))
            TennisLedgerLine(away, detail.awayPlayer.seed, FixtureSide.AWAY, detail, fixture.status == FixtureStatus.LIVE, hidden, compact)
        }
        detail is SportsDetail.Sessions -> Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val podium = detail.sessions.lastOrNull { it.state == FixtureStatus.FINAL && it.top.isNotEmpty() }?.takeIf { !hidden }
            if (podium != null) {
                LedgerTitle(fixture.title, compact)
                Text(podium.name, style = SportCaps, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    podium.top.take(3).forEachIndexed { index, name ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${index + 1}", style = ledgerNumerals(if (compact) 24.sp else 32.sp), color = if (index == 0) NuvioTheme.colors.TextPrimary
                                else NuvioTheme.colors.TextSecondary, maxLines = 1)
                            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else SportDetailBody(fixture, hidden, state.favourites, Modifier.fillMaxWidth().weight(1f))
        }
        detail is SportsDetail.Card -> SportDetailBody(fixture, hidden, state.favourites, modifier)
        detail is SportsDetail.Cricket && home != null && away != null && !hidden && !scheduled && detail.innings.isNotEmpty() ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                listOf(home, away).forEachIndexed { index, team ->
                    if (index == 1) Box(Modifier.fillMaxWidth().height(1.dp).background(hairlineColour()))
                    val innings = detail.innings.filter { it.team == (team.abbreviation ?: team.shortName ?: team.name) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TeamLogo(team, if (compact) 22.dp else 28.dp)
                        Text(team.shortName ?: team.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (innings.lastOrNull()?.batting == true) Box(Modifier.size(6.dp).clip(CircleShape).background(NuvioTheme.colors.Warning))
                        innings.forEachIndexed { at, entry ->
                            Text("${entry.runs}" + if (entry.wickets < 10) "/${entry.wickets}" else "", style = ledgerNumerals(if (compact) 26.sp else 34.sp), maxLines = 1,
                                color = if (at == innings.lastIndex) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary)
                        }
                    }
                }
            }
        home != null && away != null -> TeamsLedger(item, home, away, summary, hidden, compact, modifier)
        else -> Box(modifier, contentAlignment = Alignment.CenterStart) { LedgerTitle(fixture.title, compact) }
    }
}

@Composable
private fun LedgerTitle(text: String, compact: Boolean) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary,
        maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun TeamsLedger(item: IptvFixtureItem, home: FixtureTeam, away: FixtureTeam, summary: SportsSummary?, hidden: Boolean, compact: Boolean, modifier: Modifier) {
    val fixture = item.fixture
    val live = fixture.status == FixtureStatus.LIVE
    val scheduled = fixture.status == FixtureStatus.SCHEDULED
    val awayFirst = SportsFixtureText.awayFirst(fixture)
    val first = if (awayFirst) away else home
    val second = if (awayFirst) home else away
    val scores = SportsFixtureText.scores(fixture)?.takeIf { !scheduled && !item.scheduleOnly }?.let { if (awayFirst) it.second to it.first else it }
    val lead = scores?.let { SportsLedger.leader(it.first, it.second) }
    val ball = fixture.situation?.possession?.takeIf { live && !hidden && scores != null }?.let { if (it == FixtureSide.HOME) home else away }
    val big = if (compact) 44.sp else 60.sp
    val built = remember(fixture, summary) { SportsStrip.build(fixture, summary) }
    val strip = !hidden && live && built != null && (fixture.situation?.homeWinPercent == null || summary != null)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomStart) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)) {
                LedgerTeam(first, fixture.sport, scheduled, compact, ball == first, Modifier.weight(1f, fill = false))
                when {
                    scores != null && hidden -> MaskBar(if (compact) 90.dp else 120.dp, Modifier.padding(bottom = 10.dp))
                    scores != null -> {
                        Text(scores.first, style = ledgerNumerals(big), color = if (lead == 1) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary, maxLines = 1)
                        Text("/", style = ledgerNumerals(big * .5f), color = NuvioTheme.colors.TextTertiary, maxLines = 1)
                        Text(scores.second, style = ledgerNumerals(big), color = if (lead == 0) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary, maxLines = 1)
                    }
                    scheduled -> Text(clock(fixture.startMillis), style = ledgerNumerals(big * .8f), color = NuvioTheme.colors.TextPrimary, maxLines = 1)
                    item.scheduleOnly && live -> Text(stringResource(R.string.iptv_sport5_section_no_live_score), style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.colors.TextTertiary, maxLines = 1, modifier = Modifier.padding(bottom = 8.dp))
                    else -> Text("v", style = ledgerNumerals(big * .5f), color = NuvioTheme.colors.TextTertiary, maxLines = 1)
                }
                LedgerTeam(second, fixture.sport, scheduled, compact, ball == second, Modifier.weight(1f, fill = false))
            }
        }
        if (strip) SportHeroStrip(fixture, summary, Modifier.fillMaxWidth())
        else if (!compact && scores != null && !hidden) PeriodTable(fixture, first, second, awayFirst)
    }
}

@Composable
private fun LedgerTeam(team: FixtureTeam, sport: String, scheduled: Boolean, compact: Boolean, possession: Boolean, modifier: Modifier) {
    val (city, nickname) = SportsLedger.names(team)
    Column(modifier) {
        city?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TeamLogo(team, if (compact) 18.dp else 22.dp)
            Text(nickname, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary, maxLines = if (city == null && !compact) 2 else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            if (possession) Box(Modifier.size(6.dp).clip(CircleShape).background(NuvioTheme.colors.Secondary))
        }
        if (scheduled) sportRecord(sport, team.record)?.let {
            Text(stringResource(R.string.iptv_ui10_sport_season_record, it), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PeriodTable(fixture: SportsFixture, first: FixtureTeam, second: FixtureTeam, awayFirst: Boolean) {
    val lines = if (awayFirst) listOf(fixture.awayLine, fixture.homeLine) else listOf(fixture.homeLine, fixture.awayLine)
    val count = lines.maxOf { it?.periods?.size ?: 0 }
    if (count == 0) return
    val periods = (maxOf(0, count - MAX_PERIODS) until count).toList()
    val current = SportsLedger.currentPeriod(fixture, count)
    val cells = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(hairlineColour()))
        Spacer(Modifier.height(3.dp))
        Row(Modifier.height(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(TABLE_NAME))
            periods.forEach { index ->
                Text(SportsFixtureText.periodLabel(fixture.sport, index + 1) ?: "${index + 1}", style = SportCaps, maxLines = 1, textAlign = TextAlign.Center,
                    color = if (index == current) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary, modifier = Modifier.width(TABLE_CELL))
            }
            Text(stringResource(R.string.iptv_ui11_sport_total).uppercase(Locale.getDefault()), style = SportCaps, color = NuvioTheme.colors.TextTertiary, maxLines = 1,
                textAlign = TextAlign.End, modifier = Modifier.width(TABLE_TOTAL))
        }
        listOf(first, second).forEachIndexed { row, team ->
            val line = lines[row]
            Row(Modifier.height(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(SportsLedger.code(team) ?: team.shortName ?: team.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(TABLE_NAME))
                periods.forEach { index ->
                    Text(line?.periods?.getOrNull(index).orEmpty(), style = cells, maxLines = 1, textAlign = TextAlign.Center,
                        fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                        color = if (index == current) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary, modifier = Modifier.width(TABLE_CELL))
                }
                Text(line?.score.orEmpty(), style = cells, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, maxLines = 1, textAlign = TextAlign.End,
                    modifier = Modifier.width(TABLE_TOTAL))
            }
        }
    }
}

@Composable
private fun TennisLedgerLine(team: FixtureTeam, seed: Int?, side: FixtureSide, detail: SportsDetail.Tennis, live: Boolean, hidden: Boolean, compact: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (live && !hidden && detail.server == side) NuvioTheme.colors.Warning else Color.Transparent))
        Text(team.name, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        seed?.let { Text("($it)", style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary, maxLines = 1) }
        Spacer(Modifier.weight(1f))
        if (hidden && detail.sets.isNotEmpty()) MaskBar(90.dp)
        else detail.sets.takeLast(MAX_SETS_SHOWN).forEach { set ->
            val games = if (side == FixtureSide.HOME) set.home else set.away
            val current = live && set.winner == null
            Text(games?.toString().orEmpty(), style = ledgerNumerals(if (compact) 24.sp else 32.sp), textAlign = TextAlign.Center, maxLines = 1,
                color = when {
                    current -> NuvioTheme.colors.Warning
                    set.winner == side -> NuvioTheme.colors.TextPrimary
                    else -> NuvioTheme.colors.TextTertiary
                }, modifier = Modifier.width(if (compact) 26.dp else 34.dp))
        }
    }
}

@Composable
private fun heroClock(fixture: SportsFixture, item: IptvFixtureItem, hidden: Boolean, now: Long, summary: SportsSummary?): Clock {
    val zone = ZoneId.systemDefault()
    val date = sportDate(Instant.ofEpochMilli(fixture.startMillis).atZone(zone).toLocalDate(), "EEEEdMMMM")
    val finished = stringResource(R.string.iptv_sport2_final)
    val live = stringResource(R.string.iptv_sport_live)
    val detail = fixture.sportDetail.takeIf { !item.scheduleOnly }
    if (fixture.status == FixtureStatus.SCHEDULED) {
        val broadcasters = fixture.broadcasters.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.iptv_sport_broadcaster, it.joinToString(", ")) }
        val start = if (fixture.home != null && fixture.away != null && detail !is SportsDetail.Tennis) date else "$date · ${clock(fixture.startMillis)}"
        return Clock(start, sportCountdown(now, fixture.startMillis), broadcasters)
    }
    if (fixture.status == FixtureStatus.FINAL) {
        val result = when (detail) {
            is SportsDetail.Cricket -> fixture.detail?.takeIf { !hidden }
            is SportsDetail.Golf -> detail.purse
            else -> null
        }
        return Clock(sportDayLabel(fixture.startMillis), finished, result)
    }
    if (hidden || item.scheduleOnly) return Clock(null, live, null)
    return when (detail) {
        is SportsDetail.Golf -> Clock(detail.statusText, detail.round?.let { stringResource(R.string.iptv_sport2_round, it) } ?: live, detail.purse)
        is SportsDetail.Tennis -> {
            val server = detail.server?.let { if (it == FixtureSide.HOME) fixture.home else fixture.away }
            Clock(detail.round, fixture.period?.let { stringResource(R.string.iptv_sport5_section_set, it) } ?: live,
                server?.let { stringResource(R.string.iptv_sport5_section_serving, it.shortName ?: it.name.substringAfterLast(' ')) })
        }
        is SportsDetail.Sessions -> {
            val session = detail.current
            Clock(session?.name, session?.takeIf { it.state == FixtureStatus.SCHEDULED }?.let { sportWhen(it.startMillis) } ?: live, null)
        }
        is SportsDetail.Card -> {
            val bout = detail.live
            Clock(bout?.weightClass, bout?.let { b -> b.round?.let { stringResource(R.string.iptv_sport5_section_fight_round, it) } } ?: live,
                bout?.let { "${fighterName(it.first)} v ${fighterName(it.second)}" })
        }
        is SportsDetail.Cricket -> {
            val batting = detail.innings.lastOrNull { it.batting } ?: detail.innings.lastOrNull()
            val chase = detail.chase?.let { c -> batting?.let { stringResource(R.string.iptv_sport5_section_chase, it.team, c.runs, c.balls,
                String.format(Locale.getDefault(), "%.2f", c.rate)) } }
            Clock(batting?.target?.let { stringResource(R.string.iptv_sport5_section_target, it) } ?: fixture.detail,
                batting?.overs?.let { stringResource(R.string.iptv_sport5_section_overs, it) } ?: live, chase)
        }
        is SportsDetail.Baseball -> {
            val outs = detail.outs?.let { pluralStringResource(R.plurals.iptv_sport5_section_outs, it, it) }
            val count = if (detail.balls != null && detail.strikes != null) "${detail.balls}–${detail.strikes}" else null
            Clock(listOfNotNull(outs, count).joinToString(" · ").takeIf(String::isNotEmpty), sportInning(detail) ?: live, situationSentence(fixture))
        }
        null -> {
            val name = when (SportsLedger.periodName(fixture.sport, fixture.period)) {
                PeriodName.QUARTER -> stringResource(R.string.iptv_ui11_sport_quarter, fixture.period ?: 0)
                PeriodName.PERIOD -> stringResource(R.string.iptv_ui11_sport_period, fixture.period ?: 0)
                PeriodName.FIRST_HALF -> stringResource(R.string.iptv_ui11_sport_first_half)
                PeriodName.SECOND_HALF -> stringResource(R.string.iptv_ui11_sport_second_half)
                PeriodName.EXTRA_TIME -> stringResource(R.string.iptv_ui11_sport_extra_time)
                PeriodName.OVERTIME -> stringResource(R.string.iptv_ui11_sport_overtime)
                null -> null
            }
            val running = fixture.clock?.trim()?.takeIf { it.isNotEmpty() && it != "0:00" && it != "0'" && it != "0" }
            val big = when {
                running != null && SportsLedger.countsDown(fixture.sport) -> stringResource(R.string.iptv_ui11_sport_clock_left, running)
                running != null -> running
                else -> fixture.detail ?: live
            }
            Clock(name ?: fixture.detail?.takeIf { it != big }, big, listOfNotNull(goalieSentence(fixture, summary), situationSentence(fixture)).joinToString(" ")
                .takeIf(String::isNotEmpty))
        }
    }
}

@Composable
private fun goalieSentence(fixture: SportsFixture, summary: SportsSummary?): String? {
    if (fixture.sport != "ice-hockey") return null
    val goalies = summary?.goalies?.takeIf { it.isNotEmpty() } ?: return null
    return SportsGoalies.inNet(goalies, SportsFixtureText.awayFirst(fixture)).mapNotNull { goalie ->
        val saves = goalie.saves ?: return@mapNotNull null
        val text = pluralStringResource(R.plurals.iptv_ui12_sport_saves, saves, saves)
        val name = SportsGoalies.surname(goalie.name)
        SportsGoalies.savePct(goalie.savePct)?.let { stringResource(R.string.iptv_ui12_sport_goalie_line, name, text, it) } ?: "$name $text"
    }.joinToString(" · ").takeIf(String::isNotEmpty)?.let { if (it.endsWith('.')) it else "$it." }
}

@Composable
private fun situationSentence(fixture: SportsFixture): String? {
    val situation = fixture.situation ?: return null
    val home = fixture.home
    val away = fixture.away
    val win = situation.homeWinPercent?.coerceIn(0, 100)?.takeIf { home != null && away != null }?.let { homeWin ->
        val awayFirst = SportsFixtureText.awayFirst(fixture)
        val first = if (awayFirst) away!! else home!!
        val second = if (awayFirst) home!! else away!!
        val firstShare = if (awayFirst) 100 - homeWin else homeWin
        stringResource(R.string.iptv_ui11_sport_win_chance, first.shortName ?: first.name, firstShare, second.shortName ?: second.name, 100 - firstShare)
    }
    return listOfNotNull(situation.downDistance, situation.lastPlay?.trim()?.let { if (it.endsWith('.')) it else "$it." }, win).joinToString(" ")
        .takeIf(String::isNotEmpty)
}

@Composable
private fun LedgerActions(item: IptvFixtureItem, reminded: Boolean, compact: Boolean, onWatch: (List<IptvFixtureLink>) -> Unit, onFeeds: () -> Unit,
    onFollow: () -> Unit, onRemind: () -> Unit, blocked: Boolean) {
    val fixture = item.fixture
    val first = item.links.firstOrNull()
    val feeds = item.links.size > 1
    val follow = sportFollowable(fixture).isNotEmpty()
    val remind = sportCanRemind(fixture)
    val crowded = listOf(feeds, follow, remind).count { it } > 2
    val small = if (compact) 24.dp else 26.dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
        if (first != null) LedgerButton(stringResource(R.string.iptv_sport5_section_watch_on, channelName(first.row)), { onWatch(item.links) },
            Modifier.fillMaxWidth(), primary = true, icon = Icons.Filled.PlayArrow, height = if (compact) 26.dp else 30.dp, blocked = blocked)
        else if (fixture.status != FixtureStatus.FINAL) Text(stringResource(if (item.linking) R.string.iptv_sport3_finding_channels else R.string.iptv_sport_no_channel),
            style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (feeds || follow || remind) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (feeds) LedgerButton(pluralStringResource(R.plurals.iptv_sport5_section_other_feeds, item.links.size - 1, item.links.size - 1), onFeeds,
                Modifier.weight(1f, fill = false), height = small, blocked = blocked)
            if (follow) LedgerButton(if (crowded) null else stringResource(if (item.favourite) R.string.iptv_sport5_section_following else R.string.iptv_sport5_section_follow),
                onFollow, Modifier.weight(1f, fill = false), icon = if (item.favourite) Icons.Filled.Star else Icons.Filled.StarBorder, height = small, blocked = blocked)
            if (remind) LedgerButton(if (crowded) null else stringResource(if (reminded) R.string.iptv_sport5_section_reminder_set else R.string.iptv_sport5_section_remind),
                onRemind, Modifier.weight(1f, fill = false), icon = if (reminded) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsNone, height = small, blocked = blocked)
        }
    }
}

@Composable
private fun LedgerButton(text: String?, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, icon: ImageVector? = null, height: Dp = 26.dp,
    blocked: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val content = if (primary && !focused) NuvioTheme.colors.Background else itemContent(focused)
    Row(modifier.height(height).onFocusChanged { focused = it.isFocused }.iptvItem(focused, shape = LedgerButtonShape)
        .then(when {
            focused -> Modifier
            primary -> Modifier.background(NuvioTheme.colors.TextPrimary, LedgerButtonShape)
            else -> Modifier.border(1.dp, NuvioTheme.colors.TextPrimary.copy(alpha = .3f), LedgerButtonShape)
        })
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (isSelect(native.keyCode)) { if (native.action == AndroidKeyEvent.ACTION_UP) onClick(); true } else false
        }
        .focusable(enabled = !blocked).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = content)
        if (text != null) Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, color = content,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val LedgerButtonShape = RoundedCornerShape(6.dp)
private val HERO_SIDE = 236.dp
private val COMPACT_HEIGHT = 196.dp
private val TABLE_NAME = 64.dp
private val TABLE_CELL = 32.dp
private val TABLE_TOTAL = 44.dp
private val GOLF_HEAD = 22.dp
private val GOLF_ROW = 24.dp
private const val GOLF_MAX_ROWS = 6
private const val MAX_PERIODS = 9
private const val MAX_SETS_SHOWN = 5
private const val COUNTDOWN_TICK_MILLIS = 30_000L
