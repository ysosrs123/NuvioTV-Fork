package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataEveningTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/espn-$name-real-20261008.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-08T20:55:00Z")
    private val cricket = SportsLeague("cricket-test", "Cricket", "cricket", "cricket/8676", null)

    @Test fun tennisKeepsTheTourDrawAndDropsUntimedMatches() {
        val matches = EspnScoreboard.parse(sample("atp-scoreboard"), league("atp"), now)
        assertEquals(listOf("184914", "184920", "184851"), matches.map { it.id })
        val next = matches[0]
        assertEquals("Rei Sakamoto v Andrey Rublev", next.title)
        assertEquals(FixtureStatus.SCHEDULED, next.status)
        assertEquals(millis("2026-10-09T04:00:00Z"), next.startMillis)
        assertEquals("10/9 - 12:00 AM EDT", next.detail)
        assertEquals("Shanghai, China PR", next.venue)
        val detail = next.sportDetail as SportsDetail.Tennis
        assertEquals("Rolex Shanghai Masters", detail.tournament)
        assertEquals("Round 2", detail.round)
        assertEquals("Show Court 3", detail.court)
        assertEquals(23, detail.awayPlayer.seed)
        assertEquals("Russia", detail.awayPlayer.country)
        assertNull(detail.homePlayer.seed)
        assertEquals(3, (matches[1].sportDetail as SportsDetail.Tennis).homePlayer.seed)
        val final = matches[2]
        assertEquals("Cameron Norrie v Dalibor Svrcina", final.title)
        assertEquals("1–2", final.score)
        assertEquals(FixtureLine("1", listOf("3", "6", "4")), final.homeLine)
        assertEquals(SportsBugText("NORRIE 3 6 4 / SVRCINA 6 3 6", "Final", "Round 1"), SportsFixtureText.bug(final))
        val women = EspnScoreboard.parse(sample("atp-scoreboard"), league("wta"), now).single()
        assertEquals("Zheng Qinwen v Elina Svitolina", women.title)
        assertEquals("ZHENG", women.home?.abbreviation)
        assertEquals(6, (women.sportDetail as SportsDetail.Tennis).awayPlayer.seed)
    }

    @Test fun tennisDoublesTiebreaksRetirementsAndWalkovers() {
        val doubles = EspnScoreboard.parse(sample("atp-scoreboard"), league("atp"), millis("2026-09-29T12:00:00Z")).first { it.id == "186263" }
        assertEquals("Sander Gille / Sem Verbeek v Finn Reynolds / James Watt", doubles.title)
        assertEquals("GILLE/VERBEEK", doubles.home?.abbreviation)
        assertEquals("2–1", doubles.score)
        assertEquals(TennisSet(6, 7, 4, 7, FixtureSide.AWAY), (doubles.sportDetail as SportsDetail.Tennis).sets[0])
        assertEquals(SportsBugText("GILLE/VERBEEK 6(4) 6 10 / REYNOLDS/WATT 7 3 4", "Final", "Qualifying 1st Round"), SportsFixtureText.bug(doubles))
        val retired = EspnScoreboard.parse(sample("atp-scoreboard"), league("atp"), millis("2026-10-05T20:00:00Z")).first { it.id == "183466" }
        assertEquals("Retired", retired.detail)
        assertEquals("1–0", retired.score)
        assertEquals(5, (retired.sportDetail as SportsDetail.Tennis).homePlayer.seed)
        assertEquals("DE MINAUR", retired.home?.abbreviation)
        assertEquals(SportsBugText("DE MINAUR 6 3 / HURKACZ 4 2", "Retired", "Semifinal"), SportsFixtureText.bug(retired))
        val wta = EspnScoreboard.parse(sample("wta-scoreboard"), league("wta"), millis("2026-10-08T12:00:00Z"))
        assertEquals(listOf("185294", "184347", "185307"), wta.map { it.id })
        val walkover = wta[2]
        assertEquals(FixtureStatus.FINAL, walkover.status)
        assertEquals("Walkover", walkover.detail)
        assertNull(walkover.score)
        assertNull(walkover.homeLine)
        assertTrue(EspnScoreboard.parse(sample("wta-scoreboard"), league("atp"), now).isEmpty())
    }

    @Test fun golfThruAndTodayFromTheRoundCard() {
        val event = EspnScoreboard.parse(sample("pga-scoreboard"), league("pga"), now).single()
        assertEquals(FixtureStatus.LIVE, event.status)
        assertEquals("Round 1 - Play Complete", event.detail)
        assertEquals(millis("2026-10-08T04:00:00Z"), event.startMillis)
        assertNull(event.venue)
        val golf = event.sportDetail as SportsDetail.Golf
        assertEquals(1, golf.round)
        assertNull(golf.purse)
        assertEquals(millis("2026-10-11T04:00:00Z"), golf.endMillis)
        assertEquals(listOf("1", "2", "T3", "T3", "T3", "T3", "7", "8"), golf.leaders.map { it.position })
        assertEquals(GolfPlayer("1", "Jacob Bridgeman", "J. Bridgeman", "USA", "https://a.espncdn.com/i/teamlogos/countries/500/usa.png", "-8", "F", "-8"),
            golf.leaders[0])
        assertNull(golf.leaders[3].thru)
        assertEquals("+10", golf.leaders.last().toPar)
        assertEquals(SportsBugText("J. Bridgeman -8", "Round 1 - Play Complete", "F"), SportsFixtureText.bug(event))
    }

    @Test fun motorsportWeekendAndRace() {
        val f1 = EspnScoreboard.parse(sample("f1-scoreboard"), league("f1"), now).single()
        val sessions = f1.sportDetail as SportsDetail.Sessions
        assertEquals(listOf("Practice 1", "Sprint Qualifying", "Sprint", "Qualifying", "Race"), sessions.sessions.map { it.name })
        assertEquals(millis("2026-10-11T12:00:00Z"), sessions.sessions.last().startMillis)
        assertEquals(FixtureStatus.SCHEDULED, f1.status)
        assertEquals(millis("2026-10-09T08:30:00Z"), f1.startMillis)
        assertEquals("Marina Bay Street Circuit", f1.venue)
        assertEquals(listOf("Apple TV"), f1.broadcasters)
        assertEquals(SportsBugText("Singapore Airlines Singapore Grand Prix", "Practice 1 · next", "Marina Bay Street Circuit"), SportsFixtureText.bug(f1))
        val race = EspnScoreboard.parse(sample("nascar-scoreboard"), league("nascar-cup"), now).single()
        assertEquals(listOf("Race"), (race.sportDetail as SportsDetail.Sessions).sessions.map { it.name })
        assertEquals(millis("2026-10-11T19:00:00Z"), race.startMillis)
        assertEquals(listOf("USA Net", "HBO Max"), race.broadcasters)
        assertNull(race.venue)
    }

    @Test fun ufcCardOrderAndMainCard() {
        val event = EspnScoreboard.parse(sample("ufc-scoreboard"), league("ufc"), now).single()
        val card = event.sportDetail as SportsDetail.Card
        assertEquals("UFC Fight Night: Allen vs. Duncan", event.title)
        assertEquals(millis("2026-10-10T21:00:00Z"), event.startMillis)
        assertEquals("Meta APEX", event.venue)
        assertEquals(listOf("Paramount+"), event.broadcasters)
        assertEquals(Fighter("Ernesta Kareckaite", "E. Kareckaite", "6-2-1", "https://a.espncdn.com/i/teamlogos/countries/500/ltu.png"), card.bouts[0].first)
        assertEquals("W Flyweight", card.bouts[0].weightClass)
        assertEquals(listOf(false, false, true, true), card.bouts.map { it.mainCard })
        assertEquals("Christian Leroy Duncan", card.mainEvent?.second?.name)
        assertEquals(5, card.mainEvent?.rounds)
        assertEquals(SportsBugText("B. Allen v C. Duncan", "Middleweight", "5 rounds"), SportsFixtureText.bug(event))
    }

    @Test fun cricketKeepsOnlyBattingInnings() {
        val game = EspnScoreboard.parse(sample("cricket-scoreboard"), cricket, now).single()
        assertEquals("278/8–166", game.score)
        assertEquals(listOf(CricketInnings("AUS", 278, 8, "50.0", null, null, false), CricketInnings("ENG", 166, 10, "39.1", 50, 279, true)),
            (game.sportDetail as SportsDetail.Cricket).innings)
        assertEquals(SportsBugText("AUS 278/8 · ENG 166", "Final", null), SportsFixtureText.bug(game))
        val summary = EspnSummary.parse(sample("cricket-summary"), cricket)
        assertEquals(listOf(SummaryInnings(FixtureSide.HOME, 1, 278, 8, "50", null), SummaryInnings(FixtureSide.AWAY, 2, 166, 10, "39.1", 279)), summary.innings)
        val table = summary.tables.single()
        assertEquals(listOf(FixtureSide.HOME, FixtureSide.AWAY), table.rows.mapNotNull { it.side })
        assertEquals(listOf("M" to "4", "W" to "3", "T" to "0", "L" to "0", "PTS" to "15"), table.rows.first { it.side == FixtureSide.HOME }.stats)
    }

    @Test fun aflScoringPlaysWithoutAFlagAndLadderPoints() {
        val game = EspnSummary.parse(sample("afl-summary"), league("afl"))
        assertEquals(6, game.moments.size)
        assertEquals(SummaryMoment(MomentKind.POINTS, FixtureSide.HOME, 1, "2:43", "P. Voss Behind", 1, 0), game.moments[0])
        assertEquals(MomentKind.GOAL, game.moments[1].kind)
        assertEquals(SummaryMoment(MomentKind.GOAL, FixtureSide.AWAY, 4, "30:02", "L. Morris Goal", 89, 96), game.moments.last())
        assertEquals(SummaryStat("Clearances", "45", "31"), game.stats.first { it.label == "Clearances" })
        assertEquals("12.17", game.homeLine?.breakdown)
        val row = game.tables.single().rows.first { it.side == FixtureSide.HOME }
        assertEquals(listOf("PTS" to "76", "PER" to "137.200"), row.stats)
    }

    @Test fun nrlHalvesKicksStartersAndStats() {
        val game = EspnSummary.parse(sample("nrl-summary"), league("nrl"))
        assertEquals(listOf("6", "13"), game.homeLine?.periods)
        assertEquals(listOf("12", "6"), game.awayLine?.periods)
        assertEquals(SummaryMoment(MomentKind.TRY, FixtureSide.AWAY, 1, "16'", "Try – Francis Manuleleua", 0, 4), game.moments[0])
        assertEquals(MomentKind.POINTS, game.moments[1].kind)
        assertTrue(game.moments.none { it.kind == MomentKind.OTHER || it.kind == MomentKind.SUBSTITUTION })
        val home = game.rosters.first { it.side == FixtureSide.HOME }
        assertEquals(listOf("1", "9", "13"), home.starters.map { it.jersey })
        assertEquals(listOf("15", "19", "20"), home.subs.map { it.jersey })
        val away = game.rosters.first { it.side == FixtureSide.AWAY }
        assertEquals(listOf("1", "13", "19"), away.starters.map { it.jersey })
        assertEquals("H", away.starters.last().position)
        assertEquals(listOf("Tries", "Run metres", "Runs", "Line breaks", "Offloads", "Tackles", "Missed tackles", "Penalties"), game.stats.map { it.label })
        assertEquals(SummaryStat("Run metres", "1707", "1149"), game.stats[1])
        assertEquals(listOf("W", "W"), game.headToHead.map { it.result })
    }

    @Test fun rugbyUnionBeforeKickOff() {
        val game = EspnSummary.parse(sample("urc-summary"), league("urc"))
        assertEquals(FixtureStatus.SCHEDULED, game.status)
        assertEquals(SummaryLine(null), game.homeLine)
        assertTrue(game.stats.isEmpty())
        val home = game.rosters.first { it.side == FixtureSide.HOME }
        assertEquals(listOf("1", "9", "15"), home.starters.map { it.jersey })
        assertEquals(listOf("16", "23"), home.subs.map { it.jersey })
        val rows = game.tables.single().rows
        assertEquals(listOf("GP" to "2", "W" to "1", "D" to "1", "L" to "0", "PD" to "+6", "PTS" to "8"), rows.first { it.side == FixtureSide.HOME }.stats)
        assertEquals(SummaryMeeting(millis("2026-02-28T15:00:00Z"), "15", "10", "15-10", "Connacht", "L", FixtureSide.HOME), game.headToHead[0])
    }

    @Test fun preGameSummariesUseSeasonValuesAndPoints() {
        val epl = EspnSummary.parse(sample("epl-summary"), league("epl"))
        assertEquals(listOf("GP" to "5", "W" to "4", "D" to "0", "L" to "1", "GD" to "+4", "PTS" to "12"),
            epl.tables.single().rows.first { it.side == FixtureSide.HOME }.stats)
        assertEquals(SummaryStat("Goal Difference", "4", "4"), epl.stats[0])
        assertEquals(SummaryLine(null), epl.homeLine)
        val nba = EspnSummary.parse(sample("nba-summary"), league("nba"))
        assertEquals(FixtureStatus.SCHEDULED, nba.status)
        assertTrue(nba.stats.isEmpty())
    }
}
