package com.nuvio.tv.core.calendar

import android.os.SystemClock
import android.util.Log
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the reminders read from the Calendar; the refresher is only built once something asks for it. */
interface CalendarReminderData {
    val state: StateFlow<CalendarState>
    val settings: StateFlow<CalendarSettings?>
    val playbackActive: StateFlow<Boolean>
    fun zone(): ZoneId
    suspend fun dueReminders(): CalendarDueReminders
    suspend fun markSeen(episodeKeys: Collection<String>)
    fun requestSettledRefresh()
}

@Singleton
class DefaultCalendarReminderData @Inject constructor(
    private val refresher: dagger.Lazy<CalendarRefresher>,
    private val zoneProvider: CalendarZoneProvider,
    settingsStore: CalendarSettingsDataStore,
    playback: CalendarPlayback
) : CalendarReminderData {
    override val state: StateFlow<CalendarState> get() = refresher.get().state
    override val settings = settingsStore.settings
    override val playbackActive = playback.active
    override fun zone(): ZoneId = zoneProvider.zone()
    override suspend fun dueReminders() = refresher.get().dueReminders()
    override suspend fun markSeen(episodeKeys: Collection<String>) = refresher.get().markRemindersSeen(episodeKeys)
    override fun requestSettledRefresh() = refresher.get().requestRefresh(CalendarRefreshReason.HOME_SETTLED)
}

/**
 * The Home reminder card, the corner message and the refresh after Home has settled. Everything runs on one thread;
 * the screens only report where the user is.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarReminderCenter internal constructor(
    private val data: CalendarReminderData,
    private val clock: Clock,
    private val elapsedMs: () -> Long,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(data: CalendarReminderData, @CalendarClock clock: Clock) : this(
        data,
        clock,
        SystemClock::elapsedRealtime,
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    )

    private val _card = MutableStateFlow<CalendarReminderCard?>(null)
    val card: StateFlow<CalendarReminderCard?> = _card.asStateFlow()

    private val _corner = MutableStateFlow<CalendarCornerMessage?>(null)
    val corner: StateFlow<CalendarCornerMessage?> = _corner.asStateFlow()

    val playbackActive: StateFlow<Boolean> get() = data.playbackActive

    private val _openAtJustAired = MutableStateFlow(false)
    val openAtJustAired: StateFlow<Boolean> = _openAtJustAired.asStateFlow()

    private val homeVisible = MutableStateFlow(false)
    private val blocked = MutableStateFlow(true)
    private val playerVisible = MutableStateFlow(false)
    private val appVisible = MutableStateFlow(true)

    /** Home is on screen with nothing over it and nothing playing. */
    private val homeFree: StateFlow<Boolean> =
        combine(homeVisible, blocked, playerVisible, data.playbackActive) { home, blocked, player, playing ->
            home && !blocked && !player && !playing
        }.stateIn(scope, SharingStarted.Eagerly, false)

    private val batcher = CalendarReminderBatcher()
    private var visit = 0
    private var cardVisit = -1
    private var cardJob: Job? = null
    private var settledJob: Job? = null
    private var schedulerJob: Job? = null
    private var cornerJob: Job? = null
    private var cornerIds = 0L
    private var lastSettledAtMs: Long? = null
    private var backgroundedSinceSettled = false
    private var announcedProfile: Int? = null

    /** Home reports whether its content is showing and its page is in front. */
    fun homeVisible(visible: Boolean) {
        scope.launch {
            if (visible == homeVisible.value) return@launch
            homeVisible.value = visible
            if (visible) {
                startHomeVisit()
                maybeScheduleSettledRefresh()
            } else {
                cardJob?.cancel()
            }
        }
    }

    /** True while a dialog or the profile picker covers the app. */
    fun setBlocked(value: Boolean) {
        scope.launch {
            blocked.value = value
            if (value) _corner.value = null
        }
    }

    fun setPlayerVisible(visible: Boolean) {
        scope.launch {
            playerVisible.value = visible
            if (visible) _corner.value = null
        }
    }

    fun setAppVisible(visible: Boolean) {
        scope.launch {
            appVisible.value = visible
            if (!visible) {
                backgroundedSinceSettled = true
                _corner.value = null
            } else if (lastSettledAtMs != null) {
                maybeScheduleSettledRefresh()
            }
        }
    }

    /** Menu while the corner message shows opens the card. Returns true when the key was used. */
    fun onMenuKey(): Boolean {
        if (_corner.value == null || _card.value != null || blocked.value) return false
        scope.launch {
            if (_corner.value == null) return@launch
            _corner.value = null
            cornerJob?.cancel()
            loadDue()?.let { showCard(it) }
        }
        return true
    }

    /** Later or Back: the card goes and everything on it stays seen. */
    fun dismissCard() {
        scope.launch {
            val shown = _card.value ?: return@launch
            _card.value = null
            markSeen(shown.episodeKeys)
        }
    }

    /** Watch now, Open show or See all: the card goes, the screen it leads to takes over. */
    fun cardActionTaken() {
        scope.launch { _card.value = null }
    }

    fun requestCalendarAtJustAired() {
        _openAtJustAired.value = true
    }

    fun consumeCalendarRequest() {
        _openAtJustAired.value = false
    }

    private fun startHomeVisit() {
        visit += 1
        val thisVisit = visit
        cardJob?.cancel()
        cardJob = scope.launch {
            while (true) {
                homeFree.first { it }
                delay(CARD_SETTLE_MS)
                if (homeFree.value) break
            }
            if (cardVisit == thisVisit || _card.value != null) return@launch
            cardVisit = thisVisit
            if (data.settings.value?.bellOn.isNullOrEmpty()) return@launch
            val due = loadDue() ?: return@launch
            if (homeFree.value) showCard(due)
        }
    }

    private suspend fun loadDue(): CalendarDueReminders? = try {
        data.dueReminders().takeIf { it.count > 0 }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "reminders unavailable: ${error.message}")
        null
    }

    private suspend fun showCard(due: CalendarDueReminders) {
        val card = calendarReminderCard(due, data.zone(), clock.instant()) ?: return
        batcher.nextBatch(due)
        _corner.value = null
        _card.value = card
        markSeen(card.episodeKeys)
    }

    private suspend fun markSeen(keys: Collection<String>) {
        try {
            data.markSeen(keys)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "could not mark reminders seen: ${error.message}")
        }
    }

    private fun maybeScheduleSettledRefresh() {
        if (settledJob?.isActive == true) return
        if (!calendarSettledRefreshDue(lastSettledAtMs, backgroundedSinceSettled, elapsedMs())) return
        settledJob = scope.launch {
            while (true) {
                appVisible.first { it }
                data.playbackActive.first { !it }
                delay(CALENDAR_SETTLE_DELAY.toMillis())
                if (!data.playbackActive.value && appVisible.value) break
            }
            lastSettledAtMs = elapsedMs()
            backgroundedSinceSettled = false
            data.requestSettledRefresh()
            startScheduler()
        }
    }

    private fun startScheduler() {
        if (schedulerJob?.isActive == true) return
        schedulerJob = scope.launch {
            primeAnnounced()
            var deadline = nextDeadlineMs()
            while (true) {
                appVisible.first { it }
                val remaining = deadline - clock.millis()
                if (remaining <= 0) {
                    checkReminders()
                    deadline = nextDeadlineMs()
                    continue
                }
                val changed = withTimeoutOrNull(remaining) {
                    merge(data.state.drop(1), data.settings.drop(1), appVisible.drop(1)).first()
                }
                if (changed != null) deadline = minOf(deadline, nextDeadlineMs())
            }
        }
    }

    private fun nextDeadlineMs(): Long {
        val now = clock.instant()
        val state = data.state.value
        val next = calendarNextReminderAt(state.titles, data.settings.value?.bellOn.orEmpty(), state.seenReminders, now)
        return now.plus(calendarReminderCheckDelay(next, now)).toEpochMilli()
    }

    /** What is already due when the checks begin belongs to the Home card, not the corner. */
    private suspend fun primeAnnounced() {
        announcedProfile = data.state.value.profileId
        batcher.reset()
        loadDue()?.let { batcher.nextBatch(it) }
    }

    private suspend fun checkReminders() {
        if (data.state.value.profileId != announcedProfile) {
            primeAnnounced()
            return
        }
        val due = loadDue() ?: return
        val batch = batcher.nextBatch(due)
        if (batch.isEmpty()) return
        val quiet = data.playbackActive.value || playerVisible.value || !appVisible.value || blocked.value ||
            data.settings.value?.cornerMessage != true || _card.value != null
        if (quiet) return
        val message = CalendarCornerMessage(++cornerIds, batch)
        _corner.value = message
        cornerJob?.cancel()
        cornerJob = scope.launch {
            delay(CORNER_MS)
            if (_corner.value?.id == message.id) _corner.value = null
        }
    }

    internal companion object {
        const val TAG = "CalendarReminders"
        const val CARD_SETTLE_MS = 1_200L
        const val CORNER_MS = 8_000L
    }
}
