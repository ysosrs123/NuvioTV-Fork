package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class XmlTvGuideTest {
    @Test fun numericCharacterReferencesRemainValidText() {
        val channels = mutableListOf<GuideChannel>()
        XmlTvGuideParser().parse("<tv><channel id=\"one\"><display-name>&#65;&#x42;</display-name></channel></tv>".byteInputStream(), channels::add, {})
        assertEquals("AB", channels.single().names.single().text)
    }
    @Test fun offsetAndMissingTimezoneAreIndependentOfDeviceTimezone() {
        val before = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Australia/Brisbane"))
            assertEquals(XmlTvGuideParser.parseTimestamp("20261005070000 +1000")?.epochMillis, XmlTvGuideParser.parseTimestamp("20261004210000")?.epochMillis)
            assertNotEquals(XmlTvGuideParser.parseTimestamp("20261101013000 -0400")?.epochMillis, XmlTvGuideParser.parseTimestamp("20261101013000 -0500")?.epochMillis)
        } finally { TimeZone.setDefault(before) }
    }
    @Test fun reducedPrecisionAndMissingStopAreNotSchedulable() {
        val programmes = mutableListOf<GuideProgramme>()
        XmlTvGuideParser().parse("""<tv><programme channel="one" start="202610"><title>Month</title></programme></tv>""".byteInputStream(), {}, programmes::add)
        assertEquals(6, programmes.single().start.precisionDigits)
        assertNull(programmes.single().stop); assertFalse(programmes.single().canSchedulePrecisely)
    }
    @Test fun streamingCallbacksPreserveLanguageVariantsAndCase() {
        val channels = mutableListOf<GuideChannel>(); val programmes = mutableListOf<GuideProgramme>()
        val result = XmlTvGuideParser().parse("""<tv><channel id="CaseID"><display-name lang="en">News &amp; Sport</display-name></channel><programme channel="CaseID" start="20261005070000 +1000" stop="20261005080000 +1000"><title lang="en">News</title><title lang="fr">Actualités</title><desc>Details</desc></programme></tv>""".byteInputStream(), channels::add, programmes::add)
        assertEquals(GuideParseSummary(1, 1, 0), result)
        assertEquals("CaseID", programmes.single().channelExternalId)
        assertEquals("News & Sport", channels.single().names.single().text)
        assertEquals(listOf("en", "fr"), programmes.single().titles.map { it.language })
        assertTrue(programmes.single().canSchedulePrecisely)
    }
    @Test fun malformedAndReversedTimesAreQuarantined() {
        val result = XmlTvGuideParser().parse("""<tv><programme channel="one" start="20260230070000"/><programme channel="one" start="20261005080000" stop="20261005070000"/><programme channel="one" start="20261005080000" stop="bad"/></tv>""".byteInputStream(), {}, { fail("No invalid programme should publish") })
        assertEquals(3, result.rejectedProgrammes)
    }
    @Test(expected = IllegalArgumentException::class) fun externalDtdIsRejected() {
        XmlTvGuideParser().parse("""<!DOCTYPE tv SYSTEM "http://127.0.0.1:9/never"><tv/>""".byteInputStream(), {}, {})
    }
    @Test(expected = IllegalArgumentException::class) fun internalEntityExpansionIsRejected() {
        XmlTvGuideParser().parse("""<!DOCTYPE tv [<!ENTITY secret SYSTEM "file:///never">]><tv/>""".byteInputStream(), {}, {})
    }
    @Test(expected = IllegalArgumentException::class) fun expandedByteLimitStopsInput() {
        XmlTvGuideParser(GuideParseLimits(expandedBytes = 10)).parse("<tv><channel id=\"one\"/></tv>".byteInputStream(), {}, {})
    }
    @Test(expected = IllegalArgumentException::class) fun manyTextVariantsCannotEvadePerRecordLimit() {
        XmlTvGuideParser(GuideParseLimits(textCharacters = 10)).parse("""<tv><channel id="one"><display-name>123456</display-name><display-name>123456</display-name></channel></tv>""".byteInputStream(), {}, {})
    }
    @Test fun oversizedCommentTextOrAttributeStopsBeforeTheTokenIsBuilt() {
        val limits = GuideParseLimits(textCharacters = 16)
        val oversized = "x".repeat(16 * 4 + 64 * 1024 + 1)
        for (document in listOf("<tv><!--$oversized--></tv>", "<tv>$oversized</tv>", "<tv a=\"$oversized\"></tv>", "<tv a=\"${">".repeat(16 * 4 + 64 * 1024 + 1)}\"></tv>")) {
            try { XmlTvGuideParser(limits).parse(document.byteInputStream(), {}, {}); fail() }
            catch (error: IllegalArgumentException) { assertEquals("Guide token limit", error.message) }
        }
        val ok = "<tv><channel id=\"a\"><display-name>${"y".repeat(16)}</display-name></channel></tv>"
        assertEquals(1, XmlTvGuideParser(limits).parse(ok.byteInputStream(), {}, {}).channels)
    }
    @Test fun smallQuarantineIsAcceptedButMostlyBrokenFeedsAreNot() {
        assertTrue(guideQuarantineAccepted(1, 1)); assertTrue(guideQuarantineAccepted(0, 16)); assertFalse(guideQuarantineAccepted(1, 17))
        assertTrue(guideQuarantineAccepted(9_800, 200)); assertFalse(guideQuarantineAccepted(9_799, 201))
    }
    @Test fun duplicateChannelBlocksMergeBoundedDistinctNames() {
        val one = GuideChannel("one", listOf(LocalizedGuideText("One", "en")))
        val merged = mergeGuideChannel(one, GuideChannel("one", listOf(LocalizedGuideText("One", "en"), LocalizedGuideText("Un", "fr"))))
        assertEquals(listOf("One", "Un"), merged.names.map { it.text })
        val many = GuideChannel("one", (0 until 40).map { LocalizedGuideText("N$it", null) })
        assertEquals(32, mergeGuideChannel(one, many).names.size)
        try { mergeGuideChannel(one, GuideChannel("two", emptyList())); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test(expected = IllegalArgumentException::class) fun nestingLimitIsEnforced() {
        XmlTvGuideParser(GuideParseLimits(depth = 2)).parse("<tv><channel id=\"one\"><display-name>A</display-name></channel></tv>".byteInputStream(), {}, {})
    }
}
