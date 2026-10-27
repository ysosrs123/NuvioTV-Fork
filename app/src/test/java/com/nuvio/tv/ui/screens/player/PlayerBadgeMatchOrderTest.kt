package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class PlayerBadgeMatchOrderTest {
    // The ch-71 and ch-51 filters of kingsizew colored_outline_badges.json, verbatim.
    private val channels71 = "(?i)(?:^(?=[\\s\\S]*(?:\u2063\u200c\u200c\u200c\u200d\u200c\u200d\u200c\u200d\u2064|\u2063\u200c\u200c\u200c\u200d\u200c\u200d\u200d\u200c\u2064|\u2063\u200c\u200c\u200c\u200d\u200c\u200d\u200d\u200d\u2064|\u2063\u200c\u200c\u200c\u200d\u200d\u200c\u200c\u200c\u2064))(?=[\\s\\S]*(?:\u2063\u200c\u200c\u200c\u200d\u200c\u200d\u200c\u200d\u2064|(?<![0-9])(?:7[-_. ]1(?:ch)?|8ch)(?![0-9])(?!\\s*(?:kbps|mbps|gbps|kb/s|mb/s|gb/s|kb|mb|gb|mib|gib))))[\\s\\S]*\$|^(?![\\s\\S]*(?:\u2063\u200c\u200c\u200c\u200d\u200c\u200d\u200c\u200d\u2064|\u2063\u200c\u200c\u200c\u200d\u200c\u200d\u200d\u200c\u2064|\u2063\u200c\u200c\u200c\u200d\u200c\u200d\u200d\u200d\u2064|\u2063\u200c\u200c\u200c\u200d\u200d\u200c\u200c\u200c\u2064))(?:^(?:(?=[\\s\\S]*\\.(?:mkv|mp4|avi|m2ts|ts|wmv|mov|m4v|mpg|mpeg|webm|iso)\\s+\\S)|(?!(?:[\\s\\S]*\\.(?:mkv|mp4|avi|m2ts|ts|wmv|mov|m4v|mpg|mpeg|webm|iso)))(?=[\\s\\S]*(?:^|[^A-Za-z0-9])(?:(?:4320|2160|1440|1080|720|576|480)[pi]?|[48]k|uhd|fhd)(?=\$|[^A-Za-z0-9])[\\s\\S]*(?:^|[^A-Za-z0-9])(?:(?:4320|2160|1440|1080|720|576|480)[pi]?|[48]k|uhd|fhd)(?=\$|[^A-Za-z0-9])))(?=[\\s\\S]*(?:^|[^0-9])(?:7[._ -]1(?:ch)?|8ch)(?!\\s*(?:kbps|mbps|gbps|kb/s|mb/s|gb/s|kb|mb|gb|mib|gib))(?=\$|[^0-9]))))"
    private val channels51 = "(?i)(?:^(?=[\\s\\S]*(?:\u2063\u200c\u200c\u200c\u200d\u200c\u200d\u200c\u200d\u2064|\u2063\u200c\u200c\u200c\u200d\u200c\u200d\u200d\u200c\u2064|\u2063\u200c\u200c\u200c\u200d\u200c\u200d\u200d\u200d\u2064|\u2063\u200c\u200c\u200c\u200d\u200d\u200c\u200c\u200c\u2064))(?=[\\s\\S]*(?:\u2063\u200c\u200c\u200c\u200d\u200c\u200d\u200d\u200d\u2064|(?<![0-9])(?:5[-_. ]1(?:ch)?|6ch)(?![0-9])(?!\\s*(?:kbps|mbps|gbps|kb/s|mb/s|gb/s|kb|mb|gb|mib|gib))))(?![\\s\\S]*(?:(?:\u2063\u200c\u200c\u200c\u200d\u200c\u200d\u200c\u200d\u2064|(?<![0-9])(?:7[-_. ]1(?:ch)?|8ch)(?![0-9])(?!\\s*(?:kbps|mbps|gbps|kb/s|mb/s|gb/s|kb|mb|gb|mib|gib)))|(?:\u2063\u200c\u200c\u200c\u200d\u200c\u200d\u200d\u200c\u2064|(?<![0-9])6[-_. ]1(?:ch)?(?![0-9])(?!\\s*(?:kbps|mbps|gbps|kb/s|mb/s|gb/s|kb|mb|gb|mib|gib)))))[\\s\\S]*\$|^(?![\\s\\S]*(?:\u2063\u200c\u200c\u200c\u200d\u200c\u200d\u200c\u200d\u2064|\u2063\u200c\u200c\u200c\u200d\u200c\u200d\u200d\u200c\u2064|\u2063\u200c\u200c\u200c\u200d\u200c\u200d\u200d\u200d\u2064|\u2063\u200c\u200c\u200c\u200d\u200d\u200c\u200c\u200c\u2064))(?:^(?:(?=[\\s\\S]*\\.(?:mkv|mp4|avi|m2ts|ts|wmv|mov|m4v|mpg|mpeg|webm|iso)\\s+\\S)|(?!(?:[\\s\\S]*\\.(?:mkv|mp4|avi|m2ts|ts|wmv|mov|m4v|mpg|mpeg|webm|iso)))(?=[\\s\\S]*(?:^|[^A-Za-z0-9])(?:(?:4320|2160|1440|1080|720|576|480)[pi]?|[48]k|uhd|fhd)(?=\$|[^A-Za-z0-9])[\\s\\S]*(?:^|[^A-Za-z0-9])(?:(?:4320|2160|1440|1080|720|576|480)[pi]?|[48]k|uhd|fhd)(?=\$|[^A-Za-z0-9])))(?![\\s\\S]*(?:^|[^0-9])(?:7[._ -]1(?:ch)?|8ch|6[._ -]1(?:ch)?|7ch)(?!\\s*(?:kbps|mbps|gbps|kb/s|mb/s|gb/s|kb|mb|gb|mib|gib))(?=\$|[^0-9]))(?=[\\s\\S]*(?:^|[^0-9])(?:5[._ -]1(?:ch)?|6ch)(?!\\s*(?:kbps|mbps|gbps|kb/s|mb/s|gb/s|kb|mb|gb|mib|gib))(?=\$|[^0-9]))))"

    private val file = "Avengers.Age.of.Ultron.2015.UHD.BluRay.2160p.TrueHD.Atmos.7.1.DV.HEVC.HYBRID.REMUX-FraMeSToR.mkv"
    private val base = PlayerMediaInfo(videoCodec = "HEVC", videoWidth = 3840, videoHeight = 2160, hdr = listOf("DV"), hdrKnown = true, bitDepth = 10)

    @Test
    fun `on the ac-3 track 5-1 matches and 7-1 does not`() {
        val info = base.copy(audioCodec = "AC-3", audioChannels = 6)
        val text = playerBadgeMatchText(info, emptyList(), file, file, audioFromRelease = true)
        assertTrue("5.1 matches in: $text", Pattern.compile(channels51).matcher(text).find())
        assertFalse("7.1 does not match in: $text", Pattern.compile(channels71).matcher(text).find())
    }

    @Test
    fun `on the truehd track 7-1 matches`() {
        val info = base.copy(audioCodec = "TrueHD", audioChannels = 8)
        val text = playerBadgeMatchText(info, emptyList(), file, file, audioFromRelease = true)
        assertTrue("7.1 matches in: $text", Pattern.compile(channels71).matcher(text).find())
    }
}
