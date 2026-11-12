package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerBuiltInBadgesTest {

    @Test
    fun `dolby vision remux with truehd atmos`() {
        val info = PlayerMediaInfo(
            videoWidth = 3840, videoHeight = 1600, audioCodec = "TrueHD", audioChannels = 8, hdr = listOf("DV", "HDR10")
        )
        assertEquals(
            listOf(
                R.drawable.badge_res_2160, R.drawable.badge_remux, R.drawable.badge_dv,
                R.drawable.badge_truehd, R.drawable.badge_atmos, R.drawable.badge_ch_7_1
            ),
            builtInPlayerBadges(info, "Film.2023.2160p.UHD.BluRay.REMUX.DV.TrueHD.Atmos.7.1-GRP.mkv")
        )
    }

    @Test
    fun `picture format follows the player, not the file name`() {
        val info = PlayerMediaInfo(videoWidth = 3840, videoHeight = 2160, hdr = listOf("HDR10"))
        assertEquals(
            listOf(R.drawable.badge_res_2160, R.drawable.badge_hdr10),
            builtInPlayerBadges(info, "Film 2160p DV HDR10")
        )
    }

    @Test
    fun `atmos only when the codec can carry it`() {
        val info = PlayerMediaInfo(videoWidth = 1920, videoHeight = 1080, audioCodec = "AAC", audioChannels = 2)
        assertEquals(listOf(R.drawable.badge_res_1080, R.drawable.badge_webdl), builtInPlayerBadges(info, "Show S01E01 WEB-DL Atmos"))
        val joc = PlayerMediaInfo(audioCodec = "E-AC-3-JOC", audioChannels = 6)
        assertEquals(listOf(R.drawable.badge_ddp, R.drawable.badge_atmos, R.drawable.badge_ch_5_1), builtInPlayerBadges(joc, ""))
    }

    @Test
    fun `dts profiles from mpv`() {
        val ma = PlayerMediaInfo(audioCodec = "DTS", audioProfiles = listOf("DTS-HD MA"), audioChannels = 6)
        assertEquals(listOf(R.drawable.badge_dts_hd_ma, R.drawable.badge_ch_5_1), builtInPlayerBadges(ma, ""))
        val core = PlayerMediaInfo(audioCodec = "DTS", audioChannels = 6)
        assertEquals(listOf(R.drawable.badge_dts, R.drawable.badge_ch_5_1), builtInPlayerBadges(core, ""))
    }

    @Test
    fun `dts-hd ma from the release name whatever the separator`() {
        val exo = PlayerMediaInfo(audioCodec = "DTS-HD", audioChannels = 6)
        listOf(
            "Film.2022.2160p.UHD.BluRay.REMUX.HDR.HEVC.DTS-HD.MA.5.1-GRP",
            "Film 2022 2160p UHD BluRay REMUX HDR HEVC DTS-HD MA 5.1",
            "Film.2019.1080p.BluRay.REMUX.AVC.DTS-HD-MA.5.1-GRP",
            "Film 2019 1080p BluRay DTSHD MA 5.1 x264",
            "Film.2019.1080p.BluRay.x264.DTS-HDMA.5.1",
            "Film (2019) 1080p BluRay REMUX DTS-HD Master Audio 5.1",
            "\uD83C\uDFA7 DTS-HD \u00B7 MA | \uD83D\uDD0A 5.1"
        ).forEach { name ->
            assertEquals(name, listOf(R.drawable.badge_dts_hd_ma, R.drawable.badge_ch_5_1), builtInPlayerBadges(exo, name).takeLast(2))
        }
        assertEquals(
            listOf(R.drawable.badge_dts_hd, R.drawable.badge_ch_5_1),
            builtInPlayerBadges(exo, "Film.2019.1080p.BluRay.DTS-HD.HRA.5.1-GRP").takeLast(2)
        )
        assertEquals(
            listOf(R.drawable.badge_dts_hd, R.drawable.badge_ch_5_1),
            builtInPlayerBadges(exo, "Film.2019.1080p.BluRay.DTS-HD.5.1-Mammoth").takeLast(2)
        )
    }

    @Test
    fun `atmos from the release name whatever the separator`() {
        val truehd = PlayerMediaInfo(audioCodec = "TrueHD", audioChannels = 8)
        listOf(
            "Film.2023.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.Atmos.7.1-GRP",
            "Film 2023 2160p UHD BluRay REMUX TrueHD Atmos 7.1",
            "Film.2023.2160p.BluRay.TrueHD.7.1.Atmos-GRP",
            "Film.2023.2160p.BluRay.TrueHDAtmos.7.1-GRP"
        ).forEach { name ->
            assertEquals(name, listOf(R.drawable.badge_truehd, R.drawable.badge_atmos, R.drawable.badge_ch_7_1), builtInPlayerBadges(truehd, name).takeLast(3))
        }
        val eac3 = PlayerMediaInfo(audioCodec = "E-AC-3", audioChannels = 6)
        listOf(
            "Show.S01E01.2160p.WEB-DL.DDP5.1.Atmos.DV.H.265-GRP",
            "Show S01E01 2160p WEB-DL DDP 5.1 Atmos H 265",
            "Show.S01E01.1080p.WEB-DL.DD+5.1.Atmos.H.264-GRP",
            "Show.S01E01.1080p.WEB-DL.E-AC-3.5.1.Atmos-GRP",
            "Show.S01E01.1080p.WEB-DL.DDPAtmos.5.1-GRP"
        ).forEach { name ->
            assertEquals(name, listOf(R.drawable.badge_ddp, R.drawable.badge_atmos, R.drawable.badge_ch_5_1), builtInPlayerBadges(eac3, name).takeLast(3))
        }
        assertEquals(
            listOf(R.drawable.badge_ddp, R.drawable.badge_ch_5_1),
            builtInPlayerBadges(eac3, "Show.S01E01.Atmosphere.1080p.WEB-DL.DDP5.1-GRP").takeLast(2)
        )
    }

    @Test
    fun `nothing known gives no badges`() {
        assertEquals(emptyList<Int>(), builtInPlayerBadges(PlayerMediaInfo(), ""))
    }

    @Test
    fun `atmos from the release name skips a track it does not speak for`() {
        val info = PlayerMediaInfo(audioCodec = "E-AC-3", audioChannels = 2)
        val release = "Film.2160p.WEB-DL.DDP5.1.Atmos.H.265-GRP"
        assertEquals(true, R.drawable.badge_atmos in builtInPlayerBadges(info, release))
        assertEquals(false, R.drawable.badge_atmos in builtInPlayerBadges(info, release, audioFromRelease = false))
        assertEquals(true, R.drawable.badge_webdl in builtInPlayerBadges(info, release, audioFromRelease = false))
    }

    @Test
    fun `atmos from the mpv audio profile`() {
        val info = PlayerMediaInfo(audioCodec = "TrueHD", audioProfiles = listOf("Dolby TrueHD + Dolby Atmos"))
        assertEquals(true, R.drawable.badge_atmos in builtInPlayerBadges(info, ""))
    }
}
