package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataMainCardTest {
    private fun sample(name: String, day: String = "20261018c"): String =
        javaClass.getResourceAsStream("/sports/$name-real-$day.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-18T00:28:00Z")
    private fun games(name: String, league: String) = EspnScoreboard.parse(sample("espn-$name-scoreboard"), league(league), now).associateBy { it.id }

    @Test fun timeoutWithoutADownIsNotRedZone() {
        val miami = games("ncaaf", "college-football").getValue("401858263")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "14–7", "Q2 · 2:00"), listOf(miami.status, miami.score, SportsFixtureText.periodClock(miami)))
        val situation = miami.situation!!
        assertFalse(situation.redZone)
        assertEquals(listOf<Any?>(null, null, "Timeout Florida State, clock 02:00", 92, 1, 2), listOf(situation.downDistance, situation.possession, situation.lastPlay,
            situation.homeWinPercent, situation.homeTimeouts, situation.awayTimeouts))
        assertFalse(EspnScoreboard.parse(sample("espn-ncaaf-scoreboard", "20261011b"), league("college-football"), millis("2026-10-11T00:00:00Z")).any { it.situation?.redZone == true && it.situation?.downDistance == null })
    }

    @Test fun mainCardBetweenBouts() {
        val card = games("ufc", "ufc").values.single()
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "Pre-fight"), listOf(card.status, card.detail))
        val bouts = (card.sportDetail as SportsDetail.Card).bouts
        assertEquals(List(8) { FixtureStatus.FINAL } + FixtureStatus.LIVE + List(4) { FixtureStatus.SCHEDULED }, bouts.map { it.state })
        assertEquals(listOf(FightMethod.SUBMISSION, FightMethod.SUBMISSION, null, FightMethod.KO), bouts.take(4).map { it.method })
        val koslow = bouts[2]
        assertEquals(listOf<Any?>(2, 3, "4:31", null), listOf(koslow.winner, koslow.endRound, koslow.endClock, koslow.round))
        val nallo = bouts[7]
        assertEquals(listOf<Any?>(1, null, 2, "0:47", true), listOf(nallo.winner, nallo.method, nallo.endRound, nallo.endClock, nallo.mainCard))
        assertTrue(bouts.drop(8).all { it.winner == null && it.method == null && it.round == null })
        assertEquals("Buckley" to "Malott", bouts.last().first.name.substringAfter(' ') to bouts.last().second.name.substringAfter(' '))
    }

    @Test fun collegeHockeyIntervalsAndOvertime() {
        val games = games("college-hockey", "college-hockey")
        val alaska = games.getValue("401904626")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "2–2", 2, "0:00", "End of 2nd"), listOf(alaska.status, alaska.score, alaska.period, alaska.clock, SportsFixtureText.periodClock(alaska)))
        assertEquals("End of 2nd", SportsFixtureText.periodClock(games.getValue("401904705")))
        val lowell = games.getValue("401904842")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "2–4", "End of 3rd"), listOf(lowell.status, lowell.score, SportsFixtureText.periodClock(lowell)))
        val northernMichigan = games.getValue("401904672")
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, "1–2", "Final/OT"), listOf(northernMichigan.status, northernMichigan.score, northernMichigan.detail))
    }

    @Test fun livePostseasonGameOnBothSources() {
        val cleveland = games("mlb", "mlb").getValue("401908027")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "1–0", "Top 2nd"), listOf(cleveland.status, cleveland.score, cleveland.detail))
        assertEquals(SportsDetail.Baseball(2, InningHalf.TOP, 0, 0, 0, false, false, false), cleveland.sportDetail)
        assertEquals("Foster Griffin pitches to Liam Hicks", cleveland.situation?.lastPlay)
        val day = SportsDbEvents.parse(sample("tsdb-eventsday-mlb"), league("mlb"), now)
        val live = day.first()
        assertEquals(listOf<Any?>("2616984", FixtureStatus.LIVE, "0–0", "Inning 1", 1, millis("2026-10-18T00:00:00Z")),
            listOf(live.id, live.status, live.score, live.detail, live.period, live.startMillis))
        assertEquals(FixtureStatus.SCHEDULED, day[1].status)
        val scores = SportsDbLive.parse(sample("tsdb-livescore-baseball")).associateBy { it.eventId }
        assertNull(scores["2616984"])
        assertEquals("NS", scores.getValue("2618466").status)
        assertEquals(day, SportsDbLive.merge(day, scores, now))
    }
}
