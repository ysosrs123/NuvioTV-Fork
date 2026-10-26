package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataCutTest {
    private val now = Instant.parse("2026-10-24T17:30:00Z").toEpochMilli()
    private fun event() = EspnScoreboard.parse(javaClass.getResourceAsStream("/sports/espn-pga-scoreboard-real-20261025.json")!!.bufferedReader().use { it.readText() },
        SportsLeagues.byId("pga")!!, now).single()

    @Test fun thirdRoundAfterTheCutRanksOnlyThePlayersStillIn() {
        val event = event()
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "Round 3 - In Progress", 3), listOf(event.status, event.detail, event.period))
        val golf = event.sportDetail as SportsDetail.Golf
        assertEquals(listOf("T1", "T1", "T3", "T3", "T3", "T6", "T6", "T6", "T9", "T9"), golf.leaders.map { it.position })
        assertEquals(listOf("11", "9", "12", "10", "9", "F", "14", "10", "17", "11"), golf.leaders.map { it.thru })
        assertEquals(listOf<Any?>("Austin Smotherman", "-15", "-4"), golf.leaders.first().let { listOf(it.name, it.toPar, it.today) })
        assertEquals("Tom Hoge" to "-6", golf.leaders[5].let { it.name to it.today })
        val entry = SportsCacheEntry(listOf(event), now)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun suspendedRoundKeepsTheEventLive() {
        val event = EspnScoreboard.parse(javaClass.getResourceAsStream("/sports/espn-pga-scoreboard-real-20261026.json")!!.bufferedReader().use { it.readText() },
            SportsLeagues.byId("pga")!!, Instant.parse("2026-10-25T23:30:00Z").toEpochMilli()).single()
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "Round 3 - Suspended", 3), listOf(event.status, event.detail, event.period))
        val leaders = (event.sportDetail as SportsDetail.Golf).leaders
        assertEquals(listOf("T1", "T1", "T3", "T3", "T3", "T6", "T6", "T6", "T6", "T6"), leaders.map { it.position })
        assertTrue(leaders.all { it.thru == "F" })
    }

    @Test fun playoffHolesAreNotReadAsAFourthRound() {
        val event = EspnScoreboard.parse(javaClass.getResourceAsStream("/sports/espn-pga-scoreboard-real-20261027.json")!!.bufferedReader().use { it.readText() },
            SportsLeagues.byId("pga")!!, Instant.parse("2026-10-26T23:30:00Z").toEpochMilli()).single()
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, "Final", 4), listOf(event.status, event.detail, (event.sportDetail as SportsDetail.Golf).round))
        val leaders = (event.sportDetail as SportsDetail.Golf).leaders
        assertEquals(listOf("Austin Smotherman" to "-16", "Séamus Power" to "-15", "Taylor Moore" to "-14"), leaders.take(3).map { it.name to it.toPar })
        assertEquals(listOf("1", "2", "T3", "T3", "T3"), leaders.take(5).map { it.position })
        assertTrue(leaders.take(2).all { it.thru == null && it.today == null })
        assertNull(SportsFixtureText.bug(event).extra)
    }

    @Test fun leaderboardOutOfOrderIsRankedByScore() {
        val event = EspnScoreboard.parse(javaClass.getResourceAsStream("/sports/espn-lpga-scoreboard-real-20261025.json")!!.bufferedReader().use { it.readText() },
            SportsLeagues.byId("lpga")!!, Instant.parse("2026-10-25T03:30:00Z").toEpochMilli()).single()
        val leaders = (event.sportDetail as SportsDetail.Golf).leaders
        assertEquals(listOf("Haeran Ryu", "Yuri Yoshida", "Hye-Jin Choi", "Minji Park"), leaders.take(4).map { it.name })
        assertEquals(listOf("1", "T2", "T2", "T2", "T5", "T5", "T5", "T5", "T9", "T9"), leaders.map { it.position })
        assertEquals(listOf("-17", "-13", "-13", "-13", "-11", "-11", "-11", "-11", "-10", "-10"), leaders.map { it.toPar })
    }
}
