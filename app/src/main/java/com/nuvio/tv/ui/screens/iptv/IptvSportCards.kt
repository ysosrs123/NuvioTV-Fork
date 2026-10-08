@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.FixtureTeam
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureText
import com.nuvio.tv.core.iptv.SportsLeagues
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

@Composable
internal fun SportFixtureCard(item: IptvFixtureItem, showScores: Boolean, playing: Boolean, modifier: Modifier, onFocused: () -> Unit,
    onClick: () -> Unit, onHold: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val longPress = rememberLongPressKeyTracker()
    var held by remember { mutableStateOf(false) }
    val fixture = item.fixture
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
            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_MENU) { onHold(); return@onPreviewKeyEvent true }
            if (isSelect(native.keyCode)) { if (native.action == AndroidKeyEvent.ACTION_UP) { if (!held) onClick(); held = false }; true } else false
        }
        .focusable()) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp).height(18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LeagueMark(fixture, 18.dp)
            Spacer(Modifier.weight(1f))
            if (item.favourite) Icon(Icons.Filled.Star, null, Modifier.size(13.dp), tint = NuvioTheme.colors.Secondary)
            if (fixture.status != FixtureStatus.SCHEDULED) Text(clock(fixture.startMillis), style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextTertiary, maxLines = 1)
        }
        val home = fixture.home
        val away = fixture.away
        if (home != null && away != null) {
            Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CardTeam(home, stringResource(R.string.iptv_sport2_home), focused, Modifier.weight(1f))
                CardCentre(fixture, showScores, focused, Modifier.width(84.dp))
                CardTeam(away, stringResource(R.string.iptv_sport2_away), focused, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                Text(fixture.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = itemContent(focused))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (fixture.status) {
                        FixtureStatus.LIVE -> Tag(stringResource(R.string.iptv_sport_live), live = true)
                        FixtureStatus.FINAL -> Tag(stringResource(R.string.iptv_sport2_final))
                        FixtureStatus.SCHEDULED -> Text("${sportDayLabel(fixture.startMillis)} · ${clock(fixture.startMillis)}", style = MaterialTheme.typography.labelMedium,
                            color = NuvioTheme.colors.TextSecondary, maxLines = 1)
                    }
                }
            }
        }
        CardFooter(item)
    }
}

@Composable
private fun CardTeam(team: FixtureTeam, side: String, focused: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        TeamLogo(team, 40.dp)
        Text(team.shortName ?: team.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = itemContent(focused))
        Text(side, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
    }
}

@Composable
private fun CardCentre(fixture: SportsFixture, showScores: Boolean, focused: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        val scores = SportsFixtureText.scores(fixture)?.takeIf { showScores }
        when (fixture.status) {
            FixtureStatus.LIVE -> {
                Tag(stringResource(R.string.iptv_sport_live), live = true)
                scores?.let { ScoreText(it, focused) }
                SportsFixtureText.periodClock(fixture)?.takeIf { showScores }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.Error, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            FixtureStatus.FINAL -> {
                Tag(stringResource(R.string.iptv_sport2_final))
                scores?.let { ScoreText(it, focused) }
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
private fun ScoreText(scores: Pair<String, String>, focused: Boolean) {
    val text = "${scores.first} – ${scores.second}"
    Text(text, style = if (text.length > 7) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        color = itemContent(focused), maxLines = 1)
}

@Composable
private fun CardFooter(item: IptvFixtureItem) {
    val fixture = item.fixture
    val first = item.links.firstOrNull()
    Row(Modifier.fillMaxWidth().height(24.dp).background(NuvioTheme.colors.TextPrimary.copy(alpha = .05f)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (first != null) Text(channelName(first.row) + if (item.links.size > 1) " +${item.links.size - 1}" else "", style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        else if (fixture.status != FixtureStatus.FINAL) Text(stringResource(R.string.iptv_sport_no_channel), style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        val venue = fixture.venue
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
    AsyncImage(request, null, modifier.size(size), contentScale = ContentScale.Fit, onError = { failed = true })
    return true
}

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
