@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
import com.nuvio.tv.core.iptv.SportsFavourites
import com.nuvio.tv.core.iptv.SportsLeagues
import com.nuvio.tv.core.iptv.SportsNuvioAlert
import com.nuvio.tv.core.iptv.SportsOverlayStyle
import com.nuvio.tv.core.iptv.SportsService
import com.nuvio.tv.data.iptv.IptvLog
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IptvSportsSettingsState(val service: SportsService = SportsService.OFF, val leagues: Set<String> = emptySet(), val hasKey: Boolean = false,
    val showScores: Boolean = true, val favourites: List<String> = emptyList(), val overlayStyle: SportsOverlayStyle = SportsOverlayStyle.GLANCE,
    val alertGames: SportsAlertGames = SportsAlertGames.FOLLOWED_AND_CLOSE, val alertHoldSeconds: Int = IptvSportsPreferences.DEFAULT_HOLD,
    val skipOnScreen: Boolean = true, val alertKinds: Set<SportsChangeKind> = SportsChangeKind.entries.toSet(),
    val nuvioAlert: SportsNuvioAlert = SportsNuvioAlert.POPUP, val nuvioQuietEndMinutes: Int = IptvSportsPreferences.DEFAULT_QUIET,
    val reminderLeadMinutes: Int = IptvSportsPreferences.DEFAULT_LEAD, val hideSpoilers: Boolean = true)

@HiltViewModel
class IptvSportsSettingsViewModel @Inject constructor(private val preferences: IptvSportsPreferences) : ViewModel() {
    private val mutable = MutableStateFlow(IptvSportsSettingsState())
    val state = mutable.asStateFlow()

    init { reload() }

    fun reload() {
        mutable.value = IptvSportsSettingsState(preferences.service, preferences.leagues, preferences.hasKey, preferences.showScores,
            preferences.favouriteTeams.sortedBy { SportsFavourites.parse(it)?.second?.lowercase() }, preferences.overlayStyle, preferences.alertGames,
            preferences.alertHoldSeconds, preferences.skipOnScreen, preferences.alertKinds, preferences.nuvioAlert, preferences.nuvioQuietEndMinutes,
            preferences.reminderLeadMinutes, preferences.hideSpoilers)
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

    fun setService(service: SportsService) { preferences.service = service; reload() }

    fun setLeagues(chosen: List<String>) {
        val service = mutable.value.service
        val offered = SportsLeagues.ALL.filter { service == SportsService.OFF || it.supports(service) }.map { it.id }.toSet()
        preferences.leagues = chosen.toSet() + (preferences.leagues - offered)
        reload()
    }

    fun saveKey(value: String, done: () -> Unit) {
        if (!IptvSportsPreferences.validKey(value)) return
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { preferences.setKey(value) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("sports key save", error) }
            reload(); done()
        }
    }

    fun removeKey() { preferences.setKey(null); reload() }
}

private enum class IptvSportsChoice { SERVICE, LEAGUES, KEY, FAVOURITES, OVERLAY, GAMES, HOLD, KINDS, NUVIO, QUIET, LEAD }

@Composable
fun IptvSportsSettingsSection(viewModel: IptvSportsSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<IptvSportsChoice?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroupCard(title = stringResource(R.string.iptv_live_sports)) {
            SettingsActionRow(title = stringResource(R.string.iptv_sport_data), subtitle = stringResource(R.string.iptv_sport_data_subtitle),
                value = stringResource(serviceLabel(state.service)), onClick = { choosing = IptvSportsChoice.SERVICE })
            if (state.service != SportsService.OFF) {
                val count = SportsLeagues.chosen(state.leagues, state.service).size
                SettingsActionRow(title = stringResource(R.string.iptv_sport_leagues), subtitle = stringResource(R.string.iptv_sport_leagues_subtitle),
                    value = if (count == 0) stringResource(R.string.iptv_sport_leagues_none) else pluralStringResource(R.plurals.iptv_sport_leagues_chosen, count, count),
                    onClick = { choosing = IptvSportsChoice.LEAGUES })
            }
            if (state.service == SportsService.THESPORTSDB) SettingsActionRow(title = stringResource(R.string.iptv_sport_key),
                subtitle = stringResource(R.string.iptv_sport_key_subtitle),
                value = stringResource(if (state.hasKey) R.string.iptv_sport_key_set else R.string.iptv_sport_key_not_set),
                valueColor = if (state.hasKey) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.Error, onClick = { choosing = IptvSportsChoice.KEY })
            if (state.service != SportsService.OFF) {
                SettingsToggleRow(title = stringResource(R.string.iptv_sport2_show_scores), subtitle = stringResource(R.string.iptv_sport2_show_scores_subtitle),
                    checked = state.showScores, onToggle = { viewModel.setShowScores(!state.showScores) })
                if (state.favourites.isNotEmpty()) SettingsActionRow(title = stringResource(R.string.iptv_sport2_favourite_teams),
                    subtitle = stringResource(R.string.iptv_sport2_favourite_hint), value = state.favourites.size.toString(),
                    onClick = { choosing = IptvSportsChoice.FAVOURITES })
            }
        }
        if (state.service != SportsService.OFF) {
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
    when (choosing) {
        IptvSportsChoice.SERVICE -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_sport_data),
            subtitle = stringResource(R.string.iptv_sport_data_subtitle),
            options = SportsService.entries.map { SettingsPickerOption(it, stringResource(serviceLabel(it)), stringResource(serviceDescription(it))) },
            selectedValue = state.service, onDismiss = dismiss, width = 560.dp, onOptionSelected = {
                viewModel.setService(it); choosing = if (it == SportsService.THESPORTSDB && !state.hasKey) IptvSportsChoice.KEY else null
            })
        IptvSportsChoice.LEAGUES -> SettingsMultiChoiceDialog(title = stringResource(R.string.iptv_sport_leagues),
            subtitle = stringResource(R.string.iptv_sport_leagues_subtitle),
            options = SportsLeagues.ALL.filter { it.supports(state.service) }.map { SettingsPickerOption(it.id, it.name) },
            selectedValues = SportsLeagues.ALL.filter { it.id in state.leagues }.map { it.id },
            onValuesSelected = { viewModel.setLeagues(it); dismiss() }, onDismiss = dismiss, maxHeight = 460.dp)
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
        IptvSportsChoice.KEY -> SportsKeyDialog(state.hasKey, onSave = { viewModel.saveKey(it, dismiss) }, onRemove = { viewModel.removeKey(); dismiss() }, onDismiss = dismiss)
        null -> Unit
    }
}

@Composable
private fun SportsKeyDialog(hasKey: Boolean, onSave: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val invalid = key.isNotBlank() && !IptvSportsPreferences.validKey(key)
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.iptv_sport_key), subtitle = stringResource(R.string.iptv_sport_key_subtitle), width = 600.dp) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SourceField(stringResource(R.string.iptv_sport_key), key, { key = it.take(IptvSportsPreferences.MAX_KEY) }, hint = stringResource(R.string.iptv_sport_key_hint),
                keyboardType = KeyboardType.Password, masked = !show, last = true, error = invalid, modifier = Modifier.weight(1f).focusRequester(first))
            NuvioActionPill({ show = !show }) {
                Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (show) R.string.iptv_password_hide else R.string.iptv_password_show))
            }
        }
        if (invalid) Text(stringResource(R.string.iptv_sport_key_invalid), color = NuvioTheme.colors.Error, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NuvioActionPill({ onSave(key) }, enabled = key.isNotBlank() && !invalid) { Text(stringResource(R.string.iptv_setup_save)) }
            if (hasKey) NuvioActionPill(onRemove) { Text(stringResource(R.string.iptv_sport_key_remove)) }
            NuvioActionPill(onDismiss) { Text(stringResource(R.string.iptv_setup_cancel)) }
        }
    }
}

private fun serviceLabel(service: SportsService): Int = when (service) {
    SportsService.OFF -> R.string.iptv_sport_data_off
    SportsService.ESPN -> R.string.iptv_sport_data_espn
    SportsService.THESPORTSDB -> R.string.iptv_sport_data_sportsdb
}

private fun serviceDescription(service: SportsService): Int = when (service) {
    SportsService.OFF -> R.string.iptv_sport_data_off_description
    SportsService.ESPN -> R.string.iptv_sport_data_espn_description
    SportsService.THESPORTSDB -> R.string.iptv_sport_data_sportsdb_description
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
