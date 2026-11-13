package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.CachedStreamLink
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReusedLinkRefreshTest {
    private fun stream(
        url: String,
        name: String = "TorBox 2160p",
        filename: String? = null,
        group: String? = null,
        size: Long? = null,
        addon: String = "AIOStreams"
    ) = Stream(
        name = name, title = null, description = null, url = url, ytId = null, infoHash = null, fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null, bingeGroup = group, countryWhitelist = null, proxyHeaders = null,
            videoSize = size, filename = filename
        ),
        addonName = addon, addonLogo = null
    )

    private fun saved(
        url: String = "https://cdn.example/old",
        name: String = "TorBox 2160p",
        filename: String? = "Show.S01E02.2160p.mkv",
        group: String? = "aio|torbox|2160p",
        size: Long? = 4_000L
    ) = CachedStreamLink(
        url = url, streamName = name, headers = emptyMap(), cachedAtMs = 1L, filename = filename,
        videoSize = size, bingeGroup = group
    )

    @Test fun anExpiredOrRefusedLinkCountsButARateLimitDoesNot() {
        assertTrue(ReusedLinkRefresh.isLinkError(403, deadSource = false))
        assertTrue(ReusedLinkRefresh.isLinkError(401, deadSource = false))
        assertTrue(ReusedLinkRefresh.isLinkError(502, deadSource = false))
        assertTrue(ReusedLinkRefresh.isLinkError(null, deadSource = true))
        assertFalse(ReusedLinkRefresh.isLinkError(429, deadSource = false))
        assertFalse(ReusedLinkRefresh.isLinkError(416, deadSource = false))
        assertFalse(ReusedLinkRefresh.isLinkError(null, deadSource = false))
    }

    @Test fun theSameFileFromTheSameSourceIsPickedWithItsFreshLink() {
        val other = stream("https://cdn.example/a", name = "RD 1080p", filename = "Show.S01E02.1080p.mkv")
        val same = stream("https://cdn.example/fresh", filename = "show.s01e02.2160p.mkv", group = "aio|torbox|2160p")
        val picked = ReusedLinkRefresh.sameSource(listOf(other, same), saved(), emptySet(), 1, 2)
        assertEquals("https://cdn.example/fresh", picked?.url)
    }

    @Test fun theFailedLinkItselfIsNeverPickedAgain() {
        val stale = stream("https://cdn.example/old", filename = "Show.S01E02.2160p.mkv")
        assertNull(ReusedLinkRefresh.sameSource(listOf(stale), saved(), emptySet(), 1, 2))
        val dead = stream("https://cdn.example/dead", filename = "Show.S01E02.2160p.mkv")
        assertNull(ReusedLinkRefresh.sameSource(listOf(dead), saved(), setOf("https://cdn.example/dead"), 1, 2))
    }

    @Test fun theEntryNameIsUsedWhenThereIsNoFileName() {
        val byName = stream("https://cdn.example/fresh", name = "TorBox 2160p", group = "aio|torbox|2160p")
        val otherGroup = stream("https://cdn.example/x", name = "TorBox 2160p", group = "other")
        val picked = ReusedLinkRefresh.sameSource(listOf(otherGroup, byName), saved(filename = null), emptySet(), 1, 2)
        assertEquals("https://cdn.example/fresh", picked?.url)
    }

    @Test fun groupAndSizeAreTheLastMatch() {
        val renamed = stream("https://cdn.example/fresh", name = "renamed", group = "aio|torbox|2160p", size = 4_000L)
        assertEquals(renamed, ReusedLinkRefresh.sameSource(listOf(renamed), saved(filename = null), emptySet(), 1, 2))
        val differentSize = renamed.copy(behaviorHints = renamed.behaviorHints?.copy(videoSize = 5_000L))
        assertNull(ReusedLinkRefresh.sameSource(listOf(differentSize), saved(filename = null), emptySet(), 1, 2))
    }

    @Test fun noMatchingEntryMeansNoPick() {
        val unrelated = stream("https://cdn.example/u", name = "Other", filename = "Other.mkv", group = "g")
        assertNull(ReusedLinkRefresh.sameSource(listOf(unrelated), saved(), emptySet(), 1, 2))
    }

    @Test fun onlyAnOutrightRefusalCountsAsRefusedAtTheFirstRequest() {
        assertTrue(ReusedLinkRefresh.isRefusedAtFirstRequest(400))
        assertTrue(ReusedLinkRefresh.isRefusedAtFirstRequest(410))
        assertFalse(ReusedLinkRefresh.isRefusedAtFirstRequest(403))
        assertFalse(ReusedLinkRefresh.isRefusedAtFirstRequest(404))
        assertFalse(ReusedLinkRefresh.isRefusedAtFirstRequest(429))
        assertFalse(ReusedLinkRefresh.isRefusedAtFirstRequest(503))
        assertFalse(ReusedLinkRefresh.isRefusedAtFirstRequest(null))
    }

    @Test fun aRefusedEntryIsFoundAgainWithItsNewLink() {
        val playing = ReusedLinkRefresh.playingEntry(
            url = "https://bridge.example/gone", streamName = "CNCVerse 1080p", headers = emptyMap(),
            filename = "Film.2024.1080p.mkv", videoSize = 2_000L, bingeGroup = "cnc|1080p"
        )
        val other = stream("https://bridge.example/other", name = "CNCVerse 720p", filename = "Film.2024.720p.mkv")
        val renewed = stream("https://bridge.example/new", name = "CNCVerse 1080p", filename = "Film.2024.1080p.mkv",
            group = "cnc|1080p")
        assertEquals(renewed, ReusedLinkRefresh.sameSource(listOf(other, renewed), playing, emptySet(), null, null))
    }

    @Test fun aRefusedEntryHandedOutWithTheSameLinkIsNotTriedAgain() {
        val playing = ReusedLinkRefresh.playingEntry(
            url = "https://bridge.example/gone", streamName = "CNCVerse 1080p", headers = emptyMap(),
            filename = "Film.2024.1080p.mkv", videoSize = null, bingeGroup = null
        )
        val same = stream("https://bridge.example/gone", name = "CNCVerse 1080p", filename = "Film.2024.1080p.mkv")
        assertNull(ReusedLinkRefresh.sameSource(listOf(same), playing, emptySet(), null, null))
        assertTrue(ReusedLinkRefresh.failedAddressBack(listOf(same), "https://bridge.example/gone"))
    }

    @Test fun aNewAddressIsNotTheFailedOneBack() {
        val renewed = stream("https://bridge.example/new", name = "CNCVerse 1080p", filename = "Film.2024.1080p.mkv")
        assertFalse(ReusedLinkRefresh.failedAddressBack(listOf(renewed), "https://bridge.example/gone"))
        assertFalse(ReusedLinkRefresh.failedAddressBack(emptyList(), "https://bridge.example/gone"))
    }
}
