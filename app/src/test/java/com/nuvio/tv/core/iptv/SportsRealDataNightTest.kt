package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataNightTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/espn-$name-real-20261010c.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-10T12:12:00Z")

    @Test fun soccerLiveScoreboardClockAndCards() {
        val games = EspnScoreboard.parse(sample("epl-scoreboard"), league("epl"), now)
        val live = games[0]
        assertEquals("Arsenal v Leeds United", live.title)
        assertEquals(FixtureStatus.LIVE, live.status)
        assertEquals(millis("2026-10-10T11:30:00Z"), live.startMillis)
        assertEquals("0–0", live.score)
        assertEquals(1, live.period)
        assertEquals("41'", live.clock)
        assertEquals("41'", live.detail)
        assertNull(live.situation)
        assertEquals(listOf(FixtureEvent(FixtureEventKind.YELLOW, FixtureSide.AWAY, "36'", null, "A. Tanaka"),
            FixtureEvent(FixtureEventKind.YELLOW, FixtureSide.AWAY, "38'", null, "E. Ampadu")), live.events)
        assertEquals(0, SportsEvents.reds(live, FixtureSide.AWAY))
        assertEquals(41.0, SportsMarkers.minutes("soccer", live.period, live.clock)!!, 0.001)
        assertEquals(live.startMillis + 36 * 60_000L, SportsMarkers.estimate(SportsEvents.moments(live)[0], "soccer", live.startMillis)!!.millis)
        assertEquals(FixtureStatus.SCHEDULED, games[1].status)
        assertNull(games[1].clock)
        assertTrue(games[1].events.isEmpty())
    }

    @Test fun soccerLiveSummaryKeyEventsStatsLineupsAndCommentary() {
        val game = EspnSummary.parse(sample("epl-summary"), league("epl"))
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals(1, game.period)
        assertEquals("41'", game.clock)
        assertEquals("41'", game.detail)
        assertEquals(SummaryLine("0", listOf("0")), game.homeLine)
        assertEquals(listOf(
            SummaryMoment(MomentKind.CARD_YELLOW, FixtureSide.AWAY, 1, "36'", "Ao Tanaka Yellow Card", wallclockMillis = millis("2026-10-10T12:06:21Z")),
            SummaryMoment(MomentKind.CARD_YELLOW, FixtureSide.AWAY, 1, "38'", "Ethan Ampadu Yellow Card", wallclockMillis = millis("2026-10-10T12:08:32Z"))), game.moments)
        val start = millis("2026-10-10T11:30:00Z")
        val markers = SportsMarkers.all(game, start)
        assertTrue(markers.all { it.second.exact })
        assertEquals(36.35, (markers[0].second.millis - start) / 60_000.0, 0.01)
        assertEquals(listOf(SummaryStat("Possession", "58.4%", "41.6%"), SummaryStat("Shots", "3", "5"), SummaryStat("On target", "1", "2"),
            SummaryStat("Corners", "0", "2")), game.stats.take(4))
        assertEquals(SummaryStat("Yellow cards", "0", "2"), game.stats.first { it.label == "Yellow cards" })
        assertEquals(listOf(SummaryLeader(FixtureSide.HOME, "Total Shots", "Myles Lewis-Skelly", "1"), SummaryLeader(FixtureSide.HOME, "Accurate Passes", "Declan Rice", "31")),
            game.leaders.filter { it.side == FixtureSide.HOME })
        val home = game.rosters.first { it.side == FixtureSide.HOME }
        val away = game.rosters.first { it.side == FixtureSide.AWAY }
        assertEquals("4-2-3-1", home.formation)
        assertEquals("3-4-2-1", away.formation)
        assertEquals(11 to 9, home.starters.size to home.subs.size)
        assertEquals(SummaryPlayer("1", "David Raya", "G", true), home.starters[0])
        assertTrue(home.subs.all { it.position == null && !it.subbedIn })
        assertEquals(listOf("Ao Tanaka", "Ethan Ampadu"), away.starters.filter { it.booked }.map { it.name })
        assertTrue((home.starters + away.starters).none { it.sentOff || it.subbedOut })
        assertEquals(listOf(SummaryPlay("Offside, Leeds United. Jayden Bogle is caught offside.", 1, "40'"),
            SummaryPlay("Ethan Ampadu (Leeds United) is shown the yellow card for a bad foul.", 1, "38'"),
            SummaryPlay("Ben White (Arsenal) wins a free kick in the defensive half.", 1, "38'")), game.lastPlays)
        assertTrue(game.winProbability.isEmpty())
        assertTrue(game.tables.isEmpty() && game.headToHead.isEmpty())
        val staff = sample("epl-summary").replace("\"participants\":[{\"athlete\":{\"id\":\"253173\",\"displayName\":\"Ao Tanaka\"}}],", "")
        assertEquals(listOf("38'"), EspnSummary.parse(staff, league("epl")).moments.map { it.clock })
    }

    @Test fun nblFourthQuarterScoreboardSummaryAndMarkers() {
        val live = EspnScoreboard.parse(sample("nbl-scoreboard"), league("nbl"), now).single()
        assertEquals("Perth Wildcats v New Zealand Breakers", live.title)
        assertEquals(FixtureStatus.LIVE, live.status)
        assertEquals("91–76", live.score)
        assertEquals(4, live.period)
        assertEquals("2:51", live.clock)
        assertEquals("2:51 - 4th", live.detail)
        assertEquals(FixtureLine("91", listOf("21", "20", "29", "21")), live.homeLine)
        assertEquals(FixtureLine("76", listOf("22", "16", "28", "10")), live.awayLine)
        assertEquals("Dylan Windler makes 26-foot three point jumper (Elijah Pepper assists)", live.situation?.lastPlay)
        val game = EspnSummary.parse(sample("nbl-summary"), league("nbl"))
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals(SummaryLine("91", listOf("21", "20", "29", "21")), game.homeLine)
        assertEquals(13, game.moments.size)
        assertEquals(SummaryMoment(MomentKind.POINTS, FixtureSide.HOME, 4, "2:51", "Dylan Windler makes 26-foot three point jumper (Elijah Pepper assists)", 91, 76),
            game.moments.last())
        assertEquals("Sam Mennenga bad pass turnover (Anthony Dell'Orso steals)", game.lastPlays[1].text)
        assertNull(game.run)
        assertTrue(game.winProbability.isEmpty())
        val elapsed = (now - live.startMillis) / 60_000.0
        assertEquals(102.0, elapsed, 0.01)
        val progress = SportsMarkers.minutes("basketball", game.period, game.clock, league = "nbl")!!
        assertEquals(104.445, progress, 0.001)
        assertEquals(elapsed, progress, 4.0)
        val markers = SportsMarkers.all(game, live.startMillis, league = "nbl")
        assertEquals(progress, (markers.last().second.millis - live.startMillis) / 60_000.0, 0.001)
        assertTrue(markers.zipWithNext().all { (a, b) -> a.second.millis <= b.second.millis })
    }
}
