package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SportsLiveTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val sydney = FixtureTeam("Sydney FC", "Sydney", "SYD")
    private val wanderers = FixtureTeam("Western Sydney Wanderers", "Western Sydney", "WSW", listOf("Wanderers"))
    private val swans = FixtureTeam("Sydney Swans", "Sydney", "SYD")
    private val cats = FixtureTeam("Geelong Cats", "Geelong", "GEEL")

    private fun football(id: String, status: FixtureStatus, score: String? = null, start: Long = now, league: String = "a-league-men",
        home: FixtureTeam = sydney, away: FixtureTeam = wanderers) =
        SportsFixture(id, league, "soccer", "${home.name} v ${away.name}", home, away, start, status, score)

    private fun afl(id: String, status: FixtureStatus, score: String? = null, start: Long = now) =
        SportsFixture(id, "afl", "australian-football", "Sydney Swans v Geelong Cats", swans, cats, start, status, score)

    @Test fun diffFindsStartsScoresAndFinishes() {
        val before = listOf(football("1", FixtureStatus.SCHEDULED), football("2", FixtureStatus.LIVE, "1–0"), football("3", FixtureStatus.LIVE, "2–2"),
            football("4", FixtureStatus.LIVE, "0–0")).associateBy { it.key }
        val after = listOf(football("1", FixtureStatus.LIVE, "0–0"), football("2", FixtureStatus.LIVE, "1–1"), football("3", FixtureStatus.FINAL, "2–2"),
            football("4", FixtureStatus.LIVE, "1–1"), football("5", FixtureStatus.LIVE, "3–0"))
        val changes = SportsChanges.diff(before, after, now)
        assertEquals(listOf("a-league-men:1" to SportsChangeKind.STARTED, "a-league-men:2" to SportsChangeKind.SCORED,
            "a-league-men:3" to SportsChangeKind.FINISHED, "a-league-men:4" to SportsChangeKind.SCORED), changes.map { it.key to it.kind })
        assertEquals(FixtureSide.AWAY, changes[1].side)
        assertNull(changes[3].side)
        assertTrue(changes.all { it.detectedAt == now })
    }

    @Test fun diffTreatsFirstLiveScoreAsGoalAndReadsTotals() {
        val started = SportsChanges.diff(mapOf("a-league-men:1" to football("1", FixtureStatus.SCHEDULED)), listOf(football("1", FixtureStatus.LIVE, "1–0")), now)
        assertEquals(listOf(SportsChangeKind.STARTED, SportsChangeKind.SCORED), started.map { it.kind })
        assertEquals(FixtureSide.HOME, started[1].side)
        val behind = SportsChanges.diff(mapOf("afl:9" to afl("9", FixtureStatus.LIVE, "5.4 (34)–3.2 (20)")), listOf(afl("9", FixtureStatus.LIVE, "5.5 (35)–3.2 (20)")), now)
        assertEquals(FixtureSide.HOME, behind.single().side)
        assertTrue(SportsChanges.diff(mapOf("afl:9" to afl("9", FixtureStatus.LIVE, "50–40")), listOf(afl("9", FixtureStatus.LIVE, "50–40")), now).isEmpty())
        assertTrue(SportsChanges.diff(mapOf("afl:9" to afl("9", FixtureStatus.LIVE, "50–40")), listOf(afl("9", FixtureStatus.LIVE)), now).isEmpty())
        val lines = afl("9", FixtureStatus.LIVE).copy(homeLine = FixtureLine("62"), awayLine = FixtureLine("61"))
        assertEquals(FixtureSide.AWAY, SportsChanges.diff(mapOf("afl:9" to afl("9", FixtureStatus.LIVE, "62–55")), listOf(lines), now).single().side)
    }

    @Test fun holdReleasesOldestFirstAtMostThreeAndReplacesSameKind() {
        val hold = SportsAlertHold(45_000)
        val goal = SportsChange(football("1", FixtureStatus.LIVE, "1–0"), SportsChangeKind.SCORED, FixtureSide.HOME, now)
        hold.offer(listOf(goal))
        assertTrue(hold.release(now + 44_000).isEmpty())
        val second = goal.copy(fixture = football("1", FixtureStatus.LIVE, "2–0"), detectedAt = now + 10_000)
        hold.offer(listOf(second))
        assertTrue(hold.release(now + 50_000).isEmpty())
        assertEquals(listOf(second), hold.release(now + 55_000))
        assertEquals(0, hold.size)
        val many = (1..5).map { SportsChange(football("$it", FixtureStatus.LIVE, "1–0"), SportsChangeKind.SCORED, FixtureSide.HOME, now - it * 1000L) }
        hold.offer(many)
        val first = hold.release(now + 60_000)
        assertEquals(listOf("5", "4", "3"), first.map { it.fixture.id })
        assertEquals(listOf("2", "1"), hold.release(now + 60_000).map { it.fixture.id })
        val zero = SportsAlertHold(0)
        zero.offer(listOf(goal))
        assertEquals(1, zero.release(now).size)
    }

    @Test fun filterHonoursGamesKindsAndScreen() {
        val favourites = setOf(SportsFavourites.key("a-league-men", sydney))
        val followedGoal = SportsChange(football("1", FixtureStatus.LIVE, "1–0"), SportsChangeKind.SCORED, FixtureSide.HOME, now)
        val otherBlowout = SportsChange(football("2", FixtureStatus.LIVE, "4–0", league = "epl", home = FixtureTeam("Arsenal"), away = FixtureTeam("Spurs")),
            SportsChangeKind.SCORED, FixtureSide.HOME, now)
        val otherClose = otherBlowout.copy(fixture = otherBlowout.fixture.copy(score = "1–1"))
        val otherStart = otherClose.copy(kind = SportsChangeKind.STARTED, fixture = otherClose.fixture.copy(score = "0–0"))
        val all = SportsChangeKind.entries.toSet()
        assertTrue(SportsAlertFilter.wanted(followedGoal, SportsAlertGames.FOLLOWED, favourites, emptySet(), all))
        assertFalse(SportsAlertFilter.wanted(followedGoal, SportsAlertGames.ALL_LIVE, favourites, setOf(followedGoal.key), all))
        assertFalse(SportsAlertFilter.wanted(followedGoal, SportsAlertGames.FOLLOWED, favourites, emptySet(), setOf(SportsChangeKind.FINISHED)))
        assertFalse(SportsAlertFilter.wanted(otherClose, SportsAlertGames.FOLLOWED, favourites, emptySet(), all))
        assertTrue(SportsAlertFilter.wanted(otherClose, SportsAlertGames.FOLLOWED_AND_CLOSE, favourites, emptySet(), all))
        assertFalse(SportsAlertFilter.wanted(otherBlowout, SportsAlertGames.FOLLOWED_AND_CLOSE, favourites, emptySet(), all))
        assertFalse(SportsAlertFilter.wanted(otherStart, SportsAlertGames.FOLLOWED_AND_CLOSE, favourites, emptySet(), all))
        assertTrue(SportsAlertFilter.wanted(otherBlowout, SportsAlertGames.ALL_LIVE, favourites, emptySet(), all))
        val finishedClose = otherClose.copy(kind = SportsChangeKind.FINISHED, fixture = otherClose.fixture.copy(status = FixtureStatus.FINAL))
        assertTrue(SportsAlertFilter.wanted(finishedClose, SportsAlertGames.FOLLOWED_AND_CLOSE, favourites, emptySet(), all))
    }

    @Test fun overlayPicksFollowedCloseLiveThenSoon() {
        val favourites = setOf(SportsFavourites.key("afl", swans))
        val followed = afl("1", FixtureStatus.LIVE, "50–10")
        val close = football("2", FixtureStatus.LIVE, "1–1")
        val blowout = football("3", FixtureStatus.LIVE, "5–0", start = now - 10 * minute)
        val soon = football("4", FixtureStatus.SCHEDULED, start = now + 10 * minute)
        val later = football("5", FixtureStatus.SCHEDULED, start = now + 60 * minute)
        val done = football("6", FixtureStatus.FINAL, "1–0")
        val fixtures = listOf(later, soon, blowout, close, followed, done)
        assertEquals(listOf("1", "2", "3", "4"), SportsOverlaySelection.pick(fixtures, favourites, emptySet(), SportsAlertGames.ALL_LIVE, true, now, 10).map { it.id })
        assertEquals(listOf("1", "3"), SportsOverlaySelection.pick(fixtures, favourites, setOf(close.key), SportsAlertGames.ALL_LIVE, true, now, 2).map { it.id })
        assertEquals(listOf("1", "2"), SportsOverlaySelection.pick(fixtures, favourites, setOf(close.key), SportsAlertGames.ALL_LIVE, false, now, 2).map { it.id })
        assertEquals(listOf("1", "2"), SportsOverlaySelection.pick(fixtures, favourites, emptySet(), SportsAlertGames.FOLLOWED_AND_CLOSE, true, now, 10).map { it.id })
        assertEquals(listOf("1"), SportsOverlaySelection.pick(fixtures, favourites, emptySet(), SportsAlertGames.FOLLOWED, true, now, 10).map { it.id })
    }

    @Test fun spoilersHideGamesWithAMatchingRecording() {
        val derby = football("1", FixtureStatus.FINAL, "2–1", start = now)
        val other = football("2", FixtureStatus.FINAL, "0–0", start = now, home = FixtureTeam("Melbourne Victory", "Melbourne Victory", "MVC"),
            away = FixtureTeam("Adelaide United", "Adelaide", "ADL"))
        val swansGame = afl("3", FixtureStatus.FINAL, "80–70", start = now)
        val recordings = listOf(SportsRecordedWindow("A-League: Sydney FC v Wanderers", now - 5 * minute, now + 120 * minute),
            SportsRecordedWindow("AFL: SYD v GEEL", now + 40 * minute, now + 200 * minute),
            SportsRecordedWindow("Melbourne Victory v Adelaide United", now - 300 * minute, now - 60 * minute))
        assertEquals(setOf(derby.key), SportsSpoilers.keys(listOf(derby, other, swansGame), recordings))
        val early = listOf(SportsRecordedWindow("Sydney Swans v Geelong Cats", now - 200 * minute, now - 31 * minute),
            SportsRecordedWindow("Sydney Swans v Geelong Cats", now + 29 * minute, now + 200 * minute))
        assertEquals(setOf(swansGame.key), SportsSpoilers.keys(listOf(swansGame), early.takeLast(1)))
        assertTrue(SportsSpoilers.keys(listOf(swansGame), early.take(1)).isEmpty())
        assertTrue(SportsSpoilers.hidden(derby, setOf(derby.key)))
        assertTrue(SportsSpoilers.keys(listOf(derby), emptyList()).isEmpty())
    }

    @Test fun timelineStacksOverlapsPerSport() {
        val from = now
        val until = now + 600 * minute
        val a = football("1", FixtureStatus.SCHEDULED, start = now + 60 * minute)
        val b = football("2", FixtureStatus.SCHEDULED, start = now + 90 * minute)
        val c = football("3", FixtureStatus.SCHEDULED, start = now + 200 * minute)
        val footy = afl("4", FixtureStatus.LIVE, start = now - 60 * minute)
        val gone = afl("5", FixtureStatus.FINAL, start = now - 600 * minute)
        val ended = afl("6", FixtureStatus.FINAL, start = now - 90 * minute)
        val lanes = SportsTimeline.lanes(listOf(c, b, footy, a, gone, ended), from, until)
        assertEquals(listOf("australian-football", "soccer"), lanes.map { it.sport })
        assertEquals(listOf("4"), lanes[0].items.map { it.fixture.id })
        assertEquals(0f, lanes[0].items[0].startFraction)
        assertEquals(120f / 600f, lanes[0].items[0].endFraction, 1e-4f)
        assertEquals(listOf("1" to 0, "2" to 1, "3" to 0), lanes[1].items.map { it.fixture.id to it.subLane })
        assertEquals(2, lanes[1].subLanes)
        assertEquals(0.1f, lanes[1].items[0].startFraction, 1e-4f)
        assertEquals(195f / 600f, lanes[1].items[0].endFraction, 1e-4f)
    }

    @Test fun remindersComeDueOnceAndPrune() {
        val soon = SportsReminder("afl:1", "Swans v Cats", now + 10 * minute, 5 * minute)
        val later = SportsReminder("afl:2", "Later", now + 60 * minute, 5 * minute)
        val old = SportsReminder("afl:3", "Old", now - 7 * 60 * minute, 5 * minute)
        assertTrue(SportsReminders.due(listOf(soon, later), now, now - 30_000).isEmpty())
        assertEquals(listOf(soon), SportsReminders.due(listOf(soon, later), now + 5 * minute, now + 5 * minute - 30_000))
        assertTrue(SportsReminders.due(listOf(soon), now + 5 * minute + 30_000, now + 5 * minute).isEmpty())
        assertTrue(SportsReminders.due(listOf(soon), now + 50 * minute, 0).isEmpty())
        assertEquals(listOf(soon, later), SportsReminders.prune(listOf(later, old, soon, soon), now))
        assertEquals(SportsReminders.MAX, SportsReminders.prune((0 until 150).map { soon.copy(key = "k$it", startMillis = now + it) }, now).size)
        assertEquals(soon, SportsReminders.decode(SportsReminders.encode(soon)))
        assertNull(SportsReminders.decode("{}"))
        assertNull(SportsReminders.decode("nope"))
    }

    @Test fun reminderFallsBackToAPlainFixture() {
        val fixture = SportsReminders.fixture(SportsReminder("afl:401", "Sydney v Geelong", now + 5 * minute, 0))
        assertEquals("afl:401", fixture.key)
        assertEquals("Sydney v Geelong", fixture.title)
        assertEquals(FixtureStatus.SCHEDULED, fixture.status)
        assertEquals("australian-football", fixture.sport)
        assertNull(fixture.home)
    }

    @Test fun pollingQuickensOnlyForFollowedGames() {
        val favourites = setOf(SportsFavourites.key("afl", swans))
        val live = afl("1", FixtureStatus.LIVE, "10–5")
        val soon = afl("1", FixtureStatus.SCHEDULED, start = now + 10 * minute)
        val other = football("2", FixtureStatus.LIVE, "1–0")
        assertEquals(SportsPolling.FOLLOWED_MILLIS, SportsPolling.backgroundDelay(listOf(live), favourites, now))
        assertEquals(SportsPolling.FOLLOWED_MILLIS, SportsPolling.backgroundDelay(listOf(soon), favourites, now))
        assertEquals(SportsPolling.IDLE_MILLIS, SportsPolling.backgroundDelay(listOf(other), favourites, now))
        assertEquals(SportsPolling.FOLLOWED_MILLIS, SportsPolling.liveTvDelay(listOf(live, other), favourites, now))
        assertEquals(SportsPolling.LIVE_TV_MILLIS, SportsPolling.liveTvDelay(listOf(other), favourites, now))
        assertEquals(setOf("afl"), SportsPolling.followedLeagues(favourites + "bad"))
        val entry = SportsCacheEntry(listOf(live), now - 40_000)
        assertTrue(SportsPolling.due(entry, favourites, now))
        assertFalse(SportsRefresh.due(entry, now))
        assertFalse(SportsPolling.due(entry, emptySet(), now))
        assertFalse(SportsPolling.due(entry.copy(fetchedAt = now - 20_000), favourites, now))
        assertFalse(SportsPolling.due(entry.copy(failedAt = now - 10_000, failures = 2), favourites, now))
        assertFalse(SportsPolling.due(SportsCacheEntry(listOf(other), now - 40_000), favourites, now))
    }
}
