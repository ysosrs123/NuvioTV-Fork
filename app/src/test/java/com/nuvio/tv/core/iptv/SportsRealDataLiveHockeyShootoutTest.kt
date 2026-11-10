package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataLiveHockeyShootoutTest {
    private fun game(suffix: String): SportsFixture {
        val json = javaClass.getResourceAsStream("/sports/espn-nhl-scoreboard-real-20261110$suffix.json")!!.bufferedReader().use { it.readText() }
        return EspnScoreboard.parse(json, SportsLeagues.byId("nhl")!!, Instant.parse("2026-11-10T05:55:00Z").toEpochMilli()).single()
    }

    @Test fun shootoutInProgressIsLabelledSoNotSecondOvertime() {
        val game = game("")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "2–2", 5, "In SO"), listOf(game.status, game.score, game.period, game.detail))
        assertEquals("In SO", SportsFixtureText.bug(game).state)
        assertEquals(listOf("P1", "P2", "P3", "OT", "SO"), (1..5).map { SportsFixtureText.periodLabel(game, it) })
    }

    @Test fun shootoutFinalCarriesTheExtraGoalOnlyInTheScore() {
        val game = game("b")
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, "3–2", "Final/SO"), listOf(game.status, game.score, game.detail))
        assertEquals(listOf("1", "1", "0", "0", "0") to listOf("1", "1", "0", "0", "0"), game.homeLine?.periods to game.awayLine?.periods)
        assertEquals("SO", SportsFixtureText.periodLabel(game, 5))
    }

    @Test fun laterHockeyOvertimesKeepTheirNumbers() {
        val game = game("").copy(detail = "5:00 - 2OT")
        assertEquals("2OT", SportsFixtureText.periodLabel(game, 5))
    }
}
