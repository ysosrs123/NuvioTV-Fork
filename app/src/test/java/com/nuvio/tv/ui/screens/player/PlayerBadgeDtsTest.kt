package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.streams.StreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgeImport
import com.nuvio.tv.core.streams.StreamBadgeMatcher
import com.nuvio.tv.core.streams.StreamBadgeRules
import org.junit.Assert.assertEquals
import org.junit.Test

/** DTS tracks get one DTS badge, the most exact one, with rules that each keep to their own word. */
class PlayerBadgeDtsTest {
    private fun filter(name: String, pattern: String) =
        StreamBadgeFilter(name = name, pattern = pattern, imageURL = "https://example.com/$name.png")

    private val pack = StreamBadgeMatcher.compile(
        StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.com/dts.json",
                    filters = listOf(
                        filter("DTS:X", """(?i)(?<![a-z0-9])dts(?:[-_: ]?x|[.]x)(?![a-z0-9])"""),
                        filter("DTS-HD MA", """(?i)(?<![a-z0-9])(?:dts[-_. ]?(?:hd[-_. ]?)?(?:ma|master[-_. ]?audio)|dtshdma)(?![a-z0-9])"""),
                        filter("DTS-HD", """(?i)(?<![a-z0-9])(?:dts[-_. ]?hd|dtshd)(?![-_. ]?(?:ma|master))(?![a-z0-9])"""),
                        filter("DTS", """(?i)(?<![a-z0-9])dts(?![-_. :]?(?:x|hd|es|ma|master))(?![a-z0-9])"""),
                        filter("5.1", """\b5[ .]1\b"""),
                    )
                )
            )
        )
    )

    private val loose = StreamBadgeMatcher.compile(
        StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.com/loose.json",
                    filters = listOf(
                        filter("DTS-HD MA", """(?i)dts[-_. ]?hd[-_. ]?ma"""),
                        filter("DTS", """(?i)\bdts\b"""),
                    )
                )
            )
        )
    )

    private fun names(info: PlayerMediaInfo, file: String): List<String> {
        val playing = playerBadgePlayingText(info, emptyList(), file, file, audioFromRelease = true)
        val text = playerBadgeMatchText(info, emptyList(), file, file, audioFromRelease = true)
        return StreamBadgeMatcher.matchedPlayerBadges(playing, listOf(text), pack).map { it.name }
    }

    @Test
    fun `dts-hd master audio shows one dts badge`() {
        val info = PlayerMediaInfo(audioCodec = "DTS-HD", audioChannels = 6)
        assertEquals(listOf("DTS-HD MA", "5.1"),
            names(info, "The.Pianist.2002.2160p.Blu-ray.Remux.DV.HDR.DTS-HD.MA.5.1-GRP.mkv"))
    }

    @Test
    fun `dts-hd without master audio shows dts-hd`() {
        val info = PlayerMediaInfo(audioCodec = "DTS-HD", audioChannels = 6)
        assertEquals(listOf("DTS-HD", "5.1"), names(info, "Film.2010.1080p.BluRay.DTS-HD.5.1-GRP.mkv"))
    }

    @Test
    fun `plain dts shows dts`() {
        val info = PlayerMediaInfo(audioCodec = "DTS", audioChannels = 6)
        assertEquals(listOf("DTS", "5.1"), names(info, "Film.2010.1080p.BluRay.DTS.5.1-GRP.mkv"))
    }

    @Test
    fun `dts-x shows dts-x with master audio`() {
        val info = PlayerMediaInfo(audioCodec = "DTS-HD", audioChannels = 8)
        assertEquals(listOf("DTS:X", "DTS-HD MA"), names(info, "Film.2019.2160p.UHD.BluRay.Remux.DTS-X.7.1-GRP.mkv"))
    }

    @Test
    fun `a loose dts rule is left out next to dts-hd ma`() {
        val info = PlayerMediaInfo(audioCodec = "DTS-HD", audioChannels = 6)
        val file = "The.Pianist.2002.2160p.Blu-ray.Remux.DV.HDR.DTS-HD.MA.5.1-CiNEPHiLES.mkv"
        val playing = playerBadgePlayingText(info, emptyList(), file, file, audioFromRelease = true)
        val text = playerBadgeMatchText(info, emptyList(), file, file, audioFromRelease = true)
        assertEquals(listOf("DTS-HD MA"), StreamBadgeMatcher.matchedPlayerBadges(playing, listOf(text), loose).map { it.name })
    }

    @Test
    fun `the stream list and details page show one dts badge too`() {
        val file = "The.Pianist.2002.2160p.Blu-ray.Remux.DV.HDR.DTS-HD.MA.5.1-GRP.mkv"
        assertEquals(listOf("DTS-HD MA"), StreamBadgeMatcher.matchedBadges(listOf(file), loose).map { it.name })
        val stream = com.nuvio.tv.domain.model.Stream(
            name = "4K", title = file, description = null, url = "https://example.com/a.mkv", ytId = null,
            infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "Addon", addonLogo = null,
        )
        assertEquals(listOf("DTS-HD MA"),
            StreamBadgeMatcher.matchedBadgesPooled(stream, loose, HashMap()).map { it.name })
    }
}
