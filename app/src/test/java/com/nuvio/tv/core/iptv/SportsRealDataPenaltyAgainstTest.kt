package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataPenaltyAgainstTest {
    @Test fun penaltyWrittenAgainstThePlayerNamesHimAndTheInfraction() {
        val game = EspnScoreboard.parse(javaClass.getResourceAsStream("/sports/espn-nhl-scoreboard-real-20261021d.json")!!.bufferedReader().use { it.readText() },
            SportsLeagues.byId("nhl")!!, Instant.parse("2026-10-21T01:28:00Z").toEpochMilli()).single()
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "P3 · 8:28"), listOf(game.status, SportsFixtureText.periodClock(game)))
        assertEquals("Luca Del Bel Belluz Closing Hand on the Puck", game.situation?.lastPlay)
        assertEquals("Chris Kreider Closing Hand on the Puck", sportsPenaltyPlay("Chris Kreider Minor", "Minor", "Closing Hand on the Puck"))
        assertEquals("Josh Manson Cross-checking against Timo Meier", sportsPenaltyPlay("Josh Manson Cross-checking against Timo Meier", "Minor", "Cross checking"))
    }
}
