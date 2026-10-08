package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsDetailTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/$name")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-08T08:00:00Z")

    @Test fun newLeaguesAreListedButNotDefaults() {
        assertEquals("tennis/atp", league("atp").espn)
        assertEquals("tennis/wta", league("wta").espn)
        assertTrue(league("wta").women)
        assertEquals("golf/pga", league("pga").espn)
        assertEquals("racing/nascar-premier", league("nascar-cup").espn)
        assertEquals("rugby/270557", league("urc").espn)
        assertEquals(setOf("afl", "nrl", "a-league-men", "epl", "champions-league"), SportsLeagues.DEFAULTS)
    }

    @Test fun tennisMatchesFromGroupingsWithSetsSeedsAndServer() {
        val matches = EspnScoreboard.parse(sample("espn-wta-scoreboard-synthetic.json"), league("wta"), now)
        assertEquals(listOf("9101", "9201", "9102", "9103"), matches.map { it.id })
        val live = matches[0]
        assertEquals(FixtureStatus.LIVE, live.status)
        assertEquals("tennis", live.sport)
        assertEquals("Jana Varga v Mia Okafor", live.title)
        assertEquals("VARGA", live.home?.abbreviation)
        assertEquals("1–1", live.score)
        assertEquals(FixtureLine("1", listOf("7", "3", "5")), live.homeLine)
        assertEquals("3rd Set", live.detail)
        assertEquals("Beijing, China", live.venue)
        val detail = live.sportDetail as SportsDetail.Tennis
        assertEquals("China Open", detail.tournament)
        assertEquals("Quarterfinal", detail.round)
        assertEquals("Diamond Court", detail.court)
        assertEquals(TennisPlayer(4, "https://a.espncdn.com/i/teamlogos/countries/500/hun.png", "Hungary"), detail.homePlayer)
        assertNull(detail.awayPlayer.seed)
        assertEquals(TennisSet(7, 6, 7, 4, FixtureSide.HOME), detail.sets[0])
        assertEquals(TennisSet(5, 4), detail.sets[2])
        assertEquals(FixtureSide.HOME, detail.server)
        assertTrue(detail.servingForSet)
        assertEquals(SportsBugText("VARGA 7 3 5 / OKAFOR 6(4) 6 4", "3rd Set", "VARGA serving for the set"), SportsFixtureText.bug(live))
        val doubles = matches[1]
        assertEquals("F. Reynolds / J. Watt", doubles.home?.name)
        assertEquals("REYNOLDS/WATT", doubles.home?.abbreviation)
        assertEquals(FixtureSide.AWAY, (doubles.sportDetail as SportsDetail.Tennis).server)
        assertFalse((doubles.sportDetail as SportsDetail.Tennis).servingForSet)
        assertEquals(FixtureStatus.SCHEDULED, matches[2].status)
        assertNull(matches[2].score)
        assertEquals(SportsBugText("LIND v RUIZ", "Quarterfinal", "China Open"), SportsFixtureText.bug(matches[2]))
        assertEquals(SportsBugText("BRANDT 6 6 / NOVAK 2 3", "Final", "Quarterfinal"), SportsFixtureText.bug(matches[3]))
        val atp = EspnScoreboard.parse(sample("espn-atp-scoreboard-synthetic.json"), league("atp"), now).single()
        assertNull((atp.sportDetail as SportsDetail.Tennis).server)
        assertEquals(SportsBugText("BERG 6 2 / FERRI 4 3", "2nd Set", null), SportsFixtureText.bug(atp))
    }

    @Test fun tennisCapsMatches() {
        val match = """{"id":"%d","date":"2026-10-08T%02d:00Z","status":{"type":{"state":"pre"}},
            "competitors":[{"homeAway":"home","athlete":{"displayName":"A Player"}},{"homeAway":"away","athlete":{"displayName":"B Player"}}]}"""
        val json = """{"events":[{"id":"1","name":"Open","groupings":[{"competitions":[${(1..80).joinToString(",") { match.format(it, it % 24) }}]}]}]}"""
        assertEquals(EspnScoreboard.MAX_MATCHES, EspnScoreboard.parse(json, league("atp"), now).size)
    }

    @Test fun servingForSetNeedsAGameToWin() {
        fun tennis(home: Int, away: Int, server: FixtureSide) =
            SportsDetail.Tennis(null, null, null, TennisPlayer(), TennisPlayer(), listOf(TennisSet(home, away)), server)
        assertTrue(tennis(5, 3, FixtureSide.HOME).servingForSet)
        assertFalse(tennis(5, 5, FixtureSide.HOME).servingForSet)
        assertTrue(tennis(6, 5, FixtureSide.HOME).servingForSet)
        assertFalse(tennis(6, 6, FixtureSide.HOME).servingForSet)
        assertTrue(tennis(3, 5, FixtureSide.AWAY).servingForSet)
        assertFalse(tennis(5, 3, FixtureSide.AWAY).servingForSet)
    }

    @Test fun golfLeaderboardWithTiesPurseAndRound() {
        val event = EspnScoreboard.parse(sample("espn-pga-scoreboard-synthetic.json"), league("pga"), now).single()
        assertEquals("Baycurrent Classic", event.title)
        assertNull(event.home)
        assertEquals(FixtureStatus.LIVE, event.status)
        assertEquals(listOf("Golf Chnl"), event.broadcasters)
        val golf = event.sportDetail as SportsDetail.Golf
        assertEquals(1, golf.round)
        assertEquals("Round 1 - Play Complete", golf.statusText)
        assertEquals("$8,000,000", golf.purse)
        assertEquals(millis("2026-10-11T04:00:00Z"), golf.endMillis)
        assertEquals(10, golf.leaders.size)
        assertEquals(listOf("1", "T2", "T2", "4", "T5", "T5", "7"), golf.leaders.take(7).map { it.position })
        assertEquals(GolfPlayer("1", "Sam Ito", "S. Ito", "Japan", "https://a.espncdn.com/i/teamlogos/countries/500/jpn.png", "-8"), golf.leaders[0])
        assertEquals("E", golf.leaders[3].toPar)
        assertEquals(SportsBugText("S. Ito -8", "Round 1 - Play Complete", null), SportsFixtureText.bug(event))
    }

    @Test fun formulaOneWeekendFollowsCurrentSession() {
        val event = EspnScoreboard.parse(sample("espn-f1-scoreboard-synthetic.json"), league("f1"), now).single()
        val sessions = event.sportDetail as SportsDetail.Sessions
        assertEquals(listOf("FP1", "SS", "SR", "Qual", "Race"), sessions.sessions.map { it.abbreviation })
        assertEquals(listOf("Practice 1", "Sprint Qualifying", "Sprint", "Qualifying", "Race"), sessions.sessions.map { it.name })
        assertEquals("Marina Bay Street Circuit", event.venue)
        assertEquals(FixtureStatus.LIVE, event.status)
        assertEquals(millis("2026-10-10T13:00:00Z"), event.startMillis)
        assertEquals("Singapore Grand Prix", event.title)
        assertEquals(listOf("Apple TV"), event.broadcasters)
        assertEquals(SportsBugText("Singapore Grand Prix", "Qualifying · live", "Marina Bay Street Circuit"), SportsFixtureText.bug(event))
        val race = EspnScoreboard.parse(sample("espn-nascar-scoreboard-synthetic.json"), league("nascar-cup"), now).single()
        assertEquals("NASCAR Cup Series at Charlotte", race.title)
        assertEquals(FixtureStatus.SCHEDULED, race.status)
        assertEquals(listOf("USA Net", "HBO Max"), race.broadcasters)
        assertEquals(SportsBugText("NASCAR Cup Series at Charlotte", "Race · next", null), SportsFixtureText.bug(race))
    }

    @Test fun ufcCardSplitsMainCardAndPrelims() {
        val event = EspnScoreboard.parse(sample("espn-ufc-scoreboard-synthetic.json"), league("ufc"), now).single()
        val card = event.sportDetail as SportsDetail.Card
        assertEquals(12, card.bouts.size)
        assertEquals(millis("2026-10-10T21:00:00Z"), event.startMillis)
        assertEquals(FixtureStatus.LIVE, event.status)
        assertEquals("Meta APEX", event.venue)
        assertEquals("Brendan Allen", card.mainEvent?.first?.name)
        assertEquals(5, card.mainEvent?.rounds)
        assertEquals(5, card.mainCard.size)
        assertEquals(7, card.prelims.size)
        assertEquals(1, card.bouts[0].winner)
        assertEquals("27-7-0", card.bouts[0].first.record)
        assertEquals("Hal Ives", card.bouts[3].second.name)
        assertEquals(2, card.live?.round)
        assertEquals(SportsBugText("Ford v Gray", "Round 2 of 3", "Welterweight"), SportsFixtureText.bug(event))
        val scheduled = event.copy(status = FixtureStatus.SCHEDULED, sportDetail = card.copy(bouts = card.bouts.map { it.copy(state = FixtureStatus.SCHEDULED) }))
        assertEquals(SportsBugText("Allen v Duncan", "Middleweight", "5 rounds"), SportsFixtureText.bug(scheduled))
    }

    @Test fun cricketInningsAndChase() {
        val cricket = SportsLeague("cricket-test", "Cricket", "cricket", "cricket/8676", null)
        val live = EspnScoreboard.parse(sample("espn-cricket-live-scoreboard-synthetic.json"), cricket, now).single()
        val detail = live.sportDetail as SportsDetail.Cricket
        assertEquals(listOf(CricketInnings("AUS", 278, 8, null, null, null, false), CricketInnings("ENG", 166, 7, "39.1", 50, 279, true)), detail.innings)
        assertEquals(113, detail.chase?.runs)
        assertEquals(65, detail.chase?.balls)
        assertEquals(10.43, detail.chase!!.rate, 0.01)
        assertEquals(SportsBugText("AUS 278/8 · ENG 166/7", "39.1 ov", "Need 113 from 65"), SportsFixtureText.bug(live))
        val final = EspnScoreboard.parse(sample("espn-cricket-scoreboard.json"), cricket, now).single()
        val innings = (final.sportDetail as SportsDetail.Cricket).innings
        assertEquals(10, innings.last().wickets)
        assertNull((final.sportDetail as SportsDetail.Cricket).chase)
        assertEquals("AUS 278/8 · ENG 166", SportsFixtureText.bug(final).primary)
        assertEquals(395, SportsDetails.balls("65.5"))
        assertNull(SportsDetails.balls("4.7"))
    }

    @Test fun baseballSituation() {
        val game = EspnScoreboard.parse(sample("espn-mlb-live-scoreboard-synthetic.json"), league("mlb"), now).single()
        assertEquals(SportsDetail.Baseball(7, InningHalf.TOP, 1, 2, 1, first = true, second = false, third = true), game.sportDetail)
        assertEquals("Ramirez singled to left.", game.situation?.lastPlay)
        assertEquals(SportsBugText("CLE 9–3 CHW", "▲7", "1 out · 2–1"), SportsFixtureText.bug(game))
    }

    @Test fun teamBugs() {
        val arsenal = FixtureTeam("Arsenal", null, "ARS")
        val leeds = FixtureTeam("Leeds United", "Leeds", "LEE")
        val football = SportsFixture("1", "epl", "soccer", "Arsenal v Leeds United", arsenal, leeds, 0L, FixtureStatus.LIVE, "1–0", "63'", period = 2, clock = "63'")
        assertEquals(SportsBugText("ARS 1–0 LEE", "63'", null), SportsFixtureText.bug(football))
        assertEquals(SportsBugText("ARS v LEE"), SportsFixtureText.bug(football.copy(status = FixtureStatus.SCHEDULED, score = null)))
        val nfl = SportsFixture("2", "nfl", "american-football", "x", FixtureTeam("Dallas Cowboys", abbreviation = "DAL"), FixtureTeam("Tampa Bay Buccaneers", abbreviation = "TB"),
            0L, FixtureStatus.LIVE, "14–10", period = 2, clock = "4:12", situation = FixtureSituation("2nd & 7 · TB 38"))
        assertEquals(SportsBugText("TB 10–14 DAL", "Q2 · 4:12", "2nd & 7 · TB 38"), SportsFixtureText.bug(nfl))
        val afl = SportsFixture("3", "afl", "australian-football", "x", FixtureTeam("Sydney Swans", abbreviation = "SYD"), FixtureTeam("Geelong Cats", abbreviation = "GEEL"),
            0L, FixtureStatus.FINAL, "62–55", "Final")
        assertEquals(SportsBugText("SYD 62–55 GEEL", "Final", null), SportsFixtureText.bug(afl))
        assertEquals(SportsBugText("Some Event"), SportsFixtureText.bug(SportsFixture("4", "f1", "motorsport", "Some Event", null, null, 0L, FixtureStatus.SCHEDULED)))
    }

    @Test fun detailsSurviveTheCache() {
        val all = listOf(
            EspnScoreboard.parse(sample("espn-wta-scoreboard-synthetic.json"), league("wta"), now),
            EspnScoreboard.parse(sample("espn-pga-scoreboard-synthetic.json"), league("pga"), now),
            EspnScoreboard.parse(sample("espn-f1-scoreboard-synthetic.json"), league("f1"), now),
            EspnScoreboard.parse(sample("espn-ufc-scoreboard-synthetic.json"), league("ufc"), now),
            EspnScoreboard.parse(sample("espn-cricket-live-scoreboard-synthetic.json"), SportsLeague("cricket-test", "Cricket", "cricket", "cricket/8676", null), now),
            EspnScoreboard.parse(sample("espn-mlb-live-scoreboard-synthetic.json"), league("mlb"), now)).flatten()
        assertEquals(9, all.size)
        assertTrue(all.all { it.sportDetail != null })
        val entry = SportsCacheEntry(all, 5L)
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
        val old = """{"version":2,"fetched":5,"fixtures":[{"id":"1","league":"epl","status":"FINAL","start":1,"sportDetail":{"v":9,"type":"hologram"}},
            {"id":"2","league":"epl","status":"LIVE","start":2}]}"""
        val decoded = SportsFixtureCodec.decode(old)!!
        assertEquals(listOf("1", "2"), decoded.fixtures.map { it.id })
        assertTrue(decoded.fixtures.all { it.sportDetail == null })
    }

    @Test fun eventsLinkByTournamentOrLeagueNotTeams() {
        val start = millis("2026-10-10T13:00:00Z")
        fun programme(title: String, from: Long) = GuideProgramme("c", GuideTimestamp(from, 14, ""), GuideTimestamp(from + 2 * 3_600_000L, 14, ""),
            listOf(LocalizedGuideText(title, "en")), emptyList())
        val f1 = EspnScoreboard.parse(sample("espn-f1-scoreboard-synthetic.json"), league("f1"), now).single()
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, SportsFixtureMatching.guideMatch(f1, programme("Formula 1: Singapore Grand Prix - Qualifying", start), start))
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, SportsFixtureMatching.guideMatch(f1, programme("F1 Singapore GP Race", millis("2026-10-11T12:00:00Z")), start))
        assertEquals(FixtureLinkReason.GUIDE_LEAGUE, SportsFixtureMatching.guideMatch(f1, programme("Formula 1 Qualifying", start), start))
        assertNull(SportsFixtureMatching.guideMatch(f1, programme("Formula 1: Japanese Grand Prix", start), start))
        assertNull(SportsFixtureMatching.guideMatch(f1, programme("Formula 1: Singapore Grand Prix", millis("2026-10-13T12:00:00Z")), start))
        val wta = EspnScoreboard.parse(sample("espn-wta-scoreboard-synthetic.json"), league("wta"), now)[0]
        val at = wta.startMillis
        assertEquals(FixtureLinkReason.GUIDE_LEAGUE, SportsFixtureMatching.guideMatch(wta, programme("WTA Tennis: China Open", at), at))
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, SportsFixtureMatching.guideMatch(wta, programme("Tennis: Varga v Okafor", at), at))
        assertNull(SportsFixtureMatching.guideMatch(wta, programme("ATP Tennis: Shanghai Masters", at), at))
        assertNull(SportsFixtureMatching.guideMatch(wta, programme("Varga v Okafor", at + 6 * 3_600_000L), at))
        val golf = EspnScoreboard.parse(sample("espn-pga-scoreboard-synthetic.json"), league("pga"), now).single()
        val sunday = millis("2026-10-11T18:00:00Z")
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, SportsFixtureMatching.guideMatch(golf, programme("PGA Tour Golf: Baycurrent Classic, Final Round", sunday), sunday))
        assertNull(SportsFixtureMatching.guideMatch(golf, programme("LPGA Tour: Buick Championship", sunday), sunday))
        val ufc = EspnScoreboard.parse(sample("espn-ufc-scoreboard-synthetic.json"), league("ufc"), now).single()
        val main = millis("2026-10-11T00:00:00Z")
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, SportsFixtureMatching.guideMatch(ufc, programme("UFC Fight Night: Allen vs Duncan", main), main))
        assertEquals(FixtureLinkReason.GUIDE_LEAGUE, SportsFixtureMatching.guideMatch(ufc, programme("UFC Fight Night Prelims", main - 3 * 3_600_000L), main))
        assertNull(SportsFixtureMatching.guideMatch(ufc, programme("UFC 321: Main Card", main), main))
        val nascar = EspnScoreboard.parse(sample("espn-nascar-scoreboard-synthetic.json"), league("nascar-cup"), now).single()
        assertEquals(FixtureLinkReason.GUIDE_LEAGUE, SportsFixtureMatching.guideMatch(nascar, programme("NASCAR Cup Series: Bank of America Roval 400", nascar.startMillis),
            nascar.startMillis))
    }
}
