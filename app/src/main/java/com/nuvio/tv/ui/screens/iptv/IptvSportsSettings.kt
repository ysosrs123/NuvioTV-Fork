@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.SportsAlertGames
import com.nuvio.tv.core.iptv.SportsChangeKind
import com.nuvio.tv.core.iptv.SportsChannelPick
import com.nuvio.tv.core.iptv.SportsChannelPicks
import com.nuvio.tv.core.iptv.SportsChannelRules
import com.nuvio.tv.core.iptv.SportsChannelSource
import com.nuvio.tv.core.iptv.SportsDbLeague
import com.nuvio.tv.core.iptv.SportsDbLeagues
import com.nuvio.tv.core.iptv.SportsFavourites
import com.nuvio.tv.core.iptv.SportsLeague
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsLogos
import com.nuvio.tv.core.iptv.SportsNuvioAlert
import com.nuvio.tv.core.iptv.SportsOverlayStyle
import com.nuvio.tv.core.iptv.SportsPickKind
import com.nuvio.tv.core.iptv.SportsPickList
import com.nuvio.tv.core.iptv.SportsSources
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvSportsFixturesRepository
import com.nuvio.tv.data.iptv.IptvSportsPickOption
import com.nuvio.tv.data.iptv.IptvSportsPreferences
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsMultiChoiceDialog
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IptvSportsSettingsState(val enabled: Boolean = false, val leagues: Set<String> = emptySet(), val hasKey: Boolean = false,
    val showScores: Boolean = true, val favourites: List<String> = emptyList(), val overlayStyle: SportsOverlayStyle = SportsOverlayStyle.GLANCE,
    val alertGames: SportsAlertGames = SportsAlertGames.FOLLOWED_AND_CLOSE, val alertHoldSeconds: Int = IptvSportsPreferences.DEFAULT_HOLD,
    val skipOnScreen: Boolean = true, val alertKinds: Set<SportsChangeKind> = SportsChangeKind.entries.toSet(),
    val nuvioAlert: SportsNuvioAlert = SportsNuvioAlert.POPUP, val nuvioQuietEndMinutes: Int = IptvSportsPreferences.DEFAULT_QUIET,
    val reminderLeadMinutes: Int = IptvSportsPreferences.DEFAULT_LEAD, val hideSpoilers: Boolean = true, val logos: SportsLogos = SportsLogos.ESPN,
    val custom: List<SportsLeague> = emptyList(), val channelSource: SportsChannelSource = SportsChannelSource.BOTH,
    val rules: SportsChannelRules = SportsChannelRules()) {
    val offered: List<SportsLeague> get() = SportsLeagues.ALL + custom
}

data class IptvSportsPickState(val query: String = "", val categories: List<SportsChannelPick> = emptyList(),
    val channels: List<IptvSportsPickOption> = emptyList(), val searching: Boolean = false)

data class IptvSportsLeagueSearch(val query: String = "", val loading: Boolean = false, val failed: Boolean = false, val results: List<SportsDbLeague> = emptyList())

@HiltViewModel
class IptvSportsSettingsViewModel @Inject constructor(private val preferences: IptvSportsPreferences, private val repository: IptvSportsFixturesRepository,
    private val profiles: ProfileManager) : ViewModel() {
    private val mutable = MutableStateFlow(IptvSportsSettingsState())
    val state = mutable.asStateFlow()
    private val searching = MutableStateFlow(IptvSportsLeagueSearch())
    val search = searching.asStateFlow()
    private var leagueList: Deferred<List<SportsDbLeague>?>? = null
    private var searchJob: Job? = null
    private val pickState = MutableStateFlow(IptvSportsPickState())
    val picks = pickState.asStateFlow()
    private var pickJob: Job? = null
    private var categoryJob: Job? = null

    init { reload() }

    fun reload() {
        mutable.value = IptvSportsSettingsState(preferences.enabled, preferences.leagues, preferences.hasKey, preferences.showScores,
            preferences.favouriteTeams.sortedBy { SportsFavourites.parse(it)?.second?.lowercase() }, preferences.overlayStyle, preferences.alertGames,
            preferences.alertHoldSeconds, preferences.skipOnScreen, preferences.alertKinds, preferences.nuvioAlert, preferences.nuvioQuietEndMinutes,
            preferences.reminderLeadMinutes, preferences.hideSpoilers, preferences.logos, preferences.customLeagues, preferences.channelSource,
            preferences.channelRules)
    }

    fun openPicks() {
        pickState.value = IptvSportsPickState()
        categoryJob?.cancel()
        categoryJob = viewModelScope.launch {
            val categories = try { repository.pickCategories(profiles.activeProfileId.value) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { IptvLog.failure("sports pick categories", error); emptyList() }
            pickState.update { it.copy(categories = categories) }
        }
    }

    fun searchPicks(query: String) {
        pickState.update { it.copy(query = query) }
        pickJob?.cancel()
        if (query.trim().length < 2) { pickState.update { it.copy(channels = emptyList(), searching = false) }; return }
        pickJob = viewModelScope.launch {
            delay(PICK_DELAY)
            pickState.update { it.copy(searching = true) }
            val found = try { repository.pickChannels(profiles.activeProfileId.value, query) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { IptvLog.failure("sports pick channels", error); emptyList() }
            pickState.update { it.copy(channels = found, searching = false) }
        }
    }

    fun closePicks() { pickJob?.cancel(); categoryJob?.cancel(); pickState.value = IptvSportsPickState() }

    fun togglePick(which: SportsPickList, pick: SportsChannelPick) {
        val rules = preferences.channelRules
        preferences.channelRules = SportsChannelPicks.set(rules, which, SportsChannelPicks.toggle(rules.list(which), pick))
        reload()
    }

    fun setEnabled(value: Boolean) { preferences.enabled = value; reload() }

    fun setLogos(value: SportsLogos) { preferences.logos = value; reload() }

    fun setChannelSource(value: SportsChannelSource) { preferences.channelSource = value; reload() }

    fun searchLeagues(query: String) {
        searching.update { it.copy(query = query) }
        val list = leagueList ?: viewModelScope.async {
            try { repository.leagueList(System.currentTimeMillis()) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("sports leagues", error); null }
        }.also { leagueList = it }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (!list.isCompleted) searching.update { it.copy(loading = true, failed = false) }
            val all = list.await()
            if (all == null) { leagueList = null; searching.update { it.copy(loading = false, failed = true, results = emptyList()) }; return@launch }
            val results = withContext(Dispatchers.Default) { SportsDbLeagues.search(all, query) }
            searching.update { it.copy(loading = false, failed = false, results = results) }
        }
    }

    fun closeSearch() { searchJob?.cancel(); searching.value = IptvSportsLeagueSearch() }

    fun addLeague(item: SportsDbLeague) {
        val league = SportsDbLeagues.builtIn(item) ?: SportsDbLeagues.league(item).also { added ->
            preferences.customLeagues = preferences.customLeagues.filter { it.id != added.id } + added
        }
        preferences.leagues = preferences.leagues + league.id
        reload()
    }

    fun setOverlayStyle(value: SportsOverlayStyle) { preferences.overlayStyle = value; reload() }

    fun setAlertGames(value: SportsAlertGames) { preferences.alertGames = value; reload() }

    fun setAlertHold(seconds: Int) { preferences.alertHoldSeconds = seconds; reload() }

    fun setSkipOnScreen(value: Boolean) { preferences.skipOnScreen = value; reload() }

    fun setAlertKinds(value: List<SportsChangeKind>) { preferences.alertKinds = value.toSet(); reload() }

    fun setNuvioAlert(value: SportsNuvioAlert) { preferences.nuvioAlert = value; reload() }

    fun setQuietEnd(minutes: Int) { preferences.nuvioQuietEndMinutes = minutes; reload() }

    fun setReminderLead(minutes: Int) { preferences.reminderLeadMinutes = minutes; reload() }

    fun setHideSpoilers(value: Boolean) { preferences.hideSpoilers = value; reload() }

    fun setShowScores(value: Boolean) { preferences.showScores = value; reload() }

    fun keepFavourites(kept: List<String>) { preferences.favouriteTeams = preferences.favouriteTeams.filter { it in kept }.toSet(); reload() }

    fun setLeagues(chosen: List<String>) {
        preferences.customLeagues = preferences.customLeagues.filter { it.id in chosen }
        preferences.leagues = chosen.toSet()
        reload()
    }

    fun saveKey(value: String, done: () -> Unit) {
        if (!IptvSportsPreferences.validKey(value)) return
        viewModelScope.launch {
            leagueList = null
            try { withContext(Dispatchers.IO) { preferences.setKey(value) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("sports key save", error) }
            reload(); done()
        }
    }

    fun removeKey() { leagueList = null; preferences.setKey(null); reload() }
}

private enum class IptvSportsChoice { LEAGUES, SEARCH, LOGOS, CHANNELS, KEY, FAVOURITES, OVERLAY, GAMES, HOLD, KINDS, NUVIO, QUIET, LEAD, PICKS }

@Composable
fun IptvSportsSettingsSection(viewModel: IptvSportsSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<IptvSportsChoice?>(null) }
    var pickList by remember { mutableStateOf(SportsPickList.ALWAYS) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroupCard(title = stringResource(R.string.iptv_live_sports)) {
            SettingsToggleRow(title = stringResource(R.string.iptv_sport7_fixtures), subtitle = stringResource(R.string.iptv_sport7_fixtures_subtitle),
                checked = state.enabled, onToggle = { viewModel.setEnabled(!state.enabled) })
            if (state.enabled) {
                val chosen = state.offered.filter { it.id in state.leagues }
                val keyNeeded = !state.hasKey && chosen.any(SportsSources::needsKey)
                SettingsActionRow(title = stringResource(R.string.iptv_sport_leagues), subtitle = stringResource(R.string.iptv_sport7_leagues_subtitle),
                    value = if (chosen.isEmpty()) stringResource(R.string.iptv_sport_leagues_none) else pluralStringResource(R.plurals.iptv_sport_leagues_chosen, chosen.size, chosen.size),
                    valueColor = if (keyNeeded) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary, onClick = { choosing = IptvSportsChoice.LEAGUES })
                SettingsActionRow(title = stringResource(R.string.iptv_sport7_add_league), subtitle = stringResource(R.string.iptv_sport7_add_league_subtitle),
                    onClick = { choosing = if (state.hasKey) IptvSportsChoice.SEARCH else IptvSportsChoice.KEY })
                SettingsActionRow(title = stringResource(R.string.iptv_sport_key), subtitle = stringResource(R.string.iptv_sport7_key_subtitle),
                    value = stringResource(if (state.hasKey) R.string.iptv_sport_key_set else R.string.iptv_sport_key_not_set),
                    valueColor = if (keyNeeded) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary, onClick = { choosing = IptvSportsChoice.KEY })
                SettingsActionRow(title = stringResource(R.string.iptv_sport7_logos), subtitle = stringResource(R.string.iptv_sport7_logos_subtitle),
                    value = stringResource(logosLabel(state.logos)), onClick = { choosing = IptvSportsChoice.LOGOS })
                SettingsActionRow(title = stringResource(R.string.iptv_sport9_channels), subtitle = stringResource(R.string.iptv_sport9_channels_subtitle),
                    value = stringResource(channelSourceLabel(state.channelSource)), onClick = { choosing = IptvSportsChoice.CHANNELS })
                SettingsToggleRow(title = stringResource(R.string.iptv_sport2_show_scores), subtitle = stringResource(R.string.iptv_sport2_show_scores_subtitle),
                    checked = state.showScores, onToggle = { viewModel.setShowScores(!state.showScores) })
                if (state.favourites.isNotEmpty()) SettingsActionRow(title = stringResource(R.string.iptv_sport2_favourite_teams),
                    subtitle = stringResource(R.string.iptv_sport2_favourite_hint), value = state.favourites.size.toString(),
                    onClick = { choosing = IptvSportsChoice.FAVOURITES })
            }
            Text(stringResource(R.string.iptv_sport7_credit), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.padding(start = 14.dp, top = 4.dp))
        }
        if (state.enabled) {
            SettingsGroupCard(title = stringResource(R.string.iptv_ui13_sport_channels), subtitle = stringResource(R.string.iptv_ui13_sport_channels_subtitle)) {
                SportsPickList.entries.forEach { which ->
                    val count = state.rules.list(which).size
                    SettingsActionRow(title = stringResource(pickTitle(which)), subtitle = stringResource(pickSubtitle(which)),
                        value = if (count == 0) stringResource(R.string.iptv_sport4_none) else pluralStringResource(R.plurals.iptv_ui13_sport_chosen, count, count),
                        onClick = { pickList = which; viewModel.openPicks(); choosing = IptvSportsChoice.PICKS })
                }
            }
            SettingsGroupCard(title = stringResource(R.string.iptv_sport4_while_watching), subtitle = stringResource(R.string.iptv_sport4_while_watching_subtitle)) {
                SettingsActionRow(title = stringResource(R.string.iptv_sport4_overlay), subtitle = stringResource(R.string.iptv_sport4_overlay_subtitle),
                    value = stringResource(overlayLabel(state.overlayStyle)), onClick = { choosing = IptvSportsChoice.OVERLAY })
                SettingsActionRow(title = stringResource(R.string.iptv_sport4_games), subtitle = stringResource(R.string.iptv_sport4_games_subtitle),
                    value = stringResource(gamesLabel(state.alertGames)), onClick = { choosing = IptvSportsChoice.GAMES })
                SettingsActionRow(title = stringResource(R.string.iptv_sport4_hold), subtitle = stringResource(R.string.iptv_sport4_hold_subtitle),
                    value = holdLabel(state.alertHoldSeconds), onClick = { choosing = IptvSportsChoice.HOLD })
                SettingsToggleRow(title = stringResource(R.string.iptv_sport4_skip), subtitle = stringResource(R.string.iptv_sport4_skip_subtitle),
                    checked = state.skipOnScreen, onToggle = { viewModel.setSkipOnScreen(!state.skipOnScreen) })
                SettingsActionRow(title = stringResource(R.string.iptv_sport4_kinds), subtitle = stringResource(R.string.iptv_sport4_kinds_subtitle),
                    value = when (state.alertKinds.size) {
                        0 -> stringResource(R.string.iptv_sport4_none)
                        SportsChangeKind.entries.size -> stringResource(R.string.iptv_sport4_all)
                        else -> SportsChangeKind.entries.filter { it in state.alertKinds }.map { stringResource(kindLabel(it)) }.joinToString(", ")
                    }, onClick = { choosing = IptvSportsChoice.KINDS })
            }
            SettingsGroupCard(title = stringResource(R.string.iptv_sport4_across_nuvio), subtitle = stringResource(R.string.iptv_sport4_across_nuvio_subtitle)) {
                SettingsActionRow(title = stringResource(R.string.iptv_sport4_nuvio_alert), subtitle = stringResource(R.string.iptv_sport4_nuvio_alert_subtitle),
                    value = stringResource(nuvioLabel(state.nuvioAlert)), onClick = { choosing = IptvSportsChoice.NUVIO })
                if (state.nuvioAlert != SportsNuvioAlert.OFF) SettingsActionRow(title = stringResource(R.string.iptv_sport4_quiet),
                    subtitle = stringResource(R.string.iptv_sport4_quiet_subtitle), value = quietLabel(state.nuvioQuietEndMinutes),
                    onClick = { choosing = IptvSportsChoice.QUIET })
                SettingsActionRow(title = stringResource(R.string.iptv_sport4_reminder_lead), subtitle = stringResource(R.string.iptv_sport4_reminder_lead_subtitle),
                    value = leadLabel(state.reminderLeadMinutes), onClick = { choosing = IptvSportsChoice.LEAD })
                SettingsToggleRow(title = stringResource(R.string.iptv_sport4_hide_spoilers), subtitle = stringResource(R.string.iptv_sport4_hide_spoilers_subtitle),
                    checked = state.hideSpoilers, onToggle = { viewModel.setHideSpoilers(!state.hideSpoilers) })
            }
        }
    }
    val dismiss = { choosing = null }
    IptvOpaqueDialogs { when (choosing) {
        IptvSportsChoice.LEAGUES -> SettingsMultiChoiceDialog(title = stringResource(R.string.iptv_sport_leagues),
            subtitle = stringResource(R.string.iptv_sport7_leagues_subtitle),
            options = state.offered.let { all -> all.map { it.sport }.distinct().let { order -> all.sortedBy { order.indexOf(it.sport) } } }
                .map { SettingsPickerOption(it.id, it.name, sportName(it.sport) + " · " + stringResource(sourceLabel(it, state.hasKey))) },
            selectedValues = state.offered.filter { it.id in state.leagues }.map { it.id },
            onValuesSelected = { viewModel.setLeagues(it); dismiss() }, onDismiss = dismiss, maxHeight = 460.dp)
        IptvSportsChoice.SEARCH -> {
            val search by viewModel.search.collectAsStateWithLifecycle()
            SportsLeagueSearchDialog(search, state.leagues, onQuery = viewModel::searchLeagues, onAdd = viewModel::addLeague,
                onDismiss = { viewModel.closeSearch(); dismiss() })
        }
        IptvSportsChoice.LOGOS -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_sport7_logos),
            subtitle = stringResource(R.string.iptv_sport7_logos_subtitle),
            options = SportsLogos.entries.map { SettingsPickerOption(it, stringResource(logosLabel(it)), stringResource(logosDescription(it))) },
            selectedValue = state.logos, onDismiss = dismiss, width = 560.dp, onOptionSelected = { viewModel.setLogos(it); dismiss() })
        IptvSportsChoice.CHANNELS -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_sport9_channels),
            subtitle = stringResource(R.string.iptv_sport9_channels_subtitle),
            options = SportsChannelSource.entries.map { SettingsPickerOption(it, stringResource(channelSourceLabel(it)), stringResource(channelSourceDescription(it))) },
            selectedValue = state.channelSource, onDismiss = dismiss, width = 560.dp, onOptionSelected = { viewModel.setChannelSource(it); dismiss() })
        IptvSportsChoice.FAVOURITES -> SettingsMultiChoiceDialog(title = stringResource(R.string.iptv_sport2_favourite_teams),
            subtitle = stringResource(R.string.iptv_sport2_favourite_teams_subtitle),
            options = state.favourites.mapNotNull { key ->
                SportsFavourites.parse(key)?.let { (league, team) -> SettingsPickerOption(key, team, SportsLeagues.byId(league)?.name ?: league) }
            },
            selectedValues = state.favourites, onValuesSelected = { viewModel.keepFavourites(it); dismiss() }, onDismiss = dismiss, maxHeight = 460.dp)
        IptvSportsChoice.OVERLAY -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_sport4_overlay),
            subtitle = stringResource(R.string.iptv_sport4_overlay_subtitle),
            options = SportsOverlayStyle.entries.map { SettingsPickerOption(it, stringResource(overlayLabel(it)), stringResource(overlayDescription(it))) },
            selectedValue = state.overlayStyle, onDismiss = dismiss, width = 560.dp, maxHeight = 420.dp,
            onOptionSelected = { viewModel.setOverlayStyle(it); dismiss() })
        IptvSportsChoice.GAMES -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_sport4_games),
            subtitle = stringResource(R.string.iptv_sport4_games_subtitle),
            options = SportsAlertGames.entries.map { SettingsPickerOption(it, stringResource(gamesLabel(it))) },
            selectedValue = state.alertGames, onDismiss = dismiss, width = 560.dp, onOptionSelected = { viewModel.setAlertGames(it); dismiss() })
        IptvSportsChoice.HOLD -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_sport4_hold),
            subtitle = stringResource(R.string.iptv_sport4_hold_subtitle),
            options = IptvSportsPreferences.HOLD_CHOICES.map { SettingsPickerOption(it, holdLabel(it)) },
            selectedValue = state.alertHoldSeconds, onDismiss = dismiss, width = 560.dp, maxHeight = 420.dp,
            onOptionSelected = { viewModel.setAlertHold(it); dismiss() })
        IptvSportsChoice.KINDS -> SettingsMultiChoiceDialog(title = stringResource(R.string.iptv_sport4_kinds),
            subtitle = stringResource(R.string.iptv_sport4_kinds_subtitle),
            options = SportsChangeKind.entries.map { SettingsPickerOption(it, stringResource(kindLabel(it))) },
            selectedValues = SportsChangeKind.entries.filter { it in state.alertKinds },
            onValuesSelected = { viewModel.setAlertKinds(it); dismiss() }, onDismiss = dismiss, width = 560.dp)
        IptvSportsChoice.NUVIO -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_sport4_nuvio_alert),
            subtitle = stringResource(R.string.iptv_sport4_nuvio_alert_subtitle),
            options = SportsNuvioAlert.entries.map { SettingsPickerOption(it, stringResource(nuvioLabel(it)), stringResource(nuvioDescription(it))) },
            selectedValue = state.nuvioAlert, onDismiss = dismiss, width = 560.dp, onOptionSelected = { viewModel.setNuvioAlert(it); dismiss() })
        IptvSportsChoice.QUIET -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_sport4_quiet),
            subtitle = stringResource(R.string.iptv_sport4_quiet_subtitle),
            options = IptvSportsPreferences.QUIET_CHOICES.map { SettingsPickerOption(it, quietLabel(it)) },
            selectedValue = state.nuvioQuietEndMinutes, onDismiss = dismiss, width = 560.dp, maxHeight = 420.dp,
            onOptionSelected = { viewModel.setQuietEnd(it); dismiss() })
        IptvSportsChoice.LEAD -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_sport4_reminder_lead),
            subtitle = stringResource(R.string.iptv_sport4_reminder_lead_subtitle),
            options = IptvSportsPreferences.LEAD_CHOICES.map { SettingsPickerOption(it, leadLabel(it)) },
            selectedValue = state.reminderLeadMinutes, onDismiss = dismiss, width = 560.dp, maxHeight = 420.dp,
            onOptionSelected = { viewModel.setReminderLead(it); dismiss() })
        IptvSportsChoice.PICKS -> {
            val picks by viewModel.picks.collectAsStateWithLifecycle()
            SportsPicksDialog(pickList, state.rules.list(pickList), picks, onQuery = viewModel::searchPicks,
                onToggle = { viewModel.togglePick(pickList, it) }, onDismiss = { viewModel.closePicks(); dismiss() })
        }
        IptvSportsChoice.KEY -> SportsKeyDialog(state.hasKey, onSave = { viewModel.saveKey(it, dismiss) }, onRemove = { viewModel.removeKey(); dismiss() }, onDismiss = dismiss)
        null -> Unit
    } }
}

@Composable
private fun SportsKeyDialog(hasKey: Boolean, onSave: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val invalid = key.isNotBlank() && !IptvSportsPreferences.validKey(key)
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.iptv_sport_key), subtitle = stringResource(R.string.iptv_sport7_key_subtitle), width = 600.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SourceField(stringResource(R.string.iptv_sport_key), key, { key = it.take(IptvSportsPreferences.MAX_KEY) }, keyboardType = KeyboardType.Password,
                    masked = !show, last = true, error = invalid, modifier = Modifier.weight(1f).focusRequester(first))
                Box(Modifier.height(KEY_FIELD_HEIGHT), contentAlignment = Alignment.Center) {
                    NuvioActionPill({ show = !show }) {
                        Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (show) R.string.iptv_password_hide else R.string.iptv_password_show))
                    }
                }
            }
            Text(stringResource(R.string.iptv_sport7_key_hint), color = if (invalid) NuvioTheme.colors.Error else NuvioTheme.colors.TextTertiary,
                style = MaterialTheme.typography.bodySmall)
        }
        if (invalid) Text(stringResource(R.string.iptv_sport_key_invalid), color = NuvioTheme.colors.Error, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NuvioActionPill({ onSave(key) }, enabled = key.isNotBlank() && !invalid) { Text(stringResource(R.string.iptv_setup_save)) }
            if (hasKey) NuvioActionPill(onRemove) { Text(stringResource(R.string.iptv_sport_key_remove)) }
            NuvioActionPill(onDismiss) { Text(stringResource(R.string.iptv_setup_cancel)) }
        }
    }
}

@Composable
private fun SportsLeagueSearchDialog(search: IptvSportsLeagueSearch, chosen: Set<String>, onQuery: (String) -> Unit, onAdd: (SportsDbLeague) -> Unit,
    onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { onQuery(search.query); withFrameNanos { }; runCatching { first.requestFocus() } }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.iptv_sport7_add_league), subtitle = stringResource(R.string.iptv_sport7_add_league_subtitle),
        width = 640.dp) {
        SourceField(stringResource(R.string.iptv_sport7_search), search.query, { onQuery(it.take(60)) }, hint = stringResource(R.string.iptv_sport7_search_hint),
            last = true, modifier = Modifier.fillMaxWidth().focusRequester(first))
        val message = when {
            search.loading -> R.string.iptv_sport7_search_loading
            search.failed -> R.string.iptv_sport7_search_failed
            search.query.trim().length >= 2 && search.results.isEmpty() -> R.string.iptv_sport7_search_none
            else -> null
        }
        if (message != null) Text(stringResource(message), style = MaterialTheme.typography.bodyMedium,
            color = if (search.failed) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(search.results, key = { it.id }) { league ->
                val added = (SportsDbLeagues.builtIn(league)?.id ?: SportsDbLeagues.league(league).id) in chosen
                SettingsActionRow(title = league.name, subtitle = league.sport.takeIf { it.isNotBlank() },
                    value = if (added) stringResource(R.string.iptv_sport7_added) else null, onClick = { if (!added) onAdd(league) },
                    trailingIcon = if (added) null else Icons.Filled.Add)
            }
        }
    }
}

@Composable
private fun SportsPicksDialog(which: SportsPickList, chosen: List<SportsChannelPick>, picks: IptvSportsPickState, onQuery: (String) -> Unit,
    onToggle: (SportsChannelPick) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val keys = remember(chosen) { chosen.map { it.key }.toSet() }
    var listed by remember { mutableStateOf(chosen) }
    LaunchedEffect(chosen) { listed = listed + chosen.filter { pick -> listed.none { it.key == pick.key } } }
    val filter = picks.query.trim()
    val categories = remember(picks.categories, filter) { picks.categories.filter { filter.isEmpty() || it.label.contains(filter, ignoreCase = true) } }
    val categoryLabel = stringResource(R.string.iptv_ui13_sport_pick_category)
    val channelLabel = stringResource(R.string.iptv_ui13_sport_pick_channel)
    NuvioDialog(onDismiss = onDismiss, title = stringResource(pickTitle(which)), subtitle = stringResource(pickSubtitle(which)), width = 680.dp) {
        SourceField(stringResource(R.string.iptv_ui13_sport_pick_search), picks.query, { onQuery(it.take(60)) },
            hint = stringResource(R.string.iptv_ui13_sport_pick_hint), last = true, modifier = Modifier.fillMaxWidth().focusRequester(first))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (listed.isNotEmpty()) {
                item(key = "chosen") { PickHeading(stringResource(R.string.iptv_ui13_sport_pick_chosen)) }
                items(listed, key = { "c:" + it.key }) { pick -> PickRow(pick, if (pick.kind == SportsPickKind.CATEGORY) categoryLabel else channelLabel, pick.key in keys, onToggle) }
            }
            if (filter.length >= 2 || picks.searching) {
                item(key = "channels") { PickHeading(stringResource(R.string.iptv_ui13_sport_pick_channels)) }
                if (picks.channels.isEmpty()) item(key = "channels-none") {
                    PickHeading(stringResource(if (picks.searching) R.string.iptv_ui13_sport_pick_searching else R.string.iptv_ui13_sport_pick_no_channels), quiet = true)
                }
                items(picks.channels, key = { "h:" + it.pick.key }) { option -> PickRow(option.pick, option.detail ?: channelLabel, option.pick.key in keys, onToggle) }
            }
            item(key = "categories") { PickHeading(stringResource(R.string.iptv_ui13_sport_pick_categories)) }
            if (categories.isEmpty()) item(key = "categories-none") { PickHeading(stringResource(R.string.iptv_ui13_sport_pick_no_categories), quiet = true) }
            items(categories, key = { "g:" + it.key }) { pick -> PickRow(pick, categoryLabel, pick.key in keys, onToggle) }
        }
    }
}

@Composable
private fun PickRow(pick: SportsChannelPick, detail: String, on: Boolean, onToggle: (SportsChannelPick) -> Unit) {
    SettingsActionRow(title = pick.label, subtitle = detail, value = if (on) stringResource(R.string.iptv_sport7_added) else null, onClick = { onToggle(pick) },
        trailingIcon = if (on) Icons.Filled.Close else Icons.Filled.Add)
}

@Composable
private fun PickHeading(text: String, quiet: Boolean = false) {
    Text(text, style = if (quiet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelLarge,
        color = if (quiet) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp))
}

private fun pickTitle(which: SportsPickList): Int = when (which) {
    SportsPickList.ALWAYS -> R.string.iptv_ui13_sport_always
    SportsPickList.NEVER -> R.string.iptv_ui13_sport_never
    SportsPickList.PREFERRED -> R.string.iptv_ui13_sport_preferred
    SportsPickList.EXCLUDED -> R.string.iptv_ui13_sport_excluded
}

private fun pickSubtitle(which: SportsPickList): Int = when (which) {
    SportsPickList.ALWAYS -> R.string.iptv_ui13_sport_always_subtitle
    SportsPickList.NEVER -> R.string.iptv_ui13_sport_never_subtitle
    SportsPickList.PREFERRED -> R.string.iptv_ui13_sport_preferred_subtitle
    SportsPickList.EXCLUDED -> R.string.iptv_ui13_sport_excluded_subtitle
}

private fun sourceLabel(league: SportsLeague, hasKey: Boolean): Int = when {
    league.espn != null -> if (hasKey && league.sportsDb != null) R.string.iptv_sport7_source_both else R.string.iptv_sport7_source_espn
    hasKey -> R.string.iptv_sport7_source_sportsdb
    else -> R.string.iptv_sport7_source_needs_key
}

private fun channelSourceLabel(source: SportsChannelSource): Int = when (source) {
    SportsChannelSource.GUIDE -> R.string.iptv_sport9_channels_guide
    SportsChannelSource.BROADCASTERS -> R.string.iptv_sport9_channels_broadcasters
    SportsChannelSource.BOTH -> R.string.iptv_sport9_channels_both
}

private fun channelSourceDescription(source: SportsChannelSource): Int = when (source) {
    SportsChannelSource.GUIDE -> R.string.iptv_sport9_channels_guide_description
    SportsChannelSource.BROADCASTERS -> R.string.iptv_sport9_channels_broadcasters_description
    SportsChannelSource.BOTH -> R.string.iptv_sport9_channels_both_description
}

private fun logosLabel(logos: SportsLogos): Int = when (logos) {
    SportsLogos.ESPN -> R.string.iptv_sport7_logos_espn
    SportsLogos.ALL -> R.string.iptv_sport7_logos_all
    SportsLogos.OFF -> R.string.iptv_sport7_logos_off
}

private fun logosDescription(logos: SportsLogos): Int = when (logos) {
    SportsLogos.ESPN -> R.string.iptv_sport7_logos_espn_description
    SportsLogos.ALL -> R.string.iptv_sport7_logos_all_description
    SportsLogos.OFF -> R.string.iptv_sport7_logos_off_description
}

private fun overlayLabel(style: SportsOverlayStyle): Int = when (style) {
    SportsOverlayStyle.OFF -> R.string.iptv_sport4_overlay_off
    SportsOverlayStyle.GLANCE -> R.string.iptv_sport4_overlay_glance
    SportsOverlayStyle.BUG -> R.string.iptv_sport4_overlay_bug
    SportsOverlayStyle.CARDS -> R.string.iptv_sport4_overlay_cards
    SportsOverlayStyle.TICKER -> R.string.iptv_sport4_overlay_ticker
}

private fun overlayDescription(style: SportsOverlayStyle): Int = when (style) {
    SportsOverlayStyle.OFF -> R.string.iptv_sport4_overlay_off_description
    SportsOverlayStyle.GLANCE -> R.string.iptv_sport4_overlay_glance_description
    SportsOverlayStyle.BUG -> R.string.iptv_sport4_overlay_bug_description
    SportsOverlayStyle.CARDS -> R.string.iptv_sport4_overlay_cards_description
    SportsOverlayStyle.TICKER -> R.string.iptv_sport4_overlay_ticker_description
}

private fun gamesLabel(games: SportsAlertGames): Int = when (games) {
    SportsAlertGames.FOLLOWED -> R.string.iptv_sport4_games_followed
    SportsAlertGames.FOLLOWED_AND_CLOSE -> R.string.iptv_sport4_games_followed_close
    SportsAlertGames.ALL_LIVE -> R.string.iptv_sport4_games_all
}

private fun kindLabel(kind: SportsChangeKind): Int = when (kind) {
    SportsChangeKind.STARTED -> R.string.iptv_sport4_kind_started
    SportsChangeKind.SCORED -> R.string.iptv_sport4_kind_scored
    SportsChangeKind.FINISHED -> R.string.iptv_sport4_kind_finished
}

private fun nuvioLabel(alert: SportsNuvioAlert): Int = when (alert) {
    SportsNuvioAlert.POPUP -> R.string.iptv_sport4_nuvio_popup
    SportsNuvioAlert.CHIP -> R.string.iptv_sport4_nuvio_chip
    SportsNuvioAlert.OFF -> R.string.iptv_sport4_nuvio_off
}

private fun nuvioDescription(alert: SportsNuvioAlert): Int = when (alert) {
    SportsNuvioAlert.POPUP -> R.string.iptv_sport4_nuvio_popup_description
    SportsNuvioAlert.CHIP -> R.string.iptv_sport4_nuvio_chip_description
    SportsNuvioAlert.OFF -> R.string.iptv_sport4_nuvio_off_description
}

@Composable
private fun holdLabel(seconds: Int): String =
    if (seconds == 0) stringResource(R.string.iptv_sport4_hold_none) else stringResource(R.string.iptv_sport4_hold_seconds, seconds)

@Composable
private fun quietLabel(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.iptv_sport4_quiet_off) else stringResource(R.string.iptv_sport4_quiet_minutes, minutes)

@Composable
private fun leadLabel(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.iptv_sport4_reminder_at_start) else stringResource(R.string.iptv_sport4_reminder_minutes, minutes)

private val KEY_FIELD_HEIGHT = 48.dp
private const val PICK_DELAY = 300L
