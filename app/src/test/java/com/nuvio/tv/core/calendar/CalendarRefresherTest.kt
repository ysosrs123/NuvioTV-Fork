package com.nuvio.tv.core.calendar

import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CalendarRefresherTest {
    @get:Rule val folder = TemporaryFolder()

    private val now = Instant.parse("2026-10-05T23:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storage by lazy { CalendarStorage({ File(folder.root, "calendar") }, { now.toEpochMilli() }) }
    private val inputs = FakeInputs()
    private val remote = FakeRemote(now)
    private var retries = listOf(Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofMillis(50))
    private val refresher by lazy {
        CalendarRefresher(storage, inputs, remote, Clock.fixed(now, ZoneOffset.UTC), scope, retries)
    }

    @After fun tearDown() = scope.cancel()

    private class FakeInputs : CalendarRefreshInputs {
        override val activeProfileId = MutableStateFlow(1)
        override val playbackActive = MutableStateFlow(false)
        override val appVisible = MutableStateFlow(true)
        override val settings = MutableStateFlow<CalendarSettings?>(CalendarSettings(profileId = 1))
        val followed = ConcurrentHashMap<Int, CalendarFollowedSnapshot>()
        val reads = AtomicInteger()
        var bells: Set<String> = emptySet()
        var concurrency = 1
        var signedInToTrakt = false
        val traktCalendarCalls = AtomicInteger()

        override suspend fun settings(profileId: Int) = CalendarSettings(profileId = profileId, bellOn = bells)
        override suspend fun followed(profileId: Int): CalendarFollowedSnapshot {
            val snapshot = followed[profileId] ?: CalendarFollowedSnapshot()
            reads.incrementAndGet()
            return snapshot
        }
        override fun traktSignedIn() = signedInToTrakt
        override suspend fun traktCalendar(today: LocalDate): List<TraktCalendarItem> {
            traktCalendarCalls.incrementAndGet()
            return emptyList()
        }
        override suspend fun refreshConcurrency() = concurrency
        override fun region() = "AU"
        override fun zone() = ZoneOffset.UTC

        val artCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var art: (List<String>) -> CalendarArt? = { null }
        var holdArtCall = 0
        val artHeld = CompletableDeferred<Unit>()
        val releaseArt = CompletableDeferred<Unit>()
        override suspend fun fetchArt(type: String, ids: List<String>): CalendarArt? {
            artCalls += "$type:${ids.first()}"
            if (artCalls.size == holdArtCall) {
                artHeld.complete(Unit)
                releaseArt.await()
            }
            return art(ids)
        }

        fun switchTo(profileId: Int) {
            settings.value = CalendarSettings(profileId = profileId)
            activeProfileId.value = profileId
        }
    }

    private class FakeRemote(private val now: Instant) : CalendarRemoteSources {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val gates = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        val started = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        var gateIgnoresCancel = false
        val failing: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

        override suspend fun tvMazeLookup(ids: CalendarIds) = TvMazeLookup.Found(ids.tmdb!!.toInt(), CalendarIds())
        override suspend fun tvMazeShow(id: Int, now: Instant): CalendarShowDates {
            calls += "show:$id"
            started.getOrPut(id) { CompletableDeferred() }.complete(Unit)
            gates[id]?.let { gate -> if (gateIgnoresCancel) withContext(NonCancellable) { gate.await() } else gate.await() }
            if (id in failing) throw IllegalStateException("unexpected data")
            return CalendarShowDates(
                status = CalendarShowStatus.RUNNING,
                entries = listOf(CalendarEntry(season = 1, episode = 1, airInstantMs = this.now.plus(Duration.ofDays(2)).toEpochMilli()))
            )
        }
        override suspend fun traktNextEpisode(ids: CalendarIds): CalendarShowDates? = null
        override suspend fun tmdbFind(imdb: String, type: CalendarTitleType): Long? = null
        override suspend fun tmdbShow(tmdbId: Long) = CalendarShowDates()
        override suspend fun tmdbSeason(tmdbId: Long, season: Int) = emptyList<CalendarEntry>()
        override suspend fun tmdbFilm(tmdbId: Long, region: String) = CalendarShowDates()
        var schedule: ((Set<String>) -> TvMazeSchedule)? = null
        val scheduleCalls = AtomicInteger()
        override suspend fun tvMazeSchedule(wanted: Set<String>): TvMazeSchedule {
            scheduleCalls.incrementAndGet()
            return schedule?.invoke(wanted) ?: throw java.io.IOException("schedule offline")
        }

        suspend fun awaitStarted(id: Int) = withTimeout(5_000) { started.getOrPut(id) { CompletableDeferred() }.await() }
    }

    private fun shows(vararg numbers: Int) = CalendarFollowedSnapshot(
        candidates = numbers.map { n ->
            CalendarTitleCandidate(
                CalendarIds(imdb = "tt$n", tmdb = n.toLong()), CalendarTitleType.SERIES, CalendarOrigin.LIBRARY,
                lastActivity = now.minus(Duration.ofDays(n.toLong())), name = "Show $n"
            )
        }
    )

    private fun show(n: Int, origin: CalendarOrigin) = CalendarTitleCandidate(
        CalendarIds(imdb = "tt$n", tmdb = n.toLong()), CalendarTitleType.SERIES, origin,
        lastActivity = now.minus(Duration.ofDays(n.toLong())), name = "Show $n"
    )

    private val traktNotLoaded = CalendarFollowedSnapshot(
        candidates = listOf(show(1, CalendarOrigin.LIBRARY)),
        notReady = mapOf("trakt" to "not loaded yet"),
        unreadOrigins = setOf(CalendarOrigin.TRAKT_WATCHED, CalendarOrigin.TRAKT_CALENDAR)
    )

    private suspend fun until(message: String, condition: suspend () -> Boolean) {
        try {
            withTimeout(5_000) { while (!condition()) delay(10) }
        } catch (error: Exception) {
            throw AssertionError("timed out waiting for $message; state=${refresher.state.value}", error)
        }
    }

    private fun titles() = refresher.state.value.titles.associateBy { it.ids.tmdb!!.toInt() }

    private suspend fun awaitIdle() = until("refresh to finish") {
        val state = refresher.state.value
        state.loaded && !state.refreshing && state.titles.isNotEmpty() && state.titles.none { it.isDue(now) }
    }

    @Test fun comingUpTitlesWithoutABackdropAreAskedOnceAndTheBackdropIsSaved() = runBlocking {
        inputs.followed[1] = shows(1, 2, 3).copy(cachedArt = mapOf("tmdb:2" to CalendarArt("continue-watching.jpg")))
        inputs.art = { ids -> if ("tt1" in ids) CalendarArt("fetched.jpg", "logo.png") else null }
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        awaitIdle()

        assertEquals(listOf("series:tt1", "series:tt3"), inputs.artCalls.sorted())
        val titles = titles()
        assertEquals("fetched.jpg", titles.getValue(1).backdrop)
        assertEquals("logo.png", titles.getValue(1).logo)
        assertEquals("continue-watching.jpg", titles.getValue(2).backdrop)
        assertEquals(null, titles.getValue(3).backdrop)
        assertEquals(now.toEpochMilli(), titles.getValue(3).backdropCheckedAtMs)
        val saved = storage.load(1).titles.associateBy { it.ids.tmdb!!.toInt() }
        assertEquals("fetched.jpg", saved.getValue(1).backdrop)
        assertEquals("continue-watching.jpg", saved.getValue(2).backdrop)

        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        until("the second refresh") { inputs.reads.get() >= 2 && !refresher.state.value.refreshing }
        assertEquals(2, inputs.artCalls.size)
        assertEquals("fetched.jpg", titles().getValue(1).backdrop)
    }

    @Test fun newBackdropsArePublishedTogetherAtTheEndOfThePass() = runBlocking {
        inputs.followed[1] = shows(1, 2, 3)
        inputs.art = { CalendarArt("fetched.jpg") }
        inputs.holdArtCall = 3
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        withTimeout(5_000) { inputs.artHeld.await() }
        delay(100)
        assertTrue(refresher.state.value.titles.isNotEmpty())
        assertTrue(refresher.state.value.titles.none { it.backdrop != null })

        inputs.releaseArt.complete(Unit)
        awaitIdle()
        until("the backdrops") { titles().values.all { it.backdrop == "fetched.jpg" } }
        assertEquals(3, inputs.artCalls.size)
    }

    @Test fun aSavedTitleThatIsNotDueStillGetsItsBackdropOnceNothingPlays() = runBlocking {
        val fresh = CalendarTitleRecord(
            key = "series:imdb:tt1", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt1", tmdb = 1),
            name = "Show 1", contentId = "tt1", origins = setOf(CalendarOrigin.LIBRARY),
            sources = mapOf(
                CalendarSource.TVMAZE to listOf(
                    CalendarEntry(season = 1, episode = 1, airInstantMs = now.plus(Duration.ofDays(2)).toEpochMilli())
                )
            ),
            fetchedAtMs = now.toEpochMilli(),
            nextRefreshAtMs = now.plus(Duration.ofHours(6)).toEpochMilli()
        )
        storage.save(1, CalendarSnapshot(titles = listOf(fresh)), storage.generation(), force = true)
        inputs.followed[1] = shows(1)
        inputs.art = { CalendarArt("fetched.jpg") }
        inputs.playbackActive.value = true
        refresher.requestRefresh(CalendarRefreshReason.HOME_SETTLED)
        until("the refresh to start") { inputs.reads.get() >= 1 }
        delay(200)
        assertTrue(inputs.artCalls.isEmpty())

        inputs.playbackActive.value = false
        until("the backdrop") { titles()[1]?.backdrop == "fetched.jpg" }
        assertTrue(remote.calls.isEmpty())
        assertEquals(listOf("series:tt1"), inputs.artCalls.toList())
    }

    @Test fun oneTitleThatFailsWithAnyErrorDoesNotStopTheOthersAndBacksOff() = runBlocking {
        inputs.followed[1] = shows(1, 2, 3)
        inputs.concurrency = 2
        remote.failing += 2
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        awaitIdle()

        val titles = titles()
        assertEquals(1, titles.getValue(2).failures)
        assertEquals(now.plus(Duration.ofHours(1)).toEpochMilli(), titles.getValue(2).nextRefreshAtMs)
        listOf(1, 3).forEach { n ->
            assertEquals(0, titles.getValue(n).failures)
            assertEquals(1, titles.getValue(n).entries.size)
        }
        assertEquals(3, storage.load(1).titles.size)
        assertEquals(1, storage.load(1).titles.first { it.ids.tmdb == 1L }.entries.size)
    }

    @Test fun nothingIsAskedWhileSomethingPlaysAndTheRefreshCarriesOnAfterwards() = runBlocking {
        inputs.followed[1] = shows(1, 2)
        inputs.playbackActive.value = true
        refresher.requestRefresh(CalendarRefreshReason.HOME_SETTLED)
        until("titles to be listed") { refresher.state.value.titles.size == 2 }
        delay(200)
        assertTrue(remote.calls.isEmpty())
        assertTrue(refresher.state.value.refreshing)

        inputs.playbackActive.value = false
        awaitIdle()
        assertEquals(listOf("show:1", "show:2"), remote.calls.toList())
    }

    @Test fun aCancelledRefreshCarriesOnWhereItStopped() = runBlocking {
        inputs.followed[1] = shows(1, 2, 3)
        remote.gates[2] = CompletableDeferred()
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        remote.awaitStarted(2)
        refresher.cancelRefresh()
        until("the refresh to stop") { !refresher.state.value.refreshing }

        val stopped = storage.load(1).titles.associateBy { it.ids.tmdb!!.toInt() }
        assertFalse(stopped.getValue(1).isDue(now))
        assertTrue(stopped.getValue(2).isDue(now))
        assertTrue(stopped.getValue(3).isDue(now))

        remote.gates.remove(2)
        remote.calls.clear()
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        awaitIdle()
        assertEquals(listOf("show:2", "show:3"), remote.calls.toList())
    }

    @Test fun aRefreshThatFinishesAfterAProfileSwitchWritesNothing() = runBlocking {
        inputs.followed[1] = shows(1)
        inputs.followed[2] = shows(7)
        remote.gates[1] = CompletableDeferred()
        remote.gateIgnoresCancel = true
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        remote.awaitStarted(1)

        inputs.switchTo(2)
        until("profile 2 to be shown") { refresher.state.value.profileId == 2 && refresher.state.value.loaded }
        remote.gates.getValue(1).complete(Unit)
        delay(300)

        assertTrue(refresher.state.value.titles.isEmpty())
        assertTrue(storage.load(2).titles.isEmpty())
        assertEquals(0L, storage.load(1).titles.single().fetchedAtMs)

        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        awaitIdle()
        assertEquals(listOf(7), titles().keys.toList())
        assertEquals(listOf(7L), storage.load(2).titles.map { it.ids.tmdb })
    }

    @Test fun aRefreshThatFinishesAfterSignOutWritesNothing() = runBlocking {
        inputs.followed[1] = shows(1)
        remote.gates[1] = CompletableDeferred()
        remote.gateIgnoresCancel = true
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        remote.awaitStarted(1)
        assertTrue(storage.file(1).exists())

        storage.clearAllProfiles()
        until("the cleared cache to be shown") { refresher.state.value.titles.isEmpty() }
        remote.gates.getValue(1).complete(Unit)
        delay(300)

        assertFalse(storage.file(1).exists())
        assertTrue(refresher.state.value.titles.isEmpty())
        assertFalse(refresher.state.value.refreshing)
    }

    @Test fun remindersLeaveOutWatchedEpisodesEvenBeforeTheFirstRefresh() = runBlocking {
        val aired = { episode: Int -> CalendarEntry(season = 1, episode = episode, airInstantMs = now.minus(Duration.ofHours(3)).toEpochMilli()) }
        val record = CalendarTitleRecord(
            "series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1", tmdb = 1),
            sources = mapOf(CalendarSource.TVMAZE to listOf(aired(1), aired(2))),
            nextRefreshAtMs = now.plus(Duration.ofDays(1)).toEpochMilli()
        )
        storage.save(1, CalendarSnapshot(titles = listOf(record)), storage.generation(), force = true)
        inputs.bells = setOf("series:imdb:tt1")
        inputs.followed[1] = CalendarFollowedSnapshot(watched = CalendarWatchedIndex.Builder().add("tt1", 1, 1).build())

        val due = refresher.dueReminders()
        assertEquals(listOf("series:imdb:tt1|S1E2"), due.items.map { it.episodeKey })

        refresher.markRemindersSeen(due.items.map { it.episodeKey })
        assertEquals(0, refresher.dueReminders().count)
        assertEquals(setOf("series:imdb:tt1|S1E2"), storage.load(1).seenReminders.keys)
    }

    @Test fun nothingIsReadBeforeTheFirstCall() = runBlocking {
        storage.save(1, CalendarSnapshot(titles = shows(1).candidates.map {
            CalendarTitleRecord("series:imdb:tt1", CalendarTitleType.SERIES, it.ids)
        }), storage.generation(), force = true)
        refresher.state.value
        delay(100)
        assertFalse(refresher.state.value.loaded)
    }

    @Test fun hiddenTitlesWaitForTheSettings() = runBlocking {
        inputs.settings.value = null
        inputs.followed[1] = shows(1)
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        until("the refresh to finish") { storage.load(1).titles.singleOrNull()?.fetchedAtMs == now.toEpochMilli() }
        assertFalse(refresher.state.value.loaded)
        assertTrue(refresher.state.value.titles.isEmpty())

        inputs.settings.value = CalendarSettings(profileId = 1, hiddenTitleKeys = setOf("series:tmdb:1"))
        until("settings to be applied") { refresher.state.value.loaded }
        assertTrue(refresher.state.value.titles.isEmpty())
        inputs.settings.value = CalendarSettings(profileId = 1)
        until("the title to show") { refresher.state.value.titles.size == 1 }
    }

    @Test fun watchedEpisodesAreReadAgainWhenAskedAndPublished() = runBlocking {
        refresher.start()
        until("cache to load") { refresher.state.value.loaded }
        assertEquals(null, refresher.state.value.watched)
        inputs.followed[1] = CalendarFollowedSnapshot(watched = CalendarWatchedIndex.Builder().add("tt1", 1, 1).build())
        refresher.reloadWatched()
        assertTrue(refresher.state.value.watched!!.isWatched(CalendarIds(imdb = "tt1"), 1, 1))
        inputs.followed[1] = CalendarFollowedSnapshot(watched = CalendarWatchedIndex.Builder().add("tt1", 1, 2).build())
        refresher.reloadWatched()
        assertTrue(refresher.state.value.watched!!.isWatched(CalendarIds(imdb = "tt1"), 1, 2))
        assertFalse(refresher.state.value.watched!!.isWatched(CalendarIds(imdb = "tt1"), 1, 1))
    }

    @Test fun dueRemindersPublishesTheWatchedIndexItRead() = runBlocking {
        inputs.followed[1] = CalendarFollowedSnapshot(watched = CalendarWatchedIndex.Builder().add("tt1", 1, 1).build())
        refresher.dueReminders()
        assertTrue(refresher.state.value.watched != null)
    }

    @Test fun aStartUpRefreshWithASourceNotReadyKeepsItsTitlesAndRunsAgainOnce() = runBlocking {
        retries = listOf(Duration.ofMillis(50))
        inputs.followed[1] = CalendarFollowedSnapshot(candidates = listOf(show(1, CalendarOrigin.LIBRARY), show(5, CalendarOrigin.TRAKT_WATCHED)))
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        awaitIdle()
        assertEquals(setOf(1, 5), titles().keys)

        inputs.followed[1] = traktNotLoaded
        inputs.playbackActive.value = true
        refresher.requestRefresh(CalendarRefreshReason.HOME_SETTLED)
        until("the incomplete refresh to finish") { inputs.reads.get() == 2 && !refresher.state.value.refreshing }
        delay(200)
        assertEquals(2, inputs.reads.get())
        assertEquals(setOf(1, 5), titles().keys)
        assertEquals(setOf(1L, 5L), storage.load(1).titles.map { it.ids.tmdb }.toSet())

        inputs.followed[1] = CalendarFollowedSnapshot(
            candidates = listOf(show(1, CalendarOrigin.LIBRARY), show(5, CalendarOrigin.TRAKT_WATCHED), show(6, CalendarOrigin.TRAKT_WATCHED))
        )
        remote.calls.clear()
        inputs.playbackActive.value = false
        until("the refresh to run again") { titles().keys == setOf(1, 5, 6) }
        awaitIdle()
        assertEquals(listOf("show:6"), remote.calls.toList())
        delay(300)
        assertEquals(3, inputs.reads.get())
    }

    @Test fun aSourceThatNeverBecomesReadyIsAskedOnlyAFewTimes() = runBlocking {
        inputs.followed[1] = traktNotLoaded
        refresher.requestRefresh(CalendarRefreshReason.HOME_SETTLED)
        until("every retry to run") { inputs.reads.get() >= 4 }
        delay(400)
        assertEquals(4, inputs.reads.get())

        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        until("the opened refresh to run") { inputs.reads.get() == 5 }
        delay(400)
        assertEquals(5, inputs.reads.get())

        inputs.followed[1] = shows(1)
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        until("a complete refresh") { inputs.reads.get() == 6 && !refresher.state.value.refreshing }
        inputs.followed[1] = traktNotLoaded
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        until("retries to start again after a complete refresh") { inputs.reads.get() == 8 }
    }

    @Test fun opensWhileARefreshRunsAreKeptAndRunOnceAfterIt() = runBlocking {
        inputs.followed[1] = shows(1)
        remote.gates[1] = CompletableDeferred()
        refresher.requestRefresh(CalendarRefreshReason.HOME_SETTLED)
        remote.awaitStarted(1)

        inputs.followed[1] = shows(1, 2)
        repeat(3) { refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED) }
        assertEquals(1, inputs.reads.get())
        remote.gates.getValue(1).complete(Unit)

        until("the kept request to add the new show") { titles().keys == setOf(1, 2) }
        awaitIdle()
        delay(200)
        assertEquals(2, inputs.reads.get())
        assertEquals(listOf("show:1", "show:2"), remote.calls.toList())
    }

    @Test fun theTraktCalendarWaitsForTraktsWatchedShowsAndThenKeepsItsSixHours() = runBlocking {
        retries = listOf(Duration.ofMillis(300))
        inputs.signedInToTrakt = true
        inputs.followed[1] = traktNotLoaded.copy(traktWatchedLoaded = false)
        refresher.requestRefresh(CalendarRefreshReason.HOME_SETTLED)
        until("the first refresh") { inputs.reads.get() == 1 && !refresher.state.value.refreshing }
        assertEquals(0, inputs.traktCalendarCalls.get())

        inputs.followed[1] = CalendarFollowedSnapshot(candidates = listOf(show(1, CalendarOrigin.LIBRARY)))
        until("the retry to ask the Trakt calendar") { inputs.traktCalendarCalls.get() == 1 }
        until("the retry to finish") { inputs.reads.get() == 2 && !refresher.state.value.refreshing }

        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        until("the opened refresh") { inputs.reads.get() == 3 && !refresher.state.value.refreshing }
        assertEquals(1, inputs.traktCalendarCalls.get())
    }

    @Test fun aProfileSwitchDropsAPendingRetry() = runBlocking {
        retries = listOf(Duration.ofMillis(300))
        inputs.followed[1] = traktNotLoaded
        refresher.requestRefresh(CalendarRefreshReason.HOME_SETTLED)
        until("the incomplete refresh") { inputs.reads.get() == 1 && !refresher.state.value.refreshing }
        inputs.switchTo(2)
        until("profile 2 to be shown") { refresher.state.value.profileId == 2 }
        delay(700)
        assertEquals(1, inputs.reads.get())
    }

    @Test fun aRetryWaitsUntilTheAppIsBackOnScreen() = runBlocking {
        inputs.followed[1] = traktNotLoaded
        inputs.appVisible.value = false
        refresher.requestRefresh(CalendarRefreshReason.HOME_SETTLED)
        until("the incomplete refresh") { inputs.reads.get() == 1 && !refresher.state.value.refreshing }
        delay(300)
        assertEquals(1, inputs.reads.get())
        inputs.appVisible.value = true
        until("the retry after coming back") { inputs.reads.get() == 2 }
    }

    @Test fun aShowGoneFromASourceReadInFullLeavesTheCalendar() = runBlocking {
        inputs.followed[1] = CalendarFollowedSnapshot(candidates = listOf(show(1, CalendarOrigin.LIBRARY), show(5, CalendarOrigin.TRAKT_WATCHED)))
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        awaitIdle()
        inputs.followed[1] = CalendarFollowedSnapshot(candidates = listOf(show(1, CalendarOrigin.LIBRARY)))
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        until("the show to leave") { titles().keys == setOf(1) }
        assertEquals(listOf(1L), storage.load(1).titles.map { it.ids.tmdb })
    }

    @Test fun theFullScheduleGivesListedShowsTheirDatesWithoutCallsOfTheirOwn() = runBlocking {
        val airs = now.plus(Duration.ofDays(70)).toEpochMilli()
        remote.schedule = { wanted ->
            assertTrue("imdb:tt1" in wanted && "imdb:tt2" in wanted)
            TvMazeSchedule(
                listOf(
                    TvMazeScheduledShow(
                        id = 501, ids = CalendarIds(imdb = "tt1"), status = CalendarShowStatus.RUNNING,
                        entries = listOf(CalendarEntry(season = 2, episode = 1, airInstantMs = airs))
                    )
                )
            )
        }
        inputs.followed[1] = shows(1, 2)
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        awaitIdle()

        val titles = titles()
        assertEquals(501, titles.getValue(1).tvmazeId)
        assertEquals(listOf(airs), titles.getValue(1).entries.map { it.airInstantMs })
        assertTrue(titles.getValue(2).entries.isEmpty())
        assertTrue(titles.values.all { it.scheduleCheckedAtMs == now.toEpochMilli() })
        assertTrue(remote.calls.isEmpty())
        assertEquals(1, remote.scheduleCalls.get())
        assertEquals(now.toEpochMilli(), storage.load(1).scheduleFetchedAtMs)

        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        until("the second refresh") { inputs.reads.get() >= 2 && !refresher.state.value.refreshing }
        assertEquals(1, remote.scheduleCalls.get())
    }

    @Test fun whenTheScheduleCannotBeReadShowsGoThroughThePerTitleRefresh() = runBlocking {
        inputs.followed[1] = shows(1)
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        awaitIdle()
        assertEquals(1, remote.scheduleCalls.get())
        assertEquals(listOf("show:1"), remote.calls.toList())
        assertEquals(0L, titles().getValue(1).scheduleCheckedAtMs)
    }

    @Test fun aRefreshWithNoShowsYetDoesNotHoldTheScheduleBack() = runBlocking {
        remote.schedule = { TvMazeSchedule(emptyList()) }
        inputs.followed[1] = CalendarFollowedSnapshot()
        refresher.requestRefresh(CalendarRefreshReason.HOME_SETTLED)
        until("the empty refresh") { inputs.reads.get() >= 1 && !refresher.state.value.refreshing }
        assertEquals(0, remote.scheduleCalls.get())
        assertEquals(0L, storage.load(1).scheduleTriedAtMs)

        inputs.followed[1] = shows(1)
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        awaitIdle()
        assertEquals(1, remote.scheduleCalls.get())
        assertEquals(now.toEpochMilli(), titles().getValue(1).scheduleCheckedAtMs)
    }
}
