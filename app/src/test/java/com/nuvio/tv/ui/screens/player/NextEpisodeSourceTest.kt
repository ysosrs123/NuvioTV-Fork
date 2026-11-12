package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodeSourceTest {

    private val nextFile = "Show.S01E03.1080p.WEB-DL.DDP5.1.H.264-GRP.mkv"

    private val torBox1080 = stream("AIOStreams", "[TB⚡] AIOStreams 1080p", nextFile, "aio|group", 2_000_000_000L)
    private val easyNews2160 = stream("AIOStreams", "[EN] AIOStreams 2160p", nextFile, "aio|group", 9_000_000_000L)
    private val otherAddonTorBox2160 = stream("Tortoise", "[TB+] Tortoise\n2160p", nextFile, "tortoise|tb", 9_000_000_000L)

    private val playedOnTorBox = NextEpisodeSource.of(
        addonName = "AIOStreams",
        streamName = "[TB⚡] AIOStreams 1080p",
        streamDescription = null
    )

    private fun byFilename(streams: List<Stream>): Stream? =
        StreamAutoPlaySelector.selectByFilename(streams = streams, nextFilename = nextFile)

    private fun byFilenameInGroup(streams: List<Stream>): Stream? =
        StreamAutoPlaySelector.selectByFilename(streams = streams, nextFilename = nextFile, bingeGroup = "aio|group")

    private fun firstInGroup(streams: List<Stream>): Stream? =
        StreamAutoPlaySelector.selectAutoPlayStream(
            streams = streams,
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AIOStreams", "Tortoise"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "aio|group",
            preferBingeGroupInSelection = true,
            bingeGroupOnly = true
        )

    @Test
    fun `the source is read from the add-on and the service in the label`() {
        assertEquals(NextEpisodeSource("AIOStreams", "TorBox"), playedOnTorBox)
        assertTrue(playedOnTorBox.matches(torBox1080))
        assertFalse(playedOnTorBox.matches(easyNews2160))
        assertFalse(playedOnTorBox.matches(otherAddonTorBox2160))
    }

    @Test
    fun `the same add-on and service win over a better copy elsewhere`() {
        val streams = listOf(easyNews2160, otherAddonTorBox2160, torBox1080)
        // Without the source preference the better copy wins.
        assertEquals(easyNews2160, byFilename(listOf(easyNews2160, torBox1080)))

        assertEquals(torBox1080, pickSameSourceFirst(streams, playedOnTorBox, allowOtherSources = true, pick = ::byFilename))
        assertEquals(torBox1080, pickSameSourceFirst(streams, playedOnTorBox, allowOtherSources = false, pick = ::byFilename))
    }

    @Test
    fun `a shared binge group keeps the same service ahead of an earlier listed one`() {
        val streams = listOf(easyNews2160, torBox1080)
        assertEquals(easyNews2160, firstInGroup(streams))
        assertEquals(torBox1080, pickSameSourceFirst(streams, playedOnTorBox, allowOtherSources = false, pick = ::firstInGroup))
        assertEquals(torBox1080, pickSameSourceFirst(streams, playedOnTorBox, allowOtherSources = false, pick = ::byFilenameInGroup))
    }

    @Test
    fun `without fallback another service is never picked`() {
        val streams = listOf(easyNews2160, otherAddonTorBox2160)
        assertNull(pickSameSourceFirst(streams, playedOnTorBox, allowOtherSources = false, pick = ::byFilename))
        assertNull(pickSameSourceFirst(streams, playedOnTorBox, allowOtherSources = false, pick = ::firstInGroup))
    }

    @Test
    fun `with fallback another source is used when the same service has none`() {
        val streams = listOf(easyNews2160, otherAddonTorBox2160)
        assertEquals(easyNews2160, pickSameSourceFirst(streams, playedOnTorBox, allowOtherSources = true, pick = ::byFilename))
    }

    @Test
    fun `a same source stream that does not match the step leaves room for the others`() {
        val wrongEpisode = torBox1080.copy(
            behaviorHints = torBox1080.behaviorHints?.copy(filename = "Show.S01E04.1080p.WEB-DL.DDP5.1.H.264-GRP.mkv")
        )
        val streams = listOf(wrongEpisode, easyNews2160)
        assertNull(pickSameSourceFirst(streams, playedOnTorBox, allowOtherSources = false, pick = ::byFilename))
        assertEquals(easyNews2160, pickSameSourceFirst(streams, playedOnTorBox, allowOtherSources = true, pick = ::byFilename))
    }

    @Test
    fun `a stream without a service only matches others without one`() {
        val playedOnEasyNews = NextEpisodeSource.of("AIOStreams", "[EN] AIOStreams 2160p", null)
        assertEquals(NextEpisodeSource("AIOStreams", null), playedOnEasyNews)
        assertTrue(playedOnEasyNews.matches(easyNews2160))
        assertFalse(playedOnEasyNews.matches(torBox1080))
    }

    @Test
    fun `an unknown source leaves the choice as it was`() {
        val unknown = NextEpisodeSource.of(addonName = " ", streamName = null, streamDescription = null)
        assertFalse(unknown.isKnown)
        val streams = listOf(easyNews2160, torBox1080)
        assertEquals(easyNews2160, pickSameSourceFirst(streams, unknown, allowOtherSources = false, pick = ::byFilename))
    }

    @Test
    fun `a label without a name falls back to the add-on name on both sides`() {
        val unnamed = torBox1080.copy(name = null, addonName = "TorBox")
        val source = NextEpisodeSource.of(addonName = "TorBox", streamName = null, streamDescription = null)
        assertTrue(source.matches(unnamed))
    }

    @Test
    fun `the add-on has responded once its results are in`() {
        val aio = AddonStreams(addonName = "AIOStreams", addonLogo = null, streams = emptyList())
        val tortoise = AddonStreams(addonName = "Tortoise", addonLogo = null, streams = listOf(otherAddonTorBox2160))
        assertFalse(playedOnTorBox.hasResponded(listOf(tortoise)))
        assertTrue(playedOnTorBox.hasResponded(listOf(tortoise, aio)))
        assertTrue(NextEpisodeSource(null, "TorBox").hasResponded(emptyList()))
    }

    @Test
    fun `a service named only in the description still counts`() {
        val tortoise = stream("Tortoise", "Tortoise\n1080p", nextFile, "tortoise|tb", 2_000_000_000L)
            .copy(description = "Show S01E03\n[TB+] cached")
        val source = NextEpisodeSource.of("Tortoise", "Tortoise\n1080p", "Show S01E02\n[TB+] cached")
        assertEquals("TorBox", source.service)
        assertTrue(source.matches(tortoise))
        assertFalse(source.matches(tortoise.copy(description = "Show S01E03\n[RD+] cached")))
    }

    @Test
    fun `only Manual mode without fallback keeps to the same source`() {
        fun only(
            mode: StreamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            next: Boolean,
            fallback: Boolean,
            preferBinge: Boolean
        ) = NextEpisodeSource.sameSourceOnly(
            PlayerSettings(
                streamAutoPlayMode = mode,
                streamAutoPlayNextEpisodeEnabled = next,
                streamAutoPlayNextEpisodeFallbackEnabled = fallback,
                streamAutoPlayPreferBingeGroupForNextEpisode = preferBinge
            )
        )
        assertTrue(only(next = false, fallback = true, preferBinge = true))
        assertTrue(only(next = true, fallback = false, preferBinge = true))
        assertTrue(only(next = true, fallback = false, preferBinge = false))
        assertFalse(only(next = true, fallback = true, preferBinge = true))
        assertFalse(only(next = true, fallback = true, preferBinge = false))
        // Manual mode with neither switch shows the source list and picks nothing.
        assertFalse(only(next = false, fallback = false, preferBinge = false))
        for (mode in StreamAutoPlayMode.values().filter { it != StreamAutoPlayMode.MANUAL }) {
            assertFalse(only(mode = mode, next = true, fallback = false, preferBinge = true))
        }
    }

    private fun stream(
        addonName: String,
        name: String,
        filename: String,
        bingeGroup: String,
        sizeBytes: Long
    ): Stream = Stream(
        name = name,
        title = null,
        description = null,
        url = "https://example.com/${name.hashCode()}.mkv",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = bingeGroup,
            countryWhitelist = null,
            proxyHeaders = null,
            videoSize = sizeBytes,
            filename = filename
        ),
        addonName = addonName,
        addonLogo = null
    )
}
