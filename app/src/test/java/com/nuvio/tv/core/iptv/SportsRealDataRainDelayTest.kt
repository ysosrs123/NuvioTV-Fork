package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataRainDelayTest {
    private fun sample(suffix: String): String =
        javaClass.getResourceAsStream("/sports/espn-cricket-scoreboard-real-20261018$suffix.json")!!.bufferedReader().use { it.readText() }
    private fun parse(suffix: String, series: String, time: String) =
        EspnScoreboard.parse(sample(suffix), SportsLeague("cricket-test", "Cricket", "cricket", "cricket/$series", null), Instant.parse(time).toEpochMilli()).single()

    @Test fun rainDelayShowsInsteadOfLive() {
        val test = parse("r", "24203", "2026-10-18T13:38:00Z")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "Match delayed by rain", "Match delayed by rain"), listOf(test.status, test.detail, SportsFixtureText.periodClock(test)))
        val bug = SportsFixtureText.bug(test)
        assertEquals(listOf("SA 20/2", "12.2 ov", "Match delayed by rain"), listOf(bug.primary, bug.state, bug.extra))
        val entry = SportsCacheEntry(listOf(test), Instant.parse("2026-10-18T13:38:00Z").toEpochMilli())
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun playInProgressStillReadsLive() {
        val odi = parse("n", "1550706", "2026-10-18T12:45:00Z")
        assertEquals("Live" to "Need 301 from 289", odi.detail to SportsFixtureText.bug(odi).extra)
    }
}
