package com.nuvio.tv.core.iptv

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class SportsLinkWindowTest {
    private val hour = 60L * 60 * 1000
    private val london = ZoneId.of("Europe/London")
    private val now = ZonedDateTime.of(2026, 10, 9, 18, 0, 0, 0, london).toInstant().toEpochMilli()
    private val kickoff = ZonedDateTime.of(2026, 10, 10, 21, 30, 0, 0, london).toInstant().toEpochMilli()
    private val arsenal = FixtureTeam("Arsenal", "Arsenal", "ARS")
    private val leeds = FixtureTeam("Leeds United", "Leeds", "LEE")
    private val game = SportsFixture("740100", "epl", "soccer", "Arsenal v Leeds United", arsenal, leeds, kickoff, FixtureStatus.SCHEDULED)

    private fun other(index: Int, start: Long) = SportsFixture("o$index", "mlb", "baseball", "Game $index", FixtureTeam("Home $index"), FixtureTeam("Away $index"),
        start, FixtureStatus.SCHEDULED)

    private fun <T> days(future: Int, block: () -> T): T = try { SportsDays.guideDays(future); block() } finally { SportsDays.guideDays(SportsDays.DEFAULT_DAYS - 1) }

    @Test fun fixtureWindowFollowsGuideDaysAhead() {
        assertEquals(SportsDays.DEFAULT_DAYS, SportsDays.DAYS)
        days(3) {
            val (from, until) = SportsDays.window(now, london)
            assertEquals(ZonedDateTime.of(2026, 10, 13, 0, 0, 0, 0, london).toInstant().toEpochMilli(), until)
            assertEquals((8..12).map { LocalDate.of(2026, 10, it) }, SportsDays.serviceDates(from, until, SportsDays.ESPN_ZONE))
        }
        days(7) {
            val (from, until) = SportsDays.window(now, london)
            assertEquals(ZonedDateTime.of(2026, 10, 17, 0, 0, 0, 0, london).toInstant().toEpochMilli(), until)
            assertEquals((8..16).map { LocalDate.of(2026, 10, it) }, SportsDays.serviceDates(from, until, SportsDays.SPORTSDB_ZONE))
            val sections = SportsFixtureSections.group(listOf(game, other(1, kickoff + 5 * 24 * hour)), now, london)
            assertEquals(listOf(FixtureSection.TOMORROW, FixtureSection.DAY), sections.map { it.section })
        }
        days(30) { assertEquals(SportsDays.MAX_DAYS, SportsDays.DAYS) }
        assertEquals(SportsDays.DEFAULT_DAYS, SportsDays.DAYS)
        assertEquals(listOf(FixtureSection.TOMORROW), SportsFixtureSections.group(listOf(game, other(1, kickoff + 5 * 24 * hour)), now, london).map { it.section })
    }

    @Test fun farDaysRefreshLessOften() {
        val today = LocalDate.of(2026, 10, 9)
        val fresh = SportsCacheEntry(emptyList(), now - hour)
        assertTrue(SportsDays.due(today.plusDays(1), london, fresh, now))
        assertTrue(SportsDays.due(today.plusDays(3), london, fresh, now))
        assertFalse(SportsDays.due(today.plusDays(4), london, fresh, now))
        assertTrue(SportsDays.due(today.plusDays(6), london, SportsCacheEntry(emptyList(), now - 4 * hour), now))
        assertTrue(SportsDays.due(today.plusDays(6), london, null, now))
    }

    @Test fun tomorrowsGameKeepsItsGuideSliceWhenManyEarlierHoursHaveGames() {
        val busy = (1..40).map { other(it, now + it * hour) }
        val slices = SportsGuideSlices.plan(busy + game, emptyList(), now)
        val slice = Math.floorDiv(kickoff, SportsGuideSlices.SLICE_MILLIS)
        assertTrue(slice in slices.keys)
        assertTrue(game in slices[slice].orEmpty())
        assertTrue(slices.size <= SportsGuideSlices.MAX_SLICES)
        val old = SportsGuideSlices.plan(busy + game, emptyList(), now, maxSlices = 24)
        assertFalse(slice in old.keys)
        val week = (1..400).map { other(it, now + it * hour / 2) }
        assertEquals(SportsGuideSlices.MAX_SLICES, SportsGuideSlices.plan(week, emptyList(), now).size)
        val finished = game.copy(id = "1", startMillis = now - 3 * hour, status = FixtureStatus.FINAL)
        val withRecent = SportsGuideSlices.plan(listOf(game), listOf(finished), now)
        assertEquals(listOf(Math.floorDiv(finished.startMillis, SportsGuideSlices.SLICE_MILLIS), slice), withRecent.keys.toList())
    }

    @Test fun tomorrowsProgrammeMatchesInsideItsSlice() {
        val programme = GuideProgramme("c", GuideTimestamp(kickoff - 30 * 60_000, 14), GuideTimestamp(kickoff + 2 * hour, 14),
            listOf(LocalizedGuideText("Live: Premier League - Arsenal v Leeds", "en")), emptyList())
        assertEquals(FixtureLinkReason.GUIDE_TEAMS, SportsFixtureMatching.guideMatch(game, programme, now))
        val start = Math.floorDiv(kickoff, SportsGuideSlices.SLICE_MILLIS) * SportsGuideSlices.SLICE_MILLIS
        assertTrue(programme.start.epochMillis <= start + SportsGuideSlices.SLICE_MILLIS && programme.stop!!.epochMillis > start)
    }

    @Test fun pendingRecordsWaitForALinkAndExpire() {
        val empty = emptyList<SportsPendingRecord>()
        val added = SportsPendingRecords.toggle(empty, 1, game, now)
        assertEquals(listOf(SportsPendingRecord(1, game.key, kickoff, kickoff + SportsRefresh.durationMillis(game))), added)
        assertEquals(added, added.map(SportsPendingRecords::encode).mapNotNull(SportsPendingRecords::decode))
        assertTrue(SportsPendingRecords.toggle(added, 1, game, now).isEmpty())
        assertEquals(2, SportsPendingRecords.toggle(added, 2, game, now).size)
        assertTrue(SportsPendingRecords.ready(added, 1, listOf(game), { false }, now).isEmpty())
        assertEquals(listOf(game), SportsPendingRecords.ready(added, 1, listOf(game), { true }, now))
        assertTrue(SportsPendingRecords.ready(added, 2, listOf(game), { true }, now).isEmpty())
        assertTrue(SportsPendingRecords.ready(added, 1, listOf(game.copy(status = FixtureStatus.FINAL)), { true }, now).isEmpty())
        assertTrue(SportsPendingRecords.prune(added, kickoff + 4 * hour).isEmpty())
        assertTrue(SportsPendingRecords.toggle(empty, 1, game.copy(status = FixtureStatus.FINAL), now).isEmpty())
        listOf("", "x|1|2|k", "1|5|4|k", "1|1|2|", "-1|1|2|k").forEach { assertNull(it, SportsPendingRecords.decode(it)) }
        assertEquals("epl:a|b", SportsPendingRecords.decode("1|1|2|epl:a|b")?.key)
        val many = (1..80).fold(empty) { all, index -> SportsPendingRecords.toggle(all, 1, other(index, now + index * hour), now) }
        assertEquals(SportsPendingRecords.MAX, many.size)
    }

    @Test fun channelSourceChoosesGuideBroadcastersOrBothWithGuideFirst() {
        val broadcast = game.copy(broadcasters = listOf("Sky Sports Premier League"))
        val programme = GuideProgramme("c", GuideTimestamp(kickoff - 30 * 60_000, 14), GuideTimestamp(kickoff + 2 * hour, 14),
            listOf(LocalizedGuideText("Premier League: Arsenal v Leeds United", "en")), emptyList())
        val channels = listOf(FixtureChannel("sky", "UK: Sky Sports Premier League HD"), FixtureChannel("tnt", "TNT Sports 1"))
        val listings = listOf(FixtureListing("tnt", programme))
        fun links(source: SportsChannelSource) = SportsFixtureMatching.link(listOf(broadcast), channels, listings, emptySet(), now, source = source)[game.id].orEmpty()
            .map { it.channelId to it.reason }
        assertEquals(listOf("tnt" to FixtureLinkReason.GUIDE_TEAMS, "sky" to FixtureLinkReason.BROADCASTER), links(SportsChannelSource.BOTH))
        assertEquals(listOf("tnt" to FixtureLinkReason.GUIDE_TEAMS), links(SportsChannelSource.GUIDE))
        assertEquals(listOf("sky" to FixtureLinkReason.BROADCASTER), links(SportsChannelSource.BROADCASTERS))
        assertTrue(SportsChannelSource.BOTH.guide && SportsChannelSource.BOTH.broadcasters)
    }
}
