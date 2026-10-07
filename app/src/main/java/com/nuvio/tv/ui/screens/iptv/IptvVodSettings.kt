@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.iptv.IptvCatalogueStore
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvProfileAccess
import com.nuvio.tv.data.iptv.IptvSource
import com.nuvio.tv.data.iptv.IptvSourceKind
import com.nuvio.tv.data.iptv.IptvVodArtworkMode
import com.nuvio.tv.data.iptv.IptvVodArtworkPreferences
import com.nuvio.tv.data.iptv.IptvVodRepository
import com.nuvio.tv.data.iptv.IptvVodStore
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IptvVodSourceSetting(val source: IptvSource, val enabled: Boolean, val movies: Int, val series: Int)

data class IptvVodSettingsState(val mode: IptvVodArtworkMode = IptvVodArtworkMode.PROVIDER, val sources: List<IptvVodSourceSetting> = emptyList())

@HiltViewModel
class IptvVodSettingsViewModel @Inject constructor(private val preferences: IptvVodArtworkPreferences, private val repository: IptvVodRepository,
    private val store: IptvVodStore, private val catalogue: IptvCatalogueStore, private val profiles: ProfileManager,
    private val access: IptvProfileAccess, private val refresher: IptvRefreshCoordinator) : ViewModel() {
    private val mutable = MutableStateFlow(IptvVodSettingsState(preferences.mode))
    val state = mutable.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            val profileId = profiles.activeProfileId.value
            val sources = withContext(Dispatchers.IO) {
                try {
                    val states = store.states(profileId).associateBy { it.ref.sourceId }
                    catalogue.sources(profileId).mapNotNull { source ->
                        val vod = states[source.ref.sourceId]
                        if (source.kind != IptvSourceKind.XTREAM && vod?.detected != true) return@mapNotNull null
                        IptvVodSourceSetting(source, IptvVodRepository.enabled(source.kind, vod), vod?.movies ?: 0, vod?.series ?: 0)
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { IptvLog.failure("vod settings", error); emptyList() }
            }
            mutable.value = IptvVodSettingsState(preferences.mode, sources)
        }
    }

    fun setMode(mode: IptvVodArtworkMode) { preferences.mode = mode; mutable.update { it.copy(mode = mode) } }

    fun toggle(setting: IptvVodSourceSetting) {
        viewModelScope.launch {
            val enable = !setting.enabled
            try {
                withContext(Dispatchers.IO) { repository.setEnabled(setting.source.ref, enable) }
                IptvLog.info("vod source enabled=$enable")
                if (enable) refresher.refresh(access.open(setting.source.ref.profileId), setting.source)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("vod settings toggle", error) }
            reload()
        }
    }
}

@Composable
fun IptvVodSettingsSection(viewModel: IptvVodSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.reload() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (state.sources.isEmpty()) return
    SettingsGroupCard(title = stringResource(R.string.iptv_vod_browse_settings_title)) {
        SettingsActionRow(title = stringResource(R.string.iptv_vod_browse_settings_artwork), subtitle = stringResource(R.string.iptv_vod_browse_settings_artwork_subtitle),
            value = stringResource(artworkLabel(state.mode)), onClick = { choosing = true })
        Text(stringResource(R.string.iptv_vod_browse_settings_sources), style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp))
        state.sources.forEach { setting ->
            SettingsToggleRow(title = setting.source.label, subtitle = when {
                !setting.enabled -> stringResource(R.string.iptv_vod_browse_settings_hidden)
                setting.movies + setting.series == 0 -> stringResource(R.string.iptv_vod_browse_settings_pending)
                else -> stringResource(R.string.iptv_vod_browse_settings_counts, setting.movies, setting.series)
            }, checked = setting.enabled, onToggle = { viewModel.toggle(setting) })
        }
    }
    if (choosing) SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_vod_browse_settings_artwork),
        subtitle = stringResource(R.string.iptv_vod_browse_settings_artwork_subtitle),
        options = IptvVodArtworkMode.entries.map { SettingsPickerOption(it, stringResource(artworkLabel(it)), stringResource(artworkDescription(it))) },
        selectedValue = state.mode, onOptionSelected = { viewModel.setMode(it); choosing = false }, onDismiss = { choosing = false }, width = 560.dp)
}

private fun artworkLabel(mode: IptvVodArtworkMode): Int = when (mode) {
    IptvVodArtworkMode.PROVIDER -> R.string.iptv_vod_browse_artwork_provider
    IptvVodArtworkMode.NUVIO -> R.string.iptv_vod_browse_artwork_nuvio
}

private fun artworkDescription(mode: IptvVodArtworkMode): Int = when (mode) {
    IptvVodArtworkMode.PROVIDER -> R.string.iptv_vod_browse_artwork_provider_description
    IptvVodArtworkMode.NUVIO -> R.string.iptv_vod_browse_artwork_nuvio_description
}
