package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataSundayTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/espn-$name-real-20261011b.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-11T00:35:00Z")

    @Test fun baseballBetweenInningsDropsTheResetCount() {
        val game = EspnScoreboard.parse(sample("mlb-scoreboard"), league("mlb"), now).single()
        assertEquals("Cleveland Guardians v Chicago White Sox", game.title)
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals("0–0", game.score)
        assertEquals(2, game.period)
        assertEquals("End 2nd", game.detail)
        assertEquals(FixtureLine("0", listOf("0", "0")), game.homeLine)
        assertEquals("End of the 2nd inning", game.situation?.lastPlay)
        assertEquals(SportsDetail.Baseball(2, InningHalf.END), game.sportDetail)
        assertEquals(SportsBugText("CHW 0–0 CLE", "End 2"), SportsFixtureText.bug(game))
        val root = JSONObject(sample("mlb-summary"))
        val summary = EspnSummary.parse(root, "baseball")
        assertEquals(FixtureStatus.LIVE, summary.status)
        assertEquals("End 2nd", summary.detail)
        assertEquals(SummaryCount(null, null, null, false, false, false, 2, true, null, null), summary.count)
        assertEquals(listOf("End of the 2nd inning", "Bazzana struck out swinging.", "Lowe grounded out to second."), summary.lastPlays.map { it.text })
        assertEquals(listOf(0.549f, 0.535f, 0.527f), summary.winProbability)
        assertTrue(summary.moments.isEmpty())
        val plays = root.getJSONArray("plays")
        val atBat = JSONArray((0 until plays.length()).map { plays.getJSONObject(it) }.takeWhile { it.optString("text") != "Pitch 4 : Strike 3 Swinging" })
        val copy = JSONObject(root.toString()).put("plays", atBat)
        copy.remove("situation")
        copy.getJSONObject("header").getJSONArray("competitions").getJSONObject(0).getJSONObject("status").put("periodPrefix", "Bottom")
        val count = EspnSummary.parse(copy, "baseball").count!!
        assertEquals(1, count.balls)
        assertEquals(2, count.strikes)
        assertEquals(true, count.bottom)
        assertEquals("Sean Burke", count.pitcher)
        assertEquals("Travis Bazzana", count.batter)
        assertEquals("Lowe grounded out to second.", EspnSummary.parse(copy, "baseball").lastPlays[1].text)
    }

    @Test fun basketballLiveClocksLinesAndLastPlays() {
        val games = EspnScoreboard.parse(sample("nba-scoreboard"), league("nba"), now)
        val raptors = games.first { it.id == "401902645" }
        assertEquals(FixtureStatus.LIVE, raptors.status)
        assertEquals("87–69", raptors.score)
        assertEquals(3, raptors.period)
        assertEquals("1:53", raptors.clock)
        assertEquals(FixtureLine("87", listOf("24", "33", "30")), raptors.homeLine)
        assertEquals(FixtureLine("69", listOf("18", "27", "24")), raptors.awayLine)
        assertEquals(FixtureSituation(null, null, "Baba Miller makes 11-foot driving floating jump shot (Blake Wesley assists)", 98), raptors.situation)
        val heat = games.first { it.id == "401898397" }
        assertEquals("24.7", heat.clock)
        assertEquals("Q1 · 24.7", SportsFixtureText.periodClock(heat))
        assertEquals(FixtureLine("24", listOf("23")), heat.awayLine)
        assertEquals("Misses free throw 2 of 2", heat.situation?.lastPlay)
        assertEquals(31.42, SportsMarkers.minutes("basketball", heat.period, heat.clock)!!, 0.01)
        val game = EspnSummary.parse(sample("nba-summary"), league("nba"))
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals("2:12", game.clock)
        assertEquals(SummaryLine("87", listOf("24", "33", "30")), game.homeLine)
        assertEquals(SummaryLine("67", listOf("18", "27", "22")), game.awayLine)
        assertEquals(SummaryPlay("Kyle Anderson misses free throw 2 of 2", 3, "2:15"), game.lastPlays.first())
        assertEquals(listOf(0.992f, 0.99f, 0.987f), game.winProbability)
        assertEquals(millis("2026-10-11T00:29:42Z"), game.moments.last().wallclockMillis)
        assertEquals(109.0, SportsMarkers.minutes("basketball", 3, "2:12")!!, 0.1)
    }

    @Test fun collegeFootballTimeoutAndMidfield() {
        val games = EspnScoreboard.parse(sample("ncaaf-scoreboard"), league("college-football"), now)
        val timeout = games.first { it.id == "401856715" }
        assertEquals("0:20", timeout.clock)
        assertEquals(FixtureSituation(null, null, "Timeout Kentucky, clock 00:20", 32, false, 1, 1), timeout.situation)
        val midfield = games.first { it.id == "401856712" }
        assertEquals(FixtureSituation("4th & 10 at 50", FixtureSide.HOME,
            "#12 K.Russell pass incomplete short middle to #3 L.Brooks thrown to UGA34", 42, false, 3, 3), midfield.situation)
        assertEquals("4th & 10 at 50", SportsFixtureText.bug(midfield).extra)
        assertEquals(94.2, SportsMarkers.minutes("american-football", 2, "0:32", league = "college-football")!!, 2.0)
    }

    @Test fun hockeyCoincidentalMinorsAreNotAPowerPlay() {
        val root = JSONObject(sample("nhl-summary"))
        val game = EspnSummary.parse(root, "ice-hockey")
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals(3, game.period)
        assertEquals("6:07", game.clock)
        assertEquals(SummaryLine("3", listOf("2", "1", "0")), game.awayLine)
        assertEquals(4, game.moments.size)
        assertEquals(millis("2026-10-10T23:12:09Z"), game.moments.last().wallclockMillis)
        assertEquals(SummaryStrength("Power Play", FixtureSide.HOME), game.strength)
        assertEquals(SummaryPlay("Nico Sturm faceoff won against Anton Lundell", 3, "12:49"), game.lastPlays.first())
        fun upTo(count: Int): SummaryStrength? {
            val plays = root.getJSONArray("plays")
            return EspnSummary.parse(JSONObject(root.toString()).put("plays", JSONArray((0 until count).map { plays.getJSONObject(it) })), "ice-hockey").strength
        }
        assertEquals(SummaryStrength("Power Play", FixtureSide.HOME), upTo(9))
        assertNull(upTo(10))
        assertEquals(SummaryStrength("Power Play", FixtureSide.AWAY), upTo(19))
        assertNull(upTo(23))
        assertEquals(SummaryStrength("Power Play", FixtureSide.HOME), upTo(25))
    }

    @Test fun golfFinalRoundWithTheLeadersStillToTeeOff() {
        val event = EspnScoreboard.parse(sample("pga-scoreboard"), league("pga"), now).single()
        assertEquals(FixtureStatus.LIVE, event.status)
        assertEquals("Round 4 - In Progress", event.detail)
        val golf = event.sportDetail as SportsDetail.Golf
        assertEquals(4, golf.round)
        assertEquals(listOf("T1", "T1", "T1", "4", "5"), golf.leaders.map { it.position })
        val leader = golf.leaders.first()
        assertEquals("Michael Brennan", leader.name)
        assertNull(leader.thru)
        assertEquals(millis("2026-10-11T01:21:00Z"), leader.teeMillis)
        val playing = golf.leaders[3]
        assertEquals(GolfPlayer("4", "Ren Yonezawa", "R. Yonezawa", "JPN", "https://a.espncdn.com/i/teamlogos/countries/500/jpn.png", "-8", "4", "-3"), playing)
    }

    @Test fun fightCardDuringWalkouts() {
        val card = EspnScoreboard.parse(sample("ufc-scoreboard"), league("ufc"), now).single()
        assertEquals(FixtureStatus.LIVE, card.status)
        assertEquals("Pre-fight", card.detail)
        val bouts = (card.sportDetail as SportsDetail.Card).bouts
        assertEquals(listOf(FixtureStatus.FINAL, FixtureStatus.FINAL, FixtureStatus.LIVE, FixtureStatus.SCHEDULED), bouts.map { it.state })
        assertEquals(listOf(null, 1, null, null), bouts.map { it.winner })
        assertTrue(bouts.all { it.round == null })
        assertEquals("Andre Fili", bouts[2].first.name)
        assertEquals(listOf(false, false, true, true), bouts.map { it.mainCard })
    }

    @Test fun moreFootballCompetitionsAreOffered() {
        val added = mapOf("afc-champions-league" to "soccer/afc.champions", "asian-cup" to "soccer/afc.asian.cup", "nations-league" to "soccer/uefa.nations",
            "copa-libertadores" to "soccer/conmebol.libertadores", "league-one" to "soccer/eng.3", "league-two" to "soccer/eng.4", "copa-del-rey" to "soccer/esp.copa_del_rey",
            "dfb-pokal" to "soccer/ger.dfb_pokal", "coppa-italia" to "soccer/ita.coppa_italia", "coupe-de-france" to "soccer/fra.coupe_de_france",
            "belgian-pro-league" to "soccer/bel.1", "brasileirao" to "soccer/bra.1", "argentine-primera" to "soccer/arg.1", "j1-league" to "soccer/jpn.1")
        added.forEach { (id, path) -> assertEquals(id, path, league(id).espn) }
        assertTrue(added.keys.none { it in SportsLeagues.DEFAULTS })
        assertTrue(added.keys.all { league(it).sport == "soccer" && !league(it).women && league(it).sportsDbId != null })
        assertEquals(listOf("4719", "4501", "4351", "4396"), listOf("afc-champions-league", "copa-libertadores", "brasileirao", "league-one").map { league(it).sportsDbId })
        val ids = SportsLeagues.ALL.map { it.id }
        assertTrue(ids.indexOf("afc-champions-league") > ids.indexOf("conference-league") && ids.indexOf("j1-league") < ids.indexOf("wsl"))
        assertEquals("ACL", SportsGuideCells.badge("afc-champions-league"))
        assertEquals("EFL1", SportsGuideCells.badge("league-one"))
        assertEquals("J1", SportsGuideCells.badge("j1-league"))
        fun detected(title: String) = SportsGuideCells.league(listOf(LocalizedGuideText(title, null)))
        assertEquals("afc-champions-league", detected("AFC Champions League Elite: Melbourne City v Ulsan"))
        assertEquals("champions-league", detected("UEFA Champions League: Arsenal v Bayern"))
        assertEquals("brasileirao", detected("Brasileirão Série A: Flamengo v Palmeiras"))
        assertEquals("serie-a", detected("Serie A: Roma v Lazio"))
        assertEquals("league-one", detected("EFL League One: Luton v Wigan"))
        assertEquals("copa-libertadores", detected("CONMEBOL Libertadores: Final"))
    }
}
