package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataNamedFreeThrowTest {
    @Test fun scoreboardFreeThrowWithThePlayer() {
        val game = EspnScoreboard.parse(javaClass.getResourceAsStream("/sports/espn-nba-scoreboard-real-20261021c.json")!!.bufferedReader().use { it.readText() },
            SportsLeagues.byId("nba")!!, Instant.parse("2026-10-20T20:58:00Z").toEpochMilli()).single()
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "Q4 · 11:46"), listOf(game.status, SportsFixtureText.periodClock(game)))
        assertEquals("Paul George misses free throw 1 of 1", game.situation?.lastPlay)
    }
}
