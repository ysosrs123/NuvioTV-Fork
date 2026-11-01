package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import com.nuvio.tv.core.streams.StreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgeImport
import com.nuvio.tv.core.streams.StreamBadgeMatcher
import com.nuvio.tv.core.streams.StreamBadgeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class PlayerBadgeWhatPlaysTest {
    private fun filter(name: String, pattern: String) =
        StreamBadgeFilter(name = name, pattern = pattern, imageURL = "https://example.com/$name.png")

    // A simple pack: one rule per badge, none of them aware of the others.
    private val pack = StreamBadgeMatcher.compile(
        StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.com/simple.json",
                    filters = listOf(
                        filter("Remux", """(?i)\bremux\b"""),
                        filter("WEB-DL", """(?i)\bweb[ ._-]?dl\b"""),
                        filter("BluRay", """(?i)\bblu[ ._-]?ray\b"""),
                        filter("4K", """(?i)\b(2160p|4k|uhd)\b"""),
                        filter("1080p", """(?i)\b1080p\b"""),
                        filter("DV", """(?i)\b(dv|dovi|dolby[ ._-]?vision)\b"""),
                        filter("HDR10+", """(?i)\bhdr10(\+|plus)"""),
                        filter("HDR10", """(?i)\bhdr10\b(?!\+)"""),
                        filter("HDR", """(?i)\bhdr\b"""),
                        filter("SDR", """(?i)\bsdr\b"""),
                        filter("HEVC", """(?i)\b(hevc|[xh][ .]?265)\b"""),
                        filter("AVC", """(?i)\b(avc|[xh][ .]?264)\b"""),
                        filter("10-bit", """(?i)\b10[ -]?bit\b"""),
                        filter("TrueHD", """(?i)\btrue[ .]?hd\b"""),
                        filter("DD+", """(?i)(ddp|dd\+|e-?ac-?3)"""),
                        filter("DD", """(?i)\b(dd|ac-?3|dolby[ .]?digital)\b"""),
                        filter("Atmos", """(?i)\batmos\b"""),
                        filter("7.1", """\b7[ .]1\b"""),
                        filter("5.1", """\b5[ .]1\b""")
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

    private val mobLand = "MobLand S02E07 Ace of Spades 2160p ATV WEB-DL DDP5 1 Atmos DV HDR H 265-FLUX.mkv"

    // What the AM9 reported: Dolby Vision profile 8 out, E-AC-3 (no JOC flag) 6 channels.
    private val mobLandInfo = PlayerMediaInfo(
        videoCodec = "HEVC", videoWidth = 3840, videoHeight = 1600, audioCodec = "E-AC-3", audioChannels = 6,
        hdr = listOf("DV", "HDR10"), hdrKnown = true, bitDepth = 10
    )

    @Test
    fun `mobland reads as it plays`() {
        assertEquals(listOf("WEB-DL", "4K", "DV", "HEVC", "10-bit", "DD+", "Atmos", "5.1"), names(mobLandInfo, mobLand))
    }

    @Test
    fun `mobland with the kingsizew picture and audio rules`() {
        val text = playerBadgeMatchText(mobLandInfo, emptyList(), mobLand, mobLand, audioFromRelease = true)
        assertTrue("a-dv finds in: $text", Pattern.compile(KW_DV).matcher(text).find())
        assertFalse("v-dv-hdr10 does not in: $text", Pattern.compile(KW_DV_HDR10).matcher(text).find())
        assertTrue("a-atmos-ddplus finds in: $text", Pattern.compile(KW_ATMOS_DDPLUS).matcher(text).find())
        assertFalse("a-dd does not in: $text", Pattern.compile(KW_DD).matcher(text).find())
    }

    @Test
    fun `the file name's own words for what plays are gone`() {
        val file = playbackFileName(mobLand, mobLandInfo)
        listOf("DDP5", "Atmos", "DV", "HDR", "265").forEach { assertFalse("$it left in: $file", it in file) }
        listOf("MobLand", "S02E07", "2160p", "ATV", "WEB-DL", "FLUX.mkv").forEach { assertTrue("$it gone from: $file", it in file) }
    }

    @Test
    fun `dolby digital plus is spelt so plain dolby digital rules miss it`() {
        val playing = playbackMatchText(mobLandInfo, emptyList(), mobLand).split(" ")
        assertFalse(playing.any { "AC3" in it.uppercase() || "AC-3" in it.uppercase() || it.startsWith("DD+") })
        assertFalse(playing.any { it.equals("HDR", true) || it.startsWith("HDR10") })
        assertTrue("DDP5.1" in playing)
    }

    @Test
    fun `a dolby vision file played as hdr10 reads hdr10 only`() {
        val file = "Movie.2023.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.7.1.Atmos-FraMeSToR.mkv"
        val info = PlayerMediaInfo(
            videoCodec = "HEVC", videoWidth = 3840, videoHeight = 2160, audioCodec = "TrueHD", audioChannels = 8,
            hdr = listOf("HDR10"), hdrKnown = true, bitDepth = 10
        )
        assertEquals(listOf("Remux", "BluRay", "4K", "HDR10", "HEVC", "10-bit", "TrueHD", "Atmos", "7.1"), names(info, file))
    }

    @Test
    fun `truehd atmos 7-1 remux`() {
        val file = "Oppenheimer.2023.1080p.BluRay.REMUX.AVC.TrueHD.Atmos.7.1-FraMeSToR.mkv"
        val info = PlayerMediaInfo(
            videoCodec = "AVC", videoWidth = 1920, videoHeight = 1080, audioCodec = "TrueHD", audioChannels = 8,
            hdrKnown = true, bitDepth = 8
        )
        assertEquals(listOf("Remux", "BluRay", "1080p", "SDR", "AVC", "TrueHD", "Atmos", "7.1"), names(info, file))
    }

    @Test
    fun `hdr10+ from the name while hdr10 plays`() {
        val file = "Movie.2160p.WEB-DL.HDR10+.HEVC.DDP5.1-GRP.mkv"
        val info = PlayerMediaInfo(
            videoCodec = "HEVC", videoWidth = 3840, videoHeight = 2160, audioCodec = "E-AC-3", audioChannels = 6,
            hdr = listOf("HDR10"), hdrKnown = true, bitDepth = 10
        )
        assertEquals(listOf("WEB-DL", "4K", "HDR10+", "HEVC", "10-bit", "DD+", "5.1"), names(info, file))
    }

    @Test
    fun `before the tracks are read the file name speaks`() {
        val shown = names(PlayerMediaInfo(), mobLand)
        assertTrue(shown.containsAll(listOf("WEB-DL", "4K", "DV", "HEVC", "DD+", "Atmos")))
    }

    @Test
    fun `atmos from the track without atmos in the name`() {
        val info = mobLandInfo.copy(audioCodec = "E-AC-3-JOC")
        val file = "Show.S01E01.2160p.WEB-DL.DDP5.1.DV.H.265-GRP.mkv"
        assertTrue("Atmos" in names(info, file))
        assertFalse("Atmos" in names(mobLandInfo, file))
    }

    @Test
    fun `built-in badges for mobland`() {
        assertEquals(
            listOf(
                R.drawable.badge_res_2160, R.drawable.badge_webdl, R.drawable.badge_dv,
                R.drawable.badge_ddp, R.drawable.badge_atmos, R.drawable.badge_ch_5_1
            ),
            builtInPlayerBadges(mobLandInfo, mobLand)
        )
    }

    private companion object {
        // The a-dv, v-dv-hdr10, a-atmos-ddplus and a-dd filters of kingsizew colored_outline_badges.json, verbatim.
        const val KW_DV = "(?is)^(?=.*?(?:(?:(?<![a-z0-9])(?:dv|dovi|dolby[-_. ]?vision)(?![a-z0-9]))))(?:(?=.*\\.(?:mkv|mp4|avi|m2ts|webm|mov|m4v)\\s+\\S)|(?=(?:.*(?:2160|1440|1080|720|576|480)[pi]?){2})|(?=.*[\\r\\n])|(?=^[a-f0-9]{40,64}\\s+\\S))(?!.*(?:(?:(?:(?<![a-z0-9])(?:hdr[-_. ]?10(?:[-_. ]?(?:\\+|p)|[-_. ]?plus))(?![a-z0-9]))|(?:(?<![a-z0-9])(?:hdr[-_. ]?10(?![-_. ]?(?:\\+|p(?:lus)?|bit|hi)))(?![a-z0-9]))|(?:(?<![a-z0-9])(?:hdr(?![-_. ]?(?:10(?![-_. ]?bit)|\\+|p(?:lus)?)))(?![a-z0-9])))))"
        const val KW_DV_HDR10 = "(?is)^(?=.*?(?:(?:(?<![a-z0-9])(?:dv|dovi|dolby[-_. ]?vision)(?![a-z0-9]))))(?=.*?(?:(?:(?<![a-z0-9])(?:hdr[-_. ]?10(?![-_. ]?(?:\\+|p(?:lus)?|bit|hi)))(?![a-z0-9]))))(?:(?=.*\\.(?:mkv|mp4|avi|m2ts|webm|mov|m4v)\\s+\\S)|(?=(?:.*(?:2160|1440|1080|720|576|480)[pi]?){2})|(?=.*[\\r\\n])|(?=^[a-f0-9]{40,64}\\s+\\S))(?!.*(?:(?:(?:(?<![a-z0-9])(?:hdr[-_. ]?10(?:[-_. ]?(?:\\+|p)|[-_. ]?plus))(?![a-z0-9])))))"
        const val KW_ATMOS_DDPLUS = "(?is)^(?=.*?(?:(?:(?<![a-z0-9])(?:atmos|ddpa\\d?)(?![a-z0-9]))))(?=.*?(?:(?:(?<![a-z0-9])(?:ddp(?:a|lus)?\\d*|dd\\+|e[-_. ]?ac[-_. ]?3|dolby[-_. ]?digital[-_. ]?(?:plus|\\+))(?![a-z0-9]))))(?:(?=.*\\.(?:mkv|mp4|avi|m2ts|webm|mov|m4v)\\s+\\S)|(?=(?:.*(?:2160|1440|1080|720|576|480)[pi]?){2})|(?=.*[\\r\\n])|(?=^[a-f0-9]{40,64}\\s+\\S))(?!.*(?:(?:(?:(?<![a-z0-9])(?:true[-_. ]?hd)(?![a-z0-9])))))"
        const val KW_DD = "(?is)^(?=.*?(?:(?:(?<![a-z0-9])(?:dd(?!p|\\+)|(?<!e[-_. ]?)ac[-_. ]?3|dolby[-_. ]?digital(?![-_. ]?(?:plus|\\+)))(?![a-z0-9]))))(?:(?=.*\\.(?:mkv|mp4|avi|m2ts|webm|mov|m4v)\\s+\\S)|(?=(?:.*(?:2160|1440|1080|720|576|480)[pi]?){2})|(?=.*[\\r\\n])|(?=^[a-f0-9]{40,64}\\s+\\S))(?!.*(?:(?:(?:(?<![a-z0-9])(?:atmos|ddpa\\d?)(?![a-z0-9]))|(?:(?<![a-z0-9])(?:true[-_. ]?hd)(?![a-z0-9]))|(?:(?<![a-z0-9])(?:ddp(?:a|lus)?\\d*|dd\\+|e[-_. ]?ac[-_. ]?3|dolby[-_. ]?digital[-_. ]?(?:plus|\\+))(?![a-z0-9])))))(?!(?=.*?(?:(?:(?:(?<![a-z0-9])(?:dts(?:[-_: ]?x|[.]x)(?!26[45]))(?![a-z0-9]))|(?:(?<![a-z0-9])(?:dts[-_. ]?(?:hd[-_. ]?)?(?:ma|master[-_. ]?audio)|dtshdma)(?![a-z0-9]))|(?:(?<![a-z0-9])(?:(?:dts[-_. ]?hd|dtshd)(?![-_. ]?(?:ma|master)))(?![a-z0-9]))|(?:(?<![a-z0-9])(?:dts[-_. ]?es|dtses)(?![a-z0-9]))|(?:(?<![a-z0-9])(?:dts(?![-_. :]?(?:x|hd|es|ma|master)))(?![a-z0-9])))))(?=.*?(?:(?:(?<![a-z0-9])(?:flac)(?![a-z0-9])))))"
    }
}
