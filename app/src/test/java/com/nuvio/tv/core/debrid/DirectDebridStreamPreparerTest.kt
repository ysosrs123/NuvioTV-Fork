package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DirectDebridStreamPreparerTest {
    @Test fun `enabled preparation cannot resolve an uncached candidate with either search policy`() = runTest {
        val store = mockk<DebridSettingsDataStore>()
        every { store.settings } returns flowOf(DebridSettings(enabled = true, torboxApiKey = "test", instantPlaybackPreparationLimit = 5))
        val resolver = mockk<DirectDebridResolver>()
        coEvery { resolver.shouldResolveToPlayableStream(any()) } returns true
        coEvery { resolver.cachedPlayableStream(any(), any(), any()) } returns null
        val preparer = DirectDebridStreamPreparer(store, resolver)
        for (enabled in listOf(false, true)) {
            preparer.prepare(listOf(stream(7)), null, null, PlayerSettings(speculativeStreamSearchEnabled = enabled), emptySet()) { _, _ -> fail("No cached link") }
        }
        coVerify(exactly = 2) { resolver.cachedPlayableStream(any(), any(), any()) }
        coVerify(exactly = 0) { resolver.resolveToPlayableStream(any(), any(), any()) }
        coVerify(exactly = 0) { resolver.resolve(any(), any(), any()) }
    }
    @Test fun `preparation retains reuse of previously resolved links`() = runTest {
        val original = stream(7)
        val cached = original.copy(url = "https://example.invalid/already-selected")
        val store = mockk<DebridSettingsDataStore>()
        every { store.settings } returns flowOf(DebridSettings(enabled = true, torboxApiKey = "test", instantPlaybackPreparationLimit = 2))
        val resolver = mockk<DirectDebridResolver>()
        coEvery { resolver.shouldResolveToPlayableStream(original) } returns true
        coEvery { resolver.cachedPlayableStream(original, null, null) } returns cached
        var reused: Stream? = null
        DirectDebridStreamPreparer(store, resolver).prepare(listOf(original), null, null, PlayerSettings(), emptySet()) { _, result -> reused = result }
        assertEquals(cached, reused)
        coVerify(exactly = 0) { resolver.resolveToPlayableStream(any(), any(), any()) }
    }
    private fun stream(fileIdx: Int?): Stream = Stream(
        name = "Direct Debrid",
        title = "Title",
        description = "Description",
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = DebridProviders.instantName(DebridProviders.TORBOX_ID),
        addonLogo = null,
        clientResolve = StreamClientResolve(
            type = "debrid",
            infoHash = "abcdef",
            fileIdx = fileIdx,
            magnetUri = "magnet:?xt=urn:btih:abcdef",
            sources = null,
            torrentName = "Torrent",
            filename = "right.mkv",
            mediaType = "movie",
            mediaId = "tt1",
            mediaOnlyId = "tt1",
            title = "Title",
            season = null,
            episode = null,
            service = "torbox",
            serviceIndex = 0,
            serviceExtension = null,
            isCached = true
        )
    )

    private val preparer = DirectDebridStreamPreparer(dataStore = mockk(), resolver = mockk())

    @Test
    fun `replaces debrid streams with the same resolve fields in every addon, ignoring case`() {
        val original = debridStream(addonName = "Addon A", infoHash = "abcdef", filename = "Movie.mkv")
        val sameTorrent = debridStream(addonName = "Addon B", infoHash = "ABCDEF", filename = "movie.MKV")
        val otherTorrent = debridStream(addonName = "Addon A", infoHash = "123456", filename = "Movie.mkv")
        val untouchedGroup = AddonStreams("Addon C", null, listOf(otherTorrent.copy(addonName = "Addon C")))
        val prepared = original.copy(url = PREPARED_URL)

        val updated = preparer.replacePreparedStream(
            groups = listOf(
                AddonStreams("Addon A", null, listOf(original, otherTorrent)),
                AddonStreams("Addon B", "logo-b", listOf(sameTorrent.copy(addonLogo = "logo-b"))),
                untouchedGroup
            ),
            original = original,
            prepared = prepared
        )

        assertEquals(PREPARED_URL, updated[0].streams[0].url)
        assertSame(otherTorrent, updated[0].streams[1])
        assertEquals(PREPARED_URL, updated[1].streams[0].url)
        assertEquals("Addon B", updated[1].streams[0].addonName)
        assertEquals("logo-b", updated[1].streams[0].addonLogo)
        assertSame(untouchedGroup, updated[2])
    }

    @Test
    fun `replaces torrent streams without resolve data only when every key field matches`() {
        val original = torrentStream(addonName = "Torrents", infoHash = "abcdef", title = "Movie 1080p")
        val sameIgnoringCase = torrentStream(addonName = "TORRENTS", infoHash = "ABCDEF", title = "movie 1080P")
        val otherTitle = torrentStream(addonName = "Torrents", infoHash = "abcdef", title = "Movie 2160p")
        val otherAddon = torrentStream(addonName = "Other", infoHash = "abcdef", title = "Movie 1080p")
        val prepared = original.copy(url = PREPARED_URL)

        val updated = preparer.replacePreparedStream(
            groups = listOf(AddonStreams("Torrents", null, listOf(original, sameIgnoringCase, otherTitle, otherAddon))),
            original = original,
            prepared = prepared
        ).single().streams

        assertEquals(PREPARED_URL, updated[0].url)
        assertEquals(PREPARED_URL, updated[1].url)
        assertSame(otherTitle, updated[2])
        assertSame(otherAddon, updated[3])
    }

    private fun debridStream(addonName: String, infoHash: String, filename: String): Stream =
        torrentStream(addonName = addonName, infoHash = null, title = "Title").copy(
            clientResolve = StreamClientResolve(
                type = "debrid",
                infoHash = infoHash,
                fileIdx = 0,
                magnetUri = null,
                sources = null,
                torrentName = "Torrent",
                filename = filename,
                mediaType = "movie",
                mediaId = "tt1",
                mediaOnlyId = "tt1",
                title = "Title",
                season = null,
                episode = null,
                service = "torbox",
                serviceIndex = 0,
                serviceExtension = null,
                isCached = true
            )
        )

    private fun torrentStream(addonName: String, infoHash: String?, title: String): Stream = Stream(
        name = "Name",
        title = title,
        description = null,
        url = null,
        ytId = null,
        infoHash = infoHash,
        fileIdx = 1,
        externalUrl = null,
        behaviorHints = null,
        addonName = addonName,
        addonLogo = null
    )

    private companion object {
        const val PREPARED_URL = "https://cdn.example/movie.mkv"
    }
}
