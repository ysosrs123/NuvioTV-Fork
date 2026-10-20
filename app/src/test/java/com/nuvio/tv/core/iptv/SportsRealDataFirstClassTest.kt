package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataFirstClassTest {
    private val now = Instant.parse("2026-10-20T19:28:00Z").toEpochMilli()
    private fun games(suffix: String, series: String) = EspnScoreboard.parse(
        javaClass.getResourceAsStream("/sports/espn-cricket-scoreboard-real-20261021$suffix.json")!!.bufferedReader().use { it.readText() },
        SportsLeague("cricket-test", "Cricket", "cricket", "cricket/$series", null), now).filter { it.status == FixtureStatus.LIVE }.associateBy { it.title }

    @Test fun declaredInningsKeepsItsMark() {
        val test = games("", "24203").getValue("South Africa v Australia")
        val bug = SportsFixtureText.bug(test)
        assertEquals(listOf("SA 425/9d · AUS 3/1", "4.0 ov", "Stumps"), listOf(bug.primary, bug.state, bug.extra))
        assertEquals(listOf(true, false), (test.sportDetail as SportsDetail.Cricket).innings.map { it.declared })
        val entry = SportsCacheEntry(listOf(test), now)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun secondInningsShowsBothInningsOfTheSide() {
        val games = games("b", "8043")
        assertEquals("TAS 241 & 39/4 · VIC 207", SportsFixtureText.bug(games.getValue("Tasmania v Victoria")).primary)
        assertEquals("SOA 203 & 104/7 · QLD 238", SportsFixtureText.bug(games.getValue("South Australia v Queensland")).primary)
        assertEquals("NSW 440/6d · WA 245/7", SportsFixtureText.bug(games.getValue("Western Australia v New South Wales")).primary)
        assertEquals("Stumps", games.getValue("Tasmania v Victoria").detail)
    }
}
