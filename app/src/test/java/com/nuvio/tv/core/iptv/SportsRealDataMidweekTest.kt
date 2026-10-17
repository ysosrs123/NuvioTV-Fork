package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataMidweekTest {
    private fun sample(name: String, day: String): String =
        javaClass.getResourceAsStream("/sports/$name-real-$day.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()

    private fun fixture(id: String, sport: String, start: String, league: String) = SportsFixture(id, league, sport, "Home v Away", FixtureTeam("Home"),
        FixtureTeam("Away"), millis(start), FixtureStatus.SCHEDULED, source = SportsService.THESPORTSDB)

    private fun merged(day: String, now: String, base: SportsFixture): SportsFixture =
        SportsDbLive.merge(listOf(base), SportsDbLive.parse(sample("tsdb-livescore-all", day)).associateBy { it.eventId }, millis(now)).single()

    private fun merged(day: String, now: String, id: String, sport: String, start: String, league: String) = merged(day, now, fixture(id, sport, start, league))

    private fun plays(root: JSONObject, count: Int): JSONObject {
        val all = root.getJSONArray("plays")
        return JSONObject(root.toString()).put("plays", JSONArray((0 until count).map { all.getJSONObject(it) }))
    }

    @Test fun footballLiveSubstitutionsReadTheCommentary() {
        val root = JSONObject(sample("espn-ucl-summary", "20261014"))
        val summary = EspnSummary.parse(root, "soccer")
        assertEquals(FixtureStatus.LIVE, summary.status)
        assertEquals(2 to "66'", summary.period to summary.clock)
        val subs = summary.moments.filter { it.kind == MomentKind.SUBSTITUTION }
        assertEquals(listOf("Substitution, Arsenal. Bukayo Saka replaces Noni Madueke.", "Substitution, Arsenal. Martin Ødegaard replaces Mikel Merino.",
            "Substitution, Arsenal. Kai Havertz replaces Viktor Gyökeres."), subs.map { it.text })
        assertTrue(subs.all { it.side == FixtureSide.HOME && it.period == 2 && it.clock == "65'" && it.wallclockMillis != null })
        val goal = summary.moments.single { it.kind == MomentKind.GOAL }
        assertEquals(listOf<Any?>("Jurriën Timber Goal", FixtureSide.HOME, "64'", 1, 0), listOf(goal.text, goal.side, goal.clock, goal.homeScore, goal.awayScore))
        assertEquals(millis("2026-10-13T20:21:05Z"), goal.wallclockMillis)
        assertEquals("Mikel Merino Yellow Card", summary.moments.single { it.kind == MomentKind.CARD_YELLOW }.text)
        val bare = JSONObject(root.toString()).apply { remove("commentary") }
        assertEquals("Bukayo Saka (Arsenal) Substitution at 65'", EspnSummary.parse(bare, "soccer").moments.first { it.kind == MomentKind.SUBSTITUTION }.text)
    }

    @Test fun ownGoalsOnTheScoreboardSayWhoScoredThem() {
        val games = EspnScoreboard.parse(sample("espn-ucl-scoreboard", "20261015"), league("champions-league"), millis("2026-10-14T22:01:30Z")).associateBy { it.id }
        val feyenoord = games.getValue("401915416")
        assertEquals(FixtureStatus.FINAL, feyenoord.status)
        assertEquals("3–2", feyenoord.score)
        val own = feyenoord.events.single { it.clock == "81'" }
        assertEquals(FixtureEvent(FixtureEventKind.GOAL, FixtureSide.HOME, "81'", null, "R. Sánchez (own goal)"), own)
        assertTrue("81' R. Sánchez (own goal)" in SportsEvents.moments(feyenoord).map { it.text })
        assertEquals(FixtureEvent(FixtureEventKind.GOAL, FixtureSide.AWAY, "64'", null, "S. Cruz (own goal)"), games.getValue("401915405").events.single { it.clock == "64'" })
        val villa = games.getValue("401915409").events.filter { it.kind == FixtureEventKind.GOAL }
        assertEquals(6, villa.size)
        assertTrue(villa.none { it.player.orEmpty().endsWith("(own goal)") })
        val entry = SportsCacheEntry(listOf(feyenoord), millis("2026-10-14T22:01:30Z"))
        assertEquals(entry, SportsFixtureCodec.decode(SportsFixtureCodec.encode(entry)))
    }

    @Test fun baseballScoreboardCountResetsOnceTheAtBatEnds() {
        val now = millis("2026-10-14T01:07:00Z")
        val game = EspnScoreboard.parse(sample("espn-mlb-scoreboard", "20261014c"), league("mlb"), now).single()
        assertEquals("Top 4th", game.detail)
        assertEquals(SportsDetail.Baseball(4, InningHalf.TOP, 2, 0, 0, true, false, false), game.sportDetail)
        assertEquals("Halpin singled to right.", game.situation?.lastPlay)
        assertEquals(52, game.situation?.homeWinPercent)
        assertEquals(SportsBugText("CLE 0–0 TB", "▲4", "2 outs · 0–0"), SportsFixtureText.bug(game))
        val root = JSONObject(sample("espn-mlb-scoreboard", "20261014c"))
        root.getJSONArray("events").getJSONObject(0).getJSONArray("competitions").getJSONObject(0).getJSONObject("situation").getJSONObject("lastPlay")
            .put("athletesInvolved", JSONArray())
        assertEquals(SportsDetail.Baseball(4, InningHalf.TOP, 2, 1, 2, true, false, false), EspnScoreboard.parse(root.toString(), league("mlb"), now).single().sportDetail)
    }

    @Test fun baseballRunOnAWildPitchIsOneMoment() {
        val summary = EspnSummary.parse(JSONObject(sample("espn-mlb-summary", "20261015b")), "baseball")
        assertEquals(FixtureStatus.FINAL, summary.status)
        assertEquals(listOf(Triple(1, 0, 1), Triple(6, 2, 1), Triple(7, 2, 3), Triple(7, 2, 4), Triple(8, 2, 5), Triple(9, 2, 6)),
            summary.moments.map { Triple(it.period, it.homeScore, it.awayScore) })
        assertEquals(1, summary.moments.count { it.text.startsWith("Turang scored on Wrobleski wild pitch") })
        assertTrue(summary.moments.all { it.kind == MomentKind.RUN && it.wallclockMillis != null })
        assertEquals(listOf(false, true, false, false, false, false), summary.moments.map { it.bottom })
        assertNull(summary.count)
        assertEquals(listOf("End of the 9th inning", "Betts struck out swinging.", "Ortiz singled to left, Pratt scored."), summary.lastPlays.map { it.text })
        assertEquals(listOf("DL Hall" to FixtureSide.AWAY, "Blake Snell" to FixtureSide.HOME, "Trevor Megill" to FixtureSide.AWAY), summary.awards.map { it.name to it.side })
    }

    @Test fun hockeyBenchMinorGivesTheOtherSideThePowerPlay() {
        val root = JSONObject(sample("espn-nhl-summary", "20261014c"))
        assertNull(EspnSummary.parse(plays(root, 2), "ice-hockey").strength)
        assertEquals(SummaryStrength("Power Play", FixtureSide.AWAY), EspnSummary.parse(plays(root, 3), "ice-hockey").strength)
        assertEquals(SummaryStrength("Power Play", FixtureSide.AWAY), EspnSummary.parse(root, "ice-hockey").strength)
    }

    @Test fun fightCardFinishedWhileTheEventStillReadsScheduled() {
        val card = EspnScoreboard.parse(sample("espn-ufc-scoreboard", "20261014d"), league("ufc"), millis("2026-10-14T01:45:00Z")).single()
        assertEquals(FixtureStatus.FINAL, card.status)
        assertEquals("Final", card.detail)
        val detail = card.sportDetail as SportsDetail.Card
        assertEquals(5, detail.bouts.size)
        assertTrue(detail.bouts.all { it.state == FixtureStatus.FINAL && it.rounds == 3 })
        assertEquals(listOf(FightMethod.DECISION, FightMethod.KO, FightMethod.KO, FightMethod.DECISION, FightMethod.DECISION), detail.bouts.map { it.method })
        assertEquals("D. da Lapa · KO/TKO · R3 0:19", SportsFixtureText.finish(detail.bouts[1]))
        assertEquals("L. Francischinelli · KO/TKO · R1 2:18", SportsFixtureText.finish(detail.bouts[2]))
        assertEquals(SportsBugText("I. Bulaid v E. Visconde", "Final", "E. Visconde · Decision"), SportsFixtureText.bug(card))
        assertEquals("9-1-0", detail.bouts[0].second.record)
    }

    @Test fun sportsDbOvertimeAndStuckShootOut() {
        val now = "2026-10-13T20:24:00Z"
        val swiss = merged("20261014", now, "2521849", "ice-hockey", "2026-10-13T18:00:00Z", "nhl")
        assertEquals(FixtureStatus.LIVE, swiss.status)
        assertEquals("3–3", swiss.score)
        assertEquals("OT · 4'", SportsFixtureText.periodClock(swiss))
        val eurocup = merged("20261014", now, "2588423", "basketball", "2026-10-13T18:00:00Z", "nba")
        assertEquals(5 to "6'", eurocup.period to eurocup.clock)
        assertEquals("OT · 6'", SportsFixtureText.periodClock(eurocup))
        assertEquals("Q4 · 7'", SportsFixtureText.periodClock(merged("20261014", now, "2565488", "basketball", "2026-10-13T18:30:00Z", "nba")))
        assertEquals(FixtureStatus.SCHEDULED, merged("20261014", now, "2608190", "basketball", "2026-10-13T18:00:00Z", "nba").status)
        val shootOut = merged("20261014", now, "2531858", "soccer", "2026-10-13T17:00:00Z", "epl")
        assertEquals(FixtureStatus.LIVE, shootOut.status)
        assertEquals("2–2", shootOut.score)
        assertEquals("P", SportsFixtureText.bug(shootOut).state)
        val later = merged("20261014", "2026-10-13T23:03:10Z", shootOut)
        assertEquals(FixtureStatus.FINAL, later.status)
        assertEquals("2–2", later.score)
        assertNull(later.detail)
    }

    @Test fun sportsDbHockeyShootOutAndLateBaseballRows() {
        val third = "2026-10-14T01:07:40Z"
        val fourth = "2026-10-14T01:45:10Z"
        val montreal = merged("20261014c", third, "2591576", "ice-hockey", "2026-10-13T22:30:00Z", "nhl")
        assertEquals(FixtureStatus.LIVE, montreal.status)
        assertEquals("P3 · 18'", SportsFixtureText.periodClock(montreal))
        val shootOut = merged("20261014d", fourth, montreal)
        assertEquals(FixtureStatus.FINAL, shootOut.status)
        assertEquals("4–3", shootOut.score)
        assertNull(shootOut.period)
        assertEquals("3–4", merged("20261014d", fourth, "2536624", "ice-hockey", "2026-10-13T23:00:00Z", "nhl").score)
        val overtime = merged("20261014c", third, "2591574", "ice-hockey", "2026-10-13T22:00:00Z", "nhl")
        assertEquals(FixtureStatus.FINAL to "4–5", overtime.status to overtime.score)
        val puckDrop = merged("20261014c", third, "2591586", "ice-hockey", "2026-10-14T01:00:00Z", "nhl")
        assertEquals(FixtureStatus.LIVE to "0–0", puckDrop.status to puckDrop.score)
        assertNull(SportsFixtureText.periodClock(puckDrop))
        assertEquals("BT", SportsFixtureText.periodClock(merged("20261014c", third, "2591578", "ice-hockey", "2026-10-13T23:00:00Z", "nhl")))
        assertEquals("Inning 7", merged("20261014c", third, "2609125", "baseball", "2026-10-13T23:00:00Z", "mlb").detail)
        assertEquals(SportsBugText("Away 6–2 Home", "Inning 8"), SportsFixtureText.bug(merged("20261014d", fourth, "2609125", "baseball", "2026-10-13T23:00:00Z", "mlb")))
        assertEquals(FixtureStatus.SCHEDULED, merged("20261014c", third, "2616981", "baseball", "2026-10-13T00:00:00Z", "mlb").status)
        val rays = merged("20261014c", third, "2616981", "baseball", "2026-10-14T00:00:00Z", "mlb")
        assertEquals(FixtureStatus.LIVE, rays.status)
        assertEquals("Inning 4", rays.detail)
        val fifth = merged("20261014d", fourth, rays)
        assertEquals("Inning 5", fifth.detail)
        assertNull(fifth.score)
        val magic = merged("20261014d", fourth, "2601644", "basketball", "2026-10-13T23:00:00Z", "nba")
        assertEquals(FixtureStatus.FINAL to "132–116", magic.status to magic.score)
    }

    @Test fun sportsDbFinishedBaseballAndLaterRows() {
        val sixth = "2026-10-15T09:39:40Z"
        val mexico = merged("20261015b", sixth, "2574556", "baseball", "2026-10-15T03:15:00Z", "mlb")
        assertEquals(FixtureStatus.FINAL, mexico.status)
        assertNull(mexico.score)
        assertNull(mexico.detail)
        assertEquals(FixtureStatus.SCHEDULED, merged("20261015b", sixth, "2522399", "ice-hockey", "2026-10-15T16:00:00Z", "nhl").status)
        val japan = merged("20261015b", sixth, "2617446", "baseball", "2026-10-15T09:00:00Z", "mlb")
        assertEquals(FixtureStatus.LIVE, japan.status)
        assertEquals(SportsBugText("Away @ Home", "Inning 2"), SportsFixtureText.bug(japan))
        assertEquals("Q3 · 1'", SportsFixtureText.periodClock(merged("20261015b", sixth, "2487912", "basketball", "2026-10-15T08:30:00Z", "nbl")))
        val seventh = "2026-10-16T21:39:30Z"
        val caracas = merged("20261017", seventh, "2609136", "baseball", "2026-10-16T18:00:00Z", "mlb")
        assertEquals(FixtureStatus.FINAL to "2–9", caracas.status to caracas.score)
        val denver = merged("20261017", seventh, "2475453", "american-football", "2026-10-16T00:15:00Z", "nfl")
        assertEquals(listOf<Any?>(FixtureStatus.FINAL, "17–14", "Final/OT"), listOf(denver.status, denver.score, denver.detail))
        assertEquals(FixtureStatus.FINAL to "100–101", merged("20261017", seventh, "2556099", "basketball", "2026-10-16T18:45:00Z", "nba").let { it.status to it.score })
        assertEquals(FixtureStatus.FINAL to "2–1", merged("20261017", seventh, "2520376", "ice-hockey", "2026-10-16T18:30:00Z", "nhl").let { it.status to it.score })
        val stale = merged("20261017", seventh, "2600775", "ice-hockey", "2026-10-15T01:35:00Z", "nhl")
        assertEquals(FixtureStatus.SCHEDULED, stale.status)
        assertNull(stale.score)
    }
}
