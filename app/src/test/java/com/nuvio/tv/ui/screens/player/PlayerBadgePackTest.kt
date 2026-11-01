package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.streams.StreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgeImport
import com.nuvio.tv.core.streams.StreamBadgeMatcher
import com.nuvio.tv.core.streams.StreamBadgeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBadgePackTest {
    private fun filter(name: String, pattern: String) =
        StreamBadgeFilter(name = name, pattern = pattern, imageURL = "https://example.com/$name.png")

    // Patterns as published in community Fusion style packs (GOLD-NONAMES and gold_badges_complete).
    private val pack = StreamBadgeMatcher.compile(
        StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.com/gold.json",
                    filters = listOf(
                        filter("Remux", """(?i)\bremux\b"""),
                        filter("4K", """(?i)^(?=.*(?:2160[pi]?|4k|uhd))(?!.*(?:1080[pi]?|720[pi]?))"""),
                        filter("1080p", """(?i)\b1080[pi]?\b"""),
                        filter("DV", """(?i)\b(?:dv|dovi|dolby[\s._-]?vision)\b"""),
                        filter("HDR10", """(?i)^(?=.*hdr[\s._-]?10)(?!.*hdr[\s._-]?10[\s._-]?(?:\\+|plus|p))"""),
                        filter("HDR", """(?i)^(?=.*\bHDR\b)(?!.*hdr[\s._-]?10)"""),
                        filter("HEVC", """(?i)\b(?:x265|h[._-]?265|hevc|hev1|hvc1)\b"""),
                        filter("AVC", """(?i)\b(?:x264|h[._-]?264|avc1?|mpeg-?4\s*avc)\b"""),
                        filter("10-bit", """(?i)\b(10[-._ ]?bit(?:s)?|main10)\b"""),
                        filter(
                            "TrueHD",
                            """(?i)(?:\btrue[ ._-]?hd\b|^(?=.*\batmos\b)(?=.*\bremux\b)(?!.*\b(?:true[ ._-]?hd|ddp|dd\+|e-?ac3|eac3)\b).+$)"""
                        ),
                        filter("Atmos", """(?i)^(?!.*\btrue[ _\.\-]?hd\b).*\batmos\b.*$"""),
                        filter("DTS:X", """(?i)\bdts[-_.: ]?x\b"""),
                        filter("DTS-HD MA", """(?i)^(?=.*\bdts[-_. ]?(?:hd[-_. ]?)?ma\b)(?!.*\bdts[-_.: ]?x\b)"""),
                        filter(
                            "DD+",
                            """(?i)^(?=.*(?:\bddp|\bdd\+|\beac-?3|\be-?ac-?3))(?!.*\batmos\b)(?!.*\btrue[\s._-]?hd\b)(?!.*\b(?:dv|dovi|dolby[\s._-]?vision)\b)"""
                        ),
                        filter("7.1", """[^0-9][7-8][. ][01](?![0-9])"""),
                        filter("5.1", """^(?=.*[^0-9]5[. ][01](?![0-9]))(?!.*[^0-9][7-8][. ][01](?![0-9]))""")
                    )
                )
            )
        )
    )

    private val remuxFile = "Movie.2023.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.7.1.Atmos-FraMeSToR.mkv"
    private val webFile = "Show.S01E01.2160p.AMZN.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX.mkv"
    private val dtsMaFile = "Movie.2019.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-EPSiLON.mkv"
    private val dtsXFile = "Movie.2021.2160p.UHD.BluRay.x265.10bit.HDR.DTS-X.7.1-SWTYBLZ.mkv"

    // An AIOStreams formatter that names none of the formats: they are only in the file name.
    private val streamName = "Movie (2023) • ⚡ 📚 TB 💻 Web 🌱 120 ⭐ 9"
    private val description = "⚙️ HEVC 🗄️ MKV 🗣️ 🇬🇧 English | Italian\n" +
        "💾 58.2 GB 📶 80 Mbps ⏱️ 2h 10m\n🧩 AIOStreams 🏷️ FraMeSToR"

    private fun names(info: PlayerMediaInfo, file: String, audioFromRelease: Boolean = true): List<String> {
        val release = listOf(file, streamName, description).joinToString(" ")
        val text = playerBadgeMatchText(info, emptyList(), release, file, audioFromRelease)
        return StreamBadgeMatcher.matchedBadges(listOf(text), pack).map { it.name }
    }

    private val uhd = PlayerMediaInfo(videoCodec = "HEVC", videoWidth = 3840, videoHeight = 2160, hdrKnown = true, bitDepth = 10)

    @Test
    fun `the file name fills in what the player has not reported`() {
        val info = PlayerMediaInfo(videoWidth = 3840, videoHeight = 2160, audioChannels = 8)
        val before = StreamBadgeMatcher.matchedBadges(listOf(playbackMatchText(info, emptyList())), pack).map { it.name }
        assertEquals(listOf("4K", "7.1"), before)
        assertEquals(listOf("Remux", "4K", "DV", "HDR10", "HEVC", "TrueHD", "7.1"), names(info, remuxFile))
    }

    @Test
    fun `dolby vision remux with truehd atmos`() {
        val info = uhd.copy(hdr = listOf("DV", "HDR10"), audioCodec = "TrueHD", audioChannels = 8)
        assertEquals(listOf("Remux", "4K", "DV", "HEVC", "10-bit", "TrueHD", "7.1"), names(info, remuxFile))
    }

    @Test
    fun `a dolby vision file playing as hdr10 shows no dolby vision`() {
        val info = uhd.copy(hdr = listOf("HDR10"), audioCodec = "TrueHD", audioChannels = 8)
        assertEquals(listOf("Remux", "4K", "HDR10", "HEVC", "10-bit", "TrueHD", "7.1"), names(info, remuxFile))
        assertFalse(Regex("""(?i)\bdv\b""").containsMatchIn(playbackFileName(remuxFile, info)))
    }

    @Test
    fun `web release with dd+ atmos`() {
        val info = uhd.copy(bitDepth = null, hdr = listOf("DV", "HDR10"), audioCodec = "E-AC-3-JOC", audioChannels = 6)
        assertEquals(listOf("4K", "DV", "HEVC", "10-bit", "Atmos", "5.1"), names(info, webFile))
    }

    @Test
    fun `dts-hd ma on an sdr avc remux`() {
        val info = PlayerMediaInfo(
            videoCodec = "AVC", videoWidth = 1920, videoHeight = 1080, audioCodec = "DTS-HD", audioChannels = 6,
            hdrKnown = true, bitDepth = 8
        )
        assertEquals(listOf("Remux", "1080p", "AVC", "DTS-HD MA", "5.1"), names(info, dtsMaFile))
    }

    @Test
    fun `dts-x on an hdr10 encode`() {
        val info = uhd.copy(hdr = listOf("HDR10"), audioCodec = "DTS-HD", audioChannels = 8)
        assertEquals(listOf("4K", "HDR10", "HEVC", "10-bit", "DTS:X", "7.1"), names(info, dtsXFile))
    }

    @Test
    fun `the dts core keeps dts-hd ma out`() {
        val info = PlayerMediaInfo(audioCodec = "DTS", audioChannels = 6)
        val text = playerBadgeMatchText(info, emptyList(), dtsMaFile, dtsMaFile, audioFromRelease = true)
        assertFalse("DTS-HD" in text)
        assertTrue("5.1" in text.split(" "))
    }

    @Test
    fun `another audio track drops the file name's audio`() {
        val info = uhd.copy(hdr = listOf("DV", "HDR10"), audioCodec = "AC-3", audioChannels = 6)
        val shown = names(info, remuxFile)
        assertEquals(listOf("Remux", "4K", "DV", "HEVC", "10-bit", "5.1"), shown)
        assertEquals(shown, names(info, remuxFile, audioFromRelease = false))
    }

    @Test
    fun `mpv before the first frame keeps the file's picture format`() {
        val info = PlayerMediaInfo(videoCodec = "HEVC", videoWidth = 3840, videoHeight = 2160, hdr = listOf("DV"))
        assertEquals(listOf("4K", "DV", "HDR10", "HEVC", "10-bit"), names(info, "Movie.2160p.DV.HDR10.HEVC.mkv"))
    }

    @Test
    fun `no file name leaves what is playing`() {
        val info = uhd.copy(hdr = listOf("HDR10"), audioCodec = "TrueHD", audioChannels = 8)
        assertEquals(playbackMatchText(info, emptyList()), playerBadgeMatchText(info, emptyList(), "", "", true))
    }

    @Test
    fun `the stats file name from a link`() {
        assertEquals("The Movie.2160p.mkv", urlFileName("https://cdn.example.com/dl/abc/The%20Movie.2160p.mkv?token=1"))
        assertEquals(null, urlFileName("https://cdn.example.com/play/abc"))
        assertEquals(null, urlFileName(null))
    }
}
