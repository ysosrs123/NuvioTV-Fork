@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.FixtureStatus
import com.nuvio.tv.core.iptv.RecordingStatus
import com.nuvio.tv.core.iptv.SportsChange
import com.nuvio.tv.core.iptv.SportsChangeKind
import com.nuvio.tv.core.iptv.SportsFavourites
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsFixtureText
import com.nuvio.tv.core.iptv.SportsNuvioAlert
import com.nuvio.tv.core.iptv.SportsOverlayText
import com.nuvio.tv.core.iptv.SportsPendingRecord
import com.nuvio.tv.core.iptv.SportsPendingRecords
import com.nuvio.tv.core.iptv.SportsRecordRules
import com.nuvio.tv.core.iptv.SportsRecordTarget
import com.nuvio.tv.core.iptv.SportsRecordedWindow
import com.nuvio.tv.core.iptv.SportsRefresh
import com.nuvio.tv.core.iptv.SportsReminder
import com.nuvio.tv.core.iptv.SportsReminders
import com.nuvio.tv.core.iptv.SportsSpoilers
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.recording.IptvRecordResult
import com.nuvio.tv.core.recording.IptvRecordRefusal
import com.nuvio.tv.core.recording.IptvRecorder
import com.nuvio.tv.data.iptv.IptvCatalogueStore
import com.nuvio.tv.data.iptv.IptvFixtureLink
import com.nuvio.tv.data.iptv.IptvGuideDaysPreference
import com.nuvio.tv.data.iptv.IptvListedChannel
import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.data.iptv.IptvLog
import com.nuvio.tv.data.iptv.IptvProfileAccess
import com.nuvio.tv.data.iptv.IptvRecording
import com.nuvio.tv.data.iptv.IptvSource
import com.nuvio.tv.data.iptv.IptvSourceRef
import com.nuvio.tv.data.iptv.IptvSportsFixturesRepository
import com.nuvio.tv.data.iptv.IptvSportsLive
import com.nuvio.tv.data.iptv.IptvSportsMode
import com.nuvio.tv.data.iptv.IptvSportsPreferences
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.screens.player.PlaybackTimelineState
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.net.URLEncoder
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface IptvSportsRequest {
    data object Live : IptvSportsRequest
    data class Team(val key: String) : IptvSportsRequest
}

enum class IptvTeamRecording { NONE, RULE, SET, RECORDING }

data class IptvSportsLine(val fixture: SportsFixture, val hidden: Boolean, val link: IptvFixtureLink?)

const val IPTV_TEAM_ROUTE = "iptv/team/{team}"

fun iptvTeamRoute(key: String): String = "iptv/team/${URLEncoder.encode(key, "UTF-8").replace("+", "%20")}"

@Singleton
class IptvSportsNuvio @Inject constructor(@ApplicationContext private val context: Context, private val live: IptvSportsLive,
    private val preferences: IptvSportsPreferences, private val repository: IptvSportsFixturesRepository, private val catalogue: IptvCatalogueStore,
    private val access: IptvProfileAccess, private val profiles: ProfileManager, private val livePreferences: IptvLivePreferences,
    private val recorder: IptvRecorder, private val liveLaunch: IptvLiveLaunch) {
    private val store = context.applicationContext.getSharedPreferences("iptv-live", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> IptvLog.failure("sports nuvio", error) })
    private val checking = Mutex()
    private val ruleKeys = MutableStateFlow(readRules())
    val rules: StateFlow<Set<String>> = ruleKeys.asStateFlow()
    private val pendingList = MutableStateFlow(readPending())
    val pending: StateFlow<List<SportsPendingRecord>> = pendingList.asStateFlow()
    private val fixtureList = MutableStateFlow<List<SportsFixture>>(emptyList())
    val fixtures: StateFlow<List<SportsFixture>> = fixtureList.asStateFlow()
    private val linkMap = MutableStateFlow<Map<String, List<IptvFixtureLink>>>(emptyMap())
    val links: StateFlow<Map<String, List<IptvFixtureLink>>> = linkMap.asStateFlow()
    private val spoilerKeys = MutableStateFlow<Set<String>>(emptySet())
    val spoilers: StateFlow<Set<String>> = spoilerKeys.asStateFlow()
    private val requestEvents = MutableSharedFlow<IptvSportsRequest>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val requests: SharedFlow<IptvSportsRequest> = requestEvents.asSharedFlow()
    private val noticeEvents = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notices: SharedFlow<String> = noticeEvents.asSharedFlow()
    private val alertEvents = MutableSharedFlow<SportsChange>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val alerts: SharedFlow<SportsChange> = alertEvents.asSharedFlow()
    val reminderDue: SharedFlow<SportsReminder> = live.reminderDue
    val reminders: StateFlow<Set<String>> = live.reminders
    private val summaryLines = MutableStateFlow<List<IptvSportsLine>?>(null)
    val summary: StateFlow<List<IptvSportsLine>?> = summaryLines.asStateFlow()
    private val recent = ArrayDeque<SportsChange>()
    private val muted = HashSet<String>()
    private val handled = HashSet<String>()
    private var handle: IptvSportsLive.Handle? = null
    private val reminderSurfaces = java.util.concurrent.atomic.AtomicInteger()
    private var worker: Job? = null
    private var loadedAt = 0L
    private var linkedAt = 0L
    private var linkedFor = emptySet<String>()

    init {
        scope.launch {
            live.alerts.collect { change ->
                val followed = SportsFavourites.has(preferences.favouriteTeams, change.fixture)
                if (followed) synchronized(recent) { recent.addLast(change); while (recent.size > MAX_RECENT) recent.removeFirst() }
                if (followed && !isMuted(change.key) && !(change.kind != SportsChangeKind.STARTED && change.key in spoilerKeys.value)) alertEvents.emit(change)
            }
        }
        scope.launch { live.snapshot.collect { snapshot -> if (snapshot != null && snapshot.result.enabled) merge(snapshot.result.fixtures) } }
        scope.launch { recorder.all.collect { refreshSpoilers() } }
    }

    val enabled: Boolean get() = preferences.enabled
    val alertStyle: SportsNuvioAlert get() = preferences.nuvioAlert
    val quietMillis: Long get() = preferences.nuvioQuietEndMinutes * 60_000L
    val showScores: Boolean get() = preferences.showScores
    val favourites: Set<String> get() = preferences.favouriteTeams
    val guideDays: Int get() = IptvGuideDaysPreference(livePreferences).days.future
    val earlyMinutes: Int get() = livePreferences.recordEarlyMinutes
    val lateMinutes: Int get() = livePreferences.recordLateMinutes

    fun foreground(on: Boolean) = synchronized(this) {
        if (on) {
            if (handle == null) handle = live.acquire(IptvSportsMode.BACKGROUND)
            if (worker?.isActive != true) worker = scope.launch { while (currentCoroutineContext().isActive) { maintain(false); delay(CHECK_MILLIS) } }
        } else {
            handle?.release(); handle = null
            worker?.cancel(); worker = null
        }
    }

    fun refresh() { scope.launch { maintain(true) } }

    fun isMuted(key: String): Boolean = synchronized(muted) { key in muted }

    fun mute(key: String) { synchronized(muted) { muted += key } }

    fun hasReminder(key: String): Boolean = live.has(key)

    fun toggleReminder(fixture: SportsFixture) = live.toggleReminder(fixture)

    fun linkFor(key: String): IptvFixtureLink? = linkMap.value[key]?.firstOrNull()

    fun fixture(key: String): SportsFixture? = fixtureList.value.firstOrNull { it.key == key }

    val remindersShown: Boolean get() = reminderSurfaces.get() > 0

    fun showReminders(): () -> Unit {
        reminderSurfaces.incrementAndGet()
        val released = java.util.concurrent.atomic.AtomicBoolean()
        return { if (released.compareAndSet(false, true)) reminderSurfaces.decrementAndGet() }
    }

    fun openTeam(key: String) { requestEvents.tryEmit(IptvSportsRequest.Team(key)) }

    fun watch(row: IptvListedChannel) {
        liveLaunch.channel.value = IptvHomeTune(row, profiles.activeProfileId.value, favourites = false, sport = true)
        requestEvents.tryEmit(IptvSportsRequest.Live)
    }

    fun watch(fixture: SportsFixture, fallback: IptvFixtureLink? = null) {
        val known = linkFor(fixture.key) ?: fallback
        if (known != null) { watch(known.row); return }
        scope.launch {
            val link = try { link(sources(profiles.activeProfileId.value), listOf(fixture), System.currentTimeMillis())[fixture.key]?.firstOrNull() }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { IptvLog.failure("sports nuvio watch", error); null }
            if (link != null) watch(link.row) else noticeEvents.emit(context.getString(R.string.iptv_sport5_nuvio_no_channel_now, plainTitle(fixture)))
        }
    }

    fun follow(key: String, on: Boolean) {
        val current = preferences.favouriteTeams
        preferences.favouriteTeams = if (on) (current + key).toList().takeLast(SportsFavourites.MAX).toSet() else current - key
        if (!on && key in ruleKeys.value) setRule(key, false)
        refresh()
    }

    fun setRule(key: String, on: Boolean) {
        val next = (if (on) ruleKeys.value + key else ruleKeys.value - key).take(SportsRecordRules.MAX_RULES).toSet()
        ruleKeys.value = next
        store.edit().putStringSet(RULES_KEY, next).apply()
        if (on && key !in preferences.favouriteTeams) follow(key, true) else refresh()
    }

    fun changePending(change: (List<SportsPendingRecord>) -> List<SportsPendingRecord>): List<SportsPendingRecord> = synchronized(pendingList) {
        val next = change(pendingList.value)
        if (next != pendingList.value) {
            pendingList.value = next
            store.edit().putStringSet(PENDING_KEY, next.map(SportsPendingRecords::encode).toSet()).apply()
        }
        next
    }

    fun claimPending(profileId: Int, fixtures: List<SportsFixture>): List<SportsFixture> {
        if (fixtures.isEmpty()) return emptyList()
        var claimed = emptyList<SportsFixture>()
        changePending { all -> SportsPendingRecords.claim(all, profileId, fixtures).let { (kept, taken) -> claimed = taken; kept } }
        return claimed
    }

    fun recording(fixture: SportsFixture?, team: String): IptvTeamRecording {
        val profile = profiles.activeProfileId.value
        val entry = fixture?.let { item -> recorder.all.value.firstOrNull { it.profileId == profile && it.fixtureKey == item.key && !it.status.finished } }
        return when {
            entry?.status == RecordingStatus.RECORDING -> IptvTeamRecording.RECORDING
            entry != null -> IptvTeamRecording.SET
            team in ruleKeys.value -> IptvTeamRecording.RULE
            else -> IptvTeamRecording.NONE
        }
    }

    suspend fun record(fixture: SportsFixture, fallback: IptvFixtureLink? = null): String {
        val link = linkFor(fixture.key) ?: fallback ?: return context.getString(R.string.iptv_sport5_nuvio_no_channel_now, plainTitle(fixture))
        return when (val result = schedule(fixture, link)) {
            is IptvRecordResult.Accepted -> context.getString(R.string.iptv_sport5_nuvio_recording_set_for, plainTitle(fixture))
            is IptvRecordResult.Refused -> context.getString(R.string.iptv_sport5_nuvio_record_refused, plainTitle(fixture), context.getString(iptvRecordRefusalMessage(result.reason)))
            null -> context.getString(R.string.iptv_sport5_nuvio_record_refused, plainTitle(fixture), context.getString(R.string.iptv_recording_refused_channel))
        }
    }

    fun playerStarted(): Long = System.currentTimeMillis()

    fun playerEnded(since: Long, leaving: Boolean) {
        if (leaving) return
        val keys = synchronized(recent) { recent.filter { it.detectedAt >= since }.map { it.key }.distinct() }.filter { !isMuted(it) }
        if (keys.isEmpty()) return
        val hidden = spoilerKeys.value
        val known = fixtureList.value.associateBy { it.key }
        val changed = synchronized(recent) { recent.associateBy { it.key } }
        summaryLines.value = keys.mapNotNull { key -> (known[key] ?: changed[key]?.fixture)?.let { IptvSportsLine(it, key in hidden || !preferences.showScores, linkFor(key)) } }
            .takeIf { it.isNotEmpty() }
    }

    fun closeSummary() { summaryLines.value = null }

    fun plainTitle(fixture: SportsFixture): String {
        val home = fixture.home ?: return fixture.title
        val away = fixture.away ?: return fixture.title
        return if (SportsFixtureText.awayFirst(fixture)) context.getString(R.string.iptv_sport2_at, away.name, home.name)
        else context.getString(R.string.iptv_sport2_versus, home.name, away.name)
    }

    fun hidden(ref: IptvSourceRef): Set<String> = livePreferences.preferences.getStringSet(livePreferences.key(ref, "hidden"), null).orEmpty().take(500).toSet()

    suspend fun sources(profileId: Int): List<IptvSource> = withContext(Dispatchers.IO) {
        access.use(access.open(profileId)) { catalogue.sources(profileId) }.filter { it.playbackEligible }
    }

    suspend fun link(sources: List<IptvSource>, fixtures: List<SportsFixture>, now: Long): Map<String, List<IptvFixtureLink>> {
        val wanted = fixtures.filter { it.status != FixtureStatus.FINAL }.distinctBy { it.key }
        if (wanted.isEmpty() || sources.isEmpty()) return emptyMap()
        val found = HashMap<String, MutableList<IptvFixtureLink>>()
        for (source in sources) {
            currentCoroutineContext().ensureActive()
            val links = try { repository.links(source.ref, wanted, now, hidden(source.ref)) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { IptvLog.failure("sports nuvio links", error); emptyMap() }
            wanted.forEach { fixture -> links[fixture.id]?.let { found.getOrPut(fixture.key) { mutableListOf() } += it } }
        }
        return found.mapValues { repository.ranked(it.value) }.filterValues { it.isNotEmpty() }
    }

    fun spoilersFor(fixtures: List<SportsFixture>, profileId: Int): Set<String> {
        if (!preferences.hideSpoilers) return emptySet()
        val unwatched = recorder.all.value.filter { it.profileId == profileId && it.playedAtMillis == null && it.status != RecordingStatus.FAILED &&
            it.status != RecordingStatus.CANCELLED }
        return SportsSpoilers.keys(fixtures, unwatched.mapNotNull { recording -> recording.title?.let { SportsRecordedWindow(it, recording.startMillis, recording.stopMillis) } }) +
            unwatched.mapNotNull(IptvRecording::fixtureKey)
    }

    private fun refreshSpoilers() { spoilerKeys.value = spoilersFor(fixtureList.value, profiles.activeProfileId.value) }

    private fun merge(fixtures: List<SportsFixture>) {
        val now = System.currentTimeMillis()
        fixtureList.update { current ->
            val next = LinkedHashMap<String, SportsFixture>()
            val sources = fixtures.associate { it.league to it.source }
            current.filter { old -> sources[old.league]?.let { it == old.source } != false }.forEach { next[it.key] = it }
            fixtures.forEach { next[it.key] = it }
            next.values.filter { it.startMillis + SportsRefresh.durationMillis(it) + KEEP_MILLIS > now }.sortedBy { it.startMillis }
        }
        refreshSpoilers()
    }

    private suspend fun maintain(force: Boolean) = checking.withLock {
        try {
            if (!preferences.enabled) { fixtureList.value = emptyList(); linkMap.value = emptyMap(); return@withLock }
            val favourites = preferences.favouriteTeams
            val now = System.currentTimeMillis()
            val profile = profiles.activeProfileId.value
            val waiting = SportsPendingRecords.prune(pendingList.value, now).filter { it.profileId == profile }
            if (favourites.isEmpty() && waiting.isEmpty()) return@withLock
            if (force || now - loadedAt >= LOAD_MILLIS || now < loadedAt) {
                val result = repository.load(now, ZoneId.systemDefault(), true, favourites, followedOnly = true, alsoLeagues = SportsPendingRecords.leagues(waiting))
                loadedAt = now
                if (result.enabled) merge(result.fixtures)
            }
            val followed = fixtureList.value.filter { SportsFavourites.has(favourites, it) && it.status != FixtureStatus.FINAL &&
                it.startMillis + SportsRefresh.durationMillis(it) > now }
            val known = fixtureList.value
            val awaited = SportsPendingRecords.watched(changePending { SportsPendingRecords.update(it, known, now) }, profile, known, now)
            val wanted = (followed + awaited).distinctBy { it.key }
            val keys = wanted.map { it.key }.toSet()
            if (force || keys != linkedFor || now - linkedAt >= LINK_MILLIS || now < linkedAt) {
                linkMap.value = link(sources(profile), wanted, now)
                linkedFor = keys; linkedAt = now
            }
            applyRules(followed, favourites, profile, now)
            applyPending(awaited, profile)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { IptvLog.failure("sports nuvio", error) }
    }

    private suspend fun applyRules(followed: List<SportsFixture>, favourites: Set<String>, profile: Int, now: Long) {
        val rules = ruleKeys.value.intersect(favourites)
        if (rules.isEmpty()) return
        val links = linkMap.value
        val targets = followed.mapNotNull { fixture -> links[fixture.key]?.firstOrNull()?.let { fixture.key to target(it) } }.toMap()
        val booked = recorder.all.value.filter { it.profileId == profile }.mapNotNull { it.fixtureKey }.toSet() + synchronized(handled) { handled.toSet() }
        val outcome = SportsRecordRules.plan(rules, followed, targets, booked, earlyMinutes * 60_000L, lateMinutes * 60_000L, now)
        for (plan in outcome.plans) {
            val link = links[plan.fixture.key]?.firstOrNull() ?: continue
            synchronized(handled) { handled += plan.fixture.key }
            val result = schedule(plan.fixture, link)
            if (result is IptvRecordResult.Refused && result.reason != IptvRecordRefusal.ALREADY_RECORDING)
                noticeEvents.emit(context.getString(R.string.iptv_sport5_nuvio_record_refused, plainTitle(plan.fixture), context.getString(iptvRecordRefusalMessage(result.reason))))
        }
        for (fixture in outcome.missed) {
            if (!synchronized(handled) { handled.add(fixture.key) }) continue
            noticeEvents.emit(context.getString(R.string.iptv_sport5_nuvio_no_channel_kickoff, plainTitle(fixture)))
        }
    }

    private suspend fun applyPending(awaited: List<SportsFixture>, profile: Int) {
        val links = linkMap.value
        val booked = recorder.all.value.filter { it.profileId == profile && !it.status.finished }.mapNotNull { it.fixtureKey }.toSet()
        for (fixture in claimPending(profile, awaited.filter { it.key in booked || !links[it.key].isNullOrEmpty() })) {
            if (fixture.key in booked) continue
            val link = links[fixture.key]?.firstOrNull() ?: continue
            noticeEvents.emit(record(fixture, link))
        }
    }

    private suspend fun schedule(fixture: SportsFixture, link: IptvFixtureLink): IptvRecordResult? {
        val now = System.currentTimeMillis()
        val target = target(link)
        val window = SportsRecordRules.window(fixture, target, earlyMinutes * 60_000L, lateMinutes * 60_000L, now) ?: return IptvRecordResult.Refused(IptvRecordRefusal.PROGRAMME_ENDED)
        val profile = profiles.activeProfileId.value
        return try {
            recorder.scheduleSport(access.open(profile), IptvSourceRef(profile, target.sourceId), target.channelId, link.programme, plainTitle(fixture).take(500),
                window, fixture.key)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { IptvLog.failure("sports record", error); null }
    }

    private fun target(link: IptvFixtureLink) = SportsRecordTarget(link.row.item.channel.sourceId, link.row.item.channel.id,
        link.programme?.start?.epochMillis, link.programme?.stop?.epochMillis, link.programme?.titles?.firstOrNull()?.text)

    private fun readRules(): Set<String> = store.getStringSet(RULES_KEY, null).orEmpty().filter { SportsFavourites.parse(it) != null && it.length <= 200 }
        .take(SportsRecordRules.MAX_RULES).toSet()

    private fun readPending(): List<SportsPendingRecord> = try {
        SportsPendingRecords.prune(store.getStringSet(PENDING_KEY, null).orEmpty().take(SportsPendingRecords.MAX * 2)
            .mapNotNull(SportsPendingRecords::decode), System.currentTimeMillis())
    } catch (error: Exception) { IptvLog.failure("sports pending read", error); emptyList() }

    private companion object {
        const val RULES_KEY = "settings-sports-record-teams"
        const val PENDING_KEY = "sports-pending-records"
        const val CHECK_MILLIS = 60_000L
        const val LOAD_MILLIS = 5L * 60 * 1000
        const val LINK_MILLIS = 10L * 60 * 1000
        const val KEEP_MILLIS = 12L * 60 * 60 * 1000
        const val MAX_RECENT = 60
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface IptvSportsNuvioEntryPoint {
    fun nuvio(): IptvSportsNuvio
}

@Composable
internal fun rememberIptvSportsNuvio(): IptvSportsNuvio {
    val context = LocalContext.current
    return remember { EntryPointAccessors.fromApplication(context.applicationContext, IptvSportsNuvioEntryPoint::class.java).nuvio() }
}

@Composable
fun IptvSportsNuvioHost(navController: NavHostController) {
    val nuvio = rememberIptvSportsNuvio()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, nuvio) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) nuvio.foreground(true)
            if (event == Lifecycle.Event.ON_STOP) nuvio.foreground(false)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); nuvio.foreground(false) }
    }
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val currentRoute by rememberUpdatedState(route)
    LaunchedEffect(nuvio, navController) {
        nuvio.requests.collect { request ->
            try {
                when (request) {
                    IptvSportsRequest.Live -> navController.navigate(Screen.IptvLive.route) {
                        popUpTo(navController.graph.startDestinationId)
                        launchSingleTop = true
                    }
                    is IptvSportsRequest.Team -> navController.navigate(iptvTeamRoute(request.key)) { launchSingleTop = true }
                }
            } catch (error: Exception) { IptvLog.failure("sports navigation", error) }
        }
    }
    LaunchedEffect(nuvio) { nuvio.notices.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    var due by remember { mutableStateOf<SportsReminder?>(null) }
    LaunchedEffect(nuvio) {
        nuvio.reminderDue.collect { reminder -> if (currentRoute != Screen.Player.route && !nuvio.remindersShown) due = reminder }
    }
    val summary by nuvio.summary.collectAsState()
    val inPlayer = route == Screen.Player.route
    due?.takeIf { !inPlayer }?.let { reminder ->
        ReminderDialog(reminder, nuvio.fixture(reminder.key), onWatch = { fixture -> due = null; nuvio.watch(fixture) }, onDismiss = { due = null })
    }
    summary?.takeIf { !inPlayer && due == null }?.let { lines -> SummaryDialog(lines, nuvio, onDismiss = nuvio::closeSummary) }
}

@Composable
private fun ReminderDialog(reminder: SportsReminder, fixture: SportsFixture?, onWatch: (SportsFixture) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val minutes = ((reminder.startMillis - System.currentTimeMillis()) / 60_000L).toInt()
    NuvioDialog(onDismiss = onDismiss, title = fixture?.let { sportTitle(it) } ?: reminder.title,
        subtitle = if (minutes > 0) stringResource(R.string.iptv_sport5_nuvio_starts_in, minutes) else stringResource(R.string.iptv_sport5_nuvio_starting_now), width = 520.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (fixture != null) NuvioActionPill({ onWatch(fixture) }, Modifier.focusRequester(first)) { Text(stringResource(R.string.iptv_sport5_nuvio_watch_game)) }
            NuvioActionPill(onDismiss, if (fixture == null) Modifier.focusRequester(first) else Modifier) { Text(stringResource(R.string.iptv_sport5_nuvio_later)) }
        }
    }
}

@Composable
private fun SummaryDialog(lines: List<IptvSportsLine>, nuvio: IptvSportsNuvio, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val watchable = lines.firstOrNull { it.fixture.status == FixtureStatus.LIVE && it.link != null }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.iptv_sport5_nuvio_while_watching), width = 560.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.take(6).forEach { line ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(iptvScoreLine(line.fixture, line.hidden), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(iptvFixtureState(line.fixture, line.hidden), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        color = if (line.fixture.status == FixtureStatus.LIVE) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (watchable != null) NuvioActionPill({ onDismiss(); nuvio.watch(watchable.fixture) }, Modifier.focusRequester(first)) {
                Text(stringResource(R.string.iptv_sport5_nuvio_watch_named, nuvio.plainTitle(watchable.fixture)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            NuvioActionPill(onDismiss, if (watchable == null) Modifier.focusRequester(first) else Modifier) { Text(stringResource(R.string.iptv_sport_close)) }
        }
    }
}

private class IptvPlayerAlert(val fixture: SportsFixture, val change: SportsChange?, val reminder: SportsReminder?, val at: Long) {
    val key: String get() = fixture.key
}

@Composable
fun IptvSportsPlayerAlerts(controlsVisible: Boolean, timeline: StateFlow<PlaybackTimelineState>, onLeave: () -> Unit) {
    val nuvio = rememberIptvSportsNuvio()
    val queue = remember { mutableStateListOf<IptvPlayerAlert>() }
    var current by remember { mutableStateOf<IptvPlayerAlert?>(null) }
    var chip by remember { mutableStateOf<IptvPlayerAlert?>(null) }
    var leaving by remember { mutableStateOf(false) }
    val leave by rememberUpdatedState(onLeave)
    DisposableEffect(nuvio) {
        val since = nuvio.playerStarted()
        onDispose { nuvio.playerEnded(since, leaving) }
    }
    fun quiet(): Boolean {
        val state = timeline.value
        val quiet = nuvio.quietMillis
        return quiet > 0 && !state.isLive && state.duration > 0 && state.duration - state.currentPosition <= quiet
    }
    fun offer(alert: IptvPlayerAlert) {
        if (alert.reminder == null && quiet()) return
        when (nuvio.alertStyle.takeIf { alert.reminder == null || it != SportsNuvioAlert.OFF } ?: SportsNuvioAlert.POPUP) {
            SportsNuvioAlert.POPUP -> { queue.removeAll { it.key == alert.key }; queue += alert; while (queue.size > MAX_QUEUED) queue.removeAt(0) }
            SportsNuvioAlert.CHIP -> chip = alert
            SportsNuvioAlert.OFF -> Unit
        }
    }
    LaunchedEffect(nuvio) { nuvio.alerts.collect { change -> offer(IptvPlayerAlert(change.fixture, change, null, System.currentTimeMillis())) } }
    LaunchedEffect(nuvio) {
        nuvio.reminderDue.collect { reminder ->
            offer(IptvPlayerAlert(nuvio.fixture(reminder.key) ?: SportsReminders.fixture(reminder), null, reminder, System.currentTimeMillis()))
        }
    }
    LaunchedEffect(controlsVisible, queue.size, current) {
        if (current != null || controlsVisible) return@LaunchedEffect
        val now = System.currentTimeMillis()
        queue.removeAll { now - it.at > STALE_MILLIS }
        if (queue.isNotEmpty() && (queue[0].reminder != null || !quiet())) current = queue.removeAt(0)
    }
    LaunchedEffect(chip) { val shown = chip ?: return@LaunchedEffect; delay(CHIP_MILLIS); if (chip === shown) chip = null }
    Box(Modifier.fillMaxSize()) {
        chip?.let { AlertChip(it, nuvio, Modifier.align(Alignment.TopStart).padding(40.dp)) }
        current?.let { alert ->
            Popup(alignment = Alignment.BottomEnd, offset = IntOffset(0, 0), onDismissRequest = { if (current === alert) current = null },
                properties = PopupProperties(focusable = !controlsVisible)) {
                AlertCard(alert, nuvio, focus = !controlsVisible, modifier = Modifier.padding(end = 40.dp, bottom = 44.dp),
                    onWatch = {
                        current = null
                        val link = nuvio.linkFor(alert.key)
                        if (link == null) nuvio.watch(alert.fixture)
                        else { leaving = true; leave(); nuvio.watch(link.row) }
                    },
                    onLater = { current = null },
                    onMute = { nuvio.mute(alert.key); queue.removeAll { it.key == alert.key }; current = null },
                    onTimeout = { if (current === alert) current = null })
            }
        }
    }
}

@Composable
private fun AlertCard(alert: IptvPlayerAlert, nuvio: IptvSportsNuvio, focus: Boolean, modifier: Modifier, onWatch: () -> Unit, onLater: () -> Unit,
    onMute: () -> Unit, onTimeout: () -> Unit) {
    val first = remember { FocusRequester() }
    val drain = remember(alert) { Animatable(1f) }
    LaunchedEffect(alert, focus) { if (focus) { withFrameNanos { }; runCatching { first.requestFocus() } } }
    LaunchedEffect(alert) { drain.animateTo(0f, tween(POPUP_MILLIS.toInt(), easing = LinearEasing)); onTimeout() }
    val fixture = alert.fixture
    val link = nuvio.linkFor(alert.key)
    val hidden = alertHidden(alert, nuvio)
    val home = fixture.home
    val away = fixture.away
    val scorer = alert.change?.takeIf { it.kind == SportsChangeKind.SCORED && !hidden }?.side
    Column(modifier.width(430.dp).clip(RoundedCornerShape(16.dp)).background(NuvioTheme.colors.Background.copy(alpha = .95f))) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(alertHeadline(alert).uppercase(), style = SportCaps, color = NuvioTheme.colors.TextPrimary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            CardState(fixture, fixture.sportDetail, hidden)
        }
        if (home != null && away != null) CardBands(fixture, home, away, false, Modifier.fillMaxWidth().height(ALERT_BANDS), 32.dp, scorer, extra = null) { team, side ->
            BandScore(fixture, team, side, hidden, false)
        } else Text(fixture.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
        Text(link?.let { stringResource(R.string.iptv_sport5_nuvio_on_channel, sportLeagueName(fixture), channelName(it.row)) } ?: sportLeagueName(fixture),
            style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp))
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (link != null) NuvioActionPill(onWatch, Modifier.focusRequester(first)) { Text(stringResource(R.string.iptv_sport5_nuvio_watch_game)) }
            NuvioActionPill(onLater, if (link == null) Modifier.focusRequester(first) else Modifier) { Text(stringResource(R.string.iptv_sport5_nuvio_later)) }
            if (alert.change != null) NuvioActionPill(onMute) { Text(stringResource(R.string.iptv_sport5_nuvio_mute_game)) }
        }
        val track = NuvioTheme.colors.TextPrimary.copy(alpha = .14f)
        val bar = NuvioTheme.colors.TextPrimary
        Box(Modifier.fillMaxWidth().height(3.dp).drawBehind {
            drawRect(track)
            drawRect(bar, size = Size(size.width * drain.value, size.height))
        })
    }
}

@Composable
private fun AlertChip(alert: IptvPlayerAlert, nuvio: IptvSportsNuvio, modifier: Modifier) {
    val fixture = alert.fixture
    val hidden = alertHidden(alert, nuvio)
    Row(modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(50)).background(NuvioTheme.colors.Background.copy(alpha = .9f))
        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CardState(fixture, fixture.sportDetail, hidden)
        Text(if (hidden) SportsOverlayText.match(fixture) else SportsFixtureText.bug(fixture).primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(listOfNotNull(alertHeadline(alert).takeIf(String::isNotEmpty), nuvio.linkFor(alert.key)?.let { channelName(it.row) }).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
    }
}

@Composable
private fun alertHidden(alert: IptvPlayerAlert, nuvio: IptvSportsNuvio): Boolean {
    val spoilers by nuvio.spoilers.collectAsState()
    return !nuvio.showScores || alert.key in spoilers
}

@Composable
private fun alertHeadline(alert: IptvPlayerAlert): String {
    val fixture = alert.fixture
    val reminder = alert.reminder
    if (reminder != null) {
        val minutes = ((reminder.startMillis - System.currentTimeMillis()) / 60_000L).toInt()
        return if (minutes > 0) stringResource(R.string.iptv_sport5_nuvio_starts_in, minutes) else stringResource(R.string.iptv_sport5_nuvio_starting_now)
    }
    val label = when (alert.change?.kind) {
        SportsChangeKind.STARTED -> stringResource(R.string.iptv_sport5_nuvio_kind_started)
        SportsChangeKind.SCORED -> stringResource(if (fixture.sport == "soccer" || fixture.sport == "ice-hockey") R.string.iptv_sport5_nuvio_kind_goal else R.string.iptv_sport5_nuvio_kind_score)
        SportsChangeKind.FINISHED -> stringResource(if (fixture.sport == "soccer" || fixture.sport == "rugby" || fixture.sport == "rugby-league") R.string.iptv_sport5_nuvio_full_time
            else R.string.iptv_sport2_final)
        null -> ""
    }
    return label
}

internal fun iptvScoreLine(fixture: SportsFixture, hidden: Boolean): String {
    val home = fixture.home ?: return fixture.title
    val away = fixture.away ?: return fixture.title
    val homeName = home.shortName ?: home.name
    val awayName = away.shortName ?: away.name
    val scores = SportsFixtureText.scores(fixture)?.takeIf { !hidden && fixture.status != FixtureStatus.SCHEDULED }
    return when {
        SportsFixtureText.awayFirst(fixture) -> if (scores == null) "$awayName @ $homeName" else "$awayName ${scores.second}–${scores.first} $homeName"
        scores == null -> "$homeName v $awayName"
        else -> "$homeName ${scores.first}–${scores.second} $awayName"
    }
}

@Composable
internal fun iptvFixtureState(fixture: SportsFixture, hidden: Boolean): String = when (fixture.status) {
    FixtureStatus.LIVE -> SportsFixtureText.bug(fixture).state?.takeIf { !hidden }?.let { stringResource(R.string.iptv_sport5_nuvio_live_state, it) }
        ?: stringResource(R.string.iptv_sport_live)
    FixtureStatus.FINAL -> if (hidden) stringResource(R.string.iptv_sport5_nuvio_result_hidden)
        else stringResource(if (fixture.sport == "soccer" || fixture.sport == "rugby" || fixture.sport == "rugby-league") R.string.iptv_sport5_nuvio_full_time else R.string.iptv_sport2_final)
    FixtureStatus.SCHEDULED -> "${sportDayLabel(fixture.startMillis)} · ${clock(fixture.startMillis)}"
}

private const val POPUP_MILLIS = 15_000L
private const val CHIP_MILLIS = 8_000L
private const val STALE_MILLIS = 3L * 60 * 1000
private const val MAX_QUEUED = 4
private val ALERT_BANDS = 84.dp
