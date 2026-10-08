package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SportsGuideCellsTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val arsenal = FixtureTeam("Arsenal", "Arsenal", "ARS")
    private val leeds = FixtureTeam("Leeds United", "Leeds", "LEE")
    private val guardians = FixtureTeam("Cleveland Guardians", "Guardians", "CLE")
    private val sox = FixtureTeam("Chicago White Sox", "White Sox", "CHW")

    private fun epl(id: String, status: FixtureStatus, score: String? = null, start: Long = now, clock: String? = null) =
        SportsFixture(id, "epl", "soccer", "Arsenal v Leeds United", arsenal, leeds, start, status, score, clock = clock)

    private fun programme(channel: String, start: Long, stop: Long? = start + 120 * minute, title: String = "Premier League: Arsenal v Leeds United") =
        GuideProgramme(channel, GuideTimestamp(start, 14), stop?.let { GuideTimestamp(it, 14) }, listOf(LocalizedGuideText(title, null)), emptyList())

    @Test fun indexLooksUpByChannelAndStartAndPrefersTeamLinks() {
        val game = epl("1", FixtureStatus.LIVE, "1–0")
        val other = epl("2", FixtureStatus.SCHEDULED, start = now + 30 * minute)
        val index = SportsGuideCells.index(listOf(
            SportsGuideLink(other, "501", programme("501", now), FixtureLinkReason.GUIDE_LEAGUE),
            SportsGuideLink(game, "501", programme("501", now), FixtureLinkReason.GUIDE_TEAMS),
            SportsGuideLink(other, "502", programme("502", now + 30 * minute, null), FixtureLinkReason.GUIDE_LEAGUE)))
        assertEquals(game, index.at("501", now))
        assertNull(index.at("501", now + 1))
        assertNull(index.at("503", now))
        assertEquals(other, index.at("502", now + 30 * minute))
        assertTrue(index.linked("502"))
        assertFalse(index.linked("503"))
        assertFalse(index.empty)
        assertTrue(SportsGuideCells.index(emptyList()).empty)
    }

    @Test fun indexPrefersClosestStartWhenReasonsMatch() {
        val near = epl("1", FixtureStatus.SCHEDULED, start = now + 5 * minute)
        val far = epl("2", FixtureStatus.SCHEDULED, start = now + 90 * minute)
        val index = SportsGuideCells.index(listOf(SportsGuideLink(far, "501", programme("501", now), FixtureLinkReason.GUIDE_LEAGUE),
            SportsGuideLink(near, "501", programme("501", now), FixtureLinkReason.GUIDE_LEAGUE)))
        assertEquals(near, index.at("501", now))
    }

    @Test fun withinChecksTheVisibleWindowIncludingOpenEndedProgrammes() {
        val index = SportsGuideCells.index(listOf(SportsGuideLink(epl("1", FixtureStatus.SCHEDULED, start = now), "501", programme("501", now, null),
            FixtureLinkReason.GUIDE_TEAMS)))
        assertTrue(index.within("501", now - 60 * minute, now + minute))
        assertTrue(index.within("501", now + 130 * minute, now + 200 * minute))
        assertFalse(index.within("501", now + 135 * minute, now + 200 * minute))
        assertFalse(index.within("501", now - 60 * minute, now))
        assertFalse(index.within("502", now, now + 60 * minute))
    }

    @Test fun badgesUseShortCodes() {
        assertEquals("EPL", SportsGuideCells.badge("epl"))
        assertEquals("AFL", SportsGuideCells.badge("afl"))
        assertEquals("F1", SportsGuideCells.badge("f1"))
        assertEquals("MLB", SportsGuideCells.badge("mlb"))
        assertEquals("UCL", SportsGuideCells.badge("champions-league"))
        assertEquals("ESL", SportsGuideCells.badge("english-super-league"))
    }

    @Test fun leagueGuessFromTitle() {
        assertEquals("epl", SportsGuideCells.league(listOf(LocalizedGuideText("Premier League Matchday Live", null))))
        assertEquals("f1", SportsGuideCells.league(listOf(LocalizedGuideText("F1: Singapore Grand Prix Qualifying", null))))
        assertEquals("a-league-women", SportsGuideCells.league(listOf(LocalizedGuideText("Liberty A-League Women: Sydney v Perth", null))))
        assertNull(SportsGuideCells.league(listOf(LocalizedGuideText("Gardening Australia", null))))
    }

    @Test fun chipShowsScoreAndClockAndRespectsSpoilers() {
        val game = epl("1", FixtureStatus.LIVE, "1–0", clock = "63'")
        assertEquals(SportsGuideChip("1–0", "63'"), SportsGuideCells.chip(game, false))
        assertEquals(SportsGuideChip(null, "63'"), SportsGuideCells.chip(game, true))
        assertNull(SportsGuideCells.chip(epl("2", FixtureStatus.SCHEDULED), false))
        assertTrue(SportsGuideCells.hidden(game, false, emptySet()))
        assertTrue(SportsGuideCells.hidden(game, true, setOf(game.key)))
        assertFalse(SportsGuideCells.hidden(game, true, setOf("epl:9")))
        val mlb = SportsFixture("3", "mlb", "baseball", "Guardians at White Sox", sox, guardians, now, FixtureStatus.LIVE, "2–3",
            sportDetail = SportsDetail.Baseball(inning = 7, half = InningHalf.TOP))
        assertEquals(SportsGuideChip("3–2", "▲7"), SportsGuideCells.chip(mlb, false))
        assertTrue(SportsGuideCells.close(mlb, false))
        assertFalse(SportsGuideCells.close(mlb, true))
    }

    @Test fun tennisChipListsSets() {
        val tennis = SportsFixture("4", "atp", "tennis", "Shanghai semi-final", FixtureTeam("Varga"), FixtureTeam("Other"), now, FixtureStatus.LIVE,
            sportDetail = SportsDetail.Tennis(null, null, null, TennisPlayer(), TennisPlayer(), listOf(TennisSet(6, 4, winner = FixtureSide.HOME), TennisSet(3, 2))))
        assertEquals("6-4 3-2", SportsGuideCells.chip(tennis, false)?.score)
        assertNull(SportsGuideCells.chip(tennis, true)?.score)
    }

    @Test fun labelHidesScoresWhenAsked() {
        assertEquals("ARS 1–0 LEE", SportsGuideCells.label(epl("1", FixtureStatus.LIVE, "1–0", clock = "63'"), false))
        assertEquals("ARS v LEE", SportsGuideCells.label(epl("1", FixtureStatus.LIVE, "1–0"), true))
        assertEquals("ARS v LEE", SportsGuideCells.label(epl("1", FixtureStatus.SCHEDULED), false))
        assertEquals("LEE @ ARS", SportsGuideCells.label(epl("1", FixtureStatus.SCHEDULED).copy(sport = "baseball"), false))
    }

    @Test fun progressUsesFootballMinuteOrElapsedTime() {
        assertEquals(0.7f, SportsGuideCells.progress(epl("1", FixtureStatus.LIVE, "1–0", clock = "63'"), now)!!, 0.001f)
        assertEquals(1f, SportsGuideCells.progress(epl("1", FixtureStatus.LIVE, "1–0", clock = "90'+4'"), now)!!, 0.001f)
        assertEquals(0.5f, SportsGuideCells.progress(epl("1", FixtureStatus.LIVE, start = now - 135 * minute / 2), now)!!, 0.001f)
        assertNull(SportsGuideCells.progress(epl("1", FixtureStatus.SCHEDULED), now))
    }

    @Test fun laneKeepsLiveAndSoonOrderedAndUnique() {
        val live = epl("1", FixtureStatus.LIVE, "1–0", start = now - 60 * minute)
        val soon = epl("2", FixtureStatus.SCHEDULED, start = now + 20 * minute)
        val later = epl("3", FixtureStatus.SCHEDULED, start = now + 45 * minute)
        val done = epl("4", FixtureStatus.FINAL, "2–2", start = now - 150 * minute)
        val stale = epl("5", FixtureStatus.SCHEDULED, start = now - 200 * minute)
        assertEquals(listOf("1", "2"), SportsGuideCells.lane(listOf(soon, later, done, live, stale, live), { it }, now).map { it.id })
        assertTrue(SportsGuideCells.lane(emptyList<SportsFixture>(), { it }, now).isEmpty())
    }
}
