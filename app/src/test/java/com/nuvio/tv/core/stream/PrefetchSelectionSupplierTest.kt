package com.nuvio.tv.core.stream

import com.nuvio.tv.core.streams.StreamBadgePresentation
import com.nuvio.tv.data.local.*
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class PrefetchSelectionSupplierTest {
    @Test fun `ranking preserves the chosen direct stream without opening a grab URL`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 100
        try {
            val settings = mockk<PlayerSettingsDataStore>()
            every { settings.playerSettings } returns flowOf(PlayerSettings(
                streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
                streamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES,
                streamAutoPlayReuseBingeGroup = false
            ))
            val addons = mockk<AddonRepository>()
            every { addons.getInstalledAddons() } returns flowOf(emptyList())
            val debrid = mockk<DebridSettingsDataStore>()
            every { debrid.settings } returns flowOf(DebridSettings())
            val badges = mockk<StreamBadgePresentation>()
            coEvery { badges.badgesFor(any()) } returns emptyList()
            val supplier = PrefetchSelectionSupplier(settings, addons, mockk(), debrid, badges, mockk(relaxed = true))
            val stream = Stream("Test", "Test", null,
                "http://127.0.0.1:${server.localPort}/grab", null, null, null, null, null, "Test", null)
            val result = supplier.rankForPrefetch(listOf(AddonStreams("Test", null, listOf(stream))), "title", null, null, uiKey = "test")
            assertEquals(stream, result?.winner)
            assertEquals(SourcePrefetchPhase.RANKED, supplier.uiSignals.value?.phase)
            try {
                server.accept().use { fail("Ranking opened a media connection") }
            } catch (_: SocketTimeoutException) { /* No speculative connection. */ }
        } finally { server.close() }
    }

    @Test fun `ranking names the server stream when a server has the title`() = runBlocking {
        val settings = mockk<PlayerSettingsDataStore>()
        every { settings.playerSettings } returns flowOf(PlayerSettings(
            streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
            streamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES,
            streamAutoPlayReuseBingeGroup = false
        ))
        val addons = mockk<AddonRepository>()
        every { addons.getInstalledAddons() } returns flowOf(emptyList())
        val debrid = mockk<DebridSettingsDataStore>()
        every { debrid.settings } returns flowOf(DebridSettings())
        val badges = mockk<StreamBadgePresentation>()
        coEvery { badges.badgesFor(any()) } returns emptyList()
        val supplier = PrefetchSelectionSupplier(settings, addons, mockk(), debrid, badges, mockk(relaxed = true))
        val addonStream = Stream("2160p", "Addon", null, "https://example.com/a.mkv", null, null, null, null, null, "Addon", null)
        val serverStream = Stream("1080p", null, null, null, null, null, null, null, null, "Jellyfin · Den", null).copy(
            serverTarget = com.nuvio.tv.data.mediaserver.ServerPlaybackTarget(
                com.nuvio.tv.data.mediaserver.ServerItemRef("c1", "i1"),
                mediaSourceId = "ms1"
            )
        )

        val result = supplier.rankForPrefetch(
            listOf(AddonStreams("Addon", null, listOf(addonStream)), AddonStreams("Jellyfin · Den", null, listOf(serverStream))),
            "title", null, null, uiKey = "server"
        )

        assertEquals(serverStream, result?.winner)
        assertEquals(SourcePrefetchPhase.RANKED, supplier.uiSignals.value?.phase)
    }

    @Test fun `with two servers the preferred one is ranked first, as on the stream screen`() = runBlocking {
        val settings = mockk<PlayerSettingsDataStore>()
        every { settings.playerSettings } returns flowOf(PlayerSettings(
            streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
            streamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES,
            streamAutoPlayReuseBingeGroup = false
        ))
        val addons = mockk<AddonRepository>()
        every { addons.getInstalledAddons() } returns flowOf(emptyList())
        val debrid = mockk<DebridSettingsDataStore>()
        every { debrid.settings } returns flowOf(DebridSettings())
        val badges = mockk<StreamBadgePresentation>()
        coEvery { badges.badgesFor(any()) } returns emptyList()
        val servers = mockk<com.nuvio.tv.data.mediaserver.ServerStreams>()
        every { servers.preferredSourceNames("movie", "tt1") } returns setOf("Silo · Den")
        val supplier = PrefetchSelectionSupplier(settings, addons, mockk(), debrid, badges, servers)
        fun serverStream(label: String, item: String) = Stream("1080p", null, null, null, null, null, null, null, null, label, null).copy(
            serverTarget = com.nuvio.tv.data.mediaserver.ServerPlaybackTarget(
                com.nuvio.tv.data.mediaserver.ServerItemRef(label, item),
                mediaSourceId = "ms-$item"
            )
        )
        val emby = serverStream("Emby · Home", "e1")
        val silo = serverStream("Silo · Den", "s1")

        val result = supplier.rankForPrefetch(
            listOf(AddonStreams("Emby · Home", null, listOf(emby)), AddonStreams("Silo · Den", null, listOf(silo))),
            "title", null, null, type = "movie", videoId = "tt1"
        )

        assertEquals(silo, result?.winner)
        assertEquals(listOf(silo, emby), result?.candidates)
        assertEquals(setOf("Silo · Den"), result?.snapshot?.preferredServerNames)
    }
}
