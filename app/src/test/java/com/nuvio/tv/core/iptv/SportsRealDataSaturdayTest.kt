package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataSaturdayTest {
    private fun sample(name: String, day: String): String =
        javaClass.getResourceAsStream("/sports/$name-real-$day.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()

    private fun merged(day: String, now: String, id: String, sport: String, start: String, league: String): SportsFixture =
        SportsDbLive.merge(listOf(SportsFixture(id, league, sport, "Home v Away", FixtureTeam("Home"), FixtureTeam("Away"), millis(start), FixtureStatus.SCHEDULED,
            source = SportsService.THESPORTSDB)), SportsDbLive.parse(sample("tsdb-livescore-all", day)).associateBy { it.eventId }, millis(now)).single()

    private fun plays(root: JSONObject, count: Int): JSONObject {
        val all = root.getJSONArray("plays")
        return JSONObject(root.toString()).put("plays", JSONArray((0 until count).map { all.getJSONObject(it) }))
    }

    @Test fun basketballOvertimeFinalsOnTheScoreboard() {
        val games = EspnScoreboard.parse(sample("espn-nba-scoreboard", "20261017b"), league("nba"), millis("2026-10-17T09:59:00Z")).associateBy { it.id }
        val celtics = games.getValue("401898407")
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, "Final/OT", "120–125"), listOf(celtics.status, celtics.detail, celtics.score))
        assertEquals(FixtureLine("120", listOf("28", "32", "34", "21", "5")), celtics.homeLine)
        assertEquals(FixtureLine("125", listOf("34", "24", "26", "31", "10")), celtics.awayLine)
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4", "OT"), (1..5).map { SportsFixtureText.periodLabel(celtics, it) })
        assertEquals(SportsBugText("BOS 125–120 PHI", "Final/OT"), SportsFixtureText.bug(celtics))
        val bucks = games.getValue("401914126")
        assertEquals(listOf("29", "42", "22", "24", "10") to listOf("21", "35", "34", "27", "11"), bucks.homeLine?.periods to bucks.awayLine?.periods)
        assertEquals(SportsBugText("MIL 128–127 IND", "Final/OT"), SportsFixtureText.bug(bucks))
        assertEquals(4, games.getValue("401898406").homeLine?.periods?.size)
        assertEquals("Final", games.getValue("401898406").detail)
        val entry = SportsCacheEntry(games.values.toList(), millis("2026-10-17T09:59:00Z"))
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun postponedPlayoffGameLeavesTheScoreboardAndEndsTheSummaryWatch() {
        assertTrue(EspnScoreboard.parse(sample("espn-mlb-scoreboard", "20261017b"), league("mlb"), millis("2026-10-17T09:59:00Z")).isEmpty())
        val summary = EspnSummary.parse(JSONObject(sample("espn-mlb-summary", "20261017b")), "baseball")
        assertEquals(FixtureStatus.FINAL, summary.status)
        assertEquals("Postponed", summary.detail)
        assertNull(summary.count)
        assertTrue(summary.moments.isEmpty() && summary.lastPlays.isEmpty())
    }

    @Test fun ownGoalByThePlayerWhoScoredThePenalty() {
        val game = EspnScoreboard.parse(sample("espn-aleague-scoreboard", "20261017c"), league("a-league-men"), millis("2026-10-17T12:58:00Z")).single()
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, "FT", "2–2"), listOf(game.status, game.detail, game.score))
        assertEquals(listOf(FixtureEvent(FixtureEventKind.GOAL, FixtureSide.HOME, "11'", null, "A. Auglah"), FixtureEvent(FixtureEventKind.GOAL, FixtureSide.AWAY, "49'", null, "P. Wood"),
            FixtureEvent(FixtureEventKind.GOAL, FixtureSide.HOME, "60'", null, "O. Lavale"), FixtureEvent(FixtureEventKind.GOAL, FixtureSide.AWAY, "78'", null, "O. Lavale (own goal)")),
            game.events.filter { it.kind == FixtureEventKind.GOAL })
        assertEquals(5, game.events.count { it.kind == FixtureEventKind.YELLOW })
    }

    @Test fun hockeyPenaltyShotGivesNoPowerPlay() {
        val root = JSONObject(sample("espn-nhl-summary", "20261017c"))
        assertEquals(SummaryStrength("Power Play", FixtureSide.HOME), EspnSummary.parse(plays(root, 2), "ice-hockey").strength)
        assertEquals(SummaryStrength("Power Play", FixtureSide.HOME), EspnSummary.parse(plays(root, 4), "ice-hockey").strength)
        assertNull(EspnSummary.parse(plays(root, 6), "ice-hockey").strength)
        assertNull(EspnSummary.parse(root, "ice-hockey").strength)
    }

    @Test fun sportsDbShootOutReadsSoAndStuckRowsSettle() {
        val shootOut = merged("20261017b", "2026-10-17T01:20:00Z", "2559236", "ice-hockey", "2026-10-16T22:00:00Z", "nhl")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "3–3", "SO"), listOf(shootOut.status, shootOut.score, shootOut.detail))
        assertEquals("SO", SportsFixtureText.periodClock(shootOut))
        assertEquals("SO", SportsFixtureText.bug(shootOut).state)
        assertEquals(FixtureStatus.SCHEDULED, merged("20261017b", "2026-10-17T09:59:00Z", "2559236", "ice-hockey", "2026-10-16T22:00:00Z", "nhl").status)
        assertEquals(FixtureStatus.SCHEDULED, merged("20261017c", "2026-10-17T12:57:00Z", "2559236", "ice-hockey", "2026-10-16T22:00:00Z", "nhl").status)
        val events = SportsDbEvents.parse(JSONObject().put("events", JSONArray().put(JSONObject().put("idEvent", "2559236").put("strHomeTeam", "Halifax Mooseheads")
            .put("strAwayTeam", "Val-d'Or Foreurs").put("strStatus", "PT").put("intHomeScore", "3").put("intAwayScore", "3").put("strTimestamp", "2026-10-16T22:00:00"))).toString(),
            league("nhl"), millis("2026-10-17T01:20:00Z")).single()
        assertEquals("SO", events.detail)
        assertEquals("BT", SportsDbEvents.shown("ice-hockey", "bt", "BT"))
        assertEquals("PT", SportsDbEvents.shown("soccer", "pt", "PT"))
    }

    @Test fun sportsDbBreaksMinutesAndInnings() {
        val a = "2026-10-17T09:59:00Z"
        assertEquals("HT", SportsFixtureText.periodClock(merged("20261017b", a, "2616640", "basketball", "2026-10-17T09:00:00Z", "nba")))
        val nbl = merged("20261017b", a, "2487915", "basketball", "2026-10-17T08:30:00Z", "nbl")
        assertEquals("78–65" to "Q4 · 1'", nbl.score to SportsFixtureText.periodClock(nbl))
        val cpbl = merged("20261017b", a, "2618128", "baseball", "2026-10-17T09:05:00Z", "mlb")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, null, "Inning 3", 3), listOf(cpbl.status, cpbl.score, cpbl.detail, cpbl.period))
        assertEquals("2H · 59'", SportsFixtureText.periodClock(merged("20261017b", a, "2540109", "soccer", "2026-10-17T08:40:00Z", "a-league-men")))
        assertEquals("HT", SportsFixtureText.bug(merged("20261017b", a, "2593069", "soccer", "2026-10-17T09:00:00Z", "epl")).state)
        val b = "2026-10-17T12:57:00Z"
        val finished = merged("20261017c", b, "2618128", "baseball", "2026-10-17T09:05:00Z", "mlb")
        assertEquals(FixtureStatus.FINAL to null, finished.status to finished.score)
        assertEquals("HT", SportsFixtureText.periodClock(merged("20261017c", b, "2606141", "basketball", "2026-10-17T12:00:00Z", "nba")))
        assertEquals("1H · 45+8'", SportsFixtureText.periodClock(merged("20261017c", b, "2532025", "soccer", "2026-10-17T12:00:00Z", "epl")))
        val everton = merged("20261017c", b, "2494063", "soccer", "2026-10-17T11:30:00Z", "epl")
        assertEquals(SportsBugText("Home 0–1 Away", "66'"), SportsFixtureText.bug(everton))
        assertEquals(FixtureStatus.SCHEDULED, merged("20261017c", b, "2564064", "basketball", "2026-10-17T13:00:00Z", "nba").status)
    }

    @Test fun eventsdayLiveRowWithAShortTime() {
        val now = millis("2026-10-17T12:57:00Z")
        val day = SportsDbEvents.parse(sample("tsdb-eventsday-epl", "20261017c"), league("epl"), now).associateBy { it.id }
        assertEquals(5, day.size)
        val everton = day.getValue("2494063")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, millis("2026-10-17T11:30:00Z"), "0–1", 2, null), listOf(everton.status, everton.startMillis, everton.score, everton.period, everton.clock))
        assertEquals("2H", SportsFixtureText.periodClock(everton))
        assertTrue(day.values.filter { it.id != "2494063" }.all { it.status == FixtureStatus.SCHEDULED })
        val root = JSONObject(sample("tsdb-eventsday-epl", "20261017c"))
        root.getJSONArray("events").getJSONObject(2).remove("strTimestamp")
        assertEquals("11:30", root.getJSONArray("events").getJSONObject(2).getString("strTime"))
        assertEquals(millis("2026-10-17T11:30:00Z"), SportsDbEvents.parse(root.toString(), league("epl"), now).single { it.id == "2494063" }.startMillis)
    }
}
