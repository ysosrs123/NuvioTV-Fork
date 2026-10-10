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

    @Test fun realSoccerLivescoresMergeIntoTheSportsDbFixturesByEventId() {
        val scores = SportsDbLive.parse(sample("tsdb-livescore-soccer-real-20261010.json")).associateBy { it.eventId }
        assertEquals(7, scores.size)
        val merged = SportsDbLive.merge(listOf(fixture("2494052", "Arsenal", "Leeds United"), fixture("2478345", "Zalgiris Vilnius", "Suduva"),
            fixture("2561953", "A", "B", kickOff - 3_600_000), fixture("2523792", "C", "D"), fixture("2605680", "South Melbourne", "Melbourne Victory"),
            fixture("2494052", "Arsenal", "Leeds United", source = SportsService.ESPN), fixture("2494999", "Chelsea", "Bournemouth")), scores, now)
        val arsenal = merged[0]
        assertEquals(FixtureStatus.LIVE, arsenal.status)
        assertEquals("0–0", arsenal.score)
        assertEquals("0", arsenal.homeLine?.score)
        assertEquals("45+1'", arsenal.clock)
        assertEquals(1, arsenal.period)
        assertEquals("1H", arsenal.detail)
        assertEquals("HT", merged[1].detail)
        assertEquals("45+4'", merged[1].clock)
        assertEquals("2–0", merged[1].score)
        assertEquals(FixtureStatus.FINAL, merged[2].status)
        assertEquals("1–0", merged[2].score)
        assertNull(merged[2].clock)
        assertEquals("90+3'", merged[3].clock)
        assertEquals(2, merged[3].period)
        assertEquals(FixtureStatus.LIVE, merged[4].status)
        assertEquals("1–1", merged[4].score)
        assertNull(merged[4].clock)
        assertEquals(FixtureStatus.SCHEDULED, merged[5].status)
        assertEquals(FixtureStatus.SCHEDULED, merged[6].status)
        assertTrue(SportsDbLive.parse("""{"Message":"No data found"}""").isEmpty())
        assertTrue(SportsDbLive.parse("""{"livescore":null}""").isEmpty())
    }

    @Test fun realLivescoresFromOtherSportsMapStatusesAndProgress() {
        val scores = SportsDbLive.parse(sample("tsdb-livescore-other-real-20261010.json")).associateBy { it.eventId }
        assertEquals(11, scores.size)
        fun merged(id: String, start: Long = kickOff) = SportsDbLive.merge(listOf(fixture(id, "Home", "Away", start)), scores, now).single()
        val nfl = merged("2475438", kickOff - 86_400_000)
        assertEquals(FixtureStatus.FINAL, nfl.status)
        assertEquals("16–24", nfl.score)
        assertNull(nfl.clock)
        val noStatus = merged("2612959")
        assertEquals(FixtureStatus.LIVE, noStatus.status)
        assertEquals("41–46", noStatus.score)
        assertNull(noStatus.detail)
        assertEquals(FixtureStatus.SCHEDULED, merged("2612959", now + 600_000).status)
        assertEquals(FixtureStatus.SCHEDULED, merged("2586119").status)
        assertNull(merged("2586119").score)
        assertEquals(FixtureStatus.SCHEDULED, merged("2522395").status)
        val quarter = merged("2487908")
        assertEquals(4, quarter.period)
        assertEquals("9'", quarter.clock)
        assertEquals("98–80", quarter.score)
        val overtime = merged("2526140")
        assertEquals(FixtureStatus.LIVE, overtime.status)
        assertEquals("OT", overtime.detail)
        assertEquals(2, merged("2506696").period)
        assertEquals("BT", merged("2506694").detail)
        assertEquals(FixtureStatus.FINAL, merged("2400532").status)
        assertEquals("8–5", merged("2400532").score)
        val partial = merged("2615590")
        assertEquals(FixtureStatus.FINAL, partial.status)
        assertNull(partial.score)
    }

    @Test fun realEventsDayAndLivescoreTogether() {
        val epl = SportsLeagues.byId("epl")!!
        val at = Instant.parse("2026-10-10T12:16:00Z").toEpochMilli()
        val events = SportsDbEvents.parse(sample("tsdb-eventsday-epl-real-20261010.json"), epl, at)
        assertEquals(listOf("2494050", "2494052"), events.map { it.id })
        assertEquals(FixtureStatus.SCHEDULED, events[0].status)
        assertEquals(Instant.parse("2026-10-10T16:30:00Z").toEpochMilli(), events[0].startMillis)
        assertEquals(FixtureStatus.LIVE, events[1].status)
        assertNull(events[1].clock)
        assertTrue(events.all { it.broadcasters.isEmpty() })
        val merged = SportsDbLive.merge(events, SportsDbLive.parse(sample("tsdb-livescore-soccer-real-20261010.json")).associateBy { it.eventId }, at)
        assertEquals("45+1'", merged[1].clock)
        assertEquals("0–0", merged[1].score)
        assertEquals(events[0], merged[0])
    }

    @Test fun livescoresAreAskedOnlyForActiveTheSportsDbGamesInCoveredSports() {
        val soccer = fixture("1", "Fulham", "Brentford")
        assertEquals(listOf("soccer"), SportsDbLive.wanted(listOf(soccer, soccer.copy(id = "2")), now))
        assertTrue(SportsDbLive.wanted(listOf(soccer.copy(source = SportsService.ESPN)), now).isEmpty())
        assertTrue(SportsDbLive.wanted(listOf(soccer.copy(startMillis = now + 6 * 3_600_000)), now).isEmpty())
        assertTrue(SportsDbLive.wanted(listOf(soccer.copy(sport = "netball")), now).isEmpty())
        val many = listOf("soccer", "basketball", "ice-hockey", "baseball", "american-football").mapIndexed { index, sport -> soccer.copy(id = "$index", sport = sport) }
        assertEquals(SportsDbLive.MAX_SPORTS, SportsDbLive.wanted(many, now).size)
        assertEquals(listOf("Soccer", "Basketball", "Ice_Hockey", "Baseball", "American_Football"),
            listOf("soccer", "basketball", "ice-hockey", "baseball", "american-football").map(SportsDbLive::path))
        assertNull(SportsDbLive.path("netball"))
    }

    @Test fun realTvChannelsPreferTheViewersCountry() {
        val channels = SportsTv.parse(sample("tsdb-lookuptv-real-20261010.json"))
        assertEquals(46, channels.size)
        assertEquals(listOf("7mate NSW", "7mate Queensland", "7mate South Australia", "7mate Victoria", "7mate Western Australia", "DAZN Australia", "ESPN Australia"),
            SportsTv.names(channels, "AU"))
        assertEquals(listOf("Sky Sports NFL", "Sky Sports Main Event"), SportsTv.names(channels, "GB"))
        assertEquals(listOf("NFL Sunday Ticket"), SportsTv.names(channels, "US"))
        assertEquals(listOf("ESPN 1 Netherlands"), SportsTv.names(channels, "NL"))
        assertEquals(listOf("DAZN Czechia"), SportsTv.names(channels, "CZ"))
        assertEquals(listOf("DAZN Turkey"), SportsTv.names(channels, "TR"))
        assertEquals(SportsTv.MAX_CHANNELS, SportsTv.names(channels, "CA").size)
        assertEquals(channels.take(SportsTv.MAX_CHANNELS).map { it.name }, SportsTv.names(channels, "JP"))
        assertEquals(channels.take(SportsTv.MAX_CHANNELS).map { it.name }, SportsTv.names(channels, null))
        assertTrue(SportsTv.parse("""{"tvevent":null}""").isEmpty())
        val added = SportsTv.add(listOf(fixture("1", "A", "B").copy(broadcasters = listOf("Optus Sport"))), mapOf("epl:sdb-1" to listOf("Stan Sport", "Optus Sport")))
        assertEquals(listOf("Optus Sport", "Stan Sport"), added.single().broadcasters)
    }

    @Test fun realLivescoresSkipRowsLeftLiveForHoursAndClocksCountUp() {
        val at = Instant.parse("2026-10-10T19:10:00Z").toEpochMilli()
        val scores = SportsDbLive.parse(sample("tsdb-livescore-all-real-20261011.json")).associateBy { it.eventId }
        assertEquals(11, scores.size)
        assertEquals(Instant.parse("2026-10-10T19:08:30Z").toEpochMilli(), scores.getValue("2519046").updatedMillis)
        assertEquals(Instant.parse("2026-10-10T05:35:30Z").toEpochMilli(), SportsDbLive.updated("2026-10-10 06:35:30"))
        assertNull(SportsDbLive.updated("yesterday"))
        fun merged(id: String, start: String, sport: String = "ice-hockey") =
            SportsDbLive.merge(listOf(fixture(id, "Home", "Away", Instant.parse(start).toEpochMilli()).copy(sport = sport)), scores, at).single()
        listOf("2559218" to "2026-10-09T22:00:00Z", "2600752" to "2026-10-10T01:00:00Z", "2526140" to "2026-10-10T01:05:00Z").forEach { (id, start) ->
            val stuck = merged(id, start)
            assertEquals(id, FixtureStatus.SCHEDULED, stuck.status)
            assertNull(id, stuck.score)
        }
        val hockey = merged("2519046", "2026-10-10T17:45:00Z")
        assertEquals(FixtureStatus.LIVE, hockey.status)
        assertEquals("4–3", hockey.score)
        assertEquals(2, hockey.period)
        assertEquals("17'", hockey.clock)
        assertEquals(82.75, SportsMarkers.minutes("ice-hockey", hockey.period, hockey.clock)!!, 0.001)
        assertEquals(85.0, (at - hockey.startMillis) / 60_000.0, 0.001)
        assertEquals(58.25, SportsMarkers.minutes("ice-hockey", 2, "17:00")!!, 0.001)
        val interval = merged("2522049", "2026-10-10T17:30:00Z")
        assertEquals("BT", interval.detail)
        assertNull(interval.period)
        assertEquals(FixtureStatus.SCHEDULED, merged("2521845", "2026-10-10T17:00:00Z").status)
        val overtime = merged("2584724", "2026-10-10T16:00:00Z", "basketball")
        assertEquals(FixtureStatus.FINAL, overtime.status)
        assertEquals("99–93", overtime.score)
        val second = merged("2564520", "2026-10-10T17:00:00Z", "soccer")
        assertEquals(2, second.period)
        assertEquals("46'", second.clock)
        val half = merged("2406578", "2026-10-10T18:00:00Z", "soccer")
        assertEquals("HT", half.detail)
        assertEquals("45+6'", half.clock)
        assertEquals(FixtureStatus.FINAL, merged("2611920", "2026-10-10T16:00:00Z", "soccer").status)
    }

    @Test fun realTvChannelsMatchCountriesWrittenOut() {
        val channels = SportsTv.parse(sample("tsdb-lookuptv-real-20261011.json"))
        assertEquals(9, channels.size)
        assertEquals(listOf("Arena Sport 1 BiH"), SportsTv.names(channels, "BA"))
        assertEquals(listOf("TOD KSA"), SportsTv.names(channels, "SA"))
        assertEquals(listOf("BeIN Sports 4 Qatar"), SportsTv.names(channels, "QA"))
        assertEquals(listOf("Nova Sport 5 CZ"), SportsTv.names(channels, "CZ"))
        assertEquals(listOf("Ligue 1+ 4 FR"), SportsTv.names(channels, "FR"))
        assertEquals(channels.take(SportsTv.MAX_CHANNELS).map { it.name }, SportsTv.names(channels, "AU"))
        assertTrue("saint kitts and nevis" in SportsTv.countryNames("KN"))
        assertTrue("trinidad and tobago" in SportsTv.countryNames("TT"))
        assertTrue("hong kong" in SportsTv.countryNames("HK"))
        assertTrue("ivory coast" in SportsTv.countryNames("CI"))
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

    @Test fun realLeagueListSearchesAndBecomesCustomLeagues() {
        val leagues = SportsDbLeagues.parse(sample("tsdb-all-leagues-real-20261010.json"))
        assertEquals(17, leagues.size)
        assertTrue(leagues.none { it.name.startsWith("_") })
        assertEquals(leagues, SportsDbLeagues.parse(sample("tsdb-all-leagues-real-20261010.json").replace("\"leagues\"", "\"all\"")))
        assertEquals(listOf("Netball World Cup", "Australian Super Netball League", "Commonwealth Games Netball", "New Zealand Netball League", "UK Netball Superleague"),
            SportsDbLeagues.search(leagues, "netball").map { it.name })
        assertEquals("English Premier League", SportsDbLeagues.search(leagues, "premier").first().name)
        assertEquals("Australian Super Netball League", SportsDbLeagues.search(leagues, "Super Netball").first().name)
        assertEquals("Australian Big Bash League", SportsDbLeagues.search(leagues, "kfc big bash").single().name)
        assertTrue(SportsDbLeagues.search(leagues, "n").isEmpty())
        assertEquals("epl", SportsDbLeagues.builtIn(leagues[0])?.id)
        assertEquals("afl", SportsDbLeagues.builtIn(leagues.first { it.id == "4456" })?.id)
        assertNull(SportsDbLeagues.builtIn(leagues.first { it.id == "4540" }))
        assertNull(leagues.first { it.id == "4735" }.alternate)
        assertTrue(SportsDbLeagues.parse("""{"leagues":[{"idLeague":"bad","strLeague":"X","strSport":"Soccer"},{"idLeague":"1","strLeague":null}]}""").isEmpty())
        val netball = SportsDbLeagues.league(leagues.first { it.id == "4540" })
        assertEquals("sdb-4540", netball.id)
        assertEquals("netball", netball.sport)
        assertNull(netball.espn)
        assertEquals("Australian Super Netball League", netball.sportsDb)
        assertEquals("4540", netball.sportsDbId)
        assertTrue("australian super netball league" in netball.aliases)
        assertEquals("ice-hockey", SportsDbLeagues.league(leagues.first { it.id == "4380" }).sport)
        assertEquals("ice-hockey", SportsDbLeagues.sport("Ice Hockey"))
        assertEquals("mma", SportsDbLeagues.sport("Fighting"))
        assertEquals(netball, SportsDbLeagues.decodeCustom(SportsDbLeagues.encodeCustom(netball)))
        assertNull(SportsDbLeagues.decodeCustom("""{"id":"../x","name":"X"}"""))
        val (fetched, decoded) = SportsDbLeagues.decode(SportsDbLeagues.encode(42L, leagues))!!
        assertEquals(42L, fetched)
        assertEquals(leagues, decoded)
    }
}
