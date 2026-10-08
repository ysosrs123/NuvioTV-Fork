package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class GuideImportTest {
    private fun at(text: String) = Instant.parse(text).toEpochMilli()
    private fun channel(id: String, vararg names: String) = GuideChannel(id, names.map { LocalizedGuideText(it, null) })

    @Test fun lenientTimestampsAcceptRealWorldOffsets() {
        val expected = at("2026-10-05T06:00:00Z")
        for (raw in listOf("20261005070000 +0100", "20261005070000+0100", "20261005070000 +01:00", "20261005070000+01:00", "20261005070000 +01",
            "20261005060000 Z", "20261005060000Z", "20261005060000 utc", "20261005060000 GMT", "20261005060000", "202610050600 +0000", "202610050700+01:00",
            " 20261005020000 -0400 ", "20261005013000 -04:30")) {
            assertEquals(raw, expected, XmlTvGuideParser.parseTimestamp(raw)?.epochMillis)
        }
        assertEquals(12, XmlTvGuideParser.parseTimestamp("202610050600 +0000")!!.precisionDigits)
        assertTrue(XmlTvGuideParser.parseTimestamp("202610050600")!!.precise)
        for (raw in listOf("20261005070000 +2500", "20261005070000 +01:75", "2026-10-05 07:00", "20261005070000 CET", "20261305070000", "", "2026100507000")) {
            assertNull(raw, XmlTvGuideParser.parseTimestamp(raw))
        }
    }

    @Test fun guideDaysWindowFollowsTheSetting() {
        val now = at("2026-10-05T15:30:00Z")
        val day = 86_400_000L
        val midnight = at("2026-10-05T00:00:00Z")
        assertEquals(midnight - day to midnight + 4 * day, GuideDays().window(now))
        assertEquals(midnight - 7 * day to midnight + 8 * day, GuideDays(7, 7).window(now))
        assertEquals(6, GuideDays.OPTIONS.size)
        assertEquals(GuideDays(), GuideDays.of(2, 5))
        assertEquals(GuideDays(3, 7), GuideDays.of(3, 7))
        assertThrows(IllegalArgumentException::class.java) { GuideDays(2, 3) }
    }

    @Test fun filterMatchesIdsWithoutSuffixCaseAndNames() {
        val filter = GuideImportFilter(listOf("BBC1.uk", " cnn.us "), listOf(guideMatchName("Sky Sports Main Event HD")))
        assertTrue(filter.matches(channel("bbc1.UK")))
        assertTrue(filter.matches(channel("cnn.us@SD")))
        assertTrue(filter.matches(channel("x123", "Sky Sports Main Event")))
        assertFalse(filter.matches(channel("itv1.uk", "ITV1")))
        assertTrue(GuideImportFilter(emptyList(), emptyList()).empty)
        assertFalse(GuideImportFilter(emptyList(), emptyList()).matches(channel("any")))
        assertEquals(filter.fingerprint, GuideImportFilter(listOf("cnn.us", "bbc1.uk"), listOf(guideMatchName("Sky Sports Main Event"))).fingerprint)
        assertNotEquals(filter.fingerprint, GuideImportFilter(listOf("cnn.us"), filter.names).fingerprint)
    }

    @Test fun programmeChannelsMatchIgnoringCaseAndUnknownOnesAreImplied() {
        val channels = GuideImportChannels(null, 10)
        assertEquals(GuideImportChannels.Admission.NEW, channels.channel(channel("BBC.One", "BBC One")))
        assertEquals("BBC.One", channels.programme(" bbc.one "))
        assertEquals(GuideImportChannels.Admission.DUPLICATE, channels.channel(channel("bbc.one", "BBC 1")))
        assertEquals("BBC.One", channels.storedId("BBC.ONE"))
        assertEquals("Loose", channels.programme("Loose"))
        assertEquals(listOf(channel("Loose", "Loose")), channels.impliedChannels)
        assertEquals(2, channels.channelCount)
    }

    @Test fun lateChannelElementsRenameImpliedProgrammes() {
        val channels = GuideImportChannels(null, 10)
        assertEquals("early", channels.programme("early"))
        assertEquals(GuideImportChannels.Admission.NEW, channels.channel(channel("EARLY", "Early")))
        assertEquals(listOf("early" to "EARLY"), channels.renames)
        assertTrue(channels.impliedChannels.isEmpty())
        assertEquals("EARLY", channels.programme("early"))
    }

    @Test fun filteredImportKeepsChannelsButOnlyWantedProgrammes() {
        val channels = GuideImportChannels(GuideImportFilter(listOf("one"), listOf(guideMatchName("Sport Two"))), 3)
        channels.channel(channel("one", "One")); channels.channel(channel("two", "Sport Two")); channels.channel(channel("three", "Three"))
        assertEquals("one", channels.programme("ONE"))
        assertEquals("two", channels.programme("two"))
        assertNull(channels.programme("three"))
        assertTrue(channels.wants("One")); assertFalse(channels.wants("three")); assertFalse(channels.wants(null))
        assertEquals(GuideImportChannels.Admission.SKIPPED, channels.channel(channel("four")))
        assertNull(channels.programme("five"))
        assertEquals(1, channels.skipped)
    }

    @Test fun parserReportsRejectedProgrammesWithTheirChannelAndStart() {
        val rejected = mutableListOf<Pair<String?, Long?>>()
        val programmes = mutableListOf<GuideProgramme>()
        XmlTvGuideParser().parse(("<tv><channel id=\" spaced \"><display-name>S</display-name></channel>" +
            "<programme channel=\" spaced \" start=\"20261005060000 +0000\" stop=\"bad\"/>" +
            "<programme channel=\"other\" start=\"never\"/>" +
            "<programme channel=\"Spaced\" start=\"20261005060000+00:00\" stop=\"20261005070000Z\"><title>Ok</title></programme></tv>").byteInputStream(),
            {}, programmes::add) { id, start -> rejected += id to start }
        assertEquals(listOf("spaced" to at("2026-10-05T06:00:00Z"), "other" to null), rejected)
        assertEquals("Spaced", programmes.single().channelExternalId)
    }

    @Test fun capacityIsBoundedByFreeSpace() {
        val mb = 1024L * 1024
        assertEquals(GUIDE_MIN_DATABASE_BYTES, guideDatabaseCap(0))
        assertEquals(GUIDE_MIN_DATABASE_BYTES, guideDatabaseCap(600 * mb))
        assertEquals(500 * mb, guideDatabaseCap(2000 * mb))
        assertEquals(GUIDE_MAX_DATABASE_BYTES, guideDatabaseCap(64 * 1024 * mb))
        assertEquals(128 * mb, guideFeedBudget(256 * mb, 0, true))
        assertEquals(256 * mb, guideFeedBudget(256 * mb, 0, false))
        assertEquals(64 * mb, guideFeedBudget(256 * mb, 2, true))
        assertEquals(GUIDE_MIN_FEED_BUDGET_BYTES, guideFeedBudget(256 * mb, 100, true))
        assertTrue(guideProgrammeBytes("bbc.one", "{}", "news") > 50)
        val day = 86_400_000L
        assertEquals(200, guideStorageCaps(0, 5 * day).programmesPerChannel)
        assertEquals(600, guideStorageCaps(0, 15 * day).programmesPerChannel)
        assertEquals(250, guideStorageCaps(0, day).descriptionCharacters)
    }

    @Test fun descriptionsAreShortAndOnePerLanguage() {
        val programme = GuideProgramme("one", GuideTimestamp(0, 14), null, listOf(LocalizedGuideText("T", null)),
            listOf(LocalizedGuideText("x".repeat(400), "en"), LocalizedGuideText("dup", "en"), LocalizedGuideText("fr", "fr"), LocalizedGuideText("de", "de"), LocalizedGuideText("it", "it")))
        val kept = GuideProgrammeCap().admit(programme)!!.descriptions
        assertEquals(listOf("en", "fr", "de"), kept.map { it.language })
        assertTrue(kept.first().text.length <= 250)
    }

    @Test fun decisionsNameTheReason() {
        fun decide(channels: Int = 5, programmes: Long = 100, matched: Long = 50, inWindow: Long = 40, stored: Long = 40, rejected: Long = 0,
            previous: Long = 0, wanted: Boolean = true, comparable: Boolean = true) =
            guideImportDecision(GuideImportCounts(channels, programmes, matched, inWindow, stored, rejected, previous, wanted, comparable))
        assertEquals(GuideImportResult(RefreshDecision.PUBLISH), decide())
        assertEquals(GuideImportResult(RefreshDecision.EMPTY_REQUIRES_REVIEW, GuideImportIssue.NO_CHANNELS), decide(channels = 0, programmes = 0, matched = 0, inWindow = 0, stored = 0))
        assertEquals(GuideImportIssue.OUT_OF_DATE, decide(inWindow = 0, stored = 0).issue)
        assertEquals(GuideImportIssue.OUT_OF_DATE, decide(programmes = 0, matched = 0, inWindow = 0, stored = 0).issue)
        assertEquals(GuideImportIssue.TOO_MANY_INVALID, decide(inWindow = 100, stored = 100, rejected = 17).issue)
        assertEquals(RefreshDecision.PUBLISH, decide(inWindow = 1000, stored = 1000, rejected = 20).decision)
        assertEquals(GuideImportIssue.TOO_MANY_INVALID, decide(inWindow = 0, stored = 0, rejected = 1).issue)
        assertEquals(GuideImportIssue.FEWER_PROGRAMMES, decide(previous = 100).issue)
        assertEquals(RefreshDecision.PUBLISH, decide(previous = 100, comparable = false).decision)
        assertEquals(GuideImportResult(RefreshDecision.PUBLISH, GuideImportIssue.NOT_LINKED), decide(matched = 0, inWindow = 0, stored = 0, wanted = false))
        assertEquals(RefreshDecision.PUBLISH, decide(matched = 0, inWindow = 0, stored = 0).decision)
        assertEquals(GuideImportIssue.FEWER_PROGRAMMES, decide(matched = 0, inWindow = 0, stored = 0, previous = 10).issue)
    }

    @Test fun failuresMapToSpecificIssues() {
        assertEquals(GuideImportIssue.NOT_XMLTV, guideFailureIssue(GuideFormatException(GuideFormatIssue.HTML)))
        assertEquals(GuideImportIssue.TOO_LARGE, guideFailureIssue(GuideFormatException(GuideFormatIssue.INPUT_LIMIT)))
        val limit = runCatching { XmlTvGuideParser(GuideParseLimits(channels = 1)).parse("<tv><channel id=\"a\"/><channel id=\"b\"/></tv>".byteInputStream(), {}, {}) }.exceptionOrNull()
        assertEquals(GuideImportIssue.TOO_LARGE, guideFailureIssue(limit!!))
        assertNull(guideFailureIssue(IllegalArgumentException("Incomplete XMLTV")))
    }

    @Test fun guideChoicePrefersTheFirstMatchWithProgrammes() {
        val feeds = listOf(GuideFeedIndex("provider", setOf("bbc1.uk")), GuideFeedIndex("xmltv", setOf("bbc1.uk", "BBC1")))
        val names = listOf(GuideNameIndex("xmltv", mapOf(guideMatchName("BBC One") to setOf("bbc-one"))))
        val order = listOf("provider", "xmltv")
        val candidates = guideCandidates("bbc1.uk", null, feeds, order, "BBC One", names)
        assertEquals(listOf(GuideKey("provider", "bbc1.uk"), GuideKey("xmltv", "bbc1.uk"), GuideKey("xmltv", "bbc-one")), candidates.map { it.key })
        assertEquals(GuideMatch(GuideKey("provider", "bbc1.uk"), GuideMatchReason.EXACT_ID), chooseGuide(candidates, null) { true })
        assertEquals(GuideKey("xmltv", "bbc1.uk"), chooseGuide(candidates, null) { it.feedId == "xmltv" }.key)
        assertEquals(GuideMatch(GuideKey("xmltv", "bbc-one"), GuideMatchReason.NAME), chooseGuide(candidates, null) { it.externalId == "bbc-one" })
        assertEquals(GuideKey("provider", "bbc1.uk"), chooseGuide(candidates, null) { false }.key)
    }

    @Test fun manualAssignmentComesFirstAndFallsBackWhenMissing() {
        val feeds = listOf(GuideFeedIndex("a", setOf("x", "BBC1")), GuideFeedIndex("b", setOf("manual")))
        val manual = GuideKey("b", "manual")
        val candidates = guideCandidates("BBC1@HD", manual, feeds, listOf("a", "b"))
        assertEquals(listOf(GuideMatch(manual, GuideMatchReason.MANUAL), GuideMatch(GuideKey("a", "BBC1"), GuideMatchReason.EXACT_ID)), candidates)
        assertEquals(manual, chooseGuide(candidates, manual) { true }.key)
        assertEquals(GuideKey("a", "BBC1"), chooseGuide(candidates, manual) { it.feedId == "a" }.key)
        val missing = guideCandidates("none", GuideKey("b", "gone"), feeds, listOf("a", "b"))
        assertEquals(GuideMatchReason.MISSING_MANUAL_TARGET, chooseGuide(missing, GuideKey("b", "gone")) { true }.reason)
        assertEquals(GuideMatchReason.NONE, chooseGuide(emptyList(), null) { true }.reason)
        val ambiguous = listOf(GuideNameIndex("a", mapOf("bbcone" to setOf("1", "2"))), GuideNameIndex("b", mapOf("bbcone" to setOf("3"))))
        assertEquals(listOf(GuideKey("b", "3")), guideCandidates(null, null, emptyList(), listOf("a", "b"), "BBC One", ambiguous).map { it.key })
    }

    @Test fun newGuidesAreAppendedAfterTheProviderGuide() {
        assertEquals(listOf("provider", "new") to listOf("provider", "new"), appendGuideLink(listOf("provider"), emptyList(), "new"))
        assertEquals(listOf("a", "b", "new") to listOf("b", "a", "new"), appendGuideLink(listOf("a", "b"), listOf("b"), "new"))
        assertNull(appendGuideLink(listOf("new"), emptyList(), "new"))
        assertNull(appendGuideLink((1..16).map { "f$it" }, emptyList(), "new"))
    }
}
