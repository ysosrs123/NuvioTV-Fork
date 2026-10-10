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
    @Test fun programmeCategoriesAreKeptDistinctAndBounded() {
        val programmes = mutableListOf<GuideProgramme>()
        val categories = (1..10).joinToString("") { "<category lang=\"en\">Genre $it</category>" }
        XmlTvGuideParser().parse("""<tv><programme channel="one" start="20261005070000 +0000"><title>Match</title><category>Sports</category><category>Sports</category>$categories</programme></tv>""".byteInputStream(), {}, programmes::add)
        val parsed = programmes.single().categories
        assertEquals(8, parsed.size)
        assertEquals(listOf("Sports", "Genre 1"), parsed.take(2))
        assertEquals(listOf(LocalizedGuideText("Match", null)), programmes.single().titles)
    }
    @Test fun channelsWithoutAnIdAreSkippedAndTheRestOfTheGuideKept() {
        val channels = mutableListOf<GuideChannel>(); val programmes = mutableListOf<GuideProgramme>()
        val result = XmlTvGuideParser().parse("""<tv><channel><display-name>No id</display-name></channel><channel id=""><display-name>Blank</display-name></channel><channel id="one"><display-name>One</display-name></channel><programme channel="one" start="20261005070000 +0000"><title>News</title></programme></tv>""".byteInputStream(), channels::add, programmes::add)
        assertEquals(listOf("one"), channels.map { it.externalId })
        assertEquals(1, programmes.size)
        assertEquals(3, result.channels)
    }
    @Test fun malformedAndReversedTimesAreQuarantined() {
        val result = XmlTvGuideParser().parse("""<tv><programme channel="one" start="20260230070000"/><programme channel="one" start="20261005080000" stop="20261005070000"/><programme channel="one" start="20261005080000" stop="bad"/></tv>""".byteInputStream(), {}, { fail("No invalid programme should publish") })
        assertEquals(3, result.rejectedProgrammes)
    }
    @Test fun externalDtdReferenceIsIgnoredWithoutBeingFetched() {
        val channels = mutableListOf<GuideChannel>()
        XmlTvGuideParser().parse("""<?xml version="1.0"?><!DOCTYPE tv SYSTEM "http://127.0.0.1:9/never"><tv><channel id="a"/></tv>""".byteInputStream(), channels::add, {})
        assertEquals("a", channels.single().externalId)
    }
    @Test(expected = IllegalArgumentException::class) fun doctypeForAnotherRootIsRejected() {
        XmlTvGuideParser().parse("""<!DOCTYPE html><tv/>""".byteInputStream(), {}, {})
    }
    @Test fun unknownNamedEntitiesStayLiteralInsteadOfFailingTheGuide() {
        val channels = mutableListOf<GuideChannel>()
        XmlTvGuideParser().parse("""<tv><channel id="a"><display-name>News&nbsp;24 &amp; more</display-name></channel></tv>""".byteInputStream(), channels::add, {})
        assertEquals("News&nbsp;24 & more", channels.single().names.single().text)
    }
    @Test fun wrongRootIsReportedAsNotXmltv() {
        try { XmlTvGuideParser().parse("<rss><channel/></rss>".byteInputStream(), {}, {}); fail() }
        catch (error: GuideFormatException) { assertEquals(GuideFormatIssue.NOT_XMLTV, error.issue) }
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
    @Test fun unwantedProgrammesAreSkippedButCounted() {
        val programmes = mutableListOf<GuideProgramme>(); val rejected = mutableListOf<String?>(); val asked = mutableListOf<String>()
        val xml = """<tv><programme channel="keep" start="20261005070000 +1000" stop="20261005080000 +1000"><title>A</title><desc>D</desc><category>News</category></programme><programme channel="drop" start="20261005070000 +1000" stop="20261005080000 +1000"><title>B</title><icon src="https://x/i.png"/></programme><programme channel="drop" start="bad"><title>C</title></programme><programme channel="keep" start="20261005080000 +1000" stop="20261005090000 +1000"><title>E</title></programme><programme channel="keep" start="bad"><title>F</title></programme></tv>"""
        val result = XmlTvGuideParser().parse(xml.byteInputStream(), {}, programmes::add, { asked += it; it == "keep" }) { id, _ -> rejected += id }
        assertEquals(listOf("A", "E"), programmes.map { it.titles.single().text })
        assertEquals(listOf("D"), programmes.first().descriptions.map { it.text }); assertEquals(listOf("News"), programmes.first().categories)
        assertNull(programmes.last().descriptions.firstOrNull()); assertNull(programmes.last().icon)
        assertEquals(listOf<String?>("keep"), rejected)
        assertEquals(GuideParseSummary(0, 4, 1), result)
        assertEquals(listOf("keep", "drop", "drop", "keep", "keep"), asked)
    }
    @Test fun sharedTimestampsParseTheSameAsSeparateOnes() {
        val programmes = mutableListOf<GuideProgramme>()
        XmlTvGuideParser().parse("""<tv><programme channel="a" start="20261005070000 +1000" stop="20261005080000 +1000"><title>A</title></programme><programme channel="a" start="20261005080000 +1000" stop="20261005090000 +0000"><title>B</title></programme></tv>""".byteInputStream(), {}, programmes::add)
        assertEquals(programmes[0].stop, programmes[1].start)
        assertEquals(XmlTvGuideParser.parseTimestamp("20261005090000 +0000"), programmes[1].stop)
    }
}
