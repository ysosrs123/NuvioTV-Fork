package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataLiveTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/espn-$name-real-20261010b.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-10T09:07:00Z")

    @Test fun nblLiveScoreboardInSecondQuarter() {
        val games = EspnScoreboard.parse(sample("nbl-scoreboard"), league("nbl"), now)
        val live = games[0]
        assertEquals("South East Melbourne Phoenix v Melbourne United", live.title)
        assertEquals(FixtureStatus.LIVE, live.status)
        assertEquals(millis("2026-10-10T08:30:00Z"), live.startMillis)
        assertEquals("37–27", live.score)
        assertEquals(2, live.period)
        assertEquals("5:56", live.clock)
        assertEquals("5:56 - 2nd", live.detail)
        assertEquals(FixtureLine("37", listOf("30", "7")), live.homeLine)
        assertEquals(FixtureLine("27", listOf("17", "10")), live.awayLine)
        assertEquals(FixtureSituation(null, null, "Cole Anthony makes shot", null), live.situation)
        assertEquals(FixtureStatus.SCHEDULED, games[1].status)
        assertNull(games[1].clock)
    }

    @Test fun nblLiveSummaryClockMomentsAndTenMinuteQuarters() {
        val game = EspnSummary.parse(sample("nbl-summary"), league("nbl"))
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals(2, game.period)
        assertEquals("5:56", game.clock)
        assertEquals(SummaryLine("37", listOf("30", "7")), game.homeLine)
        assertEquals(SummaryLine("27", listOf("17", "10")), game.awayLine)
        assertEquals(34, game.moments.size)
        assertTrue(game.moments.all { it.kind == MomentKind.POINTS && it.wallclockMillis == null })
        assertEquals(SummaryMoment(MomentKind.POINTS, FixtureSide.HOME, 1, "9:42", "D'Shawn Schwartz makes 12-foot hook shot (Nathan Sobey assists)", 2, 0),
            game.moments[0])
        assertEquals(SummaryMoment(MomentKind.POINTS, FixtureSide.AWAY, 2, "6:03", "Cole Anthony makes shot", 37, 27), game.moments.last())
        assertEquals(listOf("Cole Anthony makes shot", "Cole Anthony defensive rebound", "Dash Daniels misses shot"), game.lastPlays.map { it.text })
        assertEquals(SummaryPlay("Cole Anthony makes shot", 2, "6:03"), game.lastPlays[0])
        assertTrue(game.winProbability.isEmpty())
        assertNull(game.run)
        assertEquals(SummaryStat("FG", "13-27", "10-26"), game.stats[0])
        assertEquals(SummaryLeader(FixtureSide.HOME, "Points", "D'Shawn Schwartz", "8 PTS"), game.leaders[0])
        val start = millis("2026-10-10T08:30:00Z")
        val markers = SportsMarkers.all(game, start, league = "nbl")
        assertEquals(34, markers.size)
        assertEquals(39.665, (markers.last().second.millis - start) / 60_000.0, 0.001)
        assertTrue(markers.zipWithNext().all { (a, b) -> a.second.millis <= b.second.millis })
        val progress = SportsMarkers.minutes("basketball", game.period, game.clock, league = "nbl")!!
        assertEquals(39.98, progress, 0.01)
        assertEquals(37.0, progress, 4.0)
        assertTrue(SportsMarkers.minutes("basketball", game.period, game.clock, league = "nba")!! > 50.0)
    }

    @Test fun tennisLiveSetsServeAndTiebreak() {
        val matches = EspnScoreboard.parse(sample("atp-scoreboard"), league("atp"), now).associateBy { it.id }
        assertEquals(listOf("184901", "184978", "184912", "184905"), EspnScoreboard.parse(sample("atp-scoreboard"), league("atp"), now).map { it.id })
        val third = matches.getValue("184901")
        assertEquals(FixtureStatus.LIVE, third.status)
        assertEquals("1–1", third.score)
        assertEquals("3rd Set", third.detail)
        assertEquals(3, third.period)
        assertEquals(FixtureLine("1", listOf("6", "3", "2")), third.homeLine)
        val tennis = third.sportDetail as SportsDetail.Tennis
        assertEquals(TennisSet(2, 2), tennis.sets.last())
        assertEquals(FixtureSide.HOME, tennis.server)
        assertEquals(TennisPlayer(28, "https://a.espncdn.com/i/teamlogos/countries/500/arg.png", "Argentina"), tennis.awayPlayer)
        assertFalse(tennis.servingForSet)
        assertEquals(tennis, SportsDetails.decode(SportsDetails.encode(tennis)))
        val doubles = matches.getValue("184978").sportDetail as SportsDetail.Tennis
        assertEquals(listOf(TennisSet(5, 7, null, null, FixtureSide.AWAY), TennisSet(6, 6, 8, 6, null)), doubles.sets)
        assertNull(doubles.server)
        assertEquals("0–1", matches.getValue("184978").score)
        assertNull((matches.getValue("184912").sportDetail as SportsDetail.Tennis).server)
        val first = matches.getValue("184905")
        assertEquals("0–0", first.score)
        assertEquals(FixtureSide.AWAY, (first.sportDetail as SportsDetail.Tennis).server)
        assertEquals(FixtureLine("0", listOf("4")), first.homeLine)
    }

    @Test fun golfBetweenRounds() {
        val event = EspnScoreboard.parse(sample("pga-scoreboard"), league("pga"), now).single()
        assertEquals(FixtureStatus.LIVE, event.status)
        assertEquals("Round 3 - Play Complete", event.detail)
        assertEquals(3, event.period)
        val golf = event.sportDetail as SportsDetail.Golf
        assertEquals(3, golf.round)
        assertEquals(listOf("T1", "T1", "T1", "4", "5"), golf.leaders.map { it.position })
        assertTrue(golf.leaders.all { it.thru == "F" && it.teeMillis == null })
        assertEquals(listOf("-6", "-4", "-2", "-6", "+7"), golf.leaders.map { it.today })
        assertEquals(millis("2026-10-11T04:00:00Z"), golf.endMillis)
    }

    @Test fun rugbyUnionSummaryDropsTrailingEmptyPeriods() {
        val game = EspnSummary.parse(sample("urc-summary"), league("urc"))
        assertEquals(FixtureStatus.FINAL, game.status)
        assertEquals(SummaryLine("36", listOf("0", "36")), game.homeLine)
        assertEquals(SummaryLine("19", listOf("0", "19")), game.awayLine)
    }
}
