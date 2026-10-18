package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataHalfTimeDownTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/$name-real-20261018d.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private val now = Instant.parse("2026-10-18T01:27:00Z").toEpochMilli()

    @Test fun halfTimeDropsTheLastDownAndPossession() {
        val games = EspnScoreboard.parse(sample("espn-ncaaf-scoreboard"), league("college-football"), now).associateBy { it.id }
        val byu = games.getValue("401856809")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "21–10", "Halftime"), listOf(byu.status, byu.score, SportsFixtureText.periodClock(byu)))
        assertEquals(FixtureSituation(null, null, "End of 2nd quarter.", byu.situation?.homeWinPercent, false, null, null), byu.situation)
        assertEquals("ND 10–21 BYU" to "Halftime", SportsFixtureText.bug(byu).let { it.primary to it.state })
        assertNull(SportsFixtureText.bug(byu).extra)
        val houston = games.getValue("401856831")
        assertEquals(listOf<Any?>(null, null, null), listOf(houston.situation?.downDistance, houston.situation?.possession, SportsFixtureText.bug(houston).extra))
    }

    @Test fun downIsKeptOnceTheSecondHalfStarts() {
        val root = JSONObject(sample("espn-ncaaf-scoreboard"))
        val event = root.getJSONArray("events").getJSONObject(0)
        val status = event.getJSONArray("competitions").getJSONObject(0).getJSONObject("status")
        status.put("period", 3).put("displayClock", "15:00").getJSONObject("type").put("name", "STATUS_IN_PROGRESS").put("shortDetail", "15:00 - 3rd")
        val byu = EspnScoreboard.parse(root.toString(), league("college-football"), now).first { it.id == "401856809" }
        assertEquals("2nd & 10 · BYU 5", byu.situation?.downDistance)
        assertEquals(FixtureSide.HOME, byu.situation?.possession)
    }

    @Test fun finishMethodsStillMissingAnHourLater() {
        val card = EspnScoreboard.parse(sample("espn-ufc-scoreboard"), league("ufc"), now).single()
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "Walkouts"), listOf(card.status, card.detail))
        val bouts = (card.sportDetail as SportsDetail.Card).bouts
        assertEquals(List(10) { FixtureStatus.FINAL } + FixtureStatus.LIVE + List(2) { FixtureStatus.SCHEDULED }, bouts.map { it.state })
        assertEquals(listOf(2, 7, 8), bouts.withIndex().filter { it.value.state == FixtureStatus.FINAL && it.value.method == null }.map { it.index })
        assertTrue(bouts.take(10).all { it.winner != null && it.endRound != null && it.endClock != null })
        assertEquals(listOf<Any?>(2, "2:48"), listOf(bouts[8].endRound, bouts[8].endClock))
    }
}
