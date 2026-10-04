package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFailoverSelectionTest {
    private val key = "series|tt1:1:2|1|2"

    private fun stream(
        url: String? = null,
        infoHash: String? = null,
        externalUrl: String? = null,
        clientResolve: StreamClientResolve? = null,
        cacheState: StreamDebridCacheState? = null,
        name: String = "entry"
    ) = Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = externalUrl,
        behaviorHints = null,
        addonName = "Addon",
        addonLogo = null,
        clientResolve = clientResolve,
        debridCacheStatus = cacheState?.let { StreamDebridCacheStatus("torbox", "TorBox", it) }
    )

    private fun torboxResolve(season: Int? = 1, episode: Int? = 2, cached: Boolean = true) = StreamClientResolve(
        type = "debrid",
        infoHash = "abcdef0123456789abcdef0123456789abcdef01",
        fileIdx = 3,
        magnetUri = "magnet:?xt=urn:btih:abcdef0123456789abcdef0123456789abcdef01",
        sources = null,
        torrentName = "Show S01",
        filename = "Show.S01E02.mkv",
        mediaType = "series",
        mediaId = "tt1",
        mediaOnlyId = "tt1",
        title = "Show",
        season = season,
        episode = episode,
        service = DebridProviders.TORBOX_ID,
        serviceIndex = 0,
        serviceExtension = null,
        isCached = cached
    )

    private fun playable(stream: Stream) = SourceFailoverSelection.isAutoFailoverPlayable(stream, 1, 2)

    @Test
    fun `a direct http link is playable`() {
        assertTrue(playable(stream(url = "https://cdn.example/show.s01e02.mkv")))
        assertTrue(playable(stream(url = "http://cdn.example/show.s01e02.mkv")))
    }

    @Test
    fun `a link the debrid already resolved stays playable`() {
        val resolved = stream(url = "https://torbox.example/dl/123", clientResolve = torboxResolve())
        assertTrue(resolved.isDirectDebrid())
        assertTrue(playable(resolved))
        assertTrue(playable(stream(url = "https://cdn.example/a.mkv", cacheState = StreamDebridCacheState.CACHED)))
    }

    @Test
    fun `torrent entries are skipped`() {
        assertFalse(playable(stream(infoHash = "abcdef0123456789abcdef0123456789abcdef01")))
        assertFalse(playable(stream(url = "magnet:?xt=urn:btih:abcdef0123456789abcdef0123456789abcdef01")))
        assertFalse(playable(stream(url = "torrent://abcdef0123456789abcdef0123456789abcdef01/3")))
    }

    @Test
    fun `a debrid entry the service reports as cached is playable`() {
        val cachedDebrid = stream(clientResolve = torboxResolve())
        assertTrue(cachedDebrid.isDirectDebrid())
        assertTrue(playable(cachedDebrid))
        assertTrue(playable(stream(clientResolve = torboxResolve(), cacheState = StreamDebridCacheState.CACHED)))
    }

    @Test
    fun `uncached debrid entries and plain torrents are skipped`() {
        val uncached = stream(clientResolve = torboxResolve(cached = false))
        assertFalse(uncached.isDirectDebrid())
        assertFalse(playable(uncached))
        assertFalse(playable(stream(clientResolve = torboxResolve(), cacheState = StreamDebridCacheState.NOT_CACHED)))
        assertFalse(playable(stream(clientResolve = torboxResolve(), cacheState = StreamDebridCacheState.CHECKING)))
        assertFalse(playable(stream(clientResolve = torboxResolve(season = 1, episode = 1))))
        val cachedTorrent = stream(
            infoHash = "abcdef0123456789abcdef0123456789abcdef01",
            cacheState = StreamDebridCacheState.CACHED
        )
        assertTrue(cachedTorrent.needsLocalDebridResolve())
        assertFalse(playable(cachedTorrent))
    }

    @Test
    fun `entries the debrid has not confirmed as cached are skipped`() {
        for (state in listOf(
            StreamDebridCacheState.NOT_CACHED,
            StreamDebridCacheState.CHECKING,
            StreamDebridCacheState.UNKNOWN
        )) {
            assertFalse(state.name, playable(stream(url = "https://addon.example/resolve/1", cacheState = state)))
        }
    }

    @Test
    fun `external links are skipped`() {
        val external = stream(externalUrl = "https://www.example.com/watch")
        assertTrue(external.isExternal())
        assertFalse(playable(external))
        assertFalse(playable(stream(url = "ftp://cdn.example/a.mkv")))
        assertFalse(playable(stream()))
    }

    @Test
    fun `an entry for another episode is skipped`() {
        val otherEpisode = stream(url = "https://torbox.example/dl/9", clientResolve = torboxResolve(season = 1, episode = 1))
        assertFalse(playable(otherEpisode))
        val otherSeason = stream(url = "https://torbox.example/dl/8", clientResolve = torboxResolve(season = 2, episode = 2))
        assertFalse(playable(otherSeason))
    }

    @Test
    fun `a list loaded for another episode gives no source`() {
        val streams = listOf(stream(url = "https://cdn.example/a.mkv"), stream(url = "https://cdn.example/b.mkv"))
        assertNull(SourceFailoverSelection.selectNext(streams, 0, "series|tt1:1:1|1|1", key, emptySet(), 1, 2))
        assertNull(SourceFailoverSelection.selectNext(streams, 0, null, key, emptySet(), 1, 2))
        assertNull(SourceFailoverSelection.selectNext(streams, 0, key, null, emptySet(), 1, 2))
    }

    @Test
    fun `the next playable entry is taken in list order, skipping dead and unplayable ones`() {
        val current = stream(url = "https://cdn.example/current.mkv", name = "current")
        val torrent = stream(infoHash = "abcdef0123456789abcdef0123456789abcdef01", name = "torrent")
        val needsCreate = stream(clientResolve = torboxResolve(), name = "needs create")
        val dead = stream(url = "https://cdn.example/dead.mkv", name = "dead")
        val external = stream(externalUrl = "https://www.example.com/watch", name = "external")
        val good = stream(url = "https://cdn.example/good.mkv", name = "good")
        val later = stream(url = "https://cdn.example/later.mkv", name = "later")
        val streams = listOf(current, torrent, needsCreate, dead, external, good, later)

        val deadKeys = setOf("https://cdn.example/current.mkv", "https://cdn.example/dead.mkv")
        assertSame(needsCreate, SourceFailoverSelection.selectNext(streams, 0, key, key, deadKeys, 1, 2))
        val debridFailed = deadKeys + SourceFailoverSelection.failoverKey(needsCreate)!!
        assertSame(good, SourceFailoverSelection.selectNext(streams, 0, key, key, debridFailed, 1, 2))
    }

    @Test
    fun `a debrid entry without a link is known by its torrent and file`() {
        assertEquals("abcdef0123456789abcdef0123456789abcdef01:3", SourceFailoverSelection.failoverKey(stream(clientResolve = torboxResolve())))
        assertEquals("https://cdn.example/a.mkv", SourceFailoverSelection.failoverKey(stream(url = "https://cdn.example/a.mkv")))
        assertNull(SourceFailoverSelection.failoverKey(stream()))
    }

    @Test
    fun `without the playing entry in the list the search starts at the top and skips dead links`() {
        val first = stream(url = "https://cdn.example/first.mkv")
        val second = stream(url = "https://cdn.example/second.mkv")
        val next = SourceFailoverSelection.selectNext(
            listOf(first, second), -1, key, key, setOf("https://cdn.example/first.mkv"), 1, 2
        )
        assertEquals(second, next)
    }

    @Test
    fun `no playable entry after the current one gives no source`() {
        val streams = listOf(
            stream(url = "https://cdn.example/a.mkv"),
            stream(infoHash = "abcdef0123456789abcdef0123456789abcdef01"),
            stream(externalUrl = "https://www.example.com/watch")
        )
        assertNull(SourceFailoverSelection.selectNext(streams, 0, key, key, emptySet(), 1, 2))
    }
}
