package com.nuvio.tv.core.iptv

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class SportsFixturesDetailsTest {
    private val nfl = SportsLeagues.byId("nfl")!!
    private val epl = SportsLeagues.byId("epl")!!
    private val afl = SportsLeagues.byId("afl")!!
    private val utc = ZoneId.of("UTC")

    private val nflScoreboard = """{"leagues":[{"id":"28","uid":"s:20~l:28","name":"National Football League","abbreviation":"NFL",
        "logos":[{"href":"https://a.espncdn.com/i/teamlogos/leagues/500/nfl.png","width":500,"height":500,"rel":["full","default"]}]}],
      "season":{"type":2,"year":2026},"week":{"number":4},
      "events":[{"id":"401772938","uid":"s:20~l:28~e:401772938","date":"2026-10-04T20:05Z","name":"Los Angeles Chargers at Seattle Seahawks",
        "shortName":"LAC @ SEA","season":{"year":2026,"type":2},"week":{"number":4},
        "competitions":[{"id":"401772938","date":"2026-10-04T20:05Z","attendance":0,
          "venue":{"id":"3673","fullName":"Lumen Field","address":{"city":"Seattle","state":"WA","country":"USA"},"indoor":false},
          "competitors":[
            {"id":"26","homeAway":"home","winner":false,"score":"7",
             "team":{"id":"26","uid":"s:20~l:28~t:26","location":"Seattle","name":"Seahawks","abbreviation":"SEA","displayName":"Seattle Seahawks",
               "shortDisplayName":"Seahawks","color":"002a5c","alternateColor":"69be28","isActive":true,
               "logo":"https://a.espncdn.com/i/teamlogos/nfl/500/scoreboard/sea.png"},
             "linescores":[{"value":7.0,"displayValue":"7","period":1}],
             "records":[{"name":"overall","abbreviation":"Any","type":"total","summary":"2-1"},{"name":"Home","type":"home","summary":"1-0"}]},
            {"id":"24","homeAway":"away","winner":false,"score":"3",
             "team":{"id":"24","location":"Los Angeles","name":"Chargers","abbreviation":"LAC","displayName":"Los Angeles Chargers",
               "shortDisplayName":"Chargers","color":"0080c6","logos":[{"href":"https://a.espncdn.com/i/teamlogos/nfl/500/lac.png"}]},
             "linescores":[{"value":3.0,"period":1}],
             "records":[{"name":"overall","type":"total","summary":"0-3"}]}],
          "situation":{"lastPlay":{"id":"4017729381","type":{"id":"74","text":"Official Timeout"},"text":"Official Timeout at 05:02.",
              "scoreValue":0,"team":{"id":"24"},"probability":{"tiePercentage":0.0,"homeWinPercentage":0.784,"awayWinPercentage":0.216,"secondsLeft":0}},
            "down":1,"yardLine":33,"distance":10,"downDistanceText":"1st & 10 at LAC 33","shortDownDistanceText":"1st & 10","possessionText":"LAC 33",
            "isRedZone":false,"homeTimeouts":3,"awayTimeouts":3,"possession":"24"},
          "status":{"clock":302.0,"displayClock":"5:02","period":1,"type":{"id":"2","name":"STATUS_IN_PROGRESS","state":"in","completed":false,
            "description":"In Progress","detail":"5:02 - 1st Quarter","shortDetail":"5:02 - 1st"}},
          "broadcasts":[{"market":"national","names":["FOX"]}]}],
        "status":{"clock":302.0,"displayClock":"5:02","period":1,"type":{"id":"2","name":"STATUS_IN_PROGRESS","state":"in","completed":false}}},
       {"id":"401772939","date":"2026-10-05T00:25Z","name":"Kansas City Chiefs at Las Vegas Raiders","week":{"number":4},
        "competitions":[{"date":"2026-10-05T00:25Z","venue":{"fullName":"Allegiant Stadium"},
          "competitors":[{"id":"13","homeAway":"home","score":"0","team":{"id":"13","displayName":"Las Vegas Raiders","abbreviation":"LV","color":"#000000"},
              "records":[{"type":"total","summary":"1-2"}]},
            {"id":"12","homeAway":"away","score":"0","team":{"id":"12","displayName":"Kansas City Chiefs","abbreviation":"KC","color":"e31837"}}],
          "status":{"clock":900.0,"displayClock":"15:00","period":0,"type":{"name":"STATUS_SCHEDULED","state":"pre","completed":false,"shortDetail":"10/4 - 8:25 PM EDT"}}}]}]}"""

    @Test fun espnParsesLogosVenueRecordsLinescoresSituationAndProbability() {
        val fixtures = EspnScoreboard.parse(nflScoreboard, nfl)
        assertEquals(2, fixtures.size)
        val live = fixtures[0]
        assertEquals(FixtureStatus.LIVE, live.status)
        assertEquals("https://a.espncdn.com/i/teamlogos/leagues/500/nfl.png", live.leagueLogo)
        assertEquals("Lumen Field", live.venue)
        assertEquals(4, live.round)
        assertEquals(1, live.period)
        assertEquals("5:02", live.clock)
        assertEquals("5:02 - 1st", live.detail)
        assertEquals("Seattle Seahawks", live.home?.name)
        assertEquals("https://a.espncdn.com/i/teamlogos/nfl/500/scoreboard/sea.png", live.home?.logo)
        assertEquals("https://a.espncdn.com/i/teamlogos/nfl/500/lac.png", live.away?.logo)
        assertEquals("002a5c", live.home?.colour)
        assertEquals("2-1", live.home?.record)
        assertEquals("0-3", live.away?.record)
        assertEquals("7–3", live.score)
        assertEquals(FixtureLine("7", listOf("7")), live.homeLine)
        assertEquals(FixtureLine("3", listOf("3")), live.awayLine)
        val situation = live.situation!!
        assertEquals("1st & 10 · LAC 33", situation.downDistance)
        assertEquals(FixtureSide.AWAY, situation.possession)
        assertEquals("Official Timeout at 05:02.", situation.lastPlay)
        assertEquals(78, situation.homeWinPercent)
        assertEquals("Q1 · 5:02", SportsFixtureText.periodClock(live))
        assertTrue(SportsFixtureText.awayFirst(live))
        val later = fixtures[1]
        assertEquals(FixtureStatus.SCHEDULED, later.status)
        assertEquals("Allegiant Stadium", later.venue)
        assertEquals("000000", later.home?.colour)
        assertEquals("1-2", later.home?.record)
        assertNull(later.away?.record)
        assertNull(later.homeLine)
        assertNull(later.situation)
        assertNull(later.period)
        assertNull(later.clock)
        assertNull(SportsFixtureText.periodClock(later))
    }

    @Test fun espnSoccerScoreObjectsAndMissingSituation() {
        val json = """{"events":[{"id":"7","date":"2026-10-10T14:00Z","competitions":[{"venue":{"displayName":"Emirates Stadium"},
            "status":{"displayClock":"67'","period":2,"type":{"name":"STATUS_SECOND_HALF","state":"in","shortDetail":"67'"}},
            "competitors":[{"homeAway":"home","score":{"value":1.0,"displayValue":"1"},"linescores":[{"value":0.0},{"value":1.0}],
                 "team":{"displayName":"Arsenal","logos":[{"href":"not a url"},{"href":"https://a.espncdn.com/i/teamlogos/soccer/500/359.png"}],"color":"zzzzzz"},
                 "records":[{"type":"ytd","summary":"5-1-1"}]},
               {"homeAway":"away","score":{"value":1.0},"team":{"displayName":"Chelsea"}}],
            "situation":{}}]}]}"""
        val match = EspnScoreboard.parse(json, epl).single()
        assertEquals("Emirates Stadium", match.venue)
        assertEquals("1–1", match.score)
        assertEquals(listOf("0", "1"), match.homeLine?.periods)
        assertEquals("https://a.espncdn.com/i/teamlogos/soccer/500/359.png", match.home?.logo)
        assertNull(match.home?.colour)
        assertEquals("5-1-1", match.home?.record)
        assertNull(match.situation)
        assertNull(match.round)
        assertEquals("2H · 67'", SportsFixtureText.periodClock(match))
        assertFalse(SportsFixtureText.awayFirst(match))
        assertTrue(SportsFixtureSections.close(match))
    }

    @Test fun sportsDbParsesBadgesVenueRoundAndProgress() {
        val json = """{"events":[{"idEvent":"2267081","strEvent":"Liverpool vs Everton","idLeague":"4328","strLeague":"English Premier League",
            "strLeagueBadge":"https://r2.thesportsdb.com/images/media/league/badge/gasy9d1737743125.png","strHomeTeam":"Liverpool","strAwayTeam":"Everton",
            "intHomeScore":"2","intAwayScore":"1","intRound":"8","strTimestamp":"2026-10-10T11:30:00","strVenue":"Anfield","strProgress":"58","strStatus":"2H",
            "strHomeTeamBadge":"https://r2.thesportsdb.com/images/media/team/badge/kfaher1737969724.png","strAwayTeamBadge":"",
            "strPostponed":"no"},
          {"idEvent":"2267082","strEvent":"Arsenal vs Chelsea","idLeague":"4328","strHomeTeam":"Arsenal","strAwayTeam":"Chelsea","intRound":"8",
            "strTimestamp":"2026-10-11T14:00:00","strStatus":"Not Started","strVenue":"Emirates Stadium","strProgress":null}]}"""
        val now = ZonedDateTime.of(2026, 10, 10, 12, 30, 0, 0, utc).toInstant().toEpochMilli()
        val (live, later) = SportsDbEvents.parse(json, epl, now)
        assertEquals(FixtureStatus.LIVE, live.status)
        assertEquals("Anfield", live.venue)
        assertEquals(8, live.round)
        assertEquals(2, live.period)
        assertEquals("58'", live.clock)
        assertEquals("https://r2.thesportsdb.com/images/media/team/badge/kfaher1737969724.png", live.home?.logo)
        assertNull(live.away?.logo)
        assertEquals("https://r2.thesportsdb.com/images/media/league/badge/gasy9d1737743125.png", live.leagueLogo)
        assertEquals("2" to "1", SportsFixtureText.scores(live))
        assertEquals("2H · 58'", SportsFixtureText.periodClock(live))
        assertTrue(SportsFixtureSections.close(live))
        assertEquals(FixtureStatus.SCHEDULED, later.status)
        assertNull(later.clock)
        assertNull(later.homeLine)
        assertEquals("Emirates Stadium", later.venue)
    }

    @Test fun closeGamesUseSportMarginsAndHideWithoutScores() {
        val start = ZonedDateTime.of(2026, 10, 10, 12, 0, 0, 0, utc).toInstant().toEpochMilli()
        fun game(id: String, league: SportsLeague, home: String, away: String, homeTeam: String = "H$id") = SportsFixture(id, league.id, league.sport,
            id, FixtureTeam(homeTeam), FixtureTeam("A$id"), start, FixtureStatus.LIVE, "$home–$away", homeLine = FixtureLine(home), awayLine = FixtureLine(away))
        val tight = game("1", epl, "0", "0")
        val open = game("2", epl, "3", "0")
        val footy = game("3", afl, "62", "55")
        val football = game("4", nfl, "21", "10", homeTeam = "Seattle Seahawks")
        assertTrue(SportsFixtureSections.close(tight))
        assertFalse(SportsFixtureSections.close(open))
        assertTrue(SportsFixtureSections.close(footy))
        assertFalse(SportsFixtureSections.close(football))
        assertFalse(SportsFixtureSections.close(tight.copy(status = FixtureStatus.FINAL)))
        val favourites = SportsFavourites.toggle(emptySet(), "nfl", FixtureTeam("Seattle Seahawks"))
        val now = start + 30 * 60_000
        val rows = SportsFixtureSections.group(listOf(tight, open, footy, football), now, utc, favourites = favourites)
        assertEquals(listOf(FixtureSection.LIVE, FixtureSection.CLOSE), rows.map { it.section })
        assertEquals("4", rows[0].fixtures.first().id)
        assertEquals(listOf("1", "3"), rows[1].fixtures.map { it.id }.sorted())
        assertEquals(listOf(FixtureSection.LIVE), SportsFixtureSections.group(listOf(tight, open), now, utc, showScores = false).map { it.section })
        assertEquals(listOf(FixtureSection.LIVE), SportsFixtureSections.group(listOf(tight), now, utc).map { it.section })
        assertTrue(SportsFavourites.has(favourites, football))
        assertFalse(SportsFavourites.has(favourites, tight))
        assertEquals(emptySet<String>(), SportsFavourites.toggle(favourites, "nfl", FixtureTeam("Seattle Seahawks")))
        assertEquals("nfl" to "Seattle Seahawks", SportsFavourites.parse("nfl:Seattle Seahawks"))
        assertNull(SportsFavourites.parse("nfl:"))
        assertNull(SportsFavourites.parse(":x"))
    }

    @Test fun periodLabelsAndFallbacks() {
        assertEquals("Q4", SportsFixtureText.periodLabel("basketball", 4))
        assertEquals("OT", SportsFixtureText.periodLabel("basketball", 5))
        assertEquals("P3", SportsFixtureText.periodLabel("ice-hockey", 3))
        assertEquals("ET", SportsFixtureText.periodLabel("soccer", 3))
        assertNull(SportsFixtureText.periodLabel("motorsport", 1))
        val half = SportsFixture("1", "afl", "australian-football", "x", null, null, 0L, FixtureStatus.LIVE, detail = "Half Time", period = 2, clock = "0:00")
        assertEquals("Half Time", SportsFixtureText.periodClock(half))
        assertEquals("Q2", SportsFixtureText.periodClock(half.copy(detail = null)))
        assertNull(SportsFixtureText.scores(half))
        assertEquals("88" to "70", SportsFixtureText.scores(half.copy(score = "88–70")))
    }

    @Test fun detailsSurviveTheCacheAndOldEntriesRefresh() {
        val fixture = EspnScoreboard.parse(nflScoreboard, nfl)[0]
        val entry = SportsCacheEntry(listOf(fixture), 99L)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
        val old = SportsFixtureCodec.decode("""{"version":1,"fetched":5,"fixtures":[{"id":"1","league":"afl","status":"LIVE","start":5}]}""")!!
        assertNull(old.fetchedAt)
        assertEquals("1", old.fixtures.single().id)
        assertTrue(SportsRefresh.due(old, 10L))
    }

    @Test fun imagesMustBeWebAddresses() {
        assertEquals("https://x.test/a.png", sportsImage(" https://x.test/a.png "))
        assertNull(sportsImage("file:///sdcard/a.png"))
        assertNull(sportsImage("https://x.test/a b.png"))
        assertNull(sportsImage("https://x.test/" + "a".repeat(1100)))
    }

    @Test fun favouritesSavedUnderAnotherServiceNameStillMatch() {
        val favourites = setOf("afl:Richmond", "epl:Arsenal FC")
        assertEquals(setOf("afl:Richmond"), SportsFavourites.matching(favourites, "afl", FixtureTeam("Richmond Tigers")))
        assertEquals(setOf("epl:Arsenal FC"), SportsFavourites.matching(favourites, "epl", FixtureTeam("Arsenal")))
        assertTrue(SportsFavourites.matching(favourites, "nrl", FixtureTeam("Richmond Tigers")).isEmpty())
        assertTrue(SportsFavourites.matching(favourites, "afl", FixtureTeam("Brisbane Lions")).isEmpty())
        assertEquals(setOf("epl:Arsenal FC"), favourites - SportsFavourites.toggle(favourites, "epl", FixtureTeam("Arsenal")))
    }
}
