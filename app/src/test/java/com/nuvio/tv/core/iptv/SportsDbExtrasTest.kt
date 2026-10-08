package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsDbExtrasTest {
    private val epl = SportsLeagues.byId("epl")!!
    private val now = Instant.parse("2026-10-09T15:00:00Z").toEpochMilli()
    private val kickOff = Instant.parse("2026-10-09T14:00:00Z").toEpochMilli()

    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/$name")!!.bufferedReader().use { it.readText() }

    private fun fixture(id: String, home: String, away: String, start: Long = kickOff, source: SportsService = SportsService.THESPORTSDB,
        status: FixtureStatus = FixtureStatus.SCHEDULED, alternatives: Pair<List<String>, List<String>> = emptyList<String>() to emptyList()) =
        SportsFixture(id, "epl", "soccer", "$home v $away", FixtureTeam(home, alternatives = alternatives.first), FixtureTeam(away, alternatives = alternatives.second),
            start, status, source = source)

    @Test fun livescoresMergeIntoTheSportsDbFixturesByEventId() {
        val scores = SportsDbLive.parse(sample("tsdb-livescore-soccer-synthetic.json"))
        assertEquals(listOf("2494100", "2494101", "2494102"), scores.map { it.eventId })
        val merged = SportsDbLive.merge(listOf(fixture("2494100", "Fulham", "Brentford"), fixture("2494101", "Everton", "Chelsea", kickOff - 3_600_000),
            fixture("2494102", "Leeds United", "Burnley", now + 5_400_000), fixture("2494100", "Fulham", "Brentford", source = SportsService.ESPN),
            fixture("2494999", "Arsenal", "Spurs")), scores.associateBy { it.eventId }, now)
        val live = merged[0]
        assertEquals(FixtureStatus.LIVE, live.status)
        assertEquals("1–0", live.score)
        assertEquals("1", live.homeLine?.score)
        assertEquals("67'", live.clock)
        assertEquals(2, live.period)
        assertEquals("2H", live.detail)
        assertEquals(FixtureStatus.FINAL, merged[1].status)
        assertEquals("2–2", merged[1].score)
        assertNull(merged[1].clock)
        assertEquals(FixtureStatus.SCHEDULED, merged[2].status)
        assertNull(merged[2].score)
        assertEquals(FixtureStatus.SCHEDULED, merged[3].status)
        assertEquals(FixtureStatus.SCHEDULED, merged[4].status)
        assertTrue(SportsDbLive.parse("""{"Message":"No data found"}""").isEmpty())
        assertTrue(SportsDbLive.parse("""{"livescore":null}""").isEmpty())
    }

    @Test fun livescoresAreAskedOnlyForActiveTheSportsDbGamesInCoveredSports() {
        val soccer = fixture("1", "Fulham", "Brentford")
        assertEquals(listOf("soccer"), SportsDbLive.wanted(listOf(soccer, soccer.copy(id = "2")), now))
        assertTrue(SportsDbLive.wanted(listOf(soccer.copy(source = SportsService.ESPN)), now).isEmpty())
        assertTrue(SportsDbLive.wanted(listOf(soccer.copy(startMillis = now + 6 * 3_600_000)), now).isEmpty())
        assertTrue(SportsDbLive.wanted(listOf(soccer.copy(sport = "netball")), now).isEmpty())
        val many = listOf("soccer", "basketball", "ice-hockey", "baseball", "american-football").mapIndexed { index, sport -> soccer.copy(id = "$index", sport = sport) }
        assertEquals(SportsDbLive.MAX_SPORTS, SportsDbLive.wanted(many, now).size)
        assertEquals("ice_hockey", SportsDbLive.path("ice-hockey"))
        assertNull(SportsDbLive.path("netball"))
    }

    @Test fun tvChannelsPreferTheViewersCountry() {
        val channels = SportsTv.parse(sample("tsdb-lookuptv-synthetic.json"))
        assertEquals(4, channels.size)
        assertEquals(listOf("Stan Sport"), SportsTv.names(channels, "AU"))
        assertEquals(listOf("Sky Sports Premier League"), SportsTv.names(channels, "GB"))
        assertEquals(listOf("Peacock"), SportsTv.names(channels, "US"))
        assertEquals(channels.map { it.name }, SportsTv.names(channels, "NZ"))
        assertEquals(channels.map { it.name }, SportsTv.names(channels, null))
        assertTrue(SportsTv.parse("""{"tvevent":null}""").isEmpty())
        val added = SportsTv.add(listOf(fixture("1", "A", "B").copy(broadcasters = listOf("Optus Sport"))), mapOf("epl:sdb-1" to listOf("Stan Sport", "Optus Sport")))
        assertEquals(listOf("Optus Sport", "Stan Sport"), added.single().broadcasters)
    }

    @Test fun tvLookupIsOnlyForFollowedUnfinishedGames() {
        val favourites = setOf("epl:Fulham")
        val games = listOf(fixture("1", "Fulham", "Brentford"), fixture("2", "Everton", "Chelsea"), fixture("3", "Brentford", "Fulham", status = FixtureStatus.FINAL),
            fixture("4", "Fulham", "Leeds United", now + 86_400_000))
        assertEquals(listOf("1", "4"), SportsTv.wanted(games, favourites, now).map { it.id })
        assertTrue(SportsTv.wanted(games, emptySet(), now).isEmpty())
    }

    @Test fun espnFixturesMatchTheSportsDbEventsByTeamsAndTime() {
        val espn = fixture("401", "Manchester United", "Liverpool", source = SportsService.ESPN, alternatives = listOf("Man United") to listOf("Liverpool"))
        val events = listOf(fixture("2494200", "Manchester City", "Liverpool"), fixture("2494201", "Liverpool", "Manchester United", kickOff + 3 * 86_400_000),
            fixture("2494202", "Manchester United", "Liverpool", kickOff + 15 * 60_000), fixture("2494203", "Arsenal", "Chelsea"))
        assertEquals("2494202", SportsTv.match(espn, events)?.id)
        assertNull(SportsTv.match(espn, events.filter { it.id != "2494202" }))
        val nfl = SportsFixture("402", "nfl", "american-football", "Tampa Bay Buccaneers at Dallas Cowboys", FixtureTeam("Dallas Cowboys", "Cowboys", "DAL",
            listOf("Dallas", "Cowboys")), FixtureTeam("Tampa Bay Buccaneers", "Buccaneers", "TB", listOf("Tampa Bay", "Buccaneers")), kickOff, FixtureStatus.SCHEDULED)
        val sportsDb = SportsFixture("2400001", "nfl", "american-football", "Dallas Cowboys v Tampa Bay Buccaneers", FixtureTeam("Dallas Cowboys"),
            FixtureTeam("Tampa Bay Buccaneers"), kickOff, FixtureStatus.SCHEDULED, source = SportsService.THESPORTSDB)
        assertEquals("2400001", SportsTv.match(nfl, listOf(sportsDb))?.id)
    }

    @Test fun leagueListSearchesAndBecomesCustomLeagues() {
        val leagues = SportsDbLeagues.parse(sample("tsdb-all-leagues-synthetic.json"))
        assertEquals(listOf("4328", "4380", "4459", "5310", "4554"), leagues.map { it.id })
        assertEquals(listOf("Netball Super League", "Suncorp Super Netball"), SportsDbLeagues.search(leagues, "netball").map { it.name })
        assertEquals("English Premier League", SportsDbLeagues.search(leagues, "premier").first().name)
        assertEquals("Suncorp Super Netball", SportsDbLeagues.search(leagues, "Super Netball").first().name)
        assertTrue(SportsDbLeagues.search(leagues, "n").isEmpty())
        assertEquals("epl", SportsDbLeagues.builtIn(leagues[0])?.id)
        assertNull(SportsDbLeagues.builtIn(leagues[2]))
        val netball = SportsDbLeagues.league(leagues[2])
        assertEquals("sdb-4459", netball.id)
        assertEquals("netball", netball.sport)
        assertNull(netball.espn)
        assertEquals("Suncorp Super Netball", netball.sportsDb)
        assertEquals("4459", netball.sportsDbId)
        assertTrue("super netball" in netball.aliases)
        assertEquals("ice-hockey", SportsDbLeagues.league(leagues[1]).sport)
        assertEquals("ice-hockey", SportsDbLeagues.sport("Ice Hockey"))
        assertEquals("mma", SportsDbLeagues.sport("Fighting"))
        assertEquals(netball, SportsDbLeagues.decodeCustom(SportsDbLeagues.encodeCustom(netball)))
        assertNull(SportsDbLeagues.decodeCustom("""{"id":"../x","name":"X"}"""))
        val (fetched, decoded) = SportsDbLeagues.decode(SportsDbLeagues.encode(42L, leagues))!!
        assertEquals(42L, fetched)
        assertEquals(leagues, decoded)
    }
}
