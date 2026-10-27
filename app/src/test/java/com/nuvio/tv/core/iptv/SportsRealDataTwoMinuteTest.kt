package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataTwoMinuteTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/espn-nfl-$name-real-20261027.json")!!.bufferedReader().use { it.readText() }
    private val nfl = SportsLeagues.byId("nfl")!!

    @Test fun twoMinuteWarningIsTheLastPlayWithTheDownKept() {
        val game = EspnScoreboard.parse(sample("scoreboard"), nfl, Instant.parse("2026-10-27T01:30:00Z").toEpochMilli()).single()
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "14–17", 2, "2:00"), listOf(game.status, game.score, game.period, game.clock))
        val situation = game.situation!!
        assertEquals(listOf<Any?>("Two-Minute Warning", "2nd & 8 · DAL 4", FixtureSide.AWAY, false, 2, 3),
            listOf(situation.lastPlay, situation.downDistance, situation.possession, situation.redZone, situation.homeTimeouts, situation.awayTimeouts))
        assertEquals(SportsBugText("DAL 17–14 PHI", "Q2 · 2:00", "2nd & 8 · DAL 4"), SportsFixtureText.bug(game))
        val entry = SportsCacheEntry(listOf(game), Instant.parse("2026-10-27T01:30:00Z").toEpochMilli())
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun summaryLeadsWithTheWarning() {
        val summary = EspnSummary.parse(sample("summary"), nfl)!!
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, 2, "2:00"), listOf(summary.status, summary.period, summary.clock))
        assertEquals(listOf("Two-Minute Warning" to "2:00", "D.Prescott up the middle to DAL 4 for 2 yards (M.Ojomo)." to "2:28"),
            summary.lastPlays.take(2).map { it.text to it.clock })
        assertEquals(listOf("7", "7") to listOf("10", "7"), summary.homeLine?.periods to summary.awayLine?.periods)
    }
}
