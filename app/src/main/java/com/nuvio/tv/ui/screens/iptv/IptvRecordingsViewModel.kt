package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.RecordingFailure
import com.nuvio.tv.core.iptv.RecordingStatus
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.recording.IptvRecordRefusal
import com.nuvio.tv.core.recording.IptvRecorder
import com.nuvio.tv.data.iptv.IptvRecording
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IptvRecordingsState(
    val ready: Boolean = false,
    val recording: List<IptvRecording> = emptyList(),
    val scheduled: List<IptvRecording> = emptyList(),
    val recorded: List<IptvRecording> = emptyList(),
    val playable: Set<String> = emptySet(),
    val freeBytes: Long? = null,
    val playing: IptvRecordingPlayback? = null,
    val message: Int? = null,
) {
    val empty: Boolean get() = recording.isEmpty() && scheduled.isEmpty() && recorded.isEmpty()
}

data class IptvRecordingPlayback(val recording: IptvRecording, val file: File)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class IptvRecordingsViewModel @Inject constructor(
    private val recorder: IptvRecorder,
    profiles: ProfileManager,
    private val screensaver: com.nuvio.tv.core.player.ScreensaverController,
) : ViewModel() {
    private val playing = MutableStateFlow<IptvRecordingPlayback?>(null)
    private val message = MutableStateFlow<Int?>(null)
    private val free = MutableStateFlow<Long?>(null)

    val state: StateFlow<IptvRecordingsState> = combine(profiles.activeProfileId.flatMapLatest { recorder.recordings(it) }, playing, message, free) { list, playing, message, free ->
        IptvRecordingsState(ready = true,
            recording = list.filter { it.status == RecordingStatus.RECORDING }.sortedBy { it.startMillis },
            scheduled = list.filter { it.status == RecordingStatus.SCHEDULED }.sortedBy { it.startMillis },
            recorded = list.filter { it.status.finished }.sortedByDescending { it.startedAtMillis ?: it.startMillis },
            playable = list.filter { recorder.file(it) != null }.map { it.id }.toSet(),
            freeBytes = free, playing = playing?.takeIf { current -> list.any { it.id == current.recording.id } }, message = message)
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IptvRecordingsState())

    init { refreshFree() }

    fun play(recording: IptvRecording) {
        val file = recorder.file(recording)
        if (file == null) { message.value = R.string.iptv_recording_file_missing; return }
        message.value = null
        playing.value = IptvRecordingPlayback(recording, file)
        screensaver.setPlaybackActive(true)
    }

    fun closePlayer() {
        playing.value = null
        screensaver.setPlaybackActive(false)
    }

    fun stop(recording: IptvRecording) { recorder.stop(recording.id) }

    fun cancel(recording: IptvRecording) { viewModelScope.launch { recorder.cancel(recording.id) } }

    fun delete(recording: IptvRecording) {
        if (playing.value?.recording?.id == recording.id) closePlayer()
        viewModelScope.launch { recorder.delete(recording.id); refreshFree() }
    }

    private fun refreshFree() {
        viewModelScope.launch { free.value = withContext(Dispatchers.IO) { recorder.freeBytes() } }
    }

    override fun onCleared() {
        if (playing.value != null) screensaver.setPlaybackActive(false)
        super.onCleared()
    }
}

fun iptvRecordRefusalMessage(reason: IptvRecordRefusal): Int = when (reason) {
    IptvRecordRefusal.CONNECTION_LIMIT -> R.string.iptv_recording_refused_conflict
    IptvRecordRefusal.NO_FREE_CONNECTION -> R.string.iptv_recording_refused_no_connection
    IptvRecordRefusal.LOW_STORAGE -> R.string.iptv_recording_refused_storage
    IptvRecordRefusal.PROGRAMME_ENDED -> R.string.iptv_recording_refused_ended
    IptvRecordRefusal.CHANNEL_UNAVAILABLE -> R.string.iptv_recording_refused_channel
    IptvRecordRefusal.ALREADY_RECORDING -> R.string.iptv_recording_refused_duplicate
    IptvRecordRefusal.LIST_FULL -> R.string.iptv_recording_refused_full
    IptvRecordRefusal.START_BLOCKED -> R.string.iptv_recording_failure_start_blocked
    IptvRecordRefusal.PROFILE_CHANGED -> R.string.iptv_recording_refused_profile
}

fun iptvRecordingFailureMessage(failure: RecordingFailure): Int = when (failure) {
    RecordingFailure.NO_CONNECTION -> R.string.iptv_recording_failure_no_connection
    RecordingFailure.DEVICE_BUSY -> R.string.iptv_recording_failure_device_busy
    RecordingFailure.SOURCE_UNAVAILABLE -> R.string.iptv_recording_failure_source
    RecordingFailure.CHANNEL_UNAVAILABLE -> R.string.iptv_recording_failure_channel
    RecordingFailure.UNSUPPORTED_STREAM -> R.string.iptv_recording_failure_unsupported
    RecordingFailure.ENCRYPTED_STREAM -> R.string.iptv_recording_failure_encrypted
    RecordingFailure.NETWORK -> R.string.iptv_recording_failure_network
    RecordingFailure.LOW_STORAGE -> R.string.iptv_recording_failure_low_storage
    RecordingFailure.STORAGE_ERROR -> R.string.iptv_recording_failure_storage
    RecordingFailure.TIME_LIMIT -> R.string.iptv_recording_failure_time_limit
    RecordingFailure.INTERRUPTED -> R.string.iptv_recording_failure_interrupted
    RecordingFailure.START_BLOCKED -> R.string.iptv_recording_failure_start_blocked
    RecordingFailure.MISSED -> R.string.iptv_recording_failure_missed
}

fun iptvRecordingStatusLabel(status: RecordingStatus): Int = when (status) {
    RecordingStatus.SCHEDULED -> R.string.iptv_recording_status_scheduled
    RecordingStatus.RECORDING -> R.string.iptv_recording_status_recording
    RecordingStatus.DONE -> R.string.iptv_recording_status_done
    RecordingStatus.PARTIAL -> R.string.iptv_recording_status_partial
    RecordingStatus.FAILED -> R.string.iptv_recording_status_failed
    RecordingStatus.CANCELLED -> R.string.iptv_recording_status_cancelled
}
