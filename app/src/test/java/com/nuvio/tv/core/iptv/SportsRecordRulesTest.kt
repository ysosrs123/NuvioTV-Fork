package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SportsRecordRulesTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val sydney = FixtureTeam("Sydney FC", "Sydney", "SYD")
    private val wanderers = FixtureTeam("Western Sydney Wanderers", "Western Sydney", "WSW")
    private val victory = FixtureTeam("Melbourne Victory", "Melbourne", "MVC")
    private val rules = setOf("a-league-men:Sydney FC")
    private val early = 1 * minute
    private val late = 2 * minute

    private fun game(id: String, start: Long, status: FixtureStatus = FixtureStatus.SCHEDULED, home: FixtureTeam = sydney, away: FixtureTeam = wanderers,
        title: String = "${home.name} v ${away.name}") = SportsFixture(id, "a-league-men", "soccer", title, home, away, start, status)

    private fun target(stop: Long? = null, title: String? = null) = SportsRecordTarget("source-1", "channel:1", null, stop, title)

    @Test fun linkedGamesArePlannedWithLiveTvPaddingAndOthersWait() {
        val linked = game("1", now + 60 * minute)
        val unlinked = game("2", now + 2 * 24 * 60 * minute)
        val other = game("3", now + 60 * minute, home = victory)
        val outcome = SportsRecordRules.plan(rules, listOf(linked, unlinked, other), mapOf(linked.key to target(), other.key to target()), emptySet(),
            early, late, now)
        val plan = outcome.plans.single()
        assertEquals(linked.key, plan.fixture.key)
        assertEquals(linked.startMillis - early, plan.window.startMillis)
        assertEquals(linked.startMillis + 135 * minute + late, plan.window.stopMillis)
        assertFalse(plan.final)
        assertEquals(listOf(unlinked.key), outcome.waiting.map { it.key })
        assertTrue(outcome.missed.isEmpty())
    }

    @Test fun scheduledFinishedAndPastGamesAreSkippedAndKickOffWithoutChannelIsMissed() {
        val started = game("1", now - 10 * minute, FixtureStatus.LIVE)
        val done = game("2", now - 200 * minute, FixtureStatus.FINAL)
        val stale = game("3", now - 300 * minute)
        val booked = game("4", now + 30 * minute)
        val outcome = SportsRecordRules.plan(rules, listOf(started, done, stale, booked), mapOf(booked.key to target()), setOf(booked.key), early, late, now)
        assertTrue(outcome.plans.isEmpty())
        assertTrue(outcome.waiting.isEmpty())
        assertEquals(listOf(started.key), outcome.missed.map { it.key })
        assertTrue(SportsRecordRules.plan(emptySet(), listOf(booked), mapOf(booked.key to target()), emptySet(), early, late, now).plans.isEmpty())
    }

    @Test fun liveGamesStartNowAndFinalsGetExtraTime() {
        val live = game("1", now - 20 * minute, FixtureStatus.LIVE)
        val window = SportsRecordRules.window(live, target(), early, late, now)!!
        assertEquals(now, window.startMillis)
        val final = game("2", now + 60 * minute, title = "A-League Men Grand Final")
        assertTrue(SportsRecordRules.final(final))
        val plan = SportsRecordRules.plan(rules, listOf(final), mapOf(final.key to target()), emptySet(), early, late, now).plans.single()
        assertTrue(plan.final)
        assertEquals(final.startMillis + 135 * minute + late + SportsRecordRules.FINAL_EXTRA_MILLIS, plan.window.stopMillis)
        assertTrue(SportsRecordRules.final(game("3", now), "Semi-Final: Sydney FC v Western Sydney Wanderers"))
        assertFalse(SportsRecordRules.final(game("4", now), "A-League Men: Sydney FC v Western Sydney Wanderers"))
        assertFalse(SportsRecordRules.final(game("5", now, FixtureStatus.LIVE).copy(detail = "Final")))
    }

    @Test fun broadcastEndExtendsOnlyWithinReason() {
        val fixture = game("1", now + 60 * minute)
        val longer = SportsRecordRules.window(fixture, target(stop = fixture.startMillis + 150 * minute), early, late, now)!!
        assertEquals(fixture.startMillis + 150 * minute + late, longer.stopMillis)
        val silly = SportsRecordRules.window(fixture, target(stop = fixture.startMillis + 600 * minute), early, late, now)!!
        assertEquals(fixture.startMillis + 135 * minute + late, silly.stopMillis)
        val ended = game("2", now - 200 * minute, FixtureStatus.LIVE)
        assertNull(SportsRecordRules.window(ended, target(), 0, 0, now))
    }

    @Test fun teamGamesPickLiveOrNextAndKeepTheLastResult() {
        val last = game("1", now - 3 * 24 * 60 * minute, FixtureStatus.FINAL, home = victory, away = sydney)
        val next = game("2", now + 60 * minute)
        val later = game("3", now + 3 * 24 * 60 * minute, home = wanderers, away = sydney)
        val unrelated = game("4", now + 30 * minute, home = victory, away = wanderers)
        val games = SportsTeams.games("a-league-men:Sydney FC", listOf(later, unrelated, next, last), now)!!
        assertEquals(next.key, games.current?.key)
        assertEquals(listOf(later.key), games.upcoming.map { it.key })
        assertEquals(last.key, games.last?.key)
        assertEquals("SYD", games.team?.abbreviation)
        assertEquals(true, SportsTeams.home(games, next))
        assertEquals(false, SportsTeams.home(games, later))
        val live = game("5", now - 30 * minute, FixtureStatus.LIVE)
        assertEquals(live.key, SportsTeams.games("a-league-men:Sydney FC", listOf(next, live), now)!!.current?.key)
        assertEquals(listOf(next.key), SportsTeams.games("a-league-men:Sydney FC", listOf(next, live), now)!!.upcoming.map { it.key })
        assertNull(SportsTeams.games("Sydney FC", listOf(next), now))
        assertNull(SportsTeams.games("nrl:Sydney Roosters", listOf(next), now)!!.current)
    }
}
