@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FightBout
import com.nuvio.tv.core.iptv.Fighter
import com.nuvio.tv.core.iptv.FixtureSide
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.FixtureTeam
import com.nuvio.tv.core.iptv.GolfPlayer
import com.nuvio.tv.core.iptv.RecordShape
import com.nuvio.tv.core.iptv.InningHalf
import com.nuvio.tv.core.iptv.SportsDetail
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureText
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsRecords
import com.nuvio.tv.core.iptv.TennisPlayer
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

internal val SportCardShape = RoundedCornerShape(16.dp)
internal val SPORT_CARD_WIDTH = 236.dp
internal val SPORT_CARD_HEIGHT = 140.dp
private const val MAX_SETS = 5
private const val GOLF_ROWS = 5
private const val GOLF_BOARD_ROWS = 5
internal val GOLF_COUNTRY_WIDTH = 28.dp
internal val GOLF_SCORE_WIDTH = 34.dp
private const val SESSION_ROWS = 4

@Composable
internal fun SportFixtureCard(item: IptvFixtureItem, hidden: Boolean, spoiler: Boolean, favourites: Set<String>, reminded: Boolean, playing: Boolean,
    modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit, onHold: () -> Unit, onMenu: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val longPress = rememberLongPressKeyTracker()
    var held by remember { mutableStateOf(false) }
    val fixture = item.fixture
    val detail = fixture.sportDetail.takeIf { !item.scheduleOnly }
    val home = fixture.home
    val away = fixture.away
    val shaped = when (detail) {
        is SportsDetail.Tennis -> home != null && away != null
        is SportsDetail.Golf, is SportsDetail.Sessions, is SportsDetail.Card -> true
        is SportsDetail.Cricket -> home != null && away != null && !hidden && fixture.status != FixtureStatus.SCHEDULED && detail.innings.isNotEmpty()
        is SportsDetail.Baseball -> home != null && away != null && !hidden && fixture.status == FixtureStatus.LIVE
        null -> false
    }
    Column(modifier.width(SPORT_CARD_WIDTH).height(SPORT_CARD_HEIGHT)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .iptvItem(focused, playing, SportCardShape)
        .background(NuvioTheme.colors.TextPrimary.copy(alpha = .05f), SportCardShape)
        .border(1.dp, NuvioTheme.colors.TextPrimary.copy(alpha = if (focused) 0f else .08f), SportCardShape)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (longPress.handle(native, ::isSelect) { held = true; onHold() }) {
                if (native.action == AndroidKeyEvent.ACTION_UP) held = false
                return@onPreviewKeyEvent true
            }
            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_MENU) { onMenu(); return@onPreviewKeyEvent true }
            if (isSelect(native.keyCode)) { if (native.action == AndroidKeyEvent.ACTION_UP) { if (!held) onClick(); held = false }; true } else false
        }
        .focusable()) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp).height(18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LeagueMark(fixture, 18.dp)
            if (detail is SportsDetail.Golf) Text(detail.tournament, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            if (item.favourite) Icon(Icons.Filled.Star, null, Modifier.size(13.dp), tint = NuvioTheme.colors.Secondary)
            if (reminded) Icon(Icons.Filled.NotificationsActive, null, Modifier.size(13.dp), tint = NuvioTheme.colors.Secondary)
            if (shaped) CardState(fixture, detail, hidden)
            else if (fixture.status != FixtureStatus.SCHEDULED) Text(clock(fixture.startMillis), style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextTertiary, maxLines = 1)
        }
        val middle = Modifier.fillMaxWidth().weight(1f)
        when {
            shaped && detail is SportsDetail.Tennis && home != null && away != null -> TennisMiddle(fixture, detail, home, away, hidden, focused, middle)
            shaped && detail is SportsDetail.Golf -> GolfMiddle(fixture, detail, hidden, favourites, focused, middle)
            shaped && detail is SportsDetail.Sessions -> SessionsMiddle(fixture, detail, focused, middle)
            shaped && detail is SportsDetail.Card -> FightMiddle(fixture, detail, hidden, focused, middle)
            shaped && detail is SportsDetail.Cricket && home != null && away != null -> CricketMiddle(fixture, detail, home, away, focused, middle)
            shaped && detail is SportsDetail.Baseball && home != null && away != null -> BaseballMiddle(fixture, detail, home, away, focused, middle)
            home != null && away != null -> Row(middle.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CardTeam(home, fixture.sport, stringResource(R.string.iptv_sport2_home), focused, Modifier.weight(1f))
                CardCentre(fixture, hidden, spoiler, item.scheduleOnly, focused, Modifier.width(84.dp))
                CardTeam(away, fixture.sport, stringResource(R.string.iptv_sport2_away), focused, Modifier.weight(1f))
            }
            else -> Column(middle.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                Text(fixture.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = itemContent(focused))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (fixture.status) {
                        FixtureStatus.LIVE -> Tag(stringResource(R.string.iptv_sport_live), live = true)
                        FixtureStatus.FINAL -> Tag(stringResource(R.string.iptv_sport2_final))
                        FixtureStatus.SCHEDULED -> Text("${sportDayLabel(fixture.startMillis)} · ${clock(fixture.startMillis)}", style = MaterialTheme.typography.labelMedium,
                            color = NuvioTheme.colors.TextSecondary, maxLines = 1)
                    }
                    if (item.scheduleOnly && fixture.status == FixtureStatus.LIVE) Text(stringResource(R.string.iptv_sport5_section_no_live_score),
                        style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
                }
            }
        }
        CardFooter(item)
    }
}

@Composable
internal fun SportDetailBody(fixture: SportsFixture, hidden: Boolean, favourites: Set<String>, modifier: Modifier) {
    when (val detail = fixture.sportDetail) {
        is SportsDetail.Golf -> GolfMiddle(fixture, detail, hidden, favourites, false, modifier)
        is SportsDetail.Sessions -> SessionsMiddle(fixture, detail, false, modifier)
        is SportsDetail.Card -> FightMiddle(fixture, detail, hidden, false, modifier)
        else -> Unit
    }
}

@Composable
private fun CardState(fixture: SportsFixture, detail: SportsDetail?, hidden: Boolean) {
    val text = when (fixture.status) {
        FixtureStatus.LIVE -> listOfNotNull(stringResource(R.string.iptv_sport_live), when (detail) {
            is SportsDetail.Tennis -> fixture.period?.let { stringResource(R.string.iptv_sport5_section_set, it) }
            is SportsDetail.Golf -> detail.round?.let { stringResource(R.string.iptv_sport5_section_golf_round, it) }
            is SportsDetail.Baseball -> if (hidden) null else sportInning(detail)
            else -> null
        }).joinToString(" · ")
        FixtureStatus.FINAL -> stringResource(R.string.iptv_sport2_final)
        FixtureStatus.SCHEDULED -> "${sportDayLabel(fixture.startMillis)} · ${clock(fixture.startMillis)}"
    }
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = if (fixture.status == FixtureStatus.LIVE) FontWeight.Bold else FontWeight.Normal,
        color = if (fixture.status == FixtureStatus.LIVE) NuvioTheme.colors.Error else NuvioTheme.colors.TextTertiary, maxLines = 1)
}

@Composable
internal fun sportInning(detail: SportsDetail.Baseball): String? {
    val inning = detail.inning ?: return null
    return when (detail.half) {
        InningHalf.TOP -> "▲$inning"
        InningHalf.BOTTOM -> "▼$inning"
        InningHalf.MIDDLE -> stringResource(R.string.iptv_sport5_section_inning_mid, inning)
        InningHalf.END -> stringResource(R.string.iptv_sport5_section_inning_end, inning)
        null -> "$inning"
    }
}

@Composable
private fun CardTeam(team: FixtureTeam, sport: String, side: String, focused: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        TeamLogo(team, 40.dp)
        Text(team.shortName ?: team.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = itemContent(focused))
        Text(sportRecord(sport, team.record) ?: side, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CardCentre(fixture: SportsFixture, hidden: Boolean, spoiler: Boolean, scheduleOnly: Boolean, focused: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        val scores = SportsFixtureText.scores(fixture)
        when (fixture.status) {
            FixtureStatus.LIVE -> {
                Tag(stringResource(R.string.iptv_sport_live), live = true)
                when {
                    scheduleOnly -> Text(stringResource(R.string.iptv_sport5_section_no_live_score), style = MaterialTheme.typography.labelSmall,
                        color = NuvioTheme.colors.TextTertiary, maxLines = 2, textAlign = TextAlign.Center)
                    hidden -> if (scores != null) MaskBar(46.dp)
                    else -> {
                        scores?.let { ScoreText(it, focused) }
                        SportsFixtureText.periodClock(fixture)?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.Error, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            FixtureStatus.FINAL -> {
                Tag(stringResource(R.string.iptv_sport2_final))
                if (hidden) {
                    MaskBar(46.dp)
                    if (spoiler) Text(stringResource(R.string.iptv_sport5_section_hold_to_show), style = MaterialTheme.typography.labelSmall,
                        color = NuvioTheme.colors.TextSecondary, maxLines = 2, textAlign = TextAlign.Center)
                } else scores?.let { ScoreText(it, focused) }
            }
            FixtureStatus.SCHEDULED -> {
                Text(clock(fixture.startMillis), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = itemContent(focused),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sportDayLabel(fixture.startMillis), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun MaskBar(width: Dp, modifier: Modifier = Modifier) {
    Box(modifier.width(width).height(14.dp).clip(SportPlaceholderShape).background(NuvioTheme.colors.TextPrimary.copy(alpha = .16f)))
}

@Composable
private fun ScoreText(scores: Pair<String, String>, focused: Boolean) {
    val text = "${scores.first} – ${scores.second}"
    Text(text, style = if (text.length > 7) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        color = itemContent(focused), maxLines = 1)
}

@Composable
private fun TennisMiddle(fixture: SportsFixture, detail: SportsDetail.Tennis, home: FixtureTeam, away: FixtureTeam, hidden: Boolean, focused: Boolean,
    modifier: Modifier) {
    val live = fixture.status == FixtureStatus.LIVE
    Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        TennisLine(home, detail.homePlayer, FixtureSide.HOME, detail, live, hidden, focused)
        TennisLine(away, detail.awayPlayer, FixtureSide.AWAY, detail, live, hidden, focused)
        val server = detail.server?.takeIf { live && !hidden }?.let { if (it == FixtureSide.HOME) home else away }
        val note = listOfNotNull(server?.let { stringResource(R.string.iptv_sport5_section_serving, it.shortName ?: it.name.substringAfterLast(' ')) },
            detail.round, detail.court).joinToString(" · ")
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TennisLine(team: FixtureTeam, player: TennisPlayer, side: FixtureSide, detail: SportsDetail.Tennis, live: Boolean, hidden: Boolean, focused: Boolean) {
    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (live && !hidden && detail.server == side) NuvioTheme.colors.Warning else Color.Transparent))
        Text(team.shortName ?: team.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = itemContent(focused), maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        player.seed?.let { Text("($it)", style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1) }
        Spacer(Modifier.weight(1f))
        if (hidden && detail.sets.isNotEmpty()) MaskBar(40.dp)
        else detail.sets.takeLast(MAX_SETS).forEach { set ->
            val games = if (side == FixtureSide.HOME) set.home else set.away
            val current = live && set.winner == null
            Text(games?.toString() ?: "", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1,
                fontWeight = if (set.winner == side || current) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    current -> NuvioTheme.colors.Warning
                    set.winner == side -> itemContent(focused)
                    else -> NuvioTheme.colors.TextSecondary
                }, modifier = Modifier.width(16.dp))
        }
    }
}

@Composable
private fun GolfMiddle(fixture: SportsFixture, detail: SportsDetail.Golf, hidden: Boolean, favourites: Set<String>, focused: Boolean, modifier: Modifier) {
    val followed = detail.leaders.filter { "${fixture.league}:${it.name.trim()}" in favourites }.toSet()
    Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically)) {
        if (hidden || fixture.status == FixtureStatus.SCHEDULED || detail.leaders.isEmpty()) {
            Text(detail.tournament, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = itemContent(focused))
            if (hidden && detail.leaders.isNotEmpty()) MaskBar(96.dp)
        } else {
            val top = detail.leaders.take(GOLF_ROWS)
            val extra = followed.firstOrNull { it !in top }
            (if (extra != null) top.take(GOLF_ROWS - 1) + extra else top).forEach { player ->
                val mine = player in followed
                val colour = if (mine) NuvioTheme.colors.Secondary else itemContent(focused)
                Row(Modifier.fillMaxWidth().height(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(player.position, style = MaterialTheme.typography.labelSmall, color = if (mine) colour else NuvioTheme.colors.TextTertiary, maxLines = 1,
                        modifier = Modifier.width(24.dp))
                    Text(player.shortName ?: player.name, style = MaterialTheme.typography.labelMedium, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    Text(golfCountry(player).orEmpty(), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1,
                        modifier = Modifier.width(GOLF_COUNTRY_WIDTH))
                    Text(player.toPar.orEmpty(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colour, maxLines = 1,
                        textAlign = TextAlign.End, modifier = Modifier.width(GOLF_SCORE_WIDTH))
                }
            }
        }
        val note = listOfNotNull(detail.statusText, detail.purse).joinToString(" · ")
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SessionsMiddle(fixture: SportsFixture, detail: SportsDetail.Sessions, focused: Boolean, modifier: Modifier) {
    val all = detail.sessions
    val from = (all.indexOfFirst { it.state != FixtureStatus.FINAL }.takeIf { it >= 0 } ?: all.size).coerceAtMost(all.size - SESSION_ROWS).coerceAtLeast(0)
    val current = detail.current
    Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        Text(fixture.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = itemContent(focused), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        all.drop(from).take(SESSION_ROWS).forEach { session ->
            val now = session == current && session.state != FixtureStatus.FINAL
            Row(Modifier.fillMaxWidth().height(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(34.dp).clip(SportPlaceholderShape).background(if (now) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextPrimary.copy(alpha = .12f)),
                    contentAlignment = Alignment.Center) {
                    Text(session.abbreviation ?: "·", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1,
                        color = if (now) NuvioTheme.colors.Background else NuvioTheme.colors.TextSecondary)
                }
                Text(session.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (session.state == FixtureStatus.FINAL) NuvioTheme.colors.TextSecondary else itemContent(focused))
                Text(when (session.state) {
                    FixtureStatus.FINAL -> stringResource(R.string.iptv_sport5_section_done)
                    FixtureStatus.LIVE -> stringResource(R.string.iptv_sport_live)
                    FixtureStatus.SCHEDULED -> sportWhen(session.startMillis)
                }, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    color = if (session.state == FixtureStatus.LIVE) NuvioTheme.colors.Error else NuvioTheme.colors.TextTertiary)
            }
        }
    }
}

@Composable
private fun FightMiddle(fixture: SportsFixture, detail: SportsDetail.Card, hidden: Boolean, focused: Boolean, modifier: Modifier) {
    val main = detail.mainEvent
    val live = detail.live?.takeIf { !hidden }
    Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        Text(main?.let { "${fighterName(it.first)} v ${fighterName(it.second)}" } ?: fixture.title, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, color = itemContent(focused), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (live != null) Text(stringResource(R.string.iptv_sport5_section_now_bout, "${fighterName(live.first)} v ${fighterName(live.second)}") +
            (live.round?.let { " · " + stringResource(R.string.iptv_sport5_section_fight_round, it) } ?: ""),
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.Error, maxLines = 1, overflow = TextOverflow.Ellipsis)
        else main?.let { bout ->
            Text(listOfNotNull(bout.weightClass, bout.rounds?.let { pluralStringResource(R.plurals.iptv_sport5_section_rounds, it, it) }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.fillMaxWidth().clip(SportPlaceholderShape).background(NuvioTheme.colors.TextPrimary.copy(alpha = .06f)).padding(horizontal = 8.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)) {
            CardSegment(stringResource(R.string.iptv_sport5_section_main_card), detail.mainCard, true)
            CardSegment(stringResource(R.string.iptv_sport5_section_prelims), detail.prelims, false)
        }
    }
}

@Composable
private fun CardSegment(name: String, bouts: List<FightBout>, main: Boolean) {
    val start = bouts.minOfOrNull { it.startMillis } ?: return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, style = MaterialTheme.typography.labelSmall, fontWeight = if (main) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
            color = if (main) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary, modifier = Modifier.weight(1f))
        Text(pluralStringResource(R.plurals.iptv_sport5_section_bouts, bouts.size, sportWhen(start), bouts.size), style = MaterialTheme.typography.labelSmall,
            maxLines = 1, color = if (main) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary)
    }
}

internal fun fighterName(fighter: Fighter): String = fighter.shortName ?: fighter.name.substringAfterLast(' ')

@Composable
private fun CricketMiddle(fixture: SportsFixture, detail: SportsDetail.Cricket, home: FixtureTeam, away: FixtureTeam, focused: Boolean, modifier: Modifier) {
    val batting = detail.innings.lastOrNull { it.batting } ?: detail.innings.last()
    val chase = detail.chase?.takeIf { fixture.status == FixtureStatus.LIVE }
    Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        listOf(home, away).forEach { team ->
            val innings = detail.innings.lastOrNull { it.team == (team.abbreviation ?: team.shortName ?: team.name) }
            CompactTeamLine(team, innings?.let { "${it.runs}" + if (it.wickets < 10) "/${it.wickets}" else "" }, focused, innings?.batting == true)
        }
        val chasing = chase?.let { stringResource(R.string.iptv_sport5_section_chase, batting.team, it.runs, it.balls, String.format(Locale.getDefault(), "%.2f", it.rate)) }
        val overs = listOfNotNull(batting.overs?.let { o -> batting.maxOvers?.let { stringResource(R.string.iptv_sport5_section_overs_of, o, it) }
            ?: stringResource(R.string.iptv_sport5_section_overs, o) }, batting.target?.let { stringResource(R.string.iptv_sport5_section_target, it) }).joinToString(" · ")
        (chasing ?: overs.takeIf(String::isNotEmpty))?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = if (chasing != null) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BaseballMiddle(fixture: SportsFixture, detail: SportsDetail.Baseball, home: FixtureTeam, away: FixtureTeam, focused: Boolean, modifier: Modifier) {
    val scores = SportsFixtureText.scores(fixture)
    Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        CompactTeamLine(away, scores?.second, focused, detail.half == InningHalf.TOP)
        CompactTeamLine(home, scores?.first, focused, detail.half == InningHalf.BOTTOM)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BasesDiamond(detail.first, detail.second, detail.third, 26.dp)
            val outs = detail.outs?.let { pluralStringResource(R.plurals.iptv_sport5_section_outs, it, it) }
            val count = if (detail.balls != null && detail.strikes != null) "${detail.balls}–${detail.strikes}" else null
            Text(listOfNotNull(outs, count).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
        }
    }
}

@Composable
internal fun BasesDiamond(first: Boolean, second: Boolean, third: Boolean, size: Dp) {
    val on = NuvioTheme.colors.Warning
    val off = NuvioTheme.colors.TextTertiary
    Canvas(Modifier.size(size)) {
        val base = this.size.width * .3f
        val stroke = 1.5.dp.toPx()
        fun diamond(cx: Float, cy: Float, filled: Boolean) {
            val path = Path().apply { moveTo(cx, cy - base / 2); lineTo(cx + base / 2, cy); lineTo(cx, cy + base / 2); lineTo(cx - base / 2, cy); close() }
            if (filled) drawPath(path, on) else drawPath(path, off, style = Stroke(stroke))
        }
        val w = this.size.width
        val h = this.size.height
        diamond(w / 2, h * .25f, second)
        diamond(w * .2f, h * .6f, third)
        diamond(w * .8f, h * .6f, first)
    }
}

@Composable
private fun CompactTeamLine(team: FixtureTeam, score: String?, focused: Boolean, active: Boolean) {
    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TeamLogo(team, 20.dp)
        Text(team.shortName ?: team.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = itemContent(focused), maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (active) Box(Modifier.size(5.dp).clip(CircleShape).background(NuvioTheme.colors.Warning))
        score?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = itemContent(focused), maxLines = 1) }
    }
}

@Composable
internal fun sportWhen(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() == LocalDate.now(zone)
    return if (today) clock(millis) else "${sportDayLabel(millis)} ${clock(millis)}"
}

@Composable
internal fun SportChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, icon: ImageVector? = null, height: Dp = 28.dp) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(height / 2)
    Row(modifier.height(height).onFocusChanged { focused = it.isFocused }.iptvItem(focused, shape = shape)
        .background(when {
            focused -> Color.Transparent
            primary -> NuvioTheme.colors.Secondary.copy(alpha = .24f)
            else -> NuvioTheme.colors.TextPrimary.copy(alpha = .08f)
        }, shape)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (isSelect(native.keyCode)) { if (native.action == AndroidKeyEvent.ACTION_UP) onClick(); true } else false
        }
        .focusable().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = itemContent(focused))
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = itemContent(focused), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CardFooter(item: IptvFixtureItem) {
    val fixture = item.fixture
    val first = item.links.firstOrNull()
    Row(Modifier.fillMaxWidth().height(24.dp).background(NuvioTheme.colors.TextPrimary.copy(alpha = .05f)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (first != null) Text(channelName(first.row) + if (item.links.size > 1) " +${item.links.size - 1}" else "", style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        else if (fixture.status != FixtureStatus.FINAL) Text(stringResource(if (item.linking) R.string.iptv_sport3_finding_channels else R.string.iptv_sport_no_channel),
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        val venue = fixture.venue ?: (fixture.sportDetail as? SportsDetail.Sessions)?.venue
        if (venue != null) Text(venue, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun TeamLogo(team: FixtureTeam, size: Dp, modifier: Modifier = Modifier) {
    var failed by remember(team.logo) { mutableStateOf(false) }
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val request = remember(team.logo, pixels) { team.logo?.let { ImageRequest.Builder(context).data(it).size(pixels, pixels).build() } }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (request != null && !failed) {
            AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onError = { failed = true })
        } else {
            val colour = teamColour(team) ?: NuvioTheme.colors.TextPrimary.copy(alpha = .14f)
            Box(Modifier.fillMaxSize().clip(CircleShape).background(colour), contentAlignment = Alignment.Center) {
                Text(team.abbreviation?.take(3) ?: monogram(team.name), maxLines = 1, fontWeight = FontWeight.Bold,
                    style = if (size >= 36.dp) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
                    color = if (colour.alpha > .5f && colour.luminance() > .6f) Color.Black else Color.White)
            }
        }
    }
}

@Composable
internal fun LeagueLogo(url: String, size: Dp, modifier: Modifier = Modifier): Boolean {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return false
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val request = remember(url, pixels) { ImageRequest.Builder(context).data(url).size(pixels, pixels).build() }
    val backed = !url.contains("-dark")
    Box(modifier.size(size).then(if (backed) Modifier.clip(LeagueBackingShape).background(Color.White.copy(alpha = .88f), LeagueBackingShape).padding(size / 10)
        else Modifier), contentAlignment = Alignment.Center) {
        AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onError = { failed = true })
    }
    return true
}

private val LeagueBackingShape = RoundedCornerShape(4.dp)

internal fun golfCountry(player: GolfPlayer): String? = player.country?.takeIf { it.length <= 3 }

@Composable
internal fun sportRecord(sport: String, record: String?): String? {
    record ?: return null
    val parsed = SportsRecords.parse(sport, record) ?: return record
    val v = parsed.values
    return when (parsed.shape) {
        RecordShape.WIN_DRAW_LOSS -> stringResource(R.string.iptv_ui10_sport_record_wdl, v[0], v[1], v[2])
        RecordShape.WIN_LOSS -> stringResource(R.string.iptv_ui10_sport_record_wl, v[0], v[1])
        RecordShape.WIN_LOSS_TIE -> stringResource(R.string.iptv_ui10_sport_record_wlt, v[0], v[1], v[2])
        RecordShape.WIN_LOSS_OVERTIME -> stringResource(R.string.iptv_ui10_sport_record_wlo, v[0], v[1], v[2])
    }
}

@Composable
internal fun GolfBoard(fixture: SportsFixture, detail: SportsDetail.Golf, favourites: Set<String>, modifier: Modifier) {
    val followed = detail.leaders.filter { "${fixture.league}:${it.name.trim()}" in favourites }.toSet()
    val columns = detail.leaders.chunked(GOLF_BOARD_ROWS).take(2)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            columns.forEach { players ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.weight(1f))
                        GolfHeading(stringResource(R.string.iptv_sport2_today), GOLF_BOARD_NUMBER)
                        GolfHeading(stringResource(R.string.iptv_ui10_sport_golf_thru), GOLF_BOARD_NUMBER)
                        GolfHeading(stringResource(R.string.iptv_ui10_sport_golf_to_par), GOLF_BOARD_SCORE)
                    }
                    players.forEach { player ->
                        val colour = if (player in followed) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextPrimary
                        Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(player.position, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary, maxLines = 1,
                                modifier = Modifier.width(28.dp))
                            Text(player.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colour, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(golfCountry(player).orEmpty(), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1,
                                modifier = Modifier.width(GOLF_COUNTRY_WIDTH))
                            Text(player.today.orEmpty(), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1,
                                textAlign = TextAlign.End, modifier = Modifier.width(GOLF_BOARD_NUMBER))
                            Text(player.thru.orEmpty(), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1,
                                textAlign = TextAlign.End, modifier = Modifier.width(GOLF_BOARD_NUMBER))
                            Text(player.toPar.orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colour, maxLines = 1,
                                textAlign = TextAlign.End, modifier = Modifier.width(GOLF_BOARD_SCORE))
                        }
                    }
                }
            }
        }
        val note = listOfNotNull(detail.statusText, detail.purse).joinToString(" · ")
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GolfHeading(text: String, width: Dp) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End, modifier = Modifier.width(width))
}

private val GOLF_BOARD_NUMBER = 44.dp
private val GOLF_BOARD_SCORE = 52.dp

@Composable
private fun LeagueMark(fixture: SportsFixture, size: Dp) {
    val shown = fixture.leagueLogo?.let { LeagueLogo(it, size) } ?: false
    if (!shown) Text(sportLeagueName(fixture), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1,
        overflow = TextOverflow.Ellipsis, color = NuvioTheme.colors.TextTertiary)
}

internal fun sportLeagueName(fixture: SportsFixture): String = SportsLeagues.byId(fixture.league)?.name ?: fixture.league

internal fun teamColour(team: FixtureTeam?): Color? = team?.colour?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

@Composable
internal fun sportTitle(fixture: SportsFixture): String {
    val home = fixture.home ?: return fixture.title
    val away = fixture.away ?: return fixture.title
    return if (SportsFixtureText.awayFirst(fixture)) stringResource(R.string.iptv_sport2_at, away.name, home.name)
    else stringResource(R.string.iptv_sport2_versus, home.name, away.name)
}

@Composable
internal fun sportDayLabel(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (day) {
        today -> stringResource(R.string.iptv_sport2_today)
        today.plusDays(1) -> stringResource(R.string.iptv_sport_tomorrow)
        else -> sportDate(day, "EEEdMMM")
    }
}

internal fun sportDate(day: LocalDate, skeleton: String): String {
    val locale = Locale.getDefault()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    return SimpleDateFormat(pattern, locale).format(Date(day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()))
}
