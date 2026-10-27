package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataExtraTimeTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/espn-copa-del-rey-$name-real-20261028.json")!!.bufferedReader().use { it.readText() }
    private val cup = SportsLeagues.byId("copa-del-rey")!!

    @Test fun extraTimeInPlayShowsTheMinuteAndPeriod() {
        val game = EspnScoreboard.parse(sample("scoreboard"), cup, Instant.parse("2026-10-27T20:30:00Z").toEpochMilli()).single()
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "3–3", 4, "120'+1'", "120'+1'"), listOf(game.status, game.score, game.period, game.clock, game.detail))
        assertEquals("SES 3–3 ESP" to "120'+1'", SportsFixtureText.bug(game).let { it.primary to it.state })
        assertEquals("ET · 120'+1'", SportsFixtureText.periodClock(game))
        assertEquals(listOf("6'", "18'", "40'", "90'", "96'", "98'"), game.events.filter { it.kind == FixtureEventKind.GOAL }.map { it.clock })
        val entry = SportsCacheEntry(listOf(game), Instant.parse("2026-10-27T20:30:00Z").toEpochMilli())
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun summaryPutsExtraTimeGoalsInTheirOwnPeriod() {
        val summary = EspnSummary.parse(sample("summary"), cup)!!
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, 4, "120'+1'"), listOf(summary.status, summary.period, summary.clock))
        assertEquals(listOf("2", "0", "1", "0") to listOf("1", "1", "1", "0"), summary.homeLine?.periods to summary.awayLine?.periods)
        assertEquals(listOf("1:6':0-1", "1:18':1-1", "1:40':2-1", "2:90':2-2", "3:96':2-3", "3:98':3-3"),
            summary.moments.filter { it.kind == MomentKind.GOAL }.map { "${it.period}:${it.clock}:${it.homeScore}-${it.awayScore}" })
    }
}
