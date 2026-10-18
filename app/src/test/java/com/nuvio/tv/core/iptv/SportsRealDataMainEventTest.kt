package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataMainEventTest {
    private fun card(suffix: String, time: String): SportsFixture =
        EspnScoreboard.parse(javaClass.getResourceAsStream("/sports/espn-ufc-scoreboard-real-20261018$suffix.json")!!.bufferedReader().use { it.readText() },
            SportsLeagues.byId("ufc")!!, Instant.parse(time).toEpochMilli()).single()
    private fun bout(card: SportsFixture) = (card.sportDetail as SportsDetail.Card).bouts.last()
    private fun bug(card: SportsFixture) = SportsFixtureText.bug(card).let { it.primary to it.state }

    @Test fun roundInProgressShowsTheClockLeft() {
        val first = card("e", "2026-10-18T02:33:12Z")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "R1, 3:03"), listOf(first.status, first.detail))
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, 1, "3:03", null, null), bout(first).let { listOf(it.state, it.round, it.clock, it.endRound, it.endClock) })
        assertEquals("J. Buckley v M. Malott" to "Round 1 of 5 · 3:03", bug(first))
        assertEquals("Round 2 of 5 · 3:03", bug(card("h", "2026-10-18T02:38:59Z")).second)
        val entry = SportsCacheEntry(listOf(first), Instant.parse("2026-10-18T02:33:12Z").toEpochMilli())
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun roundEndAndBreakLeaveTheClockOut() {
        val bell = card("f", "2026-10-18T02:35:57Z")
        assertEquals("R1, -" to null, bell.detail to bout(bell).clock)
        assertEquals("Round 1 of 5", bug(bell).second)
        val gap = card("g", "2026-10-18T02:36:43Z")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "End R1", 1, null), listOf(gap.status, gap.detail, bout(gap).round, bout(gap).clock))
        assertEquals("Round 1 of 5", bug(gap).second)
        val stopped = card("i", "2026-10-18T02:40:30Z")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "End R2", 2, null, null), listOf(stopped.status, stopped.detail, bout(stopped).round, bout(stopped).clock, bout(stopped).winner))
    }

    @Test fun finishTimeIsCorrectedToTheTimeElapsed() {
        val first = bout(card("j", "2026-10-18T02:42:46Z"))
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, 2, FightMethod.KO, 2, "2:28", null), listOf(first.state, first.winner, first.method, first.endRound, first.endClock, first.clock))
        val corrected = card("k", "2026-10-18T02:43:32Z")
        assertEquals(FixtureStatus.FINAL, corrected.status)
        assertEquals("2:32", bout(corrected).endClock)
        assertEquals("M. Malott · KO/TKO · R2 2:32", SportsFixtureText.finish(bout(corrected)))
    }
}
