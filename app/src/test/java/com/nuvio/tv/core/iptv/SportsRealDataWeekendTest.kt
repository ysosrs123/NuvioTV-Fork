package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataWeekendTest {
    private fun sample(name: String): String = javaClass.getResourceAsStream("/sports/espn-$name-real-20261011.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-10T19:07:00Z")

    @Test fun collegeFootballLiveSituationAndPace() {
        val games = EspnScoreboard.parse(sample("ncaaf-scoreboard"), league("college-football"), now)
        val game = games.first { it.id == "401858481" }
        assertEquals("Nebraska Cornhuskers v Indiana Hoosiers", game.title)
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals(millis("2026-10-10T16:00:00Z"), game.startMillis)
        assertEquals("17–13", game.score)
        assertEquals(4, game.period)
        assertEquals("7:43", game.clock)
        assertEquals("7:43 - 4th", game.detail)
        assertEquals(FixtureLine("17", listOf("10", "0", "0", "7")), game.homeLine)
        assertEquals(FixtureLine("13", listOf("3", "0", "3", "7")), game.awayLine)
        assertEquals(FixtureSituation("3rd & 4 · NEB 47", FixtureSide.AWAY,
            "No Huddle-Shotgun #10 J.Hoover pass incomplete short middle to #8 T.Morris thrown to NEB37 broken up by #28 C.Benning QB hurried by #24 D.Foster", 60),
            game.situation)
        val other = games.first { it.id == "401856716" }
        assertEquals(FixtureSituation("2nd & 10 · MIZ 25", FixtureSide.AWAY,
            "No Huddle-Shotgun #10 M.Reed pass incomplete short middle to #84 R.Anderson III thrown to Mizzou16 QB hurried by #10 D.Hopkins", 96), other.situation)
        val elapsed = (now - game.startMillis) / 60_000.0
        val progress = SportsMarkers.minutes("american-football", game.period, game.clock, league = "college-football")!!
        assertEquals(184.85, progress, 0.01)
        assertEquals(elapsed, progress, 4.0)
        assertTrue(SportsMarkers.minutes("american-football", game.period, game.clock, league = "nfl")!! < elapsed - 25)
    }

    @Test fun collegeFootballLiveSummaryDrivesScoringAndProbability() {
        val game = EspnSummary.parse(sample("ncaaf-summary"), league("college-football"))
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals(4, game.period)
        assertEquals("7:43", game.clock)
        assertEquals(SummaryLine("17", listOf("10", "0", "0", "7")), game.homeLine)
        assertEquals(listOf(MomentKind.POINTS, MomentKind.POINTS, MomentKind.TOUCHDOWN, MomentKind.POINTS, MomentKind.TOUCHDOWN, MomentKind.TOUCHDOWN),
            game.moments.map { it.kind })
        assertEquals(SummaryMoment(MomentKind.TOUCHDOWN, FixtureSide.AWAY, 4, "11:51", "J. Hoover pass to N. Marsh for 75 yds, for a TD (N. Radicic KICK)", 17, 13,
            millis("2026-10-10T18:49:37Z")), game.moments.last())
        assertEquals(SummaryPlay("No Huddle-Shotgun #10 J.Hoover pass incomplete short middle to #8 T.Morris thrown to NEB37 broken up by #28 C.Benning QB hurried by #24 D.Foster",
            4, "8:02"), game.lastPlays.first())
        assertTrue(game.lastPlays.none { it.text.startsWith("(") })
        assertEquals(listOf(0.5644f, 0.6009f), game.winProbability.takeLast(2))
        assertEquals(SummaryStat("Possession", "34:47", "17:11"), game.stats.first { it.label == "Possession" })
        val start = millis("2026-10-10T16:00:00Z")
        val touchdown = SportsMarkers.estimate(game.moments.last(), game.sport, start, league = "college-football")!!
        assertTrue(touchdown.exact)
        assertEquals(169.62, (touchdown.millis - start) / 60_000.0, 0.01)
        assertEquals(169.62, SportsMarkers.minutes("american-football", 4, "11:51", league = "college-football")!!, 3.0)
    }

    @Test fun hockeyPenaltyStartsAPowerPlay() {
        val live = EspnScoreboard.parse(sample("nhl-scoreboard"), league("nhl"), now).single()
        assertEquals("Boston Bruins v Philadelphia Flyers", live.title)
        assertEquals(FixtureStatus.LIVE, live.status)
        assertEquals("3–0", live.score)
        assertEquals(3, live.period)
        assertEquals("13:44", live.clock)
        assertEquals(FixtureLine("3", listOf("1", "1", "1")), live.homeLine)
        assertEquals("William Borgen Tripping against Noah Cates", live.situation?.lastPlay)
        val root = JSONObject(sample("nhl-summary"))
        val game = EspnSummary.parse(root, "ice-hockey")
        assertEquals(FixtureStatus.LIVE, game.status)
        assertEquals("13:44", game.clock)
        assertEquals(3, game.moments.size)
        assertEquals(SummaryStrength("Power Play", FixtureSide.AWAY), game.strength)
        assertEquals(SummaryPlay("William Borgen Tripping against Noah Cates", 3, "6:16"), game.lastPlays.first())
        assertEquals(listOf(SummaryGoalie(FixtureSide.AWAY, "Dan Vladar", 24, 27, ".889"), SummaryGoalie(FixtureSide.HOME, "Jeremy Swayman", 10, 10, "1.000")), game.goalies)
        assertEquals(SummaryStat("Shots", "27", "10"), game.stats.first())
        val goal = game.moments.last()
        assertEquals(millis("2026-10-10T18:57:43Z"), goal.wallclockMillis)
        assertEquals(106.0 + 1.9167 * 1.75, SportsMarkers.minutes("ice-hockey", goal.period, goal.clock, elapsed = true)!!, 0.01)
        fun upTo(count: Int, extra: JSONObject? = null): SportsSummary {
            val copy = JSONObject(root.toString())
            val plays = copy.getJSONArray("plays")
            val kept = JSONArray((0 until count).map { plays.getJSONObject(it) } + listOfNotNull(extra))
            return EspnSummary.parse(copy.put("plays", kept), "ice-hockey")
        }
        assertEquals(SummaryStrength("Power Play", FixtureSide.AWAY), upTo(2).strength)
        assertEquals(SummaryStrength("Power Play", FixtureSide.AWAY), upTo(8).strength)
        assertNull(upTo(9).strength)
        val plays = root.getJSONArray("plays")
        val penalty = plays.getJSONObject(plays.length() - 1)
        val coincidental = JSONObject(penalty.toString()).put("id", "x").put("team", JSONObject().put("id", "15"))
        assertNull(upTo(plays.length(), coincidental).strength)
        val misconduct = JSONObject(penalty.toString()).put("type", JSONObject(penalty.getJSONObject("type").toString()).put("penaltyType", "Misconduct").put("penaltyMinutes", "10"))
        assertNull(upTo(plays.length() - 1, misconduct).strength)
    }

    @Test fun rugbyLiveClockStuckAtTheStartIsDropped() {
        val games = EspnScoreboard.parse(sample("urc-scoreboard"), league("urc"), now)
        val live = games.first { it.id == "604055" }
        assertEquals("Leinster v Cardiff Blues", live.title)
        assertEquals(FixtureStatus.LIVE, live.status)
        assertEquals("15–0", live.score)
        assertEquals(1, live.period)
        assertNull(live.clock)
        assertEquals("First Half", live.detail)
        assertEquals(FixtureLine("15"), live.homeLine)
        assertEquals(FixtureLine("0"), live.awayLine)
        assertEquals(listOf(FixtureEventKind.TRY, FixtureEventKind.YELLOW, FixtureEventKind.TRY, FixtureEventKind.TRY), live.events.map { it.kind })
        assertEquals("19'", live.events.last().clock)
        assertEquals(20.0, SportsMarkers.minutes("rugby", live.period, live.clock)!!, 0.001)
        val final = games.first { it.id == "604054" }
        assertEquals("26–26", final.score)
        assertEquals(FixtureLine("26", listOf("5", "21")), final.homeLine)
        assertEquals(FixtureLine("26", listOf("26", "0")), final.awayLine)
        assertEquals("FT", final.detail)
        assertNull(final.clock)
        val game = EspnSummary.parse(sample("urc-summary"), league("urc"))
        assertEquals(FixtureStatus.LIVE, game.status)
        assertNull(game.clock)
        assertEquals("First Half", game.detail)
        assertEquals(SummaryLine("15", listOf("15")), game.homeLine)
        assertEquals(SummaryLine("0", listOf("0")), game.awayLine)
        assertEquals(listOf("Try – Hugo Keenan", "Yellow card – Aled Davies", "Try – Jimmy O'Brien", "Try – Jack Conan"), game.moments.map { it.text })
        assertEquals(15, game.moments.last().homeScore)
        assertTrue(SportsLines.behind("1'", listOf("9'", "19'")))
        assertFalse(SportsLines.behind("50'", listOf("45'+5'", "48'")))
        assertFalse(SportsLines.behind("7:43", listOf("9'")))
        assertEquals(emptyList<String>(), SportsLines.perPeriod(listOf("0", "0"), "15"))
        assertEquals(listOf("0", "0"), SportsLines.perPeriod(listOf("0", "0"), "0"))
    }

    @Test fun footballFinalGoalsSubstitutionsAndStoppageTime() {
        val game = EspnSummary.parse(sample("epl-summary"), league("epl"))
        assertEquals(FixtureStatus.FINAL, game.status)
        assertEquals("FT", game.detail)
        assertEquals(SummaryLine("2", listOf("0", "2")), game.homeLine)
        assertEquals(SummaryLine("1", listOf("0", "1")), game.awayLine)
        assertEquals(mapOf(MomentKind.CARD_YELLOW to 5, MomentKind.GOAL to 3, MomentKind.SUBSTITUTION to 10), game.moments.groupingBy { it.kind }.eachCount())
        val goals = game.moments.filter { it.kind == MomentKind.GOAL }
        assertEquals(listOf("James Justin Goal", "Riccardo Calafiori Goal", "Bruno Guimarães Goal"), goals.map { it.text })
        assertEquals(listOf(0 to 1, 1 to 1, 2 to 1), goals.map { it.homeScore to it.awayScore })
        assertEquals(listOf(FixtureSide.AWAY, FixtureSide.HOME, FixtureSide.HOME), goals.map { it.side })
        val sub = game.moments.first { it.kind == MomentKind.SUBSTITUTION }
        assertEquals(SummaryMoment(MomentKind.SUBSTITUTION, FixtureSide.HOME, 2, "63'", "Substitution, Arsenal. Jurriën Timber replaces Ben White.",
            wallclockMillis = millis("2026-10-10T12:52:20Z")), sub)
        val start = millis("2026-10-10T11:30:00Z")
        assertEquals(74.25, (SportsMarkers.estimate(goals[0], "soccer", start)!!.millis - start) / 60_000.0, 0.01)
        assertEquals(62.0, SportsMarkers.minutes("soccer", 2, "45'")!!, 0.001)
        assertEquals(48.0, SportsMarkers.minutes("soccer", 1, "45'+3'")!!, 0.001)
    }

    @Test fun tennisWalkoverWithAPlaceholderDateIsLeftOut() {
        val draw = sample("atp-scoreboard")
        assertEquals(listOf("184980"), EspnScoreboard.parse(draw, league("atp"), now).map { it.id })
        val earlier = EspnScoreboard.parse(draw, league("atp"), millis("2026-10-09T09:00:00Z"))
        assertEquals(listOf("184927", "184980"), earlier.map { it.id }.sorted())
        val walkover = earlier.first { it.id == "184927" }
        assertEquals(FixtureStatus.FINAL, walkover.status)
        assertEquals("Walkover", walkover.detail)
        assertNull(walkover.score)
    }

    @Test fun moreParsedLeaguesAreOffered() {
        val added = mapOf("ligue-1" to "soccer/fra.1", "europa-league" to "soccer/uefa.europa", "conference-league" to "soccer/uefa.europa.conf",
            "championship" to "soccer/eng.2", "fa-cup" to "soccer/eng.fa", "efl-cup" to "soccer/eng.league_cup", "scottish-premiership" to "soccer/sco.1",
            "eredivisie" to "soccer/ned.1", "primeira-liga" to "soccer/por.1", "super-lig" to "soccer/tur.1", "saudi-pro-league" to "soccer/ksa.1",
            "mls" to "soccer/usa.1", "liga-mx" to "soccer/mex.1", "wsl" to "soccer/eng.w.1", "nwsl" to "soccer/usa.nwsl",
            "womens-champions-league" to "soccer/uefa.wchampions", "g-league" to "basketball/nba-development",
            "womens-college-basketball" to "basketball/womens-college-basketball", "cfl" to "football/cfl", "ufl" to "football/ufl",
            "college-hockey" to "hockey/mens-college-hockey", "womens-college-hockey" to "hockey/womens-college-hockey", "pfl" to "mma/pfl",
            "lpga" to "golf/lpga", "dp-world-tour" to "golf/eur", "liv" to "golf/liv", "nascar-xfinity" to "racing/nascar-secondary",
            "nascar-truck" to "racing/nascar-truck", "indycar" to "racing/irl", "premiership-rugby" to "rugby/267979", "top-14" to "rugby/270559",
            "champions-cup" to "rugby/271937", "six-nations" to "rugby/180659", "rugby-championship" to "rugby/244293")
        added.forEach { (id, path) -> assertEquals(id, path, league(id).espn) }
        assertTrue(added.keys.none { it in SportsLeagues.DEFAULTS })
        assertTrue(listOf("wsl", "nwsl", "womens-champions-league", "womens-college-basketball", "womens-college-hockey", "lpga").all { league(it).women })
        assertEquals(listOf("4334", "4481", "5071", "4329"), listOf("ligue-1", "europa-league", "conference-league", "championship").map { league(it).sportsDbId })
        val ids = SportsLeagues.ALL.map { it.id }
        assertTrue(ids.indexOf("ligue-1") > ids.indexOf("bundesliga") && ids.indexOf("womens-champions-league") < ids.indexOf("nba"))
        assertTrue(ids.indexOf("top-14") > ids.indexOf("urc"))
        assertEquals("UEL", SportsGuideCells.badge("europa-league"))
        assertEquals("T14", SportsGuideCells.badge("top-14"))
        assertEquals("MLS", SportsGuideCells.badge("mls"))
        assertEquals("europa-league", SportsGuideCells.league(listOf(LocalizedGuideText("UEFA Europa League: Roma v Porto", null))))
        assertEquals("conference-league", SportsGuideCells.league(listOf(LocalizedGuideText("UEFA Conference League: Chelsea v Legia", null))))
        assertEquals("urc", SportsGuideCells.league(listOf(LocalizedGuideText("United Rugby Championship: Leinster v Cardiff", null))))
        assertEquals("rugby-championship", SportsGuideCells.league(listOf(LocalizedGuideText("The Rugby Championship: Australia v New Zealand", null))))
        assertEquals("champions-league", SportsGuideCells.league(listOf(LocalizedGuideText("UEFA Champions League: Arsenal v Bayern", null))))
        assertEquals(29.0, SportsMarkers.minutes("basketball", 2, "10:00", league = "womens-college-basketball")!!, 0.001)
    }
}
