package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataShootoutTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/espn-$name-scoreboard-real-20261018q.json")!!.bufferedReader().use { it.readText() }
    private fun league(path: String) = SportsLeague("cup-test", "Cup", "soccer", path, null)
    private val now = Instant.parse("2026-10-18T13:28:00Z").toEpochMilli()

    @Test fun penaltyShootoutFinalNamesTheWinnerAndScore() {
        val game = EspnScoreboard.parse(sample("eng-trophy"), league("soccer/eng.trophy"), now).single()
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, "2–2", "FT-Pens · CHL 7–6"), listOf(game.status, game.score, game.detail))
        assertEquals("FT-Pens · CHL 7–6", SportsFixtureText.bug(game).state)
        val entry = SportsCacheEntry(listOf(game), now)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun extraTimeFinalWithoutAShootoutScoreKeepsTheStatus() {
        val game = EspnScoreboard.parse(sample("copa-del-rey"), league("soccer/esp.copa_del_rey"), now).single()
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, "3–3", "AET"), listOf(game.status, game.score, game.detail))
    }
}
