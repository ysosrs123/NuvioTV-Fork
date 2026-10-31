package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataFightNightTest {
    private fun bouts(suffix: String, time: String): List<FightBout> {
        val card = EspnScoreboard.parse(javaClass.getResourceAsStream("/sports/espn-ufc-scoreboard-real-20261101$suffix.json")!!.bufferedReader().use { it.readText() },
            SportsLeagues.byId("ufc")!!, Instant.parse(time).toEpochMilli()).single()
        return (card.sportDetail as SportsDetail.Card).bouts
    }

    @Test fun finishWithoutAnEndClockYetLeavesTheTimeOut() {
        val stopped = bouts("", "2026-10-31T23:31:11Z")[5]
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, 1, FightMethod.KO, 2, null), listOf(stopped.state, stopped.winner, stopped.method, stopped.endRound, stopped.endClock))
        assertEquals("R. Sezinando · KO/TKO · R2", SportsFixtureText.finish(stopped))
        assertEquals("R. Sezinando · KO/TKO · R2 5:00", SportsFixtureText.finish(bouts("b", "2026-10-31T23:33:28Z")[5]))
    }

    @Test fun firstUnofficialWinnerDetailNamesTheMethod() {
        val bouts = bouts("b", "2026-10-31T23:33:28Z")
        assertEquals("L. Szabová · KO/TKO · R3 3:20", SportsFixtureText.finish(bouts[0]))
        assertEquals(listOf(FightMethod.KO, FightMethod.DECISION, FightMethod.SUBMISSION, FightMethod.DECISION, FightMethod.KO, FightMethod.KO), bouts.take(6).map { it.method })
        assertEquals(FixtureStatus.LIVE, bouts[6].state)
    }
}
