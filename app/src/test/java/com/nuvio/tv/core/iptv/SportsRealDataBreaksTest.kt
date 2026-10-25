package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataBreaksTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/$name.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private val now = Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()

    @Test fun pitchingChangeIsTheLatestPlayUntilTheNextBatter() {
        val root = JSONObject(sample("espn-mlb-summary-real-20261025"))
        val full = EspnSummary.parse(root.toString(), league("mlb"))!!
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, 4, "Top 4th", 2), listOf(full.status, full.period, full.detail, full.count?.outs))
        assertEquals("JoJo Romero pitches to Victor Mesa Jr.", full.lastPlays.first().text)
        val plays = root.getJSONArray("plays")
        val change = (0 until plays.length()).first { plays.getJSONObject(it).optString("text") == "Romero relieved Henderson" }
        assertEquals("C", plays.getJSONObject(change).optString("summaryType"))
        root.put("plays", JSONArray((0..change).map { plays.getJSONObject(it) }))
        assertEquals("Romero relieved Henderson", EspnSummary.parse(root.toString(), league("mlb"))!!.lastPlays.first().text)
    }

    @Test fun delayedFootballClockBehindTheGoalsIsLeftOut() {
        val games = EspnScoreboard.parse(sample("espn-mls-scoreboard-real-20261025"), league("mls"), now)
        assertEquals(4, games.size)
        assertTrue(games.all { it.status == FixtureStatus.LIVE && it.period == 2 && it.clock == null && it.detail == "Second Half" })
        assertEquals("Second Half", SportsFixtureText.bug(games.first { it.id == "761893" }).state)
        val summary = EspnSummary.parse(sample("espn-mls-summary-real-20261025"), league("mls"))!!
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, 2, null, "Second Half"), listOf(summary.status, summary.period, summary.clock, summary.detail))
        assertTrue(summary.moments.any { it.clock == "51'" && it.kind == MomentKind.GOAL })
    }

    @Test fun basketballQuarterBreakAndRestartInTheSummary() {
        val brk = EspnSummary.parse(sample("espn-nba-summary-real-20261025"), league("nba"))!!
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, 3, "0.0", "End of 3rd"), listOf(brk.status, brk.period, brk.clock, brk.detail))
        assertEquals(listOf("34", "30", "33") to listOf("25", "35", "27"), brk.homeLine?.periods to brk.awayLine?.periods)
        assertEquals("End of the 3rd Quarter", brk.lastPlays.first().text)
        val restart = EspnSummary.parse(sample("espn-nba-summary-real-20261025b"), league("nba"))!!
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, 3, "12:00", "12:00 - 3rd"), listOf(restart.status, restart.period, restart.clock, restart.detail))
        assertEquals(listOf("27", "35", "0") to listOf("28", "29", "0"), restart.homeLine?.periods to restart.awayLine?.periods)
    }
}
