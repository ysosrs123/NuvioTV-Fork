@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureSide
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.MomentKind
import com.nuvio.tv.core.iptv.SportsEvents
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureText
import com.nuvio.tv.core.iptv.SportsMarkers
import com.nuvio.tv.core.iptv.SportsRefresh
import com.nuvio.tv.core.iptv.SportsSummary
import com.nuvio.tv.core.iptv.SportsTimelineItem
import com.nuvio.tv.core.iptv.SportsTimelineLane
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal const val TIMELINE_HOUR_MILLIS = 60L * 60 * 1000
private const val TIMELINE_HOURS = 14
private val LABEL_WIDTH = 92.dp
private val AXIS_HEIGHT = 18.dp
private val LANE_PITCH = 26.dp
private val BlockShape = RoundedCornerShape(6.dp)
private val RulerShape = RoundedCornerShape(12.dp)

internal fun sportTimelineWindow(now: Long): Pair<Long, Long> {
    val from = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()
    return from to from + TIMELINE_HOURS * TIMELINE_HOUR_MILLIS
}

internal object SportsStrip {
    private class Shape(val periods: Int, val clocks: (Int) -> Pair<String, String>, val end: String?)

    private val SHAPES = mapOf(
        "soccer" to Shape(2, { "${(it - 1) * 45}'" to "${it * 45}'" }, "90'"),
        "rugby" to Shape(2, { "${(it - 1) * 40}'" to "${it * 40}'" }, "80'"),
        "rugby-league" to Shape(2, { "${(it - 1) * 40}'" to "${it * 40}'" }, "80'"),
        "american-football" to Shape(4, { "15:00" to "0:00" }, null),
        "basketball" to Shape(4, { "12:00" to "0:00" }, null),
        "ice-hockey" to Shape(3, { "20:00" to "0:00" }, null),
        "australian-football" to Shape(4, { "0:00" to "30:00" }, null),
    )
    private val MOMENTS = setOf(MomentKind.GOAL, MomentKind.TRY, MomentKind.CARD_YELLOW, MomentKind.CARD_RED)
    private val TICKS = setOf("soccer", "rugby-league", "rugby")

    class Strip(val segments: List<Pair<Double, Double>>, val total: Double, val progress: Double?, val ticks: List<Pair<Double, MomentKind>>,
        val sides: List<FixtureSide?>, val end: String?)

    fun supports(sport: String): Boolean = sport in SHAPES

    fun build(fixture: SportsFixture, summary: SportsSummary?): Strip? {
        val shape = SHAPES[fixture.sport] ?: return null
        val sport = fixture.sport
        val league = fixture.league
        val segments = (1..shape.periods).map { period ->
            val (from, to) = shape.clocks(period)
            (SportsMarkers.minutes(sport, period, from, league = league) ?: return null) to (SportsMarkers.minutes(sport, period, to, league = league) ?: return null)
        }
        val total = segments.last().second.takeIf { it > 0 } ?: return null
        val period = summary?.period ?: fixture.period
        val clock = summary?.clock ?: fixture.clock
        val progress = when {
            period == null -> null
            period > shape.periods -> total
            else -> SportsMarkers.minutes(sport, period, clock, league = league)?.coerceIn(0.0, total)
        }
        val moments = if (sport in TICKS) (summary?.moments ?: SportsEvents.moments(fixture)).filter { it.kind in MOMENTS }.mapNotNull { moment ->
            SportsMarkers.minutes(sport, moment.period, moment.clock, elapsed = sport == "ice-hockey" && summary?.moments != null, league = league)?.coerceIn(0.0, total)?.let { Triple(it, moment.kind, moment.side) }
        } else emptyList()
        return Strip(segments, total, progress, moments.map { it.first to it.second }, moments.map { it.third }, shape.end ?: SportsFixtureText.periodLabel(sport, shape.periods))
    }
}

@Composable
internal fun SportHeroStrip(fixture: SportsFixture, summary: SportsSummary?, modifier: Modifier) {
    val strip = remember(fixture, summary) { SportsStrip.build(fixture, summary) } ?: return
    val track = NuvioTheme.colors.TextPrimary.copy(alpha = .16f)
    val played = NuvioTheme.colors.TextSecondary
    val now = NuvioTheme.colors.Error
    val yellow = NuvioTheme.colors.Warning
    val homeColour = teamColour(fixture.home) ?: NuvioTheme.colors.Secondary
    val awayColour = teamColour(fixture.away) ?: NuvioTheme.colors.TextPrimary
    Row(modifier.height(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.iptv_sport5_section_kick_off, clock(fixture.startMillis)), style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextTertiary, maxLines = 1)
        Canvas(Modifier.weight(1f).height(14.dp)) {
            val mid = size.height / 2
            val line = 2.dp.toPx()
            fun x(minutes: Double) = (minutes / strip.total).toFloat() * size.width
            strip.segments.forEach { (from, to) ->
                drawLine(track, Offset(x(from), mid), Offset(x(to), mid), line, StrokeCap.Round)
                strip.progress?.takeIf { it > from }?.let { drawLine(played, Offset(x(from), mid), Offset(x(minOf(it, to)), mid), line, StrokeCap.Round) }
            }
            strip.ticks.forEachIndexed { index, (minutes, kind) ->
                val cx = x(minutes)
                when (kind) {
                    MomentKind.GOAL, MomentKind.TRY -> drawCircle(if (strip.sides[index] == FixtureSide.AWAY) awayColour else homeColour, 5.dp.toPx(), Offset(cx, mid))
                    else -> drawRect(if (kind == MomentKind.CARD_RED) now else yellow, Offset(cx - 3.dp.toPx(), mid - 5.dp.toPx()), Size(6.dp.toPx(), 10.dp.toPx()))
                }
            }
            strip.progress?.let { val cx = x(it); drawLine(now, Offset(cx, 0f), Offset(cx, size.height), 2.dp.toPx()) }
        }
        strip.end?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary, maxLines = 1) }
    }
}

@Composable
internal fun IptvSportTimeline(lanes: List<SportsTimelineLane>, window: Pair<Long, Long>, now: Long, state: IptvFixturesState, playingId: String?,
    modifier: Modifier, onRail: () -> Unit, onFocused: (IptvFixtureItem) -> Unit, onOpen: (IptvFixtureItem) -> Unit, onHold: (IptvFixtureItem) -> Unit,
    onMenu: (IptvFixtureItem) -> Unit) {
    val items = remember(state.rows) { state.items.associateBy { it.fixture.key } }
    val events = remember(lanes) {
        lanes.flatMapIndexed { lane, entry -> entry.items.map { lane to it } }
            .sortedWith(compareBy({ it.second.fixture.startMillis }, { it.first }, { it.second.subLane })).map { it.second }
    }
    var chosen by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val longPress = rememberLongPressKeyTracker()
    val current = events.indexOfFirst { it.fixture.key == chosen }.takeIf { it >= 0 }
        ?: events.indexOfFirst { it.fixture.startMillis + SportsRefresh.durationMillis(it.fixture) > now }.coerceAtLeast(0)
    val selected = events.getOrNull(current)?.let { items[it.fixture.key] }
    fun move(index: Int) {
        val event = events.getOrNull(index) ?: return
        chosen = event.fixture.key
        items[event.fixture.key]?.let(onFocused)
    }
    val (from, until) = window
    val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (hour >= 15) R.string.iptv_sport5_section_tonight else R.string.iptv_sport2_today), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
            Text(stringResource(R.string.iptv_sport5_section_timeline_hint), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clip(RulerShape).background(NuvioTheme.colors.TextPrimary.copy(alpha = .04f), RulerShape)
            .then(if (focused) Modifier.border(2.dp, NuvioTheme.colors.FocusRing.copy(alpha = .5f), RulerShape) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) selected?.let(onFocused) }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (selected != null && longPress.handle(native, ::isSelect) { held = true; onHold(selected) }) {
                    if (native.action == AndroidKeyEvent.ACTION_UP) held = false
                    return@onPreviewKeyEvent true
                }
                val down = native.action == AndroidKeyEvent.ACTION_DOWN
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (down) { if (current == 0) { if (native.repeatCount == 0) onRail() } else move(current - 1) }; true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (down && current < events.size - 1) move(current + 1); true }
                    AndroidKeyEvent.KEYCODE_MENU -> { if (down) selected?.let(onMenu); true }
                    else -> if (isSelect(native.keyCode)) { if (native.action == AndroidKeyEvent.ACTION_UP) { if (!held) selected?.let(onOpen); held = false }; true } else false
                }
            }
            .focusable()) {
            val track = maxWidth - LABEL_WIDTH - 8.dp
            val viewport = LANE_PITCH * ((maxHeight - AXIS_HEIGHT) / LANE_PITCH).toInt().coerceAtLeast(1)
            val scroll = rememberScrollState()
            val density = LocalDensity.current
            val focusLane = lanes.indexOfFirst { lane -> lane.items.any { it.fixture.key == selected?.fixture?.key } }
            LaunchedEffect(focusLane, current, lanes) {
                if (focusLane < 0) return@LaunchedEffect
                val sub = lanes[focusLane].items.firstOrNull { it.fixture.key == selected?.fixture?.key }?.subLane ?: 0
                val top = with(density) { (LANE_PITCH * (lanes.take(focusLane).sumOf { it.subLanes } + sub)).roundToPx() }
                val bottom = top + with(density) { LANE_PITCH.roundToPx() }
                val height = with(density) { viewport.roundToPx() }
                if (top < scroll.value) scroll.animateScrollTo(top) else if (bottom > scroll.value + height) scroll.animateScrollTo(bottom - height)
            }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(AXIS_HEIGHT)) {
                    for (step in 0 until TIMELINE_HOURS step 2) {
                        val millis = from + step * TIMELINE_HOUR_MILLIS
                        val day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                        Text(if (day.hour == 0) "${clock(millis)} ${sportDate(day.toLocalDate(), "EEE")}" else clock(millis), style = MaterialTheme.typography.labelSmall,
                            color = NuvioTheme.colors.TextTertiary, maxLines = 1,
                            modifier = Modifier.offset(x = LABEL_WIDTH + track * (step.toFloat() / TIMELINE_HOURS) + 3.dp, y = 3.dp))
                    }
                }
                Column(Modifier.fillMaxWidth().height(viewport).verticalScroll(scroll, enabled = false)) {
                    lanes.forEach { lane ->
                        Box(Modifier.fillMaxWidth().height(LANE_PITCH * lane.subLanes)) {
                            Text(sportName(lane.sport), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, top = 6.dp).width(LABEL_WIDTH - 16.dp))
                            lane.items.forEach { event ->
                                val item = items[event.fixture.key]
                                TimelineBlock(event, item, state, item != null && item.fixture.key == selected?.fixture?.key, focused,
                                    item?.links?.any { it.row.item.channel.id == playingId } == true,
                                    Modifier.offset(x = LABEL_WIDTH + track * event.startFraction, y = LANE_PITCH * event.subLane + 2.dp)
                                        .width((track * (event.endFraction - event.startFraction)).coerceAtLeast(28.dp)).height(LANE_PITCH - 4.dp))
                            }
                        }
                    }
                }
            }
            val fraction = ((now - from).toDouble() / (until - from)).toFloat()
            if (fraction in 0f..1f) Box(Modifier.offset(x = LABEL_WIDTH + track * fraction).padding(top = 4.dp, bottom = 4.dp).width(2.dp).fillMaxHeight()
                .background(NuvioTheme.colors.Error))
        }
    }
}

@Composable
private fun TimelineBlock(event: SportsTimelineItem, item: IptvFixtureItem?, state: IptvFixturesState, selected: Boolean, focused: Boolean, playing: Boolean,
    modifier: Modifier) {
    val fixture = event.fixture
    val hidden = state.hidden(fixture)
    val home = fixture.home
    val away = fixture.away
    val plain = if (home != null && away != null) {
        if (SportsFixtureText.awayFirst(fixture)) "${SportsFixtureText.code(away)} @ ${SportsFixtureText.code(home)}"
        else "${SportsFixtureText.code(home)} v ${SportsFixtureText.code(away)}"
    } else fixture.title
    val main = if (hidden || fixture.status == FixtureStatus.SCHEDULED || item?.scheduleOnly == true) plain else SportsFixtureText.bug(fixture).primary
    val channel = item?.links?.firstOrNull()?.let { channelName(it.row) }
    val missing = item != null && item.links.isEmpty() && !item.linking && fixture.status != FixtureStatus.FINAL
    val text = listOfNotNull(main, clock(fixture.startMillis).takeIf { fixture.status == FixtureStatus.SCHEDULED },
        channel ?: if (missing) stringResource(R.string.iptv_sport_no_channel) else null).joinToString(" · ")
    val fill = when {
        missing -> Color.Transparent
        fixture.status == FixtureStatus.LIVE -> NuvioTheme.colors.Error.copy(alpha = .28f)
        fixture.status == FixtureStatus.FINAL -> NuvioTheme.colors.TextPrimary.copy(alpha = .06f)
        else -> NuvioTheme.colors.TextPrimary.copy(alpha = .12f)
    }
    val dash = NuvioTheme.colors.TextTertiary
    val active = selected && focused
    Box(modifier.clip(BlockShape).background(fill, BlockShape)
        .then(if (missing) Modifier.drawBehind {
            drawRoundRect(dash, cornerRadius = CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        } else Modifier)
        .iptvItem(active, playing, BlockShape)
        .then(if (selected && !focused) Modifier.border(1.dp, NuvioTheme.colors.TextSecondary, BlockShape) else Modifier)
        .padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = if (fixture.status == FixtureStatus.LIVE) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                active -> itemContent(true)
                missing || fixture.status == FixtureStatus.FINAL -> NuvioTheme.colors.TextSecondary
                else -> NuvioTheme.colors.TextPrimary
            }, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun sportName(sport: String): String = when (sport) {
    "soccer" -> stringResource(R.string.iptv_sport5_section_sport_football)
    "australian-football" -> stringResource(R.string.iptv_sport5_section_sport_afl)
    "rugby-league" -> stringResource(R.string.iptv_sport5_section_sport_rugby_league)
    "rugby" -> stringResource(R.string.iptv_sport5_section_sport_rugby)
    "american-football" -> stringResource(R.string.iptv_sport5_section_sport_american_football)
    "basketball" -> stringResource(R.string.iptv_sport5_section_sport_basketball)
    "baseball" -> stringResource(R.string.iptv_sport5_section_sport_baseball)
    "ice-hockey" -> stringResource(R.string.iptv_sport5_section_sport_ice_hockey)
    "motorsport" -> stringResource(R.string.iptv_sport5_section_sport_motor)
    "mma" -> stringResource(R.string.iptv_sport5_section_sport_fighting)
    "tennis" -> stringResource(R.string.iptv_sport5_section_sport_tennis)
    "golf" -> stringResource(R.string.iptv_sport5_section_sport_golf)
    "cricket" -> stringResource(R.string.iptv_sport5_section_sport_cricket)
    else -> sport.replace('-', ' ').replaceFirstChar { it.uppercase() }
}
