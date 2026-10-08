package com.nuvio.tv.ui.screens.iptv

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.RecordingLocations
import com.nuvio.tv.core.recording.IptvRecordingTargets
import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class IptvRecordPlaces(val options: List<IptvLocationOption>, val default: String, val notes: Map<String, Int>)

@HiltViewModel
class IptvRecordPlacesViewModel @Inject constructor(private val targets: IptvRecordingTargets, private val preferences: IptvLivePreferences) : ViewModel() {
    suspend fun load(): IptvRecordPlaces = withContext(Dispatchers.IO) {
        try { places() } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
            IptvLog.failure("recording places", error)
            IptvRecordPlaces(listOf(IptvLocationOption(RecordingLocations.INTERNAL, IptvLocationKind.DEVICE, null, null, null)), RecordingLocations.INTERNAL, emptyMap())
        }
    }

    private fun places(): IptvRecordPlaces {
        val notes = HashMap<String, Int>()
        val internal = IptvLocationOption(RecordingLocations.INTERNAL, IptvLocationKind.DEVICE, null,
            runCatching { targets.internalDirectory().usableSpace }.getOrNull(), null)
        val drives = targets.volumes().map { volume ->
            val value = RecordingLocations.volume(volume.id)
            if (!volume.mounted) notes[value] = R.string.iptv_location_drive_missing
            else if (volume.readOnly) notes[value] = R.string.iptv_record_where_read_only
            IptvLocationOption(value, IptvLocationKind.DRIVE, volume.label, volume.freeBytes.takeIf { volume.mounted }, volume.fileSystem)
        }
        val media = if (targets.media.available) {
            if (!targets.media.mounted()) notes[RecordingLocations.MEDIA] = R.string.iptv_media_missing
            listOf(IptvLocationOption(RecordingLocations.MEDIA, IptvLocationKind.MEDIA, null, targets.media.freeBytes().takeIf { it > 0 }, null))
        } else emptyList()
        val share = targets.shares.settings()?.let { listOf(IptvLocationOption(RecordingLocations.SHARE, IptvLocationKind.SHARE, it.label, preferences.shareFreeBytes, null)) }.orEmpty()
        val options = listOf(internal) + drives + media + share
        val choice = RecordingLocations.choice(preferences.recordLocation)
        return IptvRecordPlaces(options, options.firstOrNull { it.value == choice }?.value ?: RecordingLocations.INTERNAL, notes)
    }
}

@Composable
internal fun RecordPlaceDialog(title: String, subtitle: String?, onRecord: (String) -> Unit, onDismiss: () -> Unit,
    viewModel: IptvRecordPlacesViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var places by remember { mutableStateOf<IptvRecordPlaces?>(null) }
    LaunchedEffect(viewModel) { places = viewModel.load() }
    val loaded = places ?: return
    val default = stringResource(R.string.iptv_record_where_default)
    SettingsSingleChoiceDialog(title = title,
        subtitle = listOfNotNull(subtitle, stringResource(R.string.iptv_record_where_hint)).joinToString("\n"),
        options = loaded.options.map { option ->
            SettingsPickerOption(option.value, placeTitle(option), placeDescription(option, loaded.notes[option.value], context),
                trailing = default.takeIf { option.value == loaded.default })
        },
        selectedValue = loaded.default, onOptionSelected = onRecord, onDismiss = onDismiss, width = 560.dp, maxHeight = 360.dp)
}

@Composable
private fun placeTitle(option: IptvLocationOption): String = when (option.kind) {
    IptvLocationKind.DEVICE -> stringResource(R.string.iptv_location_device)
    IptvLocationKind.DRIVE -> option.label ?: stringResource(R.string.iptv_location_drive)
    IptvLocationKind.SHARE -> stringResource(R.string.iptv_network_location)
    IptvLocationKind.MEDIA -> stringResource(R.string.iptv_media_label)
}

@Composable
private fun placeDescription(option: IptvLocationOption, note: Int?, context: android.content.Context): String? {
    if (note != null) return stringResource(note)
    val free = option.freeBytes?.let { stringResource(R.string.iptv_location_free, Formatter.formatShortFileSize(context, it)) }
    return when (option.kind) {
        IptvLocationKind.DEVICE, IptvLocationKind.MEDIA -> free
        IptvLocationKind.DRIVE -> listOfNotNull(free, option.fileSystem?.label,
            if (option.fileSystem?.largeFiles == false) stringResource(R.string.iptv_location_parts) else null).joinToString(" · ").ifEmpty { null }
        IptvLocationKind.SHARE -> listOfNotNull(option.label, free).joinToString(" · ")
    }
}
