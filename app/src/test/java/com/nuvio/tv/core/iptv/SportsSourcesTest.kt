package com.nuvio.tv.core.iptv

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SportsSourcesTest {
    private val afl = SportsLeagues.byId("afl")!!
    private val bigBash = SportsLeagues.byId("big-bash")!!
    private val atp = SportsLeagues.byId("atp")!!
    private val netball = SportsDbLeagues.league(SportsDbLeague("4459", "Suncorp Super Netball", "Netball", "Super Netball"))
    private val ok = SportsCacheEntry(emptyList(), 10L)
    private val once = SportsCacheEntry(emptyList(), 10L, 20L, 1)
    private val failing = SportsCacheEntry(emptyList(), 10L, 20L, 2)
    private val untried = SportsCacheEntry(emptyList(), null, 20L, 1)

    @After fun clear() { SportsLeagues.custom = emptyList() }

    @Test fun eachLeagueUsesEspnWhenItCanOtherwiseTheSportsDbWithAKey() {
        assertEquals(SportsService.ESPN, SportsSources.pick(afl, false, listOf(ok), emptyList()))
        assertEquals(SportsService.ESPN, SportsSources.pick(afl, true, listOf(ok), listOf(ok)))
        assertNull(SportsSources.pick(bigBash, false, emptyList(), emptyList()))
        assertEquals(SportsService.THESPORTSDB, SportsSources.pick(bigBash, true, emptyList(), emptyList()))
        assertEquals(SportsService.THESPORTSDB, SportsSources.pick(netball, true, emptyList(), emptyList()))
        assertNull(SportsSources.pick(netball, false, emptyList(), emptyList()))
        assertTrue(SportsSources.needsKey(bigBash))
        assertTrue(SportsSources.needsKey(netball))
        assertFalse(SportsSources.needsKey(afl))
        assertTrue(SportsSources.available(atp, false))
        assertFalse(SportsSources.available(bigBash, false))
        assertTrue(SportsSources.available(bigBash, true))
    }

    @Test fun repeatedEspnFailuresFallBackToTheSportsDbUntilEspnRecovers() {
        assertFalse(SportsSources.espnFailing(listOf(once, ok, null)))
        assertFalse(SportsSources.espnFailing(listOf(null, null)))
        assertTrue(SportsSources.espnFailing(listOf(failing, failing, ok)))
        assertTrue(SportsSources.espnFailing(listOf(failing, ok)))
        assertFalse(SportsSources.espnFailing(listOf(failing, ok, ok)))
        val down = listOf(failing, failing, failing)
        assertTrue(SportsSources.fallback(afl, true, down))
        assertFalse(SportsSources.fallback(afl, false, down))
        assertFalse(SportsSources.fallback(atp, true, down))
        assertEquals(SportsService.ESPN, SportsSources.pick(afl, true, down, listOf(untried)))
        assertEquals(SportsService.THESPORTSDB, SportsSources.pick(afl, true, down, listOf(ok, null)))
        assertEquals(SportsService.ESPN, SportsSources.pick(afl, false, down, listOf(ok)))
        assertEquals(SportsService.ESPN, SportsSources.pick(afl, true, listOf(ok, failing, ok), listOf(ok)))
        assertEquals(SportsService.ESPN, SportsSources.pick(atp, true, down, emptyList()))
    }

    @Test fun oldServiceSettingMigrates() {
        assertTrue(SportsSources.enabled(null, null))
        assertFalse(SportsSources.enabled(null, "OFF"))
        assertTrue(SportsSources.enabled(null, "ESPN"))
        assertTrue(SportsSources.enabled(null, "THESPORTSDB"))
        assertFalse(SportsSources.enabled(false, "ESPN"))
        assertTrue(SportsSources.enabled(true, "OFF"))
    }

    @Test fun logosFollowTheSourceSetting() {
        val team = FixtureTeam("Fulham", logo = "https://example.test/f.png")
        val espn = SportsFixture("1", "epl", "soccer", "A v B", team, team, 0L, FixtureStatus.SCHEDULED, leagueLogo = "https://example.test/l.png")
        val sportsDb = espn.copy(id = "2", source = SportsService.THESPORTSDB)
        val default = SportsSources.logos(listOf(espn, sportsDb), SportsLogos.ESPN)
        assertEquals("https://example.test/f.png", default[0].home?.logo)
        assertEquals("https://example.test/l.png", default[0].leagueLogo)
        assertNull(default[1].home?.logo)
        assertNull(default[1].away?.logo)
        assertNull(default[1].leagueLogo)
        assertTrue(SportsSources.logos(listOf(espn, sportsDb), SportsLogos.OFF).all { it.home?.logo == null && it.leagueLogo == null })
        assertEquals(listOf(espn, sportsDb), SportsSources.logos(listOf(espn, sportsDb), SportsLogos.ALL))
    }

    @Test fun fixtureKeysStayStableAndSourcesSurviveTheCache() {
        val espn = SportsFixture("2494100", "epl", "soccer", "Fulham v Brentford", null, null, 5L, FixtureStatus.SCHEDULED)
        val sportsDb = espn.copy(source = SportsService.THESPORTSDB)
        assertEquals("epl:2494100", espn.key)
        assertEquals("epl:sdb-2494100", sportsDb.key)
        val encoded = SportsFixtureCodec.encode(SportsCacheEntry(listOf(espn, sportsDb), 7L))
        assertEquals(listOf(SportsService.ESPN, SportsService.THESPORTSDB), SportsFixtureCodec.decode(encoded)!!.fixtures.map { it.source })
        val old = """{"version":2,"fetched":7,"failures":0,"fixtures":[{"id":"9","league":"epl","start":5,"status":"SCHEDULED","title":"A v B"}]}"""
        assertEquals(SportsService.ESPN, SportsFixtureCodec.decode(old)!!.fixtures.single().source)
        assertEquals(SportsService.THESPORTSDB, SportsFixtureCodec.decode(old, SportsService.THESPORTSDB)!!.fixtures.single().source)
        assertEquals(SportsService.THESPORTSDB, SportsDbEvents.parse("""{"events":[{"idEvent":"1","idLeague":"4328","strTimestamp":"2026-10-09T14:00:00",
            "strStatus":"NS","strHomeTeam":"Fulham","strAwayTeam":"Brentford"}]}""", SportsLeagues.byId("epl")!!, 0L).single().source)
    }

    @Test fun customLeaguesAreFoundById() {
        assertNull(SportsLeagues.byId(netball.id))
        SportsLeagues.custom = listOf(netball)
        assertEquals(netball, SportsLeagues.byId("sdb-4459"))
        assertEquals(listOf("afl", "sdb-4459"), SportsLeagues.chosen(setOf("afl", "sdb-4459")).map { it.id })
        assertTrue(netball.custom)
        assertFalse(afl.custom)
    }
}
