package com.nuvio.tv.core.iptv

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class SportsFixturesTest {
    private val afl = SportsLeagues.byId("afl")!!
    private val epl = SportsLeagues.byId("epl")!!
    private val f1 = SportsLeagues.byId("f1")!!
    private val sydney = ZoneId.of("Australia/Sydney")

    private val espn = """{"leagues":[{"id":"1","abbreviation":"AFL"}],"events":[
        {"id":"401","date":"2026-10-09T08:40Z","name":"Carlton Blues at Richmond Tigers","shortName":"CARL @ RICH",
         "competitions":[{"id":"401","date":"2026-10-09T08:40Z",
           "status":{"clock":0,"displayClock":"0:00","period":0,"type":{"id":"1","name":"STATUS_SCHEDULED","state":"pre","completed":false,"shortDetail":"10/9 - 7:40 PM"}},
           "competitors":[{"id":"1","homeAway":"away","score":"0","team":{"id":"1","location":"Carlton","name":"Blues","abbreviation":"CARL","displayName":"Carlton Blues","shortDisplayName":"Carlton"}},
                          {"id":"2","homeAway":"home","score":"0","team":{"id":"2","location":"Richmond","name":"Tigers","abbreviation":"RICH","displayName":"Richmond Tigers","shortDisplayName":"Richmond"}}],
           "broadcasts":[{"market":"national","names":["Seven Network","Kayo"]}],
           "geoBroadcasts":[{"type":{"id":"1","shortName":"TV"},"market":{"id":"1","type":"National"},"media":{"shortName":"Fox Footy"},"lang":"en","region":"au"}]}]},
        {"id":"402","date":"2026-10-09T03:10Z","name":"Geelong Cats at Sydney Swans",
         "competitions":[{"date":"2026-10-09T03:10Z","status":{"type":{"name":"STATUS_IN_PROGRESS","state":"in","completed":false,"shortDetail":"Q3 12:04"}},
           "competitors":[{"homeAway":"home","score":{"value":62.0,"displayValue":"62"},"team":{"displayName":"Sydney Swans","abbreviation":"SYD"}},
                          {"homeAway":"away","score":{"value":55.0,"displayValue":"55"},"team":{"displayName":"Geelong Cats","abbreviation":"GEEL"}}]}]},
        {"id":"403","date":"2026-10-08T03:10Z","competitions":[{"status":{"type":{"name":"STATUS_FINAL","state":"post","completed":true,"shortDetail":"Final"}},
           "competitors":[{"homeAway":"home","score":"88","team":{"displayName":"Adelaide Crows"}},{"homeAway":"away","score":"70","team":{"displayName":"Port Adelaide"}}]}]},
        {"id":"404","date":"2026-10-10T03:10Z","competitions":[{"status":{"type":{"name":"STATUS_POSTPONED","state":"pre"}},
           "competitors":[{"homeAway":"home","team":{"displayName":"Hawthorn Hawks"}},{"homeAway":"away","team":{"displayName":"Essendon Bombers"}}]}]},
        {"id":"405"},
        {"date":"2026-10-10T03:10Z"}
    ]}"""

    @Test fun espnScoreboardParsesTeamsStatusScoresAndBroadcasters() {
        val fixtures = EspnScoreboard.parse(espn, afl)
        assertEquals(listOf("401", "402", "403"), fixtures.map { it.id })
        val first = fixtures[0]
        assertEquals("Richmond Tigers", first.home?.name)
        assertEquals("Richmond", first.home?.shortName)
        assertEquals("RICH", first.home?.abbreviation)
        assertTrue("Tigers" in first.home!!.alternatives)
        assertEquals("Carlton Blues", first.away?.name)
        assertEquals(FixtureStatus.SCHEDULED, first.status)
        assertNull(first.score)
        assertEquals(listOf("Seven Network", "Kayo", "Fox Footy"), first.broadcasters)
        assertEquals(ZonedDateTime.of(2026, 10, 9, 8, 40, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(), first.startMillis)
        assertEquals("afl", first.league)
        assertEquals(FixtureStatus.LIVE, fixtures[1].status)
        assertEquals("62–55", fixtures[1].score)
        assertEquals("Q3 12:04", fixtures[1].detail)
        assertEquals(FixtureStatus.FINAL, fixtures[2].status)
        assertEquals("88–70", fixtures[2].score)
    }

    @Test fun espnTolerantOfMissingParts() {
        assertTrue(EspnScoreboard.parse("{}", afl).isEmpty())
        assertTrue(EspnScoreboard.parse("""{"events":[]}""", afl).isEmpty())
        val race = EspnScoreboard.parse("""{"events":[{"id":"9","name":"Louis Vuitton Australian Grand Prix","date":"2026-03-15T04:00Z",
            "status":{"type":{"state":"pre"}},"competitions":[{"date":"2026-03-15T04:00Z","competitors":[{"athlete":{"displayName":"A Driver"}},{"athlete":{"displayName":"B Driver"}},{"athlete":{"displayName":"C"}}],
            "broadcasts":[{"names":["ESPN"]}]}]}]}""", f1).single()
        assertNull(race.home)
        assertEquals("Louis Vuitton Australian Grand Prix", race.title)
        assertEquals(listOf("ESPN"), race.broadcasters)
    }

    private val sportsDb = """{"events":[
        {"idEvent":"1","strEvent":"Arsenal vs Chelsea","idLeague":"4328","strLeague":"English Premier League","strSport":"Soccer","strHomeTeam":"Arsenal","strAwayTeam":"Chelsea",
         "intHomeScore":null,"intAwayScore":null,"strTimestamp":"2026-10-10T14:00:00","dateEvent":"2026-10-10","strTime":"14:00:00","strTVStation":"Sky Sports Main Event, Optus Sport","strStatus":"Not Started","strPostponed":"no"},
        {"idEvent":"2","strEvent":"Liverpool vs Everton","idLeague":"4328","strHomeTeam":"Liverpool","strAwayTeam":"Everton","intHomeScore":"1","intAwayScore":"0",
         "dateEvent":"2026-10-10","strTime":"11:30:00+00:00","strStatus":"2H"},
        {"idEvent":"3","strEvent":"Spurs vs Fulham","idLeague":"4328","strHomeTeam":"Tottenham","strAwayTeam":"Fulham","intHomeScore":"2","intAwayScore":"2",
         "strTimestamp":"2026-10-09T14:00:00+00:00","strStatus":"Match Finished"},
        {"idEvent":"4","strEvent":"Leeds vs Hull","idLeague":"4329","strLeague":"English League Championship","strHomeTeam":"Leeds","strAwayTeam":"Hull","strTimestamp":"2026-10-10T14:00:00"},
        {"idEvent":"5","strEvent":"Wolves vs Brentford","idLeague":"4328","strHomeTeam":"Wolves","strAwayTeam":"Brentford","strTimestamp":"2026-10-10T14:00:00","strPostponed":"yes"},
        {"idEvent":"6","strEvent":"Villa vs Forest","strLeague":"English Premier League","strHomeTeam":"Aston Villa","strAwayTeam":"Nottingham Forest","strStatus":"Postponed","strTimestamp":"2026-10-10T14:00:00"},
        {"idEvent":"7","strLeague":"English Premier League","strHomeTeam":"West Ham"}
    ]}"""

    @Test fun sportsDbParsesEventsAndFiltersOtherLeagues() {
        val now = ZonedDateTime.of(2026, 10, 10, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val fixtures = SportsDbEvents.parse(sportsDb, epl, now)
        assertEquals(listOf("1", "2", "3"), fixtures.map { it.id })
        assertEquals(FixtureStatus.SCHEDULED, fixtures[0].status)
        assertEquals("Arsenal", fixtures[0].home?.name)
        assertEquals(listOf("Sky Sports Main Event", "Optus Sport"), fixtures[0].broadcasters)
        assertEquals(ZonedDateTime.of(2026, 10, 10, 14, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(), fixtures[0].startMillis)
        assertEquals(FixtureStatus.LIVE, fixtures[1].status)
        assertEquals("1–0", fixtures[1].score)
        assertEquals("2H", fixtures[1].detail)
        assertEquals(FixtureStatus.FINAL, fixtures[2].status)
        assertEquals("2–2", fixtures[2].score)
        assertTrue(SportsDbEvents.parse("""{"events":null}""", epl, now).isEmpty())
        assertTrue(SportsDbEvents.parse("{}", epl, now).isEmpty())
    }

    @Test fun leaguesMarkServiceSupport() {
        assertTrue(SportsLeagues.ALL.all { it.espn != null || it.sportsDb != null })
        assertFalse(SportsLeagues.byId("big-bash")!!.supports(SportsService.ESPN))
        assertTrue(SportsLeagues.byId("big-bash")!!.supports(SportsService.THESPORTSDB))
        assertFalse(afl.supports(SportsService.OFF))
        assertEquals(SportsLeagues.ALL.size, SportsLeagues.ALL.map { it.id }.distinct().size)
        assertTrue(SportsLeagues.DEFAULTS.all { SportsLeagues.byId(it) != null })
        assertEquals(listOf("afl"), SportsLeagues.chosen(setOf("afl", "big-bash", "missing"), SportsService.ESPN).map { it.id })
    }

    @Test fun daysCoverTodayAndNextTwoInLocalZoneAndServiceDates() {
        val now = ZonedDateTime.of(2026, 10, 7, 9, 0, 0, 0, sydney).toInstant().toEpochMilli()
        val (start, end) = SportsDays.window(now, sydney)
        assertEquals(ZonedDateTime.of(2026, 10, 7, 0, 0, 0, 0, sydney).toInstant().toEpochMilli(), start)
        assertEquals(ZonedDateTime.of(2026, 10, 10, 0, 0, 0, 0, sydney).toInstant().toEpochMilli(), end)
        assertEquals((6..9).map { LocalDate.of(2026, 10, it) }, SportsDays.serviceDates(start, end, SportsDays.ESPN_ZONE))
        assertEquals((6..9).map { LocalDate.of(2026, 10, it) }, SportsDays.serviceDates(start, end, SportsDays.SPORTSDB_ZONE))
        val london = ZoneId.of("Europe/London")
        val (from, until) = SportsDays.window(ZonedDateTime.of(2026, 10, 7, 9, 0, 0, 0, london).toInstant().toEpochMilli(), london)
        assertEquals((6..9).map { LocalDate.of(2026, 10, it) }, SportsDays.serviceDates(from, until, SportsDays.ESPN_ZONE))
        assertEquals((6..9).map { LocalDate.of(2026, 10, it) }, SportsDays.serviceDates(from, until, SportsDays.SPORTSDB_ZONE))
    }

    private fun fixture(id: String, start: Long, status: FixtureStatus = FixtureStatus.SCHEDULED, league: String = "afl") =
        SportsFixture(id, league, "australian-football", id, null, null, start, status)

    @Test fun refreshEvery30MinutesLiveEvery2AndBacksOff() {
        val now = 10_000_000_000L
        val later = fixture("a", now + 5 * 60 * 60 * 1000)
        assertTrue(SportsRefresh.due(null, now))
        assertFalse(SportsRefresh.due(SportsRefresh.succeeded(listOf(later), now - 29 * 60_000), now))
        assertTrue(SportsRefresh.due(SportsRefresh.succeeded(listOf(later), now - 30 * 60_000), now))
        val live = fixture("b", now - 60 * 60_000, FixtureStatus.LIVE)
        assertFalse(SportsRefresh.due(SportsRefresh.succeeded(listOf(live), now - 60_000), now))
        assertTrue(SportsRefresh.due(SportsRefresh.succeeded(listOf(live), now - 2 * 60_000), now))
        val starting = fixture("c", now + 10 * 60_000)
        assertTrue(SportsRefresh.due(SportsRefresh.succeeded(listOf(starting), now - 2 * 60_000), now))
        val failed = SportsRefresh.failed(SportsRefresh.succeeded(listOf(later), now - 3 * 60 * 60_000), now)
        assertEquals(1, failed.failures)
        assertEquals(listOf(later), failed.fixtures)
        assertFalse(SportsRefresh.due(failed, now + 60_000))
        assertTrue(SportsRefresh.due(failed, now + 2 * 60_000))
        val many = (1..12).fold(failed) { entry, _ -> SportsRefresh.failed(entry, now) }
        assertEquals(SportsRefresh.MAX_BACKOFF_MILLIS, SportsRefresh.backoff(many.failures))
        assertFalse(SportsRefresh.due(many, now + 59 * 60_000))
        assertEquals(0, SportsRefresh.succeeded(emptyList(), now).failures)
    }

    @Test fun sectionsGroupLiveTodayTomorrowAndLater() {
        val now = ZonedDateTime.of(2026, 10, 7, 15, 0, 0, 0, sydney).toInstant().toEpochMilli()
        fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, sydney).toInstant().toEpochMilli()
        val fixtures = listOf(fixture("live", at(7, 14), FixtureStatus.LIVE), fixture("tonight", at(7, 19)), fixture("done", at(7, 12), FixtureStatus.FINAL),
            fixture("stale", at(7, 9)), fixture("lagging", at(7, 14)), fixture("tomorrow", at(8, 19)), fixture("saturday", at(9, 13)), fixture("far", at(10, 13)),
            fixture("yesterday", at(6, 13), FixtureStatus.FINAL))
        val rows = SportsFixtureSections.group(fixtures, now, sydney)
        assertEquals(listOf(FixtureSection.LIVE, FixtureSection.TODAY, FixtureSection.TOMORROW, FixtureSection.DAY, FixtureSection.FINISHED), rows.map { it.section })
        assertEquals(listOf("live"), rows[0].fixtures.map { it.id })
        assertEquals(listOf("lagging", "tonight"), rows[1].fixtures.map { it.id })
        assertEquals(listOf("tomorrow"), rows[2].fixtures.map { it.id })
        assertEquals(listOf("saturday"), rows[3].fixtures.map { it.id })
        assertEquals(LocalDate.of(2026, 10, 9), rows[3].day)
        assertEquals(listOf("done"), rows[4].fixtures.map { it.id })
        assertEquals(listOf(FixtureSection.LIVE, FixtureSection.TODAY, FixtureSection.TOMORROW, FixtureSection.DAY),
            SportsFixtureSections.group(fixtures, now, sydney, showScores = false).map { it.section })
    }

    @Test fun cacheEntriesRoundTrip() {
        val fixture = SportsFixture("1", "epl", "soccer", "Arsenal v Chelsea", FixtureTeam("Arsenal", "Gunners", "ARS", listOf("Arsenal FC")),
            FixtureTeam("Chelsea"), 123L, FixtureStatus.LIVE, "1–0", "67'", listOf("Optus Sport"))
        val entry = SportsCacheEntry(listOf(fixture, fixture("x", 5L)), 99L, 100L, 2)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
        assertNull(SportsFixtureCodec.decode("not json"))
        assertNull(SportsFixtureCodec.decode("""{"version":3}"""))
        assertEquals(SportsCacheEntry(emptyList(), null), SportsFixtureCodec.decode("""{"version":1,"fixtures":[{"id":"1"}]}"""))
    }
}
