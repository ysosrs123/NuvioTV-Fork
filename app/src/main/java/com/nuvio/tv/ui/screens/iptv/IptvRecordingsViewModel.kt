package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.RecordingFailure
import com.nuvio.tv.core.iptv.RecordingNote
import com.nuvio.tv.core.iptv.RecordingNotes
import com.nuvio.tv.core.iptv.RecordingStatus
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.recording.IptvFreeSpace
import com.nuvio.tv.core.recording.IptvRecordRefusal
import com.nuvio.tv.core.recording.IptvRecorder
import com.nuvio.tv.core.recording.IptvRecordingAvailability
import com.nuvio.tv.core.recording.IptvRecordingDeletion
import com.nuvio.tv.core.server.IptvRecordingStreamServer
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvRecording
import com.nuvio.tv.data.iptv.IptvRecordingReader
import com.nuvio.tv.data.iptv.IptvShareError
import com.nuvio.tv.domain.model.WatchProgress
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
    val availability: Map<String, IptvRecordingAvailability> = emptyMap(),
    val uploading: Set<String> = emptySet(),
    val notes: Map<String, Int> = emptyMap(),
    val free: IptvFreeSpace? = null,
    val playing: IptvRecordingPlayback? = null,
    val stream: IptvRecordingStream? = null,
    val message: Int? = null,
    val preparing: Boolean = false,
) {
    val empty: Boolean get() = recording.isEmpty() && scheduled.isEmpty() && recorded.isEmpty()
}

class IptvRecordingPlayback(val recording: IptvRecording, val reader: IptvRecordingReader)

class IptvRecordingStream(val id: String, val url: String, val title: String, val channel: String, val resume: Boolean = false) {
    override fun toString() = "IptvRecordingStream(withheld)"
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class IptvRecordingsViewModel @Inject constructor(
    private val recorder: IptvRecorder,
    profiles: ProfileManager,
    private val screensaver: com.nuvio.tv.core.player.ScreensaverController,
) : ViewModel() {
    private val playing = MutableStateFlow<IptvRecordingPlayback?>(null)
    private val message = MutableStateFlow<Int?>(null)
    private val free = MutableStateFlow<IptvFreeSpace?>(null)
    private val stream = MutableStateFlow<IptvRecordingStream?>(null)
    private val preparing = MutableStateFlow(false)
    private var server: IptvRecordingStreamServer? = null

    private val shown = combine(playing, stream, preparing) { playing, stream, preparing -> Triple(playing, stream, preparing) }
    private val upload = combine(recorder.uploading, recorder.uploadIssues) { uploading, issues -> uploading to issues }

    val state: StateFlow<IptvRecordingsState> = combine(profiles.activeProfileId.flatMapLatest { recorder.recordings(it) }, shown, message, free,
        upload) { list, (playing, stream, preparing), message, free, (uploading, issues) ->
        val availability = recorder.availability(list)
        val notes = list.mapNotNull { entry ->
            RecordingNotes.upload(issues[entry.id] == IptvShareError.FULL, entry.status, entry.upload, entry.onMedia)?.let { entry.id to iptvRecordingNoteMessage(it) }
        }.toMap()
        IptvRecordingsState(ready = true,
            recording = list.filter { it.status == RecordingStatus.RECORDING }.sortedBy { it.startMillis },
            scheduled = list.filter { it.status == RecordingStatus.SCHEDULED }.sortedBy { it.startMillis },
            recorded = list.filter { it.status.finished }.sortedByDescending { it.startedAtMillis ?: it.startMillis },
            playable = availability.filterValues { it == IptvRecordingAvailability.PLAYABLE }.keys,
            availability = availability, uploading = uploading, notes = notes,
            free = free, playing = playing?.takeIf { current -> list.any { it.id == current.recording.id } },
            stream = stream?.takeIf { current -> list.any { it.id == current.id } }, message = message, preparing = preparing)
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IptvRecordingsState())

    init { refreshFree() }

    fun play(recording: IptvRecording, fullPlayer: Boolean = false, fromStart: Boolean = false) {
        if (preparing.value) return
        viewModelScope.launch {
            val reader = withContext(Dispatchers.IO) { recorder.reader(recording) }
            if (reader == null) { message.value = R.string.iptv_recording_file_missing; return@launch }
            message.value = null
            val previous = server.also { server = null }
            if (fullPlayer) preparing.value = true
            val started = try {
                if (fullPlayer || previous != null) withContext(Dispatchers.IO) {
                    previous?.let { runCatching { it.stop() } }
                    if (fullPlayer) {
                        val playlist = try { recorder.playlist(recording, reader, IptvRecordingStreamServer.MEDIA) } catch (cancel: CancellationException) { throw cancel }
                            catch (error: Exception) { IptvLog.failure("recording playlist", error); null }
                        IptvRecordingStreamServer.start(reader, playlist)
                    } else null
                } else null
            } finally { preparing.value = false }
            if (started != null) {
                server = started
                val position = if (fromStart) null else (recorder.all.value.firstOrNull { it.id == recording.id } ?: recording).resumeMillis
                IptvRecordingResume.open(started.url, recording.id, position)
                stream.value = IptvRecordingStream(recording.id, started.url, recording.title ?: recording.channelName, recording.channelName, position != null)
            } else {
                playing.value = IptvRecordingPlayback(recording, reader)
                screensaver.setPlaybackActive(true)
            }
            if (recording.playedAtMillis == null) recorder.markPlayed(recording.id)
        }
    }

    fun streamOpened() { stream.value = null }

    fun playerReturned() {
        if (stream.value == null) stopStream()
    }

    private fun stopStream() {
        val current = server ?: return
        server = null
        kotlin.concurrent.thread(name = "recording-stream-stop", isDaemon = true) { runCatching { current.stop() } }
    }

    fun closePlayer() {
        playing.value = null
        screensaver.setPlaybackActive(false)
    }

    fun stop(recording: IptvRecording) { recorder.stop(recording.id) }

    fun cancel(recording: IptvRecording) { viewModelScope.launch { recorder.cancel(recording.id) } }

    fun delete(recording: IptvRecording) {
        if (playing.value?.recording?.id == recording.id) closePlayer()
        viewModelScope.launch {
            if (recorder.delete(recording.id) == IptvRecordingDeletion.FILE_KEPT) message.value = R.string.iptv_media_delete_not_owned
            refreshFree()
        }
    }

    private fun refreshFree() {
        viewModelScope.launch { free.value = withContext(Dispatchers.IO) { recorder.freeSpace() } }
    }

    override fun onCleared() {
        stopStream()
        if (playing.value != null) screensaver.setPlaybackActive(false)
        super.onCleared()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface IptvRecordingResumeEntryPoint {
    fun iptvRecorder(): IptvRecorder
}

object IptvRecordingResume {
    private class Opened(val id: String, @Volatile var positionMillis: Long?)
    private val opened = LinkedHashMap<String, Opened>()

    internal fun open(url: String, id: String, positionMillis: Long?) = synchronized(opened) {
        opened.remove(url)
        opened[url] = Opened(id, positionMillis)
        while (opened.size > MAX_OPEN) opened.remove(opened.keys.first())
    }

    fun applies(url: String?): Boolean = url != null && synchronized(opened) { url in opened }

    fun load(url: String): WatchProgress? {
        val position = synchronized(opened) { opened[url]?.positionMillis } ?: return null
        return WatchProgress(contentId = "", contentType = "movie", name = "", poster = null, backdrop = null, logo = null, videoId = "", season = null,
            episode = null, episodeTitle = null, position = position, duration = 0, lastWatched = System.currentTimeMillis())
    }

    suspend fun save(context: Context, url: String, positionMillis: Long, durationMillis: Long) {
        val entry = synchronized(opened) { opened[url] } ?: return
        entry.positionMillis = com.nuvio.tv.core.iptv.RecordingResume.keep(positionMillis, durationMillis)
        try {
            EntryPointAccessors.fromApplication(context.applicationContext, IptvRecordingResumeEntryPoint::class.java).iptvRecorder()
                .saveResume(entry.id, positionMillis, durationMillis)
        } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { IptvLog.failure("recording resume save", error) }
    }

    private const val MAX_OPEN = 4
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
    IptvRecordRefusal.EXACT_ALARMS_DENIED -> R.string.iptv_recording_refused_exact_alarms
    IptvRecordRefusal.STORAGE_MISSING -> R.string.iptv_recording_refused_storage_missing
    IptvRecordRefusal.STORAGE_READ_ONLY -> R.string.iptv_recording_refused_storage_read_only
    IptvRecordRefusal.SHARE_MISSING -> R.string.iptv_recording_refused_share_missing
}

fun iptvRecordingFailureMessage(recording: IptvRecording, failure: RecordingFailure): Int =
    RecordingNotes.failure(failure, recording.spooled, recording.onMedia)?.let(::iptvRecordingNoteMessage) ?: iptvRecordingFailureMessage(failure)

fun iptvRecordingNoteMessage(note: RecordingNote): Int = when (note) {
    RecordingNote.BOX_STORAGE_LOW -> R.string.iptv_ui17_recording_box_storage_low
    RecordingNote.SHARE_FULL -> R.string.iptv_ui17_recording_share_full
    RecordingNote.MEDIA_FULL -> R.string.iptv_ui17_recording_media_full
    RecordingNote.SHARE_PAUSED -> R.string.iptv_ui17_recording_share_paused
    RecordingNote.MEDIA_PAUSED -> R.string.iptv_ui17_recording_media_paused
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
    RecordingFailure.STORAGE_MISSING -> R.string.iptv_recording_failure_storage_missing
    RecordingFailure.STORAGE_REMOVED -> R.string.iptv_recording_failure_storage_removed
    RecordingFailure.SHARE_FULL -> R.string.iptv_ui17_recording_share_full
}

fun iptvRecordingStatusLabel(status: RecordingStatus): Int = when (status) {
    RecordingStatus.SCHEDULED -> R.string.iptv_recording_status_scheduled
    RecordingStatus.RECORDING -> R.string.iptv_recording_status_recording
    RecordingStatus.DONE -> R.string.iptv_recording_status_done
    RecordingStatus.PARTIAL -> R.string.iptv_recording_status_partial
    RecordingStatus.FAILED -> R.string.iptv_recording_status_failed
    RecordingStatus.CANCELLED -> R.string.iptv_recording_status_cancelled
}
