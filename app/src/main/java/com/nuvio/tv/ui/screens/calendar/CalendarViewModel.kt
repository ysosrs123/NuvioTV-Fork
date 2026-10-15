package com.nuvio.tv.ui.screens.calendar

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.calendar.CALENDAR_DEFAULT_WINDOW_DAYS
import com.nuvio.tv.core.calendar.CalendarRefreshReason
import com.nuvio.tv.core.calendar.CalendarSectionKind
import com.nuvio.tv.core.calendar.CalendarSettings
import com.nuvio.tv.core.calendar.CalendarState
import com.nuvio.tv.core.calendar.buildCalendarSections
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CalendarViewMode { LIST, MONTH }

enum class CalendarPhase { LOADING, BUILDING, NOTHING_FOLLOWED, ALL_QUIET, READY }

@Immutable
data class CalendarUiState(
    val phase: CalendarPhase = CalendarPhase.LOADING,
    val remaining: Int = 0,
    val zone: ZoneId? = null,
    val now: Instant = Instant.EPOCH,
    val today: LocalDate? = null,
    val windowDays: Int = CALENDAR_DEFAULT_WINDOW_DAYS,
    val list: CalendarListModel = CalendarListModel(),
    val days: Map<LocalDate, List<CalendarRow>> = emptyMap()
)

@Immutable
data class CalendarFocus(val row: CalendarRow, val details: CalendarRowDetails? = null)

/** Nulls stand for today, read when the month is drawn so the box's current date and zone are used. */
@Immutable
data class CalendarMonthState(val month: YearMonth? = null, val focusedDay: LocalDate? = null) {
    fun focusedDay(today: LocalDate): LocalDate = focusedDay ?: today
    fun month(today: LocalDate): YearMonth = month ?: YearMonth.from(focusedDay(today))
}

private data class CalendarInputs(
    val state: CalendarState,
    val settings: CalendarSettings?,
    val zone: ZoneId,
    val now: Instant
)

internal fun calendarPhase(state: CalendarState, settingsLoaded: Boolean, list: CalendarListModel): CalendarPhase = when {
    !state.loaded || !settingsLoaded -> CalendarPhase.LOADING
    !list.isEmpty -> CalendarPhase.READY
    state.refreshing || state.watched == null -> CalendarPhase.BUILDING
    state.titles.isEmpty() -> CalendarPhase.NOTHING_FOLLOWED
    else -> CalendarPhase.ALL_QUIET
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel internal constructor(
    private val data: CalendarScreenData,
    computeDispatcher: CoroutineDispatcher,
    tickMillis: Long
) : ViewModel() {

    @Inject
    constructor(data: CalendarScreenData) : this(data, Dispatchers.Default, TICK_MS)

    private val ticks = flow {
        while (true) {
            emit(data.now())
            delay(tickMillis)
        }
    }

    val uiState: StateFlow<CalendarUiState> =
        combine(data.state, data.settings, data.zones, ticks) { state, settings, zone, now ->
            CalendarInputs(state, settings, zone, now)
        }
            .mapLatest(::build)
            .distinctUntilChanged()
            .flowOn(computeDispatcher)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), CalendarUiState())

    private val _viewMode = MutableStateFlow(CalendarViewMode.LIST)
    val viewMode: StateFlow<CalendarViewMode> = _viewMode.asStateFlow()

    private val _month = MutableStateFlow(CalendarMonthState())
    val month: StateFlow<CalendarMonthState> = _month.asStateFlow()

    private val _focus = MutableStateFlow<CalendarFocus?>(null)
    val focus: StateFlow<CalendarFocus?> = _focus.asStateFlow()

    /** Set when OK on a month day asks the list to open on that day; the screen clears it once it has focused there. */
    private val _pendingListKey = MutableStateFlow<String?>(null)
    val pendingListKey: StateFlow<String?> = _pendingListKey.asStateFlow()

    var lastFocusedListKey: String? = null
        private set

    private var detailsJob: Job? = null
    private var titleOpened = false

    init {
        viewModelScope.launch {
            data.openAtJustAired.collect { open ->
                if (!open) return@collect
                val state = uiState.first { it.phase != CalendarPhase.LOADING && it.phase != CalendarPhase.BUILDING }
                data.consumeOpenAtJustAired()
                if (state.phase != CalendarPhase.READY) return@collect
                _pendingListKey.value = state.list.firstRowKeyIn(CalendarSectionKind.JUST_AIRED) ?: state.list.startRowKey
                _viewMode.value = CalendarViewMode.LIST
            }
        }
    }

    /** Each time the page comes into view; the page is kept while you are elsewhere, so this is not done once. */
    fun onScreenShown() {
        if (!titleOpened) showToday()
        titleOpened = false
        data.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
    }

    /** Back from a title page keeps the month where it was; any other way in starts on today. */
    fun onTitleOpened() {
        titleOpened = true
    }

    /** The menu entry chosen while the Calendar is already open. */
    fun showToday() {
        _month.value = CalendarMonthState()
    }

    private fun build(inputs: CalendarInputs): CalendarUiState {
        val (state, settings, zone, now) = inputs
        val bells = settings?.bellOn.orEmpty()
        val windowDays = settings?.windowDays ?: CALENDAR_DEFAULT_WINDOW_DAYS
        val list = if (state.loaded && settings != null) {
            buildCalendarList(buildCalendarSections(state.titles, now, zone, windowDays), now, zone, bells, state.watched)
        } else CalendarListModel()
        val days = if (state.loaded && settings != null) buildCalendarDays(state.titles, now, zone, bells, state.watched) else emptyMap()
        return CalendarUiState(
            phase = calendarPhase(state, settings != null, list),
            remaining = state.remaining,
            zone = zone,
            now = now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES),
            today = now.atZone(zone).toLocalDate(),
            windowDays = windowDays,
            list = list,
            days = days
        )
    }

    fun setViewMode(mode: CalendarViewMode) {
        if (mode == CalendarViewMode.MONTH && _viewMode.value != CalendarViewMode.MONTH) showToday()
        _viewMode.value = mode
    }

    fun focusDay(date: LocalDate) {
        _month.value = CalendarMonthState(YearMonth.from(date), date)
    }

    /** OK on a month day: back to the list, on that day or the next one with something on it. */
    fun openDayInList(date: LocalDate) {
        _pendingListKey.value = uiState.value.list.rowKeyFor(date)
        _viewMode.value = CalendarViewMode.LIST
    }

    fun consumePendingListKey() {
        _pendingListKey.value = null
    }

    fun onRowFocused(row: CalendarRow, fromList: Boolean) {
        if (fromList) lastFocusedListKey = row.key
        if (_focus.value?.row?.key == row.key && _focus.value?.row == row) return
        _focus.value = CalendarFocus(row)
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            delay(DETAILS_DELAY_MS)
            val cached = runCatching { data.details(row) }.getOrNull()
            if (_focus.value?.row?.key != row.key) return@launch
            _focus.value = CalendarFocus(row, cached)
            if (cached?.complete == true) return@launch
            delay(DETAILS_LOOKUP_DELAY_MS - DETAILS_DELAY_MS)
            val fetched = runCatching { data.details(row, fetch = true) }.getOrNull() ?: return@launch
            if (_focus.value?.row?.key == row.key) _focus.value = CalendarFocus(row, fetched)
        }
    }

    fun clearFocus() {
        detailsJob?.cancel()
        _focus.value = null
    }

    fun toggleBell(row: CalendarRow) {
        if (!row.status.hasBell) return
        viewModelScope.launch { data.setBell(row.titleKey, row.status != CalendarRowStatus.BELL_ON) }
    }

    fun hide(row: CalendarRow) {
        if (_focus.value?.row?.titleKey == row.titleKey) _focus.value = null
        if (_viewMode.value == CalendarViewMode.LIST) {
            _pendingListKey.value = uiState.value.list.neighbourOf(row)
        }
        viewModelScope.launch { data.setHidden(row.titleKey, hidden = true, name = row.name) }
    }

    private companion object {
        const val TICK_MS = 60_000L
        const val DETAILS_DELAY_MS = 120L
        const val DETAILS_LOOKUP_DELAY_MS = 400L
        const val STOP_AFTER_MS = 5_000L
    }
}
