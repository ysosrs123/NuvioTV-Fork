@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureSide
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.FixtureTeam
import com.nuvio.tv.core.iptv.SportsAlertGames
import com.nuvio.tv.core.iptv.SportsChange
import com.nuvio.tv.core.iptv.SportsChangeKind
import com.nuvio.tv.core.iptv.SportsDetail
import com.nuvio.tv.core.iptv.SportsEvents
import com.nuvio.tv.core.iptv.SportsFavourites
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureSections
import com.nuvio.tv.core.iptv.SportsFixtureText
import com.nuvio.tv.core.iptv.SportsHeadline
import com.nuvio.tv.core.iptv.SportsOverlaySelection
import com.nuvio.tv.core.iptv.SportsOverlayStyle
import com.nuvio.tv.core.iptv.SportsOverlayText
import com.nuvio.tv.core.iptv.SportsReminder
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.data.iptv.IptvSportsPreferences
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay

@EntryPoint
@InstallIn(SingletonComponent::class)
interface IptvSportsOverlayEntryPoint {
    fun sportsPreferences(): IptvSportsPreferences
}

private data class OverlaySettings(val style: SportsOverlayStyle, val games: SportsAlertGames, val skip: Boolean)

private fun overlaySettings(preferences: IptvSportsPreferences) = OverlaySettings(preferences.overlayStyle, preferences.alertGames, preferences.skipOnScreen)

@Composable
internal fun IptvSportsOverlayLayer(playingId: String?, controlsVisible: Boolean, onWatch: (IptvListedChannel) -> Unit, modifier: Modifier = Modifier) {
    val viewModel: IptvSportsFixturesViewModel = hiltViewModel()
    val context = LocalContext.current
    val preferences = remember { EntryPointAccessors.fromApplication(context.applicationContext, IptvSportsOverlayEntryPoint::class.java).sportsPreferences() }
    var settings by remember { mutableStateOf(overlaySettings(preferences)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(preferences) { while (true) { delay(REFRESH_MILLIS); settings = overlaySettings(preferences); now = System.currentTimeMillis() } }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items = remember(state.rows) { state.rows.flatMap { it.items }.distinctBy { it.fixture.key } }
    val onScreen = remember(items, playingId) {
        if (playingId == null) emptySet() else items.filter { item -> item.links.any { it.row.item.channel.id == playingId } }.map { it.fixture.key }.toSet()
    }
    LaunchedEffect(onScreen) { viewModel.setOnScreen(onScreen) }
    DisposableEffect(viewModel) { onDispose { viewModel.setOnScreen(emptySet()) } }
    val nuvio = rememberIptvSportsNuvio()
    DisposableEffect(nuvio) { val release = nuvio.showReminders(); onDispose { release() } }
    val alerts = remember { mutableStateListOf<SportsChange>() }
    val reminders = remember { mutableStateListOf<SportsReminder>() }
    val latestSettings by rememberUpdatedState(settings)
    val latestState by rememberUpdatedState(state)
    val latestOnScreen by rememberUpdatedState(onScreen)
    LaunchedEffect(viewModel) {
        viewModel.alerts.collect { change ->
            val shown = latestState
            if (latestSettings.style != SportsOverlayStyle.GLANCE || !shown.enabled || change.key in latestOnScreen) return@collect
            if (change.kind != SportsChangeKind.STARTED && (!shown.showScores || change.key in shown.spoilerKeys)) return@collect
            if (alerts.firstOrNull()?.key == change.key) alerts[0] = change
            else { alerts.removeAll { it.key == change.key }; alerts += change }
            while (alerts.size > MAX_QUEUE) alerts.removeAt(1)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.reminderDue.collect { reminder ->
            reminders.removeAll { it.key == reminder.key }
            reminders += reminder
            while (reminders.size > MAX_QUEUE) reminders.removeAt(1)
        }
    }
    LaunchedEffect(settings.style) { if (settings.style != SportsOverlayStyle.GLANCE) alerts.clear() }
    val fixtures = remember(items) { items.map { it.fixture } }
    val count = when (settings.style) {
        SportsOverlayStyle.BUG -> 1
        SportsOverlayStyle.CARDS -> MAX_CARDS
        SportsOverlayStyle.TICKER -> MAX_TICKER
        else -> 0
    }
    val picks = remember(fixtures, state.favourites, onScreen, settings, now, count) {
        if (count == 0 || !state.enabled) emptyList()
        else SportsOverlaySelection.pick(fixtures, state.favourites, onScreen, settings.games, settings.skip, now, count)
    }
    fun hidden(fixture: SportsFixture) = !state.showScores || fixture.key in state.spoilerKeys
    fun link(key: String): IptvListedChannel? {
        val links = items.firstOrNull { it.fixture.key == key }?.links.orEmpty()
        return (links.firstOrNull { it.row.item.channel.id != playingId } ?: links.firstOrNull())?.row
    }
    val focusManager = LocalFocusManager.current
    var cardFocused by remember { mutableStateOf(false) }
    fun release() { if (cardFocused) { cardFocused = false; if (!focusManager.moveFocus(FocusDirection.Exit)) focusManager.clearFocus() } }
    val reminder = reminders.firstOrNull()
    val glance = if (reminder == null) alerts.firstOrNull() else null
    val requester = remember { FocusRequester() }
    val cardKey = reminder?.let { "r:${it.key}" } ?: glance?.let { "g:${it.key}:${it.kind}:${it.detectedAt}" }
    LaunchedEffect(cardKey, controlsVisible) {
        if (cardKey != null && !controlsVisible) { withFrameNanos { }; runCatching { requester.requestFocus() } }
    }
    Box(modifier.fillMaxSize()) {
        if (!controlsVisible && picks.isNotEmpty()) when (settings.style) {
            SportsOverlayStyle.BUG -> IptvSportsBug(picks.first(), hidden(picks.first()), Modifier.align(Alignment.TopEnd).padding(top = EDGE_TOP, end = EDGE),
                state.favourites)
            SportsOverlayStyle.CARDS -> Column(Modifier.align(Alignment.TopEnd).padding(top = EDGE_TOP, end = EDGE).width(CARD_WIDTH),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                picks.forEach { fixture -> OverlayCard(fixture, hidden(fixture), SportsFavourites.has(state.favourites, fixture), now) }
            }
            SportsOverlayStyle.TICKER -> OverlayTicker(picks, ::hidden, Modifier.align(Alignment.BottomCenter).padding(horizontal = EDGE).padding(bottom = 24.dp))
            else -> Unit
        }
        val cardModifier = Modifier.align(Alignment.TopCenter).padding(top = GLANCE_TOP).onFocusChanged { cardFocused = it.hasFocus }
        when {
            reminder != null -> {
                val dismiss = { release(); reminders.remove(reminder); Unit }
                ReminderCard(reminder, link(reminder.key), now, requester, onWatch = { channel -> dismiss(); onWatch(channel) }, onDismiss = dismiss,
                    onLeave = ::release, modifier = cardModifier)
            }
            glance != null -> {
                val channel = link(glance.key)
                val dismiss = { release(); alerts.remove(glance); Unit }
                GlanceCard(glance, channel, onOk = { dismiss(); channel?.let(onWatch) }, onDismiss = dismiss, onLeave = ::release,
                    modifier = cardModifier.focusRequester(requester))
            }
        }
    }
}

@Composable
private fun GlanceCard(change: SportsChange, channel: IptvListedChannel?, onOk: () -> Unit, onDismiss: () -> Unit, onLeave: () -> Unit,
    modifier: Modifier) {
    val progress = remember(change) { Animatable(1f) }
    LaunchedEffect(change) { progress.animateTo(0f, tween(GLANCE_MILLIS, easing = LinearEasing)); onDismiss() }
    var focused by remember { mutableStateOf(false) }
    val fixture = change.fixture
    val track = NuvioTheme.colors.TextPrimary.copy(alpha = .14f)
    val bar = NuvioTheme.colors.TextPrimary
    Column(modifier.width(GLANCE_WIDTH).onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { event -> cardKeys(event.nativeKeyEvent, onOk, onDismiss, onLeave) }
        .focusable().iptvPanel(GlanceShape, GlassRole.HUD)
        .then(if (focused) Modifier.border(2.dp, NuvioTheme.colors.FocusRing, GlanceShape) else Modifier)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val team = SportsOverlayText.scorer(change) ?: fixture.home
            if (team != null) TeamLogo(team, 40.dp) else LeagueBadge(fixture, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(headline(change), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val state = SportsFixtureText.bug(fixture).state?.takeIf { change.kind != SportsChangeKind.FINISHED }
                val on = channel?.let { stringResource(R.string.iptv_sport5_overlay_on_channel, channelName(it)) }
                Text(listOfNotNull(sportLeagueName(fixture), state, on).joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (channel != null) Hint(stringResource(R.string.iptv_sport5_overlay_hint_watch))
                Hint(stringResource(R.string.iptv_sport5_overlay_hint_dismiss))
            }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).drawBehind {
            drawRect(track)
            drawRect(bar, size = Size(size.width * progress.value, size.height))
        })
    }
}

private fun cardKeys(native: AndroidKeyEvent, onOk: () -> Unit, onDismiss: () -> Unit, onLeave: () -> Unit): Boolean = when {
    isSelect(native.keyCode) -> { if (native.action == AndroidKeyEvent.ACTION_UP) onOk(); true }
    native.keyCode == AndroidKeyEvent.KEYCODE_BACK -> { if (native.action == AndroidKeyEvent.ACTION_UP) onDismiss(); true }
    native.action == AndroidKeyEvent.ACTION_DOWN -> { onLeave(); true }
    else -> false
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
}

@Composable
private fun headline(change: SportsChange): String {
    val fixture = change.fixture
    val line = SportsOverlayText.scoreLine(fixture)
    return when (SportsOverlayText.headline(change)) {
        SportsHeadline.GOAL -> stringResource(R.string.iptv_sport5_overlay_goal, line)
        SportsHeadline.TEAM_SCORED -> {
            val team = SportsOverlayText.scorer(change)
            val score = SportsOverlayText.score(fixture)
            if (team != null && score != null) stringResource(R.string.iptv_sport5_overlay_team_scored, SportsOverlayText.name(team), score)
            else stringResource(R.string.iptv_sport5_overlay_scored, line)
        }
        SportsHeadline.SCORED -> stringResource(R.string.iptv_sport5_overlay_scored, line)
        SportsHeadline.KICK_OFF -> stringResource(R.string.iptv_sport5_overlay_kick_off, SportsOverlayText.match(fixture))
        SportsHeadline.STARTED -> stringResource(R.string.iptv_sport5_overlay_started, SportsOverlayText.match(fixture))
        SportsHeadline.FULL_TIME -> stringResource(R.string.iptv_sport5_overlay_full_time, line)
        SportsHeadline.FINAL -> stringResource(R.string.iptv_sport5_overlay_final, line)
    }
}

@Composable
private fun ReminderCard(reminder: SportsReminder, channel: IptvListedChannel?, now: Long, requester: FocusRequester, onWatch: (IptvListedChannel) -> Unit, onDismiss: () -> Unit,
    onLeave: () -> Unit, modifier: Modifier) {
    LaunchedEffect(reminder) { delay(REMINDER_MILLIS); onDismiss() }
    val minutes = SportsOverlayText.minutesUntil(reminder.startMillis, maxOf(now, System.currentTimeMillis()))
    Column(modifier.width(GLANCE_WIDTH)
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            when (native.keyCode) {
                AndroidKeyEvent.KEYCODE_BACK -> { if (native.action == AndroidKeyEvent.ACTION_UP) onDismiss(); true }
                AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> false
                else -> if (native.action == AndroidKeyEvent.ACTION_DOWN && !isSelect(native.keyCode)) { onLeave(); true } else false
            }
        }
        .iptvPanel(GlanceShape, GlassRole.HUD).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.iptv_sport5_overlay_reminder), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
        Text(if (minutes > 0) stringResource(R.string.iptv_sport5_overlay_starts_in, reminder.title, minutes)
            else stringResource(R.string.iptv_sport5_overlay_starting, reminder.title),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (channel != null) NuvioActionPill({ onWatch(channel) }, Modifier.focusRequester(requester)) {
                Text(stringResource(R.string.iptv_sport5_overlay_watch), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            }
            NuvioActionPill(onDismiss, if (channel == null) Modifier.focusRequester(requester) else Modifier) { Text(stringResource(R.string.iptv_sport5_overlay_dismiss), maxLines = 1) }
        }
    }
}

@Composable
private fun OverlayCard(fixture: SportsFixture, hidden: Boolean, favourite: Boolean, now: Long) {
    val bug = remember(fixture) { SportsFixtureText.bug(fixture) }
    val close = !hidden && SportsFixtureSections.close(fixture)
    Column(Modifier.fillMaxWidth().iptvPanel(SmallShape, GlassRole.HUD)
        .then(if (close || favourite) Modifier.border(2.dp, NuvioTheme.colors.Secondary, SmallShape) else Modifier)
        .padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(sportLeagueName(fixture), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val state = when (fixture.status) {
                FixtureStatus.SCHEDULED -> SportsOverlayText.minutesUntil(fixture.startMillis, now).takeIf { it > 0 }?.let { stringResource(R.string.iptv_sport5_overlay_in_minutes, it) }
                    ?: clock(fixture.startMillis)
                else -> bug.state
            }
            val label = listOfNotNull(if (close) stringResource(R.string.iptv_sport5_overlay_close_game) else null, state).joinToString(" · ")
            if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1,
                color = if (close) NuvioTheme.colors.Secondary else if (fixture.status == FixtureStatus.LIVE) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary)
        }
        val home = fixture.home
        val away = fixture.away
        val tennis = fixture.sportDetail as? SportsDetail.Tennis
        if (home != null && away != null && (fixture.sportDetail == null || tennis != null || fixture.sportDetail is SportsDetail.Baseball)) {
            val scores = SportsFixtureText.scores(fixture)?.takeIf { !hidden && fixture.status != FixtureStatus.SCHEDULED }
            val first = SportsFixtureText.awayFirst(fixture)
            val lines = listOf(FixtureSide.HOME to home, FixtureSide.AWAY to away).let { if (first) it.reversed() else it }
            lines.forEachIndexed { index, (side, team) ->
                val value = when {
                    hidden || fixture.status == FixtureStatus.SCHEDULED -> null
                    tennis != null -> SportsOverlayText.tennisGames(tennis, side).joinToString(" ").takeIf(String::isNotEmpty)
                    else -> scores?.let { SportsOverlayText.total(if (side == FixtureSide.HOME) it.first else it.second) }
                }
                val seed = (if (side == FixtureSide.HOME) tennis?.homePlayer?.seed else tennis?.awayPlayer?.seed)?.let { " ($it)" }.orEmpty()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(SportsOverlayText.name(team) + seed, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (index == 0) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary, modifier = Modifier.weight(1f))
                    if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1,
                        color = NuvioTheme.colors.TextPrimary)
                }
            }
        } else {
            Text(if (hidden) SportsOverlayText.match(fixture) else bug.primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = NuvioTheme.colors.TextPrimary)
            bug.extra?.takeIf { !hidden }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = NuvioTheme.colors.TextSecondary)
            }
        }
    }
}

@Composable
private fun OverlayTicker(fixtures: List<SportsFixture>, hidden: (SportsFixture) -> Boolean, modifier: Modifier) {
    val pages = remember(fixtures) { SportsOverlayText.pages(fixtures) }
    var page by remember { mutableIntStateOf(0) }
    LaunchedEffect(pages.size) {
        page = 0
        if (pages.size > 1) while (true) { delay(TICKER_MILLIS); page = (page + 1) % pages.size }
    }
    val shown = pages.getOrNull(page) ?: pages.firstOrNull() ?: return
    Row(modifier.fillMaxWidth().height(44.dp).iptvPanel(SmallShape, GlassRole.HUD).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        val first = shown.fixtures.first()
        Text(sportLeagueName(first), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, color = Color.Black,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(sportColour(first.sport)).padding(horizontal = 6.dp, vertical = 2.dp))
        shown.fixtures.forEachIndexed { index, fixture ->
            if (index > 0) Text("|", style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
            val bug = SportsFixtureText.bug(fixture)
            val masked = hidden(fixture)
            val state = if (fixture.status == FixtureStatus.SCHEDULED) clock(fixture.startMillis) else bug.state
            Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (masked || fixture.status == FixtureStatus.SCHEDULED) SportsOverlayText.match(fixture) else bug.primary, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                state?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1) }
            }
        }
    }
}

@Composable
internal fun IptvSportsBug(fixture: SportsFixture, hidden: Boolean, modifier: Modifier = Modifier, followed: Set<String> = emptySet()) {
    val bug = remember(fixture) { SportsFixtureText.bug(fixture) }
    Row(modifier.height(IntrinsicSize.Min).iptvPanel(BugShape, GlassRole.HUD), verticalAlignment = Alignment.CenterVertically) {
        val home = fixture.home
        val away = fixture.away
        if (hidden) BugSegment {
            Text(SportsOverlayText.match(fixture), style = bugStyle(), fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
        } else when (val detail = fixture.sportDetail) {
            is SportsDetail.Tennis -> if (home != null && away != null && detail.sets.isNotEmpty()) TennisBug(home, away, detail, bug.state)
                else { BugText(bug.primary); BugState(bug.state, fixture); BugExtra(bug.extra) }
            is SportsDetail.Golf -> {
                val leader = detail.leaders.firstOrNull()?.takeIf { fixture.status != FixtureStatus.SCHEDULED }
                BugText(leader?.let(SportsOverlayText::golfLine) ?: bug.primary)
                if (leader == null) BugState(bug.state, fixture)
                BugExtra(SportsOverlayText.golfFollowed(detail, fixture.league, followed)?.let(SportsOverlayText::golfLine), accent = true)
            }
            is SportsDetail.Sessions -> {
                BugText(bug.primary)
                BugState(bug.state, fixture)
                val next = SportsOverlayText.nextSession(detail)
                BugExtra(next?.let { stringResource(R.string.iptv_sport5_overlay_next, it.name, "${sportDayLabel(it.startMillis)} ${clock(it.startMillis)}") } ?: bug.extra)
            }
            is SportsDetail.Card -> {
                BugText(bug.primary)
                BugState(bug.state, fixture)
                BugExtra(if (SportsOverlayText.mainEventLive(detail)) stringResource(R.string.iptv_sport5_overlay_main_event) else bug.extra)
            }
            is SportsDetail.Cricket -> { BugText(bug.primary); BugExtra(bug.extra, accent = true); BugState(bug.state, fixture) }
            is SportsDetail.Baseball -> {
                if (home != null && away != null) TeamsSegment(fixture, home, away) else BugText(bug.primary)
                BugState(bug.state, fixture)
                if (fixture.status == FixtureStatus.LIVE) BugSegment(NuvioTheme.colors.TextPrimary.copy(alpha = .04f)) {
                    Bases(detail)
                    bug.extra?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextSecondary, maxLines = 1) }
                }
            }
            null -> {
                if (home != null && away != null) TeamsSegment(fixture, home, away) else BugText(bug.primary)
                BugState(bug.state, fixture)
                val scored = remember(fixture) { SportsEvents.recentTry(fixture) }
                BugExtra(scored?.clock?.let { stringResource(R.string.iptv_sport6_try, it) } ?: bug.extra, accent = scored != null)
            }
        }
    }
}

@Composable
private fun bugStyle(): TextStyle = MaterialTheme.typography.titleSmall

@Composable
private fun BugSegment(background: Color = Color.Transparent, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxHeight().background(background).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp), content = content)
}

@Composable
private fun BugText(text: String) {
    BugSegment { Text(text, style = bugStyle(), fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 320.dp)) }
}

@Composable
private fun BugState(text: String?, fixture: SportsFixture) {
    if (text == null) return
    BugSegment(NuvioTheme.colors.TextPrimary.copy(alpha = .08f)) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (fixture.status == FixtureStatus.LIVE) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary)
    }
}

@Composable
private fun BugExtra(text: String?, accent: Boolean = false) {
    if (text == null) return
    BugSegment(NuvioTheme.colors.TextPrimary.copy(alpha = .04f)) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (accent) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary, modifier = Modifier.widthIn(max = 260.dp))
    }
}

@Composable
private fun TeamsSegment(fixture: SportsFixture, home: FixtureTeam, away: FixtureTeam) {
    val scores = SportsFixtureText.scores(fixture)?.takeIf { fixture.status != FixtureStatus.SCHEDULED }
    val first = SportsFixtureText.awayFirst(fixture)
    val left = if (first) away else home
    val right = if (first) home else away
    val leftScore = scores?.let { if (first) it.second else it.first }
    val rightScore = scores?.let { if (first) it.first else it.second }
    val possession = fixture.situation?.possession?.takeIf { fixture.sport == "american-football" && fixture.status == FixtureStatus.LIVE }
    val leftHas = possession != null && (possession == FixtureSide.AWAY) == first
    val leftReds = SportsEvents.reds(fixture, if (first) FixtureSide.AWAY else FixtureSide.HOME)
    val rightReds = SportsEvents.reds(fixture, if (first) FixtureSide.HOME else FixtureSide.AWAY)
    BugSegment {
        TeamLogo(left, 20.dp)
        RedCards(leftReds)
        if (leftHas) Dot()
        if (leftScore == null || rightScore == null) Text(stringResource(if (first) R.string.iptv_sport2_at else R.string.iptv_sport2_versus, SportsFixtureText.code(left), SportsFixtureText.code(right)),
            style = bugStyle(), fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
        else {
            BugScore(leftScore, fixture.sport, leading = true)
            Text("–", style = bugStyle(), color = NuvioTheme.colors.TextTertiary, maxLines = 1)
            BugScore(rightScore, fixture.sport, leading = false)
        }
        if (possession != null && !leftHas) Dot()
        RedCards(rightReds)
        TeamLogo(right, 20.dp)
    }
}

@Composable
private fun RedCards(count: Int) {
    if (count <= 0) return
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(minOf(count, MAX_RED_PIPS)) { Box(Modifier.size(6.dp, 9.dp).clip(RoundedCornerShape(1.dp)).background(NuvioTheme.colors.Error)) }
    }
}

@Composable
private fun BugScore(score: String, sport: String, leading: Boolean) {
    val afl = SportsOverlayText.afl(score).takeIf { sport == "australian-football" }
    val detail: @Composable () -> Unit = {
        if (afl != null) Text("${afl.goals}.${afl.behinds}", style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
    }
    if (leading) detail()
    Text(afl?.total?.toString() ?: score, style = bugStyle(), fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
    if (!leading) detail()
}

@Composable
private fun Dot() {
    Box(Modifier.size(7.dp).clip(CircleShape).background(NuvioTheme.colors.Secondary))
}

@Composable
private fun Bases(detail: SportsDetail.Baseball) {
    val on = NuvioTheme.colors.Secondary
    val off = NuvioTheme.colors.TextTertiary
    Box(Modifier.width(26.dp).height(22.dp)) {
        listOf(Triple(detail.second, 9.dp, 1.dp), Triple(detail.third, 2.dp, 9.dp), Triple(detail.first, 16.dp, 9.dp)).forEach { (occupied, x, y) ->
            Box(Modifier.offset(x, y).size(8.dp).rotate(45f).then(if (occupied) Modifier.background(on) else Modifier.border(1.5.dp, off)))
        }
    }
}

@Composable
private fun TennisBug(home: FixtureTeam, away: FixtureTeam, detail: SportsDetail.Tennis, state: String?) {
    val current = detail.sets.lastIndex.takeIf { detail.sets.lastOrNull()?.winner == null }
    Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(FixtureSide.HOME to home, FixtureSide.AWAY to away).forEach { (side, team) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (detail.server == side) NuvioTheme.colors.Secondary else Color.Transparent))
                Text(SportsOverlayText.tennisName(team), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.width(84.dp))
                SportsOverlayText.tennisGames(detail, side).forEachIndexed { index, games ->
                    Text(games, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1,
                        color = if (index == current) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextPrimary)
                }
            }
        }
    }
    if (state != null) BugSegment(NuvioTheme.colors.TextPrimary.copy(alpha = .08f)) {
        Text(state, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, color = NuvioTheme.colors.TextSecondary)
    }
}

@Composable
private fun LeagueBadge(fixture: SportsFixture, size: Dp) {
    val shown = fixture.leagueLogo?.let { LeagueLogo(it, size) } ?: false
    if (!shown) Box(Modifier.size(size).clip(CircleShape).background(sportColour(fixture.sport)), contentAlignment = Alignment.Center) {
        Text(monogram(sportLeagueName(fixture)), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1)
    }
}

private fun sportColour(sport: String): Color = when (sport) {
    "baseball" -> Color(0xFFFFCC80)
    "tennis" -> Color(0xFFC5E1A5)
    "motorsport" -> Color(0xFF90CAF9)
    "soccer" -> Color(0xFFFF8A80)
    "australian-football", "rugby-league", "rugby" -> Color(0xFFB39DDB)
    "basketball", "american-football" -> Color(0xFFFFAB91)
    "ice-hockey" -> Color(0xFF80DEEA)
    "cricket", "golf" -> Color(0xFFA5D6A7)
    else -> Color(0xFFE0E0E0)
}

private val GlanceShape = RoundedCornerShape(14.dp)
private val SmallShape = RoundedCornerShape(12.dp)
private val BugShape = RoundedCornerShape(9.dp)
private val EDGE = 36.dp
private val EDGE_TOP = 30.dp
private val GLANCE_TOP = 84.dp
private val GLANCE_WIDTH = 400.dp
private val CARD_WIDTH = 240.dp
private const val GLANCE_MILLIS = 8_000
private const val REMINDER_MILLIS = 30_000L
private const val TICKER_MILLIS = 6_000L
private const val REFRESH_MILLIS = 15_000L
private const val MAX_QUEUE = 6
private const val MAX_CARDS = 3
private const val MAX_TICKER = 24
private const val MAX_RED_PIPS = 3
