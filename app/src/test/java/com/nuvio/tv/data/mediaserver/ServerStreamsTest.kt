package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.R
import com.nuvio.tv.core.streams.StreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgeImport
import com.nuvio.tv.core.streams.StreamBadgePresentation
import com.nuvio.tv.core.streams.StreamBadgeRules
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerStreamsTest {
    private val file = "Apocalypse Now 1979 Theatrical Cut UHD BluRay 2160p TrueHD Atmos 7 1 DV HEVC REMUX-FraMeSToR.mkv"

    private fun candidate(
        mediaSourceId: String = "ms1",
        title: String = "2160p",
        filename: String? = file,
        sizeBytes: Long? = 57_508_742_418L,
        bitrateBps: Long? = 52_071_460L,
        video: String? = "HEVC • DV P7.6 (HDR10)",
        audio: String? = "TrueHD Atmos • 7.1",
        container: String? = "MKV",
        versionName: String? = null
    ) = ServerCandidate(
        target = ServerPlaybackTarget(ServerItemRef("c1", "m1"), mediaSourceId),
        title = title,
        filename = filename,
        sizeBytes = sizeBytes,
        bitrateBps = bitrateBps,
        video = video,
        audio = audio,
        container = container,
        versionName = versionName
    )

    @Test
    fun showsAServerVersionLikeAnAddonStream() {
        val text = serverStreamTexts(listOf(candidate()), "Jellyfin", "HomeServer").single()
        assertEquals("Jellyfin · 2160p", text.name)
        assertEquals(
            "📄 $file\n" +
                "🎥 HEVC • DV P7.6 (HDR10) | 🔊 TrueHD Atmos • 7.1\n" +
                "💾 53.6 GB • 52 Mbps • MKV • 🔍 HomeServer",
            text.description
        )
    }

    @Test
    fun leavesOutWhatTheServerDoesNotSend() {
        assertEquals(
            "🎥 HEVC • DV P7.6 (HDR10)\n💾 MKV • 🔍 HomeServer",
            serverStreamDescription(candidate(filename = null, audio = null, sizeBytes = null, bitrateBps = null), "HomeServer")
        )
        assertEquals(
            "📄 $file\n🔊 TrueHD Atmos • 7.1\n💾 53.6 GB • 🔍 HomeServer",
            serverStreamDescription(candidate(video = null, bitrateBps = null, container = null), "HomeServer")
        )
        assertEquals(
            "🔍 HomeServer",
            serverStreamDescription(
                candidate(filename = " ", video = null, audio = null, sizeBytes = 0L, bitrateBps = null, container = null),
                "HomeServer"
            )
        )
        assertNull(
            serverStreamDescription(
                candidate(filename = null, video = null, audio = null, sizeBytes = null, bitrateBps = null, container = null),
                ""
            )
        )
        val text = serverStreamTexts(listOf(candidate(title = "")), "Emby", "Home").single()
        assertEquals("Emby", text.name)
    }

    @Test
    fun formatsSizesLikeTheSizeChip() {
        assertEquals("53.6 GB", formatServerSize(57_508_742_418L))
        assertEquals("1.0 GB", formatServerSize(1024L * 1024 * 1024))
        assertEquals("512 MB", formatServerSize(512L * 1024 * 1024))
    }

    @Test
    fun keepsVersionsWithTheSameTitleApart() {
        val texts = serverStreamTexts(
            listOf(
                candidate("a", versionName = "Theatrical Cut"),
                candidate("b", versionName = "Redux"),
                candidate("c", title = "1080p", versionName = "1080p Remux")
            ),
            "Jellyfin",
            "HomeServer"
        )
        assertEquals(listOf("Jellyfin · 2160p", "Jellyfin · 2160p", "Jellyfin · 1080p"), texts.map { it.name })
        assertEquals("💾 53.6 GB • 52 Mbps • MKV • 🔍 HomeServer • Theatrical Cut", texts[0].description!!.lines().last())
        assertEquals("💾 53.6 GB • 52 Mbps • MKV • 🔍 HomeServer • Redux", texts[1].description!!.lines().last())
        assertEquals("💾 53.6 GB • 52 Mbps • MKV • 🔍 HomeServer", texts[2].description!!.lines().last())
    }

    @Test
    fun pointsEachServerAtItsOwnLogo() {
        assertEquals("android.resource://com.nuvio.tv.test/${R.raw.jellyfin_logo}", serverLogoUri("jellyfin", "com.nuvio.tv.test"))
        assertEquals("android.resource://com.nuvio.tv.test/${R.raw.emby_logo}", serverLogoUri("emby", "com.nuvio.tv.test"))
        assertEquals("android.resource://com.nuvio.tv.test/${R.raw.silo_logo}", serverLogoUri("silo", "com.nuvio.tv.test"))
        assertNull(serverLogoUri("fake", "com.nuvio.tv.test"))
    }

    private fun streams(provider: FakeServerProvider): ServerStreams {
        val (repository, _) = fakeServerRepository(provider)
        val metaRepository = mockk<MetaRepository> { every { getCachedMeta(any(), any()) } returns null }
        return ServerStreams(repository, ServerMatcher(repository, mockk<TmdbService>(relaxed = true), metaRepository))
    }

    @Test
    fun buildsStreamsWithLogoSizeAndFileName() = runBlocking {
        val provider = FakeServerProvider(id = "jellyfin").apply {
            itemCandidates["m1"] = listOf(candidate())
        }
        val stream = streams(provider).candidates(ServerItemRef("cfake", "m1")).single()
        assertEquals("Fake · 2160p", stream.name)
        assertEquals("Fake · Box", stream.addonName)
        assertEquals(serverLogoUri("jellyfin"), stream.addonLogo)
        assertEquals(57_508_742_418L, stream.behaviorHints?.videoSize)
        assertEquals(file, stream.behaviorHints?.filename)
        assertEquals("💾 53.6 GB • 52 Mbps • MKV • 🔍 Box", stream.description!!.lines().last())
    }

    @Test
    fun badgeRulesMatchServerStreamsAndKeepTheSize() = runBlocking {
        val provider = FakeServerProvider(id = "emby").apply {
            itemCandidates["m1"] = listOf(candidate())
        }
        val stream = streams(provider).candidates(ServerItemRef("cfake", "m1")).single()
        val rules = StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://badges.example/fusion.json",
                    filters = listOf(
                        StreamBadgeFilter(name = "Remux", pattern = "(?i)\\bREMUX\\b", imageURL = "https://badges.example/remux.png"),
                        StreamBadgeFilter(name = "HDR10", pattern = "HDR10(?!\\+)", imageURL = "https://badges.example/hdr10.png"),
                        StreamBadgeFilter(name = "AV1", pattern = "\\bAV1\\b", imageURL = "https://badges.example/av1.png")
                    )
                )
            )
        )
        val badged = StreamBadgePresentation(mockk(relaxed = true))
            .apply(listOf(AddonStreams(addonName = stream.addonName, addonLogo = null, streams = listOf(stream))), rules)
            .single().streams.single()
        assertEquals(listOf("Remux", "HDR10"), badged.badges.map { it.name })
        assertEquals(57_508_742_418L, badged.behaviorHints?.videoSize)
        assertEquals(stream.addonLogo, badged.addonLogo)
    }
}
