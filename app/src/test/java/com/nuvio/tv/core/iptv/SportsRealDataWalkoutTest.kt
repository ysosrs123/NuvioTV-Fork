package com.nuvio.tv.core.iptv

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsRealDataWalkoutTest {
    private fun sample(name: String, day: String = "20261018b"): String =
        javaClass.getResourceAsStream("/sports/$name-real-$day.json")!!.bufferedReader().use { it.readText() }
    private fun league(id: String) = SportsLeagues.byId(id)!!
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private val now = millis("2026-10-17T21:10:00Z")

    private fun games(name: String, league: String) = EspnScoreboard.parse(sample("espn-$name-scoreboard"), league(league), now).associateBy { it.id }

    @Test fun hockeyPenaltyWithoutTheInfractionInTheTextNamesIt() {
        val games = games("nhl", "nhl")
        val lightning = games.getValue("401892521")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "0–2", "P2 · 14:25"), listOf(lightning.status, lightning.score, SportsFixtureText.periodClock(lightning)))
        assertEquals("Liam Ohgren Closing Hand on the Puck", lightning.situation?.lastPlay)
        assertEquals("Goalie Stopped", games.getValue("401892520").situation?.lastPlay)
        assertEquals("William Borgen Tripping against Noah Cates", sportsPenaltyPlay("William Borgen Tripping against Noah Cates", "Minor", "Tripping"))
        assertEquals("Ryan Hartman Major", sportsPenaltyPlay("Ryan Hartman Major", "Major", "Major"))
        assertEquals("Ryan Hartman Minor", sportsPenaltyPlay("Ryan Hartman Minor", null, "Slashing"))
    }

    @Test fun summaryLastPlayNamesTheInfractionToo() {
        val root = JSONObject(javaClass.getResourceAsStream("/sports/espn-nhl-summary-real-20261010.json")!!.bufferedReader().use { it.readText() })
        val plays = root.getJSONArray("plays")
        val cut = (0 until plays.length()).first { plays.getJSONObject(it).optJSONObject("type")?.optString("id") == "143" }
        root.put("plays", JSONArray((0..cut).map { plays.getJSONObject(it) }))
        val summary = EspnSummary.parse(root.toString(), league("nhl"))!!
        assertEquals("Michael Brandsegg-Nygård Closing Hand on the Puck", summary.lastPlays.first().text)
    }

    @Test fun firstBoutAtWalkoutsMakesTheCardLive() {
        val card = games("ufc", "ufc").values.single()
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "Walkouts"), listOf(card.status, card.detail))
        val bouts = (card.sportDetail as SportsDetail.Card).bouts
        assertEquals(13, bouts.size)
        assertEquals(FixtureStatus.LIVE, bouts.first().state)
        assertTrue(bouts.drop(1).all { it.state == FixtureStatus.SCHEDULED })
        assertTrue(bouts.all { it.round == null && it.winner == null && it.method == null })
        assertEquals("Cody Chovancek" to "SuYoung You", bouts.first().first.name to bouts.first().second.name)
        assertEquals(listOf(7, 6), bouts.partition { !it.mainCard }.toList().map { it.size })
        assertEquals(millis("2026-10-17T21:00:00Z"), card.startMillis)
    }

    @Test fun collegeHockeyAndMlsLive() {
        val hockey = games("college-hockey", "college-hockey")
        val clarkson = hockey.getValue("401904408")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "1–0", "End of 1st"), listOf(clarkson.status, clarkson.score, SportsFixtureText.periodClock(clarkson)))
        assertEquals("P1 · 16:09", SportsFixtureText.periodClock(hockey.getValue("401904891")))
        val mls = games("mls", "mls")
        val minnesota = mls.getValue("761876")
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "0–2", 1), listOf(minnesota.status, minnesota.score, minnesota.period))
        assertEquals(FixtureStatus.FINAL to "2–2", mls.getValue("761875").let { it.status to it.score })
    }

    @Test fun mlbEventsDayCarriesTheUtcDateForAnEveningGame() {
        val games = SportsDbEvents.parse(sample("tsdb-eventsday-mlb"), league("mlb"), now)
        assertEquals(listOf(millis("2026-10-18T00:00:00Z"), millis("2026-10-18T21:00:00Z")), games.map { it.startMillis })
        assertTrue(games.all { it.status == FixtureStatus.SCHEDULED && it.score == null })
        assertEquals("Cleveland Guardians v Tampa Bay Rays", games.first().title)
        val later = millis("2026-10-18T01:30:00Z")
        val live = SportsDbLive.merge(games, mapOf("2616984" to SportsDbLiveScore("2616984", "IN4", "IN4", "1", "0", later - 60_000)), later).first()
        assertEquals(listOf<Any?>(FixtureStatus.LIVE, "1–0", "Inning 4", 4), listOf(live.status, live.score, live.detail, live.period))
    }
}
