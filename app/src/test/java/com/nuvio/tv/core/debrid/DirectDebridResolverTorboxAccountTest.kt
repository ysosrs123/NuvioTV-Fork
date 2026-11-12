package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectDebridResolverTorboxAccountTest {
    private val link = DirectDebridResolveResult.Success("https://cdn.example/file.mkv", "file.mkv", 20L)
    private val torbox = mockk<TorboxDirectDebridResolver> {
        coEvery { resolve(any(), any(), any()) } returns link
    }
    private val torboxCopies = mockk<ThumbnailCopyDebrid>()

    @Test
    fun `an account-only lookup adds nothing to TorBox when the title is not in the list`() = runTest {
        coEvery { torboxCopies.inTorboxList(any<Stream>()) } returns false

        val result = resolver().resolve(stream(), season = 1, episode = 2, mayAddToAccount = false)

        assertEquals(DirectDebridResolveResult.NotCached, result)
        coVerify(exactly = 0) { torbox.resolve(any(), any(), any()) }
    }

    @Test
    fun `an account-only lookup adds nothing to TorBox when the list cannot be read`() = runTest {
        coEvery { torboxCopies.inTorboxList(any<Stream>()) } returns null

        val result = resolver().resolve(stream(), season = 1, episode = 2, mayAddToAccount = false)

        assertEquals(DirectDebridResolveResult.NotCached, result)
        coVerify(exactly = 0) { torbox.resolve(any(), any(), any()) }
    }

    @Test
    fun `an account-only lookup uses a title already in the TorBox list`() = runTest {
        coEvery { torboxCopies.inTorboxList(any<Stream>()) } returns true

        val result = resolver().resolve(stream(), season = 1, episode = 2, mayAddToAccount = false)

        assertEquals(link, result)
        coVerify(exactly = 1) { torbox.resolve(any(), 1, 2) }
    }

    @Test
    fun `a plain torrent sent to TorBox follows the same rule`() = runTest {
        coEvery { torboxCopies.inTorboxList(any<Stream>()) } returns false

        val result = resolver().resolve(plainTorrent(), season = 1, episode = 2, mayAddToAccount = false)

        assertEquals(DirectDebridResolveResult.NotCached, result)
        coVerify(exactly = 0) { torbox.resolve(any(), any(), any()) }
    }

    @Test
    fun `a play lookup goes to TorBox without reading the list`() = runTest {
        val result = resolver().resolve(stream(), season = 1, episode = 2)

        assertEquals(link, result)
        coVerify(exactly = 1) { torbox.resolve(any(), 1, 2) }
        coVerify(exactly = 0) { torboxCopies.inTorboxList(any<Stream>()) }
    }

    private fun resolver(): DirectDebridResolver {
        val dataStore = mockk<DebridSettingsDataStore>()
        every { dataStore.settings } returns flowOf(DebridSettings(enabled = true, torboxApiKey = "tb_token"))
        val linkStore = mockk<ResolvedLinkStore>(relaxed = true)
        coEvery { linkStore.get(any()) } returns null
        return DirectDebridResolver(
            dataStore,
            torbox,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            linkStore,
            mockk(relaxed = true),
            torboxCopies
        )
    }

    private fun stream(): Stream = plainTorrent().copy(
        infoHash = null,
        addonName = DebridProviders.instantName(DebridProviders.TORBOX_ID),
        clientResolve = StreamClientResolve(
            type = "debrid",
            infoHash = "abcdef",
            fileIdx = null,
            magnetUri = "magnet:?xt=urn:btih:abcdef",
            sources = null,
            torrentName = "Torrent",
            filename = "file.mkv",
            mediaType = "series",
            mediaId = "tt1:1:2",
            mediaOnlyId = "tt1",
            title = "Title",
            season = 1,
            episode = 2,
            service = DebridProviders.TORBOX_ID,
            serviceIndex = 0,
            serviceExtension = null,
            isCached = true
        )
    )

    private fun plainTorrent(): Stream = Stream(
        name = "Torrent",
        title = "Title",
        description = null,
        url = null,
        ytId = null,
        infoHash = "abcdef",
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Add-on",
        addonLogo = null
    )
}
