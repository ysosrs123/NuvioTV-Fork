package com.nuvio.tv.ui.screens.calendar

import com.nuvio.tv.core.calendar.CalendarEntry
import com.nuvio.tv.core.calendar.CalendarIds
import com.nuvio.tv.core.calendar.CalendarRefreshReason
import com.nuvio.tv.core.calendar.CalendarSettings
import com.nuvio.tv.core.calendar.CalendarSource
import com.nuvio.tv.core.calendar.CalendarState
import com.nuvio.tv.core.calendar.CalendarTitleRecord
import com.nuvio.tv.core.calendar.CalendarTitleType
import com.nuvio.tv.core.calendar.CalendarWatchedIndex
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {
    private val now = Instant.parse("2026-10-05T23:00:00Z")
    private val sydney = ZoneId.of("Australia/Sydney")

    private class FakeData(private val instant: Instant, zone: ZoneId) : CalendarScreenData {
        override val state = MutableStateFlow(CalendarState())
        override val settings = MutableStateFlow<CalendarSettings?>(null)
        override val zones = MutableStateFlow(zone)
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun now() = instant
        override fun requestRefresh(reason: CalendarRefreshReason) { calls += "refresh:$reason" }
        override suspend fun setBell(titleKey: String, on: Boolean) {
            calls += "bell:$titleKey:$on"
            settings.value = settings.value?.let { it.copy(bellOn = if (on) it.bellOn + titleKey else it.bellOn - titleKey) }
        }
        override suspend fun setHidden(titleKey: String, hidden: Boolean, name: String?) { calls += "hide:$titleKey:$hidden:$name" }
        override suspend fun setWindowDays(days: Int) { settings.value = settings.value?.copy(windowDays = days) }
        override suspend fun setMenuVisible(visible: Boolean) = Unit
        override suspend fun setZoneOverride(zone: String?) = Unit
        override suspend fun setCornerMessage(enabled: Boolean) = Unit
        override suspend fun setIncludePersonalLists(include: Boolean) = Unit
        override suspend fun setComingUpRow(enabled: Boolean) = Unit
        override val openAtJustAired = MutableStateFlow(false)
        override fun consumeOpenAtJustAired() { openAtJustAired.value = false }
        var cachedSynopsis: String? = "story"
        override suspend fun details(row: CalendarRow, fetch: Boolean): CalendarRowDetails {
            calls += "details:${row.name}:$fetch"
            return CalendarRowDetails(backdrop = "art-${row.name}", synopsis = if (fetch) "fetched story" else cachedSynopsis)
        }
    }

    private val data = FakeData(now, sydney)

    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = CalendarViewModel(data, Dispatchers.Unconfined, tickMillis = 3_600_000L)

    private fun show(imdb: String, vararg airs: String) = CalendarTitleRecord(
        key = "series:imdb:$imdb", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = imdb), name = imdb, contentId = imdb,
        sources = mapOf(CalendarSource.TVMAZE to airs.mapIndexed { i, at ->
            CalendarEntry(season = 1, episode = i + 1, airInstantMs = Instant.parse(at).toEpochMilli())
        })
    )

    private suspend fun CalendarViewModel.until(check: (CalendarUiState) -> Boolean): CalendarUiState =
        withTimeout(5_000) { uiState.first(check) }

    @Test fun openingTheCalendarAsksForARefreshAndWaitsForSettings() = runBlocking {
        val vm = viewModel()
        assertTrue(data.calls.isEmpty())
        vm.onScreenShown()
        assertEquals(listOf("refresh:CALENDAR_OPENED"), data.calls)
        vm.onScreenShown()
        assertEquals(listOf("refresh:CALENDAR_OPENED", "refresh:CALENDAR_OPENED"), data.calls)
        assertEquals(CalendarPhase.LOADING, vm.uiState.value.phase)
        data.state.value = CalendarState(profileId = 1, loaded = true)
        assertEquals(CalendarPhase.LOADING, vm.uiState.value.phase)
        data.settings.value = CalendarSettings(profileId = 1)
        vm.until { it.phase == CalendarPhase.BUILDING }
        Unit
    }

    @Test fun emptyStatesTellBuildingNothingFollowedAndAllQuietApart() = runBlocking {
        val vm = viewModel()
        data.settings.value = CalendarSettings(profileId = 1)
        data.state.value = CalendarState(profileId = 1, loaded = true, refreshing = true, remaining = 12, titles = listOf(show("tt1")))
        assertEquals(12, vm.until { it.phase == CalendarPhase.BUILDING }.remaining)
        data.state.value = CalendarState(profileId = 1, loaded = true, watched = CalendarWatchedIndex())
        vm.until { it.phase == CalendarPhase.NOTHING_FOLLOWED }
        data.state.value = CalendarState(profileId = 1, loaded = true, watched = CalendarWatchedIndex(), titles = listOf(show("tt1")))
        vm.until { it.phase == CalendarPhase.ALL_QUIET }
        data.state.value = data.state.value.copy(titles = listOf(show("tt1", "2026-10-06T09:30:00Z")))
        val ready = vm.until { it.phase == CalendarPhase.READY }
        assertEquals(LocalDate.of(2026, 10, 6), ready.today)
        assertEquals(sydney, ready.zone)
    }

    @Test fun daysShownFollowsTheSetting() = runBlocking {
        val vm = viewModel()
        data.settings.value = CalendarSettings(profileId = 1, windowDays = 30)
        data.state.value = CalendarState(
            profileId = 1, loaded = true, watched = CalendarWatchedIndex(),
            titles = listOf(show("tt1", "2026-10-06T09:30:00Z", "2026-10-25T09:30:00Z"))
        )
        val thirty = vm.until { it.phase == CalendarPhase.READY }
        assertTrue(thirty.list.entries.none { it is CalendarHeaderEntry && it.kind == com.nuvio.tv.core.calendar.CalendarSectionKind.LATER })
        data.setWindowDays(14)
        vm.until { state ->
            state.windowDays == 14 &&
                state.list.entries.any { it is CalendarHeaderEntry && it.kind == com.nuvio.tv.core.calendar.CalendarSectionKind.LATER }
        }
        Unit
    }

    @Test fun bellTogglesForTheShowAndHideKeepsTheNameAndMovesFocusOn() = runBlocking {
        val vm = viewModel()
        data.settings.value = CalendarSettings(profileId = 1)
        data.state.value = CalendarState(
            profileId = 1, loaded = true, watched = CalendarWatchedIndex(),
            titles = listOf(show("tt1", "2026-10-06T09:30:00Z"), show("tt2", "2026-10-07T09:30:00Z"))
        )
        val ready = vm.until { it.phase == CalendarPhase.READY }
        val row = ready.list.entries.filterIsInstance<CalendarRowEntry>().first().row
        assertEquals(CalendarRowStatus.BELL_OFF, row.status)
        vm.toggleBell(row)
        val on = vm.until { state ->
            state.list.entries.filterIsInstance<CalendarRowEntry>().first().row.status == CalendarRowStatus.BELL_ON
        }
        vm.toggleBell(on.list.entries.filterIsInstance<CalendarRowEntry>().first().row)
        assertEquals(listOf("bell:series:imdb:tt1:true", "bell:series:imdb:tt1:false"), data.calls.filter { it.startsWith("bell") })

        vm.hide(row)
        assertTrue("hide:series:imdb:tt1:true:tt1" in data.calls)
        assertEquals("series:imdb:tt2|S1E1", vm.pendingListKey.value)
        vm.consumePendingListKey()
        assertNull(vm.pendingListKey.value)
    }

    @Test fun focusedRowGetsItsDetailsAfterAShortPause() = runBlocking {
        val vm = viewModel()
        data.settings.value = CalendarSettings(profileId = 1)
        data.state.value = CalendarState(profileId = 1, loaded = true, titles = listOf(show("tt1", "2026-10-06T09:30:00Z")))
        val row = vm.until { it.phase == CalendarPhase.READY }.list.entries.filterIsInstance<CalendarRowEntry>().first().row
        vm.onRowFocused(row, fromList = true)
        assertEquals(row, vm.focus.value?.row)
        assertNull(vm.focus.value?.details)
        assertEquals(row.key, vm.lastFocusedListKey)
        withTimeout(5_000) { while (vm.focus.value?.details == null) delay(10) }
        assertEquals("art-tt1", vm.focus.value?.details?.backdrop)
    }

    @Test fun okOnAMonthDayOpensTheListThere() = runBlocking {
        val vm = viewModel()
        data.settings.value = CalendarSettings(profileId = 1)
        data.state.value = CalendarState(
            profileId = 1, loaded = true,
            titles = listOf(show("tt1", "2026-10-06T09:30:00Z"), show("tt2", "2026-10-09T09:30:00Z"))
        )
        val today = vm.until { it.phase == CalendarPhase.READY }.today!!
        vm.setViewMode(CalendarViewMode.MONTH)
        assertEquals(LocalDate.of(2026, 10, 6), vm.month.value.focusedDay(today))
        vm.focusDay(LocalDate.of(2026, 10, 8))
        vm.openDayInList(LocalDate.of(2026, 10, 8))
        assertEquals(CalendarViewMode.LIST, vm.viewMode.value)
        assertEquals("series:imdb:tt2|S1E1", vm.pendingListKey.value)
    }

    @Test fun theMonthOpensOnTodayInTheCalendarsZone() = runBlocking {
        val vm = viewModel()
        data.settings.value = CalendarSettings(profileId = 1)
        data.state.value = CalendarState(
            profileId = 1, loaded = true,
            titles = listOf(show("tt1", "2026-10-06T09:30:00Z"), show("tt2", "2026-10-09T09:30:00Z"))
        )
        val ready = vm.until { it.phase == CalendarPhase.READY }
        val today = ready.today!!
        assertEquals(LocalDate.of(2026, 10, 6), today)
        val later = ready.list.entries.filterIsInstance<CalendarRowEntry>().last().row
        assertEquals(LocalDate.of(2026, 10, 9), later.date)

        vm.onRowFocused(later, fromList = true)
        vm.setViewMode(CalendarViewMode.MONTH)
        assertEquals(today, vm.month.value.focusedDay(today))
        assertEquals(YearMonth.of(2026, 10), vm.month.value.month(today))

        vm.focusDay(LocalDate.of(2026, 10, 4))
        vm.setViewMode(CalendarViewMode.LIST)
        vm.setViewMode(CalendarViewMode.MONTH)
        assertEquals(today, vm.month.value.focusedDay(today))

        vm.focusDay(LocalDate.of(2026, 10, 4))
        vm.onScreenShown()
        assertEquals(today, vm.month.value.focusedDay(today))

        vm.focusDay(LocalDate.of(2026, 11, 2))
        vm.onTitleOpened()
        vm.onScreenShown()
        assertEquals(LocalDate.of(2026, 11, 2), vm.month.value.focusedDay(today))
        assertEquals(YearMonth.of(2026, 11), vm.month.value.month(today))
        vm.onScreenShown()
        assertEquals(today, vm.month.value.focusedDay(today))

        vm.focusDay(LocalDate.of(2026, 10, 4))
        vm.showToday()
        assertEquals(today, vm.month.value.focusedDay(today))

        data.zones.value = ZoneId.of("UTC")
        val utcToday = vm.until { it.zone == ZoneId.of("UTC") }.today!!
        assertEquals(LocalDate.of(2026, 10, 5), utcToday)
        assertEquals(utcToday, vm.month.value.focusedDay(utcToday))
        assertEquals(YearMonth.of(2026, 10), vm.month.value.month(utcToday))
    }

    @Test fun seeAllFromTheReminderCardOpensJustAired() = runBlocking {
        val vm = viewModel()
        vm.setViewMode(CalendarViewMode.MONTH)
        data.openAtJustAired.value = true
        data.settings.value = CalendarSettings(profileId = 1)
        data.state.value = CalendarState(
            profileId = 1, loaded = true,
            titles = listOf(show("tt1", "2026-10-03T09:30:00Z"), show("tt2", "2026-10-09T09:30:00Z"))
        )
        vm.until { it.phase == CalendarPhase.READY }
        withTimeout(5_000) { while (data.openAtJustAired.value) delay(10) }
        assertEquals(CalendarViewMode.LIST, vm.viewMode.value)
        assertEquals("series:imdb:tt1|S1E1", vm.pendingListKey.value)
    }

    @Test fun missingDetailsAreLookedUpOnceTheFocusSettles() = runBlocking {
        val vm = viewModel()
        data.cachedSynopsis = null
        data.settings.value = CalendarSettings(profileId = 1)
        data.state.value = CalendarState(
            profileId = 1, loaded = true,
            titles = listOf(show("tt1", "2026-10-06T09:30:00Z"), show("tt2", "2026-10-07T09:30:00Z"))
        )
        val rows = vm.until { it.phase == CalendarPhase.READY }.list.entries.filterIsInstance<CalendarRowEntry>().map { it.row }
        vm.onRowFocused(rows[0], fromList = true)
        vm.onRowFocused(rows[1], fromList = true)
        withTimeout(5_000) { while (vm.focus.value?.details?.synopsis == null) delay(10) }
        assertEquals(rows[1], vm.focus.value?.row)
        assertEquals("fetched story", vm.focus.value?.details?.synopsis)
        assertEquals("art-tt2", vm.focus.value?.details?.backdrop)

        vm.onRowFocused(rows[1], fromList = true)
        delay(600)
        assertEquals(listOf("details:tt2:true"), data.calls.filter { it.endsWith(":true") })
    }
}
