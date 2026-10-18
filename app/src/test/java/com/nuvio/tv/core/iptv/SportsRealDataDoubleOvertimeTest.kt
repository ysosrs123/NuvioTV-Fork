package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataDoubleOvertimeTest {
    private fun sample(name: String, day: String): String =
        javaClass.getResourceAsStream("/sports/$name-real-$day.json")!!.bufferedReader().use { it.readText() }
    private val now = Instant.parse("2026-10-18T03:17:00Z").toEpochMilli()

    @Test fun collegeDoubleOvertimeFinal() {
        val miami = EspnScoreboard.parse(sample("espn-ncaaf-scoreboard", "20261018l"), SportsLeagues.byId("college-football")!!, now).single()
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, "43–35", "Final/2OT"), listOf(miami.status, miami.score, miami.detail))
        assertEquals(FixtureLine("43", listOf("7", "7", "0", "14", "7", "8")), miami.homeLine)
        assertEquals(FixtureLine("35", listOf("0", "14", "7", "7", "7", "0")), miami.awayLine)
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4", "OT", "2OT"), (1..6).map { SportsFixtureText.periodLabel(miami, it) })
    }

    @Test fun finishedCardKeepsFinishesWithoutAMethod() {
        val card = EspnScoreboard.parse(sample("espn-ufc-scoreboard", "20261018m"), SportsLeagues.byId("ufc")!!, now).single()
        assertEquals(FixtureStatus.FINAL, card.status)
        val bouts = (card.sportDetail as SportsDetail.Card).bouts
        assertTrue(bouts.all { it.state == FixtureStatus.FINAL && it.winner != null && it.clock == null })
        assertEquals(listOf(2, 7, 8), bouts.withIndex().filter { it.value.method == null }.map { it.index })
        assertEquals("M. Malott · KO/TKO · R2 2:32", SportsFixtureText.finish(bouts.last()))
        assertEquals("J. Jasudavicius · Decision", SportsFixtureText.finish(bouts[11]))
    }
}
