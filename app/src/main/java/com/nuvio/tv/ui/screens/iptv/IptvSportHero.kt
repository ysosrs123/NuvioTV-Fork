@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureLine
import com.nuvio.tv.core.iptv.SportsCountdown
import com.nuvio.tv.core.iptv.FixtureSide
import com.nuvio.tv.core.iptv.FixtureSituation
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.FixtureTeam
import com.nuvio.tv.core.iptv.SportsDetail
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureText
import com.nuvio.tv.core.iptv.SportsSummary
import com.nuvio.tv.data.iptv.IptvFixtureLink
import com.nuvio.tv.ui.components.rememberShimmerBrush
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay

@Composable
internal fun IptvSportHero(active: Boolean, modifier: Modifier, fallback: @Composable (Modifier) -> Unit) {
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
    val watched = item?.fixture?.takeIf { it.status == FixtureStatus.LIVE && !state.hidden(it) && it.teams && it.sport == "soccer" }
    LaunchedEffect(watched?.key) { viewModel.watchSummary(watched) }
    DisposableEffect(viewModel) { onDispose { viewModel.watchSummary(null) } }
    when {
        !state.enabled -> fallback(modifier)
        item != null -> SportHero(item, state, summary?.takeIf { watched != null && it.eventId == watched.id }, modifier,
            onWatch = { links -> if (links.size == 1) viewModel.prompt(IptvSportPrompt.Watch(links.first().row)) else viewModel.prompt(IptvSportPrompt.Channels(item.fixture.key)) },
            onFeeds = { viewModel.prompt(IptvSportPrompt.Channels(item.fixture.key)) }, onFollow = { viewModel.prompt(IptvSportPrompt.Options(item.fixture.key)) },
            onRemind = { viewModel.toggleReminder(item.fixture) })
        key == IptvSportsFixturesViewModel.FEATURED && state.loading -> SportHeroPlaceholder(modifier)
        else -> fallback(modifier)
    }
}

@Composable
private fun SportHeroPlaceholder(modifier: Modifier) {
    val brush = rememberShimmerBrush(backdropAware = LocalV2Appearance.current != null)
    Column(modifier.iptvPanel().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(180.dp).height(12.dp).clip(SportPlaceholderShape).background(brush))
        Box(Modifier.fillMaxWidth(.55f).height(22.dp).clip(SportPlaceholderShape).background(brush))
        repeat(2) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(brush))
                Box(Modifier.width(140.dp).height(14.dp).clip(SportPlaceholderShape).background(brush))
            }
        }
    }
}

@Composable
private fun SportHero(item: IptvFixtureItem, state: IptvFixturesState, summary: SportsSummary?, modifier: Modifier, onWatch: (List<IptvFixtureLink>) -> Unit,
    onFeeds: () -> Unit, onFollow: () -> Unit, onRemind: () -> Unit) {
    val fixture = item.fixture
    val live = fixture.status == FixtureStatus.LIVE
    val hidden = state.hidden(fixture)
    Column(modifier.iptvPanel().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fixture.leagueLogo?.let { LeagueLogo(it, 16.dp) }
            val round = fixture.round?.let { stringResource(if (fixture.sport == "american-football") R.string.iptv_sport2_week else R.string.iptv_sport2_round, it) }
            Text(listOfNotNull(sportLeagueName(fixture), round, fixture.venue).joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (item.favourite) Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = NuvioTheme.colors.Secondary)
            HeroState(fixture, !hidden)
        }
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(sportTitle(fixture), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            HeroActions(item, fixture.key in state.reminders, onWatch, onFeeds, onFollow, onRemind)
        }
        val home = fixture.home
        val away = fixture.away
        val detail = fixture.sportDetail.takeIf { !item.scheduleOnly }
        if (home != null && away != null && fixture.status == FixtureStatus.SCHEDULED) {
            HeroPreMatch(fixture, home, away, Modifier.fillMaxWidth().weight(1f))
        } else if (home != null && away != null) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                HeroScoreboard(fixture, home, away, !hidden && fixture.status != FixtureStatus.SCHEDULED && !item.scheduleOnly,
                    hidden && fixture.status != FixtureStatus.SCHEDULED, Modifier.weight(1.15f).fillMaxHeight())
                val situation = fixture.situation?.takeIf { !hidden && live && (it.downDistance != null || it.lastPlay != null) }
                if (situation != null) HeroSituation(situation, Modifier.weight(1f).fillMaxHeight())
            }
            val win = fixture.situation?.homeWinPercent?.takeIf { !hidden && live }
            if (!hidden && live && SportsStrip.supports(fixture.sport) && (win == null || summary != null)) SportHeroStrip(fixture, summary, Modifier.fillMaxWidth())
            else win?.let { WinBar(fixture, home, away, it) }
        } else if (detail is SportsDetail.Golf && !hidden && fixture.status != FixtureStatus.SCHEDULED && detail.leaders.isNotEmpty()) {
            GolfBoard(fixture, detail, state.favourites, Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp))
        } else if (detail is SportsDetail.Golf || detail is SportsDetail.Sessions || detail is SportsDetail.Card) {
            SportDetailBody(fixture, hidden, state.favourites, Modifier.fillMaxWidth(.6f).weight(1f))
        } else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun HeroState(fixture: SportsFixture, showScores: Boolean) {
    when (fixture.status) {
        FixtureStatus.LIVE -> {
            Box(Modifier.size(8.dp).clip(CircleShape).background(NuvioTheme.colors.Error))
            Text(SportsFixtureText.periodClock(fixture)?.takeIf { showScores } ?: stringResource(R.string.iptv_sport_live),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
        }
        FixtureStatus.FINAL -> Text(stringResource(R.string.iptv_sport2_final), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextSecondary, maxLines = 1)
        FixtureStatus.SCHEDULED -> Text("${sportDayLabel(fixture.startMillis)} · ${clock(fixture.startMillis)}", style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
    }
}

@Composable
private fun HeroActions(item: IptvFixtureItem, reminded: Boolean, onWatch: (List<IptvFixtureLink>) -> Unit, onFeeds: () -> Unit, onFollow: () -> Unit,
    onRemind: () -> Unit) {
    val fixture = item.fixture
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val first = item.links.firstOrNull()
        if (first != null) {
            SportChip(stringResource(R.string.iptv_sport5_section_watch_on, channelName(first.row)), { onWatch(item.links) }, Modifier.widthIn(max = 240.dp),
                primary = true, icon = Icons.Filled.PlayArrow)
            if (item.links.size > 1) SportChip(pluralStringResource(R.plurals.iptv_sport5_section_other_feeds, item.links.size - 1, item.links.size - 1), onFeeds)
        } else if (fixture.status != FixtureStatus.FINAL) Text(stringResource(if (item.linking) R.string.iptv_sport3_finding_channels else R.string.iptv_sport_no_channel),
            style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
        if (sportFollowable(fixture).isNotEmpty()) SportChip(stringResource(if (item.favourite) R.string.iptv_sport5_section_following else R.string.iptv_sport5_section_follow),
            onFollow, icon = if (item.favourite) Icons.Filled.Star else Icons.Filled.StarBorder)
        if (sportCanRemind(fixture)) SportChip(stringResource(if (reminded) R.string.iptv_sport5_section_reminder_set else R.string.iptv_sport5_section_remind), onRemind,
            icon = if (reminded) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsNone)
    }
}

@Composable
private fun HeroScoreboard(fixture: SportsFixture, home: FixtureTeam, away: FixtureTeam, scores: Boolean, masked: Boolean, modifier: Modifier) {
    val awayFirst = SportsFixtureText.awayFirst(fixture)
    val sides = if (awayFirst) listOf(FixtureSide.AWAY, FixtureSide.HOME) else listOf(FixtureSide.HOME, FixtureSide.AWAY)
    val count = if (scores) maxOf(fixture.homeLine?.periods?.size ?: 0, fixture.awayLine?.periods?.size ?: 0) else 0
    val periods = (maxOf(0, count - MAX_PERIODS) until count).toList()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        if (periods.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            periods.forEach { index ->
                Text(SportsFixtureText.periodLabel(fixture.sport, index + 1) ?: "${index + 1}", style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.width(PERIOD_WIDTH))
            }
            Spacer(Modifier.width(TOTAL_WIDTH))
        }
        sides.forEach { side ->
            val team = if (side == FixtureSide.HOME) home else away
            val line = if (side == FixtureSide.HOME) fixture.homeLine else fixture.awayLine
            HeroTeamLine(team, fixture.sport, line, fixture.situation?.possession == side && scores && fixture.status == FixtureStatus.LIVE, scores, masked, periods)
        }
    }
}

@Composable
private fun HeroTeamLine(team: FixtureTeam, sport: String, line: FixtureLine?, possession: Boolean, scores: Boolean, masked: Boolean, periods: List<Int>) {
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(team, 34.dp)
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(team.shortName ?: team.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            sportRecord(sport, team.record)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary, maxLines = 1) }
            if (possession) Box(Modifier.size(6.dp).clip(CircleShape).background(NuvioTheme.colors.Secondary))
        }
        periods.forEach { index ->
            Text(line?.periods?.getOrNull(index).orEmpty(), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1,
                textAlign = TextAlign.Center, modifier = Modifier.width(PERIOD_WIDTH))
        }
        if (scores) Text(line?.score.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary,
            maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(TOTAL_WIDTH))
        else if (masked) MaskBar(TOTAL_WIDTH - 8.dp)
    }
}

@Composable
private fun HeroPreMatch(fixture: SportsFixture, home: FixtureTeam, away: FixtureTeam, modifier: Modifier) {
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(COUNTDOWN_TICK_MILLIS); value = System.currentTimeMillis() } }
    val awayFirst = SportsFixtureText.awayFirst(fixture)
    val zone = ZoneId.systemDefault()
    val left = SportsCountdown.until(now, fixture.startMillis)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PreMatchTeam(if (awayFirst) away else home, fixture.sport, false, Modifier.weight(1f))
            Column(Modifier.width(200.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                Text(clock(fixture.startMillis), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1)
                Text(sportDate(Instant.ofEpochMilli(fixture.startMillis).atZone(zone).toLocalDate(), "EEEEdMMMM"), style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when {
                    left == null -> stringResource(R.string.iptv_ui10_sport_starting)
                    left.days > 0 -> pluralStringResource(R.plurals.iptv_ui10_sport_starts_days, left.days, left.days, left.hours)
                    left.hours > 0 -> stringResource(R.string.iptv_ui10_sport_starts_hours, left.hours, left.minutes)
                    else -> stringResource(R.string.iptv_ui10_sport_starts_minutes, left.minutes)
                }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.Secondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            PreMatchTeam(if (awayFirst) home else away, fixture.sport, true, Modifier.weight(1f))
        }
        if (fixture.broadcasters.isNotEmpty()) Text(stringResource(R.string.iptv_sport_broadcaster, fixture.broadcasters.joinToString(", ")),
            style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PreMatchTeam(team: FixtureTeam, sport: String, end: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp, if (end) Alignment.End else Alignment.Start)) {
        if (!end) TeamLogo(team, PRE_MATCH_LOGO)
        Column(Modifier.weight(1f, fill = false), horizontalAlignment = if (end) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(team.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = if (end) TextAlign.End else TextAlign.Start)
            sportRecord(sport, team.record)?.let {
                Text(stringResource(R.string.iptv_ui10_sport_season_record, it), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (end) TeamLogo(team, PRE_MATCH_LOGO)
    }
}

@Composable
private fun HeroSituation(situation: FixtureSituation, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        situation.downDistance?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        situation.lastPlay?.let {
            Text(stringResource(R.string.iptv_sport2_last_play).uppercase(), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
            Text(it, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WinBar(fixture: SportsFixture, home: FixtureTeam, away: FixtureTeam, homeWin: Int) {
    val awayFirst = SportsFixtureText.awayFirst(fixture)
    val left = if (awayFirst) away else home
    val right = if (awayFirst) home else away
    val leftShare = (if (awayFirst) 100 - homeWin else homeWin).coerceIn(0, 100)
    val rightShare = 100 - leftShare
    Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.iptv_sport2_win_percent, shortTeam(left), leftShare), style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
        Row(Modifier.weight(1f).height(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.weight(leftShare.coerceAtLeast(1).toFloat()).height(4.dp).background(barColour(left, NuvioTheme.colors.Secondary), BarShape))
            Box(Modifier.weight(rightShare.coerceAtLeast(1).toFloat()).height(4.dp).background(barColour(right, NuvioTheme.colors.TextSecondary), BarShape))
        }
        Text(stringResource(R.string.iptv_sport2_win_percent, shortTeam(right), rightShare), style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
    }
}

private fun shortTeam(team: FixtureTeam): String = team.abbreviation ?: team.shortName ?: team.name

private fun barColour(team: FixtureTeam, fallback: Color): Color =
    teamColour(team)?.takeIf { it.luminance() in .04f..0.9f } ?: fallback

private val BarShape = RoundedCornerShape(2.dp)
private val PERIOD_WIDTH = 26.dp
private val TOTAL_WIDTH = 40.dp
private const val MAX_PERIODS = 6
private val PRE_MATCH_LOGO = 64.dp
private const val COUNTDOWN_TICK_MILLIS = 30_000L
