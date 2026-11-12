package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DirectDebridResolverClaimTest {
    @Test
    fun `an account-only lookup leaves TorBox thumbnail copies alone`() = runTest {
        val copies = mockk<ThumbnailCopyAdded>(relaxed = true)

        resolver(copies).resolve(stream(), season = null, episode = null, mayAddToAccount = false)

        coVerify(exactly = 0) { copies.claimAndWait(any(), any()) }
    }

    @Test
    fun `a play lookup claims the title for playback`() = runTest {
        val copies = mockk<ThumbnailCopyAdded>(relaxed = true)

        resolver(copies).resolve(stream(), season = null, episode = null)

        coVerify(exactly = 1) { copies.claimAndWait("abcdef", any()) }
    }

    private fun resolver(copies: ThumbnailCopyAdded): DirectDebridResolver {
        val dataStore = mockk<DebridSettingsDataStore>()
        every { dataStore.settings } returns flowOf(DebridSettings(enabled = false))
        return DirectDebridResolver(
            dataStore,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            copies,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
    }

    private fun stream(): Stream = Stream(
        name = "Direct Debrid",
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
