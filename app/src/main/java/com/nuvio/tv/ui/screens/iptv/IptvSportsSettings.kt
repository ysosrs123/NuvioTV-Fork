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
import com.nuvio.tv.core.iptv.SportsFavourites
import com.nuvio.tv.core.iptv.SportsLeagues
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
    val showScores: Boolean = true, val favourites: List<String> = emptyList())

@HiltViewModel
class IptvSportsSettingsViewModel @Inject constructor(private val preferences: IptvSportsPreferences) : ViewModel() {
    private val mutable = MutableStateFlow(IptvSportsSettingsState())
    val state = mutable.asStateFlow()

    init { reload() }

    fun reload() {
        mutable.value = IptvSportsSettingsState(preferences.service, preferences.leagues, preferences.hasKey, preferences.showScores,
            preferences.favouriteTeams.sortedBy { SportsFavourites.parse(it)?.second?.lowercase() })
    }

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

private enum class IptvSportsChoice { SERVICE, LEAGUES, KEY, FAVOURITES }

@Composable
fun IptvSportsSettingsSection(viewModel: IptvSportsSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<IptvSportsChoice?>(null) }
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
