package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataShootoutSummaryTest {
    private fun sample(kind: String): String = javaClass.getResourceAsStream("/sports/espn-nhl-$kind-real-20261103.json")!!.bufferedReader().use { it.readText() }
    private val nhl = SportsLeagues.byId("nhl")!!

    @Test fun shootoutWinnerIsNotAGoalAtOneNil() {
        val summary = EspnSummary.parse(sample("summary"), nhl)!!
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, "Final/SO"), listOf(summary.status, summary.detail))
        assertEquals(listOf("1", "1", "0", "0", "1") to listOf("0", "1", "1", "0", "0"), summary.homeLine?.periods to summary.awayLine?.periods)
        val goals = summary.moments.filter { it.kind == MomentKind.GOAL }
        assertTrue(goals.none { it.period == 5 })
        assertEquals(listOf<Any?>(3, "7:23", 2, 2), goals.last().let { listOf(it.period, it.clock, it.homeScore, it.awayScore) })
        assertEquals(listOf("Game End", "End of SO", "End of Shootout"), summary.lastPlays.map { it.text })
    }

    @Test fun shootoutFinalLabelsTheLastColumn() {
        val game = EspnScoreboard.parse(sample("scoreboard"), nhl, Instant.parse("2026-11-03T04:30:00Z").toEpochMilli()).single()
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, "3–2", "Final/SO"), listOf(game.status, game.score, game.detail))
        assertEquals(listOf("P1", "P2", "P3", "OT", "SO"), (1..5).map { SportsFixtureText.periodLabel(game, it) })
    }
}
