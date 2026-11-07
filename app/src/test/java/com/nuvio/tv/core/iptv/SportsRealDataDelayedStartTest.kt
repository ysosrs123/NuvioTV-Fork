package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataDelayedStartTest {
    private fun sample(kind: String): String = javaClass.getResourceAsStream("/sports/espn-aleague-$kind-real-20261107.json")!!.bufferedReader().use { it.readText() }
    private val league = SportsLeagues.byId("a-league-men")!!

    @Test fun delayedKickOffStaysListed() {
        val game = EspnScoreboard.parse(sample("scoreboard"), league, Instant.parse("2026-11-07T04:30:00Z").toEpochMilli()).single()
        assertEquals(listOf<Any?>("401892143", FixtureStatus.LIVE, "Delay"), listOf(game.id, game.status, game.detail))
        assertEquals("Delay", SportsFixtureText.bug(game).state)
        assertEquals("Delay", SportsFixtureText.periodClock(game))
    }

    @Test fun delayedSummaryReadsTheSame() {
        val summary = EspnSummary.parse(sample("summary"), league)!!
        assertEquals(FixtureStatus.LIVE to "Delay", summary.status to summary.detail)
    }
}
