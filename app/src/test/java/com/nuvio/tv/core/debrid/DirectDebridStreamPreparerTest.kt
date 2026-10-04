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

}
