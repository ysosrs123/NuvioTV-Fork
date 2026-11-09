package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataHockeyOvertimeTest {
    private fun game(suffix: String): SportsFixture {
        val json = javaClass.getResourceAsStream("/sports/espn-nhl-scoreboard-real-20261109$suffix.json")!!.bufferedReader().use { it.readText() }
        return EspnScoreboard.parse(json, SportsLeagues.byId("nhl")!!, Instant.parse("2026-11-09T02:50:00Z").toEpochMilli()).single()
    }

    @Test fun overtimeInProgressShowsTheFourthPeriodAsOt() {
        val game = game("")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "3–3", 4, "4:03"), listOf(game.status, game.score, game.period, game.clock))
        assertEquals("OT · 4:03", SportsFixtureText.bug(game).state)
        assertEquals(listOf("P1", "P2", "P3", "OT"), (1..4).map { SportsFixtureText.periodLabel(game, it) })
    }

    @Test fun goalOnTheOvertimeBuzzerReadsAsEndOfOtUntilTheFinal() {
        val game = game("b")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "4–3", "End of OT"), listOf(game.status, game.score, SportsFixtureText.bug(game).state))
        assertEquals("End of OT", SportsFixtureText.periodClock(game))
    }
}
