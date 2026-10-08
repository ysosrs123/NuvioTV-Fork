@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureRow
import com.nuvio.tv.core.iptv.FixtureSection
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.FixtureTeam
import com.nuvio.tv.core.iptv.SportsFavourites
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.RecordingStatus
import com.nuvio.tv.core.iptv.SportsChange
import com.nuvio.tv.core.iptv.SportsFixtureSections
import com.nuvio.tv.core.iptv.SportsRecordedWindow
import com.nuvio.tv.core.iptv.SportsReminder
import com.nuvio.tv.core.iptv.SportsService
import com.nuvio.tv.core.iptv.SportsSpoilers
import com.nuvio.tv.core.recording.IptvRecorder
import com.nuvio.tv.data.iptv.IptvFixtureLink
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvRecording
import com.nuvio.tv.data.iptv.IptvSourceRef
import com.nuvio.tv.data.iptv.IptvSportsFixtures
import com.nuvio.tv.data.iptv.IptvSportsFixturesRepository
import com.nuvio.tv.data.iptv.IptvSportsLive
import com.nuvio.tv.data.iptv.IptvSportsMode
import com.nuvio.tv.data.iptv.IptvSportsSnapshot
import com.nuvio.tv.data.iptv.IptvSportsPreferences
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.rememberShimmerBrush
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IptvFixtureItem(val fixture: SportsFixture, val links: List<IptvFixtureLink>, val favourite: Boolean = false, val linking: Boolean = false)

data class IptvFixtureRow(val section: FixtureSection, val day: LocalDate?, val items: List<IptvFixtureItem>) {
    val key: String get() = "${section.name}:${day ?: ""}"
}

data class IptvFixturesState(val enabled: Boolean = false, val loading: Boolean = false, val rows: List<IptvFixtureRow> = emptyList(),
    val failed: Boolean = false, val missingKey: Boolean = false, val noLeagues: Boolean = false, val showScores: Boolean = true,
    val favourites: Set<String> = emptySet(), val reminders: Set<String> = emptySet(), val spoilerKeys: Set<String> = emptySet())

@HiltViewModel
class IptvSportsFixturesViewModel @Inject constructor(private val repository: IptvSportsFixturesRepository, private val preferences: IptvSportsPreferences,
    private val live: IptvSportsLive, private val recorder: IptvRecorder) : ViewModel() {
    private val mutable = MutableStateFlow((preferences.service != SportsService.OFF).let { IptvFixturesState(enabled = it, loading = it) })
    val state = mutable.asStateFlow()
    private val heroKey = MutableStateFlow<String?>(FEATURED)
    val hero = heroKey.asStateFlow()
    val alerts: SharedFlow<SportsChange> = live.alerts
    val reminderDue: SharedFlow<SportsReminder> = live.reminderDue
    private var handle: IptvSportsLive.Handle? = null
    private var linkJob: Job? = null
    private var opened: Pair<IptvSourceRef, Set<String>>? = null
    private var loaded: IptvSportsFixtures? = null
    private var grouped = emptyList<FixtureRow>()
    private var refreshed = false
    private var linked = emptyMap<String, List<IptvFixtureLink>>()
    private var linkedSignature: Set<Pair<String, Long>>? = null
    private var pendingSignature: Set<Pair<String, Long>>? = null
    private var linkedAt = 0L
    private var viewing = false
    private var recordings = emptyList<IptvRecording>()
    private var spoilerKeys = emptySet<String>()

    init {
        viewModelScope.launch { live.snapshot.collect { snapshot -> if (snapshot != null && snapshot.mode == IptvSportsMode.LIVE_TV && handle != null) accept(snapshot) } }
        viewModelScope.launch { live.reminders.collect { keys -> mutable.update { it.copy(reminders = keys) } } }
        viewModelScope.launch { recorder.all.collect { all -> recordings = all; if (spoilers()) publish() } }
    }

    fun open(ref: IptvSourceRef, hiddenCategories: Set<String>) {
        if (handle != null && opened == ref to hiddenCategories) return
        linkJob?.cancel()
        if (opened?.first != ref) linked = emptyMap()
        linkedSignature = null
        opened = ref to hiddenCategories
        if (handle == null) handle = live.acquire(IptvSportsMode.LIVE_TV)
        live.snapshot.value?.takeIf { it.mode == IptvSportsMode.LIVE_TV }?.let(::accept)
    }

    private fun accept(snapshot: IptvSportsSnapshot) {
        val result = snapshot.result
        if (result.service == SportsService.OFF) {
            loaded = null; grouped = emptyList(); linkJob?.cancel(); linked = emptyMap(); linkedSignature = null; spoilerKeys = emptySet()
            mutable.value = IptvFixturesState(reminders = live.reminders.value)
            return
        }
        try {
            val now = System.currentTimeMillis()
            val showScores = preferences.showScores
            val favourites = preferences.favouriteTeams
            loaded = result; refreshed = snapshot.refreshed
            grouped = SportsFixtureSections.group(result.fixtures, now, ZoneId.systemDefault(), showScores, favourites)
            spoilers()
            publish(showScores, favourites)
            opened?.let { (ref, hiddenCategories) -> relink(ref, hiddenCategories, now) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { IptvLog.failure("sports fixtures", error) }
    }

    private fun spoilers(): Boolean {
        val result = loaded
        val profile = opened?.first?.profileId
        val next = if (result == null || profile == null || !preferences.hideSpoilers) emptySet() else SportsSpoilers.keys(result.fixtures,
            recordings.filter { it.profileId == profile && it.status != RecordingStatus.FAILED && it.status != RecordingStatus.CANCELLED }
                .mapNotNull { recording -> recording.title?.let { SportsRecordedWindow(it, recording.startMillis, recording.stopMillis) } })
        if (next == spoilerKeys) return false
        spoilerKeys = next
        return true
    }

    fun toggleReminder(fixture: SportsFixture) = live.toggleReminder(fixture)

    fun setOnScreen(keys: Set<String>) = live.setOnScreen(keys)

    override fun onCleared() { handle?.release(); handle = null }

    fun close() { handle?.release(); handle = null; linkJob?.cancel(); linkJob = null; pendingSignature = null; publish() }

    fun show(ref: IptvSourceRef, hiddenCategories: Set<String>, started: Boolean) {
        viewing = true
        heroKey.value = FEATURED
        if (!started) return
        open(ref, hiddenCategories)
        if (loaded != null) relink(ref, hiddenCategories, System.currentTimeMillis())
    }

    fun hide() { viewing = false }

    fun focusFixture(key: String?) { heroKey.value = key }

    fun toggleFavourite(fixture: SportsFixture, team: FixtureTeam) {
        preferences.favouriteTeams = SportsFavourites.toggle(preferences.favouriteTeams, fixture.league, team)
        val result = loaded ?: return
        val showScores = preferences.showScores
        val favourites = preferences.favouriteTeams
        grouped = SportsFixtureSections.group(result.fixtures, System.currentTimeMillis(), ZoneId.systemDefault(), showScores, favourites)
        publish(showScores, favourites)
    }

    private fun relink(ref: IptvSourceRef, hiddenCategories: Set<String>, now: Long) {
        val shown = grouped.flatMap { it.fixtures }.filter { it.status != FixtureStatus.FINAL }.distinctBy { it.key }
        val signature = shown.map { it.key to it.startMillis }.toSet()
        if (linkJob?.isActive == true) { if (signature == pendingSignature) return; linkJob?.cancel() }
        if (signature == linkedSignature && !(viewing && now - linkedAt >= RELINK_MILLIS)) return
        pendingSignature = signature
        publish()
        linkJob = viewModelScope.launch {
            linked = try { repository.links(ref, shown, now, hiddenCategories) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { IptvLog.failure("sports links", error); linked }
            linkedSignature = signature; linkedAt = System.currentTimeMillis(); pendingSignature = null
            publish()
        }
    }

    private fun publish(showScores: Boolean = mutable.value.showScores, favourites: Set<String> = mutable.value.favourites) {
        val result = loaded ?: return
        val linking = pendingSignature != null || linkedSignature == null
        mutable.value = IptvFixturesState(true, loading = !refreshed && grouped.isEmpty() && !result.missingKey && !result.noLeagues,
            rows = grouped.map { row -> IptvFixtureRow(row.section, row.day, row.fixtures.map { fixture ->
                val links = linked[fixture.id].orEmpty()
                IptvFixtureItem(fixture, links, SportsFavourites.has(favourites, fixture), linking && links.isEmpty() && fixture.status != FixtureStatus.FINAL)
            }) },
            failed = result.failed, missingKey = result.missingKey, noLeagues = result.noLeagues, showScores = showScores, favourites = favourites,
            reminders = live.reminders.value, spoilerKeys = spoilerKeys)
    }

    companion object {
        const val FEATURED = "\u0000featured"
        private const val RELINK_MILLIS = 5L * 60 * 1000
    }
}

@Composable
internal fun IptvSportsFixturesSync(source: IptvSourceRef?, hiddenCategories: Set<String>, active: Boolean,
    viewModel: IptvSportsFixturesViewModel = hiltViewModel()) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, source, hiddenCategories, active) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) { if (active && source != null) viewModel.open(source, hiddenCategories) else viewModel.close() }
            if (event == Lifecycle.Event.ON_STOP) viewModel.close()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); viewModel.close() }
    }
}

@Composable
internal fun IptvSportsFixturesRow(source: IptvSourceRef?, hiddenCategories: Set<String>, playingId: String?, onWatch: (IptvListedChannel) -> Unit,
    onRail: () -> Unit = {}, modifier: Modifier = Modifier, viewModel: IptvSportsFixturesViewModel = hiltViewModel()) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, source, hiddenCategories) {
        if (source != null) viewModel.show(source, hiddenCategories, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { viewModel.hide() }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.enabled || source == null) return
    var choosing by remember { mutableStateOf<IptvFixtureItem?>(null) }
    var holding by remember { mutableStateOf<IptvFixtureItem?>(null) }
    val message = when {
        state.missingKey -> R.string.iptv_sport_key_missing
        state.noLeagues -> R.string.iptv_sport_no_leagues
        state.failed -> R.string.iptv_sport_failed
        state.loading -> R.string.iptv_sport_loading
        state.rows.isEmpty() -> R.string.iptv_sport_none
        else -> null
    }
    val warning = state.failed || state.missingKey
    if (state.rows.isEmpty()) {
        Column(modifier.fillMaxWidth().height(SPORT_ROW_HEIGHT), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.iptv_sport_fixtures), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary, maxLines = 1)
                if (state.loading && message != null) RowMessage(message, false)
            }
            if (state.loading) SportCardsPlaceholder()
            else Row(Modifier.fillMaxWidth().height(SPORT_CARD_HEIGHT).iptvPanel(SportCardShape).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Filled.SportsSoccer, null, Modifier.size(32.dp), tint = NuvioTheme.colors.TextTertiary)
                if (message != null) Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (warning) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary)
            }
        }
    } else {
        var lastKey by remember { mutableIntStateOf(0) }
        LazyColumn(modifier.fillMaxWidth().height(SPORT_ROW_HEIGHT)
            .onPreviewKeyEvent { event -> if (event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) lastKey = event.nativeKeyEvent.keyCode; false }
            .onFocusChanged { if (!it.hasFocus && lastKey == AndroidKeyEvent.KEYCODE_DPAD_DOWN) viewModel.focusFixture(null) }
            .focusGroup(), verticalArrangement = Arrangement.spacedBy(SPORT_ROW_GAP)) {
            itemsIndexed(state.rows, key = { _, row -> row.key }) { index, row ->
                SportFixtureRow(row, state.showScores, playingId, message.takeIf { index == 0 && state.failed }, onRail,
                    onFocused = { viewModel.focusFixture(it.fixture.key) },
                    onClick = { item -> if (item.links.size == 1) onWatch(item.links.first().row) else choosing = item },
                    onHold = { holding = it })
            }
        }
    }
    choosing?.let { chosen ->
        val item = state.rows.firstNotNullOfOrNull { row -> row.items.firstOrNull { it.fixture.key == chosen.fixture.key } } ?: chosen
        FixtureChannelsDialog(item, onWatch = { choosing = null; onWatch(it) }, onDismiss = { choosing = null })
    }
    holding?.let { held ->
        val item = state.rows.firstNotNullOfOrNull { row -> row.items.firstOrNull { it.fixture.key == held.fixture.key } } ?: held
        FixtureOptionsDialog(item, state.favourites, onToggle = { team -> viewModel.toggleFavourite(item.fixture, team) },
            onWatch = { holding = null; if (item.links.size == 1) onWatch(item.links.first().row) else choosing = item }, onDismiss = { holding = null })
    }
}

@Composable
private fun RowMessage(message: Int, warning: Boolean) {
    Text(stringResource(message), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = if (warning) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary)
}

@Composable
private fun SportFixtureRow(row: IptvFixtureRow, showScores: Boolean, playingId: String?, message: Int?, onRail: () -> Unit,
    onFocused: (IptvFixtureItem) -> Unit, onClick: (IptvFixtureItem) -> Unit, onHold: (IptvFixtureItem) -> Unit) {
    val listState = rememberLazyListState()
    var lastFocused by remember(row.key) { mutableIntStateOf(0) }
    val requesters = remember(row.key) { mutableMapOf<Int, FocusRequester>() }
    fun requester(index: Int) = requesters.getOrPut(index) { FocusRequester() }
    Column(Modifier.fillMaxWidth().height(SPORT_ROW_HEIGHT), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (row.section == FixtureSection.LIVE) Box(Modifier.size(8.dp).clip(CircleShape).background(NuvioTheme.colors.Error))
            Text(rowTitle(row), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
            Text("${row.items.size}", style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
            if (message != null) RowMessage(message, true)
        }
        LazyRow(state = listState, modifier = Modifier.fillMaxWidth()
            .focusRestorer {
                val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
                val index = lastFocused.takeIf { it in visible } ?: visible.firstOrNull()
                index?.let { requesters[it] } ?: FocusRequester.Default
            }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val left = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT && lastFocused == 0
                if (left && native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) onRail()
                left
            }, contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(row.items, key = { _, item -> item.fixture.key }) { index, item ->
                SportFixtureCard(item, showScores, playing = item.links.any { it.row.item.channel.id == playingId }, modifier = Modifier.focusRequester(requester(index)),
                    onFocused = { lastFocused = index; onFocused(item) }, onClick = { onClick(item) }, onHold = { onHold(item) })
            }
        }
    }
}

@Composable
private fun rowTitle(row: IptvFixtureRow): String = when (row.section) {
    FixtureSection.LIVE -> stringResource(R.string.iptv_sport_live_now)
    FixtureSection.CLOSE -> stringResource(R.string.iptv_sport2_close_games)
    FixtureSection.TODAY -> stringResource(R.string.iptv_sport_later_today)
    FixtureSection.TOMORROW -> stringResource(R.string.iptv_sport_tomorrow)
    FixtureSection.DAY -> row.day?.let { sportDate(it, "EEEEdMMMM") }.orEmpty()
    FixtureSection.FINISHED -> stringResource(R.string.iptv_sport2_finished)
}

@Composable
private fun FixtureOptionsDialog(item: IptvFixtureItem, favourites: Set<String>, onToggle: (FixtureTeam) -> Unit, onWatch: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val fixture = item.fixture
    val teams = listOfNotNull(fixture.home, fixture.away)
    NuvioDialog(onDismiss = onDismiss, title = sportTitle(fixture),
        subtitle = if (teams.isEmpty()) null else stringResource(R.string.iptv_sport2_favourite_subtitle), width = 560.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            teams.forEachIndexed { index, team ->
                SettingsToggleRow(title = team.name, subtitle = stringResource(R.string.iptv_sport2_favourite_team),
                    checked = SportsFavourites.key(fixture.league, team) in favourites, onToggle = { onToggle(team) },
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier)
            }
            if (fixture.status != FixtureStatus.FINAL) SettingsActionRow(title = stringResource(R.string.iptv_sport_choose_channel), subtitle = null,
                onClick = onWatch, leadingIcon = Icons.Filled.LiveTv, trailingIcon = null, modifier = if (teams.isEmpty()) Modifier.focusRequester(first) else Modifier)
            if (teams.isEmpty() && fixture.status == FixtureStatus.FINAL) NuvioActionPill(onDismiss, Modifier.focusRequester(first)) { Text(stringResource(R.string.iptv_sport_close)) }
        }
    }
}

@Composable
private fun FixtureChannelsDialog(item: IptvFixtureItem, onWatch: (IptvListedChannel) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(item.links.isEmpty()) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val fixture = item.fixture
    NuvioDialog(onDismiss = onDismiss, title = sportTitle(fixture),
        subtitle = stringResource(when {
            item.links.isNotEmpty() -> R.string.iptv_sport_choose_channel
            item.linking -> R.string.iptv_sport3_finding_channels
            else -> R.string.iptv_sport_no_channel_description
        }), width = 560.dp) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item.links.forEachIndexed { index, link ->
                SettingsActionRow(title = channelName(link.row),
                    subtitle = link.programme?.let { "${timeRange(it)} · ${title(it)}" } ?: link.broadcaster?.let { stringResource(R.string.iptv_sport_broadcaster, it) },
                    onClick = { onWatch(link.row) }, leadingIcon = Icons.Filled.LiveTv, trailingIcon = null,
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier)
            }
            if (item.links.isEmpty()) NuvioActionPill(onDismiss, Modifier.focusRequester(first)) { Text(stringResource(R.string.iptv_sport_close)) }
        }
    }
}

@Composable
private fun SportCardsPlaceholder() {
    val brush = rememberShimmerBrush(backdropAware = LocalV2Appearance.current != null)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(PLACEHOLDER_CARDS) {
            Column(Modifier.width(SPORT_CARD_WIDTH).height(SPORT_CARD_HEIGHT).clip(SportCardShape)
                .background(NuvioTheme.colors.TextPrimary.copy(alpha = .05f), SportCardShape)
                .border(1.dp, NuvioTheme.colors.TextPrimary.copy(alpha = .08f), SportCardShape)) {
                Box(Modifier.padding(start = 10.dp, top = 10.dp).width(44.dp).height(12.dp).clip(SportPlaceholderShape).background(brush))
                Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(brush))
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(52.dp).height(18.dp).clip(SportPlaceholderShape).background(brush))
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(40.dp).clip(CircleShape).background(brush))
                }
                Box(Modifier.fillMaxWidth().height(24.dp).background(NuvioTheme.colors.TextPrimary.copy(alpha = .05f)).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.width(96.dp).height(10.dp).clip(SportPlaceholderShape).background(brush))
                }
            }
        }
    }
}

internal val SportPlaceholderShape = RoundedCornerShape(4.dp)
private val SPORT_ROW_HEIGHT = 168.dp
private val SPORT_ROW_GAP = 12.dp
private const val PLACEHOLDER_CARDS = 6
