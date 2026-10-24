package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataLiveShootoutTest {
    private fun sample(kind: String): String = javaClass.getResourceAsStream("/sports/espn-sco-challenge-$kind-real-20261025.json")!!.bufferedReader().use { it.readText() }
    private val league = SportsLeague("cup-test", "Cup", "soccer", "soccer/sco.tennents", null)
    private val now = Instant.parse("2026-10-24T17:30:00Z").toEpochMilli()

    @Test fun liveShootoutShowsTheKicksInsteadOfTheLastMinute() {
        val game = EspnScoreboard.parse(sample("scoreboard"), league, now).single()
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "2–2", "Pens · 6–5", null, 5), listOf(game.status, game.score, game.detail, game.clock, game.period))
        assertEquals("BRC 2–2 STT" to "Pens · 6–5", SportsFixtureText.bug(game).let { it.primary to it.state })
        assertEquals("Pens · 6–5", SportsFixtureText.periodClock(game))
        assertEquals(listOf("2'", "8'", "31'", "64'"), game.events.filter { it.kind == FixtureEventKind.GOAL }.map { it.clock })
        val entry = SportsCacheEntry(listOf(game), now)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun summaryLeavesTheKicksOutOfTheMoments() {
        val summary = EspnSummary.parse(sample("summary"), league)!!
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, 5, "AET-pens", "2", "2"), listOf(summary.status, summary.period, summary.detail, summary.homeLine?.score, summary.awayLine?.score))
        val goals = summary.moments.filter { it.kind == MomentKind.GOAL }
        assertEquals(listOf("2'", "8'", "31'", "64'"), goals.map { it.clock })
        assertEquals(2 to 2, goals.last().let { it.homeScore to it.awayScore })
    }
}
