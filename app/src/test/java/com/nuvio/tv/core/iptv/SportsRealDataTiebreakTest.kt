package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataTiebreakTest {
    private val now = Instant.parse("2026-10-20T11:28:00Z").toEpochMilli()
    private fun matches() = EspnScoreboard.parse(javaClass.getResourceAsStream("/sports/espn-atp-scoreboard-real-20261020.json")!!.bufferedReader().use { it.readText() },
        SportsLeagues.byId("atp")!!, now).associateBy { it.id }

    @Test fun liveTiebreakShowsItsPoints() {
        val doubles = matches().getValue("185126")
        val sets = (doubles.sportDetail as SportsDetail.Tennis).sets
        assertEquals(TennisSet(6, 6, 2, 2), sets.last())
        val bug = SportsFixtureText.bug(doubles)
        assertEquals(listOf("NYS/ROGER-VASSELIN 6 6 / REYNOLDS/WATT 3 6", "2nd Set", "Tie-break 2–2"), listOf(bug.primary, bug.state, bug.extra))
        val entry = SportsCacheEntry(listOf(doubles), now)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun ordinaryGamesKeepTheServer() {
        val singles = matches().getValue("185092")
        assertEquals("BAEZ serving", SportsFixtureText.bug(singles).extra)
        val tiebreak = singles.copy(sportDetail = (singles.sportDetail as SportsDetail.Tennis).let { it.copy(sets = it.sets.dropLast(1) + TennisSet(6, 6, 5, 4)) })
        assertEquals("Tie-break 5–4 · BAEZ serving", SportsFixtureText.bug(tiebreak).extra)
    }
}
