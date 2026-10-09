package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SportsPendingRecordsTest {
    private val hour = 60L * 60 * 1000
    private val now = 1_791_000_000_000L
    private val game = SportsFixture("740100", "epl", "soccer", "Arsenal v Leeds United", FixtureTeam("Arsenal"), FixtureTeam("Leeds United"), now + 24 * hour,
        FixtureStatus.SCHEDULED)
    private val custom = SportsFixture("55", "sdb-4328", "soccer", "Hull v Leeds", FixtureTeam("Hull"), FixtureTeam("Leeds"), now + 2 * hour,
        FixtureStatus.SCHEDULED, source = SportsService.THESPORTSDB)

    private fun other(index: Int, start: Long) = SportsFixture("o$index", "mlb", "baseball", "Game $index", FixtureTeam("Home $index"), FixtureTeam("Away $index"),
        start, FixtureStatus.SCHEDULED)

    @Test fun leaguesComeFromTheFixtureKeys() {
        val all = listOf(SportsPendingRecords.of(1, game), SportsPendingRecords.of(1, custom), SportsPendingRecords.of(2, game))
        assertEquals(setOf("epl", "sdb-4328"), SportsPendingRecords.leagues(all))
        assertTrue(SportsPendingRecords.leagues(emptyList()).isEmpty())
    }

    @Test fun updateFollowsKickoffChangesAndDropsEndedGames() {
        val all = listOf(SportsPendingRecords.of(1, game), SportsPendingRecords.of(1, custom))
        assertEquals(all.sortedBy { it.startMillis }, SportsPendingRecords.update(all, emptyList(), now))
        val moved = game.copy(startMillis = now + 30 * hour)
        assertEquals(now + 30 * hour, SportsPendingRecords.update(all, listOf(moved), now).first { it.key == game.key }.startMillis)
        assertEquals(listOf(custom.key), SportsPendingRecords.update(all, listOf(game.copy(status = FixtureStatus.FINAL)), now).map { it.key })
        assertEquals(listOf(game.key), SportsPendingRecords.update(all, emptyList(), custom.startMillis + SportsRefresh.durationMillis(custom)).map { it.key })
        val early = custom.copy(startMillis = now - 4 * hour, status = FixtureStatus.LIVE)
        assertEquals(listOf(game.key), SportsPendingRecords.update(all, listOf(early), now).map { it.key })
    }

    @Test fun watchedIsBoundedToTheProfileAndSoonestGames() {
        val fixtures = (1..30).map { other(it, now + it * hour) }
        val all = fixtures.reversed().map { SportsPendingRecords.of(1, it) } + SportsPendingRecords.of(2, game)
        val watched = SportsPendingRecords.watched(all, 1, fixtures + game, now)
        assertEquals(SportsPendingRecords.MAX_WATCHED, watched.size)
        assertEquals(fixtures.take(SportsPendingRecords.MAX_WATCHED), watched)
        assertEquals(listOf(game), SportsPendingRecords.watched(all, 2, fixtures + game, now))
        assertTrue(SportsPendingRecords.watched(all, 2, listOf(game.copy(status = FixtureStatus.FINAL)), now).isEmpty())
    }

    @Test fun claimTakesEachRuleOnce() {
        val all = listOf(SportsPendingRecords.of(1, game), SportsPendingRecords.of(1, custom), SportsPendingRecords.of(2, game))
        val (kept, taken) = SportsPendingRecords.claim(all, 1, listOf(game, game, other(1, now)))
        assertEquals(listOf(game), taken)
        assertEquals(listOf(SportsPendingRecords.of(1, custom), SportsPendingRecords.of(2, game)), kept)
        val (again, none) = SportsPendingRecords.claim(kept, 1, listOf(game))
        assertTrue(none.isEmpty())
        assertEquals(kept, again)
    }
}
