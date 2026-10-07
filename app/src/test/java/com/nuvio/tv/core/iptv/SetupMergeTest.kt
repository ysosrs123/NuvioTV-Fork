package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class SetupMergeTest {
    private val bundle = SetupBundle(true,
        listOf(BundleSource("s0", "Lounge", SetupKind.XTREAM, "http://X.example:8080/", "me", "new-password"),
            BundleSource("s1", "List", SetupKind.M3U, "https://lists.example/a.m3u"),
            BundleSource("s2", "Portal", SetupKind.STALKER, "http://portal.example/c/", "00:1a:79:00:00:01"),
            BundleSource("s3", "Needs login", SetupKind.XTREAM, "http://y.example", null, null)),
        guides = listOf(BundleGuide("g0", "Guide", "https://epg.example/guide.xml"), BundleGuide("g1", "Other", "https://epg.example/other.xml")),
        settings = BundleSettings(SetupSettings()))
    private val existing = listOf(ExistingSource("old-x", SetupKind.XTREAM, "http://x.example:8080", "me"),
        ExistingSource("old-p", SetupKind.STALKER, "http://portal.example/c", "00:1A:79:00:00:01"),
        ExistingSource("old-m", SetupKind.M3U, "https://lists.example/b.m3u", null))
    private val guides = listOf(ExistingGuide("feed-1", "https://epg.example/guide.xml"), ExistingGuide("feed-2", "https://epg.example/third.xml"))

    @Test fun mergeReusesMatchingSourcesAndGuides() {
        val plan = SetupMerge.plan(bundle, existing, guides, SetupImportMode.MERGE)
        assertEquals(mapOf("s0" to "old-x", "s2" to "old-p"), plan.reuseSources)
        assertEquals(listOf("s1"), plan.createSources.map { it.key })
        assertEquals(listOf("s3"), plan.skippedSources.map { it.key })
        assertEquals(mapOf("g0" to "feed-1"), plan.reuseGuides)
        assertEquals(listOf("g1"), plan.createGuides.map { it.key })
        assertTrue(plan.removeSources.isEmpty() && plan.removeGuides.isEmpty())
        assertFalse(plan.applySettings)
    }

    @Test fun replaceRemovesEverythingAndAppliesSettings() {
        val plan = SetupMerge.plan(bundle, existing, guides, SetupImportMode.REPLACE)
        assertEquals(listOf("old-x", "old-p", "old-m"), plan.removeSources)
        assertEquals(listOf("feed-1", "feed-2"), plan.removeGuides)
        assertEquals(listOf("s0", "s1", "s2"), plan.createSources.map { it.key })
        assertEquals(2, plan.createGuides.size)
        assertTrue(plan.applySettings)
        assertFalse(SetupMerge.plan(bundle.copy(settings = null), existing, guides, SetupImportMode.REPLACE).applySettings)
    }

    @Test fun differentLoginsAreDifferentSources() {
        assertNotEquals(SetupMerge.identity(SetupKind.XTREAM, "http://x.example", "me"), SetupMerge.identity(SetupKind.XTREAM, "http://x.example", "you"))
        assertEquals(SetupMerge.identity(SetupKind.XTREAM, "http://x.example:80/", "me"), SetupMerge.identity(SetupKind.XTREAM, "http://X.example", "me"))
        assertNotEquals(SetupMerge.identity(SetupKind.M3U, "http://x.example/a.m3u?t=1", null), SetupMerge.identity(SetupKind.M3U, "http://x.example/a.m3u?t=2", null))
        assertNull(SetupMerge.identity(SetupKind.XTREAM, "http://x.example", null))
        assertNull(SetupMerge.identity(SetupKind.STALKER, "http://x.example/c/", "nope"))
    }

    @Test fun linksKeepExistingOrderFirst() {
        assertEquals(listOf("a", "b", "c"), SetupMerge.links(listOf("a", "b"), listOf("b", "c")))
        assertEquals(16, SetupMerge.links((1..10).map { "e$it" }, (1..10).map { "i$it" }).size)
    }

    @Test fun overlaysMatchByProviderThenLocatorThenGuideThenName() {
        val channels = listOf(MatchableChannel("c1", "BBC One", "101", "aaa", "bbc1"), MatchableChannel("c2", "BBC One", "102", "bbb", "bbc1.hd"),
            MatchableChannel("c3", "News", null, "ccc", null), MatchableChannel("c4", "Sport", null, null, "sport.tv"),
            MatchableChannel("c5", "Film", null, null, null), MatchableChannel("c6", "Dup", null, null, null), MatchableChannel("c7", "Dup", null, null, null))
        val overlays = listOf(BundleOverlay("s", ChannelMatch("BBC One", "102")), BundleOverlay("s", ChannelMatch("News", locator = "ccc")),
            BundleOverlay("s", ChannelMatch("SPORT", guideId = "sport.tv")), BundleOverlay("s", ChannelMatch("film")),
            BundleOverlay("s", ChannelMatch("Dup")), BundleOverlay("s", ChannelMatch("Missing", "999")),
            BundleOverlay("s", ChannelMatch("BBC One Renamed", "101")))
        val matched = SetupMerge.match(overlays, channels).associate { it.first.match.name to it.second }
        assertEquals(mapOf("BBC One" to "c2", "News" to "c3", "SPORT" to "c4", "film" to "c5", "BBC One Renamed" to "c1"), matched)
    }

    @Test fun pendingImportsRoundTrip() {
        val pending = SetupPendingImport(listOf(SetupBundles.PROVIDER, "feed-1"), listOf(BundleOverlay("s0", ChannelMatch("A", "1"), "Mine", 2, true, "mpegts", "feed-1", "a.uk")), true)
        assertEquals(pending, SetupMerge.decodePending(SetupMerge.encodePending(pending)))
        assertEquals(SetupPendingImport(null, emptyList(), false), SetupMerge.decodePending(SetupMerge.encodePending(SetupPendingImport(null, emptyList(), false))))
    }
}
