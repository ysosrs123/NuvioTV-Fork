package com.nuvio.tv.core.calendar

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarReminderCenterTest {
    private val start = Instant.parse("2026-10-05T23:00:00Z")

    private class FakeData(private val clock: Clock) : CalendarReminderData {
        override val state = MutableStateFlow(CalendarState(profileId = 1, loaded = true))
        override val settings = MutableStateFlow<CalendarSettings?>(CalendarSettings(profileId = 1))
        override val playbackActive = MutableStateFlow(false)
        val seen = linkedSetOf<String>()
        val markCalls = mutableListOf<Set<String>>()
        var settledRequests = 0

        override fun zone(): ZoneId = ZoneId.of("Australia/Sydney")
        override suspend fun dueReminders() = dueReminders(
            state.value.titles, settings.value?.bellOn.orEmpty(), seen, { _, _ -> false }, clock.instant()
        )
        override suspend fun markSeen(episodeKeys: Collection<String>) {
            markCalls += episodeKeys.toSet()
            seen += episodeKeys
            state.value = state.value.copy(seenReminders = seen.toSet())
        }
        override fun requestSettledRefresh() { settledRequests++ }

        fun follow(vararg titles: CalendarTitleRecord) {
            state.value = state.value.copy(titles = titles.toList())
            settings.value = settings.value?.copy(bellOn = titles.map { it.key }.toSet())
        }
    }

    private fun show(id: String, vararg entries: CalendarEntry) = CalendarTitleRecord(
        key = "series:imdb:$id", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = id), name = "Show $id",
        sources = mapOf(CalendarSource.TVMAZE to entries.toList())
    )

    private fun airs(offset: Duration, episode: Int = 1) =
        CalendarEntry(season = 1, episode = episode, airInstantMs = start.plus(offset).toEpochMilli())

    private fun TestScope.setUp(): Pair<FakeData, CalendarReminderCenter> {
        val clock = object : Clock() {
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?): Clock = this
        }
        val data = FakeData(clock)
        val center = CalendarReminderCenter(data, clock, { testScheduler.currentTime }, backgroundScope)
        center.setBlocked(false)
        runCurrent()
        return data to center
    }

    private fun TestScope.wait(duration: Duration) {
        advanceTimeBy(duration.toMillis())
        runCurrent()
    }

    private fun TestScope.openHome(center: CalendarReminderCenter) {
        center.homeVisible(true)
        runCurrent()
    }

    private fun TestScope.leaveHome(center: CalendarReminderCenter) {
        center.homeVisible(false)
        runCurrent()
    }

    @Test fun homeShowsOneCardForWhatIsDueAndMarksItSeen() = runTest {
        val (data, center) = setUp()
        data.follow(show("tt1", airs(Duration.ofHours(-2))))
        openHome(center)
        assertNull(center.card.value)
        wait(Duration.ofSeconds(2))
        val card = center.card.value
        assertNotNull(card)
        assertTrue(card!!.single)
        assertEquals(setOf("series:imdb:tt1|S1E1"), data.seen)
    }

    @Test fun laterAndBackMarkEverythingListedOrCounted() = runTest {
        val (data, center) = setUp()
        data.follow(show("tt1", *(1..5).map { airs(Duration.ofHours(-2L - it), it) }.toTypedArray()))
        openHome(center)
        wait(Duration.ofSeconds(2))
        val card = center.card.value!!
        assertEquals(3, card.items.size)
        assertEquals(2, card.more)
        center.dismissCard()
        runCurrent()
        assertNull(center.card.value)
        assertEquals(listOf(card.episodeKeys.toSet(), card.episodeKeys.toSet()), data.markCalls)
        assertEquals(5, data.seen.size)
    }

    @Test fun theCardWaitsWhilePlayingOrCovered() = runTest {
        val (data, center) = setUp()
        data.follow(show("tt1", airs(Duration.ofHours(-2))))
        data.playbackActive.value = true
        center.setBlocked(true)
        openHome(center)
        wait(Duration.ofMinutes(1))
        assertNull(center.card.value)
        data.playbackActive.value = false
        wait(Duration.ofMinutes(1))
        assertNull(center.card.value)
        center.setBlocked(false)
        wait(Duration.ofSeconds(2))
        assertNotNull(center.card.value)
    }

    @Test fun atMostOneCardPerHomeVisit() = runTest {
        val (data, center) = setUp()
        data.follow(show("tt1", airs(Duration.ofHours(-3), 1)))
        openHome(center)
        wait(Duration.ofSeconds(2))
        center.dismissCard()
        runCurrent()
        data.follow(show("tt1", airs(Duration.ofHours(-3), 1), airs(Duration.ofHours(-2), 2)))
        wait(Duration.ofMinutes(5))
        assertNull(center.card.value)
        leaveHome(center)
        openHome(center)
        wait(Duration.ofSeconds(2))
        assertEquals(listOf(2), center.card.value?.items?.map { it.entry.episode })
    }

    @Test fun nothingDueNoCard() = runTest {
        val (data, center) = setUp()
        data.follow(show("tt1", airs(Duration.ofMinutes(-30))))
        openHome(center)
        wait(Duration.ofSeconds(5))
        assertNull(center.card.value)
        assertTrue(data.markCalls.isEmpty())
    }

    @Test fun refreshTwentySecondsAfterHomeOncePerStart() = runTest {
        val (data, center) = setUp()
        openHome(center)
        wait(Duration.ofSeconds(19))
        assertEquals(0, data.settledRequests)
        wait(Duration.ofSeconds(2))
        assertEquals(1, data.settledRequests)
        leaveHome(center)
        openHome(center)
        wait(Duration.ofMinutes(1))
        assertEquals(1, data.settledRequests)
    }

    @Test fun refreshAgainAfterSixHoursAway() = runTest {
        val (data, center) = setUp()
        openHome(center)
        wait(Duration.ofSeconds(21))
        center.setAppVisible(false)
        wait(Duration.ofHours(5))
        center.setAppVisible(true)
        wait(Duration.ofMinutes(1))
        assertEquals(1, data.settledRequests)
        center.setAppVisible(false)
        wait(Duration.ofHours(1))
        center.setAppVisible(true)
        wait(Duration.ofSeconds(21))
        assertEquals(2, data.settledRequests)
    }

    @Test fun refreshNeverWhilePlaying() = runTest {
        val (data, center) = setUp()
        data.playbackActive.value = true
        openHome(center)
        wait(Duration.ofMinutes(10))
        assertEquals(0, data.settledRequests)
        data.playbackActive.value = false
        wait(Duration.ofSeconds(21))
        assertEquals(1, data.settledRequests)
    }

    @Test fun cornerMessageWhenAReminderFallsDueAndItGoesAfterEightSeconds() = runTest {
        val (data, center) = setUp()
        data.follow(show("tt1", airs(Duration.ofMinutes(10))))
        openHome(center)
        wait(Duration.ofSeconds(21))
        leaveHome(center)
        wait(Duration.ofMinutes(69))
        assertNull(center.corner.value)
        wait(Duration.ofSeconds(40))
        val message = center.corner.value
        assertNotNull(message)
        assertEquals(1, message!!.count)
        wait(Duration.ofSeconds(8))
        assertNull(center.corner.value)
        assertTrue(data.seen.isEmpty())

        openHome(center)
        wait(Duration.ofSeconds(2))
        assertNotNull(center.card.value)
    }

    @Test fun releasesFallingDueTogetherShareOneMessage() = runTest {
        val (data, center) = setUp()
        data.follow(show("tt1", airs(Duration.ofMinutes(10))), show("tt2", airs(Duration.ofMinutes(10))))
        openHome(center)
        wait(Duration.ofSeconds(21))
        leaveHome(center)
        wait(Duration.ofMinutes(69).plusSeconds(40))
        assertEquals(2, center.corner.value?.count)
        assertEquals(2, center.corner.value?.names?.size)
    }

    @Test fun whatWasDueAtStartIsLeftForTheHomeCard() = runTest {
        val (data, center) = setUp()
        data.follow(show("tt1", airs(Duration.ofHours(-2))))
        center.setBlocked(true)
        openHome(center)
        wait(Duration.ofMinutes(30))
        assertNull(center.corner.value)
        assertNull(center.card.value)
    }

    @Test fun cornerMessageSettingOff() = runTest {
        val (data, center) = setUp()
        data.follow(show("tt1", airs(Duration.ofMinutes(10))))
        data.settings.value = data.settings.value?.copy(cornerMessage = false)
        openHome(center)
        wait(Duration.ofSeconds(21))
        leaveHome(center)
        wait(Duration.ofMinutes(75))
        assertNull(center.corner.value)
        openHome(center)
        wait(Duration.ofSeconds(2))
        assertNotNull(center.card.value)
    }

    @Test fun noCornerMessageWhilePlayingOrInThePlayer() = runTest {
        val (data, center) = setUp()
        data.follow(show("tt1", airs(Duration.ofMinutes(10))), show("tt2", airs(Duration.ofMinutes(20))))
        openHome(center)
        wait(Duration.ofSeconds(21))
        leaveHome(center)
        data.playbackActive.value = true
        wait(Duration.ofMinutes(75))
        assertNull(center.corner.value)
        data.playbackActive.value = false
        center.setPlayerVisible(true)
        wait(Duration.ofMinutes(10))
        assertNull(center.corner.value)
        center.setPlayerVisible(false)
        wait(Duration.ofMinutes(10))
        assertNull(center.corner.value)
    }

    @Test fun menuWhileTheMessageShowsOpensTheCard() = runTest {
        val (data, center) = setUp()
        data.follow(show("tt1", airs(Duration.ofMinutes(10))))
        assertFalse(center.onMenuKey())
        openHome(center)
        wait(Duration.ofSeconds(21))
        leaveHome(center)
        wait(Duration.ofMinutes(69).plusSeconds(40))
        assertNotNull(center.corner.value)
        assertTrue(center.onMenuKey())
        runCurrent()
        assertNull(center.corner.value)
        assertNotNull(center.card.value)
        assertEquals(1, data.seen.size)
    }

    @Test fun seeAllAsksTheCalendarToOpenAtJustAired() = runTest {
        val (_, center) = setUp()
        center.requestCalendarAtJustAired()
        assertTrue(center.openAtJustAired.value)
        center.consumeCalendarRequest()
        assertFalse(center.openAtJustAired.value)
    }

    @Test fun noCornerMessageAndNoMenuWhileSomethingCoversTheApp() = runTest {
        val (data, center) = setUp()
        data.follow(show("tt1", airs(Duration.ofMinutes(10))), show("tt2", airs(Duration.ofMinutes(20))))
        openHome(center)
        wait(Duration.ofSeconds(21))
        leaveHome(center)
        center.setBlocked(true)
        wait(Duration.ofMinutes(69).plusSeconds(40))
        assertNull(center.corner.value)
        center.setBlocked(false)
        wait(Duration.ofMinutes(10))
        assertNotNull(center.corner.value)
        center.setBlocked(true)
        runCurrent()
        assertNull(center.corner.value)
        assertFalse(center.onMenuKey())
        assertNull(center.card.value)
    }

    @Test fun menuIsNotUsedWhileCovered() = runTest {
        val (data, center) = setUp()
        data.follow(show("tt1", airs(Duration.ofMinutes(10))))
        openHome(center)
        wait(Duration.ofSeconds(21))
        leaveHome(center)
        wait(Duration.ofMinutes(69).plusSeconds(40))
        assertNotNull(center.corner.value)
        center.setBlocked(true)
        runCurrent()
        assertFalse(center.onMenuKey())
        runCurrent()
        assertNull(center.card.value)
    }

    @Test fun theSettledRefreshWaitsWhileTheAppIsAway() = runTest {
        val (data, center) = setUp()
        openHome(center)
        wait(Duration.ofSeconds(5))
        center.setAppVisible(false)
        wait(Duration.ofMinutes(5))
        assertEquals(0, data.settledRequests)
        center.setAppVisible(true)
        wait(Duration.ofSeconds(21))
        assertEquals(1, data.settledRequests)
    }
}
