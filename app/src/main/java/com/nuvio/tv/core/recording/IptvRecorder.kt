package com.nuvio.tv.core.recording

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.nuvio.tv.core.iptv.AcquisitionKey
import com.nuvio.tv.core.iptv.AdmissionDenial
import com.nuvio.tv.core.iptv.ConsumerReservation
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.LiveAdmissionResult
import com.nuvio.tv.core.iptv.LiveConsumerLease
import com.nuvio.tv.core.iptv.LiveConsumerRole
import com.nuvio.tv.core.iptv.LiveSessionAdmission
import com.nuvio.tv.core.iptv.RecordingAlarmAction
import com.nuvio.tv.core.iptv.RecordingFailure
import com.nuvio.tv.core.iptv.RecordingFiles
import com.nuvio.tv.core.iptv.RecordingLocations
import com.nuvio.tv.core.iptv.RecordingMedia
import com.nuvio.tv.core.iptv.RecordingMediaState
import com.nuvio.tv.core.iptv.RecordingParts
import com.nuvio.tv.core.iptv.RecordingPlan
import com.nuvio.tv.core.iptv.RecordingRetry
import com.nuvio.tv.core.iptv.RecordingSlot
import com.nuvio.tv.core.iptv.RecordingSpan
import com.nuvio.tv.core.iptv.RecordingStatus
import com.nuvio.tv.core.iptv.RecordingStop
import com.nuvio.tv.core.iptv.RecordingStorage
import com.nuvio.tv.core.iptv.RecordingText
import com.nuvio.tv.core.iptv.RecordingTransitions
import com.nuvio.tv.core.iptv.RecordingWindow
import com.nuvio.tv.core.iptv.recordingAlarmAction
import com.nuvio.tv.core.iptv.recordingConflicts
import com.nuvio.tv.core.iptv.trimRecordingPadding
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.data.iptv.IptvCatalogueItem
import com.nuvio.tv.data.iptv.IptvCatalogueStore
import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvMediaDelete
import com.nuvio.tv.data.iptv.IptvMediaStoreConnector
import com.nuvio.tv.data.iptv.IptvProfileAccess
import com.nuvio.tv.data.iptv.IptvRecording
import com.nuvio.tv.data.iptv.IptvPartsReader
import com.nuvio.tv.data.iptv.IptvRecordingCopier
import com.nuvio.tv.data.iptv.IptvRecordingOutput
import com.nuvio.tv.data.iptv.IptvRecordingProgress
import com.nuvio.tv.data.iptv.IptvRecordingReader
import com.nuvio.tv.data.iptv.IptvRecordingStore
import com.nuvio.tv.data.iptv.IptvRecordingUploader
import com.nuvio.tv.data.iptv.IptvShareConnector
import com.nuvio.tv.data.iptv.IptvShareReader
import com.nuvio.tv.data.iptv.IptvSource
import com.nuvio.tv.data.iptv.IptvSourceConnection
import com.nuvio.tv.data.iptv.IptvSourceKind
import com.nuvio.tv.data.iptv.IptvSourceRef
import com.nuvio.tv.data.iptv.IptvStalkerClient
import com.nuvio.tv.data.iptv.IptvStreamFormat
import com.nuvio.tv.data.iptv.IptvUploadOutcome
import com.nuvio.tv.data.iptv.IptvUploadResult
import com.nuvio.tv.data.iptv.IptvXtreamClient
import com.nuvio.tv.data.iptv.admissionAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class IptvRecordRefusal {
    CONNECTION_LIMIT, NO_FREE_CONNECTION, LOW_STORAGE, PROGRAMME_ENDED, CHANNEL_UNAVAILABLE,
    ALREADY_RECORDING, LIST_FULL, START_BLOCKED, PROFILE_CHANGED, EXACT_ALARMS_DENIED, STORAGE_MISSING, STORAGE_READ_ONLY, SHARE_MISSING,
}

enum class IptvRecordingAvailability { PLAYABLE, NOT_READY, MISSING, DRIVE_MISSING, SHARE_MISSING, UPLOADING }

enum class IptvRecordingDeletion { REMOVED, NOT_FOUND, FILE_KEPT }

data class IptvFreeSpace(val bytes: Long, val drive: String?, val share: Boolean)

sealed interface IptvRecordResult {
    data class Accepted(val recording: IptvRecording, val immediate: Boolean) : IptvRecordResult
    data class Refused(val reason: IptvRecordRefusal) : IptvRecordResult
}

@Singleton
class IptvRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogue: IptvCatalogueStore,
    private val access: IptvProfileAccess,
    private val admission: LiveSessionAdmission,
    private val livePreferences: IptvLivePreferences,
    private val targets: IptvRecordingTargets,
) : ProfileScopedCredentialStore {
    private class Resolved(val source: IptvSource, val item: IptvCatalogueItem, val streams: Int, val connection: IptvSourceConnection?) {
        override fun toString() = "Resolved(connection withheld)"
    }
    private class Target(val accountId: String, val streams: Int, val generation: Long, val address: String, val format: IptvStreamFormat) {
        override fun toString() = "Target(address withheld)"
    }

    private val store = IptvRecordingStore(File(context.filesDir, "iptv/recordings.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> IptvLog.failure("recorder", error) })
    private val mutex = Mutex()
    private val jobs = HashMap<String, Job>()
    private val progress = HashMap<String, IptvRecordingProgress>()
    private val stops = HashMap<String, RecordingStop>()
    private val entries = MutableStateFlow<List<IptvRecording>>(emptyList())
    private val profileEntries = HashMap<Int, StateFlow<List<IptvRecording>>>()
    private val runningIds = MutableStateFlow<Set<String>>(emptySet())
    private val uploadJobs = HashMap<String, Job>()
    private val uploadingIds = MutableStateFlow<Set<String>>(emptySet())
    private var loaded = false

    private val runningChanges = MutableStateFlow(0L)
    val running: StateFlow<Set<String>> = runningIds.asStateFlow()
    val uploading: StateFlow<Set<String>> = uploadingIds.asStateFlow()
    internal val changes: StateFlow<Long> = runningChanges.asStateFlow()
    val all: StateFlow<List<IptvRecording>> = entries.asStateFlow()

    init {
        scope.launch { mutex.withLock { load() } }
        scope.launch { while (isActive) { delay(UPLOAD_RETRY_MILLIS); retryUploads() } }
    }

    fun busy(): Boolean = running.value.isNotEmpty() || uploading.value.isNotEmpty()

    fun recordings(profileId: Int): StateFlow<List<IptvRecording>> = synchronized(profileEntries) {
        profileEntries.getOrPut(profileId) {
            entries.map { list -> list.filter { it.profileId == profileId } }
                .stateIn(scope, SharingStarted.Eagerly, entries.value.filter { it.profileId == profileId })
        }
    }

    suspend fun recordNow(session: IptvProfileAccess.Session, source: IptvSourceRef, channelId: String,
        programme: GuideProgramme? = null, location: String? = null): IptvRecordResult {
        val window = RecordingPlan.now(System.currentTimeMillis(), programme?.stop?.epochMillis,
            postRollMillis = livePreferences.recordLateMinutes * 60_000L)
            ?: return IptvRecordResult.Refused(IptvRecordRefusal.PROGRAMME_ENDED)
        return create(session, source, channelId, programme, window, location)
    }

    suspend fun schedule(session: IptvProfileAccess.Session, source: IptvSourceRef, channelId: String,
        programme: GuideProgramme, location: String? = null): IptvRecordResult {
        val window = RecordingPlan.programme(System.currentTimeMillis(), programme.start.epochMillis, programme.stop?.epochMillis,
            livePreferences.recordEarlyMinutes * 60_000L, livePreferences.recordLateMinutes * 60_000L)
            ?: return IptvRecordResult.Refused(IptvRecordRefusal.PROGRAMME_ENDED)
        return create(session, source, channelId, programme, window, location)
    }

    suspend fun scheduleSport(session: IptvProfileAccess.Session, source: IptvSourceRef, channelId: String, programme: GuideProgramme?,
        title: String, window: RecordingWindow, fixtureKey: String, location: String? = null): IptvRecordResult {
        if (window.stopMillis <= System.currentTimeMillis()) return IptvRecordResult.Refused(IptvRecordRefusal.PROGRAMME_ENDED)
        return create(session, source, channelId, programme, window, location, title, fixtureKey)
    }

    suspend fun markPlayed(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            update(id) { if (it.playedAtMillis == null) it.copy(playedAtMillis = System.currentTimeMillis()) else it }
            publish()
        }
    }

    suspend fun cancel(id: String): Boolean = withContext(Dispatchers.IO) {
        val status = mutex.withLock {
            load()
            val current = store.get(id) ?: return@withLock null
            if (current.status == RecordingStatus.SCHEDULED) {
                IptvRecordingAlarms.cancel(context, current.id)
                update(id) { it.copy(status = RecordingStatus.CANCELLED, finishedAtMillis = System.currentTimeMillis()) }
                publish()
            }
            current.status
        }
        when (status) {
            RecordingStatus.SCHEDULED -> true
            RecordingStatus.RECORDING -> stop(id)
            else -> false
        }
    }

    fun stop(id: String, reason: RecordingStop = RecordingStop.USER): Boolean {
        val job = synchronized(this) { jobs[id]?.also { if (id !in stops) stops[id] = reason } } ?: return false
        job.cancel()
        return true
    }

    suspend fun delete(id: String): IptvRecordingDeletion = withContext(Dispatchers.IO) {
        val (job, upload) = synchronized(this@IptvRecorder) { jobs[id]?.also { stops[id] = RecordingStop.REMOVED } to uploadJobs[id] }
        upload?.cancel()
        var kept = false
        val removed = mutex.withLock {
            load()
            remove(id)?.also { IptvRecordingAlarms.cancel(context, it.id); kept = !deleteFiles(it); publish() }
        }
        job?.cancel()
        when {
            removed == null -> IptvRecordingDeletion.NOT_FOUND
            kept -> IptvRecordingDeletion.FILE_KEPT
            else -> IptvRecordingDeletion.REMOVED
        }
    }

    fun availability(list: List<IptvRecording>): Map<String, IptvRecordingAvailability> {
        val mounted by lazy { targets.mountedVolumeIds() }
        val share by lazy { targets.shares.settings()?.id }
        val media by lazy { targets.media.present(list.filter { it.onMedia && !it.upload && it.status.finished }.mapNotNull { RecordingMedia.id(it.file) }) }
        return list.associate { recording -> recording.id to availability(recording, { mounted }, { share }, { media }) }
    }

    private fun availability(recording: IptvRecording, mounted: () -> Set<String>, share: () -> String?, media: () -> Set<Long>): IptvRecordingAvailability {
        if (!recording.status.finished) return IptvRecordingAvailability.NOT_READY
        if (recording.onMedia) return when (RecordingMedia.state(recording.upload, recording.file, if (recording.upload) emptySet() else media())) {
            RecordingMediaState.COPYING -> IptvRecordingAvailability.UPLOADING
            RecordingMediaState.PRESENT -> IptvRecordingAvailability.PLAYABLE
            RecordingMediaState.MISSING -> IptvRecordingAvailability.MISSING
        }
        RecordingLocations.shareId(recording.storage)?.let { id ->
            return when {
                recording.upload -> IptvRecordingAvailability.UPLOADING
                recording.file == null -> IptvRecordingAvailability.MISSING
                share() != id -> IptvRecordingAvailability.SHARE_MISSING
                else -> IptvRecordingAvailability.PLAYABLE
            }
        }
        val main = recording.file?.let(::File) ?: return IptvRecordingAvailability.MISSING
        RecordingLocations.volumeId(recording.storage)?.let { if (it !in mounted()) return IptvRecordingAvailability.DRIVE_MISSING }
        return if (targets.localParts(main).sumOf { it.length() } > 0) IptvRecordingAvailability.PLAYABLE else IptvRecordingAvailability.MISSING
    }

    fun reader(recording: IptvRecording): IptvRecordingReader? {
        if (!recording.status.finished || recording.upload) return null
        val path = recording.file ?: return null
        if (recording.onMedia) return RecordingMedia.id(path)?.let { targets.media.reader(it) }
        if (recording.onShare) {
            val place = (targets.resolve(recording.storage) as? IptvPlaceResult.Ready)?.place as? IptvRecordingPlace.Share ?: return null
            return IptvShareReader(place.connector, path)
        }
        val parts = targets.localParts(File(path)).takeIf { files -> files.sumOf { it.length() } > 0 } ?: return null
        return IptvPartsReader(parts)
    }

    fun retryUploads() {
        val pending = entries.value.filter { it.upload && it.status.finished }
        var launched = false
        pending.forEach { entry -> if (launchUpload(entry.id)) launched = true }
        if (launched) startService(null)
    }

    fun exactAlarmsAllowed(): Boolean = IptvRecordingAlarms.exactAllowed(context)

    fun exactAlarmSettings(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent.takeIf { context.packageManager.resolveActivity(it, 0) != null }
    }

    fun freeSpace(): IptvFreeSpace? = when (val result = targets.preferred()) {
        is IptvPlaceResult.Ready -> when (val place = result.place) {
            is IptvRecordingPlace.Local -> IptvFreeSpace(targets.freeBytes(place), place.label, false)
            is IptvRecordingPlace.Share -> livePreferences.shareFreeBytes?.let { IptvFreeSpace(it, null, true) }
            is IptvRecordingPlace.Media -> IptvFreeSpace(place.target.freeBytes(), place.label, false)
        }
        else -> null
    }

    override fun removeProfile(profileId: Int) = runBlocking { removeWhere(profileId) }

    override fun clearAllProfiles() = runBlocking { removeWhere(null) }

    internal fun begin(id: String) {
        synchronized(this) {
            if (id in jobs) return
            val holder = IptvRecordingProgress()
            progress[id] = holder
            val job = scope.launch { record(id, holder) }
            jobs[id] = job
            runningIds.value = jobs.keys.toSet(); runningChanges.value += 1
            job.invokeOnCompletion {
                synchronized(this) {
                    if (jobs[id] === job) { jobs.remove(id); progress.remove(id) }
                    stops.remove(id)
                    runningIds.value = jobs.keys.toSet(); runningChanges.value += 1
                }
            }
        }
    }

    internal fun blocked(id: String) {
        scope.launch { mutex.withLock { load(); fail(id, RecordingFailure.START_BLOCKED); publish() } }
    }

    internal fun stopAll(reason: RecordingStop) {
        synchronized(this) {
            jobs.forEach { (id, job) -> if (id !in stops) stops[id] = reason; job.cancel() }
        }
    }

    internal fun runningTitles(): List<String> {
        val ids = running.value + uploading.value
        return entries.value.filter { it.id in ids }.map { it.title ?: it.channelName }
    }

    internal fun onAlarm(id: String, done: () -> Unit) {
        scope.launch {
            try {
                mutex.withLock {
                    load()
                    val entry = store.get(id)?.takeIf { it.status == RecordingStatus.SCHEDULED } ?: return@withLock
                    when (recordingAlarmAction(entry.window, System.currentTimeMillis(), ALARM_EARLY_MILLIS)) {
                        RecordingAlarmAction.MISSED -> fail(id, RecordingFailure.MISSED)
                        RecordingAlarmAction.START_NOW -> if (!startService(id)) fail(id, RecordingFailure.START_BLOCKED)
                        RecordingAlarmAction.ARM -> IptvRecordingAlarms.arm(context, id, entry.startMillis)
                    }
                    publish()
                }
            } finally { done() }
        }
    }

    internal fun rearm(done: () -> Unit) {
        scope.launch {
            try {
                mutex.withLock {
                    load()
                    val now = System.currentTimeMillis()
                    store.all().filter { it.status == RecordingStatus.SCHEDULED }.forEach { plan(it, now) }
                    publish()
                }
            } finally { done() }
        }
    }

    private suspend fun create(session: IptvProfileAccess.Session, source: IptvSourceRef, channelId: String,
        programme: GuideProgramme?, window: RecordingWindow, location: String?, named: String? = null, fixtureKey: String? = null): IptvRecordResult =
        withContext(Dispatchers.IO) {
            require(source.profileId == session.profileId)
            mutex.withLock {
                load()
                createLocked(session, source, channelId, programme, window, location, named, fixtureKey)
            }
        }

    private fun createLocked(session: IptvProfileAccess.Session, source: IptvSourceRef, channelId: String,
        programme: GuideProgramme?, window: RecordingWindow, location: String?, named: String?, fixtureKey: String?): IptvRecordResult {
        val now = System.currentTimeMillis()
        val found = try {
            access.use(session) {
                val stored = catalogue.sources(source.profileId).singleOrNull { it.ref == source }?.takeIf { it.playbackEligible }
                val item = stored?.let { catalogue.playbackItem(source, channelId) }
                if (stored == null || item == null) null else Triple(stored, item,
                    catalogue.accounts(source.profileId).firstOrNull { it.id == stored.accountId }?.maxStreams ?: 1)
            }
        } catch (_: IllegalStateException) { return IptvRecordResult.Refused(IptvRecordRefusal.PROFILE_CHANGED) }
            ?: return IptvRecordResult.Refused(IptvRecordRefusal.CHANNEL_UNAVAILABLE)
        val (stored, item, streams) = found
        val active = store.all().filter { it.status.holdsConnection }
        val requested = RecordingSpan.of(window.startMillis, window.stopMillis, programme?.start?.epochMillis, programme?.stop?.epochMillis)
        val core = RecordingSlot(stored.accountId, requested.coreStartMillis, requested.coreStopMillis)
        if (active.any { it.source == source && it.channelId == channelId && it.slot.startMillis < core.stopMillis && it.slot.stopMillis > core.startMillis }) {
            return IptvRecordResult.Refused(IptvRecordRefusal.ALREADY_RECORDING)
        }
        if (recordingConflicts(active.filter { it.profileId == source.profileId }.map { it.slot }, core, streams)) {
            return IptvRecordResult.Refused(IptvRecordRefusal.CONNECTION_LIMIT)
        }
        val neighbours = active.filter { it.profileId == source.profileId && ((it.source == source && it.channelId == channelId) || it.accountId == stored.accountId) }
        val trim = trimRecordingPadding(requested, neighbours.map { it.span })
        val immediate = trim.candidate.startMillis <= now
        if (!immediate && !IptvRecordingAlarms.exactAllowed(context)) return IptvRecordResult.Refused(IptvRecordRefusal.EXACT_ALARMS_DENIED)
        val place = when (val result = if (location == null) targets.preferred() else targets.place(location)) {
            is IptvPlaceResult.Ready -> result.place
            IptvPlaceResult.Missing -> return IptvRecordResult.Refused(IptvRecordRefusal.STORAGE_MISSING)
            IptvPlaceResult.ReadOnly -> return IptvRecordResult.Refused(IptvRecordRefusal.STORAGE_READ_ONLY)
            IptvPlaceResult.ShareMissing -> return IptvRecordResult.Refused(IptvRecordRefusal.SHARE_MISSING)
        }
        val duration = trim.candidate.stopMillis - trim.candidate.startMillis
        val committed = active.filter { it.status == RecordingStatus.RECORDING && it.storage == place.storage }
            .sumOf { RecordingStorage.estimatedBytes(it.stopMillis - now) }
        if (place is IptvRecordingPlace.Share) {
            if (!RecordingStorage.canStart(targets.spoolFreeBytes())) return IptvRecordResult.Refused(IptvRecordRefusal.LOW_STORAGE)
            val shareFree = livePreferences.shareFreeBytes
            if (shareFree != null && !RecordingStorage.hasRoomFor(shareFree, duration, committed, 0)) return IptvRecordResult.Refused(IptvRecordRefusal.LOW_STORAGE)
        } else if (place is IptvRecordingPlace.Media && !RecordingStorage.canStart(targets.spoolFreeBytes())) {
            return IptvRecordResult.Refused(IptvRecordRefusal.LOW_STORAGE)
        } else if (!RecordingStorage.hasRoomFor(targets.freeBytes(place), duration, committed)) {
            return IptvRecordResult.Refused(IptvRecordRefusal.LOW_STORAGE)
        }
        if (immediate) {
            if ((admission.snapshot().upstreamsByAccount[admissionAccount(source.profileId, stored.accountId)] ?: 0) >= streams) return IptvRecordResult.Refused(IptvRecordRefusal.NO_FREE_CONNECTION)
        }
        val language = Locale.getDefault().language
        val title = named ?: programme?.let { p -> (p.titles.firstOrNull { it.language?.substringBefore('-') == language } ?: p.titles.firstOrNull())?.text }
        val description = programme?.let { p -> (p.descriptions.firstOrNull { it.language?.substringBefore('-') == language } ?: p.descriptions.firstOrNull())?.text }
        val entry = IptvRecording(id = UUID.randomUUID().toString(), profileId = source.profileId, sourceId = source.sourceId,
            accountId = stored.accountId, channelId = channelId, channelName = (item.overlay.customName ?: item.channel.data.name).let { RecordingText.display(it).ifEmpty { it.trim() } }.take(240),
            title = title?.let(RecordingText::display)?.takeIf { it.isNotEmpty() }?.take(500), description = description?.trim()?.takeIf { it.isNotEmpty() }?.take(4000),
            startMillis = trim.candidate.startMillis, stopMillis = trim.candidate.stopMillis, status = RecordingStatus.SCHEDULED,
            programmeStartMillis = programme?.start?.epochMillis, programmeStopMillis = programme?.stop?.epochMillis, createdAtMillis = now,
            storage = place.storage, storageLabel = place.label?.take(240), fixtureKey = fixtureKey?.take(400))
        try { store.insert(entry) } catch (error: Exception) {
            IptvLog.failure("recording save", error)
            return IptvRecordResult.Refused(if (error is IllegalStateException) IptvRecordRefusal.LIST_FULL else IptvRecordRefusal.LOW_STORAGE)
        }
        neighbours.zip(trim.neighbours).forEach { (neighbour, span) -> retime(neighbour, span, now) }
        if (immediate) {
            if (!startService(entry.id)) {
                fail(entry.id, RecordingFailure.START_BLOCKED)
                publish()
                return IptvRecordResult.Refused(IptvRecordRefusal.START_BLOCKED)
            }
        } else IptvRecordingAlarms.arm(context, entry.id, entry.startMillis)
        publish()
        IptvLog.info("recording ${if (immediate) "started" else "scheduled"}")
        return IptvRecordResult.Accepted(store.get(entry.id) ?: entry, immediate)
    }

    private fun update(id: String, transform: (IptvRecording) -> IptvRecording): IptvRecording? = try {
        store.update(id, transform)
    } catch (error: Exception) {
        IptvLog.failure("recording save", error)
        store.get(id)
    }

    private fun remove(id: String): IptvRecording? {
        val entry = store.get(id)
        return try { store.remove(id) } catch (error: Exception) {
            IptvLog.failure("recording save", error)
            entry
        }
    }

    private fun retime(entry: IptvRecording, span: RecordingSpan, now: Long) {
        if (span.startMillis == entry.startMillis && span.stopMillis == entry.stopMillis) return
        val scheduled = entry.status == RecordingStatus.SCHEDULED && entry.startMillis > now
        val start = if (scheduled) span.startMillis else entry.startMillis
        val stop = maxOf(span.stopMillis, now + 1)
        if (stop <= start) return
        try {
            update(entry.id) { it.copy(startMillis = start, stopMillis = stop) }
            if (scheduled && start != entry.startMillis) IptvRecordingAlarms.arm(context, entry.id, start)
        } catch (error: Exception) { IptvLog.failure("recording retime", error) }
    }

    private suspend fun record(id: String, holder: IptvRecordingProgress) {
        val started = try {
            while (true) {
                val wait = mutex.withLock {
                    load()
                    val current = store.get(id)?.takeIf { it.status == RecordingStatus.SCHEDULED } ?: return
                    current.startMillis - System.currentTimeMillis()
                }
                if (wait <= 0) break
                delay(minOf(wait, START_CHECK_MILLIS))
            }
            val remote = folderNames(mutex.withLock { store.get(id)?.storage })
            mutex.withLock {
                load()
                val current = store.get(id)?.takeIf { it.status == RecordingStatus.SCHEDULED } ?: return
                val now = System.currentTimeMillis()
                if (current.stopMillis <= now) { fail(id, RecordingFailure.MISSED); publish(); return }
                val place = (targets.resolve(current.storage) as? IptvPlaceResult.Ready)?.place ?: run {
                    fail(id, RecordingFailure.STORAGE_MISSING)
                    publish()
                    IptvLog.info("recording location unavailable")
                    return
                }
                val used = store.all().filter { it.id != current.id }.mapNotNull { it.file?.substringAfterLast('/')?.substringAfterLast('\\') }.toSet()
                val name = RecordingFiles.name(current.channelName, current.title, now, ZoneId.systemDefault()) { candidate ->
                    candidate in used || candidate.lowercase(Locale.ROOT).let { it in remote || RecordingFiles.partial(it) in remote } ||
                        (place is IptvRecordingPlace.Local && (File(place.directory, candidate).exists() || File(place.directory, RecordingFiles.partial(candidate)).exists()))
                }
                val path = when (place) {
                    is IptvRecordingPlace.Local -> File(place.directory, name).path
                    is IptvRecordingPlace.Share -> place.settings.target.path(name)
                    is IptvRecordingPlace.Media -> name
                }
                update(id) { it.copy(status = RecordingStatus.RECORDING, startedAtMillis = now, file = path, storageLabel = place.label?.take(240) ?: it.storageLabel) }
                    ?.takeIf { it.status == RecordingStatus.RECORDING && it.file != null }?.let { it to place }.also { publish() }
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                val stop = synchronized(this@IptvRecorder) { stops[id] }
                mutex.withLock {
                    if (stop == RecordingStop.USER) update(id) { current ->
                        if (current.status != RecordingStatus.SCHEDULED) current
                        else current.copy(status = RecordingStatus.CANCELLED, finishedAtMillis = System.currentTimeMillis())
                    } else if (stop != RecordingStop.REMOVED) fail(id, RecordingFailure.INTERRUPTED)
                    publish()
                }
            }
            throw cancel
        } ?: return
        val (entry, place) = started
        val path = requireNotNull(entry.file)
        val spool = targets.spool(entry.id)
        val copied = AtomicBoolean(false)
        val output = when (place) {
            is IptvRecordingPlace.Local -> File(path).let { main ->
                IptvRecordingOutput(place.directory, { index, _ -> File(place.directory, RecordingFiles.partial(RecordingParts.name(main.name, index))) },
                    place.partBytes, if (place.partBytes == Long.MAX_VALUE) 0 else RecordingParts.SEGMENT_HEADROOM_BYTES)
            }
            is IptvRecordingPlace.Share, is IptvRecordingPlace.Media -> { spool.mkdirs(); IptvRecordingUploader.spoolOutput(spool) }
        }
        val link = connector(place)
        val upload = link?.let { remote ->
            scope.async(start = CoroutineStart.LAZY) {
                uploader(place, remote).upload(spool, path, { holder.committed }, { copied.get() }, UPLOAD_PATIENCE_MILLIS)
            }.also { track(id, it); it.start() }
        }
        var failure: RecordingFailure? = null
        var lease: LiveConsumerLease? = null
        val ticker = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(PROGRESS_MILLIS)
                if (++ticks % PERSIST_TICKS == 0) mutex.withLock {
                    update(id) { it.copy(bytes = holder.bytes, gaps = holder.gaps, parts = if (upload == null) holder.parts.coerceIn(1, RecordingParts.MAX_PARTS) else 1) }
                }
                if ((store.get(id)?.stopMillis ?: Long.MAX_VALUE) <= System.currentTimeMillis()) stop(id, RecordingStop.ENDED)
                publish()
            }
        }
        try {
            failure = run {
                if (!RecordingStorage.canStart(targets.freeBytes(place))) return@run RecordingFailure.LOW_STORAGE
                val target = try { resolve(entry) } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { IptvLog.failure("recording address", error); return@run RecordingFailure.SOURCE_UNAVAILABLE }
                    ?: return@run RecordingFailure.CHANNEL_UNAVAILABLE
                admission.setAccountLimit(target.accountId, target.streams)
                val key = AcquisitionKey(target.accountId, entry.channelId, "record:${entry.id}", target.generation)
                val reservation = ConsumerReservation(LiveConsumerRole.RECORDING, 0, BUFFER_BYTES)
                val grace = minOf(System.currentTimeMillis() + RecordingRetry.ADMISSION_GRACE_MILLIS, entry.stopMillis)
                var admitted = admission.acquire(key, ACQUISITION_BYTES, reservation)
                while (admitted is LiveAdmissionResult.Denied && admitted.reason == AdmissionDenial.ACCOUNT_LIMIT && System.currentTimeMillis() < grace) {
                    delay(RecordingRetry.ADMISSION_RETRY_MILLIS)
                    admitted = admission.acquire(key, ACQUISITION_BYTES, reservation)
                }
                when (val result = admitted) {
                    is LiveAdmissionResult.Denied -> return@run if (result.reason == AdmissionDenial.ACCOUNT_LIMIT) RecordingFailure.NO_CONNECTION else RecordingFailure.DEVICE_BUSY
                    is LiveAdmissionResult.Admitted -> lease = result.lease
                }
                IptvLog.info("recording connected")
                val stopAt = minOf(entry.stopMillis, requireNotNull(entry.startedAtMillis) + RecordingPlan.MAX_DURATION_MILLIS)
                IptvRecordingCopier().copy(target.address, target.format, output, stopAt, holder).failure
            }
        } catch (_: CancellationException) {
        } catch (error: Exception) {
            IptvLog.failure("recording", error)
            failure = failure ?: RecordingFailure.NETWORK
        } finally {
            withContext(NonCancellable) {
                ticker.cancel()
                lease?.let { admission.release(it)?.let(admission::completeClose) }
                output.close()
                copied.set(true)
                val stop = synchronized(this@IptvRecorder) { stops[id] }
                if (upload == null) finishLocal(entry, output, holder.bytes, failure, holder.gaps, stop)
                else finishShare(entry, spool, upload, holder, failure, stop, link)
            }
        }
    }

    private suspend fun finishLocal(entry: IptvRecording, output: IptvRecordingOutput, written: Long, failure: RecordingFailure?, gaps: Int,
        stop: RecordingStop?) = mutex.withLock {
        val main = File(requireNotNull(entry.file))
        val volume = RecordingLocations.volumeId(entry.storage)
        val now = System.currentTimeMillis()
        if (volume != null && volume !in targets.mountedVolumeIds()) {
            if (store.get(entry.id) == null) { publish(); return@withLock }
            val outcome = RecordingTransitions.outcome(written, RecordingFailure.STORAGE_REMOVED, gaps, stop)
            update(entry.id) { it.copy(status = outcome.status, failure = outcome.failure, bytes = written, gaps = gaps,
                file = if (written > 0) main.path else null, parts = output.parts.coerceIn(1, RecordingParts.MAX_PARTS), finishedAtMillis = now) }
            IptvLog.info("recording finished ${outcome.status} ${outcome.failure}")
            publish()
            return@withLock
        }
        val files = output.files().filter { it.isFile }.toMutableList()
        while (files.size > 1 && files.last().length() == 0L) files.removeAt(files.lastIndex).delete()
        val bytes = files.sumOf { it.length() }
        var storage: RecordingFailure? = null
        if (bytes > 0) {
            files.forEachIndexed { index, part ->
                val final = File(main.parentFile, RecordingParts.name(main.name, index + 1))
                if (part != final && !part.renameTo(final)) storage = RecordingFailure.STORAGE_ERROR
            }
        } else files.forEach { it.delete() }
        if (store.get(entry.id) == null) {
            targets.localParts(main).forEach { it.delete() }
            files.forEach { it.delete() }
            publish()
            return@withLock
        }
        val outcome = RecordingTransitions.outcome(bytes, failure ?: storage, gaps, stop)
        update(entry.id) {
            it.copy(status = outcome.status, failure = outcome.failure, bytes = bytes, gaps = gaps, file = if (bytes > 0) main.path else null,
                parts = files.size.coerceIn(1, RecordingParts.MAX_PARTS), finishedAtMillis = now)
        }
        IptvLog.info("recording finished ${outcome.status}${outcome.failure?.let { " $it" }.orEmpty()}${stop?.let { " stop=$it" }.orEmpty()}" +
            if (files.size > 1) " parts=${files.size}" else "")
        publish()
    }

    private suspend fun finishShare(entry: IptvRecording, spool: File, upload: Deferred<IptvUploadOutcome>, holder: IptvRecordingProgress,
        failure: RecordingFailure?, stop: RecordingStop?, link: IptvShareConnector?) {
        val bytes = holder.committed
        val outcome = RecordingTransitions.outcome(bytes, failure, holder.gaps, stop)
        val present = mutex.withLock {
            if (store.get(entry.id) == null) return@withLock false
            update(entry.id) {
                it.copy(status = outcome.status, failure = outcome.failure, bytes = bytes, gaps = holder.gaps, file = if (bytes > 0) it.file else null,
                    upload = bytes > 0, finishedAtMillis = System.currentTimeMillis())
            }
            IptvLog.info("recording finished ${outcome.status}${outcome.failure?.let { " $it" }.orEmpty()}${stop?.let { " stop=$it" }.orEmpty()} ${if (entry.onMedia) "media" else "share"}")
            publish()
            true
        }
        if (!present || bytes == 0L) {
            upload.cancel()
            spool.deleteRecursively()
            if (present) mutex.withLock { publish() }
            return
        }
        val result = try { upload.await() } catch (_: CancellationException) { null }
        applyUpload(entry.id, result, spool, link)
    }

    private suspend fun applyUpload(id: String, result: IptvUploadOutcome?, spool: File, link: IptvShareConnector?) = mutex.withLock {
        val media = (link as? IptvMediaStoreConnector)?.published
        if (store.get(id) == null) {
            spool.deleteRecursively()
            val remote = result?.remote
            if (result?.result == IptvUploadResult.DONE) {
                if (media != null) targets.media.delete(media)
                else if (link != null && remote != null) scope.launch {
                    try { link.connect().use { it.delete(remote) } } catch (error: Exception) { IptvLog.failure("recording remote delete", error) }
                }
            }
            publish()
            return@withLock
        }
        when {
            result == null || result.result == IptvUploadResult.PENDING -> Unit
            result.result == IptvUploadResult.DONE -> {
                update(id) {
                    it.copy(upload = false, bytes = result.bytes.takeIf { bytes -> bytes > 0 } ?: it.bytes,
                        file = if (it.onMedia) media?.toString() else result.remote ?: it.file)
                }
                spool.deleteRecursively()
            }
            else -> {
                spool.deleteRecursively()
                update(id) { it.copy(upload = false, file = null, failure = RecordingFailure.STORAGE_ERROR) }
            }
        }
        IptvLog.info("recording upload ${result?.result ?: "STOPPED"}${result?.error?.let { " $it" }.orEmpty()}")
        publish()
    }

    private suspend fun folderNames(storage: String?): Set<String> {
        val place = (targets.resolve(storage) as? IptvPlaceResult.Ready)?.place ?: return emptySet()
        if (place is IptvRecordingPlace.Local) return emptySet()
        val listing = scope.async {
            when (place) {
                is IptvRecordingPlace.Share -> place.connector.connect().use { it.list(place.settings.target.folder) }
                is IptvRecordingPlace.Media -> place.target.names()
                is IptvRecordingPlace.Local -> emptyList()
            }
        }
        val names = try { withTimeoutOrNull(LISTING_MILLIS) { listing.await() } } catch (cancel: CancellationException) { listing.cancel(); throw cancel }
            catch (error: Exception) { IptvLog.failure("recording names", error); null }
        return names.orEmpty().mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    }

    private fun uploader(place: IptvRecordingPlace, connector: IptvShareConnector) =
        IptvRecordingUploader(connector, onFree = { if (place is IptvRecordingPlace.Share) livePreferences.shareFreeBytes = it })

    private fun connector(place: IptvRecordingPlace): IptvShareConnector? = when (place) {
        is IptvRecordingPlace.Local -> null
        is IptvRecordingPlace.Share -> place.connector
        is IptvRecordingPlace.Media -> place.target.connector()
    }

    private fun track(id: String, job: Job) {
        synchronized(this) {
            uploadJobs[id] = job
            uploadingIds.value = uploadJobs.keys.toSet(); runningChanges.value += 1
            job.invokeOnCompletion {
                synchronized(this) {
                    if (uploadJobs[id] === job) uploadJobs.remove(id)
                    uploadingIds.value = uploadJobs.keys.toSet(); runningChanges.value += 1
                }
            }
        }
    }

    private fun launchUpload(id: String): Boolean = synchronized(this) {
        if (id in jobs || id in uploadJobs) return false
        val job = scope.launch(start = CoroutineStart.LAZY) { uploadPending(id) }
        track(id, job)
        job.start()
        true
    }

    private suspend fun uploadPending(id: String) {
        val entry = mutex.withLock { load(); store.get(id)?.takeIf { it.upload && it.status.finished } } ?: return
        val path = entry.file ?: return
        val place = (targets.resolve(entry.storage) as? IptvPlaceResult.Ready)?.place ?: return
        val link = connector(place) ?: return
        val spool = targets.spool(id)
        val result = uploader(place, link).upload(spool, path, { Long.MAX_VALUE }, { true }, RETRY_PATIENCE_MILLIS)
        applyUpload(id, result, spool, link)
    }

    private suspend fun resolve(entry: IptvRecording): Target? {
        val session = access.open(entry.profileId)
        val ref = entry.source
        val found = access.use(session) {
            val source = catalogue.sources(entry.profileId).singleOrNull { it.ref == ref }?.takeIf { it.playbackEligible }
            val item = source?.let { catalogue.playbackItem(ref, entry.channelId) }
            if (source == null || item == null) null else Resolved(source, item,
                catalogue.accounts(entry.profileId).firstOrNull { it.id == source.accountId }?.maxStreams ?: 1,
                if (source.kind == IptvSourceKind.M3U) null else catalogue.connection(ref))
        } ?: return null
        val address = when (found.source.kind) {
            IptvSourceKind.STALKER -> IptvStalkerClient().streamUrl(requireNotNull(found.connection),
                found.item.attributes[IptvStalkerClient.COMMAND_ATTRIBUTE] ?: return null)
            IptvSourceKind.XTREAM -> IptvXtreamClient.streamUrl(requireNotNull(found.connection), found.item.channel.data.locator)
            IptvSourceKind.M3U -> found.item.channel.data.locator
        }
        return Target(admissionAccount(entry.profileId, found.source.accountId), found.streams, found.source.activeGeneration ?: 0, address,
            found.item.overlay.streamFormat.takeIf { it != IptvStreamFormat.AUTO } ?: livePreferences.defaultFormat)
    }

    private suspend fun removeWhere(profileId: Int?) {
        val removed = withContext(Dispatchers.IO) {
            mutex.withLock {
                load()
                val list = store.all().filter { profileId == null || it.profileId == profileId }
                try { if (profileId == null) store.clear() else store.removeProfile(profileId) } catch (error: Exception) { IptvLog.failure("recordings save", error) }
                list.forEach { IptvRecordingAlarms.cancel(context, it.id); deleteFiles(it) }
                publish()
                list
            }
        }
        synchronized(this) { removed.mapNotNull { entry -> uploadJobs[entry.id]?.cancel(); jobs[entry.id]?.also { stops[entry.id] = RecordingStop.REMOVED } } }.forEach { it.cancel() }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val now = System.currentTimeMillis()
        store.all().forEach { entry ->
            when (entry.status) {
                RecordingStatus.RECORDING -> interrupted(entry, now)
                RecordingStatus.SCHEDULED -> plan(entry, now)
                else -> Unit
            }
        }
        publish()
        scope.launch { retryUploads() }
    }

    private fun interrupted(entry: IptvRecording, now: Long) {
        if (entry.spooled) {
            val spool = targets.spool(entry.id)
            val pending = spool.listFiles()?.any { it.isFile } == true
            val outcome = RecordingTransitions.interrupted(if (pending) maxOf(entry.bytes, 1) else 0)
            if (!pending) spool.deleteRecursively()
            update(entry.id) { it.copy(status = outcome.status, failure = outcome.failure, upload = pending, file = if (pending) it.file else null, finishedAtMillis = now) }
            IptvLog.info("recording interrupted ${outcome.status} ${if (entry.onMedia) "media" else "share"}")
            return
        }
        val main = entry.file?.let(::File)
        val volume = RecordingLocations.volumeId(entry.storage)
        if (main != null && volume != null && volume !in targets.mountedVolumeIds()) {
            val outcome = RecordingTransitions.interrupted(entry.bytes)
            update(entry.id) { it.copy(status = outcome.status, failure = outcome.failure, file = if (entry.bytes > 0) it.file else null, finishedAtMillis = now) }
            IptvLog.info("recording interrupted ${outcome.status} drive missing")
            return
        }
        val parts = main?.let { file -> targets.localParts(file).mapIndexed { index, part ->
            val final = File(file.parentFile, RecordingParts.name(file.name, index + 1))
            if (part == final || part.renameTo(final)) final else part
        } }.orEmpty()
        val bytes = parts.sumOf { it.length() }
        if (bytes == 0L) parts.forEach { it.delete() }
        val outcome = RecordingTransitions.interrupted(bytes)
        update(entry.id) {
            it.copy(status = outcome.status, failure = outcome.failure, bytes = bytes, file = if (bytes > 0) main?.path else null,
                parts = parts.size.coerceIn(1, RecordingParts.MAX_PARTS), finishedAtMillis = now)
        }
        IptvLog.info("recording interrupted ${outcome.status}")
    }

    private fun plan(entry: IptvRecording, now: Long) {
        when (recordingAlarmAction(entry.window, now)) {
            RecordingAlarmAction.MISSED -> fail(entry.id, RecordingFailure.MISSED)
            RecordingAlarmAction.START_NOW -> if (IptvRecordingAlarms.exactAllowed(context)) IptvRecordingAlarms.arm(context, entry.id, now + 1_000)
                else if (!startService(entry.id)) fail(entry.id, RecordingFailure.START_BLOCKED)
            RecordingAlarmAction.ARM -> IptvRecordingAlarms.arm(context, entry.id, entry.startMillis)
        }
    }

    private fun fail(id: String, failure: RecordingFailure) {
        update(id) { current ->
            if (current.status != RecordingStatus.SCHEDULED) current
            else current.copy(status = RecordingStatus.FAILED, failure = failure, finishedAtMillis = System.currentTimeMillis())
        }
        IptvRecordingAlarms.cancel(context, id)
    }

    private fun startService(id: String?): Boolean = try {
        ContextCompat.startForegroundService(context, IptvRecordingService.intent(context, id))
        true
    } catch (error: Exception) {
        IptvLog.failure("recording start", error)
        false
    }

    private fun deleteFiles(entry: IptvRecording): Boolean {
        if (entry.onMedia) {
            targets.spool(entry.id).deleteRecursively()
            val id = RecordingMedia.id(entry.file) ?: return true
            return targets.media.delete(id) != IptvMediaDelete.NOT_OWNED
        }
        if (entry.onShare) {
            targets.spool(entry.id).deleteRecursively()
            val path = entry.file ?: return true
            val share = (targets.resolve(entry.storage) as? IptvPlaceResult.Ready)?.place as? IptvRecordingPlace.Share ?: return true
            val whole = entry.status.finished && !entry.upload
            scope.launch {
                try { share.connector.connect().use { it.delete(RecordingFiles.partial(path)); if (whole) it.delete(path) } }
                catch (error: Exception) { IptvLog.failure("recording remote delete", error) }
            }
            return true
        }
        val file = entry.file?.let(::File) ?: return true
        if (!targets.owns(file.absoluteFile.parentFile ?: return true)) return true
        targets.localParts(file).forEach { it.delete() }
        File(RecordingFiles.partial(file.path)).delete()
        file.delete()
        return true
    }

    private fun publish() = synchronized(this) {
        val live = progress.mapValues { it.value.bytes }
        entries.value = store.all().map { entry ->
            live[entry.id]?.takeIf { entry.status == RecordingStatus.RECORDING }?.let { entry.copy(bytes = it) } ?: entry
        }
    }

    private companion object {
        const val UPLOAD_PATIENCE_MILLIS = 2 * 60 * 1000L
        const val RETRY_PATIENCE_MILLIS = 60 * 1000L
        const val UPLOAD_RETRY_MILLIS = 15 * 60 * 1000L
        const val ACQUISITION_BYTES = 4L * 1024 * 1024
        const val BUFFER_BYTES = 1L * 1024 * 1024
        const val ALARM_EARLY_MILLIS = 3 * 60 * 1000L
        const val PROGRESS_MILLIS = 2_000L
        const val PERSIST_TICKS = 30
        const val START_CHECK_MILLIS = 30_000L
        const val LISTING_MILLIS = 8_000L
    }
}
