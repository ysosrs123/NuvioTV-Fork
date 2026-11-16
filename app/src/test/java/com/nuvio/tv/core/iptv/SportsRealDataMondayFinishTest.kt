package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataMondayFinishTest {
    private val now = Instant.parse("2026-11-16T02:30:00Z").toEpochMilli()

    @Test fun finalRoundSuspendedBeforeAnyoneStartsShowsTheMondayTeeTimes() {
        val event = EspnScoreboard.parse(javaClass.getResourceAsStream("/sports/espn-pga-scoreboard-real-20261116.json")!!.bufferedReader().use { it.readText() },
            SportsLeagues.byId("pga")!!, now).single()
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "Round 4 - Suspended"), listOf(event.status, event.detail))
        val golf = event.sportDetail as SportsDetail.Golf
        assertEquals(4, golf.round)
        assertEquals(listOf("1", "T2", "T2", "T2", "T5", "T5"), golf.leaders.map { it.position })
        assertTrue(golf.leaders.all { it.thru == null && it.today == null })
        assertEquals(Instant.parse("2026-11-16T15:31:00Z").toEpochMilli(), golf.leaders.first().teeMillis)
        assertEquals(listOf<Any?>("T. Crowe -14", "Round 4 - Suspended", null), SportsFixtureText.bug(event).let { listOf(it.primary, it.state, it.extra) })
    }
}
