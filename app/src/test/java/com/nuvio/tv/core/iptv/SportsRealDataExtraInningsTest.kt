package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataExtraInningsTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/$name-real-20261028.json")!!.bufferedReader().use { it.readText() }
    private val mlb = SportsLeagues.byId("mlb")!!
    private val now = Instant.parse("2026-10-28T03:30:00Z").toEpochMilli()

    @Test fun extraInningsOnEspnAddATenthColumn() {
        val game = EspnScoreboard.parse(sample("espn-mlb-scoreboard"), mlb, now).single()
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "2–2", "End 10th", 10), listOf(game.status, game.score, game.detail, game.period))
        assertEquals(listOf("0", "1", "0", "1", "0", "0", "0", "0", "0", "0") to listOf("0", "0", "0", "1", "1", "0", "0", "0", "0", "0"),
            game.homeLine?.periods to game.awayLine?.periods)
        assertEquals(SportsDetail.Baseball(10, InningHalf.END, null, null, null, false, false, false), game.sportDetail)
        assertEquals(SportsBugText("MIL 2–2 TB", "End 10"), SportsFixtureText.bug(game))
        assertEquals("End of the 10th inning", game.situation?.lastPlay)
    }

    @Test fun sportsDbStopsAtTheNinthAndFinishesTheGameEarly() {
        val day = SportsDbEvents.parse(sample("tsdb-eventsday-mlb"), mlb, now)
        assertEquals(listOf<Any?>("2619530", FixtureStatus.FINAL, "2–2"), day.single().let { listOf(it.id, it.status, it.score) })
        val live = SportsDbLive.parse(sample("tsdb-livescore-baseball")).single()
        assertEquals(listOf<Any?>("2619529", "IN9", "2", "2"), listOf(live.eventId, live.status, live.homeScore, live.awayScore))
        assertEquals(day, SportsDbLive.merge(day, mapOf(live.eventId to live), now))
    }
}
