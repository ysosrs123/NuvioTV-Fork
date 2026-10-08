package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SportsFixturesRealDataTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/$name")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun nrlHalfScoresAreCumulativeAndFormIsNotARecord() {
        val game = EspnScoreboard.parse(sample("espn-nrl-scoreboard.json"), league("nrl")).single()
        assertEquals("Roosters v Knights", game.title)
        assertEquals(FixtureStatus.FINAL, game.status)
        assertEquals("19–18", game.score)
        assertEquals(FixtureLine("19", listOf("6", "13")), game.homeLine)
        assertEquals(FixtureLine("18", listOf("12", "6")), game.awayLine)
        assertNull(game.home?.record)
        assertEquals("Accor Stadium", game.venue)
        assertEquals(4, game.round)
        assertEquals("https://a.espncdn.com/i/teamlogos/leagues/500/nrl.png", game.leagueLogo)
        assertTrue(game.home?.logo.orEmpty().startsWith("https://a.espncdn.com/"))
    }

    @Test fun aflQuartersRecordsAndRound() {
        val game = EspnScoreboard.parse(sample("espn-afl-scoreboard.json"), league("afl")).single()
        assertEquals("Fremantle v Brisbane Lions", game.title)
        assertEquals(FixtureLine("89", listOf("22", "21", "29", "17")), game.homeLine)
        assertEquals(FixtureLine("96", listOf("23", "20", "19", "34")), game.awayLine)
        assertEquals("19-4", game.home?.record)
        assertEquals("5d368f", game.home?.colour)
        assertEquals("MCG", game.venue)
        assertEquals(5, game.round)
        assertNull(game.period)
        assertNull(game.clock)
    }

    @Test fun nflScheduledGamesKeepBroadcastersAndNoScores() {
        val games = EspnScoreboard.parse(sample("espn-nfl-scoreboard.json"), league("nfl"))
        assertEquals(3, games.size)
        val first = games[0]
        assertEquals("Dallas Cowboys v Tampa Bay Buccaneers", first.title)
        assertEquals(FixtureStatus.SCHEDULED, first.status)
        assertEquals(millis("2026-10-09T00:15:00Z"), first.startMillis)
        assertEquals(listOf("Prime Video"), first.broadcasters)
        assertEquals("AT&T Stadium", first.venue)
        assertEquals(5, first.round)
        assertEquals("2-2", first.home?.record)
        assertEquals("DAL", first.home?.abbreviation)
        assertNull(first.score)
        assertNull(first.homeLine)
        assertNull(first.situation)
    }

    @Test fun soccerScheduledFixtures() {
        val epl = EspnScoreboard.parse(sample("espn-epl-scoreboard.json"), league("epl"))
        assertEquals("Arsenal v Leeds United", epl[0].title)
        assertEquals(listOf("USA Net", "Universo"), epl[0].broadcasters)
        assertEquals("4-0-1", epl[0].home?.record)
        assertNull(epl[0].round)
        assertNull(epl[0].clock)
        val aleague = EspnScoreboard.parse(sample("espn-aleague-scoreboard.json"), league("a-league-men")).single()
        assertEquals("Sydney FC v Western Sydney Wanderers", aleague.title)
        assertEquals(listOf("ESPN+"), aleague.broadcasters)
        assertEquals("Allianz Stadium", aleague.venue)
        assertEquals("0-0-0", aleague.home?.record)
    }

    @Test fun nbaFinalLinescores() {
        val game = EspnScoreboard.parse(sample("espn-nba-scoreboard.json"), league("nba"))[0]
        assertEquals("Indiana Pacers v Minnesota Timberwolves", game.title)
        assertEquals(FixtureStatus.FINAL, game.status)
        assertEquals(FixtureLine("123", listOf("32", "29", "38", "24")), game.homeLine)
        assertEquals(listOf("NBA TV"), game.broadcasters)
        assertEquals("Final", game.detail)
    }

    @Test fun cricketInningsAreNotPeriods() {
        val cricket = SportsLeague("cricket-test", "Cricket", "cricket", "cricket/8676", null)
        val game = EspnScoreboard.parse(sample("espn-cricket-scoreboard.json"), cricket).single()
        assertEquals("Australia v England", game.title)
        assertEquals(emptyList<String>(), game.homeLine?.periods)
        assertEquals(emptyList<String>(), game.awayLine?.periods)
        assertNull(game.home?.record)
        assertEquals("f3f702", game.home?.colour)
    }

    @Test fun periodTotalsOnlyConvertWhenCumulative() {
        assertEquals(listOf("6", "13"), SportsLines.perPeriod(listOf("6", "19"), "19"))
        assertEquals(listOf("0", "1"), SportsLines.perPeriod(listOf("0", "1"), "1"))
        assertEquals(listOf("3", "3"), SportsLines.perPeriod(listOf("3", "3"), "6"))
        assertEquals(listOf("7", "5"), SportsLines.perPeriod(listOf("7", "5"), "9"))
        assertEquals(listOf("1", "x"), SportsLines.perPeriod(listOf("1", "x"), "1"))
    }

    @Test fun sportsDbFreeKeyEvents() {
        val now = millis("2026-10-08T10:00:00Z")
        val past = SportsDbEvents.parse(sample("tsdb-event.json"), league("epl"), now).single()
        assertEquals("Fulham v Manchester United", past.title)
        assertEquals(FixtureStatus.FINAL, past.status)
        assertEquals("1–1", past.score)
        assertEquals(millis("2026-09-20T15:30:00Z"), past.startMillis)
        assertEquals("Craven Cottage", past.venue)
        assertEquals(5, past.round)
        assertTrue(past.broadcasters.isEmpty())
        assertTrue(past.home?.logo.orEmpty().startsWith("https://r2.thesportsdb.com/"))
        assertTrue(past.leagueLogo.orEmpty().startsWith("https://r2.thesportsdb.com/"))
        val next = SportsDbEvents.parse(sample("tsdb-next-epl.json"), league("epl"), now).single()
        assertEquals(FixtureStatus.SCHEDULED, next.status)
        assertEquals(millis("2026-10-10T11:30:00Z"), next.startMillis)
        assertNull(next.score)
        assertTrue(SportsDbEvents.parse(sample("tsdb-eventsday-soccer.json"), league("epl"), now).isEmpty())
        assertTrue(SportsDbEvents.parse(sample("tsdb-eventsday-afl.json"), league("afl"), now).isEmpty())
    }
}
