package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SportsChannelRulesTest {
    private val hour = 60L * 60 * 1000
    private val start = 1_800_000_000_000L
    private val afl = SportsFixture("401", "afl", "australian-football", "Richmond Tigers v Carlton Blues", FixtureTeam("Richmond Tigers", "Richmond", "RICH"),
        FixtureTeam("Carlton Blues", "Carlton", "CARL"), start, FixtureStatus.SCHEDULED, broadcasters = listOf("Seven Network", "Fox Footy"))
    private val channels = listOf(FixtureChannel("footy", "Fox Footy HD", "Sport"), FixtureChannel("guide", "Some Sport Channel", "UK | Sport"),
        FixtureChannel("seven", "7 HD Sydney", "AU | Sport "), FixtureChannel("league", "League channel", "Sport"))
    private val listings = listOf(FixtureListing("footy", programme("AFL: Richmond v Carlton")), FixtureListing("guide", programme("Richmond v Carlton", "AFL")),
        FixtureListing("league", programme("AFL Live: Richmond Tigers")))

    private fun programme(title: String, description: String? = null) =
        GuideProgramme("c", GuideTimestamp(start - 10 * 60_000, 14, ""), GuideTimestamp(start + 3 * hour, 14, ""), listOf(LocalizedGuideText(title, "en")),
            listOfNotNull(description?.let { LocalizedGuideText(it, "en") }))

    private fun category(name: String) = SportsChannelPick(SportsPickKind.CATEGORY, name)
    private fun channel(id: String) = SportsChannelPick(SportsPickKind.CHANNEL, id, "Channel $id")
    private fun linked(rules: SportsChannelRules) =
        SportsFixtureMatching.link(listOf(afl), channels, listings, emptySet(), start, rules = rules).getValue("401").map { it.channelId }

    @Test fun preferredChannelsAndCategoriesRankFirstWithinTheSameMatch() {
        assertEquals(listOf("footy", "guide", "league", "seven"), linked(SportsChannelRules()))
        assertEquals(listOf("guide", "footy", "league", "seven"), linked(SportsChannelRules(preferred = listOf(category("uk | sport")))))
        assertEquals(listOf("guide", "footy", "league", "seven"), linked(SportsChannelRules(preferred = listOf(channel("guide")))))
    }

    @Test fun preferredChannelsMatchedByBrandOrLeagueDoNotBeatBothTeams() {
        assertEquals(listOf("footy", "guide", "league", "seven"), linked(SportsChannelRules(preferred = listOf(category("au | sport")))))
        assertEquals(listOf("footy", "guide", "league", "seven"), linked(SportsChannelRules(preferred = listOf(category("AU | Sport"), channel("league")))))
        val broadcastOnly = listings.filter { it.channelId != "footy" }
        assertEquals(listOf("guide", "league", "seven", "footy"), SportsFixtureMatching.link(listOf(afl), channels, broadcastOnly, emptySet(), start,
            rules = SportsChannelRules(preferred = listOf(channel("seven")))).getValue("401").map { it.channelId })
    }

    @Test fun excludedChannelsAndCategoriesAreNeverOffered() {
        assertEquals(listOf("footy", "league", "seven"), linked(SportsChannelRules(excluded = listOf(category("UK | Sport")))))
        assertEquals(listOf("guide", "league", "seven"), linked(SportsChannelRules(excluded = listOf(channel("footy")))))
        assertTrue(SportsFixtureMatching.link(listOf(afl), channels, listings, emptySet(), start,
            rules = SportsChannelRules(excluded = listOf(category("Sport"), category("UK | Sport"), category("AU | Sport")))).isEmpty())
    }

    @Test fun rankedKeepsOrderWithinTiersAndDropsExcluded() {
        val rules = SportsChannelRules(preferred = listOf(category("AU")), excluded = listOf(channel("b")))
        val links = listOf("a" to "UK", "b" to "AU", "c" to "AU", "d" to null, "e" to "au ")
        assertEquals(listOf("c", "e", "a", "d"), rules.ranked(links) { it }.map { it.first })
        assertSame(links, SportsChannelRules().ranked(links) { it })
    }

    @Test fun sportOnlyHonoursAlwaysAndNever() {
        val rules = SportsChannelRules(always = listOf(category("NZ | Sport"), channel("x")), never = listOf(category("Racing"), channel("y")))
        assertTrue(rules.sportOnly("a", "Sport", matched = true))
        assertFalse(rules.sportOnly("a", "Sport", matched = false))
        assertTrue(rules.sportOnly("a", "nz | sport", matched = false))
        assertTrue(rules.sportOnly("x", null, matched = false))
        assertFalse(rules.sportOnly("b", "Racing", matched = true))
        assertFalse(rules.sportOnly("y", "NZ | Sport", matched = true))
        assertFalse(SportsChannelRules(always = listOf(category(" "))).sportOnly("a", "", matched = false))
    }

    @Test fun picksRoundTripAndListsStayExclusive() {
        val pick = SportsChannelPick(SportsPickKind.CHANNEL, "id-1", "Fox\tFooty")
        assertEquals(SportsChannelPick(SportsPickKind.CHANNEL, "id-1", "Fox Footy"), SportsChannelPicks.decode(SportsChannelPicks.encode(pick)))
        assertEquals(category(" Sport "), SportsChannelPicks.decode(SportsChannelPicks.encode(category(" Sport ")))?.copy(label = " Sport "))
        assertNull(SportsChannelPicks.decode("x\tid\tname"))
        assertNull(SportsChannelPicks.decode("c\t \tname"))
        assertNull(SportsChannelPicks.decode("garbage"))
        var rules = SportsChannelPicks.set(SportsChannelRules(), SportsPickList.ALWAYS, listOf(category("Sport"), category("sport ")))
        assertEquals(1, rules.always.size)
        rules = SportsChannelPicks.set(rules, SportsPickList.NEVER, SportsChannelPicks.toggle(rules.never, category("SPORT")))
        assertTrue(rules.always.isEmpty())
        assertEquals(1, rules.never.size)
        rules = SportsChannelPicks.set(rules, SportsPickList.NEVER, SportsChannelPicks.toggle(rules.never, category("sport")))
        assertTrue(rules.never.isEmpty())
        rules = SportsChannelPicks.set(rules, SportsPickList.PREFERRED, listOf(channel("a")))
        rules = SportsChannelPicks.set(rules, SportsPickList.EXCLUDED, listOf(channel("a"), channel("b")))
        assertTrue(rules.preferred.isEmpty())
        assertEquals(SportsChannelRules(excluded = listOf(channel("a"), channel("b"))), rules.linking)
        assertEquals(SportsChannelPicks.MAX, SportsChannelPicks.clean((1..150).map { channel("c$it") }).size)
    }

    @Test fun sportOnlyLooksADayAhead() {
        val now = start
        assertEquals(now - hour to now + 24 * hour, SportsOnlyWindow.range(now, now - hour, now + hour))
        assertEquals(now to now + 30 * hour, SportsOnlyWindow.range(now, now + 26 * hour, now + 30 * hour))
        assertTrue(SportsOnlyWindow.overlaps(longArrayOf(now + 2 * hour, now + 3 * hour, now + 20 * hour, now + 22 * hour), now + 19 * hour, now + 24 * hour))
        assertFalse(SportsOnlyWindow.overlaps(longArrayOf(now + 2 * hour, now + 3 * hour), now + 3 * hour, now + 24 * hour))
        assertFalse(SportsOnlyWindow.overlaps(null, now, now + hour))
    }

    @Test fun sportPageGuideWindowCoversTheNextDay() {
        val now = start + 17 * 60_000
        val window = SportsOnlyWindow.guide(now)
        assertTrue(window.startMillis <= now - GuideGridWindow.SLOT_MILLIS / 2)
        assertTrue(window.endMillis >= now + 23 * hour)
        assertTrue(SportsOnlyWindow.fresh(window, now))
        assertFalse(SportsOnlyWindow.fresh(GuideGridWindow(window.startMillis, window.startMillis + 12 * hour), now))
        var later = now
        while (SportsOnlyWindow.fresh(window, later)) later += 60_000
        assertTrue(later > now)
        if (window.spanMillis > SportsOnlyWindow.AHEAD_MILLIS + GuideGridWindow.SLOT_MILLIS) assertTrue(window.endMillis - (later - 60_000) >= SportsOnlyWindow.AHEAD_MILLIS)
    }
}
