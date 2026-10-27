package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataOvertimeFinalTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/espn-nba-$name-real-20261027.json")!!.bufferedReader().use { it.readText() }
    private val nba = SportsLeagues.byId("nba")!!

    @Test fun overtimeFinalHasAnOvertimeColumn() {
        val game = EspnScoreboard.parse(sample("scoreboard"), nba, Instant.parse("2026-10-27T03:30:00Z").toEpochMilli()).single()
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, "127–125", "Final/OT"), listOf(game.status, game.score, game.detail))
        assertEquals(listOf("24", "26", "36", "27", "14") to listOf("31", "29", "26", "27", "12"), game.homeLine?.periods to game.awayLine?.periods)
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4", "OT"), (1..5).map { SportsFixtureText.periodLabel(game, it) })
        assertEquals(SportsBugText("MIN 125–127 CLE", "Final/OT", null), SportsFixtureText.bug(game))
    }

    @Test fun overtimeSummaryEndsWithTheGame() {
        val summary = EspnSummary.parse(sample("summary"), nba)!!
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, "Final/OT"), listOf(summary.status, summary.detail))
        assertEquals("127" to "125", summary.homeLine?.score to summary.awayLine?.score)
        assertEquals(listOf("14", "12"), listOf(summary.homeLine?.periods?.last(), summary.awayLine?.periods?.last()))
        assertEquals(listOf("End of Game", "End of the 1st Overtime", "Anthony Edwards makes 28-foot three pointer"), summary.lastPlays.map { it.text })
        assertTrue(summary.moments.any { it.period == 5 })
    }
}
