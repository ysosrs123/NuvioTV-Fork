@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.FixtureTeam
import com.nuvio.tv.core.iptv.SportsChangeKind
import com.nuvio.tv.core.iptv.SportsFavourites
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsNuvioAlert
import com.nuvio.tv.core.iptv.SportsRecordRules
import com.nuvio.tv.core.iptv.SportsService
import com.nuvio.tv.core.iptv.SportsTeams
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.recording.IptvRecorder
import com.nuvio.tv.data.iptv.IptvFixtureLink
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvSportsFixturesRepository
import com.nuvio.tv.data.iptv.IptvSportsPreferences
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.V2Atmosphere
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IptvTeamState(val key: String, val name: String, val league: String, val team: FixtureTeam? = null, val loading: Boolean = true,
    val enabled: Boolean = true, val following: Boolean = false, val current: SportsFixture? = null, val home: Boolean? = null,
    val currentHidden: Boolean = false, val upcoming: List<SportsFixture> = emptyList(), val last: SportsFixture? = null, val lastHidden: Boolean = false,
    val links: List<IptvFixtureLink> = emptyList(), val reminder: Boolean = false, val recording: IptvTeamRecording = IptvTeamRecording.NONE,
    val rule: Boolean = false, val early: Int = 0, val late: Int = 0, val guideDays: Int = 3, val kinds: Set<SportsChangeKind> = emptySet(),
    val hideSpoilers: Boolean = true, val nuvioAlert: SportsNuvioAlert = SportsNuvioAlert.POPUP, val lead: Int = 0, val saveTo: String? = null)

@HiltViewModel
class IptvTeamViewModel @Inject constructor(savedState: SavedStateHandle, private val sports: IptvSportsNuvio, private val preferences: IptvSportsPreferences,
    private val repository: IptvSportsFixturesRepository, private val recorder: IptvRecorder, private val profiles: ProfileManager) : ViewModel() {
    private val key = savedState.get<String>("team").orEmpty().take(200)
    private val parsed = SportsFavourites.parse(key)
    private val mutable = MutableStateFlow(IptvTeamState(key, parsed?.second ?: key, parsed?.first?.let { SportsLeagues.byId(it)?.name ?: it }.orEmpty()))
    val state = mutable.asStateFlow()
    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message: SharedFlow<String> = messages.asSharedFlow()
    private var fixtures = emptyList<SportsFixture>()
    private var links = emptyMap<String, List<IptvFixtureLink>>()
    private var spoilers = emptySet<String>()
    private var loaded = false
    private var refresher: Job? = null

    init {
        viewModelScope.launch { sports.rules.collect { publish() } }
        viewModelScope.launch { sports.reminders.collect { publish() } }
        viewModelScope.launch { recorder.all.collect { publish() } }
        viewModelScope.launch { mutable.value = mutable.value.copy(saveTo = withContext(Dispatchers.IO) { runCatching { recorder.freeSpace()?.drive }.getOrNull() }) }
    }

    fun active(on: Boolean) {
        if (!on) { refresher?.cancel(); refresher = null; return }
        if (refresher?.isActive != true) refresher = viewModelScope.launch { while (isActive) { load(); delay(REFRESH_MILLIS) } }
    }

    private suspend fun load() {
        if (parsed == null || preferences.service == SportsService.OFF) { loaded = true; publish(); return }
        try {
            val now = System.currentTimeMillis()
            fixtures = withContext(Dispatchers.IO) { repository.load(now, ZoneId.systemDefault(), true, preferences.favouriteTeams + key, followedOnly = true).fixtures }
            val games = SportsTeams.games(key, fixtures, now)
            val wanted = listOfNotNull(games?.current) + games?.upcoming.orEmpty().take(MAX_LINKED)
            links = sports.link(sports.sources(profiles.activeProfileId.value), wanted, now)
            spoilers = sports.spoilersFor(fixtures, profiles.activeProfileId.value)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { IptvLog.failure("team page", error) }
        loaded = true
        publish()
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        val games = SportsTeams.games(key, fixtures, now)
        val current = games?.current
        mutable.value = mutable.value.copy(team = games?.team ?: mutable.value.team, loading = !loaded, enabled = preferences.service != SportsService.OFF && parsed != null,
            following = key in preferences.favouriteTeams, current = current, home = games?.let { g -> current?.let { SportsTeams.home(g, it) } },
            currentHidden = current?.let { !preferences.showScores || it.key in spoilers } == true, upcoming = games?.upcoming.orEmpty(),
            last = games?.last, lastHidden = games?.last?.let { !preferences.showScores || it.key in spoilers } == true,
            links = current?.let { links[it.key] }.orEmpty(), reminder = current?.let { sports.hasReminder(it.key) } == true,
            recording = sports.recording(current, key), rule = key in sports.rules.value, early = sports.earlyMinutes, late = sports.lateMinutes,
            guideDays = sports.guideDays, kinds = preferences.alertKinds, hideSpoilers = preferences.hideSpoilers, nuvioAlert = preferences.nuvioAlert,
            lead = preferences.reminderLeadMinutes)
    }

    fun toggleFollow() { sports.follow(key, key !in preferences.favouriteTeams); publish() }

    fun toggleRule() { sports.setRule(key, key !in sports.rules.value); publish() }

    fun toggleReminder(fixture: SportsFixture) { sports.toggleReminder(fixture) }

    fun record(fixture: SportsFixture, link: IptvFixtureLink) { viewModelScope.launch { messages.emit(sports.record(fixture, link)) } }

    fun watch(link: IptvFixtureLink) = sports.watch(link.row)

    fun toggleKind(kind: SportsChangeKind) {
        val kinds = preferences.alertKinds
        preferences.alertKinds = if (kind in kinds) kinds - kind else kinds + kind
        publish()
    }

    fun toggleSpoilers() { preferences.hideSpoilers = !preferences.hideSpoilers; publish() }

    private companion object {
        const val REFRESH_MILLIS = 60_000L
        const val MAX_LINKED = 4
    }
}

@Composable
fun IptvTeamScreen(viewModel: IptvTeamViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.active(true)
            if (event == Lifecycle.Event.ON_STOP) viewModel.active(false)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); viewModel.active(false) }
    }
    LaunchedEffect(viewModel) { viewModel.message.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        if (!LocalIptvAppearance.current.plainBackground) LocalV2Appearance.current?.let { V2Atmosphere(rich = false, background = it.settingsBackground) }
        Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                state.team?.let { TeamLogo(it, 64.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(state.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(state.league, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
                }
                NuvioActionPill(viewModel::toggleFollow, Modifier.focusRequester(first), enabled = state.enabled) {
                    Text(stringResource(if (state.following) R.string.iptv_sport5_nuvio_following else R.string.iptv_sport5_nuvio_follow))
                }
            }
            if (!state.enabled) {
                Text(stringResource(R.string.iptv_sport5_nuvio_sport_off), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                return@Column
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    NextPanel(state, viewModel)
                    ThenPanel(state)
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TeamPanel(stringResource(R.string.iptv_sport5_nuvio_record_every)) {
                        SettingsToggleRow(title = stringResource(R.string.iptv_sport5_nuvio_record_every_rule), subtitle = stringResource(R.string.iptv_sport5_nuvio_record_every_subtitle),
                            checked = state.rule, onToggle = viewModel::toggleRule)
                        InfoRow(stringResource(R.string.iptv_sport5_nuvio_start_early), stringResource(R.string.iptv_sport5_nuvio_live_tv_minutes, state.early))
                        InfoRow(stringResource(R.string.iptv_sport5_nuvio_keep_going), stringResource(R.string.iptv_sport5_nuvio_finals_minutes, state.late,
                            (SportsRecordRules.FINAL_EXTRA_MILLIS / 60_000L).toInt()))
                        InfoRow(stringResource(R.string.iptv_sport5_nuvio_save_to), state.saveTo ?: stringResource(R.string.iptv_sport5_nuvio_live_tv_setting))
                        InfoRow(stringResource(R.string.iptv_sport5_nuvio_no_channel_by), stringResource(R.string.iptv_sport5_nuvio_tell_me))
                    }
                    TeamPanel(stringResource(R.string.iptv_sport5_nuvio_reminders)) {
                        InfoRow(stringResource(R.string.iptv_sport5_nuvio_before_start), if (state.lead == 0) stringResource(R.string.iptv_sport4_reminder_at_start)
                            else stringResource(R.string.iptv_sport4_reminder_minutes, state.lead))
                        InfoRow(stringResource(R.string.iptv_sport5_nuvio_if_watching), stringResource(when (state.nuvioAlert) {
                            SportsNuvioAlert.POPUP -> R.string.iptv_sport4_nuvio_popup
                            SportsNuvioAlert.CHIP -> R.string.iptv_sport4_nuvio_chip
                            SportsNuvioAlert.OFF -> R.string.iptv_sport4_nuvio_off
                        }))
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TeamPanel(stringResource(R.string.iptv_sport5_nuvio_alerts), stringResource(R.string.iptv_sport5_nuvio_alerts_subtitle)) {
                        listOf(SportsChangeKind.STARTED to R.string.iptv_sport4_kind_started, SportsChangeKind.SCORED to R.string.iptv_sport4_kind_scored,
                            SportsChangeKind.FINISHED to R.string.iptv_sport4_kind_finished).forEach { (kind, label) ->
                            SettingsToggleRow(title = stringResource(label), subtitle = null, checked = kind in state.kinds, onToggle = { viewModel.toggleKind(kind) })
                        }
                    }
                    TeamPanel(stringResource(R.string.iptv_sport5_nuvio_no_spoilers)) {
                        Text(stringResource(R.string.iptv_sport5_nuvio_no_spoilers_text, state.name), style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary)
                        SettingsToggleRow(title = stringResource(R.string.iptv_sport4_hide_spoilers), subtitle = null, checked = state.hideSpoilers,
                            onToggle = viewModel::toggleSpoilers)
                    }
                }
            }
        }
    }
}

@Composable
private fun NextPanel(state: IptvTeamState, viewModel: IptvTeamViewModel) {
    val fixture = state.current
    TeamPanel(null) {
        if (fixture == null) {
            Text(stringResource(if (state.loading) R.string.iptv_sport_loading else R.string.iptv_sport5_nuvio_no_game, state.guideDays),
                style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
            return@TeamPanel
        }
        val live = fixture.status == FixtureStatus.LIVE
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (live) R.string.iptv_sport5_nuvio_now else R.string.iptv_sport5_nuvio_next), style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, color = if (live) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary, modifier = Modifier.weight(1f))
            Text(iptvFixtureState(fixture, state.currentHidden), style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            fixture.home?.let { TeamLogo(it, 30.dp) }
            Text(iptvScoreLine(fixture, state.currentHidden), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            fixture.away?.let { TeamLogo(it, 30.dp) }
        }
        val side = state.home?.let { stringResource(if (it) R.string.iptv_sport2_home else R.string.iptv_sport2_away) }
        listOfNotNull(fixture.venue, side).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val note = when {
            state.links.isNotEmpty() -> stringResource(R.string.iptv_sport5_nuvio_channel_known, channelName(state.links.first().row) +
                if (state.links.size > 1) " +${state.links.size - 1}" else "")
            live -> stringResource(R.string.iptv_sport_no_channel)
            else -> stringResource(R.string.iptv_sport5_nuvio_channel_unknown, state.guideDays, sportDayLabel(fixture.startMillis))
        }
        Text(note, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.fillMaxWidth()
            .background(NuvioTheme.colors.TextPrimary.copy(alpha = .06f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val link = state.links.firstOrNull()
            if (link != null && (live || fixture.startMillis - System.currentTimeMillis() <= WATCH_AHEAD_MILLIS)) NuvioActionPill({ viewModel.watch(link) }) {
                Text(stringResource(R.string.iptv_sport5_nuvio_watch_game))
            }
            if (!live) NuvioActionPill({ viewModel.toggleReminder(fixture) }) {
                Text(stringResource(if (state.reminder) R.string.iptv_sport5_nuvio_reminder_set else R.string.iptv_sport5_nuvio_remind_me))
            }
            when (state.recording) {
                IptvTeamRecording.SET, IptvTeamRecording.RECORDING -> Text(stringResource(if (state.recording == IptvTeamRecording.SET) R.string.iptv_sport5_nuvio_recording_set
                    else R.string.iptv_sport5_nuvio_recording_now), style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.Error,
                    modifier = Modifier.align(Alignment.CenterVertically))
                else -> if (link != null) NuvioActionPill({ viewModel.record(fixture, link) }) { Text(stringResource(R.string.iptv_sport5_nuvio_record_game)) }
                    else if (state.recording == IptvTeamRecording.RULE) Text(stringResource(R.string.iptv_sport5_nuvio_records_all), style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.TextSecondary, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
    }
}

@Composable
private fun ThenPanel(state: IptvTeamState) {
    val last = state.last
    if (state.upcoming.isEmpty() && last == null) return
    TeamPanel(stringResource(R.string.iptv_sport5_nuvio_then)) {
        state.upcoming.take(MAX_THEN).forEach { fixture -> InfoRow(sportTitle(fixture), "${sportDayLabel(fixture.startMillis)} · ${clock(fixture.startMillis)}") }
        if (last != null) InfoRow(stringResource(R.string.iptv_sport5_nuvio_last_result), if (state.lastHidden) stringResource(R.string.iptv_sport5_nuvio_result_hidden)
            else "${iptvScoreLine(last, false)} · ${iptvFixtureState(last, false)}")
    }
}

@Composable
private fun TeamPanel(title: String?, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().iptvPanel(RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary)
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val MAX_THEN = 4
private const val WATCH_AHEAD_MILLIS = 30L * 60 * 1000
