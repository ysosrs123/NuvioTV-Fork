@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.HomeRowKind
import com.nuvio.tv.core.iptv.HomeRowSettings
import com.nuvio.tv.data.iptv.IptvHomePreferences
import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class IptvHomeSettingsState(val settings: HomeRowSettings = HomeRowSettings(), val sport: Boolean = true)

@HiltViewModel
class IptvHomeSettingsViewModel @Inject constructor(@ApplicationContext context: Context, private val livePreferences: IptvLivePreferences) : ViewModel() {
    private val preferences = IptvHomePreferences(context)
    private val mutable = MutableStateFlow(IptvHomeSettingsState(preferences.settings, livePreferences.sport))
    val state = mutable.asStateFlow()

    fun reload() { mutable.value = IptvHomeSettingsState(preferences.settings, livePreferences.sport) }

    fun toggle(kind: HomeRowKind) {
        preferences.set(kind, !mutable.value.settings.shows(kind))
        reload()
    }
}

@Composable
fun IptvHomeSettingsSection(viewModel: IptvHomeSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.reload() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SettingsGroupCard(title = stringResource(R.string.iptv_home_settings_title)) {
        HomeRowKind.entries.filter { it != HomeRowKind.SPORT || state.sport }.forEach { kind ->
            SettingsToggleRow(title = stringResource(iptvHomeRowTitle(kind)), subtitle = stringResource(rowDescription(kind)),
                checked = state.settings.shows(kind), onToggle = { viewModel.toggle(kind) })
        }
    }
}

private fun rowDescription(kind: HomeRowKind): Int = when (kind) {
    HomeRowKind.FAVOURITES -> R.string.iptv_home_settings_favourites
    HomeRowKind.SPORT -> R.string.iptv_home_settings_sport
    HomeRowKind.MOVIES -> R.string.iptv_home_settings_movies
    HomeRowKind.SERIES -> R.string.iptv_home_settings_series
    HomeRowKind.RECORDINGS -> R.string.iptv_home_settings_recordings
}
