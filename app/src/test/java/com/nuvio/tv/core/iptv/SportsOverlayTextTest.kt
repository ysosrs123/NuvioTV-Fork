package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SportsOverlayTextTest {
    private val now = 1_800_000_000_000L
    private val arsenal = FixtureTeam("Arsenal", null, "ARS")
    private val leeds = FixtureTeam("Leeds United", "Leeds", "LEE")
    private val guardians = FixtureTeam("Cleveland Guardians", "Guardians", "CLE")
    private val sox = FixtureTeam("Chicago White Sox", "White Sox", "CHW")

    private fun epl(status: FixtureStatus, score: String? = null) =
        SportsFixture("1", "epl", "soccer", "Arsenal v Leeds United", arsenal, leeds, now, status, score)

    private fun mlb(score: String?) = SportsFixture("2", "mlb", "baseball", "Guardians at White Sox", sox, guardians, now, FixtureStatus.LIVE, score)

    @Test fun headlinesFollowSportAndKind() {
        assertEquals(SportsHeadline.GOAL, SportsOverlayText.headline(SportsChange(epl(FixtureStatus.LIVE, "2–0"), SportsChangeKind.SCORED, FixtureSide.HOME, now)))
        val sox = SportsChange(mlb("3–3"), SportsChangeKind.SCORED, FixtureSide.HOME, now)
        assertEquals(SportsHeadline.TEAM_SCORED, SportsOverlayText.headline(sox))
        assertEquals("White Sox", SportsOverlayText.scorer(sox)?.let(SportsOverlayText::name))
        assertEquals(SportsHeadline.SCORED, SportsOverlayText.headline(sox.copy(side = null)))
        assertEquals(SportsHeadline.KICK_OFF, SportsOverlayText.headline(SportsChange(epl(FixtureStatus.LIVE), SportsChangeKind.STARTED, null, now)))
        assertEquals(SportsHeadline.STARTED, SportsOverlayText.headline(SportsChange(mlb(null), SportsChangeKind.STARTED, null, now)))
        assertEquals(SportsHeadline.FULL_TIME, SportsOverlayText.headline(SportsChange(epl(FixtureStatus.FINAL, "2–0"), SportsChangeKind.FINISHED, null, now)))
        assertEquals(SportsHeadline.FINAL, SportsOverlayText.headline(SportsChange(mlb("3–2"), SportsChangeKind.FINISHED, null, now)))
    }

    @Test fun scoreLinesUseShortNamesAndSportOrder() {
        assertEquals("Arsenal 2–0 Leeds", SportsOverlayText.scoreLine(epl(FixtureStatus.LIVE, "2–0")))
        assertEquals("Arsenal v Leeds", SportsOverlayText.scoreLine(epl(FixtureStatus.SCHEDULED)))
        assertEquals("Guardians 3–2 White Sox", SportsOverlayText.scoreLine(mlb("2–3")))
        assertEquals("Guardians @ White Sox", SportsOverlayText.match(mlb(null)))
        assertEquals("3–3", SportsOverlayText.score(mlb("3–3")))
        assertEquals("2–1", SportsOverlayText.score(mlb("1–2")))
        assertNull(SportsOverlayText.score(epl(FixtureStatus.SCHEDULED, "0–0")))
        val afl = SportsFixture("3", "afl", "australian-football", "Brisbane v Fremantle", FixtureTeam("Brisbane Lions", "Brisbane", "BL"),
            FixtureTeam("Fremantle", null, "FRE"), now, FixtureStatus.LIVE, "10.11 (71)–10.7 (67)")
        assertEquals("Brisbane 71–67 Fremantle", SportsOverlayText.scoreLine(afl))
        val f1 = SportsFixture("4", "f1", "motorsport", "Singapore Grand Prix", null, null, now, FixtureStatus.LIVE)
        assertEquals("Singapore Grand Prix", SportsOverlayText.match(f1))
    }

    @Test fun aflScoresSplitGoalsAndBehinds() {
        assertEquals(SportsAflScore(10, 11, 71), SportsOverlayText.afl("10.11 (71)"))
        assertNull(SportsOverlayText.afl("71"))
        assertNull(SportsOverlayText.afl(null))
        assertEquals("71", SportsOverlayText.total("10.11 (71)"))
        assertEquals("166 (39.1/50 ov, target 279)", SportsOverlayText.total("166 (39.1/50 ov, target 279)"))
    }

    @Test fun minutesRoundUpAndStopAtZero() {
        assertEquals(5, SportsOverlayText.minutesUntil(now + 5 * 60_000L, now))
        assertEquals(5, SportsOverlayText.minutesUntil(now + 4 * 60_000L + 1, now))
        assertEquals(0, SportsOverlayText.minutesUntil(now - 1, now))
    }

    @Test fun tickerPagesByLeagueInOrder() {
        fun f(id: String, league: String) = SportsFixture(id, league, "soccer", id, null, null, now, FixtureStatus.LIVE)
        val pages = SportsOverlayText.pages(listOf(f("1", "mlb"), f("2", "epl"), f("3", "mlb"), f("4", "epl"), f("5", "epl"), f("5", "epl")), size = 2)
        assertEquals(listOf("mlb" to listOf("1", "3"), "epl" to listOf("2", "4"), "epl" to listOf("5")), pages.map { page -> page.league to page.fixtures.map { it.id } })
    }

    @Test fun individualSportsDetails() {
        val tennis = SportsDetail.Tennis("Shanghai", "SF", null, TennisPlayer(5), TennisPlayer(12),
            listOf(TennisSet(6, 4, winner = FixtureSide.HOME), TennisSet(3, 2)), FixtureSide.HOME)
        assertEquals(listOf("6", "3"), SportsOverlayText.tennisGames(tennis, FixtureSide.HOME))
        assertEquals(listOf("4", "2"), SportsOverlayText.tennisGames(tennis, FixtureSide.AWAY))
        assertEquals("OKAFOR", SportsOverlayText.tennisName(FixtureTeam("Daniel Okafor")))
        val golf = SportsDetail.Golf("Baycurrent Classic", 2, leaders = listOf(GolfPlayer("1", "Kenji Nakamura", toPar = "-14", thru = "F"),
            GolfPlayer("T9", "Cam Reid", toPar = "-8", thru = "12")))
        assertNull(SportsOverlayText.golfFollowed(golf, "pga", emptySet()))
        val reid = SportsOverlayText.golfFollowed(golf, "pga", setOf("pga:Cam Reid"))
        assertEquals("T9 REID -8 · thru 12", reid?.let(SportsOverlayText::golfLine))
        assertNull(SportsOverlayText.golfFollowed(golf, "pga", setOf("pga:Kenji Nakamura")))
        val sessions = SportsDetail.Sessions("Marina Bay", listOf(RaceSession("Qual", "Qualifying", now, FixtureStatus.LIVE),
            RaceSession("Race", "Race", now + 86_400_000L, FixtureStatus.SCHEDULED)))
        assertEquals("Race", SportsOverlayText.nextSession(sessions)?.name)
        assertNull(SportsOverlayText.nextSession(sessions.copy(sessions = sessions.sessions.take(1))))
        val main = FightBout("Middleweight", 5, Fighter("Brendan Allen"), Fighter("Christian Duncan"), startMillis = now, state = FixtureStatus.LIVE, round = 2)
        val prelim = FightBout("Flyweight", 3, Fighter("A B"), Fighter("C D"), startMillis = now)
        assertTrue(SportsOverlayText.mainEventLive(SportsDetail.Card(listOf(prelim, main))))
        assertFalse(SportsOverlayText.mainEventLive(SportsDetail.Card(listOf(prelim.copy(state = FixtureStatus.LIVE), main.copy(state = FixtureStatus.SCHEDULED)))))
    }
}
