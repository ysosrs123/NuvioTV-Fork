package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class GuideAiringTest {
    private val hour = 3_600_000L
    private val now = 1_790_000_000_000L
    private fun time(millis: Long, precise: Boolean = true) = GuideTimestamp(millis, if (precise) 14 else 8, millis.toString())
    private fun programme(start: Long, stop: Long?, title: String = "News", channel: String = "one", precise: Boolean = true) =
        GuideProgramme(channel, time(start, precise), stop?.let { time(it) }, listOf(LocalizedGuideText(title, "en")), emptyList())

    @Test fun searchTitleFoldsEveryLanguageAndStaysBounded() {
        val title = guideSearchTitle(listOf(LocalizedGuideText("ＢＢＣ News", "en"), LocalizedGuideText("Straße", "de"), LocalizedGuideText("bbc news", null)))!!
        assertEquals("bbc news\nstrasse", title)
        assertTrue(title.contains(guideSearchQuery("  BBC NEWS ")!!))
        assertTrue(title.contains(guideSearchQuery("STRASSE")!!))
        assertNull(guideSearchTitle(listOf(LocalizedGuideText("   ", "en"))))
        assertNull(guideSearchTitle(emptyList()))
        assertEquals(GUIDE_SEARCH_TITLE_CHARACTERS, guideSearchTitle(listOf(LocalizedGuideText("x".repeat(2000), null)))!!.length)
        val surrogate = guideSearchTitle(listOf(LocalizedGuideText("x".repeat(GUIDE_SEARCH_TITLE_CHARACTERS - 1) + "😀", null)))!!
        assertEquals(GUIDE_SEARCH_TITLE_CHARACTERS - 1, surrogate.length)
        assertFalse(Character.isHighSurrogate(surrogate.last()))
    }

    @Test fun queriesAreFoldedTrimmedAndBounded() {
        assertEquals(foldSearchText("ﬁlm"), guideSearchQuery(" FILM "))
        assertEquals(foldSearchText("οδος"), guideSearchQuery("ΟΔΟΣ"))
        assertNull(guideSearchQuery("   "))
        assertNull(guideSearchQuery("a\nb"))
        assertEquals("%_", guideSearchQuery("%_"))
        assertThrows(IllegalArgumentException::class.java) { guideSearchQuery("x".repeat(257)) }
    }

    @Test fun airingNeedsAPreciseStartBeforeNowAndAStopAfterIt() {
        assertTrue(guideAiringAt(programme(now, now + hour), now))
        assertFalse(guideAiringAt(programme(now - hour, now), now))
        assertFalse(guideAiringAt(programme(now + 1, now + hour), now))
        assertTrue(guideAiringAt(programme(now - hour, null), now))
        assertFalse(guideAiringAt(programme(now - GUIDE_AIRING_LOOKBACK_MILLIS, now + hour), now))
        assertFalse(guideAiringAt(programme(now - hour, now + hour, precise = false), now))
        assertFalse(guideAiringAt(GuideProgramme("one", time(now - hour), time(now + hour, false), emptyList(), emptyList()), now))
    }

    @Test fun nameKeysMatchTheImportAndCatalogueNormalisation() {
        val channel = GuideChannel("BBCOne.uk@SD", listOf(LocalizedGuideText("BBC One HD", "en"), LocalizedGuideText("UK: BBC One", null), LocalizedGuideText("X", null)))
        assertEquals(listOf("bbcone"), guideChannelNameKeys(channel))
        assertEquals("bbcone", channelNameKey("UK: BBC One FHD"))
        assertNull(channelNameKey("X"))
        assertEquals(listOf("itv"), guideChannelNameKeys(GuideChannel("itv.uk", emptyList())))
    }

    @Test fun candidatesCollectIdsNamesAndKeysWithinBounds() {
        val a = GuideKey("a", "bbc1.uk"); val b = GuideKey("b", "bbc1.uk")
        val one = GuideChannel("bbc1.uk", listOf(LocalizedGuideText("BBC One", null)))
        val wanted = guideAiringCandidates(listOf(a to one, b to one))
        assertEquals(setOf("bbc1.uk"), wanted.guideIds)
        assertEquals(setOf("bbcone", "bbc1"), wanted.nameKeys)
        assertEquals(setOf(a, b), wanted.keys)
        val many = (1..50).map { GuideKey("a", "c$it") to GuideChannel("c$it", listOf(LocalizedGuideText("Channel $it", null))) }
        assertEquals(10, guideAiringCandidates(many, maxNames = 10).nameKeys.size)
        assertEquals(50, guideAiringCandidates(many, maxNames = 10).guideIds.size)
    }

    @Test fun resultsFollowTheResolvedKeyAndKeepChannelOrderWithoutDuplicates() {
        val a = GuideKey("a", "one"); val b = GuideKey("b", "one")
        val early = programme(now - hour, now + hour, "Early"); val late = programme(now - 1, now + hour, "Late")
        val airing = earliestAiring(listOf(a to late, a to early, b to programme(now, now + 1, "Other")))
        assertEquals("Early", airing.getValue(a).titles.single().text)
        val channels = listOf("ch3" to b, "ch1" to a, "ch2" to null, "ch1" to a, "ch4" to GuideKey("c", "one"), "ch5" to a)
        val results = airingChannels(channels, { it.first }, { it.second }, airing, 10)
        assertEquals(listOf("ch3", "ch1", "ch5"), results.map { it.first.first })
        assertEquals(listOf("Other", "Early", "Early"), results.map { it.second.titles.single().text })
        assertEquals(listOf("ch3", "ch1"), airingChannels(channels, { it.first }, { it.second }, airing, 2).map { it.first.first })
        assertThrows(IllegalArgumentException::class.java) { airingChannels(channels, { it.first }, { it.second }, airing, 0) }
    }
}
