package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataWomensCollegeTest {
    private fun sample(kind: String): String = javaClass.getResourceAsStream("/sports/espn-wcbb-$kind-real-20261103.json")!!.bufferedReader().use { it.readText() }
    private val league = SportsLeagues.byId("womens-college-basketball")!!

    @Test fun quartersBreaksAndTheLastSecond() {
        val games = EspnScoreboard.parse(sample("scoreboard"), league, Instant.parse("2026-11-03T00:30:00Z").toEpochMilli()).associateBy { it.id }
        assertEquals(5, games.size)
        assertEquals(listOf("End of 1st", "Halftime", "Q4 · 9:16", "Halftime", "Q3 · 0:01"),
            listOf("401912964", "401927955", "401913855", "401927081", "401907046").map { SportsFixtureText.periodClock(games.getValue(it)) })
        val minnesota = games.getValue("401913855")
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4"), (1..4).map { SportsFixtureText.periodLabel(minnesota, it) })
        assertEquals(SportsBugText("HARV 52–56 MINN", "Q4 · 9:16", null), SportsFixtureText.bug(minnesota))
        assertEquals("Kensley Feltner subbing in for Morehead State", games.getValue("401927955").situation?.lastPlay)
    }

    @Test fun liveSummaryInTheFourth() {
        val summary = EspnSummary.parse(sample("summary"), league)!!
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, 4, "9:16"), listOf(summary.status, summary.period, summary.clock))
        assertEquals("Grace Grocholski makes 20-foot jumper (Natalie Kussow assists)" to "9:22", summary.lastPlays.first().let { it.text to it.clock })
        assertEquals(listOf("17", "20", "17", "2") to listOf("14", "16", "22", "0"), summary.homeLine?.periods to summary.awayLine?.periods)
    }
}
