package com.nuvio.tv.core.recording

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import com.nuvio.tv.core.iptv.RecordingPlan
import com.nuvio.tv.core.iptv.RecordingRetry
import com.nuvio.tv.core.iptv.RecordingSlot
import com.nuvio.tv.core.iptv.RecordingSpan
import com.nuvio.tv.core.iptv.RecordingStatus
import com.nuvio.tv.core.iptv.RecordingStop
import com.nuvio.tv.core.iptv.RecordingStorage
import com.nuvio.tv.core.iptv.RecordingTransitions
import com.nuvio.tv.core.iptv.RecordingWindow
import com.nuvio.tv.core.iptv.recordingAlarmAction
import com.nuvio.tv.core.iptv.recordingConflicts
import com.nuvio.tv.core.iptv.trimRecordingPadding
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.data.iptv.IptvCatalogueItem
import com.nuvio.tv.data.iptv.IptvCatalogueStore
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvProfileAccess
import com.nuvio.tv.data.iptv.IptvRecording
import com.nuvio.tv.data.iptv.IptvRecordingCopier
import com.nuvio.tv.data.iptv.IptvRecordingProgress
import com.nuvio.tv.data.iptv.IptvRecordingStore
import com.nuvio.tv.data.iptv.IptvSource
import com.nuvio.tv.data.iptv.IptvSourceConnection
import com.nuvio.tv.data.iptv.IptvSourceKind
import com.nuvio.tv.data.iptv.IptvSourceRef
import com.nuvio.tv.data.iptv.IptvStalkerClient
import com.nuvio.tv.data.iptv.IptvStreamFormat
import com.nuvio.tv.data.iptv.IptvXtreamClient
import com.nuvio.tv.data.iptv.admissionAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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

enum class IptvRecordRefusal {
    CONNECTION_LIMIT, NO_FREE_CONNECTION, LOW_STORAGE, PROGRAMME_ENDED, CHANNEL_UNAVAILABLE,
    ALREADY_RECORDING, LIST_FULL, START_BLOCKED, PROFILE_CHANGED, EXACT_ALARMS_DENIED,
}

sealed interface IptvRecordResult {
    data class Accepted(val recording: IptvRecording) : IptvRecordResult
    data class Refused(val reason: IptvRecordRefusal) : IptvRecordResult
}

@Singleton
class IptvRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogue: IptvCatalogueStore,
    private val access: IptvProfileAccess,
    private val admission: LiveSessionAdmission,
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
    private var loaded = false

    val running: StateFlow<Set<String>> = runningIds.asStateFlow()
    val all: StateFlow<List<IptvRecording>> = entries.asStateFlow()

    init { scope.launch { mutex.withLock { load() } } }

    fun recordings(profileId: Int): StateFlow<List<IptvRecording>> = synchronized(profileEntries) {
        profileEntries.getOrPut(profileId) {
            entries.map { list -> list.filter { it.profileId == profileId } }
                .stateIn(scope, SharingStarted.Eagerly, entries.value.filter { it.profileId == profileId })
        }
    }

    suspend fun recordNow(session: IptvProfileAccess.Session, source: IptvSourceRef, channelId: String,
        programme: GuideProgramme? = null): IptvRecordResult {
        val window = RecordingPlan.now(System.currentTimeMillis(), programme?.stop?.epochMillis)
            ?: return IptvRecordResult.Refused(IptvRecordRefusal.PROGRAMME_ENDED)
        return create(session, source, channelId, programme, window)
    }

    suspend fun schedule(session: IptvProfileAccess.Session, source: IptvSourceRef, channelId: String,
        programme: GuideProgramme): IptvRecordResult {
        val window = RecordingPlan.programme(System.currentTimeMillis(), programme.start.epochMillis, programme.stop?.epochMillis)
            ?: return IptvRecordResult.Refused(IptvRecordRefusal.PROGRAMME_ENDED)
        return create(session, source, channelId, programme, window)
    }

    suspend fun cancel(id: String): Boolean = withContext(Dispatchers.IO) {
        val status = mutex.withLock {
            load()
            val current = store.get(id) ?: return@withLock null
            if (current.status == RecordingStatus.SCHEDULED) {
                IptvRecordingAlarms.cancel(context, current.id)
                store.update(id) { it.copy(status = RecordingStatus.CANCELLED, finishedAtMillis = System.currentTimeMillis()) }
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

    fun stop(id: String): Boolean {
        val job = synchronized(this) { jobs[id]?.also { if (id !in stops) stops[id] = RecordingStop.USER } } ?: return false
        job.cancel()
        return true
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val job = synchronized(this@IptvRecorder) { jobs[id]?.also { stops[id] = RecordingStop.REMOVED } }
        val removed = mutex.withLock {
            load()
            store.remove(id)?.also { IptvRecordingAlarms.cancel(context, it.id); deleteFiles(it); publish() }
        }
        job?.cancel()
        removed != null
    }

    fun file(recording: IptvRecording): File? =
        recording.file?.let(::File)?.takeIf { recording.status.finished && it.isFile && it.length() > 0 }

    fun exactAlarmsAllowed(): Boolean = IptvRecordingAlarms.exactAllowed(context)

    fun exactAlarmSettings(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent.takeIf { context.packageManager.resolveActivity(it, 0) != null }
    }

    fun freeBytes(): Long = runCatching { directory().usableSpace }.getOrDefault(0L)

    override fun removeProfile(profileId: Int) = runBlocking { removeWhere(profileId) }

    override fun clearAllProfiles() = runBlocking { removeWhere(null) }

    internal fun begin(id: String) {
        synchronized(this) {
            if (id in jobs) return
            val holder = IptvRecordingProgress()
            progress[id] = holder
            val job = scope.launch { record(id, holder) }
            jobs[id] = job
            runningIds.value = jobs.keys.toSet()
            job.invokeOnCompletion {
                synchronized(this) {
                    if (jobs[id] === job) { jobs.remove(id); progress.remove(id) }
                    stops.remove(id)
                    runningIds.value = jobs.keys.toSet()
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
        val ids = running.value
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
        programme: GuideProgramme?, window: RecordingWindow): IptvRecordResult = withContext(Dispatchers.IO) {
        require(source.profileId == session.profileId)
        mutex.withLock {
            load()
            createLocked(session, source, channelId, programme, window)
        }
    }

    private fun createLocked(session: IptvProfileAccess.Session, source: IptvSourceRef, channelId: String,
        programme: GuideProgramme?, window: RecordingWindow): IptvRecordResult {
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
        if (immediate) {
            if ((admission.snapshot().upstreamsByAccount[admissionAccount(source.profileId, stored.accountId)] ?: 0) >= streams) return IptvRecordResult.Refused(IptvRecordRefusal.NO_FREE_CONNECTION)
            if (!RecordingStorage.canStart(freeBytes())) return IptvRecordResult.Refused(IptvRecordRefusal.LOW_STORAGE)
        }
        val language = Locale.getDefault().language
        val title = programme?.let { p -> (p.titles.firstOrNull { it.language?.substringBefore('-') == language } ?: p.titles.firstOrNull())?.text }
        val description = programme?.let { p -> (p.descriptions.firstOrNull { it.language?.substringBefore('-') == language } ?: p.descriptions.firstOrNull())?.text }
        val entry = IptvRecording(id = UUID.randomUUID().toString(), profileId = source.profileId, sourceId = source.sourceId,
            accountId = stored.accountId, channelId = channelId, channelName = (item.overlay.customName ?: item.channel.data.name).take(240),
            title = title?.trim()?.takeIf { it.isNotEmpty() }?.take(500), description = description?.trim()?.takeIf { it.isNotEmpty() }?.take(4000),
            startMillis = trim.candidate.startMillis, stopMillis = trim.candidate.stopMillis, status = RecordingStatus.SCHEDULED,
            programmeStartMillis = programme?.start?.epochMillis, programmeStopMillis = programme?.stop?.epochMillis, createdAtMillis = now)
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
        return IptvRecordResult.Accepted(store.get(entry.id) ?: entry)
    }

    private fun retime(entry: IptvRecording, span: RecordingSpan, now: Long) {
        if (span.startMillis == entry.startMillis && span.stopMillis == entry.stopMillis) return
        val scheduled = entry.status == RecordingStatus.SCHEDULED && entry.startMillis > now
        val start = if (scheduled) span.startMillis else entry.startMillis
        val stop = maxOf(span.stopMillis, now + 1)
        if (stop <= start) return
        try {
            store.update(entry.id) { it.copy(startMillis = start, stopMillis = stop) }
            if (scheduled && start != entry.startMillis) IptvRecordingAlarms.arm(context, entry.id, start)
        } catch (error: Exception) { IptvLog.failure("recording retime", error) }
    }

    private suspend fun record(id: String, holder: IptvRecordingProgress) {
        val entry = try {
            while (true) {
                val wait = mutex.withLock {
                    load()
                    val current = store.get(id)?.takeIf { it.status == RecordingStatus.SCHEDULED } ?: return
                    current.startMillis - System.currentTimeMillis()
                }
                if (wait <= 0) break
                delay(minOf(wait, START_CHECK_MILLIS))
            }
            mutex.withLock {
                load()
                val current = store.get(id)?.takeIf { it.status == RecordingStatus.SCHEDULED } ?: return
                val now = System.currentTimeMillis()
                if (current.stopMillis <= now) { fail(id, RecordingFailure.MISSED); publish(); return }
                val file = File(directory(), RecordingFiles.name(current.channelName, current.title, now, current.id, ZoneId.systemDefault()))
                store.update(id) { it.copy(status = RecordingStatus.RECORDING, startedAtMillis = now, file = file.path) }.also { publish() }
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                val stop = synchronized(this@IptvRecorder) { stops[id] }
                mutex.withLock {
                    if (stop == RecordingStop.USER) store.update(id) { current ->
                        if (current.status != RecordingStatus.SCHEDULED) current
                        else current.copy(status = RecordingStatus.CANCELLED, finishedAtMillis = System.currentTimeMillis())
                    } else if (stop != RecordingStop.REMOVED) fail(id, RecordingFailure.INTERRUPTED)
                    publish()
                }
            }
            throw cancel
        } ?: return
        val final = File(requireNotNull(entry.file))
        val part = File(RecordingFiles.partial(final.path))
        var failure: RecordingFailure? = null
        var lease: LiveConsumerLease? = null
        val ticker = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(PROGRESS_MILLIS)
                if (++ticks % PERSIST_TICKS == 0) mutex.withLock { store.update(id) { it.copy(bytes = holder.bytes, gaps = holder.gaps) } }
                if ((store.get(id)?.stopMillis ?: Long.MAX_VALUE) <= System.currentTimeMillis()) stop(id)
                publish()
            }
        }
        try {
            failure = run {
                if (!RecordingStorage.canStart(freeBytes())) return@run RecordingFailure.LOW_STORAGE
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
                IptvRecordingCopier().copy(target.address, target.format, part, stopAt, holder).failure
            }
        } catch (_: CancellationException) {
        } catch (error: Exception) {
            IptvLog.failure("recording", error)
            failure = failure ?: RecordingFailure.NETWORK
        } finally {
            withContext(NonCancellable) {
                ticker.cancel()
                lease?.let { admission.release(it)?.let(admission::completeClose) }
                val stop = synchronized(this@IptvRecorder) { stops[id] }
                finish(entry, part, final, failure, holder.gaps, stop)
            }
        }
    }

    private suspend fun finish(entry: IptvRecording, part: File, final: File, failure: RecordingFailure?, gaps: Int, stop: RecordingStop?) = mutex.withLock {
        val bytes = if (part.isFile) part.length() else 0L
        var file: File? = null
        var storage: RecordingFailure? = null
        if (bytes > 0) {
            file = if (part.renameTo(final)) final else { storage = RecordingFailure.STORAGE_ERROR; part }
        } else part.delete()
        if (store.get(entry.id) == null) {
            file?.delete(); part.delete()
            publish()
            return@withLock
        }
        val outcome = RecordingTransitions.outcome(bytes, failure ?: storage, gaps, stop)
        store.update(entry.id) {
            it.copy(status = outcome.status, failure = outcome.failure, bytes = bytes, gaps = gaps, file = file?.path, finishedAtMillis = System.currentTimeMillis())
        }
        IptvLog.info("recording finished ${outcome.status}${outcome.failure?.let { " $it" }.orEmpty()}")
        publish()
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
        return Target(admissionAccount(entry.profileId, found.source.accountId), found.streams, found.source.activeGeneration ?: 0, address, found.item.overlay.streamFormat)
    }

    private suspend fun removeWhere(profileId: Int?) {
        val removed = withContext(Dispatchers.IO) {
            mutex.withLock {
                load()
                val list = if (profileId == null) store.clear() else store.removeProfile(profileId)
                list.forEach { IptvRecordingAlarms.cancel(context, it.id); deleteFiles(it) }
                publish()
                list
            }
        }
        synchronized(this) { removed.mapNotNull { entry -> jobs[entry.id]?.also { stops[entry.id] = RecordingStop.REMOVED } } }.forEach { it.cancel() }
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
    }

    private fun interrupted(entry: IptvRecording, now: Long) {
        val final = entry.file?.let(::File)
        val part = final?.let { File(RecordingFiles.partial(it.path)) }
        var file: File? = null
        if (part != null && part.isFile && part.length() > 0) file = if (part.renameTo(final)) final else part
        else if (final != null && final.isFile && final.length() > 0) file = final
        part?.takeIf { it.isFile && it != file }?.delete()
        val bytes = file?.length() ?: 0L
        val outcome = RecordingTransitions.interrupted(bytes)
        store.update(entry.id) { it.copy(status = outcome.status, failure = outcome.failure, bytes = bytes, file = file?.path, finishedAtMillis = now) }
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
        store.update(id) { current ->
            if (current.status != RecordingStatus.SCHEDULED) current
            else current.copy(status = RecordingStatus.FAILED, failure = failure, finishedAtMillis = System.currentTimeMillis())
        }
        IptvRecordingAlarms.cancel(context, id)
    }

    private fun startService(id: String): Boolean = try {
        ContextCompat.startForegroundService(context, IptvRecordingService.intent(context, id))
        true
    } catch (error: Exception) {
        IptvLog.failure("recording start", error)
        false
    }

    private fun deleteFiles(entry: IptvRecording) {
        val file = entry.file?.let(::File) ?: return
        val root = directories().map { it.absoluteFile }
        if (root.none { file.absoluteFile.parentFile == it }) return
        File(RecordingFiles.partial(file.path)).delete()
        file.delete()
    }

    private fun publish() = synchronized(this) {
        val live = progress.mapValues { it.value.bytes }
        entries.value = store.all().map { entry ->
            live[entry.id]?.takeIf { entry.status == RecordingStatus.RECORDING }?.let { entry.copy(bytes = it) } ?: entry
        }
    }

    private fun directories(): List<File> = listOfNotNull(context.getExternalFilesDir(DIRECTORY), File(context.filesDir, DIRECTORY))

    private fun directory(): File {
        val external = context.getExternalFilesDir(DIRECTORY)?.takeIf {
            Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED && (it.isDirectory || it.mkdirs())
        }
        return external ?: File(context.filesDir, DIRECTORY).also { it.mkdirs() }
    }

    private companion object {
        const val DIRECTORY = "recordings"
        const val ACQUISITION_BYTES = 4L * 1024 * 1024
        const val BUFFER_BYTES = 1L * 1024 * 1024
        const val ALARM_EARLY_MILLIS = 3 * 60 * 1000L
        const val PROGRESS_MILLIS = 2_000L
        const val PERSIST_TICKS = 30
        const val START_CHECK_MILLIS = 30_000L
    }
}
