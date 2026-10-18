package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataLiveCricketTest {
    private fun sample(name: String, suffix: String): String =
        javaClass.getResourceAsStream("/sports/espn-cricket-$name-real-20261018$suffix.json")!!.bufferedReader().use { it.readText() }
    private fun league(series: String) = SportsLeague("cricket-test", "Cricket", "cricket", "cricket/$series", null)
    private val now = Instant.parse("2026-10-18T12:45:00Z").toEpochMilli()

    @Test fun oneDayChaseInTheFirstOvers() {
        val game = EspnScoreboard.parse(sample("scoreboard", "n"), league("1550706"), now).single()
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "305–5/0", "Live"), listOf(game.status, game.score, SportsFixtureText.periodClock(game)))
        val innings = (game.sportDetail as SportsDetail.Cricket).innings
        assertEquals(listOf(CricketInnings("PAK", 305, 10, "49.5"), CricketInnings("SL", 5, 0, "1.5", 50, 306, true)), innings)
        val bug = SportsFixtureText.bug(game)
        assertEquals(listOf("PAK 305 · SL 5/0", "1.5 ov", "Need 301 from 289"), listOf(bug.primary, bug.state, bug.extra))
        val summary = EspnSummary.parse(sample("summary", "n"), league("1550706"))!!
        assertEquals(FixtureStatus.LIVE, summary.status)
        assertEquals(listOf(SummaryInnings(FixtureSide.HOME, 1, 305, 10, "49.5", null), SummaryInnings(FixtureSide.AWAY, 2, 5, 0, "1.5", 306)), summary.innings)
        assertTrue(summary.moments.isEmpty() && summary.lastPlays.isEmpty())
    }

    @Test fun testMatchFirstInnings() {
        val game = EspnScoreboard.parse(sample("scoreboard", "o"), league("24203"), now).single()
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, null, "Match delayed by rain"), listOf(game.status, game.score, SportsFixtureText.periodClock(game)))
        assertEquals(listOf(CricketInnings("SA", 20, 2, "12.2", null, null, true)), (game.sportDetail as SportsDetail.Cricket).innings)
        assertEquals("SA 20/2" to "12.2 ov", SportsFixtureText.bug(game).let { it.primary to it.state })
        assertEquals(listOf(SummaryInnings(FixtureSide.HOME, 1, 20, 2, "12.2", null)), EspnSummary.parse(sample("summary", "o"), league("24203"))!!.innings)
    }

    @Test fun twentyOverChaseWonWithBallsLeft() {
        val game = EspnScoreboard.parse(sample("scoreboard", "p"), league("24268"), now).single()
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, "165/8–162/2"), listOf(game.status, game.score))
        val detail = game.sportDetail as SportsDetail.Cricket
        assertEquals("AUS Women won by 2 wkts (7b rem)", detail.result)
        assertEquals(CricketInnings("AUS-W", 165, 8, "18.5", 20, 163, true), detail.innings.last())
    }
}
