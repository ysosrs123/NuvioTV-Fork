package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsSummaryTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/$name")!!.bufferedReader().use { it.readText() }
    private fun summary(name: String, league: String) = EspnSummary.parse(sample("espn-$name-summary-synthetic.json"), SportsLeagues.byId(league)!!)
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val minute = 60_000L

    @Test fun soccerKeyEventsStatsRostersTableAndHeadToHead() {
        val game = summary("epl", "epl")
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals(2, game.period)
        assertEquals("67'", game.clock)
        assertEquals("Arsenal", game.homeName)
        assertEquals(listOf(MomentKind.GOAL, MomentKind.CARD_YELLOW, MomentKind.SUBSTITUTION, MomentKind.GOAL, MomentKind.CARD_RED), game.moments.map { it.kind })
        val goal = game.moments[0]
        assertEquals(SummaryMoment(MomentKind.GOAL, FixtureSide.HOME, 1, "23'", "Bukayo Saka Goal", 1, 0, millis("2026-10-10T14:23:40Z")), goal)
        assertEquals("Yellow Card – Ethan Ampadu", game.moments[1].text)
        assertEquals(FixtureSide.AWAY, game.moments[1].side)
        assertNull(game.moments[1].homeScore)
        assertEquals("Substitution – Gabriel Martinelli, Leandro Trossard", game.moments[2].text)
        assertEquals(1 to 1, game.moments[3].homeScore to game.moments[3].awayScore)
        assertEquals("Penalty - Scored – Joel Piroe", game.moments[3].text)
        assertEquals(EspnSummary.MAX_STATS, game.stats.size)
        assertEquals(SummaryStat("Possession", "61.2%", "38.8%"), game.stats[0])
        assertEquals(listOf("Possession", "Shots", "On target", "Corners", "Fouls"), game.stats.take(5).map { it.label })
        assertEquals(listOf(SummaryLeader(FixtureSide.HOME, "Goals", "Bukayo Saka", "1")), game.leaders)
        val home = game.rosters.first { it.side == FixtureSide.HOME }
        assertEquals(3, home.starters.size)
        assertEquals(SummaryPlayer("8", "Martin Odegaard", "AM", true, captain = true), home.starters[1])
        assertTrue(home.starters[2].subbedOut)
        assertEquals(listOf(true, false), home.subs.map { it.subbedIn })
        assertEquals(1, game.rosters.first { it.side == FixtureSide.AWAY }.starters.size)
        val table = game.tables.single()
        assertEquals("Premier League", table.name)
        assertEquals(listOf(FixtureSide.HOME, null, FixtureSide.AWAY), table.rows.map { it.side })
        assertEquals(listOf("GP" to "7", "W" to "5", "D" to "1", "L" to "1", "GD" to "+9", "PTS" to "16"), table.rows[0].stats)
        val meeting = game.headToHead.single()
        assertEquals(SummaryMeeting(millis("2025-08-23T14:00:00Z"), "5", "0", "5-0", "Leeds United", "W", FixtureSide.HOME), meeting)
        assertTrue(game.lastPlays.isEmpty())
        assertNull(game.count)
        assertNull(game.run)
    }

    @Test fun nflScoringPlaysStatsLeadersAndProbability() {
        val game = summary("nfl", "nfl")
        assertEquals(listOf(MomentKind.TOUCHDOWN, MomentKind.POINTS), game.moments.map { it.kind })
        assertEquals(listOf(FixtureSide.HOME, FixtureSide.AWAY), game.moments.map { it.side })
        assertEquals(7 to 3, game.moments[1].homeScore to game.moments[1].awayScore)
        assertEquals(millis("2026-10-11T20:16:02Z"), game.moments[0].wallclockMillis)
        assertEquals(SummaryStat("1st downs", "12", "9"), game.stats[0])
        assertEquals(6, game.stats.size)
        assertEquals(listOf(0.5f, 0.71f, 0.64f), game.winProbability)
        assertEquals(listOf("Passing Yards", "Rushing Yards", "Passing Yards"), game.leaders.map { it.category })
        assertEquals("14/20, 170 YDS, 1 TD", game.leaders[0].stat)
        assertEquals(SummaryPlay("K.Walker left end to SEA 40 for 6 yards.", 3, "11:20"), game.lastPlays[0])
        assertEquals(3, game.lastPlays.size)
        assertEquals(SummaryLine("7", listOf("7", "0")), game.homeLine)
        assertTrue(game.rosters.isEmpty())
    }

    @Test fun nbaRunProbabilityCapAndLeaders() {
        val game = summary("nba", "nba")
        assertEquals(SummaryRun(FixtureSide.AWAY, 8), game.run)
        assertEquals("8–0", game.run!!.text)
        assertEquals(EspnSummary.MAX_PROBABILITY, game.winProbability.size)
        assertEquals(0.31f, game.winProbability.last())
        assertEquals(8, game.moments.size)
        assertTrue(game.moments.all { it.kind == MomentKind.POINTS })
        assertEquals(95 to 99, game.moments.last().homeScore to game.moments.last().awayScore)
        assertEquals(SummaryStat("FG", "27-58", "28-60"), game.stats[0])
        assertEquals(4, game.stats.size)
        assertEquals(listOf("28 PTS", "11 REB"), game.leaders.map { it.stat })
        assertEquals(SummaryPlay("Lakers Full Timeout", 4, "45.2"), game.lastPlays[0])
        assertEquals(listOf("20", "25", "22", "28"), game.homeLine?.periods)
    }

    @Test fun mlbCountBasesAndRuns() {
        val game = summary("mlb", "mlb")
        assertEquals(SummaryCount(1, 1, 1, onFirst = true, onSecond = false, onThird = true, inning = 5, bottom = true,
            pitcher = "Tanner Bibee", batter = "Andrew Benintendi"), game.count)
        assertEquals(listOf(MomentKind.RUN, MomentKind.RUN, MomentKind.RUN), game.moments.map { it.kind })
        assertEquals(listOf(FixtureSide.AWAY, FixtureSide.HOME, FixtureSide.HOME), game.moments.map { it.side })
        assertEquals(listOf(false, true, true), game.moments.map { it.bottom })
        assertEquals(SummaryStat("Hits", "7", "6"), game.stats[0])
        assertEquals(listOf("Hits", "Errors", "Home runs", "RBI"), game.stats.map { it.label })
        assertEquals(3, game.winProbability.size)
        assertEquals("Pitch 2 : Ball 1", game.lastPlays[0].text)
    }

    @Test fun nhlPowerPlayAndGoals() {
        val game = summary("nhl", "nhl")
        assertEquals(SummaryStrength("Power Play", FixtureSide.AWAY), game.strength)
        assertEquals(2, game.moments.size)
        assertTrue(game.moments.all { it.kind == MomentKind.GOAL && it.wallclockMillis != null })
        assertTrue(game.winProbability.isEmpty())
    }

    @Test fun aflGoalsBehindsAndQuarters() {
        val game = summary("afl", "afl")
        assertEquals(FixtureStatus.FINAL, game.status)
        assertEquals(SummaryLine("89", listOf("22", "21", "29", "17"), "13.11"), game.homeLine)
        assertEquals("14.12", game.awayLine?.breakdown)
        assertEquals(listOf(MomentKind.GOAL, MomentKind.POINTS, MomentKind.GOAL), game.moments.map { it.kind })
        assertEquals(listOf("Disposals", "Kicks", "Handballs", "Marks", "Tackles", "Inside 50s", "Clearances"), game.stats.map { it.label })
        val split = EspnSummary.parse(JSONObject("""{"header":{"competitions":[{"competitors":[
            {"homeAway":"home","score":"20","linescores":[{"period":1,"goals":2,"behinds":3},{"period":2,"goals":0,"behinds":5}]},
            {"homeAway":"away","score":"7","linescores":[{"period":1,"goals":1,"behinds":1}]}]}]}}"""), "australian-football")
        assertEquals(SummaryLine("20", listOf("2.3", "0.5"), "2.8"), split.homeLine)
    }

    @Test fun nrlDetailsTriesRosterAndHeadToHead() {
        val game = summary("nrl", "nrl")
        assertEquals(listOf(MomentKind.TRY, MomentKind.POINTS, MomentKind.TRY, MomentKind.POINTS, MomentKind.POINTS, MomentKind.POINTS, MomentKind.TRY,
            MomentKind.POINTS, MomentKind.CARD_YELLOW), game.moments.map { it.kind })
        assertEquals(SummaryMoment(MomentKind.TRY, FixtureSide.AWAY, null, "8'", "Try – Dominic Young", 0, 4), game.moments[0])
        assertEquals(12 to 10, game.moments[7].homeScore to game.moments[7].awayScore)
        assertEquals("Sin Bin – Tyson Frizell", game.moments.last().text)
        assertEquals(listOf("6", "6"), game.homeLine?.periods)
        assertEquals(listOf("10", "0"), game.awayLine?.periods)
        val roster = game.rosters.single()
        assertTrue(roster.starters.single().captain)
        assertTrue(roster.subs.single().subbedIn)
        assertEquals(5, game.headToHead.size)
        assertEquals(listOf("W", "L", "W", "D", "L"), game.headToHead.map { it.result })
    }

    @Test fun rugbyUnionStandingsFromChildren() {
        val game = summary("urc", "super-rugby")
        assertEquals(FixtureStatus.SCHEDULED, game.status)
        val table = game.tables.single()
        assertEquals("United Rugby Championship", table.name)
        assertEquals(listOf(FixtureSide.HOME, null, FixtureSide.AWAY), table.rows.map { it.side })
        assertEquals(listOf("GP", "W", "D", "L", "PD", "PTS"), table.rows[0].stats.map { it.first })
        assertEquals(1, game.headToHead.size)
        assertTrue(game.stats.isEmpty())
        assertNull(game.homeLine?.score)
        assertTrue(game.moments.isEmpty())
    }

    @Test fun toleratesEmptyAndCricketInnings() {
        val empty = EspnSummary.parse(JSONObject("{}"), "soccer")
        assertEquals(FixtureStatus.SCHEDULED, empty.status)
        assertTrue(empty.moments.isEmpty() && empty.tables.isEmpty())
        val cricket = EspnSummary.parse(JSONObject("""{"header":{"competitions":[{"status":{"type":{"state":"in"}},"competitors":[
            {"id":"1","homeAway":"home","team":{"id":"1","displayName":"Sixers"},"linescores":[{"period":1,"runs":176,"wickets":6,"overs":"20"}]},
            {"id":"2","homeAway":"away","team":{"id":"2","displayName":"Scorchers"},"linescores":[{"period":2,"runs":88,"wickets":2,"overs":"10.3","target":177}]}]}]}}"""), "cricket")
        assertEquals(listOf(SummaryInnings(FixtureSide.HOME, 1, 176, 6, "20", null), SummaryInnings(FixtureSide.AWAY, 2, 88, 2, "10.3", 177)), cricket.innings)
        assertNull(cricket.homeLine)
    }

    @Test fun markersUseWallclockOrPeriodClock() {
        val epl = summary("epl", "epl")
        val start = millis("2026-10-10T14:00:00Z")
        val markers = SportsMarkers.all(epl, start)
        assertEquals(SportsMarker(millis("2026-10-10T14:23:40Z"), true), markers[0].second)
        assertEquals(SportsMarker(start + 47 * minute, false), markers[1].second)
        assertEquals(SportsMarker(start + 78 * minute, false), markers[3].second)
        val nrl = summary("nrl", "nrl")
        assertEquals(start + 57 * minute, SportsMarkers.estimate(nrl.moments[6], "rugby-league", start)!!.millis)
        assertEquals(start + 8 * minute, SportsMarkers.estimate(nrl.moments[0], "rugby-league", start)!!.millis)
        val nfl = summary("nfl", "nfl")
        assertEquals(76.667, SportsMarkers.minutes("american-football", 2, "2:00")!!, 0.01)
        assertFalse(SportsMarkers.estimate(nfl.moments[1], "american-football", start)!!.exact)
        assertEquals(147.97, SportsMarkers.minutes("basketball", 4, "45.2")!!, 0.01)
        assertEquals(61.69, SportsMarkers.minutes("ice-hockey", 2, "15:02")!!, 0.01)
        assertEquals(85.5, SportsMarkers.minutes("baseball", 5, null, bottom = true)!!, 0.01)
        assertEquals(150.83, SportsMarkers.minutes("australian-football", 4, "28:50")!!, 0.01)
        assertNull(SportsMarkers.minutes("cricket", 1, "10:00"))
        val mlb = summary("mlb", "mlb")
        assertEquals(SportsMarker(start + 85L * minute + 30_000, false), SportsMarkers.estimate(mlb.moments[2], "baseball", start))
        val afl = summary("afl", "afl")
        val clamped = SportsMarkers.estimate(afl.moments[2], "australian-football", start, start - 5 * minute, start + 150 * minute)
        assertEquals(SportsMarker(start + 150 * minute, false), clamped)
        val early = SportsMarkers.estimate(epl.moments[0], "soccer", start, start + 30 * minute, start + 120 * minute)
        assertEquals(SportsMarker(start + 30 * minute, true), early)
    }
}
