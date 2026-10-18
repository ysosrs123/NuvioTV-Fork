package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataNewSportsTest {
    private fun games(name: String, sport: String, path: String) =
        EspnScoreboard.parse(javaClass.getResourceAsStream("/sports/espn-$name-scoreboard-real-20261019.json")!!.bufferedReader().use { it.readText() },
            SportsLeague("$name-test", "Test", sport, path, null), Instant.parse("2026-10-18T18:28:00Z").toEpochMilli()).associateBy { it.id }

    @Test fun collegeVolleyballReadsSetsAndPoints() {
        val louisville = games("college-volleyball", "volleyball", "volleyball/womens-college-volleyball").getValue("401883955")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "2–0", "3rd Set", 3), listOf(louisville.status, louisville.score, SportsFixtureText.periodClock(louisville), louisville.period))
        assertEquals(FixtureLine("2", listOf("29", "25", "10")), louisville.homeLine)
        assertEquals(FixtureLine("0", listOf("27", "22", "7")), louisville.awayLine)
    }

    @Test fun collegeFieldHockeyReadsQuartersAndARisingClock() {
        val games = games("college-field-hockey", "field-hockey", "field-hockey/womens-college-field-hockey")
        val wagner = games.getValue("401897324")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "1–0", 4, "55:54", "55:54 - 4th"), listOf(wagner.status, wagner.score, wagner.period, wagner.clock, SportsFixtureText.periodClock(wagner)))
        assertEquals(FixtureLine("1", listOf("0", "0", "1", "0")), wagner.homeLine)
        assertEquals("End of 3rd", SportsFixtureText.periodClock(games.getValue("401896893")))
    }
}
