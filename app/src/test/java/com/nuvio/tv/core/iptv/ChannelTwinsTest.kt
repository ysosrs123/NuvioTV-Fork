package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class ChannelTwinsTest {
    @Test fun searchPhraseDropsRegionBracketsAndQuality() {
        assertEquals("fox sports 503", guideSearchPhrase("AU: Fox Sports 503 FHD"))
        assertEquals("fox sports 503", guideSearchPhrase("AU | FOX SPORTS 503 (Backup) HD"))
        assertEquals("kayo", guideSearchPhrase("Kayo+"))
        assertEquals("hd", guideSearchPhrase("HD"))
        assertNull(guideSearchPhrase("  - "))
    }

    @Test fun matchesByGuideKeyGuideIdOrName() {
        val target = TwinChannel("a", "1", "AU: Fox Sports 503 HD", "FoxSports503.au@HD", GuideKey("feed", "fs503"))
        assertTrue(ChannelTwins.same(target, TwinChannel("b", "9", "Something else", guide = GuideKey("feed", "fs503"))))
        assertTrue(ChannelTwins.same(target, TwinChannel("b", "9", "Other name", "foxsports503.au")))
        assertTrue(ChannelTwins.same(target, TwinChannel("b", "9", "FOX SPORTS 503")))
        assertFalse(ChannelTwins.same(target, TwinChannel("a", "2", "Fox Sports 503")))
        assertFalse(ChannelTwins.same(target, TwinChannel("b", "9", "Fox Sports 502", "fs502")))
        assertFalse(ChannelTwins.same(TwinChannel("a", "1", "-"), TwinChannel("b", "9", "-")))
    }

    @Test fun pickKeepsSourceOrderTwoPerSourceAndCap() {
        val target = TwinChannel("a", "1", "Fox Sports 503")
        val candidates = listOf(TwinChannel("b", "1", "Fox Sports 503 HD"), TwinChannel("b", "2", "Fox Sports 503 SD"), TwinChannel("b", "3", "UK: Fox Sports 503"),
            TwinChannel("b", "1", "Fox Sports 503 HD"), TwinChannel("c", "4", "Fox Sports 503"), TwinChannel("c", "5", "Fox Sports 505"), TwinChannel("a", "6", "Fox Sports 503"))
        assertEquals(listOf("b/1", "b/2", "c/4"), ChannelTwins.pick(target, candidates).map { "${it.sourceId}/${it.id}" })
        assertEquals(listOf("b/1"), ChannelTwins.pick(target, candidates, max = 1).map { "${it.sourceId}/${it.id}" })
    }

    @Test fun recentFinalsWithinTwelveHours() {
        val hour = 60L * 60 * 1000
        val now = 100 * hour
        fun fixture(id: String, start: Long, status: FixtureStatus = FixtureStatus.FINAL) =
            SportsFixture(id, "nrl", "rugby-league", id, null, null, start, status)
        val list = listOf(fixture("old", now - 16 * hour), fixture("edge", now - 14 * hour), fixture("recent", now - 3 * hour), fixture("live", now - hour, FixtureStatus.LIVE),
            fixture("later", now + hour), fixture("just", now - hour), fixture("recent", now - 3 * hour))
        assertEquals(listOf("just", "recent", "edge"), SportsCatchup.recent(list, now).map { it.id })
        assertEquals(listOf("just"), SportsCatchup.recent(list, now, max = 1).map { it.id })
        assertEquals(now - 14 * hour + 135 * 60_000L, SportsCatchup.end(list[1]))
    }
}
