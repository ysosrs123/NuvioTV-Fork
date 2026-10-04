package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSourceFailoverTest {
    private fun addonStream(url: String?) =
        Stream("1080p", null, null, url, null, null, null, null, null, "Addon", null)

    private fun serverStream(source: String) =
        Stream("4K", null, null, null, null, null, null, null, null, "Jellyfin · Den", null).copy(
            serverTarget = ServerPlaybackTarget(ServerItemRef("c1", "item1"), mediaSourceId = source)
        )

    private fun nextLiveSource(streams: List<Stream>, currentIndex: Int, deadKeys: Set<String>): Stream? =
        SourceFailoverSelection.selectNext(streams, currentIndex, "request", "request", deadKeys, season = null, episode = null)

    @Test
    fun serverSourcesAreFoundByTargetBecauseTheirListEntryHasNoUrl() {
        val first = serverStream("v1")
        val second = serverStream("v2")
        val streams = listOf(first, second, addonStream("https://example.com/a.mkv"))

        assertEquals(1, indexOfServerTarget(streams, second.serverTarget))
        assertEquals(-1, indexOfServerTarget(streams, null))
        assertEquals(-1, indexOfServerTarget(streams, ServerPlaybackTarget(ServerItemRef("c1", "other"), "v1")))
    }

    @Test
    fun failoverLeavesAFailedServerSourceInsteadOfReopeningIt() {
        val server = serverStream("v1")
        val addon = addonStream("https://example.com/a.mkv")
        val streams = listOf(server, addon)
        val dead = setOf(server.deadSourceKey()!!)

        assertEquals(addon, nextLiveSource(streams, currentIndex = 0, deadKeys = dead))
        assertEquals(addon, nextLiveSource(streams, currentIndex = -1, deadKeys = dead))
    }

    @Test
    fun failoverTriesAnotherVersionOnTheServerBeforeAddons() {
        val first = serverStream("v1")
        val second = serverStream("v2")
        val addon = addonStream("https://example.com/a.mkv")

        assertEquals(second, nextLiveSource(listOf(first, second, addon), 0, setOf(first.deadSourceKey()!!)))
        assertEquals(
            addon,
            nextLiveSource(listOf(first, second, addon), 0, setOf(first.deadSourceKey()!!, second.deadSourceKey()!!))
        )
    }

    @Test
    fun noLiveSourceLeftEndsTheFailover() {
        val server = serverStream("v1")
        val addon = addonStream("https://example.com/a.mkv")

        assertNull(nextLiveSource(listOf(server, addon), 1, setOf(server.deadSourceKey()!!)))
        assertNull(nextLiveSource(listOf(server, addon), -1, setOf(server.deadSourceKey()!!, "https://example.com/a.mkv")))
        assertNull(nextLiveSource(emptyList(), -1, emptySet()))
    }

    @Test
    fun addonEntriesWithoutALinkAreSkipped() {
        val unresolved = addonStream(null)
        assertNull(unresolved.deadSourceKey())
        assertNull(nextLiveSource(listOf(serverStream("v1"), unresolved), 0, setOf("anything")))
    }

    @Test
    fun serverSourcesQualifyForFailoverWithoutALink() {
        val addon = addonStream("https://example.com/a.mkv")
        val server = serverStream("v1")

        assertTrue(SourceFailoverSelection.isAutoFailoverPlayable(server, season = 1, episode = 2))
        assertEquals(server, nextLiveSource(listOf(addon, server), 0, setOf("https://example.com/a.mkv")))
        assertEquals(server.serverTarget!!.key(), SourceFailoverSelection.failoverKey(server))
    }

    @Test
    fun onlyMalformedOrUnspecifiedReadErrorsAfterTheFirstFrameCountAsCorruption() {
        assertTrue(isMidPlayCorruption(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, hasRenderedFirstFrame = true))
        assertTrue(isMidPlayCorruption(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, hasRenderedFirstFrame = true))
        assertFalse(isMidPlayCorruption(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, hasRenderedFirstFrame = false))
        assertFalse(isMidPlayCorruption(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, hasRenderedFirstFrame = true))
        assertFalse(isMidPlayCorruption(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, hasRenderedFirstFrame = true))
    }
}
