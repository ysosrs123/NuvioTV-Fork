package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataQuarterBreakTest {
    private fun sample(name: String, day: String): String =
        javaClass.getResourceAsStream("/sports/$name-real-$day.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = "2026-10-17T20:31:00Z"

    private fun merged(id: String, sport: String, start: String, league: String): SportsFixture =
        SportsDbLive.merge(listOf(SportsFixture(id, league, sport, "Home v Away", FixtureTeam("Home"), FixtureTeam("Away"), millis(start), FixtureStatus.SCHEDULED,
            source = SportsService.THESPORTSDB)), SportsDbLive.parse(sample("tsdb-livescore-all", "20261018")).associateBy { it.eventId }, millis(now)).single()

    private fun games(json: String = sample("espn-ncaaf-scoreboard", "20261018")) =
        EspnScoreboard.parse(json, league("college-football"), millis(now)).associateBy { it.id }

    @Test fun quarterBreakWithAFullClockReadsEndOfQuarter() {
        val games = games()
        val tennessee = games.getValue("401856723")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "9–0", 1, "0:00", "End of 1st"), listOf(tennessee.status, tennessee.score, tennessee.period, tennessee.clock, tennessee.detail))
        assertEquals("End of 1st", SportsFixtureText.periodClock(tennessee))
        assertEquals("ALA 0–9 TENN" to "End of 1st", SportsFixtureText.bug(tennessee).let { it.primary to it.state })
        assertEquals("End of 1st quarter.", tennessee.situation?.lastPlay)
        assertEquals(FixtureLine("9", listOf("9")), tennessee.homeLine)
        val texasTech = games.getValue("401856828")
        assertEquals(listOf<Any?>("0:00", "End of 1st", "End of 1st"), listOf(texasTech.clock, texasTech.detail, SportsFixtureText.periodClock(texasTech)))
        val georgia = games.getValue("401856719")
        assertEquals(listOf<Any?>("10:38", "10:38 - 2nd", "Q2 · 10:38"), listOf(georgia.clock, georgia.detail, SportsFixtureText.periodClock(georgia)))
        val entry = SportsCacheEntry(games.values.toList(), millis(now))
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun nextPeriodAtAFullClockKeepsItsClock() {
        val root = JSONObject(sample("espn-ncaaf-scoreboard", "20261018"))
        val event = (0 until root.getJSONArray("events").length()).map { root.getJSONArray("events").getJSONObject(it) }.single { it.getString("id") == "401856723" }
        val status = event.getJSONArray("competitions").getJSONObject(0).getJSONObject("status")
        status.put("period", 2).getJSONObject("type").put("shortDetail", "15:00 - 2nd")
        val next = games(root.toString()).getValue("401856723")
        assertEquals(listOf<Any?>("15:00", "15:00 - 2nd", "Q2 · 15:00"), listOf(next.clock, next.detail, SportsFixtureText.periodClock(next)))
        status.put("period", 1).getJSONObject("type").put("shortDetail", "15:00 - 1st")
        event.getJSONArray("competitions").getJSONObject(0).getJSONObject("situation").getJSONObject("lastPlay").put("text", "(15:00) Kickoff to the ALA 25")
        assertEquals("Q1 · 15:00", SportsFixtureText.periodClock(games(root.toString()).getValue("401856723")))
    }

    @Test fun sportsDbRowsAgreeWithTheScoreboard() {
        val winterLeague = merged("2609140", "baseball", "2026-10-17T20:00:00Z", "mlb")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, null, "Inning 1", 1), listOf(winterLeague.status, winterLeague.score, winterLeague.detail, winterLeague.period))
        val jets = merged("2591606", "ice-hockey", "2026-10-17T19:30:00Z", "nhl")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "0–0", "BT"), listOf(jets.status, jets.score, SportsFixtureText.periodClock(jets)))
        assertEquals("P1 · 12'", SportsFixtureText.periodClock(merged("2591607", "ice-hockey", "2026-10-17T20:00:00Z", "nhl")))
        val capitals = merged("2591605", "ice-hockey", "2026-10-17T17:00:00Z", "nhl")
        assertEquals(FixtureStatus.FINAL to "4–2", capitals.status to capitals.score)
        val football = merged("2580629", "soccer", "2026-10-17T17:30:00Z", "epl")
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, "3–0", null), listOf(football.status, football.score, football.clock))
        val broncos = merged("2475453", "american-football", "2026-10-16T00:15:00Z", "nfl")
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, "17–14", "Final/OT"), listOf(broncos.status, broncos.score, broncos.detail))
    }
}
