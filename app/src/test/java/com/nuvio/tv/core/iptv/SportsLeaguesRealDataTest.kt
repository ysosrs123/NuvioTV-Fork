package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsLeaguesRealDataTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/espn-$name-real-20261010.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-10T00:30:00Z")

    @Test fun parsedEspnLeaguesAreOffered() {
        assertEquals("basketball/nbl", league("nbl").espn)
        assertEquals("football/college-football", league("college-football").espn)
        assertTrue(league("wnba").women)
        val ids = SportsLeagues.ALL.map { it.id }
        assertTrue(ids.indexOf("nbl") == ids.indexOf("nba") + 1)
        assertTrue(ids.indexOf("college-football") > ids.indexOf("nfl"))
        assertEquals(listOf("australian-football", "rugby-league", "soccer", "rugby", "cricket", "basketball", "american-football", "baseball", "ice-hockey",
            "motorsport", "mma", "tennis", "golf"), SportsLeagues.ALL.map { it.sport }.distinct())
        assertFalse(listOf("nbl", "wnba", "college-football").any { it in SportsLeagues.DEFAULTS })
        assertEquals("NBL", SportsGuideCells.badge("nbl"))
        assertEquals("CFB", SportsGuideCells.badge("college-football"))
        assertEquals("nbl", SportsGuideCells.league(listOf(LocalizedGuideText("NBL Basketball: Hawks v JackJumpers", null))))
        assertEquals("college-football", SportsGuideCells.league(listOf(LocalizedGuideText("College Football: Georgia at Alabama", null))))
        assertEquals("nba", SportsGuideCells.league(listOf(LocalizedGuideText("NBA Basketball: Lakers v Celtics", null))))
    }

    @Test fun nblScoreboardAndTenMinuteQuarters() {
        val game = EspnScoreboard.parse(sample("nbl-scoreboard"), league("nbl"), now).single()
        assertEquals("401875247", game.id)
        assertEquals("nbl", game.league)
        assertEquals("basketball", game.sport)
        assertEquals("Illawarra Hawks v Tasmania JackJumpers", game.title)
        assertEquals(FixtureStatus.FINAL, game.status)
        assertEquals("112–122", game.score)
        assertEquals(millis("2026-10-09T08:30:00Z"), game.startMillis)
        val summary = EspnSummary.parse(sample("nbl-summary"), league("nbl"))
        assertEquals(FixtureStatus.FINAL, summary.status)
        assertEquals(10, summary.moments.size)
        val first = summary.moments.first { it.clock == "9:56" }
        assertEquals(1, first.period)
        assertEquals(0.18, SportsMarkers.minutes("basketball", 1, "9:56", league = "nbl")!!, 0.01)
        assertEquals(126.91, SportsMarkers.minutes("basketball", 4, "0:02", league = "nbl")!!, 0.01)
        assertEquals(0.0, SportsMarkers.minutes("basketball", 1, "12:00", league = "nbl")!!, 0.001)
        assertEquals(5.60, SportsMarkers.minutes("basketball", 1, "9:56")!!, 0.01)
        assertEquals(147.97, SportsMarkers.minutes("basketball", 4, "45.2", league = "nba")!!, 0.01)
        val start = game.startMillis
        val markers = SportsMarkers.all(summary, start, league = "nbl")
        assertEquals(10, markers.size)
        assertTrue(markers.all { !it.second.exact && it.second.millis in start..start + 130 * 60_000L })
        assertTrue(SportsMarkers.all(summary, start).any { it.second.millis > start + 130 * 60_000L })
    }

    @Test fun collegeFootballScoreboard() {
        val games = EspnScoreboard.parse(sample("ncaaf-scoreboard"), league("college-football"), now)
        assertEquals(listOf("401858487", "401856826"), games.map { it.id })
        val next = games[0]
        assertEquals("american-football", next.sport)
        assertEquals("Washington Huskies v Iowa Hawkeyes", next.title)
        assertEquals(FixtureStatus.SCHEDULED, next.status)
        assertEquals(millis("2026-10-10T01:00:00Z"), next.startMillis)
        assertNull(next.score)
        val summary = EspnSummary.parse(sample("ncaaf-summary"), league("college-football"))
        assertEquals("american-football", summary.sport)
        assertTrue(summary.moments.isEmpty())
    }
}
