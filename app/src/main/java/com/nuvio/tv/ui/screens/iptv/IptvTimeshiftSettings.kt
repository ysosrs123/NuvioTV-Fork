package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.LocalTimeshiftLength
import com.nuvio.tv.core.iptv.RecordingLocations
import com.nuvio.tv.core.recording.IptvRecordingTargets
import com.nuvio.tv.data.iptv.IptvLocalTimeshiftConfig
import com.nuvio.tv.data.iptv.IptvLocalTimeshiftSession
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvTimeshiftPreferences
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IptvTimeshiftDrive(val location: String, val label: String, val freeBytes: Long)

data class IptvTimeshiftSettingsState(val enabled: Boolean = false, val length: LocalTimeshiftLength = LocalTimeshiftLength.AUTOMATIC,
    val location: String = RecordingLocations.INTERNAL, val internalFreeBytes: Long = 0, val drives: List<IptvTimeshiftDrive> = emptyList())

internal object IptvLocalTimeshiftPlaces {
    private val swept = AtomicBoolean(false)

    private fun volumeDirectory(targets: IptvRecordingTargets, id: String): File? = targets.volumes()
        .firstOrNull { it.id == id && it.mounted && !it.readOnly }?.directory?.parentFile?.let { File(it, IptvTimeshiftPreferences.DIRECTORY) }

    fun drives(targets: IptvRecordingTargets): List<IptvTimeshiftDrive> = targets.volumes().filter { it.mounted && !it.readOnly }
        .map { IptvTimeshiftDrive(RecordingLocations.volume(it.id), it.label, it.freeBytes) }

    fun config(preferences: IptvTimeshiftPreferences, targets: IptvRecordingTargets): IptvLocalTimeshiftConfig? {
        if (!preferences.enabled) return null
        val directory = RecordingLocations.volumeId(preferences.location)?.let { volumeDirectory(targets, it) ?: return null }
            ?: preferences.internalDirectory()
        return IptvLocalTimeshiftConfig(directory, preferences.length)
    }

    fun sweepOnce(preferences: IptvTimeshiftPreferences, targets: IptvRecordingTargets) {
        if (!swept.compareAndSet(false, true)) return
        val directories = listOf(preferences.internalDirectory()) + runCatching {
            targets.volumes().filter { it.mounted && !it.readOnly }.mapNotNull { it.directory.parentFile?.let { parent -> File(parent, IptvTimeshiftPreferences.DIRECTORY) } }
        }.getOrDefault(emptyList())
        val removed = runCatching { IptvLocalTimeshiftSession.sweep(directories) }.getOrDefault(0)
        if (removed > 0) IptvLog.info("local timeshift removed leftovers count=$removed")
    }
}

@HiltViewModel
class IptvTimeshiftSettingsViewModel @Inject constructor(@ApplicationContext context: Context,
    private val targets: IptvRecordingTargets) : ViewModel() {
    private val preferences = IptvTimeshiftPreferences(context)
    private val mutable = MutableStateFlow(IptvTimeshiftSettingsState(preferences.enabled, preferences.length, preferences.location))
    val state = mutable.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            val internal = withContext(Dispatchers.IO) { runCatching { preferences.internalDirectory().let { it.mkdirs(); it.usableSpace } }.getOrDefault(0L) }
            val drives = withContext(Dispatchers.IO) { runCatching { IptvLocalTimeshiftPlaces.drives(targets) }.getOrDefault(emptyList()) }
            mutable.update { it.copy(enabled = preferences.enabled, length = preferences.length, location = preferences.location,
                internalFreeBytes = internal, drives = drives) }
        }
    }

    fun toggle() { preferences.enabled = !preferences.enabled; mutable.update { it.copy(enabled = preferences.enabled) } }
    fun setLength(value: LocalTimeshiftLength) { preferences.length = value; mutable.update { it.copy(length = value) } }
    fun setLocation(value: String) { preferences.location = value; mutable.update { it.copy(location = preferences.location) } }
}

@Composable
internal fun IptvTimeshiftSettingsSection(viewModel: IptvTimeshiftSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var choosing by remember { mutableStateOf<String?>(null) }
    SettingsGroupCard(title = stringResource(R.string.iptv_timeshift_title)) {
        SettingsToggleRow(title = stringResource(R.string.iptv_timeshift_title), subtitle = stringResource(R.string.iptv_timeshift_subtitle),
            checked = state.enabled, onToggle = viewModel::toggle, expandSubtitleOnFocus = true)
        SettingsActionRow(title = stringResource(R.string.iptv_timeshift_length), subtitle = stringResource(R.string.iptv_timeshift_length_subtitle),
            value = stringResource(lengthLabel(state.length)), enabled = state.enabled, onClick = { choosing = LENGTH })
        val drive = state.drives.firstOrNull { it.location == state.location }
        val place = when {
            state.location == RecordingLocations.INTERNAL -> stringResource(R.string.iptv_location_device)
            drive != null -> drive.label
            else -> stringResource(R.string.iptv_location_drive_missing)
        }
        SettingsActionRow(title = stringResource(R.string.iptv_timeshift_location), subtitle = stringResource(R.string.iptv_timeshift_location_subtitle),
            value = place, enabled = state.enabled, onClick = { viewModel.reload(); choosing = LOCATION })
    }
    when (choosing) {
        LENGTH -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_timeshift_length),
            options = LocalTimeshiftLength.entries.map {
                SettingsPickerOption(it, stringResource(lengthLabel(it)),
                    if (it == LocalTimeshiftLength.AUTOMATIC) stringResource(R.string.iptv_timeshift_length_auto_description) else null)
            },
            selectedValue = state.length, onOptionSelected = { viewModel.setLength(it); choosing = null }, onDismiss = { choosing = null }, width = 520.dp)
        LOCATION -> SettingsSingleChoiceDialog(title = stringResource(R.string.iptv_timeshift_location),
            options = listOf(SettingsPickerOption(RecordingLocations.INTERNAL, stringResource(R.string.iptv_location_device),
                stringResource(R.string.iptv_location_free, Formatter.formatShortFileSize(context, state.internalFreeBytes)))) +
                state.drives.map { SettingsPickerOption(it.location, it.label, stringResource(R.string.iptv_location_free, Formatter.formatShortFileSize(context, it.freeBytes))) },
            selectedValue = state.location, onOptionSelected = { viewModel.setLocation(it); choosing = null }, onDismiss = { choosing = null }, width = 520.dp)
    }
}

private const val LENGTH = "length"
private const val LOCATION = "location"

private fun lengthLabel(value: LocalTimeshiftLength): Int = when (value) {
    LocalTimeshiftLength.MINUTES_15 -> R.string.iptv_timeshift_length_15
    LocalTimeshiftLength.MINUTES_30 -> R.string.iptv_timeshift_length_30
    LocalTimeshiftLength.MINUTES_60 -> R.string.iptv_timeshift_length_60
    LocalTimeshiftLength.AUTOMATIC -> R.string.iptv_timeshift_length_auto
}
