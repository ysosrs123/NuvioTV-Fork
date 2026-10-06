package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class GuideNameMatchingTest {
    @Test fun regionPrefixesQualityTagsAndSymbolsAreIgnored() {
        for (name in listOf("UK: BBC One HD", "BBC One", "US | BBC ONE FHD", "BBC One (Backup)", "[UK] BBC One 1080p", "bbc-one 60fps"))
            assertEquals(name, "bbcone", guideMatchName(name))
        assertEquals("bbcone", guideIdMatchName("BBCOne.uk"))
        assertEquals("tf1", guideMatchName("FR: TF1 ᴴᴰ".replace("ᴴᴰ", "HD")))
        assertEquals("televisioncanaria", guideMatchName("Televisión Canaria"))
        assertEquals(guideMatchName("Первый канал"), guideMatchName("RU: Первый канал HD"))
        assertTrue(guideMatchName("Первый канал").isNotEmpty())
    }

    @Test fun timeshiftAndNumberedChannelsStayDistinct() {
        assertEquals("bbconeplus1", guideMatchName("BBC One +1"))
        assertNotEquals(guideMatchName("Sky Sports 1"), guideMatchName("Sky Sports 2"))
        assertEquals("4k", guideMatchName("4K"))
        assertEquals(guideMatchName("Law and Order"), guideMatchName("Law & Order"))
    }

    @Test fun onlyAUniqueMatchInTheFirstFeedThatKnowsTheNameIsUsed() {
        val first = GuideNameIndex("a", mapOf("bbcone" to setOf("bbc1.uk"), "news" to setOf("n1", "n2")))
        val second = GuideNameIndex("b", mapOf("bbcone" to setOf("other"), "itv" to setOf("itv.uk")))
        assertEquals(GuideKey("a", "bbc1.uk"), uniqueNameMatch("UK: BBC One HD", listOf(first, second)))
        assertEquals(GuideKey("b", "itv.uk"), uniqueNameMatch("ITV", listOf(first, second)))
        assertNull(uniqueNameMatch("News", listOf(first, second)))
        assertNull(uniqueNameMatch("Unknown", listOf(first, second)))
        assertNull(uniqueNameMatch("+", listOf(first, second)))
    }
}
