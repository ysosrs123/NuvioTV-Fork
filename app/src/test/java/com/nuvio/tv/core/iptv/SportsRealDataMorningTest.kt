package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataMorningTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/espn-$name-real-20261010.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-09T23:50:00Z")

    @Test fun nhlLiveScoreboardBetweenPeriods() {
        val games = EspnScoreboard.parse(sample("nhl-scoreboard"), league("nhl"), now)
        val live = games[0]
        assertEquals("Detroit Red Wings v Seattle Kraken", live.title)
        assertEquals(FixtureStatus.LIVE, live.status)
        assertEquals("1–1", live.score)
        assertEquals(1, live.period)
        assertEquals("0:00", live.clock)
        assertEquals("End of 1st", live.detail)
        assertEquals(FixtureLine("1", listOf("1")), live.homeLine)
        assertEquals("1-2-0", live.home?.record)
        assertEquals(FixtureSituation(null, null, "End of 1st Period", null), live.situation)
        val next = games[1]
        assertEquals(FixtureStatus.SCHEDULED, next.status)
        assertNull(next.score)
        assertNull(next.clock)
    }

    @Test fun nhlLiveSummaryGoalsGoaliesAndPowerPlay() {
        val json = sample("nhl-summary")
        val game = EspnSummary.parse(json, league("nhl"))
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals(1, game.period)
        assertEquals("End of 1st", game.detail)
        assertEquals(SummaryLine("1", listOf("1")), game.homeLine)
        assertEquals(listOf(FixtureSide.HOME, FixtureSide.AWAY), game.moments.map { it.side })
        assertTrue(game.moments.all { it.kind == MomentKind.GOAL && it.wallclockMillis != null })
        assertEquals(SummaryMoment(MomentKind.GOAL, FixtureSide.HOME, 1, "9:19",
            "Viktor Arvidsson Goal (3) Wrist Shot, assists: Lucas Raymond (4), Moritz Seider (2)", 1, 0, millis("2026-10-09T23:24:39Z")), game.moments[0])
        assertEquals(SummaryStat("Shots", "18", "5"), game.stats[0])
        assertEquals(listOf(SummaryGoalie(FixtureSide.AWAY, "Philipp Grubauer", 17, 18, ".944"), SummaryGoalie(FixtureSide.HOME, "John Gibson", 4, 5, ".800")),
            game.goalies)
        assertNull(game.strength)
        assertEquals(listOf("End of 1st Period", "Berkly Catton shot blocked by Viktor Arvidsson", "Moritz Seider Wrist Shot Wide Left"), game.lastPlays.map { it.text })
        fun upTo(count: Int) = EspnSummary.parse(JSONObject(json).apply {
            val plays = getJSONArray("plays")
            put("plays", JSONArray((0 until count).map { plays.get(it) }))
        }, "ice-hockey")
        assertEquals(SummaryStrength("Power Play", FixtureSide.AWAY), upTo(7).strength)
        assertEquals(SummaryStrength("Power Play", FixtureSide.HOME), upTo(2).strength)
    }

    @Test fun hockeyPlayClocksCountUp() {
        val start = millis("2026-10-09T23:00:00Z")
        val goal = SummaryMoment(MomentKind.GOAL, FixtureSide.HOME, 1, "9:19", "Goal")
        assertEquals(SportsMarker(start + 978_250, false), SportsMarkers.estimate(goal, "ice-hockey", start))
        assertEquals(SportsMarkers.minutes("ice-hockey", 2, "10:41")!!, SportsMarkers.minutes("ice-hockey", 2, "9:19", elapsed = true)!!, 0.001)
    }

    @Test fun golfMidRoundThruTodayAndTeeTimes() {
        val event = EspnScoreboard.parse(sample("pga-scoreboard"), league("pga"), now).single()
        assertEquals(FixtureStatus.LIVE, event.status)
        assertEquals("Round 3 - In Progress", event.detail)
        assertEquals(listOf("Golf Chnl"), event.broadcasters)
        val golf = event.sportDetail as SportsDetail.Golf
        assertEquals(3, golf.round)
        assertEquals(listOf("T1", "T1", "T3", "T3", "5", "6", "7"), golf.leaders.map { it.position })
        assertEquals(GolfPlayer("T1", "Keith Mitchell", "K. Mitchell", "USA", "https://a.espncdn.com/i/teamlogos/countries/500/usa.png", "-11", null, null,
            millis("2026-10-10T01:36:00Z")), golf.leaders[0])
        assertEquals(GolfPlayer("5", "Takumi Kanaya", "T. Kanaya", "JPN", "https://a.espncdn.com/i/teamlogos/countries/500/jpn.png", "-4", "1", "-1"), golf.leaders[4])
        assertEquals(listOf("1", "E"), golf.leaders[5].let { listOf(it.thru, it.today) })
        assertEquals("+10", golf.leaders.last().toPar)
        assertEquals(millis("2026-10-10T01:25:00Z"), golf.leaders[2].teeMillis)
        assertEquals(golf, SportsDetails.decode(SportsDetails.encode(golf)))
        assertEquals(millis("2026-01-15T13:05:00Z"), EspnScoreboard.teeTime("Thu Jan 15 08:05:00 PST 2026"))
        assertNull(EspnScoreboard.teeTime("2026-10-10T01:36Z"))
    }

    @Test fun doublesMatchTiebreakAndRetirement() {
        val wta = EspnScoreboard.parse(sample("wta-scoreboard"), league("wta"), millis("2026-10-09T18:00:00Z")).single()
        assertEquals("Su-Wei Hsieh / Jelena Ostapenko v Erin Routliffe / Aldila Sutjiadi", wta.title)
        assertEquals("1–2", wta.score)
        val sets = (wta.sportDetail as SportsDetail.Tennis).sets
        assertEquals(listOf(TennisSet(6, 7, 5, 7, FixtureSide.AWAY), TennisSet(6, 4, null, null, FixtureSide.HOME), TennisSet(7, 10, null, null, FixtureSide.AWAY)), sets)
        assertEquals(FixtureLine("1", listOf("6", "6", "7")), wta.homeLine)
        assertEquals(FixtureLine("2", listOf("7", "4", "10")), wta.awayLine)
        val retired = EspnScoreboard.parse(sample("wta-scoreboard"), league("atp"), millis("2026-10-01T18:00:00Z")).single()
        assertEquals("Retired", retired.detail)
        assertEquals("1–1", retired.score)
        assertEquals(TennisSet(7, 4, null, null, null), (retired.sportDetail as SportsDetail.Tennis).sets.last())
    }

    @Test fun formulaOneSessionResults() {
        val f1 = EspnScoreboard.parse(sample("f1-scoreboard"), league("f1"), now).single()
        val sessions = (f1.sportDetail as SportsDetail.Sessions).sessions
        assertEquals(listOf("FP1", "SS", "SR", "Qual", "Race"), sessions.map { it.abbreviation })
        assertEquals(listOf(FixtureStatus.FINAL, FixtureStatus.FINAL, FixtureStatus.SCHEDULED, FixtureStatus.SCHEDULED, FixtureStatus.SCHEDULED), sessions.map { it.state })
        assertEquals(listOf("G. Russell", "C. Leclerc", "L. Norris"), sessions[0].top)
        assertEquals(listOf("M. Verstappen", "G. Russell", "C. Leclerc"), sessions[1].top)
        assertTrue(sessions.drop(2).all { it.top.isEmpty() })
        assertEquals(FixtureStatus.SCHEDULED, f1.status)
        assertEquals(millis("2026-10-10T09:00:00Z"), f1.startMillis)
        val detail = f1.sportDetail!!
        assertEquals(detail, SportsDetails.decode(SportsDetails.encode(detail)))
    }

    @Test fun nflScoringPlaysAndDrives() {
        val game = EspnSummary.parse(sample("nfl-summary"), league("nfl"))
        assertEquals(FixtureStatus.FINAL, game.status)
        assertEquals(7, game.moments.size)
        assertEquals(SummaryMoment(MomentKind.TOUCHDOWN, FixtureSide.HOME, 1, "10:51", "Javonte Williams 1 Yd Rush (Brandon Aubrey Kick)", 7, 0,
            millis("2026-10-09T00:22:49Z")), game.moments[0])
        assertEquals(listOf(MomentKind.TOUCHDOWN, MomentKind.TOUCHDOWN, MomentKind.POINTS, MomentKind.TOUCHDOWN, MomentKind.TOUCHDOWN, MomentKind.POINTS,
            MomentKind.TOUCHDOWN), game.moments.map { it.kind })
        assertEquals(listOf(FixtureSide.HOME, FixtureSide.AWAY, FixtureSide.HOME, FixtureSide.AWAY, FixtureSide.AWAY, FixtureSide.AWAY, FixtureSide.HOME),
            game.moments.map { it.side })
        assertEquals(16 to 24, game.moments.last().homeScore to game.moments.last().awayScore)
        assertEquals("END GAME", game.lastPlays[0].text)
        assertEquals(0f, game.winProbability.last())
        assertEquals(SummaryStat("1st downs", "20", "24"), game.stats[0])
    }
}
