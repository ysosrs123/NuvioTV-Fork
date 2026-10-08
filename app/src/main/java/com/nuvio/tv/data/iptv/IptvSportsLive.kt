package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.SportsAlertFilter
import com.nuvio.tv.core.iptv.SportsAlertGames
import com.nuvio.tv.core.iptv.SportsAlertHold
import com.nuvio.tv.core.iptv.SportsChange
import com.nuvio.tv.core.iptv.SportsChangeKind
import com.nuvio.tv.core.iptv.SportsChanges
import com.nuvio.tv.core.iptv.SportsFixture
import com.nuvio.tv.core.iptv.SportsNuvioAlert
import com.nuvio.tv.core.iptv.SportsPolling
import com.nuvio.tv.core.iptv.SportsReminder
import com.nuvio.tv.core.iptv.SportsReminders
import com.nuvio.tv.core.iptv.SportsService
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class IptvSportsMode { LIVE_TV, BACKGROUND }

data class IptvSportsSnapshot(val result: IptvSportsFixtures, val fetchedAt: Long, val favourites: Set<String>, val showScores: Boolean,
    val mode: IptvSportsMode, val refreshed: Boolean)

class IptvSportsLive(private val repository: IptvSportsFixturesRepository, private val preferences: IptvSportsPreferences,
    private val clock: () -> Long = System::currentTimeMillis) {
    inner class Handle internal constructor(val mode: IptvSportsMode) {
        fun release() = this@IptvSportsLive.release(this)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> IptvLog.failure("sports live", error) })
    private val mutable = MutableStateFlow<IptvSportsSnapshot?>(null)
    val snapshot: StateFlow<IptvSportsSnapshot?> = mutable.asStateFlow()
    private val alertEvents = MutableSharedFlow<SportsChange>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val alerts: SharedFlow<SportsChange> = alertEvents.asSharedFlow()
    private val dueEvents = MutableSharedFlow<SportsReminder>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val reminderDue: SharedFlow<SportsReminder> = dueEvents.asSharedFlow()
    private val reminderKeys = MutableStateFlow<Set<String>>(emptySet())
    val reminders: StateFlow<Set<String>> = reminderKeys.asStateFlow()
    private val handles = mutableListOf<Handle>()
    private val hold = SportsAlertHold(preferences.alertHoldSeconds * 1000L)
    private val reminderLock = Any()
    @Volatile private var onScreen = emptySet<String>()
    @Volatile private var pollMode: IptvSportsMode? = null
    private var poller: Job? = null
    private var ticker: Job? = null
    private var previous: Map<String, SportsFixture>? = null
    private var previousAt = 0L
    private var reminderCheckedAt = 0L

    init { scope.launch { synchronized(reminderLock) { reminderKeys.value = preferences.reminders.map { it.key }.toSet() } } }

    fun acquire(mode: IptvSportsMode): Handle = synchronized(handles) { Handle(mode).also { handles += it; schedule() } }

    private fun release(handle: Handle) = synchronized(handles) { if (handles.remove(handle)) schedule() }

    fun setOnScreen(keys: Set<String>) { onScreen = keys.take(MAX_ON_SCREEN).toSet() }

    fun has(key: String): Boolean = key in reminderKeys.value

    fun addReminder(fixture: SportsFixture) { scope.launch { changeReminders { all, now ->
        if (fixture.startMillis <= now) all
        else all.filter { it.key != fixture.key } + SportsReminder(fixture.key, fixture.title.take(240), fixture.startMillis, preferences.reminderLeadMinutes * 60_000L)
    } } }

    fun removeReminder(key: String) { scope.launch { changeReminders { all, _ -> all.filter { it.key != key } } } }

    fun toggleReminder(fixture: SportsFixture) = if (has(fixture.key)) removeReminder(fixture.key) else addReminder(fixture)

    private fun changeReminders(change: (List<SportsReminder>, Long) -> List<SportsReminder>) = synchronized(reminderLock) {
        val now = clock()
        val next = SportsReminders.prune(change(preferences.reminders, now), now)
        preferences.reminders = next
        reminderKeys.value = next.map { it.key }.toSet()
    }

    private fun schedule() {
        if (handles.isEmpty()) {
            poller?.cancel(); ticker?.cancel(); poller = null; ticker = null; pollMode = null; hold.clear()
            return
        }
        val mode = if (handles.any { it.mode == IptvSportsMode.LIVE_TV }) IptvSportsMode.LIVE_TV else IptvSportsMode.BACKGROUND
        if (poller?.isActive == true && pollMode == mode) return
        poller?.cancel()
        pollMode = mode
        poller = scope.launch { poll(mode) }
        if (ticker?.isActive != true) ticker = scope.launch { tick() }
    }

    private suspend fun poll(mode: IptvSportsMode) {
        var refresh = false
        while (currentCoroutineContext().isActive) {
            val now = clock()
            val favourites = preferences.favouriteTeams
            val showScores = preferences.showScores
            if (mode == IptvSportsMode.BACKGROUND && (favourites.isEmpty() || preferences.nuvioAlert == SportsNuvioAlert.OFF || preferences.service == SportsService.OFF)) {
                delay(CHECK_MILLIS); continue
            }
            var wait = if (mode == IptvSportsMode.LIVE_TV) SportsPolling.LIVE_TV_MILLIS else SportsPolling.IDLE_MILLIS
            try {
                val result = repository.load(now, ZoneId.systemDefault(), refresh, favourites, followedOnly = mode == IptvSportsMode.BACKGROUND)
                if (result.service != SportsService.OFF) {
                    if (refresh) detect(result.fixtures, now)
                    wait = if (mode == IptvSportsMode.LIVE_TV) SportsPolling.liveTvDelay(result.fixtures, favourites, now)
                        else SportsPolling.backgroundDelay(result.fixtures, favourites, now)
                }
                mutable.value = IptvSportsSnapshot(result, now, favourites, showScores, mode, refresh)
                if (result.service == SportsService.OFF) { synchronized(this) { previous = null }; refresh = false; delay(CHECK_MILLIS); continue }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                IptvLog.failure("sports fixtures", error)
                if (refresh) mutable.value = mutable.value?.let { it.copy(result = it.result.copy(failed = true), refreshed = true) }
                    ?: IptvSportsSnapshot(IptvSportsFixtures(preferences.service, failed = true), now, favourites, showScores, mode, true)
            }
            if (refresh) delay(wait)
            refresh = true
        }
    }

    private fun detect(fixtures: List<SportsFixture>, now: Long) = synchronized(this) {
        val before = previous?.takeIf { now - previousAt in 0..STALE_MILLIS }
        if (before != null) hold.offer(SportsChanges.diff(before, fixtures, now))
        previous = fixtures.associateBy { it.key }
        previousAt = now
    }

    private suspend fun tick() {
        while (currentCoroutineContext().isActive) {
            delay(TICK_MILLIS)
            val now = clock()
            try {
                if (hold.size > 0) {
                    hold.holdMillis = preferences.alertHoldSeconds * 1000L
                    val background = pollMode == IptvSportsMode.BACKGROUND
                    val games = if (background) SportsAlertGames.FOLLOWED else preferences.alertGames
                    val kinds = preferences.alertKinds.let { if (preferences.showScores) it else it - SportsChangeKind.SCORED - SportsChangeKind.FINISHED }
                    val favourites = preferences.favouriteTeams
                    val screen = if (background) emptySet() else onScreen
                    hold.release(now).filter { SportsAlertFilter.wanted(it, games, favourites, screen, kinds) }.forEach { alertEvents.emit(it) }
                }
                if (now - reminderCheckedAt >= REMINDER_MILLIS || now < reminderCheckedAt) checkReminders(now)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { IptvLog.failure("sports alerts", error) }
        }
    }

    private suspend fun checkReminders(now: Long) {
        val due = synchronized(reminderLock) {
            val all = preferences.reminders
            val fired = SportsReminders.due(all, now, reminderCheckedAt.takeIf { it <= now } ?: 0L)
            reminderCheckedAt = now
            val kept = SportsReminders.prune(all, now).filter { reminder -> fired.none { it.key == reminder.key } }
            if (kept != all) preferences.reminders = kept
            reminderKeys.value = kept.map { it.key }.toSet()
            fired
        }
        due.forEach { dueEvents.emit(it) }
    }

    private companion object {
        const val TICK_MILLIS = 5_000L
        const val REMINDER_MILLIS = 30_000L
        const val CHECK_MILLIS = 60_000L
        const val STALE_MILLIS = 5L * 60 * 1000
        const val MAX_ON_SCREEN = 16
    }
}
