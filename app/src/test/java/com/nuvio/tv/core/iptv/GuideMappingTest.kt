package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class GuideMappingTest {
    private val feeds = listOf(GuideFeedIndex("first", setOf("one")), GuideFeedIndex("second", setOf("one", "two")))
    @Test fun duplicateIdsRequireAnExplicitPriority() {
        val ambiguous = resolveGuideMapping("one", null, feeds)
        assertEquals(GuideMatchReason.AMBIGUOUS, ambiguous.reason)
        assertNull(ambiguous.key)
        assertEquals(2, ambiguous.candidates.size)
        assertEquals(GuideKey("second", "one"), resolveGuideMapping("one", null, feeds, listOf("second", "first")).key)
    }
    @Test fun manualMappingWinsOverSourceIdAndPriority() {
        assertEquals(GuideKey("second", "two"), resolveGuideMapping("one", GuideKey("second", "two"), feeds, listOf("first")).key)
    }
    @Test fun missingManualTargetDoesNotRebindToAnotherFeed() {
        val result = resolveGuideMapping("one", GuideKey("removed", "one"), feeds, listOf("first"))
        assertEquals(GuideMatchReason.MISSING_MANUAL_TARGET, result.reason)
        assertNull(result.key)
    }
    @Test fun feedCompletionOrderDoesNotChangeAnExplicitChoice() {
        val priority = listOf("second", "first")
        assertEquals(resolveGuideMapping("one", null, feeds, priority).key, resolveGuideMapping("one", null, feeds.reversed(), priority).key)
        assertEquals(GuideKey("second", "two"), resolveGuideMapping("two", null, feeds).key)
    }
    @Test fun nowUsesHalfOpenIntervalsAndDoesNotGuessMissingStops() {
        fun programme(start: Long, stop: Long?) = GuideProgramme("one", GuideTimestamp(start, 14, ""), stop?.let { GuideTimestamp(it, 14, "") }, emptyList(), emptyList())
        val previous = programme(0, 100)
        val current = programme(100, 200)
        val unknown = programme(50, null)
        val otherChannel = current.copy(channelExternalId = "two")
        assertEquals(listOf(current), programmesAt(listOf(previous, current, unknown, otherChannel), "one", 100))
    }
    @Test fun feedSuffixesFallBackToTheBaseIdOnlyWithoutAnExactMatch() {
        val index = listOf(GuideFeedIndex("f", setOf("abc.uk", "news.uk@HD")))
        assertEquals(GuideMatch(GuideKey("f", "abc.uk"), GuideMatchReason.EXACT_ID, listOf(GuideKey("f", "abc.uk"))), resolveGuideMapping("abc.uk@SD", null, index))
        assertEquals(GuideKey("f", "news.uk@HD"), resolveGuideMapping("news.uk@HD", null, index).key)
        assertEquals(GuideMatchReason.NONE, resolveGuideMapping("other.uk@SD", null, index).reason)
        assertEquals(GuideMatchReason.NONE, resolveGuideMapping("@SD", null, listOf(GuideFeedIndex("f", setOf("")))).reason)
        val two = listOf(GuideFeedIndex("a", setOf("abc.uk")), GuideFeedIndex("b", setOf("abc.uk")))
        assertEquals(GuideMatchReason.AMBIGUOUS, resolveGuideMapping("abc.uk@SD", null, two).reason)
        assertEquals(GuideKey("b", "abc.uk"), resolveGuideMapping("abc.uk@SD", null, two, listOf("b")).key)
    }
    @Test fun feedSuffixIsOnlyATrailingAtToken() {
        assertEquals("abc.uk", guideIdWithoutFeedSuffix(" abc.uk@SD "))
        assertEquals("abc.uk", guideIdWithoutFeedSuffix("abc.uk@East-1"))
        assertNull(guideIdWithoutFeedSuffix("abc.uk"))
        assertNull(guideIdWithoutFeedSuffix("@SD"))
        assertNull(guideIdWithoutFeedSuffix("abc@ex ample"))
        assertNull(guideIdWithoutFeedSuffix("abc.uk@" + "x".repeat(17)))
    }
}
