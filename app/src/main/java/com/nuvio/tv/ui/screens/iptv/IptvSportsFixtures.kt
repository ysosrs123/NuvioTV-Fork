@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import android.view.KeyEvent as AndroidKeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
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
import com.nuvio.tv.core.iptv.RecordingStatus
import com.nuvio.tv.core.iptv.SportsCatchup
import com.nuvio.tv.core.iptv.SportsDbLive
import com.nuvio.tv.core.iptv.SportsChange
import com.nuvio.tv.core.iptv.SportsChannelRules
import com.nuvio.tv.core.iptv.SportsDays
import com.nuvio.tv.core.iptv.SportsDetail
import com.nuvio.tv.core.iptv.SportsFavourites
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureSections
import com.nuvio.tv.core.iptv.SportsPendingRecords
import com.nuvio.tv.core.iptv.SportsRecordedWindow
import com.nuvio.tv.core.iptv.SportsReminder
import com.nuvio.tv.core.iptv.SportsService
import com.nuvio.tv.core.iptv.SportsSpoilers
import com.nuvio.tv.core.iptv.SportsSummary
import com.nuvio.tv.core.iptv.SportsTimeline
import com.nuvio.tv.core.recording.IptvRecorder
import com.nuvio.tv.data.iptv.IptvFixtureLink
import com.nuvio.tv.data.iptv.IptvGuideDaysPreference
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvRecording
import com.nuvio.tv.data.iptv.IptvSourceRef
import com.nuvio.tv.data.iptv.IptvSportsFixtures
import com.nuvio.tv.data.iptv.IptvSportsFixturesRepository
import com.nuvio.tv.data.iptv.IptvSportsLive
import com.nuvio.tv.data.iptv.IptvSportsMode
import com.nuvio.tv.data.iptv.IptvSportsPreferences
import com.nuvio.tv.data.iptv.IptvSportsSnapshot
import com.nuvio.tv.data.iptv.IptvSportsSummaryClient
import com.nuvio.tv.data.iptv.IptvSportsSummaryWatch
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.rememberShimmerBrush
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IptvFixtureItem(val fixture: SportsFixture, val links: List<IptvFixtureLink>, val favourite: Boolean = false, val linking: Boolean = false,
    val scheduleOnly: Boolean = false)

data class IptvFixtureRow(val section: FixtureSection, val day: LocalDate?, val items: List<IptvFixtureItem>) {
    val key: String get() = "${section.name}:${day ?: ""}"
}

data class IptvFixturesState(val enabled: Boolean = false, val loading: Boolean = false, val rows: List<IptvFixtureRow> = emptyList(),
    val failed: Boolean = false, val missingKey: Boolean = false, val noLeagues: Boolean = false, val showScores: Boolean = true,
    val favourites: Set<String> = emptySet(), val reminders: Set<String> = emptySet(), val spoilerKeys: Set<String> = emptySet(),
    val recent: List<IptvFixtureItem> = emptyList(), val hideSpoilers: Boolean = false, val recorded: Set<String> = emptySet(),
    val pending: Set<String> = emptySet()) {
    fun hidden(fixture: SportsFixture): Boolean = !showScores || fixture.key in spoilerKeys
    fun spoiler(fixture: SportsFixture): Boolean = showScores && fixture.key in spoilerKeys
    val items: List<IptvFixtureItem> get() = rows.flatMap { it.items }.distinctBy { it.fixture.key }
    fun item(key: String?): IptvFixtureItem? = key?.let { wanted -> rows.firstNotNullOfOrNull { row -> row.items.firstOrNull { it.fixture.key == wanted } } }
}

sealed class IptvSportPrompt {
    data class Channels(val key: String) : IptvSportPrompt()
    data class Options(val key: String) : IptvSportPrompt()
    data class Watch(val row: IptvListedChannel) : IptvSportPrompt()
    data object Scores : IptvSportPrompt()
}

@HiltViewModel
class IptvSportsFixturesViewModel @Inject constructor(private val repository: IptvSportsFixturesRepository, private val preferences: IptvSportsPreferences,
    private val live: IptvSportsLive, private val recorder: IptvRecorder, summaryClient: IptvSportsSummaryClient, private val nuvio: IptvSportsNuvio,
    private val livePreferences: IptvLivePreferences, refreshes: IptvRefreshCoordinator, @ApplicationContext private val context: Context) : ViewModel() {
    private val mutable = MutableStateFlow(preferences.enabled.let { IptvFixturesState(enabled = it, loading = it) })
    val state = mutable.asStateFlow()
    private val heroKey = MutableStateFlow<String?>(FEATURED)
    val hero = heroKey.asStateFlow()
    val alerts: SharedFlow<SportsChange> = live.alerts
    val reminderDue: SharedFlow<SportsReminder> = live.reminderDue
    private val summaryWatch = IptvSportsSummaryWatch(summaryClient, viewModelScope)
    val summary: StateFlow<SportsSummary?> = summaryWatch.summary
    private val promptState = MutableStateFlow<IptvSportPrompt?>(null)
    val prompt = promptState.asStateFlow()
    private val revealed = mutableSetOf<String>()
    private var summaryFixture: SportsFixture? = null
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
    private var recordedKeys = emptySet<String>()
    private var refreshedAt = 0L
    private val messageEvents = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = messageEvents.asSharedFlow()
    val channelRules: StateFlow<SportsChannelRules> = preferences.channelRulesFlow

    init {
        viewModelScope.launch { live.snapshot.collect { snapshot -> if (snapshot != null && snapshot.mode == IptvSportsMode.LIVE_TV && handle != null) accept(snapshot) } }
        viewModelScope.launch { live.reminders.collect { keys -> mutable.update { it.copy(reminders = keys) } } }
        viewModelScope.launch { recorder.all.collect { all -> recordings = all; val spoiled = spoilers(); if (booked() || spoiled) publish() } }
        viewModelScope.launch { nuvio.pending.collect { publish() } }
        viewModelScope.launch {
            preferences.channelRulesFlow.map { it.linking }.distinctUntilChanged().drop(1).collect {
                if (handle != null && loaded != null) opened?.let { (ref, hidden) -> relink(ref, hidden, System.currentTimeMillis(), force = true) }
            }
        }
        viewModelScope.launch {
            refreshes.status.collect { all ->
                val done = all.values.filter { it.phase == IptvRefreshPhase.DONE }.maxOfOrNull { it.finishedAtMillis ?: 0L } ?: 0L
                if (done <= refreshedAt) return@collect
                val first = refreshedAt == 0L
                refreshedAt = done
                if (!first && handle != null && loaded != null) opened?.let { (ref, hidden) -> relink(ref, hidden, System.currentTimeMillis(), force = true) }
            }
        }
    }

    fun open(ref: IptvSourceRef, hiddenCategories: Set<String>) {
        if (handle != null && opened == ref to hiddenCategories) return
        linkJob?.cancel()
        if (opened?.first != ref) linked = emptyMap()
        linkedSignature = null
        opened = ref to hiddenCategories
        booked()
        SportsDays.guideDays(IptvGuideDaysPreference(livePreferences).days.future)
        if (handle == null) { handle = live.acquire(IptvSportsMode.LIVE_TV); summaryFixture?.let(::watchSummary) }
        live.snapshot.value?.takeIf { it.mode == IptvSportsMode.LIVE_TV }?.let(::accept)
    }

    private fun accept(snapshot: IptvSportsSnapshot) {
        val result = snapshot.result
        if (!result.enabled) {
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
            recordings.filter { it.profileId == profile && it.status != RecordingStatus.FAILED && it.status != RecordingStatus.CANCELLED && it.playedAtMillis == null }
                .mapNotNull { recording -> recording.title?.let { SportsRecordedWindow(it, recording.startMillis, recording.stopMillis) } })
        if (next == spoilerKeys) return false
        spoilerKeys = next
        return true
    }

    fun toggleReminder(fixture: SportsFixture) = live.toggleReminder(fixture)

    fun setOnScreen(keys: Set<String>) = live.setOnScreen(keys)

    fun reveal(key: String) { if (revealed.add(key)) publish() }

    fun prompt(next: IptvSportPrompt?) { promptState.value = next }

    fun watchSummary(fixture: SportsFixture?) {
        summaryFixture = fixture
        if (fixture == null || fixture.status != FixtureStatus.LIVE || fixture.source != SportsService.ESPN || handle == null) summaryWatch.stop()
        else summaryWatch.start(fixture)
    }

    override fun onCleared() { summaryWatch.stop(); handle?.release(); handle = null }

    fun close() { summaryWatch.stop(); handle?.release(); handle = null; linkJob?.cancel(); linkJob = null; pendingSignature = null; publish() }

    fun show(ref: IptvSourceRef, hiddenCategories: Set<String>, started: Boolean) {
        viewing = true
        heroKey.value = FEATURED
        if (!started) return
        open(ref, hiddenCategories)
        if (loaded != null) relink(ref, hiddenCategories, System.currentTimeMillis())
    }

    fun hide() { viewing = false; summaryWatch.stop(); promptState.value = null }

    fun focusFixture(key: String?) { heroKey.value = key }

    fun toggleFavourite(fixture: SportsFixture, team: FixtureTeam) {
        preferences.favouriteTeams = SportsFavourites.toggle(preferences.favouriteTeams, fixture.league, team)
        val result = loaded ?: return
        val showScores = preferences.showScores
        val favourites = preferences.favouriteTeams
        grouped = SportsFixtureSections.group(result.fixtures, System.currentTimeMillis(), ZoneId.systemDefault(), showScores, favourites)
        publish(showScores, favourites)
    }

    private fun relink(ref: IptvSourceRef, hiddenCategories: Set<String>, now: Long, force: Boolean = false) {
        val shown = (grouped.flatMap { it.fixtures }.filter { it.status != FixtureStatus.FINAL } + SportsCatchup.recent(loaded?.fixtures.orEmpty(), now))
            .distinctBy { it.key }
        val signature = shown.map { it.key to it.startMillis }.toSet()
        if (linkJob?.isActive == true) { if (signature == pendingSignature && !force) return; linkJob?.cancel() }
        if (!force && signature == linkedSignature && !(viewing && now - linkedAt >= RELINK_MILLIS)) return
        pendingSignature = signature
        publish()
        linkJob = viewModelScope.launch {
            linked = try { repository.links(ref, shown, now, hiddenCategories) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { IptvLog.failure("sports links", error); linked }
            linkedSignature = signature; linkedAt = System.currentTimeMillis(); pendingSignature = null
            publish()
            recordPending()
        }
    }

    fun record(item: IptvFixtureItem) {
        val link = item.links.firstOrNull() ?: return
        viewModelScope.launch { messageEvents.emit(nuvio.record(item.fixture, link)) }
    }

    fun cancelRecording(fixture: SportsFixture) {
        val profile = opened?.first?.profileId ?: return
        val entry = recordings.firstOrNull { it.profileId == profile && it.fixtureKey == fixture.key && !it.status.finished } ?: return
        viewModelScope.launch {
            val done = try { recorder.cancel(entry.id) } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { IptvLog.failure("sports record cancel", error); false }
            if (done) messageEvents.emit(context.getString(R.string.iptv_sport9_recording_cancelled, nuvio.plainTitle(fixture)))
        }
    }

    fun togglePending(fixture: SportsFixture) {
        val profile = opened?.first?.profileId ?: return
        val now = System.currentTimeMillis()
        val was = nuvio.pending.value.any { it.profileId == profile && it.key == fixture.key }
        val next = nuvio.changePending { SportsPendingRecords.toggle(it, profile, fixture, now) }
        val added = !was && next.any { it.profileId == profile && it.key == fixture.key }
        if (was || added) messageEvents.tryEmit(context.getString(if (added) R.string.iptv_sport9_pending_added else R.string.iptv_sport9_pending_removed,
            nuvio.plainTitle(fixture)))
        if (added) recordPending()
    }

    private fun recordPending() {
        val profile = opened?.first?.profileId ?: return
        val now = System.currentTimeMillis()
        val known = loaded?.fixtures.orEmpty()
        val all = nuvio.changePending { SportsPendingRecords.update(it, known, now) }
        val ready = nuvio.claimPending(profile, SportsPendingRecords.ready(all, profile, known, { linked[it.id].orEmpty().isNotEmpty() }, now))
        if (ready.isEmpty()) return
        viewModelScope.launch { ready.forEach { fixture -> linked[fixture.id]?.firstOrNull()?.let { messageEvents.emit(nuvio.record(fixture, it)) } } }
    }

    private fun booked(): Boolean {
        val profile = opened?.first?.profileId
        val next = if (profile == null) emptySet() else recordings.filter { it.profileId == profile && !it.status.finished }.mapNotNull { it.fixtureKey }.toSet()
        if (next == recordedKeys) return false
        recordedKeys = next
        return true
    }

    private fun pendingKeys(): Set<String> = opened?.first?.profileId?.let { profile -> nuvio.pending.value.filter { it.profileId == profile }.map { it.key }.toSet() }.orEmpty()

    private fun publish(showScores: Boolean = mutable.value.showScores, favourites: Set<String> = mutable.value.favourites) {
        val result = loaded ?: return
        val linking = pendingSignature != null || linkedSignature == null
        mutable.value = IptvFixturesState(true, loading = !refreshed && grouped.isEmpty() && !result.missingKey && !result.noLeagues,
            rows = grouped.map { row -> IptvFixtureRow(row.section, row.day, row.fixtures.map { fixture ->
                val links = if (fixture.status == FixtureStatus.FINAL) emptyList() else linked[fixture.id].orEmpty()
                IptvFixtureItem(fixture, links, SportsFavourites.has(favourites, fixture), linking && links.isEmpty() && fixture.status != FixtureStatus.FINAL,
                    scheduleOnly(fixture))
            }) },
            failed = result.failed, missingKey = result.missingKey, noLeagues = result.noLeagues, showScores = showScores, favourites = favourites,
            reminders = live.reminders.value, spoilerKeys = spoilerKeys - revealed,
            recent = SportsCatchup.recent(result.fixtures, System.currentTimeMillis()).mapNotNull { fixture ->
                linked[fixture.id]?.takeIf { it.isNotEmpty() }?.let { IptvFixtureItem(fixture, it, SportsFavourites.has(favourites, fixture), scheduleOnly = scheduleOnly(fixture)) }
            }, hideSpoilers = preferences.hideSpoilers, recorded = recordedKeys, pending = pendingKeys())
    }

    private fun scheduleOnly(fixture: SportsFixture): Boolean = fixture.source == SportsService.THESPORTSDB && fixture.sport !in SportsDbLive.SPORTS

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
    onRail: () -> Unit = {}, modifier: Modifier = Modifier, blocked: Boolean = false, toggle: FocusRequester? = null, restore: FocusRequester? = null,
    viewModel: IptvSportsFixturesViewModel = hiltViewModel()) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, source, hiddenCategories) {
        if (source != null) viewModel.show(source, hiddenCategories, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { viewModel.hide() }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(TICK_MILLIS); value = System.currentTimeMillis() } }
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    if (!state.enabled || source == null) return
    fun open(item: IptvFixtureItem) { if (item.links.size == 1) onWatch(item.links.first().row) else viewModel.prompt(IptvSportPrompt.Channels(item.fixture.key)) }
    fun hold(item: IptvFixtureItem) { if (state.spoiler(item.fixture)) viewModel.reveal(item.fixture.key) else viewModel.prompt(IptvSportPrompt.Options(item.fixture.key)) }
    fun options(item: IptvFixtureItem) = viewModel.prompt(IptvSportPrompt.Options(item.fixture.key))
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
        Column(modifier.fillMaxWidth().height(SPORT_ROW_HEIGHT + SPORT_ROW_BLEED), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        val window = remember(now / TIMELINE_HOUR_MILLIS) { sportTimelineWindow(now) }
        val lanes = remember(state.rows, window) { SportsTimeline.lanes(state.items.map { it.fixture }, window.first, window.second) }
        var lastKey by remember { mutableIntStateOf(0) }
        var lastSlot by remember { mutableIntStateOf(0) }
        val slots = state.rows.size + if (lanes.isNotEmpty()) 1 else 0
        val rowsState = rememberLazyListState()
        val inner = LocalBringIntoViewSpec.current
        CompositionLocalProvider(LocalBringIntoViewSpec provides SportRowsSpec) {
            LazyColumn(modifier.fillMaxWidth().height(SPORT_ROW_HEIGHT + SPORT_ROW_BLEED)
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN) lastKey = native.keyCode
                    toggle != null && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && native.action == AndroidKeyEvent.ACTION_DOWN && lastSlot >= slots - 1 &&
                        runCatching { toggle.requestFocus() }.isSuccess
                }
                .onFocusChanged { if (!it.hasFocus && lastKey == AndroidKeyEvent.KEYCODE_DPAD_DOWN) viewModel.focusFixture(null) }
                .focusProperties { onEnter = { if (blocked) cancelFocusChange() } }
                .focusGroup(), state = rowsState, contentPadding = PaddingValues(bottom = SPORT_ROW_BLEED), verticalArrangement = Arrangement.spacedBy(SPORT_ROW_GAP)) {
                var slot = 0
                state.rows.forEachIndexed { index, row ->
                    val at = slot++
                    item(key = row.key) {
                        SportSlot(at, rowsState, inner, onFocus = { lastSlot = at }) {
                            SportFixtureRow(row, state, playingId, message.takeIf { index == 0 && state.failed }, onRail, blocked, restore.takeIf { at == lastSlot },
                                onScores = if (index == 0) ({ viewModel.prompt(IptvSportPrompt.Scores) }) else null,
                                onFocused = { viewModel.focusFixture(it.fixture.key) }, onClick = { open(it) }, onHold = { hold(it) }, onMenu = { options(it) })
                        }
                    }
                    if (index == 0 && lanes.isNotEmpty()) {
                        val ruler = slot++
                        item(key = TIMELINE_KEY) {
                            SportSlot(ruler, rowsState, inner, restore.takeIf { ruler == lastSlot }, onFocus = { lastSlot = ruler }) {
                                IptvSportTimeline(lanes, window, now, state, playingId, Modifier.fillMaxWidth().height(SPORT_ROW_HEIGHT), onRail,
                                    onFocused = { viewModel.focusFixture(it.fixture.key) }, onOpen = { open(it) }, onHold = { hold(it) }, onMenu = { options(it) })
                            }
                        }
                    }
                }
            }
        }
    }
    when (val current = prompt) {
        is IptvSportPrompt.Channels -> state.item(current.key)?.let { item ->
            FixtureChannelsDialog(item, onWatch = { viewModel.prompt(null); onWatch(it) }, onDismiss = { viewModel.prompt(null) },
                pending = item.fixture.key in state.pending, onPending = { viewModel.togglePending(item.fixture) })
        } ?: LaunchedEffect(current) { viewModel.prompt(null) }
        is IptvSportPrompt.Options -> state.item(current.key)?.let { item ->
            FixtureOptionsDialog(item, state, onToggle = { team -> viewModel.toggleFavourite(item.fixture, team) },
                onReminder = { viewModel.toggleReminder(item.fixture) }, onWatch = { viewModel.prompt(null); open(item) },
                onRecord = { viewModel.prompt(null); viewModel.record(item) }, onCancelRecording = { viewModel.prompt(null); viewModel.cancelRecording(item.fixture) },
                onPending = { viewModel.togglePending(item.fixture) }, onDismiss = { viewModel.prompt(null) })
        } ?: LaunchedEffect(current) { viewModel.prompt(null) }
        is IptvSportPrompt.Watch -> LaunchedEffect(current) { viewModel.prompt(null); onWatch(current.row) }
        IptvSportPrompt.Scores -> IptvAllScores(state, playingId, onWatch = { viewModel.prompt(null); onWatch(it) }, onReveal = viewModel::reveal,
            onReminder = viewModel::toggleReminder, onDismiss = { viewModel.prompt(null) })
        null -> Unit
    }
}

@Composable
private fun SportSlot(index: Int, list: LazyListState, spec: BringIntoViewSpec, restore: FocusRequester? = null, onFocus: () -> Unit = {},
    content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        Box((restore?.let { Modifier.focusRequester(it) } ?: Modifier).onFocusChanged { if (it.hasFocus) { onFocus(); scope.launch { list.animateScrollToItem(index) } } }) { content() }
    }
}

private val SportRowsSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@Composable
private fun RowMessage(message: Int, warning: Boolean) {
    Text(stringResource(message), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = if (warning) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary)
}

@Composable
private fun SportFixtureRow(row: IptvFixtureRow, state: IptvFixturesState, playingId: String?, message: Int?, onRail: () -> Unit, blocked: Boolean,
    restore: FocusRequester?, onScores: (() -> Unit)?,
    onFocused: (IptvFixtureItem) -> Unit, onClick: (IptvFixtureItem) -> Unit, onHold: (IptvFixtureItem) -> Unit, onMenu: (IptvFixtureItem) -> Unit) {
    val listState = rememberLazyListState()
    var lastFocused by remember(row.key) { mutableIntStateOf(0) }
    val requesters = remember(row.key) { mutableMapOf<Int, FocusRequester>() }
    fun requester(index: Int) = requesters.getOrPut(index) { FocusRequester() }
    Column(Modifier.fillMaxWidth().height(SPORT_ROW_HEIGHT), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (row.section == FixtureSection.LIVE) Box(Modifier.size(8.dp).clip(CircleShape).background(NuvioTheme.colors.Error))
            Text(rowTitle(row), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
            Text("${row.items.size}", style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
            if (onScores != null) SportChip(stringResource(R.string.iptv_sport5_section_all_scores), onScores, height = 22.dp,
                icon = Icons.AutoMirrored.Filled.List, blocked = blocked)
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
                SportFixtureCard(item, state.hidden(item.fixture), state.spoiler(item.fixture), state.favourites, item.fixture.key in state.reminders,
                    playing = item.links.any { it.row.item.channel.id == playingId },
                    modifier = Modifier.focusRequester(requester(index)).then(if (restore != null && index == lastFocused) Modifier.focusRequester(restore) else Modifier),
                    onFocused = { lastFocused = index; onFocused(item) }, onClick = { onClick(item) }, onHold = { onHold(item) }, onMenu = { onMenu(item) }, blocked = blocked)
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

internal fun sportFollowable(fixture: SportsFixture): List<FixtureTeam> {
    val teams = listOfNotNull(fixture.home, fixture.away)
    if (teams.isNotEmpty()) return teams
    return (fixture.sportDetail as? SportsDetail.Golf)?.leaders.orEmpty().take(FOLLOW_GOLFERS).map { FixtureTeam(it.name, it.shortName) }
}

internal fun sportCanRemind(fixture: SportsFixture, now: Long = System.currentTimeMillis()): Boolean =
    fixture.status == FixtureStatus.SCHEDULED && fixture.startMillis > now

@Composable
private fun FixtureOptionsDialog(item: IptvFixtureItem, state: IptvFixturesState, onToggle: (FixtureTeam) -> Unit, onReminder: () -> Unit, onWatch: () -> Unit,
    onRecord: () -> Unit, onCancelRecording: () -> Unit, onPending: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val fixture = item.fixture
    val teams = sportFollowable(fixture)
    val remind = sportCanRemind(fixture)
    val reminded = fixture.key in state.reminders
    val watch = fixture.status != FixtureStatus.FINAL
    val record = SportsPendingRecords.wanted(fixture, System.currentTimeMillis())
    NuvioDialog(onDismiss = onDismiss, title = sportTitle(fixture),
        subtitle = if (teams.isEmpty()) null else stringResource(R.string.iptv_sport2_favourite_subtitle), width = 560.dp) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (watch) SettingsActionRow(title = stringResource(R.string.iptv_sport_choose_channel), subtitle = null,
                onClick = onWatch, leadingIcon = Icons.Filled.LiveTv, trailingIcon = null, modifier = Modifier.focusRequester(first))
            if (remind) SettingsActionRow(title = stringResource(if (reminded) R.string.iptv_sport5_section_cancel_reminder else R.string.iptv_sport5_section_remind),
                subtitle = if (reminded) stringResource(R.string.iptv_sport5_section_reminder_set) else "${sportDayLabel(fixture.startMillis)} · ${clock(fixture.startMillis)}",
                onClick = onReminder, leadingIcon = if (reminded) Icons.Filled.NotificationsOff else Icons.Filled.NotificationsActive, trailingIcon = null,
                modifier = if (!watch) Modifier.focusRequester(first) else Modifier)
            if (record) FixtureRecordRow(item, fixture.key in state.recorded, fixture.key in state.pending, onRecord, onCancelRecording, onPending,
                if (!watch && !remind) Modifier.focusRequester(first) else Modifier)
            teams.forEachIndexed { index, team ->
                SettingsToggleRow(title = team.name, subtitle = stringResource(R.string.iptv_sport2_favourite_team),
                    checked = SportsFavourites.key(fixture.league, team) in state.favourites, onToggle = { onToggle(team) },
                    modifier = if (index == 0 && !watch && !remind && !record) Modifier.focusRequester(first) else Modifier)
            }
            if (!watch && !remind && !record && teams.isEmpty()) NuvioActionPill(onDismiss, Modifier.focusRequester(first)) { Text(stringResource(R.string.iptv_sport_close)) }
        }
    }
}

@Composable
private fun FixtureRecordRow(item: IptvFixtureItem, recorded: Boolean, pending: Boolean, onRecord: () -> Unit, onCancelRecording: () -> Unit, onPending: () -> Unit,
    modifier: Modifier) {
    val link = item.links.firstOrNull()
    when {
        recorded -> SettingsActionRow(title = stringResource(R.string.iptv_sport9_cancel_recording), subtitle = stringResource(R.string.iptv_sport9_recording_set),
            onClick = onCancelRecording, leadingIcon = Icons.Filled.Cancel, trailingIcon = null, modifier = modifier)
        link != null -> SettingsActionRow(title = stringResource(R.string.iptv_sport9_record),
            subtitle = stringResource(R.string.iptv_sport9_record_on, channelName(link.row)), onClick = onRecord, leadingIcon = Icons.Filled.FiberManualRecord,
            trailingIcon = null, modifier = modifier)
        pending -> SettingsActionRow(title = stringResource(R.string.iptv_sport9_record_waiting_cancel), subtitle = stringResource(R.string.iptv_sport9_record_waiting),
            onClick = onPending, leadingIcon = Icons.Filled.Cancel, trailingIcon = null, modifier = modifier)
        else -> SettingsActionRow(title = stringResource(R.string.iptv_sport9_record_when_found), subtitle = stringResource(R.string.iptv_sport9_record_when_found_subtitle),
            onClick = onPending, leadingIcon = Icons.Filled.Schedule, trailingIcon = null, modifier = modifier)
    }
}

@Composable
internal fun FixtureChannelsDialog(item: IptvFixtureItem, onWatch: (IptvListedChannel) -> Unit, onDismiss: () -> Unit, pending: Boolean = false,
    onPending: (() -> Unit)? = null) {
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
                    subtitle = link.programme?.let { stringResource(R.string.iptv_sport9_link_guide, timeRange(it), title(it)) }
                        ?: link.broadcaster?.let { stringResource(R.string.iptv_sport9_link_broadcaster, it) },
                    onClick = { onWatch(link.row) }, leadingIcon = Icons.Filled.LiveTv, trailingIcon = null,
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier)
            }
            val waitable = onPending != null && item.links.isEmpty() && SportsPendingRecords.wanted(fixture, System.currentTimeMillis())
            if (waitable && onPending != null) SettingsActionRow(
                title = stringResource(if (pending) R.string.iptv_sport9_record_waiting_cancel else R.string.iptv_sport9_record_when_found),
                subtitle = stringResource(if (pending) R.string.iptv_sport9_record_waiting else R.string.iptv_sport9_record_when_found_subtitle),
                onClick = onPending, leadingIcon = if (pending) Icons.Filled.Cancel else Icons.Filled.Schedule, trailingIcon = null,
                modifier = Modifier.focusRequester(first))
            if (item.links.isEmpty()) NuvioActionPill(onDismiss, if (waitable) Modifier else Modifier.focusRequester(first)) { Text(stringResource(R.string.iptv_sport_close)) }
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
                Box(Modifier.padding(start = 12.dp, top = 10.dp).width(72.dp).height(10.dp).clip(SportPlaceholderShape).background(brush))
                Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(2) {
                        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(5.dp).fillMaxHeight().background(brush))
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(32.dp).clip(CircleShape).background(brush))
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.width(72.dp).height(14.dp).clip(SportPlaceholderShape).background(brush))
                            Spacer(Modifier.weight(1f))
                            Box(Modifier.padding(end = 12.dp).width(28.dp).height(18.dp).clip(SportPlaceholderShape).background(brush))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.width(96.dp).height(10.dp).clip(SportPlaceholderShape).background(brush))
                }
            }
        }
    }
}

internal val SportPlaceholderShape = RoundedCornerShape(4.dp)
private val SPORT_ROW_HEIGHT = 168.dp
private val SPORT_ROW_GAP = 12.dp
private val SPORT_ROW_BLEED = 8.dp
private const val PLACEHOLDER_CARDS = 6
private const val TICK_MILLIS = 60_000L
private const val TIMELINE_KEY = "\u0000timeline"
private const val FOLLOW_GOLFERS = 5
