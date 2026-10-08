package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SportsEventsTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/$name")!!.bufferedReader().use { it.readText() }
    private val epl = SportsLeagues.byId("epl")!!
    private val nrl = SportsLeagues.byId("nrl")!!

    @Test fun soccerDetailsBecomeGoalsAndCards() {
        val fixture = EspnScoreboard.parse(sample("espn-epl-details-scoreboard-synthetic.json"), epl).single()
        assertEquals(listOf(
            FixtureEvent(FixtureEventKind.GOAL, FixtureSide.HOME, "12'", null, "B. Saka"),
            FixtureEvent(FixtureEventKind.YELLOW, FixtureSide.AWAY, "25'", null, "M. Caicedo"),
            FixtureEvent(FixtureEventKind.GOAL, FixtureSide.AWAY, "41'", null, "C. Palmer"),
            FixtureEvent(FixtureEventKind.RED, FixtureSide.AWAY, "55'", null, "Wesley Fofana"),
            FixtureEvent(FixtureEventKind.GOAL, FixtureSide.HOME, "67'", null, "Declan Rice"),
            FixtureEvent(FixtureEventKind.RED, null, "70'", null, null)), fixture.events)
        assertEquals(0, SportsEvents.reds(fixture, FixtureSide.HOME))
        assertEquals(1, SportsEvents.reds(fixture, FixtureSide.AWAY))
        assertNull(SportsEvents.recentTry(fixture))
        assertEquals(listOf(MomentKind.GOAL, MomentKind.CARD_YELLOW, MomentKind.GOAL, MomentKind.CARD_RED, MomentKind.GOAL, MomentKind.CARD_RED),
            SportsEvents.moments(fixture).map { it.kind })
    }

    @Test fun rugbyLeagueTriesAndRecentTry() {
        val fixture = EspnScoreboard.parse(sample("espn-nrl-details-scoreboard-synthetic.json"), nrl).single()
        assertEquals(listOf(FixtureEventKind.TRY, FixtureEventKind.OTHER, FixtureEventKind.TRY, FixtureEventKind.OTHER, FixtureEventKind.TRY), fixture.events.map { it.kind })
        assertEquals(listOf(FixtureSide.AWAY, FixtureSide.AWAY, FixtureSide.HOME, FixtureSide.HOME, FixtureSide.HOME), fixture.events.map { it.side })
        assertEquals(FixtureEvent(FixtureEventKind.TRY, FixtureSide.HOME, "51'", null, "Daniel Tupou"), SportsEvents.recentTry(fixture))
        assertNull(SportsEvents.recentTry(fixture.copy(clock = "54'")))
        assertNull(SportsEvents.recentTry(fixture.copy(clock = null)))
        assertNull(SportsEvents.recentTry(fixture.copy(status = FixtureStatus.FINAL)))
        assertEquals(0, SportsEvents.reds(fixture, FixtureSide.HOME))
        assertEquals(3, SportsEvents.moments(fixture).size)
    }

    @Test fun scheduledAndOtherSportsHaveNoEvents() {
        val json = sample("espn-epl-details-scoreboard-synthetic.json").replace("\"state\":\"in\"", "\"state\":\"pre\"")
        assertTrue(EspnScoreboard.parse(json, epl).single().events.isEmpty())
        assertTrue(EspnScoreboard.parse(sample("espn-nfl-scoreboard.json"), SportsLeagues.byId("nfl")!!).all { it.events.isEmpty() })
    }

    @Test fun eventsRoundTripAndOldCachesLoad() {
        val fixture = EspnScoreboard.parse(sample("espn-epl-details-scoreboard-synthetic.json"), epl).single()
        val entry = SportsCacheEntry(listOf(fixture), 5L)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
        val old = """{"version":2,"fetched":5,"fixtures":[{"id":"1","league":"epl","sport":"soccer","title":"A v B","start":1,"status":"LIVE"}]}"""
        assertEquals(emptyList<FixtureEvent>(), SportsFixtureCodec.decode(old)!!.fixtures.single().events)
        val odd = old.replace("\"status\":\"LIVE\"", "\"status\":\"LIVE\",\"events\":[{\"kind\":\"NOPE\"},{\"kind\":\"RED\",\"side\":\"X\"},3]")
        assertEquals(listOf(FixtureEvent(FixtureEventKind.RED, null, null)), SportsFixtureCodec.decode(odd)!!.fixtures.single().events)
    }
}
