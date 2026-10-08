@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureRow
import com.nuvio.tv.core.iptv.FixtureSection
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.FixtureTeam
import com.nuvio.tv.core.iptv.SportsFavourites
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureSections
import com.nuvio.tv.core.iptv.SportsService
import com.nuvio.tv.data.iptv.IptvFixtureLink
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvSourceRef
import com.nuvio.tv.data.iptv.IptvSportsFixtures
import com.nuvio.tv.data.iptv.IptvSportsFixturesRepository
import com.nuvio.tv.data.iptv.IptvSportsPreferences
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class IptvFixtureItem(val fixture: SportsFixture, val links: List<IptvFixtureLink>, val favourite: Boolean = false)

data class IptvFixtureRow(val section: FixtureSection, val day: LocalDate?, val items: List<IptvFixtureItem>) {
    val key: String get() = "${section.name}:${day ?: ""}"
}

data class IptvFixturesState(val enabled: Boolean = false, val loading: Boolean = false, val rows: List<IptvFixtureRow> = emptyList(),
    val failed: Boolean = false, val missingKey: Boolean = false, val noLeagues: Boolean = false, val showScores: Boolean = true,
    val favourites: Set<String> = emptySet())

@HiltViewModel
class IptvSportsFixturesViewModel @Inject constructor(private val repository: IptvSportsFixturesRepository, private val preferences: IptvSportsPreferences) : ViewModel() {
    private val mutable = MutableStateFlow(IptvFixturesState())
    val state = mutable.asStateFlow()
    private val heroKey = MutableStateFlow<String?>(null)
    val hero = heroKey.asStateFlow()
    private var job: Job? = null
    private var opened: Pair<IptvSourceRef, Set<String>>? = null
    private var loaded: IptvSportsFixtures? = null
    private var linked = emptyMap<String, List<IptvFixtureLink>>()
    private var refreshed = false

    fun open(ref: IptvSourceRef, hiddenCategories: Set<String>) {
        if (job?.isActive == true && opened == ref to hiddenCategories) return
        job?.cancel()
        opened = ref to hiddenCategories
        job = viewModelScope.launch {
            var refresh = false
            var signature: List<Pair<String, Long>>? = null
            var linkedAt = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                val zone = ZoneId.systemDefault()
                if (!refresh) mutable.update { it.copy(loading = it.rows.isEmpty()) }
                try {
                    val result = repository.load(now, zone, refresh)
                    if (result.service == SportsService.OFF) { loaded = null; mutable.value = IptvFixturesState(); return@launch }
                    val showScores = preferences.showScores
                    val favourites = preferences.favouriteTeams
                    val rows = SportsFixtureSections.group(result.fixtures, now, zone, showScores, favourites)
                    val shown = rows.flatMap { it.fixtures }.filter { it.status != FixtureStatus.FINAL }.distinctBy { it.key }
                    val current = shown.map { it.key to it.startMillis }
                    if (current != signature || now - linkedAt >= RELINK_MILLIS) {
                        linked = try { repository.links(ref, shown, now, hiddenCategories) }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (error: Exception) { IptvLog.failure("sports links", error); linked }
                        signature = current; linkedAt = now
                    }
                    loaded = result; refreshed = refresh
                    publish(result, rows, refresh, showScores, favourites)
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    IptvLog.failure("sports fixtures", error)
                    mutable.update { it.copy(enabled = true, loading = false, failed = true) }
                }
                if (refresh) delay(TICK_MILLIS)
                refresh = true
            }
        }
    }

    fun close() { job?.cancel(); job = null; heroKey.value = null }

    fun focusFixture(key: String?) { heroKey.value = key }

    fun toggleFavourite(fixture: SportsFixture, team: FixtureTeam) {
        preferences.favouriteTeams = SportsFavourites.toggle(preferences.favouriteTeams, fixture.league, team)
        val result = loaded ?: return
        val showScores = preferences.showScores
        val favourites = preferences.favouriteTeams
        publish(result, SportsFixtureSections.group(result.fixtures, System.currentTimeMillis(), ZoneId.systemDefault(), showScores, favourites), refreshed,
            showScores, favourites)
    }

    private fun publish(result: IptvSportsFixtures, rows: List<FixtureRow>, refresh: Boolean, showScores: Boolean, favourites: Set<String>) {
        mutable.value = IptvFixturesState(true, loading = !refresh && rows.isEmpty() && !result.missingKey && !result.noLeagues,
            rows = rows.map { row -> IptvFixtureRow(row.section, row.day, row.fixtures.map { IptvFixtureItem(it, linked[it.id].orEmpty(), SportsFavourites.has(favourites, it)) }) },
            failed = result.failed, missingKey = result.missingKey, noLeagues = result.noLeagues, showScores = showScores, favourites = favourites)
    }

    private companion object {
        const val TICK_MILLIS = 60_000L
        const val RELINK_MILLIS = 5L * 60 * 1000
    }
}

@Composable
internal fun IptvSportsFixturesRow(source: IptvSourceRef?, hiddenCategories: Set<String>, playingId: String?, onWatch: (IptvListedChannel) -> Unit,
    onRail: () -> Unit = {}, modifier: Modifier = Modifier, viewModel: IptvSportsFixturesViewModel = hiltViewModel()) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, source, hiddenCategories) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && source != null) viewModel.open(source, hiddenCategories)
            if (event == Lifecycle.Event.ON_STOP) viewModel.close()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); viewModel.close() }
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
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.iptv_sport_fixtures), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary)
            if (message != null) RowMessage(message, warning)
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
    choosing?.let { item -> FixtureChannelsDialog(item, onWatch = { choosing = null; onWatch(it) }, onDismiss = { choosing = null }) }
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
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val fixture = item.fixture
    NuvioDialog(onDismiss = onDismiss, title = sportTitle(fixture),
        subtitle = stringResource(if (item.links.isEmpty()) R.string.iptv_sport_no_channel_description else R.string.iptv_sport_choose_channel), width = 560.dp) {
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

private val SPORT_ROW_HEIGHT = 168.dp
private val SPORT_ROW_GAP = 12.dp
