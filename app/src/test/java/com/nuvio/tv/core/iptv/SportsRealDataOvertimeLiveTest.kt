package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataOvertimeLiveTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/espn-nba-$name-real-20261029.json")!!.bufferedReader().use { it.readText() }
    private val nba = SportsLeagues.byId("nba")!!
    private val now = Instant.parse("2026-10-29T03:30:00Z").toEpochMilli()

    @Test fun overtimeInPlayShowsTheOvertimeClock() {
        val game = EspnScoreboard.parse(sample("scoreboard"), nba, now).single()
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "105–105", "4:33 - OT", 5, "4:33"), listOf(game.status, game.score, game.detail, game.period, game.clock))
        assertEquals(listOf("32", "23", "30", "20", "0") to listOf("22", "29", "34", "20", "0"), game.homeLine?.periods to game.awayLine?.periods)
        assertEquals("OT", SportsFixtureText.periodLabel(game, 5))
        assertEquals(SportsBugText("SA 105–105 UTAH", "OT · 4:33", null), SportsFixtureText.bug(game))
        assertEquals("De'Aaron Fox out of bounds lost ball turnover" to 53, game.situation?.let { it.lastPlay to it.homeWinPercent })
        val entry = SportsCacheEntry(listOf(game), now)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun overtimeSummaryHasTheFifthPeriod() {
        val summary = EspnSummary.parse(sample("summary"), nba)!!
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, 5, "4:33", "4:33 - OT"), listOf(summary.status, summary.period, summary.clock, summary.detail))
        assertEquals(listOf("0", "0"), listOf(summary.homeLine?.periods?.last(), summary.awayLine?.periods?.last()))
        assertEquals(listOf("De'Aaron Fox out of bounds lost ball turnover" to "4:46", "Victor Wembanyama vs. Jaxson Hayes (De'Aaron Fox gains possession)" to "5:00"),
            summary.lastPlays.take(2).map { it.text to it.clock })
        assertTrue(summary.lastPlays.all { it.period == 5 })
    }
}
