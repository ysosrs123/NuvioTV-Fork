package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class GuideStorageCapsTest {
    private fun programme(channel: String, start: Long, description: String = "Short") =
        GuideProgramme(channel, GuideTimestamp(start, 14, ""), GuideTimestamp(start + 1, 14, ""), listOf(LocalizedGuideText("Title", null)),
            listOf(LocalizedGuideText(description, "en")))

    @Test fun eachChannelKeepsAtMostItsCapAndOthersAreUnaffected() {
        val cap = GuideProgrammeCap(GuideStorageCaps(programmesPerChannel = 3))
        val kept = (0L until 5).mapNotNull { cap.admit(programme("one", it)) } + (0L until 2).mapNotNull { cap.admit(programme("two", it)) }
        assertEquals(listOf(0L, 1, 2, 0, 1), kept.map { it.start.epochMillis })
        assertEquals(2L, cap.dropped)
    }

    @Test fun distinctChannelsAreBounded() {
        val cap = GuideProgrammeCap(GuideStorageCaps(channels = 2))
        assertNotNull(cap.admit(programme("a", 0))); assertNotNull(cap.admit(programme("b", 0)))
        assertNull(cap.admit(programme("c", 0)))
        assertNotNull(cap.admit(programme("a", 1)))
    }

    @Test fun longDescriptionsAreShortenedAndShortOnesUntouched() {
        val cap = GuideProgrammeCap(GuideStorageCaps(descriptionCharacters = 400))
        val short = programme("one", 0)
        assertSame(short, cap.admit(short))
        val long = cap.admit(programme("one", 1, "word ".repeat(200)))!!
        val text = long.descriptions.single().text
        assertTrue(text.length <= 400); assertTrue(text.endsWith("…")); assertTrue(text.startsWith("word word"))
        assertEquals("en", long.descriptions.single().language)
        assertEquals(long.titles, short.titles)
    }

    @Test fun shorteningNeverSplitsASurrogatePairOrExceedsTheLimit() {
        val emoji = "📺"
        for (limit in 1..12) {
            val text = shortenGuideText(emoji.repeat(10), limit)
            assertTrue(text.length <= limit)
            assertFalse(text.dropLast(1).lastOrNull()?.let(Character::isHighSurrogate) ?: false)
        }
        assertEquals("abc", shortenGuideText("abc", 3))
        assertEquals("abcdefgh…", shortenGuideText("abcdefghijklmnop", 9))
    }
}
